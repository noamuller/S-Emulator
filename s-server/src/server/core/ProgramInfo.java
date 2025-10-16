package server.core;

public class ProgramInfo {
    private final String id;
    private final String name;

    public ProgramInfo(String id, String name) {
        this.id = id; this.name = name;
    }
    public String getId() { return id; }
    public String getName() { return name; }
}
