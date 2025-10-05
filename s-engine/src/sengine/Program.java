package sengine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Program model + expansion logic (degree 0/1).
 *
 * For תרגיל 2 we also support a small function library (parsed from <S-Functions>).
 * Two synthetic forms are expanded here at degree=1:
 *   - QUOTE:   QUOTE <dst> <- Func(<args...>)
 *   - JEF:     JUMP_EQUAL_FUNCTION <var> == Func(<args...>) GOTO <label|EXIT>
 *
 * The function body is inlined with simple variable substitution:
 *   - y inside the function is rewritten to the caller's destination (<dst>).
 *   - x1,x2,... inside the function are bound to the evaluated argument values.
 *     If an argument itself is a function call, it is evaluated first into a fresh zK
 *     scratch variable, and that zK is bound to the corresponding xN.
 *   - z* used inside the function are renamed to fresh zK to avoid collisions.
 *   - Labels Lk inside the function are remapped to unique L(K+offset) to avoid collisions.
 *
 * Degree 0 returns the program as-is. Degree 1 returns the expanded, inlined form.
 * (All function bodies are turned into basic instructions by the parser already.)
 */
public final class Program {
    public final String name;
    public final List<Instruction> instructions;

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // Optional function library (name -> body as instructions parsed with our normal parser)
    public final Map<String, List<Instruction>> functions;

    public Program(String name, List<Instruction> ins) {
        this(name, ins, Map.of());
    }

    public Program(String name, List<Instruction> ins, Map<String, List<Instruction>> functions) {
        this.name = name;
        this.instructions = List.copyOf(ins);
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
    }

    public boolean hasSynthetic() {
        return instructions.stream().anyMatch(i -> !i.basic);
    }
    public int maxExpansionDegree() { return hasSynthetic() ? 1 : 0; }
    /** Back-compat for existing GUI code that calls maxDegree(). */
    public int maxDegree() {
        return maxExpansionDegree();
    }

    // ---------- Rendering / expansion ----------

    public Rendered expandToDegree(int degree) {
        if (degree <= 0) return render(instructions, Map.of());
        List<Instruction> flat = new ArrayList<>();
        Map<Integer, Instruction> originMap = new HashMap<>();
        int n = 0;

        for (Instruction i : instructions) {
            if (i.basic) {
                flat.add(i);
                originMap.put(++n, null); // no synthetic parent
                continue;
            }

            // Try to expand the synthetic ourselves if it is one of our known macros.
            List<Instruction> expanded = tryExpandKnownSynthetic(i);
            if (expanded == null || expanded.isEmpty()) {
                // Unknown synthetic - keep as-is so the UI still shows something
                flat.add(i);
                originMap.put(++n, null);
            } else {
                for (Instruction x : expanded) {
                    flat.add(x);
                    originMap.put(++n, i); // ancestry: child came from i
                }
            }
        }
        return render(flat, originMap);
    }

    private List<Instruction> tryExpandKnownSynthetic(Instruction inst) {
        // We rely on the parser to set a readable text like:
        //   QUOTE y <- Func(...)
        //   JUMP_EQUAL_FUNCTION x1 == Func(...) GOTO L7
        String t = inst.text == null ? "" : inst.text.trim();

        Matcher m;
        if ((m = RX_QUOTE.matcher(t)).matches()) {
            String dst = m.group(1);
            String func = m.group(2);
            String argStr = m.group(3) == null ? "" : m.group(3).trim();
            return expandQuote(dst, func, argStr);

        } else if ((m = RX_JEF.matcher(t)).matches()) {
            String var   = m.group(1);
            String func  = m.group(2);
            String argStr= m.group(3) == null ? "" : m.group(3).trim();
            String label = m.group(4);
            return expandJumpEqualFunction(var, func, argStr, label);
        }
        return null;
    }

    // QUOTE <dst> <- Func(<args...>)
    private List<Instruction> expandQuote(String dstVar, String funcName, String argsStr) {
        List<Instruction> out = new ArrayList<>();
        // Evaluate the call into the destination variable directly.
        evalFuncInto(VariableRef.parse(dstVar), funcName, parseArgs(argsStr), out, new Scratch());
        return out;
    }

    // JUMP_EQUAL_FUNCTION <var> == Func(<args...>) GOTO <label>
    private List<Instruction> expandJumpEqualFunction(String var, String funcName, String argsStr, String target) {
        List<Instruction> out = new ArrayList<>();
        Scratch scratch = new Scratch();
        VariableRef tmp = VariableRef.parse("z" + scratch.nextZ());
        // compute Func(args) into tmp
        evalFuncInto(tmp, funcName, parseArgs(argsStr), out, scratch);
        // if var == tmp goto target
        String cmd = "IF " + var + " == " + tmp.name() + " GOTO " + target;
        out.add(Instruction.parseFromText(null, cmd, "B", null));
        return out;
    }

    // Evaluate a function call into a target variable (y/xk/zk)
    private void evalFuncInto(VariableRef target, String funcName, List<Arg> args, List<Instruction> out, Scratch scratch) {
        List<Instruction> body = functions.get(funcName);
        if (body == null)
            throw new IllegalStateException("Unknown function: " + funcName);

        // Bind x1..xN
        Map<String, String> varMap = new HashMap<>();
        // y inside function -> target
        varMap.put("y", target.name());

        int idx = 1;
        for (Arg a : args) {
            String formal = "x" + idx++;
            if (a instanceof VarArg va) {
                varMap.put(formal, va.v.name());
            } else if (a instanceof CallArg ca) {
                VariableRef tmp = VariableRef.parse("z" + scratch.nextZ());
                evalFuncInto(tmp, ca.func, ca.args, out, scratch); // emit code
                varMap.put(formal, tmp.name());
            }
        }

        // Remap internal z* to fresh ones
        Map<String,String> zMap = new HashMap<>();
        // Label remapping to avoid collisions
        Map<String,String> labelMap = new HashMap<>();
        int labelBase = scratch.nextLabelBase();

        for (Instruction fi : body) {
            // Build a textual command with substituted variables & labels
            String newLabel = null;
            if (fi.label != null && !fi.label.isBlank()) {
                newLabel = remapLabel(fi.label, labelBase, labelMap);
            }

            String cmdText = fi.text;
            // Substitute variables token-wise: y, x\d+, z\d+
            cmdText = substituteVariables(cmdText, varMap, zMap, scratch);
            // Substitute labels in GOTO / IF ... GOTO ...
            cmdText = substituteLabelsInText(cmdText, labelBase, labelMap);

            Instruction ni = Instruction.parseFromText(newLabel, cmdText, "B", null);
            out.add(ni);
        }
    }

    private static String substituteVariables(String text, Map<String,String> varMap, Map<String,String> zMap, Scratch scratch) {
        // replace y / x\d+ / z\d+ tokens that appear as standalone identifiers
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                int j = i+1;
                while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)))) j++;
                String tok = text.substring(i, j);
                String repl = null;
                if (tok.equals("y")) repl = varMap.getOrDefault("y", "y");
                else if (tok.matches("x\\d+")) repl = varMap.getOrDefault(tok, tok);
                else if (tok.matches("z\\d+")) {
                    repl = zMap.computeIfAbsent(tok, k -> "z" + scratch.nextZ());
                }
                sb.append(repl == null ? tok : repl);
                i = j;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String substituteLabelsInText(String text, int labelBase, Map<String,String> labelMap) {
        Pattern p = Pattern.compile("L(\\d+)");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String old = "L" + m.group(1);
            String repl = remapLabel(old, labelBase, labelMap);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String remapLabel(String old, int base, Map<String,String> map) {
        if (old == null) return null;
        String key = old.toUpperCase(Locale.ROOT);
        if (key.equals("EXIT")) return "EXIT";
        if (!key.matches("L\\d+")) return key;
        return map.computeIfAbsent(key, k -> {
            int n = Integer.parseInt(k.substring(1));
            return "L" + (base + n);
        });
    }

    // ---------- Argument parsing ----------

    private static final Pattern RX_QUOTE = Pattern.compile(
            "^QUOTE\\s+([xyz]\\d*|y)\\s*<-\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RX_JEF = Pattern.compile(
            "^JUMP_EQUAL_FUNCTION\\s+([xyz]\\d*|y)\\s*==\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*GOTO\\s*(EXIT|L\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static List<Arg> parseArgs(String s) {
        List<Arg> out = new ArrayList<>();
        if (s == null) return out;
        String str = s.trim();
        if (str.isEmpty()) return out;

        int i = 0;
        while (i < str.length()) {
            // skip commas
            while (i < str.length() && (str.charAt(i) == ',' || Character.isWhitespace(str.charAt(i)))) i++;
            if (i >= str.length()) break;

            if (str.charAt(i) == '(') {
                // parse a call: (FuncName, [args...])
                int start = ++i;
                int depth = 1;
                StringBuilder buf = new StringBuilder();
                while (i < str.length() && depth > 0) {
                    char ch = str.charAt(i);
                    if (ch == '(') { depth++; }
                    else if (ch == ')') { depth--; if (depth==0) break; }
                    buf.append(ch);
                    i++;
                }
                // buf now contains "FuncName, maybeArgs"
                String inside = buf.toString().trim();
                String func = inside;
                String innerArgs = "";
                int comma = inside.indexOf(',');
                if (comma >= 0) {
                    func = inside.substring(0, comma).trim();
                    innerArgs = inside.substring(comma+1).trim();
                }
                out.add(new CallArg(func, parseArgs(innerArgs)));
                // skip the closing ')'
                if (i < str.length() && str.charAt(i) == ')') i++;
                // skip a trailing comma if exists
                if (i < str.length() && str.charAt(i) == ',') i++;
            } else {
                // variable name token
                int j = i+1;
                while (j < str.length() && !Character.isWhitespace(str.charAt(j)) && str.charAt(j) != ',') j++;
                String tok = str.substring(i, j).trim();
                out.add(new VarArg(VariableRef.parse(tok)));
                i = j;
                if (i < str.length() && str.charAt(i) == ',') i++;
            }
        }
        return out;
    }

    // ---------- Render helpers (unchanged) ----------

    private Rendered render(List<Instruction> list, Map<Integer, Instruction> originMap) {
        Map<String,Integer> labelToIndex = new HashMap<>();
        for (int i=0;i<list.size();i++) {
            String lbl = list.get(i).label;
            if (lbl != null && !lbl.isBlank()) labelToIndex.putIfAbsent(lbl.toUpperCase(Locale.ROOT), i);
        }
        List<String> lines = new ArrayList<>();
        List<List<String>> chains = new ArrayList<>();
        int sumCycles = 0;
        for (int i=0;i<list.size();i++) {
            Instruction inst = list.get(i);
            int num = i+1;
            lines.add(inst.formatLine(num));
            sumCycles += inst.cycles();

            List<String> chain = new ArrayList<>();
            Instruction parent = originMap.get(num);
            while (parent != null) {
                chain.add(parent.formatLine(-1));
                parent = parent.origin;
            }
            chains.add(chain);
        }
        return new Rendered(name, list, labelToIndex, lines, chains, sumCycles);
    }

    public Set<VariableRef> referencedVariables() {
        Set<VariableRef> vars = new TreeSet<>();
        for (Instruction i : instructions) {
            for (String tok : i.text.replaceAll("[^A-Za-z0-9]", " ").split("\\s+")) {
                if (tok.isBlank()) continue;
                String low = tok.toLowerCase(Locale.ROOT);
                if (low.equals("y")) vars.add(VariableRef.y());
                else if (low.matches("x\\d+")) vars.add(VariableRef.parse(low));
                else if (low.matches("z\\d+")) vars.add(VariableRef.parse(low));
            }
        }
        return vars;
    }

    public static final class Rendered {
        public final String name;
        public final List<Instruction> list;
        public final Map<String,Integer> labelIndex;
        public final List<String> lines;
        public final List<List<String>> originChains;
        public final int sumCycles;
        Rendered(String name, List<Instruction> list, Map<String,Integer> labelIndex, List<String> lines,
                 List<List<String>> originChains, int sumCycles) {
            this.name = name; this.list = list; this.labelIndex = labelIndex; this.lines = lines;
            this.originChains = originChains; this.sumCycles = sumCycles;
        }
    }

    public Set<String> labelsUsed() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Instruction i : instructions) {
            if (i.label != null) {
                String lbl = i.label.trim();
                if (!lbl.isEmpty()) out.add(lbl);
            }
        }
        return out;
    }

    // ---------- Tiny arg & scratch helpers ----------

    private static abstract class Arg {}
    private static final class VarArg extends Arg {
        final VariableRef v;
        VarArg(VariableRef v) { this.v = v; }
    }
    private static final class CallArg extends Arg {
        final String func;
        final List<Arg> args;
        CallArg(String func, List<Arg> args) { this.func = func; this.args = args; }
    }

    private static final class Scratch {
        private int nextZ = 1;
        private int nextLabel = 1000;
        int nextZ() { return nextZ++; }
        int nextLabelBase() { int b = nextLabel; nextLabel += 100; return b; }
    }
}
