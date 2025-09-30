package sengine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Runner {

    public static final class RunResult {
        public final Program.Rendered rendered;
        public final int degree;
        public final int cycles;
        public final LinkedHashMap<String,Integer> variables;
        public final int y;

        RunResult(Program.Rendered rendered, int degree, int cycles, LinkedHashMap<String,Integer> vars) {
            this.rendered = rendered;
            this.degree = degree;
            this.cycles = cycles;
            this.variables = vars;
            this.y = vars.getOrDefault("y", 0);
        }
    }

    /** Run program at the given expansion degree with the given inputs (x1, x2, x3, ...). */
    public static RunResult run(Program program, int degree, List<Integer> inputs) {
        if (program == null) throw new IllegalArgumentException("Program is null");
        degree = Math.max(0, Math.min(degree, program.maxDegree()));
        Program.Rendered r = program.expandToDegree(degree);

        // Variables map (LinkedHashMap so we keep a nice display order)
        LinkedHashMap<String,Integer> vars = new LinkedHashMap<>();
        vars.put("y", 0);
        // preload inputs: x1, x2, ...
        if (inputs != null) {
            for (int i = 0; i < inputs.size(); i++) {
                vars.put("x" + (i + 1), Math.max(0, inputs.get(i)));
            }
        }

        int pc = 0;
        int cycles = 0;

        // Build label -> index map (already provided in Program.Rendered, but we re-use here)
        Map<String,Integer> labelToIndex = new HashMap<>();
        for (int i = 0; i < r.list.size(); i++) {
            String lbl = r.list.get(i).label;
            if (lbl != null && !lbl.isBlank()) {
                labelToIndex.put(lbl.toUpperCase(Locale.ROOT), i);
            }
        }

        while (pc >= 0 && pc < r.list.size()) {
            Instruction inst = r.list.get(pc);
            String text = inst.text == null ? "" : inst.text.trim();

            // Count cycles
            cycles += Math.max(0, inst.cycles());

            // 1) GOTO EXIT / GOTO Lk
            Matcher m;
            if ((m = RX_GOTO.matcher(text)).matches()) {
                String target = m.group(1).toUpperCase(Locale.ROOT);
                if (target.equals("EXIT")) break;
                Integer idx = labelToIndex.get(target);
                if (idx == null) throw new IllegalStateException("Unknown label: " + target);
                pc = idx;
                continue;
            }

            // 2) IF <var> == 0 GOTO ...
            if ((m = RX_IF_EQ_ZERO.matcher(text)).matches()) {
                String v = m.group(1);
                String target = m.group(2);
                if (get(vars, v) == 0) {
                    if (target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = labelToIndex.get(target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Unknown label: " + target);
                    pc = idx;
                    continue;
                } else {
                    pc++;
                    continue;
                }
            }

            // 3) IF <var> != 0 GOTO ...
            if ((m = RX_IF_NE_ZERO.matcher(text)).matches()) {
                String v = m.group(1);
                String target = m.group(2);
                if (get(vars, v) != 0) {
                    if (target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = labelToIndex.get(target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Unknown label: " + target);
                    pc = idx;
                    continue;
                } else {
                    pc++;
                    continue;
                }
            }

            // 4) IF <varA> == <varB> GOTO ...
            if ((m = RX_IF_EQ_VAR.matcher(text)).matches()) {
                String a = m.group(1);
                String b = m.group(2);
                String target = m.group(3);
                if (get(vars, a) == get(vars, b)) {
                    if (target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = labelToIndex.get(target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Unknown label: " + target);
                    pc = idx;
                    continue;
                } else {
                    pc++;
                    continue;
                }
            }

            // 5) IF <var> == CONST GOTO ...
            if ((m = RX_IF_EQ_CONST.matcher(text)).matches()) {
                String a = m.group(1);
                int c = Integer.parseInt(m.group(2));
                String target = m.group(3);
                if (get(vars, a) == c) {
                    if (target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = labelToIndex.get(target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Unknown label: " + target);
                    pc = idx;
                    continue;
                } else {
                    pc++;
                    continue;
                }
            }

            // 6) dst <- dst + 1
            if ((m = RX_INC.matcher(text)).matches()) {
                String dst = m.group(1);
                set(vars, dst, get(vars, dst) + 1);
                pc++;
                continue;
            }

            // 7) dst <- dst - 1
            if ((m = RX_DEC.matcher(text)).matches()) {
                String dst = m.group(1);
                set(vars, dst, Math.max(0, get(vars, dst) - 1));
                pc++;
                continue;
            }

            // 8) dst <- src
            if ((m = RX_ASSIGN_VAR.matcher(text)).matches()) {
                String dst = m.group(1);
                String src = m.group(2);
                set(vars, dst, get(vars, src));
                pc++;
                continue;
            }

            // 9) dst <- CONST
            if ((m = RX_ASSIGN_CONST.matcher(text)).matches()) {
                String dst = m.group(1);
                int c = Integer.parseInt(m.group(2));
                set(vars, dst, Math.max(0, c));
                pc++;
                continue;
            }

            // 10) dst <- 0 (covered by const case but keep for clarity)
            if ((m = RX_ZERO.matcher(text)).matches()) {
                String dst = m.group(1);
                set(vars, dst, 0);
                pc++;
                continue;
            }

            // If we reach here, we didn't recognize the line; just step to avoid infinite loop.
            pc++;
        }

        return new RunResult(r, degree, cycles, vars);
    }

    // ---------- Simple variable store helpers ----------
    private static int get(Map<String,Integer> vars, String name) {
        return vars.getOrDefault(name, 0);
    }
    private static void set(Map<String,Integer> vars, String name, int value) {
        vars.put(name, Math.max(0, value));
    }

    // ---------- Regex patterns for the supported textual forms ----------
    // GOTO
    private static final Pattern RX_GOTO = Pattern.compile("^GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);

    // IF var == 0 / != 0
    private static final Pattern RX_IF_EQ_ZERO = Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*==\\s*0\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_IF_NE_ZERO = Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*!=\\s*0\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);

    // IF varA == varB
    private static final Pattern RX_IF_EQ_VAR = Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*==\\s*([xyz]\\d*|y)\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);

    // IF var == CONST
    private static final Pattern RX_IF_EQ_CONST = Pattern.compile("^IF\\s+([xyz]\\d*|y)\\s*==\\s*(\\d+)\\s+GOTO\\s+(EXIT|L\\d+)$", Pattern.CASE_INSENSITIVE);

    // dst <- dst + 1
    private static final Pattern RX_INC = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*\\1\\s*\\+\\s*1$", Pattern.CASE_INSENSITIVE);

    // dst <- dst - 1
    private static final Pattern RX_DEC = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*\\1\\s*-\\s*1$", Pattern.CASE_INSENSITIVE);

    // dst <- src
    private static final Pattern RX_ASSIGN_VAR = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*([xyz]\\d*|y)$", Pattern.CASE_INSENSITIVE);

    // dst <- CONST
    private static final Pattern RX_ASSIGN_CONST = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);

    // dst <- 0
    private static final Pattern RX_ZERO = Pattern.compile("^([xyz]\\d*|y)\\s*<-\\s*0$", Pattern.CASE_INSENSITIVE);
}
