package sengine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class Debugger {

    public static final class Snapshot {
        public final int pc;
        public final int cycles;
        public final boolean halted;
        public final LinkedHashMap<String,Integer> vars;
        public final Map<String,Integer> changed;
        Snapshot(int pc, int cycles, boolean halted,
                 LinkedHashMap<String,Integer> vars, Map<String,Integer> changed) {
            this.pc = pc; this.cycles = cycles; this.halted = halted;
            this.vars = vars; this.changed = changed;
        }
    }

    private final Program.Rendered rendered;
    private final LinkedHashMap<String,Integer> vars = new LinkedHashMap<>();
    private final Map<String,Integer> prevVars = new LinkedHashMap<>();

    private int pc = 0;
    private int cycles = 0;
    private boolean halted = false;
    private final Map<String,Integer> labelToIndex = new HashMap<>();

    public Debugger(Program program, int degree, List<Integer> inputs) {
        degree = Math.max(0, Math.min(degree, program.maxDegree()));
        this.rendered = program.expandToDegree(degree);

        vars.put("y", 0);
        if (inputs != null) {
            for (int i = 0; i < inputs.size(); i++) vars.put("x"+(i+1), Math.max(0, inputs.get(i)));
        }

        for (int i=0;i<rendered.list.size();i++) {
            String lbl = rendered.list.get(i).label;
            if (lbl != null && !lbl.isBlank()) labelToIndex.put(lbl.toUpperCase(Locale.ROOT), i);
        }
    }

    public Program.Rendered rendered() { return rendered; }
    public Snapshot snapshot() { return new Snapshot(pc, cycles, halted, copy(vars), Map.of()); }


    public Snapshot step() {
        if (halted || pc < 0 || pc >= rendered.list.size()) {
            halted = true;
            pc = -1;
            return new Snapshot(pc, cycles, true, copy(vars), Map.of());
        }

        Instruction inst = rendered.list.get(pc);
        String text = inst.text == null ? "" : inst.text.trim();


        cycles += Math.max(0, inst.cycles());

        prevVars.clear();
        prevVars.putAll(vars);


        Matcher m;

        if ((m = RX_GOTO.matcher(text)).matches()) {
            String target = m.group(1).toUpperCase(Locale.ROOT);
            if (target.equals("EXIT")) { halted = true; pc = -1; }
            else pc = mustFind(target);
            return snapshotChanged();
        }
        if ((m = RX_IF_EQ_ZERO.matcher(text)).matches()) {
            String v = m.group(1); String target = m.group(2);
            if (get(v) == 0) jump(target); else pc++;
            return snapshotChanged();
        }
        if ((m = RX_IF_NE_ZERO.matcher(text)).matches()) {
            String v = m.group(1); String target = m.group(2);
            if (get(v) != 0) jump(target); else pc++;
            return snapshotChanged();
        }
        if ((m = RX_IF_EQ_VAR.matcher(text)).matches()) {
            String a = m.group(1), b = m.group(2), target = m.group(3);
            if (get(a) == get(b)) jump(target); else pc++;
            return snapshotChanged();
        }
        if ((m = RX_IF_EQ_CONST.matcher(text)).matches()) {
            String a = m.group(1); int c = Integer.parseInt(m.group(2)); String target = m.group(3);
            if (get(a) == c) jump(target); else pc++;
            return snapshotChanged();
        }
        if ((m = RX_INC.matcher(text)).matches()) {
            String dst = m.group(1); set(dst, get(dst)+1); pc++; return snapshotChanged();
        }
        if ((m = RX_DEC.matcher(text)).matches()) {
            String dst = m.group(1); set(dst, Math.max(0, get(dst)-1)); pc++; return snapshotChanged();
        }
        if ((m = RX_ASSIGN_VAR.matcher(text)).matches()) {
            String dst = m.group(1), src = m.group(2); set(dst, get(src)); pc++; return snapshotChanged();
        }
        if ((m = RX_ASSIGN_CONST.matcher(text)).matches()) {
            String dst = m.group(1); int c = Integer.parseInt(m.group(2)); set(dst, Math.max(0,c)); pc++; return snapshotChanged();
        }
        if ((m = RX_ZERO.matcher(text)).matches()) {
            String dst = m.group(1); set(dst, 0); pc++; return snapshotChanged();
        }


        if ((m = RX_QUOTE.matcher(text)).matches()) {
            String dst = m.group(1);
            String expr = m.group(2);
            int val = evalExpr(expr);
            set(dst, val);
            pc++;
            return snapshotChanged();
        }


        if ((m = RX_JEF.matcher(text)).matches()) {
            String v = m.group(1);
            String expr = m.group(2);
            String target = m.group(3);
            int val = evalExpr(expr);
            if (get(v) == val) jump(target); else pc++;
            return snapshotChanged();
        }


        pc++;
        return snapshotChanged();
    }

    private void jump(String target) {
        if (target.equalsIgnoreCase("EXIT")) { halted = true; pc = -1; }
        else pc = mustFind(target.toUpperCase(Locale.ROOT));
    }

    private int mustFind(String labelUpper) {
        Integer idx = labelToIndex.get(labelUpper);
        if (idx == null) throw new IllegalStateException("Unknown label: " + labelUpper);
        return idx;
    }

    private Snapshot snapshotChanged() {
        Map<String,Integer> changed = new LinkedHashMap<>();
        for (var e : vars.entrySet()) {
            Integer before = prevVars.get(e.getKey());
            if (before == null || !before.equals(e.getValue())) changed.put(e.getKey(), e.getValue());
        }
        return new Snapshot(pc, cycles, halted, copy(vars), changed);
    }

    private int get(String name) { return vars.getOrDefault(name, 0); }
    private void set(String name, int v) { vars.put(name, Math.max(0, v)); }
    private static LinkedHashMap<String,Integer> copy(LinkedHashMap<String,Integer> m) {
        return new LinkedHashMap<>(m);
    }



    private static final Pattern RX_CALL = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*)\\((.*)\\)$");

    private int evalExpr(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;


        Matcher call = RX_CALL.matcher(t);
        if (call.matches()) {
            String fname = call.group(1);
            String args = call.group(2);
            List<String> parts = splitTopLevel(args);
            List<Integer> evals = new ArrayList<>(parts.size());
            for (String p : parts) evals.add(evalExpr(p));
            return apply(fname, evals);
        }


        if (t.charAt(0) == '(' && t.charAt(t.length()-1) == ')') {
            String inner = t.substring(1, t.length()-1).trim();
            if (!inner.isEmpty()) {
                List<String> parts = splitTopLevel(inner);
                if (!parts.isEmpty()) {
                    String fname = parts.get(0).trim();
                    List<Integer> evals = new ArrayList<>();
                    for (int i = 1; i < parts.size(); i++) evals.add(evalExpr(parts.get(i)));
                    return apply(fname, evals);
                }
            }
            return 0;
        }


        if (t.chars().allMatch(Character::isDigit)) {
            try { return Integer.parseInt(t); } catch (Exception ignore) { return 0; }
        }


        if (Character.isLetter(t.charAt(0))) {
            return get(t);
        }

        return 0;
    }


    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; cur.append(c); }
            else if (c == ')') { depth--; cur.append(c); }
            else if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        if (out.size() == 1 && out.get(0).isEmpty()) return List.of();
        return out;
    }


    private int apply(String name, List<Integer> a) {
        String n = name.trim();


        if (n.equalsIgnoreCase("CONST0")) return 0;


        if (n.equalsIgnoreCase("Successor")) return Math.max(0, (a.size() >= 1 ? a.get(0) : 0) + 1);


        if (n.equalsIgnoreCase("Minus")) return Math.max(0, (a.size() >= 1 ? a.get(0) : 0) - (a.size() >= 2 ? a.get(1) : 0));


        if (n.equalsIgnoreCase("Smaller_Than")) return (a.size() >= 2 && a.get(0) < a.get(1)) ? 1 : 0;


        if (n.equalsIgnoreCase("Bigger_Equal_Than")) return (a.size() >= 2 && a.get(0) >= a.get(1)) ? 1 : 0;


        if (n.equalsIgnoreCase("Smaller_Equal_Than")) return (a.size() >= 2 && a.get(0) <= a.get(1)) ? 1 : 0;


        if (n.equalsIgnoreCase("NOT")) return (a.size() >= 1 && a.get(0) == 0) ? 1 : 0;


        if (n.equalsIgnoreCase("EQUAL")) return (a.size() >= 2 && Objects.equals(a.get(0), a.get(1))) ? 1 : 0;


        if (n.equalsIgnoreCase("AND")) {
            for (int v : a) if (v == 0) return 0;
            return 1;
        }


        if (n.equalsIgnoreCase("OR")) {
            for (int v : a) if (v != 0) return 1;
            return 0;
        }


        return 0;
    }


    private static final String VAR = "([A-Za-z][A-Za-z0-9_]*)";
    private static final String LABEL = "(EXIT|L\\d+)";

    private static final Pattern RX_GOTO        = Pattern.compile("^GOTO\\s+" + LABEL + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_EQ_ZERO  = Pattern.compile("^IF\\s+" + VAR + "\\s*==\\s*0\\s+GOTO\\s+" + LABEL + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_NE_ZERO  = Pattern.compile("^IF\\s+" + VAR + "\\s*!=\\s*0\\s+GOTO\\s+" + LABEL + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_EQ_VAR   = Pattern.compile("^IF\\s+" + VAR + "\\s*==\\s*" + VAR + "\\s+GOTO\\s+" + LABEL + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_EQ_CONST = Pattern.compile("^IF\\s+" + VAR + "\\s*==\\s*(\\d+)\\s+GOTO\\s+" + LABEL + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_INC         = Pattern.compile("^" + VAR + "\\s*<-\\s*\\1\\s*\\+\\s*1$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_DEC         = Pattern.compile("^" + VAR + "\\s*<-\\s*\\1\\s*-\\s*1$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_ASSIGN_VAR  = Pattern.compile("^" + VAR + "\\s*<-\\s*" + VAR + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_ASSIGN_CONST= Pattern.compile("^" + VAR + "\\s*<-\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_ZERO        = Pattern.compile("^" + VAR + "\\s*<-\\s*0$", Pattern.CASE_INSENSITIVE);

    private static final Pattern RX_QUOTE       = Pattern.compile("^QUOTE\\s+" + VAR + "\\s*<-\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_JEF         = Pattern.compile("^JUMP_EQUAL_FUNCTION\\s+" + VAR + "\\s*==\\s*(.+)\\s+GOTO\\s+" + LABEL + "$", Pattern.CASE_INSENSITIVE);
}
