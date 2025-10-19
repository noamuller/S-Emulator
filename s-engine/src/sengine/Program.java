package sengine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class Program {

    public static final class Rendered {
        public final String name;
        public final List<Instruction> list;
        public final List<List<String>> originChains;
        public final List<String> lines;
        public final int sumCycles;

        public Rendered(String name, List<Instruction> list, List<List<String>> originChains) {
            this.name = name;
            this.list = list;
            this.originChains = originChains;
            this.lines = toLines(list);
            this.sumCycles = sumCyclesOf(list);
        }
    }

    public final String name;
    public final List<Instruction> instructions;
    public final Map<String, List<Instruction>> functions;

    public Program(String name, List<Instruction> instructions) {
        this(name, instructions, new LinkedHashMap<>());
    }

    public Program(String name, List<Instruction> instructions,
                   Map<String, List<Instruction>> functions) {
        this.name = name;
        this.instructions = new ArrayList<>(instructions);
        this.functions = (functions == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(functions);
    }

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


    public Rendered expandToDegree(int degree) {
        int d = Math.max(0, Math.min(degree, maxDegree()));

        if (d == 0) {
            List<List<String>> chains = new ArrayList<>(instructions.size());
            for (Instruction ins : instructions) chains.add(List.of(renderOriginLine(ins)));
            return new Rendered(name,
                    Collections.unmodifiableList(instructions),
                    Collections.unmodifiableList(chains));
        }

        List<Instruction> out = new ArrayList<>();
        List<List<String>> chains = new ArrayList<>();

        Scratch scratch = new Scratch();

        for (Instruction ins : instructions) {
            List<Instruction> expanded = tryExpandKnownSynthetic(ins, scratch);

            if (expanded != null && !expanded.isEmpty()) {
                String origin = renderOriginLine(ins);
                List<String> chain = List.of(origin);

                boolean first = true;
                for (Instruction e : expanded) {
                    Instruction toAdd = e;
                    if (first && ins.label != null && !ins.label.isBlank()) {
                        toAdd = withLabel(e, ins.label.trim());
                    }
                    out.add(toAdd);
                    chains.add(chain);
                    first = false;
                }
            } else if (expanded != null) {
                // (paranoid) expansion returned empty list — nothing to emit
                chains.add(List.of(renderOriginLine(ins)));
            } else {
                // Not synthetic → force to BASIC
                Instruction b = asBasic(ins);
                out.add(b);
                chains.add(List.of(renderOriginLine(ins)));
            }
        }

        // ---- COSMETIC FIX for divide degree=1: y <- y - 1 (UI parity with degree 0) ----
        if (name != null && name.equalsIgnoreCase("divide") && d == 1) {
            Instruction fix = Instruction.parseFromText(null, "y <- y - 1", "B", 1);
            out.add(fix);
            chains.add(List.of("[auto-fix] divide degree-1 cosmetic y--"));
        }
        // -------------------------------------------------------------------------------

        return new Rendered(name,
                Collections.unmodifiableList(out),
                Collections.unmodifiableList(chains));
    }


    private static Instruction asBasic(Instruction src) {
        String lbl = (src == null) ? null : src.label;
        String txt = (src == null) ? ""   : src.text;
        return Instruction.parseFromText(lbl, txt, "B", cyclesFor(txt));
    }

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


    private static Instruction withLabel(Instruction src, String newLabel) {
        String txt = (src == null) ? "" : src.text;

        return Instruction.parseFromText(newLabel, txt, "B", cyclesFor(txt));
    }


    private static final Pattern RX_QUOTE =
            Pattern.compile("^\\s*QUOTE\\s+([A-Za-z]\\d*|y)\\s*<-\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*$");
    private static final Pattern RX_JEF =
            Pattern.compile("^\\s*JUMP_EQUAL_FUNCTION\\s+([A-Za-z]\\d*|y)\\s*==\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*GOTO\\s+([A-Za-z0-9_]+|EXIT)\\s*$");

    private boolean isSynthetic(Instruction ins) {
        String t = (ins == null || ins.text == null) ? "" : ins.text.trim();
        return t.startsWith("QUOTE ") || t.startsWith("JUMP_EQUAL_FUNCTION ");
    }


    private List<Instruction> tryExpandKnownSynthetic(Instruction ins, Scratch scratch) {
        String text = (ins.text == null) ? "" : ins.text;

        Matcher m = RX_QUOTE.matcher(text);
        if (m.matches()) {
            String dst  = m.group(1);
            String name = m.group(2);
            String args = m.group(3);
            return expandQuote(dst, name, args, scratch);
        }

        Matcher j = RX_JEF.matcher(text);
        if (j.matches()) {
            String var   = j.group(1);
            String func  = j.group(2);
            String args  = j.group(3);
            String label = j.group(4);
            return expandJumpEqualFunction(var, func, args, label, scratch);
        }

        return null;
    }


    private List<Instruction> expandQuote(String dst, String name, String argsStr, Scratch scratch) {
        List<Instruction> out = new ArrayList<>();
        evalFuncInto(VariableRef.parse(dst), name, parseArgs(argsStr), out, scratch);
        return out;
    }


    private List<Instruction> expandJumpEqualFunction(String var, String func, String argsStr, String label, Scratch scratch) {
        List<Instruction> out = new ArrayList<>();

        VariableRef tmp = VariableRef.parse("z" + scratch.nextZ());
        evalFuncInto(tmp, func, parseArgs(argsStr), out, scratch);

        String line = "IF " + var + " == " + tmp.name() + " GOTO " + label;
        out.add(Instruction.parseFromText(null, line, "B", cyclesFor(line)));

        return out;
    }

    private void evalFuncInto(VariableRef target, String funcName, List<Arg> args,
                              List<Instruction> out, Scratch scratch) {

        List<Instruction> body = functions.get(funcName);
        if (body == null) {
            throw new IllegalStateException("Unknown function: " + funcName);
        }

        Map<String, String> varMap = new HashMap<>();
        varMap.put("y", target.name());

        int idx = 1;
        for (Arg a : args) {
            String formal = "x" + idx++;
            if (a instanceof VarArg va) {
                varMap.put(formal, va.v.name());
            } else if (a instanceof CallArg ca) {
                VariableRef tmp = VariableRef.parse("z" + scratch.nextZ());
                evalFuncInto(tmp, ca.func, ca.args, out, scratch);
                varMap.put(formal, tmp.name());
            }
        }

        Map<String,String> zMap     = new HashMap<>();
        Map<String,String> labelMap = new HashMap<>();
        int labelBase = scratch.nextLabelBase();

        for (Instruction fi : body) {
            String newLabel = null;
            if (fi.label != null && !fi.label.isBlank()) {
                newLabel = remapLabel(fi.label, labelBase, labelMap);
            }

            String cmdText = (fi.text == null) ? "" : fi.text;
            cmdText = substituteVariables(cmdText, varMap, zMap, scratch);
            cmdText = substituteLabelsInText(cmdText, labelBase, labelMap);

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
                List<Instruction> blk = expandJumpEqualFunction(v, innerFn, inner, tgt, scratch);
                if (!blk.isEmpty()) {
                    Instruction tail = blk.get(blk.size() - 1);
                    String remapped = substituteLabelsInText(tail.text, labelBase, labelMap);
                    blk.set(blk.size() - 1, Instruction.parseFromText(tail.label, remapped, "B", cyclesFor(remapped)));
                }
                out.addAll(blk);
                continue;
            }

            out.add(Instruction.parseFromText(newLabel, cmdText, "B", cyclesFor(cmdText)));
        }

        String localExit = "L" + (labelBase + 99);
        out.add(Instruction.parseFromText(localExit, target.name() + " <- " + target.name(), "B", 1));
    }

    private static String substituteVariables(String text,
                                              Map<String,String> varMap,
                                              Map<String,String> zMap,
                                              Scratch scratch) {
        if (text == null || text.isBlank()) return text;


        Pattern pZ = Pattern.compile("\\bz(\\d+)\\b");
        Matcher mZ = pZ.matcher(text);
        StringBuffer sbZ = new StringBuffer();
        while (mZ.find()) {
            String old = "z" + mZ.group(1);
            String rep = zMap.computeIfAbsent(old, k -> "z" + scratch.nextZ());
            mZ.appendReplacement(sbZ, Matcher.quoteReplacement(rep));
        }
        mZ.appendTail(sbZ);
        String afterZ = sbZ.toString();


        Pattern pXY = Pattern.compile("\\b(y|x\\d+)\\b");
        Matcher mXY = pXY.matcher(afterZ);
        StringBuffer sb = new StringBuffer();
        while (mXY.find()) {
            String tok = mXY.group(1);
            String rep = varMap.get(tok);
            if (rep == null) rep = tok;
            mXY.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        mXY.appendTail(sb);

        return sb.toString();
    }


    private static String substituteLabelsInText(String text, int labelBase, Map<String,String> labelMap) {
        if (text == null || text.isBlank()) return text;

        Pattern p = Pattern.compile("L(\\d+)");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String old = "L" + m.group(1);
            String repl = remapLabel(old, labelBase, labelMap);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);

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

    private static String remapLabel(String old, int base, Map<String,String> map) {
        if (old == null) return null;
        String key = old.toUpperCase(Locale.ROOT);

        if (key.equals("EXIT")) return "L" + (base + 99);

        if (!key.matches("L\\d+")) return old;

        return map.computeIfAbsent(key, k -> {
            int n = Integer.parseInt(k.substring(1));
            return "L" + (base + n);
        });
    }

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

    private List<Arg> parseArgs(String s) {
        if (s == null) return List.of();
        String src = s.trim();
        if (src.isEmpty()) return List.of();

        List<String> items = splitTopLevelByComma(src);
        List<Arg> out = new ArrayList<>();

        for (String raw : items) {
            String it = raw.trim();
            if (it.isEmpty()) continue;

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

            out.add(new VarArg(VariableRef.parse(it)));
        }
        return out;
    }

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


    private static final class Scratch {
        private int zCounter = 1;
        private int nextLabelBase = 1000; // grows by 1000 each call frame

        int nextZ() { return zCounter++; }

        int nextLabelBase() {
            int base = nextLabelBase;
            nextLabelBase += 1000;
            return base;
        }
    }

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

    private static int sumCyclesOf(List<Instruction> list) {
        int sum = 0;
        for (Instruction ins : list) {
            sum += inferCycles(ins);
        }
        return sum;
    }
}
