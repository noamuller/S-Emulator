package server.core;

import sengine.Debugger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps live debug sessions and per-user history. */
public final class RunManager {
    private final Map<String, Debugger> debuggers = new ConcurrentHashMap<>();
    private final Map<String, List<EngineFacade.HistoryRow>> historyByUser = new ConcurrentHashMap<>();

    public String registerDebugger(Debugger dbg) {
        String id = UUID.randomUUID().toString();
        debuggers.put(id, dbg);
        return id;
    }

    public Debugger getDebugger(String runId) { return debuggers.get(runId); }

    public void stop(String runId) { debuggers.remove(runId); }

    public void addHistory(String userId, EngineFacade.HistoryRow row) {
        historyByUser.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>())).add(row);
    }

    public List<EngineFacade.HistoryRow> history(String userId) {
        return historyByUser.getOrDefault(userId, List.of());
    }
}
