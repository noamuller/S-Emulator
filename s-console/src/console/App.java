package console;

import sengine.Program;
import sengine.ProgramParser;

import java.io.File;
import java.util.List;
import java.util.Scanner;


public final class App {

    private static Program currentProgram = null;
    private static Program lastGoodProgram = null;
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
        System.out.println("(1) Load system XML");
        System.out.println("(2) Show program");
        System.out.println("(3) Expand (preview degree)");
        System.out.println("(4) Run program  [use GUI]");
        System.out.println("(5) Show history [use GUI]");
        System.out.println("(6) Exit");
    }




    private static void cmdLoadXml() {
        System.out.print("Enter full path to XML file: ");
        String path = sc.nextLine().trim();
        File xml = new File(path);

        try {

            File xsd = new File("S-Emulator-v2.xsd");
            if (xsd.exists()) {
                ProgramParser.validateWithXsd(xml, xsd);
            }

            Program p = ProgramParser.parseFromXml(xml);



            currentProgram = p;
            lastGoodProgram = p;
            System.out.println("OK: program \"" + currentProgram.name + "\" loaded.");
        } catch (Exception ex) {
            System.out.println("Error: " + ex.getMessage());
            if (lastGoodProgram != null) {
                currentProgram = lastGoodProgram;
                System.out.println("Keeping previous valid program loaded.");
            } else {
                currentProgram = null;
            }
        }
    }


    private static void cmdShowProgram() {
        if (currentProgram == null) {
            System.out.println("No program loaded.");
            return;
        }
        var r = currentProgram.expandToDegree(0);
        System.out.println();
        System.out.println("Program: " + r.name + "  |  Degree: 0/" + currentProgram.maxDegree());
        for (String line : r.lines) System.out.println(line);
        System.out.println("Total cycles (sum of listed): " + r.sumCycles);
    }


    private static void cmdExpand() {
        if (currentProgram == null) {
            System.out.println("No program loaded.");
            return;
        }
        int max = currentProgram.maxDegree();
        int deg = readInt("Enter expansion degree [0.." + max + "]: ");
        deg = clamp(deg, 0, max);

        var r = currentProgram.expandToDegree(deg);
        System.out.println();
        System.out.println("Program: " + r.name + "  |  Degree: " + deg + "/" + max);
        for (String line : r.lines) System.out.println(line);
        System.out.println("Total cycles (sum of listed): " + r.sumCycles);

        // Optional: show origin chain of a specific instruction
        if (!r.originChains.isEmpty()) {
            String ans = readString("Show origin chain for which #? (empty to skip): ");
            if (!ans.isBlank()) {
                try {
                    int idx = Integer.parseInt(ans.trim());
                    if (idx >= 1 && idx <= r.originChains.size()) {
                        List<String> chain = r.originChains.get(idx - 1);
                        if (chain.isEmpty()) {
                            System.out.println("No origin (basic instruction).");
                        } else {
                            System.out.println("Origin chain (latest first):");
                            for (String s : chain) System.out.println("  " + s);
                        }
                    } else {
                        System.out.println("Out of range.");
                    }
                } catch (NumberFormatException ignore) {
                    System.out.println("Not a number.");
                }
            }
        }
    }

    private static void cmdRun() {
        System.out.println("please use the GUI to Run / Debug.");
    }

    private static void cmdHistory() {
        System.out.println("please use the GUI to view History/Statistics.");
    }

    // ---------- Small helpers ----------

    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine();
            try { return Integer.parseInt(s.trim()); }
            catch (Exception ignore) { System.out.println("Please enter a number."); }
        }
    }

    private static String readString(String prompt) {
        System.out.print(prompt);
        return sc.nextLine();
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
