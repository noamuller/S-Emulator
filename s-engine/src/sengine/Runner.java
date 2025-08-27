package sengine;

import java.util.*;

public final class Runner {

    public static final class RunResult {
        public final int degree;
        public final List<Integer> inputs;
        public final int y;
        public final int cycles;
        public final LinkedHashMap<String,Integer> variables;
        public final Program.Rendered rendered;
        RunResult(int degree, List<Integer> inputs, int y, int cycles, LinkedHashMap<String,Integer> vars,
                  Program.Rendered rendered) {
            this.degree=degree; this.inputs=inputs; this.y=y; this.cycles=cycles; this.variables=vars; this.rendered=rendered;
        }
    }

    public static RunResult run(Program program, int degree, List<Integer> inputs) {
        Program.Rendered r = program.expandToDegree(degree);

        Map<String,Integer> vars = new HashMap<>();
        vars.put("y", 0);
        for (int i=0;i<inputs.size();i++) vars.put("x"+(i+1), Math.max(0, inputs.get(i)));

        int pc = 0;
        int cycles = 0;

        while (pc < r.list.size()) {
            Instruction inst = r.list.get(pc);
            cycles += inst.cycles();

            if (inst instanceof Instruction.Inc inc) {
                String v = inc.v.name();
                vars.put(v, vars.getOrDefault(v,0) + 1);
                pc++;

            } else if (inst instanceof Instruction.Dec dec) {
                String v = dec.v.name();
                vars.put(v, Math.max(0, vars.getOrDefault(v,0) - 1));
                pc++;

            } else if (inst instanceof Instruction.Nop) {
                pc++;

            } else if (inst instanceof Instruction.IfNzGoto j) {
                int val = vars.getOrDefault(j.v.name(), 0);
                if (val != 0) {
                    if (j.target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = r.labelIndex.get(j.target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Missing label at runtime: "+j.target);
                    pc = idx;
                } else pc++;

            } else if (inst instanceof Instruction.Assign a) {
                int srcVal = vars.getOrDefault(a.src.name(), 0);
                vars.put(a.dst.name(), srcVal);
                pc++;

            } else if (inst instanceof Instruction.Goto g) {
                if (g.target.equalsIgnoreCase("EXIT")) break;
                Integer idx = r.labelIndex.get(g.target.toUpperCase(Locale.ROOT));
                if (idx == null) throw new IllegalStateException("Missing label at runtime: "+g.target);
                pc = idx;

            } else if (inst instanceof Instruction.SetZero s0) {
                vars.put(s0.v.name(), 0);
                pc++;

            } else if (inst instanceof Instruction.SetConst sc) {
                vars.put(sc.v.name(), sc.n);
                pc++;

            } else if (inst instanceof Instruction.IfZeroGoto jz) {
                int val = vars.getOrDefault(jz.v.name(), 0);
                if (val == 0) {
                    if (jz.target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = r.labelIndex.get(jz.target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Missing label at runtime: "+jz.target);
                    pc = idx;
                } else pc++;

            } else if (inst instanceof Instruction.IfEqConstGoto jc) {
                int val = vars.getOrDefault(jc.v.name(), 0);
                if (val == jc.c) {
                    if (jc.target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = r.labelIndex.get(jc.target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Missing label at runtime: "+jc.target);
                    pc = idx;
                } else pc++;

            } else if (inst instanceof Instruction.IfEqVarGoto jv) {
                int a = vars.getOrDefault(jv.a.name(), 0);
                int b = vars.getOrDefault(jv.b.name(), 0);
                if (a == b) {
                    if (jv.target.equalsIgnoreCase("EXIT")) break;
                    Integer idx = r.labelIndex.get(jv.target.toUpperCase(Locale.ROOT));
                    if (idx == null) throw new IllegalStateException("Missing label at runtime: "+jv.target);
                    pc = idx;
                } else pc++;

            } else {
                pc++;
            }
        }

        int y = vars.getOrDefault("y", 0);
        LinkedHashMap<String,Integer> ordered = orderVars(vars);
        return new RunResult(degree, inputs, y, cycles, ordered, r);
    }

    private static LinkedHashMap<String,Integer> orderVars(Map<String,Integer> vars) {
        List<String> xs = new ArrayList<>();
        List<String> zs = new ArrayList<>();
        for (String k : vars.keySet()) {
            if (k.equals("y")) continue;
            if (k.startsWith("x")) xs.add(k);
            else if (k.startsWith("z")) zs.add(k);
        }
        xs.sort(Comparator.comparingInt(k -> Integer.parseInt(k.substring(1))));
        zs.sort(Comparator.comparingInt(k -> Integer.parseInt(k.substring(1))));
        LinkedHashMap<String,Integer> out = new LinkedHashMap<>();
        out.put("y", vars.getOrDefault("y", 0));
        for (String k : xs) out.put(k, vars.get(k));
        for (String k : zs) out.put(k, vars.get(k));
        return out;
    }
}
