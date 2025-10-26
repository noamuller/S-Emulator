package server.core;

import sengine.Debugger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Manages live debug sessions and per-user run history. */
public class RunManager {

    /* ---------- session model ---------- */
    private static final class Session {
        final long id;
        final String userId;
        final Debugger dbg;
        final int degree;
        final List<Integer> inputs;
        final int tariff;

        Session(long id, String userId, Debugger dbg, int degree, List<Integer> inputs, int tariff) {
            this.id = id; this.userId = userId; this.dbg = dbg;
            this.degree = degree; this.inputs = inputs; this.tariff = tariff;
        }
    }

    public static final class HistoryItem {
        private final int runNo, degree, y, cycles;
        private final String inputs;
        private final long timestamp;
        public HistoryItem(int runNo, int degree, String inputs, int y, int cycles) {
            this.runNo = runNo; this.degree = degree; this.inputs = inputs;
            this.y = y; this.cycles = cycles; this.timestamp = Instant.now().toEpochMilli();
        }
        public int runNo(){ return runNo; }
        public int degree(){ return degree; }
        public String inputs(){ return inputs; }
        public int y(){ return y; }
        public int cycles(){ return cycles; }
        public long timestamp(){ return timestamp; }
    }

    private final AtomicLong idGen = new AtomicLong(1);
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<HistoryItem>> histories = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> runCounters = new ConcurrentHashMap<>();

    /* ---------- sessions ---------- */

    public long start(String userId, Debugger dbg, int degree, List<Integer> inputs, int tariff) {
        long id = idGen.getAndIncrement();
        sessions.put(id, new Session(id, userId, dbg, degree,
                inputs == null ? List.of() : List.copyOf(inputs), tariff));
        return id;
    }

    public Debugger get(long runId) {
        Session s = sessions.get(runId);
        if (s == null) throw new IllegalArgumentException("Unknown runId " + runId);
        return s.dbg;
    }

    public String user(long runId) {
        Session s = sessions.get(runId);
        if (s == null) throw new IllegalArgumentException("Unknown runId " + runId);
        return s.userId;
    }

    public int degree(long runId) {
        Session s = sessions.get(runId);
        if (s == null) throw new IllegalArgumentException("Unknown runId " + runId);
        return s.degree;
    }

    public List<Integer> inputs(long runId) {
        Session s = sessions.get(runId);
        if (s == null) throw new IllegalArgumentException("Unknown runId " + runId);
        return s.inputs;
    }

    public int tariff(long runId) {
        Session s = sessions.get(runId);
        if (s == null) throw new IllegalArgumentException("Unknown runId " + runId);
        return s.tariff;
    }

    public void finish(long runId) {
        sessions.remove(runId);
    }

    /* ---------- history ---------- */

    public void recordHistory(String userId, int degree, List<Integer> inputs, int y, int cycles) {
        String inputsStr = (inputs == null || inputs.isEmpty())
                ? ""
                : String.join(", ", inputs.stream().map(String::valueOf).toList());
        int runNo = runCounters.computeIfAbsent(userId, k -> new AtomicInteger(0)).incrementAndGet();
        histories.computeIfAbsent(userId, k -> new ArrayList<>())
                .add(new HistoryItem(runNo, degree, inputsStr, y, cycles));
    }

    public List<HistoryItem> history(String userId) {
        return histories.getOrDefault(userId, List.of());
    }
}
