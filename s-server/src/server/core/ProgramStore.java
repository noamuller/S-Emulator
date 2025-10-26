package server.core;

import sengine.Program;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ProgramStore {

    private final Map<String, Program> programs = new ConcurrentHashMap<>();
    private final Map<String, ProgramInfo> infos = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(1);

    public synchronized ProgramInfo register(Program program) {
        String id = "prog_" + counter.getAndIncrement();
        programs.put(id, program);

        // simple, engine-agnostic metadata for now
        List<String> functions = List.of("main");
        int maxDegree = 0;

        ProgramInfo info = new ProgramInfo(id, id, functions, maxDegree);
        infos.put(id, info);
        return info;
    }

    public Program getProgram(String id) {
        Program p = programs.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown program: " + id);
        return p;
    }

    public List<ProgramInfo> list() {
        return new ArrayList<>(infos.values());
    }

    public ProgramInfo info(String id) {
        ProgramInfo i = infos.get(id);
        if (i == null) throw new IllegalArgumentException("Unknown program: " + id);
        return i;
    }
}
