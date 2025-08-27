package sengine;

import java.util.*;
import java.util.stream.Collectors;

public final class Program {
    public final String name;
    public final List<Instruction> instructions;

    public Program(String name, List<Instruction> ins) {
        this.name = name;
        this.instructions = List.copyOf(ins);
    }

    public boolean hasSynthetic() {
        return instructions.stream().anyMatch(i -> !i.basic);
    }

    public int maxDegree() { return hasSynthetic() ? 1 : 0; }

    public Rendered expandToDegree(int degree) {
        if (degree <= 0) return render(instructions, Map.of());
        List<Instruction> flat = new ArrayList<>();
        Map<Integer, Instruction> originMap = new HashMap<>();
        int n = 0;
        for (Instruction i : instructions) {
            List<Instruction> xs = i.basic ? List.of(i) : i.expand();
            for (Instruction x : xs) {
                flat.add(x);
                originMap.put(++n, i);
            }
        }
        return render(flat, originMap);
    }

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
}
