package server.core;

import java.util.List;

public class ProgramInfo {
    public final String id;
    public final String name;
    public final List<String> functions;
    public final int maxDegree;

    public ProgramInfo(String id, String name, List<String> functions, int maxDegree) {
        this.id = id;
        this.name = name;
        this.functions = functions;
        this.maxDegree = maxDegree;
    }

    public String id() { return id; }
    public String name() { return name; }
    public List<String> functions() { return functions; }
    public int maxDegree() { return maxDegree; }
}
