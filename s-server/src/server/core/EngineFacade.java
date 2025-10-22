package server.core;

import java.util.List;
import java.util.Map;

public interface EngineFacade {
    // Programs
    ProgramInfo loadProgram(String xmlText);                // parse+register, returns id/name/functions/maxDegree
    List<TraceRow> expand(String programId, String function, int degree);

    // Runs (regular)
    RunResult run(String userId, String programId, String function,
                  List<Integer> inputs, int degree, String architecture);

    // Debug
    DebugSession startDebug(String userId, String programId, String function,
                            List<Integer> inputs, int degree, String architecture);
    DebugState step(String runId);
    DebugState resume(String runId);
    DebugState stop(String runId);

    // Credits
    CreditsState getCredits(String userId);
    CreditsState chargeCredits(String userId, int amount);

    // History
    List<HistoryRow> history(String userId);

    // ===== DTOs kept here for convenience =====
    record TraceRow(int index, String type, String label, String instr, int cycles) {}

    record RunResult(String runId, int y, int cycles,
                     Map<String,Integer> variables, List<TraceRow> trace) {}

    record DebugSession(String runId, DebugState state) {}

    record DebugState(String runId, int pc, int cycles, boolean halted,
                      Map<String,Integer> variables, TraceRow current) {}

    record CreditsState(String userId, int credits) {}

    record HistoryRow(int runNo, int degree, String inputs, int y, int cycles, long timestamp) {}
}
