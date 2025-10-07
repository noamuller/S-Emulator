package gui;

import sengine.Debugger;
import sengine.Program;
import sengine.ProgramParser;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;


public final class EngineAdapter {


    public static final class Row {
        private final int index;
        private final String type;
        private final String label;
        private final String instructionText;
        private final int cycles;

        public Row(int index, String type, String label, String instructionText, int cycles) {
            this.index = index;
            this.type = type;
            this.label = label;
            this.instructionText = instructionText;
            this.cycles = cycles;
        }
        public int getIndex() { return index; }
        public String getType() { return type; }
        public String getLabel() { return label; }
        public String getInstructionText() { return instructionText; }
        public int getCycles() { return cycles; }
    }

    public record RunResult(int y, int cycles) {}

    private Program program;


    private Debugger dbg;
    private Debugger.Snapshot lastSnap;
    private final StringBuilder dbgLog = new StringBuilder();
    private int dbgDegree = 0;
    private List<Integer> dbgInputs = List.of();
    private List<Row> dbgRows = List.of();



    public void load(File xml) {
        program = ProgramParser.parseFromXml(xml);
        clearDebugger();
    }

    private void clearDebugger() {
        dbg = null;
        lastSnap = null;
        dbgLog.setLength(0);
        dbgDegree = 0;
        dbgInputs = List.of();
        dbgRows = List.of();
    }

    public boolean isLoaded() { return program != null; }

    public int getMaxDegree() { return program == null ? 1 : program.maxDegree(); }



    public List<Row> getOriginalRows() { return toRows(0); }

    public List<Row> getExpandedRows(int degree) { return toRows(Math.max(0, degree)); }

    private List<Row> toRows(int degree) {
        if (program == null) return List.of();
        Program.Rendered r = program.expandToDegree(degree);
        List<Row> out = new ArrayList<>(r.list.size());
        int i = 1;
        for (var ins : r.list) {
            String type = safeGetType(ins);
            String label = safeGetLabel(ins);
            String text  = safeGetText(ins);
            int cycles   = safeGetCycles(ins);
            out.add(new Row(i++, type, label, text, cycles));
        }
        return out;
    }

    private static String safeGetType(Object ins) {
        try { return String.valueOf(ins.getClass().getMethod("type").invoke(ins)); } catch (Exception ignored) {}
        try { return String.valueOf(ins.getClass().getMethod("getType").invoke(ins)); } catch (Exception ignored) {}
        return "";
    }
    private static String safeGetLabel(Object ins) {
        try { Object v = ins.getClass().getField("label").get(ins); return v == null ? "" : String.valueOf(v); }
        catch (Exception ignored) {}
        try { return String.valueOf(ins.getClass().getMethod("label").invoke(ins)); } catch (Exception ignored) {}
        try { return String.valueOf(ins.getClass().getMethod("getLabel").invoke(ins)); } catch (Exception ignored) {}
        return "";
    }
    private static String safeGetText(Object ins) {
        try { Object v = ins.getClass().getField("text").get(ins); return v == null ? "" : String.valueOf(v); }
        catch (Exception ignored) {}
        try { return String.valueOf(ins.getClass().getMethod("text").invoke(ins)); } catch (Exception ignored) {}
        try { return String.valueOf(ins.getClass().getMethod("getText").invoke(ins)); } catch (Exception ignored) {}
        return "";
    }
    private static int safeGetCycles(Object ins) {
        try { Object v = ins.getClass().getMethod("cycles").invoke(ins); return v == null ? 0 : ((Number)v).intValue(); }
        catch (Exception ignored) {}
        try { Object v = ins.getClass().getMethod("getCycles").invoke(ins); return v == null ? 0 : ((Number)v).intValue(); }
        catch (Exception ignored) {}
        return 0;
    }



    public RunResult run(int degree, List<Integer> inputs) {
        ensureProgram();
        Debugger d = new Debugger(program, Math.max(0, degree), inputs == null ? List.of() : inputs);
        Debugger.Snapshot snap = d.snapshot();
        int guard = 1_000_000;
        while (!snap.halted && guard-- > 0) snap = d.step();
        int y = 0;
        if (snap.vars != null) {
            Integer v = snap.vars.get("y");
            if (v != null) y = v;
        }
        return new RunResult(y, snap.cycles);
    }



    public void dbgStart(int degree, List<Integer> inputs) {
        ensureProgram();
        dbgDegree = Math.max(0, degree);
        dbgInputs = inputs == null ? List.of() : List.copyOf(inputs);
        dbgRows = toRows(dbgDegree); // exact rows used by the debugger
        dbg = new Debugger(program, dbgDegree, dbgInputs);
        lastSnap = dbg.snapshot();
        dbgLog.setLength(0);
        appendSnapToLog("(init)", lastSnap);
    }

    public void dbgResume() {
        ensureDbg();
        int guard = 10_000; // reasonable cap for resume
        while (!lastSnap.halted && guard-- > 0) {
            lastSnap = dbg.step();
            appendSnapToLog("step", lastSnap);
        }
    }

    public void dbgStep() {
        ensureDbg();
        lastSnap = dbg.step();
        appendSnapToLog("step", lastSnap);
    }


    public void dbgStop() {
        ensureDbg();
        int guard = 1_000_000;
        while (!lastSnap.halted && guard-- > 0) {
            lastSnap = dbg.step();
        }
        appendSnapToLog("stop", lastSnap);
    }

    public boolean isHalted() { return lastSnap != null && lastSnap.halted; }
    public int getPcIndex() { return lastSnap == null ? -1 : lastSnap.pc; } // 0-based
    public String getPcText() { return lastSnap == null ? "*" : String.valueOf(lastSnap.pc); }
    public int getCycles() { return lastSnap == null ? 0 : lastSnap.cycles; }
    public String getLog() { return dbgLog.toString(); }


    public int getCurrentY() {
        if (lastSnap == null || lastSnap.vars == null) return 0;
        return lastSnap.vars.getOrDefault("y", 0);
    }


    public LinkedHashMap<String,Integer> getVars() {
        if (lastSnap == null || lastSnap.vars == null) return new LinkedHashMap<>();
        // keep insertion order of LinkedHashMap but sort for stable UI (x1,x2,...,y,z1,...)
        return sortVars(lastSnap.vars);
    }


    public Map<String,Integer> getChanged() {
        if (lastSnap == null || lastSnap.changed == null) return Map.of();
        return lastSnap.changed;
    }

    public List<Row> getDebuggerRows() { return dbgRows; }
    public int getDebuggerDegree() { return dbgDegree; }
    public List<Integer> getDebuggerInputs() { return dbgInputs; }



    private void ensureProgram() {
        if (program == null) throw new IllegalStateException("Program is not loaded.");
    }
    private void ensureDbg() {
        if (dbg == null) throw new IllegalStateException("Debugger is not started.");
    }
    private void appendSnapToLog(String prefix, Debugger.Snapshot s) {
        if (s == null) return;
        if (s.changed != null && !s.changed.isEmpty()) {
            dbgLog.append(prefix).append(" | pc=").append(s.pc).append(" cyc=").append(s.cycles).append(" changed=");
            dbgLog.append(new LinkedHashMap<>(s.changed));
        } else {
            dbgLog.append(prefix).append(" | pc=").append(s.pc).append(" cyc=").append(s.cycles);
        }
        dbgLog.append('\n');
    }

    private static LinkedHashMap<String,Integer> sortVars(LinkedHashMap<String,Integer> in) {

        Comparator<String> cmp = Comparator
                .comparing((String s) -> !s.equals("y"))
                .thenComparing((String s) -> !(s.startsWith("x") || s.startsWith("X")))
                .thenComparing((String s) -> !(s.startsWith("z") || s.startsWith("Z")))
                .thenComparing(s -> s, String.CASE_INSENSITIVE_ORDER);
        return in.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(cmp))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a,b)->a,
                        LinkedHashMap::new
                ));
    }
}
