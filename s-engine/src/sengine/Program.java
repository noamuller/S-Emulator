package sengine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Program model + expansion engine (תרגיל 2).
 *
 * Degree:
 *  - 0: keep synthetics (QUOTE / JUMP_EQUAL_FUNCTION)
 *  - 1: inline synthetics into basic instructions
 *
 * Fixes included:
 *  - Robust parsing of nested function args (tuple "(Fn,...)" and call "Fn(...)")
 *  - QUOTE/JEF allowed *inside function bodies*
 *  - Any EXIT inside inlined functions remapped to a per-call local "return" label
 *  - Back-compat for console App: Rendered now exposes name, lines, sumCycles
 */
public final class Program {

    // -------------------------
    // Rendered view (back-compat)
    // -------------------------
    public static final class Rendered {
        /** Program name (for console App printing). */
        public final String name;
        /** The instruction list to display/run (used by GUI). */
        public final List<Instruction> list;
        /** Origin info per row (used by GUI). Same size as {@link #list}. */
        public final List<List<String>> originChains;
        /** Pre-formatted lines (for console App printing). */
        public final List<String> lines;
        /** Sum of cycles over the listed instructions (for console App). */
        public final int sumCycles;

        public Rendered(String name, List<Instruction> list, List<List<String>> originChains) {
            this.name = name;
            this.list = list;
            this.originChains = originChains;
            this.lines = toLines(list);
            this.sumCycles = sumCyclesOf(list);
        }
    }

    // -------------------------
    // Fields & Constructors
    // -------------------------
    public final String name;
    public final List<Instruction> instructions;                 // degree-0 program body
    public final Map<String, List<Instruction>> functions;       // name -> textual body

    public Program(String name, List<Instruction> instructions) {
        this(name, instructions, new LinkedHashMap<>());
    }

    public Program(String name, List<Instruction> instructions,
                   Map<String, List<Instruction>> functions) {
        this.name = name;
        this.instructions = new ArrayList<>(instructions);
        this.functions = (functions == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(functions);
    }

    // -------------------------
    // Degree & Expansion API
    // -------------------------

    /** Maximum expansion degree (0 if no synthetics, 1 if any QUOTE/JEF exist). */
    public int maxDegree() {
        for (Instruction ins : instructions) {
            if (isSynthetic(ins)) return 1;
        }
        for (List<Instruction> body : functions.values()) {
            for (Instruction ins : body) {
                if (isSynthetic(ins)) return 1;
            }
        }
        return 0;
    }

    /** Expand to requested degree (clamped). */
    /** Expand to requested degree (clamped). */
    public Rendered expandToDegree(int degree) {
        int d = Math.max(0, Math.min(degree, maxDegree()));

        if (d == 0) {
            // Degree 0: return the original list, with a one-line origin per row.
            List<List<String>> chains = new ArrayList<>(instructions.size());
            for (Instruction ins : instructions) chains.add(List.of(renderOriginLine(ins)));
            return new Rendered(name,
                    Collections.unmodifiableList(instructions),
                    Collections.unmodifiableList(chains));
        }

        // Degree 1: inline synthetics. Also force EVERY remaining line to be BASIC (type "B").
        List<Instruction> out = new ArrayList<>();
        List<List<String>> chains = new ArrayList<>();

        for (Instruction ins : instructions) {
            List<Instruction> expanded = tryExpandKnownSynthetic(ins);
            if (expanded != null) {
                String origin = renderOriginLine(ins);
                List<String> chain = List.of(origin);
                for (Instruction e : expanded) {
                    // already created as "B" inside the expanders
                    out.add(e);
                    chains.add(chain);
                }
            } else {
                // Not a QUOTE/JEF → convert whatever it is (even if originally "S") to a BASIC line
                Instruction b = asBasic(ins);
                out.add(b);
                chains.add(List.of(renderOriginLine(ins)));
            }
        }
        return new Rendered(name,
                Collections.unmodifiableList(out),
                Collections.unmodifiableList(chains));
    }
    /** Convert any instruction to a BASIC instruction with the same label/text. */
    private static Instruction asBasic(Instruction src) {
        String lbl = (src == null) ? null : src.label;
        String txt = (src == null) ? ""   : src.text;
        return Instruction.parseFromText(lbl, txt, "B", cyclesFor(txt));
    }


    // Returns 2 for IF-lines, 1 otherwise.
    private static int cyclesFor(String text) {
        if (text == null) return 1;
        String t = text.trim().toUpperCase(java.util.Locale.ROOT);
        return t.startsWith("IF ") ? 2 : 1;
    }

    private static String renderOriginLine(Instruction ins) {
        String lbl = (ins.label == null || ins.label.isBlank()) ? "" : (ins.label + ": ");
        String txt = (ins.text == null) ? "" : ins.text;
        return (lbl + txt).trim();
    }

    // -------------------------
    // Recognize + expand synthetics
    // -------------------------

    private static final Pattern RX_QUOTE =
            Pattern.compile("^\\s*QUOTE\\s+([A-Za-z]\\d*|y)\\s*<-\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*$");

    private static final Pattern RX_JEF =
            Pattern.compile("^\\s*JUMP_EQUAL_FUNCTION\\s+([A-Za-z]\\d*|y)\\s*==\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*GOTO\\s+([A-Za-z0-9_]+|EXIT)\\s*$");

    private boolean isSynthetic(Instruction ins) {
        String t = (ins == null || ins.text == null) ? "" : ins.text.trim();
        return t.startsWith("QUOTE ") || t.startsWith("JUMP_EQUAL_FUNCTION ");
    }

    private List<Instruction> tryExpandKnownSynthetic(Instruction ins) {
        String text = (ins.text == null) ? "" : ins.text;

        Matcher m = RX_QUOTE.matcher(text);
        if (m.matches()) {
            String dst  = m.group(1);
            String name = m.group(2);
            String args = m.group(3);
            return expandQuote(dst, name, args);
        }

        Matcher j = RX_JEF.matcher(text);
        if (j.matches()) {
            String var   = j.group(1);
            String func  = j.group(2);
            String args  = j.group(3);
            String label = j.group(4);
            return expandJumpEqualFunction(var, func, args, label);
        }

        return null; // not synthetic
    }

    // -------------------------
    // QUOTE / JEF expansion
    // -------------------------

    /** Expand a QUOTE dst <- Func(args) into basic instructions. */
    private List<Instruction> expandQuote(String dst, String name, String argsStr) {
        Scratch scratch = new Scratch();
        List<Instruction> out = new ArrayList<>();
        evalFuncInto(VariableRef.parse(dst), name, parseArgs(argsStr), out, scratch);
        return out;
    }

    /** Expand JUMP_EQUAL_FUNCTION var == Func(args) GOTO label into basic instructions. */
// Program.java — replace the whole method
    private List<Instruction> expandJumpEqualFunction(String var, String func, String argsStr, String label) {
        Scratch scratch = new Scratch();
        List<Instruction> out = new ArrayList<>();

        // Evaluate Func(args) into a fresh temp (all emitted as BASIC instructions)
        VariableRef tmp = VariableRef.parse("z" + scratch.nextZ());
        evalFuncInto(tmp, func, parseArgs(argsStr), out, scratch);

        // Final IF compares <var> to the computed temp and jumps;
        // emit explicitly as BASIC with proper cycles.
        String line = "IF " + var + " == " + tmp.name() + " GOTO " + label;
        out.add(Instruction.parseFromText(null, line, "B", cyclesFor(line)));

        return out;
    }


    /**
     * Inline a function call into 'out', with proper variable/label remapping and EXIT handling.
     *  - y in callee → 'target'
     *  - x1..xN in callee → bound to caller args (Var or nested call)
     *  - zN in callee → unique zk per inlining frame (no collisions)
     *  - L#### labels remapped by adding a per-call base
     *  - EXIT in callee → per-call local "return" label (L<labelBase+99>) placed after the inlined body
     */
    private void evalFuncInto(VariableRef target, String funcName, List<Arg> args,
                              List<Instruction> out, Scratch scratch) {

        List<Instruction> body = functions.get(funcName);
        if (body == null) {
            throw new IllegalStateException("Unknown function: " + funcName);
        }

        // Bind y and x1..xN
        Map<String, String> varMap = new HashMap<>();
        varMap.put("y", target.name());

        int idx = 1;
        for (Arg a : args) {
            String formal = "x" + idx++;
            if (a instanceof VarArg va) {
                varMap.put(formal, va.v.name());
            } else if (a instanceof CallArg ca) {
                VariableRef tmp = VariableRef.parse("z" + scratch.nextZ());
                evalFuncInto(tmp, ca.func, ca.args, out, scratch);     // inline nested call
                varMap.put(formal, tmp.name());
            }
        }

        // Fresh locals/labels for this inlining frame
        Map<String,String> zMap     = new HashMap<>();
        Map<String,String> labelMap = new HashMap<>();
        int labelBase = scratch.nextLabelBase(); // e.g., 1000, 2000, 3000,...

        for (Instruction fi : body) {
            // 1) remap label definition if present
            String newLabel = null;
            if (fi.label != null && !fi.label.isBlank()) {
                newLabel = remapLabel(fi.label, labelBase, labelMap);
            }

            // 2) substitute variables and remap labels (including EXIT) within the text
            String cmdText = (fi.text == null) ? "" : fi.text;
            cmdText = substituteVariables(cmdText, varMap, zMap, scratch);   // xk, y, zk
            cmdText = substituteLabelsInText(cmdText, labelBase, labelMap);  // Lk, EXIT

            // 3) allow QUOTE / JEF *inside* function bodies
            Matcher mq = RX_QUOTE.matcher(cmdText);
            if (mq.matches()) {
                String dst     = mq.group(1);
                String innerFn = mq.group(2);
                String inner   = mq.group(3);
                evalFuncInto(VariableRef.parse(dst), innerFn, parseArgs(inner), out, scratch);
                continue;
            }

            Matcher mj = RX_JEF.matcher(cmdText);
            if (mj.matches()) {
                String v       = mj.group(1);
                String innerFn = mj.group(2);
                String inner   = mj.group(3);
                String tgt     = mj.group(4);
                List<Instruction> blk = expandJumpEqualFunction(v, innerFn, inner, tgt);
                // Remap labels/EXIT on the final IF line of the JEF block to this frame's local mapping
                if (!blk.isEmpty()) {
                    Instruction tail = blk.get(blk.size() - 1);
                    String remapped = substituteLabelsInText(tail.text, labelBase, labelMap);
                    blk.set(blk.size() - 1, Instruction.parseFromText(tail.label, remapped, "B", cyclesFor(remapped)));

                }
                out.addAll(blk);
                continue;
            }

            // 4) default: treat as a basic instruction
            out.add(Instruction.parseFromText(newLabel, cmdText, "B", cyclesFor(cmdText)));

        }

        // 5) ensure the local return label exists right after the inlined body
        String localExit = "L" + (labelBase + 99);
        out.add(Instruction.parseFromText(localExit, target.name() + " <- " + target.name(), "B", 1));
    }

    // -------------------------
    // Variable + label rewriting
    // -------------------------

    private static String substituteVariables(String text,
                                              Map<String,String> varMap,
                                              Map<String,String> zMap,
                                              Scratch scratch) {
        if (text == null || text.isBlank()) return text;

        // Replace y and x\d+
        Pattern pXY = Pattern.compile("\\b(y|x\\d+)\\b");
        Matcher mXY = pXY.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (mXY.find()) {
            String tok = mXY.group(1);
            String rep = varMap.get(tok);
            if (rep == null) rep = tok; // unknown xN → keep
            mXY.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        mXY.appendTail(sb);
        String afterXY = sb.toString();

        // Replace z\d+ with per-frame unique z's
        Pattern pZ = Pattern.compile("\\bz(\\d+)\\b");
        Matcher mZ = pZ.matcher(afterXY);
        StringBuffer sb2 = new StringBuffer();
        while (mZ.find()) {
            String old = "z" + mZ.group(1);
            String rep = zMap.computeIfAbsent(old, k -> "z" + scratch.nextZ());
            mZ.appendReplacement(sb2, Matcher.quoteReplacement(rep));
        }
        mZ.appendTail(sb2);

        return sb2.toString();
    }

    /** Remap label tokens in text: L123 → L(base+123), EXIT → L(base+99). */
    private static String substituteLabelsInText(String text, int labelBase, Map<String,String> labelMap) {
        if (text == null || text.isBlank()) return text;

        // Remap L<digits>
        Pattern p = Pattern.compile("L(\\d+)");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String old = "L" + m.group(1);
            String repl = remapLabel(old, labelBase, labelMap);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);

        // Remap EXIT to a per-call local label
        Pattern p2 = Pattern.compile("\\bEXIT\\b", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(sb.toString());
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            String repl = remapLabel("EXIT", labelBase, labelMap);
            m2.appendReplacement(sb2, Matcher.quoteReplacement(repl));
        }
        m2.appendTail(sb2);

        return sb2.toString();
    }

    /** Map Lk → L(base+k); EXIT → L(base+99). */
    private static String remapLabel(String old, int base, Map<String,String> map) {
        if (old == null) return null;
        String key = old.toUpperCase(Locale.ROOT);

        if (key.equals("EXIT")) return "L" + (base + 99);

        if (!key.matches("L\\d+")) return old; // not an L#### token → leave as-is

        return map.computeIfAbsent(key, k -> {
            int n = Integer.parseInt(k.substring(1));
            return "L" + (base + n);
        });
    }

    // -------------------------
    // Argument node hierarchy
    // -------------------------
    private sealed interface Arg permits VarArg, CallArg {}
    private static final class VarArg implements Arg {
        final VariableRef v;
        VarArg(VariableRef v) { this.v = v; }
    }
    private static final class CallArg implements Arg {
        final String func;
        final List<Arg> args;
        CallArg(String func, List<Arg> args) { this.func = func; this.args = args; }
    }

    // -------------------------
    // Robust argument parsing
    // -------------------------

    /**
     * Parse a comma-separated argument list into Arg nodes.
     * Supports both:
     *  - tuple form:  (FuncName, arg1, arg2, ...)
     *  - call form:   FuncName(arg1, arg2, ...)
     * And nested combinations like:
     *  (Bigger_Equal_Than,z3,x2),(NOT,(EQUAL,z3,(CONST0)))
     */
    private List<Arg> parseArgs(String s) {
        if (s == null) return List.of();
        String src = s.trim();
        if (src.isEmpty()) return List.of();

        List<String> items = splitTopLevelByComma(src);
        List<Arg> out = new ArrayList<>();

        for (String raw : items) {
            String it = raw.trim();
            if (it.isEmpty()) continue;

            // (1) Tuple form: (FuncName, args...)
            if (it.charAt(0) == '(') {
                int match = matchingParenPos(it, 0);
                if (match == it.length() - 1) {
                    String inner = it.substring(1, match).trim();
                    int cut = firstTopLevelComma(inner);
                    String name = (cut < 0 ? inner : inner.substring(0, cut)).trim();
                    String rest = (cut < 0 ? ""    : inner.substring(cut + 1)).trim();
                    out.add(new CallArg(name, rest.isEmpty() ? List.of() : parseArgs(rest)));
                    continue;
                }
            }

            // (2) Call form: FuncName(...)
            int lp = it.indexOf('(');
            if (lp > 0 && it.endsWith(")")) {
                int match = matchingParenPos(it, lp);
                if (match == it.length() - 1) {
                    String name  = it.substring(0, lp).trim();
                    String inner = it.substring(lp + 1, match).trim();
                    out.add(new CallArg(name, inner.isEmpty() ? List.of() : parseArgs(inner)));
                    continue;
                }
            }

            // (3) Variable form
            out.add(new VarArg(VariableRef.parse(it)));
        }
        return out;
    }

    /** Split a string on commas that are at top level (not inside parentheses). */
    private static List<String> splitTopLevelByComma(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; cur.append(c); }
            else if (c == ')') { depth--; cur.append(c); }
            else if (c == ',' && depth == 0) { out.add(cur.toString()); cur.setLength(0); }
            else { cur.append(c); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Return index of matching ')' for '(' at openPos; -1 if not found. */
    private static int matchingParenPos(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** First comma at top level (depth==0) or -1 if none. */
    private static int firstTopLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    // -------------------------
    // Scratch: fresh z / label bases
    // -------------------------

    /** Per-expansion scratch counters to avoid collisions. */
    private static final class Scratch {
        private int zCounter = 1;
        private int nextLabelBase = 1000; // grows by 1000 each call frame

        int nextZ() { return zCounter++; }

        /** Returns a fresh base (1000, 2000, 3000, ...) for remapping L#### labels per call frame. */
        int nextLabelBase() {
            int base = nextLabelBase;
            nextLabelBase += 1000;
            return base;
        }
    }

    // -------------------------
    // Helpers for console App
    // -------------------------

    private static List<String> toLines(List<Instruction> list) {
        List<String> out = new ArrayList<>(list.size());
        int i = 1;
        for (Instruction ins : list) {
            String lbl = (ins.label == null || ins.label.isBlank()) ? "" : (ins.label + ": ");
            String txt = (ins.text == null) ? "" : ins.text;
            out.add(String.format("%3d  %s%s", i++, lbl, txt));
        }
        return out;
    }
    private static int inferCycles(Instruction ins) {
        if (ins == null || ins.text == null) return 1;
        String t = ins.text.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.startsWith("IF ")) return 2;
        return 1;
    }

    // Sum cycles without relying on a field on Instruction.
// Heuristic: IF ... costs 2, everything else costs 1.
    private static int sumCyclesOf(List<Instruction> list) {
        int sum = 0;
        for (Instruction ins : list) {
            sum += inferCycles(ins);
        }
        return sum;
    }

}
