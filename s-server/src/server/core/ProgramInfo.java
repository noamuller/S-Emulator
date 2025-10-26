package server.core;

import java.util.List;

public record ProgramInfo(String id, String name, List<String> functions, int maxDegree) {}
