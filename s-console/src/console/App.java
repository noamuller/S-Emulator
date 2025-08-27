package console;

import sengine.*;

import java.io.File;
import java.util.*;

public final class App {

    private static Program currentProgram = null;
    private static Program lastGoodProgram = null;
    private static final RunHistory history = new RunHistory();
    private static final Scanner sc = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("S-Emulator (Console) — Java 21");
        while (true) {
            printMenu();
            int choice = readInt("Choose [1-6]: ");
            switch (choice) {
                case 1 -> cmdLoadXml();
                case 2 -> cmdShowProgram();
                case 3 -> cmdExpand();
                case 4 -> cmdRun();
                case 5 -> cmdHistory();
                case 6 -> { System.out.println("Bye!"); return; }
                default -> System.out.println("Invalid choice. Please select 1..6.");
            }
        }
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("1) Load system XML");
        System.out.println("2) Show program");
        System.out.println("3) Expand");
        System.out.println("4) Run program");
        System.out.println("5) Show history/statistics");
        System.out.println("6) Exit");
    }

    private static void cmdLoadXml() {
        System.out.print("Enter full path to XML file: ");
        String path = sc.nextLine().trim();
        ProgramParser.ParseResult r = ProgramParser.parseXml(new File(path));
        if (r.error != null) {
            System.out.println("Error: " + r.error);
            if (lastGoodProgram != null) {
                currentProgram = lastGoodProgram;
                System.out.println("Keeping previous valid program loaded.");
            } else {
                currentProgram = null;
            }
        } else {
            currentProgram = r.program;
            lastGoodProgram = r.program;
            System.out.println("OK: program \"" + currentProgram.name + "\" loaded.");
        }
    }

    private static void cmdShowProgram() {
        if (!ensureLoaded()) return;
        Program.Rendered r = currentProgram.expandToDegree(0);
        System.out.println("Program: " + r.name);
        Set<VariableRef> inputs = currentProgram.referencedVariables().stream()
                .filter(v -> v.kind()== VariableRef.Kind.X)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> labels = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Instruction i : currentProgram.instructions) if (i.label != null) labels.add(i.label);
        if (r.labelIndex.containsKey("EXIT")) labels.add("EXIT");

        System.out.println("Inputs used: " + (inputs.isEmpty() ? "(none)" : joinVars(inputs)));
        System.out.println("Labels used: " + (labels.isEmpty() ? "(none)" : String.join(", ", labels)));
        for (String line : r.lines) System.out.println(line);
    }

    private static String joinVars(Set<VariableRef> vars) {
        List<String> names = new ArrayList<>();
        for (VariableRef v: vars) names.add(v.name());
        return String.join(", ", names);
    }

    private static void cmdExpand() {
        if (!ensureLoaded()) return;
        int max = currentProgram.maxDegree();
        System.out.println("Max expansion degree: " + max);
        int d = readInt("Enter degree [0.."+max+"]: ");
        if (d < 0 || d > max) { System.out.println("Invalid degree."); return; }
        Program.Rendered r = currentProgram.expandToDegree(d);
        for (int i=0;i<r.lines.size();i++) {
            String line = r.lines.get(i);
            System.out.print(line);
            List<String> chain = r.originChains.get(i);
            for (String ch : chain) System.out.print("  <<<  " + ch.replace("#-1", "#?"));
            System.out.println();
        }
    }

    private static void cmdRun() {
        if (!ensureLoaded()) return;
        int max = currentProgram.maxDegree();
        System.out.println("Max expansion degree: " + max);
        int d = readInt("Enter degree to run [0.."+max+"]: ");
        if (d < 0 || d > max) { System.out.println("Invalid degree."); return; }

        Set<VariableRef> inputs = currentProgram.referencedVariables().stream()
                .filter(v -> v.kind()== VariableRef.Kind.X)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        System.out.println("Enter comma-separated inputs for x1,x2,... (any count is accepted).");
        System.out.println("Inputs used by program: " + (inputs.isEmpty() ? "(none)" : joinVars(inputs)));
        System.out.print("Values: ");
        String line = sc.nextLine().trim();
        List<Integer> vals = parseCsvInts(line);

        Runner.RunResult res = Runner.run(currentProgram, d, vals);
        System.out.println("Executed program (degree "+d+").");
        for (String l : res.rendered.lines) System.out.println(l);
        System.out.println("Result:");
        System.out.println("y = " + res.y);
        for (Map.Entry<String,Integer> e : res.variables.entrySet()) {
            if (e.getKey().equals("y")) continue;
            System.out.println(e.getKey()+" = "+e.getValue());
        }
        System.out.println("Total cycles = " + res.cycles);
        history.add(d, vals, res.y, res.cycles);
    }

    private static void cmdHistory() {
        if (!ensureLoaded()) return;
        if (history.isEmpty()) { System.out.println("No runs yet."); return; }
        System.out.println("Run# | Degree | Inputs | y | cycles");
        for (RunHistory.Entry e : history.all()) {
            System.out.println(String.format("%d | %d | %s | %d | %d",
                    e.runNo, e.degree, e.inputs.toString(), e.y, e.cycles));
        }
    }

    private static boolean ensureLoaded() {
        if (currentProgram == null) {
            System.out.println("No valid program loaded. Please load an XML first.");
            return false;
        }
        return true;
    }

    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim();
            try { return Integer.parseInt(s); } catch (Exception e) { System.out.println("Please enter a number."); }
        }
    }

    private static List<Integer> parseCsvInts(String s) {
        if (s.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try { out.add(Integer.parseInt(part)); } catch (Exception ignore) { out.add(0); }
        }
        return out;
    }
}
