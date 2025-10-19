package server.core;

import sengine.Debugger;
import sengine.Program;
import sengine.ProgramParser;
import sengine.Runner;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.io.File;
import java.nio.file.Files;

/**
 * Run/session manager tailored to this project.
 * Singleton access via get().
 */
public class RunManager {

    /* ---------- Singleton ---------- */
    private static final RunManager INSTANCE = new RunManager();
    public static RunManager get() { return INSTANCE; }
    private RunManager() {}

    public enum Mode { REGULAR, DEBUG }

    public static final class RunSession {
        public final long id;
        public final String userId;
        public final String programId;
        public final int degree;
        public final List<Integer> inputs;
        public final Mode mode;

        public volatile boolean finished = false;
        public volatile int cycles = 0;
        public volatile int pc = -1;
        public volatile LinkedHashMap<String,Integer> vars = new LinkedHashMap<>();
        public volatile String currentInstruction = null;

        /* debug engine */
        volatile Debugger debugger;

        RunSession(long id, String userId, String programId,
                   int degree, List<Integer> inputs, Mode mode) {
            this.id = id;
            this.userId = userId;
            this.programId = programId;
            this.degree = degree;
            this.inputs = inputs;
            this.mode = mode;
        }
    }

    private final AtomicLong seq = new AtomicLong(1);
    private final Map<Long, RunSession> sessions = new ConcurrentHashMap<>();

    private final UserStore users = UserStore.get();
    private final ProgramStore programs = ProgramStore.get();

    public RunSession start(String userId, String programId, int degree, List<Integer> inputs, Mode mode) {
        if (users.getById(userId) == null) throw new IllegalArgumentException("Unknown user: " + userId);
        ProgramStore.ProgramEntry entry = findProgram(userId, programId);
        if (entry == null) throw new IllegalArgumentException("Unknown program for this user: " + programId);

        Program program = parse(entry.getXml());

        long id = seq.getAndIncrement();
        RunSession s = new RunSession(id, userId, programId, degree, inputs, mode);

        if (mode == Mode.REGULAR) {
            Runner.RunResult rr = Runner.run(program, degree, inputs);
            s.finished = true;
            s.cycles = rr.cycles;
            s.vars = rr.variables;
            s.pc = -1;
            s.currentInstruction = null;
        } else {
            Debugger d = new Debugger(program, degree, inputs);
            s.debugger = d;
            var snap = d.snapshot();
            s.pc = snap.pc;
            s.cycles = snap.cycles;
            s.finished = snap.halted;
            s.vars = snap.vars;
            s.currentInstruction = instructionAt(d, snap.pc);
        }

        sessions.put(s.id, s);
        return s;
    }

    public RunSession get(long id) {
        RunSession s = sessions.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown runId: " + id);
        return s;
    }

    public RunSession step(long id) {
        RunSession s = get(id);
        if (s.debugger == null) throw new IllegalStateException("Not a debug run");

        var snap = s.debugger.step();
        s.pc = snap.pc;
        s.cycles = snap.cycles;
        s.finished = snap.halted;
        s.vars = snap.vars;
        s.currentInstruction = instructionAt(s.debugger, snap.pc);
        return s;
    }

    public RunSession resume(long id) {
        RunSession s = get(id);
        if (s.debugger == null) throw new IllegalStateException("Not a debug run");

        int guard = 100000; // safety
        Debugger dbg = s.debugger;
        while (guard-- > 0) {
            var snap = dbg.step();
            s.pc = snap.pc;
            s.cycles = snap.cycles;
            s.vars = snap.vars;
            s.finished = snap.halted;
            s.currentInstruction = instructionAt(dbg, snap.pc);
            if (snap.halted) break;
        }
        return s;
    }

    public RunSession stop(long id) {
        RunSession s = get(id);
        s.finished = true;
        return s;
    }

    /* ---------- helpers ---------- */

    private ProgramStore.ProgramEntry findProgram(String userId, String programId) {
        for (ProgramStore.ProgramEntry e : programs.list(userId)) {
            if (Objects.equals(e.getId(), programId)) return e;
        }
        return null;
    }

    /** ProgramParser expects a File: write XML to temp file and call parseFromXml(File). */
    private static Program parse(String xml) {
        try {
            File tmp = File.createTempFile("program-", ".xml");
            Files.writeString(tmp.toPath(), xml == null ? "" : xml, StandardCharsets.UTF_8);
            try {
                // FIX: use ProgramParser.parseFromXml(File)
                return ProgramParser.parseFromXml(tmp);
            } finally {
                try { Files.deleteIfExists(tmp.toPath()); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse program XML: " + e.getMessage(), e);
        }
    }

    private static String instructionAt(Debugger d, int pc) {
        if (pc < 0) return null;
        var list = d.rendered().list;
        if (pc >= 0 && pc < list.size()) {
            var inst = list.get(pc);
            return inst.text == null ? "" : inst.text;
        }
        return null;
    }
}
