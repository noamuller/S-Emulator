package server.core;

import sengine.Debugger;
import sengine.Instruction;
import sengine.Program;
import sengine.ProgramParser;
import sengine.Runner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public final class EngineFacadeImpl implements EngineFacade {

    private final ProgramStore programs;
    private final UserStore users;
    private final RunManager runs;

    public EngineFacadeImpl(ProgramStore programs, UserStore users, RunManager runs) {
        this.programs = programs;
        this.users = users;
        this.runs = runs;
    }

    @Override
    public ProgramInfo loadProgram(String xmlText) {
        Program p = ProgramParser.parseFromXml(writeTempXml(xmlText));
        String id = programs.put(p);

        List<String> functions = new ArrayList<>(p.functions.keySet());
        String name = (p.name == null || p.name.isBlank()) ? "(unnamed)" : p.name;

        return new ProgramInfo(id, name, functions, p.maxDegree());
    }

    @Override
    public List<TraceRow> expand(String programId, String function, int degree) {
        Program p = requireProgram(programId);
        Program.Rendered r = p.expandToDegree(degree);

        List<TraceRow> rows = new ArrayList<>(r.list.size());
        int i = 1;
        for (Instruction ins : r.list) {
            rows.add(new TraceRow(
                    i++,
                    ins.prettyType(),
                    safe(ins.label),
                    safe(ins.text),
                    Math.max(0, ins.cycles())
            ));
        }
        return rows;
    }

    @Override
    public RunResult run(String userId, String programId, String function,
                         List<Integer> inputs, int degree, String architecture) {
        Program p = requireProgram(programId);
        List<Integer> in = (inputs == null) ? List.of() : inputs;

        Runner.RunResult rr = Runner.run(p, degree, in);

        List<TraceRow> trace = new ArrayList<>(rr.rendered.list.size());
        int i = 1;
        for (Instruction ins : rr.rendered.list) {
            trace.add(new TraceRow(
                    i++,
                    ins.prettyType(),
                    safe(ins.label),
                    safe(ins.text),
                    Math.max(0, ins.cycles())
            ));
        }

        if (userId != null && !userId.isBlank()) {
            int runNo = runs.history(userId).size() + 1;
            runs.addHistory(userId, new HistoryRow(
                    runNo, Math.max(0, degree), inputsToString(in), rr.y, rr.cycles, System.currentTimeMillis()
            ));
        }

        String runId = "run-" + UUID.randomUUID();
        return new RunResult(runId, rr.y, rr.cycles, rr.variables, trace);
    }

    @Override
    public DebugSession startDebug(String userId, String programId, String function,
                                   List<Integer> inputs, int degree, String architecture) {
        Program p = requireProgram(programId);
        Debugger dbg = new Debugger(p, degree, inputs == null ? List.of() : inputs);
        String runId = runs.registerDebugger(dbg);
        return new DebugSession(runId, toState(runId, dbg.rendered(), dbg.snapshot()));
    }

    @Override
    public DebugState status(String runId) {
        Debugger dbg = runs.getDebugger(runId);
        if (dbg == null) return new DebugState(runId, -1, 0, true, Map.of(), null);
        return toState(runId, dbg.rendered(), dbg.snapshot());
    }

    @Override
    public DebugState step(String runId) {
        Debugger dbg = runs.getDebugger(runId);
        if (dbg == null) return new DebugState(runId, -1, 0, true, Map.of(), null);
        return toState(runId, dbg.rendered(), dbg.step());
    }

    @Override
    public DebugState resume(String runId) {
        Debugger dbg = runs.getDebugger(runId);
        if (dbg == null) return new DebugState(runId, -1, 0, true, Map.of(), null);

        // Your Debugger has no resume(); loop steps until halted.
        Debugger.Snapshot s = dbg.snapshot();
        while (!s.halted) {
            s = dbg.step();
        }
        return toState(runId, dbg.rendered(), s);
    }

    @Override
    public DebugState stop(String runId) {
        Debugger dbg = runs.getDebugger(runId);
        if (dbg == null) return new DebugState(runId, -1, 0, true, Map.of(), null);
        Debugger.Snapshot s = dbg.snapshot();
        runs.stop(runId);
        return new DebugState(runId, s.pc, s.cycles, true, s.vars, null);
    }

    @Override
    public CreditsState getCredits(String userId) {
        User u = users.getById(userId);
        int c = (u == null) ? 0 : u.getCredits();
        return new CreditsState(userId, c);
    }

    @Override
    public CreditsState chargeCredits(String userId, int amount) {
        int c = users.charge(userId, amount);
        return new CreditsState(userId, c);
    }

    @Override
    public List<HistoryRow> history(String userId) {
        return runs.history(userId);
    }

    private Program requireProgram(String id) {
        Program p = programs.get(id);
        if (p == null) throw new NoSuchElementException("program not found: " + id);
        return p;
    }

    private static String inputsToString(List<Integer> inputs) {
        if (inputs == null || inputs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(inputs.get(i));
        }
        return sb.toString();
    }

    private static String safe(String s) { return (s == null) ? "" : s; }

    private static File writeTempXml(String xml) {
        try {
            File f = File.createTempFile("program-", ".xml");
            Files.writeString(f.toPath(), xml == null ? "" : xml, StandardCharsets.UTF_8);
            return f;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static DebugState toState(String runId, Program.Rendered rendered, Debugger.Snapshot s) {
        TraceRow current = null;
        if (!s.halted && s.pc >= 0 && s.pc < rendered.list.size()) {
            Instruction ins = rendered.list.get(s.pc);
            current = new TraceRow(
                    s.pc + 1,
                    ins.prettyType(),
                    safe(ins.label),
                    safe(ins.text),
                    Math.max(0, ins.cycles())
            );
        }
        return new DebugState(runId, s.pc, s.cycles, s.halted, s.vars, current);
    }
}
