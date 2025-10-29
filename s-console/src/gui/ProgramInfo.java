package gui;

import java.util.List;

/** Minimal DTO the UI uses for the programs table and function combo. */
public class ProgramInfo {
    private final String id;
    private final String name;
    private final List<String> functions;
    private final int maxDegree;

    public ProgramInfo(String id, String name, List<String> functions, int maxDegree) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.functions = functions;
        this.maxDegree = maxDegree;
    }

    // Getters (JavaFX/table-friendly)
    public String getId() { return id; }
    public String getName() { return name; }
    public List<String> getFunctions() { return functions; }
    public int getMaxDegree() { return maxDegree; }

    // Also expose simple accessors if your bindings use method refs
    public String id() { return id; }
    public String name() { return name; }
    public List<String> functionsList() { return functions; }
    public int maxDegree() { return maxDegree; }
}
