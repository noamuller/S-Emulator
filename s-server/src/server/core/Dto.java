package server.core;

import java.util.*;

public final class Dto {

    public static final class ProgramSummary {
        public String id;
        public String name;
        public int maxDegree;
        public List<FunctionSummary> functions = new ArrayList<>();
    }

    public static final class FunctionSummary {
        public String name;
        public String userString;
    }

    public static final class UserSummary {
        public String id;
        public String name;
        public int credits;
    }

    public static final class RunRequest {
        public String userId;
        public String programId;
        public String functionName;
        public int degree;
        public List<Integer> inputs;
        public String mode;
    }

    public static final class RunStatus {
        public long runId;
        public String state;
        public int pc;
        public String currentInstruction;
        public int cycles;
        public Map<String,Integer> variables;
        public boolean finished;
    }

    public static final class HistoryRow {
        public int runNo;
        public int degree;
        public List<Integer> inputs;
        public int y;
        public int cycles;
        public long atEpochMillis;
    }
}

