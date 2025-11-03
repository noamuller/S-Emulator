package server.core;

import sengine.Program;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProgramStore {

    private static final ProgramStore INSTANCE = new ProgramStore();
    public static ProgramStore get() { return INSTANCE; }

    private final Map<String, Program> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByName = new ConcurrentHashMap<>();

    public ProgramStore() {}

    public String put(Program p) {
        String id = UUID.randomUUID().toString();
        byId.put(id, p);
        if (p != null && p.name != null) {
            idByName.put(p.name, id);
        }
        return id;
    }

    public Program get(String id) { return byId.get(id); }

    public Program getByName(String name) {
        String id = idByName.get(name);
        return id == null ? null : byId.get(id);
    }

    public java.util.List<ProgramInfo> list() {
        java.util.List<ProgramInfo> out = new java.util.ArrayList<>();
        for (var e : byId.entrySet()) {
            Program p = e.getValue();
            out.add(new ProgramInfo(
                    e.getKey(),
                    p.name == null ? "(unnamed)" : p.name,
                    new java.util.ArrayList<>(p.functions.keySet()),
                    p.maxDegree()
            ));

        }
        return out;
    }
}
