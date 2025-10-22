package server.core;

import sengine.*; // your s-engine package: ProgramParser, Program, Runner, Debugger, Instruction, etc.

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EngineFacadeImpl implements EngineFacade {

    private final ProgramStore programStore;
    private final UserStore userStore;
    private final RunManager runManager;

    // Architecture cost multipliers (adjust to your spec)
    private final Map<String,Integer> architectureTariff = Map.of(
            "Basic", 1, "Advanced", 2, "Pro", 3
    );

    public EngineFacadeImpl(ProgramStore ps, UserStore us, RunManager rm) {
        this.programStore = ps;
        this.userStore = us;
        this.runManager = rm;
    }

    // ---------- Programs ----------

    @Override
    public ProgramInfo loadProgram(String xmlText) {
        ProgramParser parser = new ProgramParser();
        Program program = parser.parse(xmlText); // throws with friendly msg on invalid xml
        return programStore.register(program);
    }

    @Override
    public List<TraceRow> expand(String programId, String function, int degree) {
        Program p = programStore.getProgram(programId);
        List<Instruction> expanded = p.expand(function, degree); // or whatever helper you expose
        return toTrace(expanded);
    }

    // ---------- Runs (regular) ----------

    @Override
    public RunResult run(String userId, String programId, String function,
                         List<Integer> inputs, int degree, String architecture) {

        Program p = programStore.getProgram(programId);
        int tariff = tariffFor(architecture);

        Runner runner = new Runner(p, function, degree, inputs);
        int y = runner.run(); // executes fully
        int cycles = runner.getCycles();

        // Credits
        userStore.charge(userId, cycles * tariff);

        Map<String,Integer> vars = runner.snapshotVariables();
        List<TraceRow> trace = toTrace(runner.getExecutedProgram());

        runManager.recordHistory(userId, degree, inputs, y, cycles);

        return new RunResult(null, y, cycles, vars, trace);
    }

    // ---------- Debug ----------

    @Override
    public DebugSession startDebug(String userId, String programId, String function,
                                   List<Integer> inputs, int degree, String architecture) {
        Program p = programStore.getProgram(programId);
        int tariff = tariffFor(architecture);

        Debugger dbg = new Debugger(p, function, degree, inputs);
        String runId = runManager.start(userId, dbg, tariff);

        return new DebugSession(runId, toState(runId, dbg));
    }

    @Override
    public DebugState step(String runId) {
        Debugger dbg = runManager.get(runId);
        dbg.step();
        if (dbg.isHalted()) finalizeDebug(runId, dbg);
        return toState(runId, dbg);
    }

    @Override
    public DebugState resume(String runId) {
        Debugger dbg = runManager.get(runId);
        dbg.resume();
        if (dbg.isHalted()) finalizeDebug(runId, dbg);
        return toState(runId, dbg);
    }

    @Override
    public DebugState stop(String runId) {
        Debugger dbg = runManager.get(runId);
        dbg.stop();
        finalizeDebug(runId, dbg);
        return toState(runId, dbg);
    }

    private void finalizeDebug(String runId, Debugger dbg) {
        // charge credits
        int tariff = runManager.tariff(runId);
        int cycles = dbg.getCycles();
        String userId = runManager.user(runId);
        userStore.charge(userId, cycles * tariff);

        // write history
        runManager.recordHistory(userId, dbg.getDegree(), dbg.getInputs(),
                dbg.getY(), dbg.getCycles());

        runManager.finish(runId);
    }

    // ---------- Credits & History ----------

    @Override
    public CreditsState getCredits(String userId) {
        return new CreditsState(userId, userStore.get(userId).credits());
    }

    @Override
    public CreditsState chargeCredits(String userId, int amount) {
        userStore.charge(userId, -amount); // negative charge = top-up
        return getCredits(userId);
    }

    @Override
    public List<HistoryRow> history(String userId) {
        return runManager.history(userId).stream()
                .map(h -> new HistoryRow(h.runNo(), h.degree(), h.inputs(),
                        h.y(), h.cycles(), h.timestamp()))
                .collect(Collectors.toList());
    }

    // ---------- helpers ----------

    private int tariffFor(String architecture) {
        return architectureTariff.getOrDefault(architecture == null ? "Basic" : architecture, 1);
    }

    private List<TraceRow> toTrace(List<Instruction> list) {
        List<TraceRow> res = new ArrayList<>();
        int idx = 1;
        for (Instruction ins : list) {
            res.add(new TraceRow(idx++,
                    ins.isBasic() ? "B" : "S",
                    ins.getLabel(),
                    ins.getText(),
                    ins.getCycles()));
        }
        return res;
    }

    private DebugState toState(String runId, Debugger dbg) {
        Instruction curr = dbg.currentInstruction();
        TraceRow row = (curr == null) ? null :
                new TraceRow(dbg.getPc(),
                        curr.isBasic() ? "B" : "S",
                        curr.getLabel(), curr.getText(), curr.getCycles());
        return new DebugState(runId, dbg.getPc(), dbg.getCycles(), dbg.isHalted(),
                dbg.snapshotVariables(), row);
    }
}
