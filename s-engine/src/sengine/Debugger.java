package sengine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-step debugger over a rendered program. */
public final class Debugger {

    public static final class Snapshot {
        public final int pc;                 // 0-based; -1 when halted
        public final int cycles;
        public final boolean halted;
        public final LinkedHashMap<String,Integer> vars;
        public final Map<String,Integer> changed; // only vars changed by the *last* step
        Snapshot(int pc, int cycles, boolean halted,
                 LinkedHashMap<String,Integer> vars, Map<String,Integer> changed) {
            this.pc = pc; this.cycles = cycles; this.halted = halted;
            this.vars = vars; this.changed = changed;
        }
    }

    private final Program.Rendered rendered;
    private final LinkedHashMap<String,Integer> vars = new LinkedHashMap<>();
    private final Map<String,Integer> prevVars = new LinkedHashMap<>();

    private int pc = 0;         // 0-based
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
        // Build labels map
        for (int i=0;i<rendered.list.size();i++) {
            String lbl = rendered.list.get(i).label;
            if (lbl != null && !lbl.isBlank()) labelToIndex.put(lbl.toUpperCase(Locale.ROOT), i);
        }
    }

    public Program.Rendered rendered() { return rendered; }
    public Snapshot snapshot() { return new Snapshot(pc, cycles, halted, copy(vars), Map.of()); }

    /** Execute a single instruction; return post-step snapshot (with changed vars). */
    public Snapshot step() {
        if (halted || pc < 0 || pc >= rendered.list.size()) {
            halted = true;
            pc = -1;
            return new Snapshot(pc, cycles, true, copy(vars), Map.of());
        }

        Instruction inst = rendered.list.get(pc);
        String text = inst.text == null ? "" : inst.text.trim();

        // cycles
        cycles += Math.max(0, inst.cycles());

        prevVars.clear();
        prevVars.putAll(vars);

        // dispatch (same regex set as Runner)
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

        // Unknown → just step to avoid lockup
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

    // same regexes used by Runner
    private static final Pattern RX_GOTO = Pattern.compile("^GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_EQ_ZERO = Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*==\\s*0\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_NE_ZERO = Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*!=\\s*0\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_EQ_VAR  = Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*==\\s*([xyz]\\d*|y)\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_EQ_CONST= Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*==\\s*(\\d+)\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_INC        = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*\\1\\s*\\+\\s*1$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_DEC        = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*\\1\\s*-\\s*1$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_ASSIGN_VAR = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*([xyz]\\d*|y)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_ASSIGN_CONST=Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_ZERO       = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*0$", Pattern.CASE_INSENSITIVE);
}
