package server.core;

import sengine.*;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class EngineFacadeImpl implements EngineFacade {

    private final ProgramStore programStore;
    private final UserStore userStore;
    private final RunManager runManager;

    // “architecture” -> cost multiplier per cycle
    private final Map<String,Integer> architectureTariff = Map.of(
            "Basic", 1, "Advanced", 2, "Pro", 3
    );

    public EngineFacadeImpl(ProgramStore ps, UserStore us, RunManager rm) {
        this.programStore = ps;
        this.userStore = us;
        this.runManager = rm;
    }

    /* ================= Programs ================= */

    @Override
    public ProgramInfo loadProgram(String xmlText) {
        try {
            // ProgramParser parses from a File → write a temp file
            File tmp = File.createTempFile("program-", ".xml");
            try (FileWriter fw = new FileWriter(tmp, StandardCharsets.UTF_8)) {
                fw.write(xmlText == null ? "" : xmlText);
            }
            Program p = ProgramParser.parseFromXml(tmp);
            return programStore.register(p);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Parse failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<TraceRow> expand(String programId, String function, int degree) {
        Program p = programStore.getProgram(programId);
        Program.Rendered r = p.expandToDegree(degree);
        return toTrace(r.list);
    }

    /* ================= Regular runs ================= */

    @Override
    public RunResult run(String userId, String programId, String function,
                         List<Integer> inputs, int degree, String architecture) {

        Program p = programStore.getProgram(programId);
        int tariff = tariffFor(architecture);

        Runner.RunResult rr = Runner.run(p, degree, inputs);
        int cycles = rr.cycles;
        int y = rr.variables.getOrDefault("y", 0);

        // “charge” = add credits (your UserStore.charge adds to the balance)
        userStore.charge(userId, cycles * tariff);

        // record history for the UI table
        runManager.recordHistory(userId, degree, inputs, y, cycles);

        return new RunResult(
                null, // no runId for regular run
                y,
                cycles,
                new LinkedHashMap<>(rr.variables),
                toTrace(rr.rendered.list)
        );
    }

    /* ================= Debug ================= */

    @Override
    public DebugSession startDebug(String userId, String programId, String function,
                                   List<Integer> inputs, int degree, String architecture) {
        Program p = programStore.getProgram(programId);
        int tariff = tariffFor(architecture);

        Debugger dbg = new Debugger(p, degree, inputs);
        long id = runManager.start(userId, dbg, degree, inputs, tariff);

        return new DebugSession(String.valueOf(id), toState(String.valueOf(id), dbg.snapshot()));
    }

    @Override
    public DebugState status(String runId) {
        Debugger dbg = runManager.get(parseRunId(runId));
        return toState(runId, dbg.snapshot());
    }

    @Override
    public DebugState step(String runId) {
        long id = parseRunId(runId);
        Debugger dbg = runManager.get(id);
        Debugger.Snapshot snap = dbg.step();
        if (snap.halted) finalizeDebug(id, snap);
        return toState(runId, snap);
    }

    @Override
    public DebugState resume(String runId) {
        long id = parseRunId(runId);
        Debugger dbg = runManager.get(id);
        Debugger.Snapshot snap = dbg.snapshot();
        int guard = 100000; // safety ceiling
        while (!snap.halted && guard-- > 0) {
            snap = dbg.step();
        }
        if (snap.halted) finalizeDebug(id, snap);
        return toState(runId, snap);
    }

    @Override
    public DebugState stop(String runId) {
        // No explicit “stop” in Debugger → fast-forward to end.
        return resume(runId);
    }

    private void finalizeDebug(long runId, Debugger.Snapshot snap) {
        int tariff = runManager.tariff(runId);
        String userId = runManager.user(runId);
        int degree = runManager.degree(runId);
        List<Integer> inputs = runManager.inputs(runId);
        int y = snap.vars.getOrDefault("y", 0);

        userStore.charge(userId, snap.cycles * tariff);
        runManager.recordHistory(userId, degree, inputs, y, snap.cycles);
        runManager.finish(runId);
    }

    /* ================= Credits & History ================= */

    @Override
    public CreditsState getCredits(String userId) {
        User u = userStore.getById(userId);
        int c = (u == null) ? 0 : u.getCredits();
        return new CreditsState(userId, c);
    }

    @Override
    public CreditsState chargeCredits(String userId, int amount) {
        // Your UserStore.charge only allows positive “top-up”.
        int credits = userStore.charge(userId, amount);
        return new CreditsState(userId, credits);
    }

    @Override
    public List<HistoryRow> history(String userId) {
        return runManager.history(userId).stream()
                .map(h -> new HistoryRow(h.runNo(), h.degree(), h.inputs(), h.y(), h.cycles(), h.timestamp()))
                .collect(Collectors.toList());
    }

    /* ================= Helpers ================= */

    private int tariffFor(String architecture) {
        return architectureTariff.getOrDefault(
                architecture == null ? "Basic" : architecture, 1);
    }

    private static List<TraceRow> toTrace(List<Instruction> list) {
        List<TraceRow> res = new ArrayList<>();
        int idx = 1;
        for (Instruction ins : list) {
            String label = (ins.label == null) ? "" : ins.label;
            String txt   = (ins.text  == null) ? "" : ins.text;
            int cycles   = ins.cycles();
            res.add(new TraceRow(idx++, "B", label, txt, cycles));
        }
        return res;
    }

    private DebugState toState(String runId, Debugger.Snapshot snap) {
        // We don’t expose instruction text from Debugger → leave empty.
        EngineFacade.TraceRow current = new EngineFacade.TraceRow(
                Math.max(0, snap.pc), "B", "", "", snap.cycles);

        Map<String,Integer> vars = new LinkedHashMap<>(snap.vars);

        return new DebugState(runId, snap.pc, snap.cycles, snap.halted, vars, current);
    }

    private static long parseRunId(String runId) {
        try { return Long.parseLong(runId); }
        catch (Exception e) { throw new IllegalArgumentException("Bad runId: " + runId); }
    }
}
