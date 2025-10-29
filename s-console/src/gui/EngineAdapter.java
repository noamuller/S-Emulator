package gui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Plain-Java HTTP adapter (no external libs).
 * Talks to /api/programs/* and /api/runs*.
 * Very tolerant JSON parsing using simple string/regex scanning.
 */
public class EngineAdapter {

    private final String base;

    public EngineAdapter(String baseUrl) {
        // Normalize: no trailing slash
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /* ===========================================================
       Upload XML  -> ProgramInfo(id, name, functions[], maxDegree)
       =========================================================== */
    public ProgramInfo uploadXml(Path xmlPath) throws IOException {
        String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);
        String url = base + "/api/programs/upload";
        String resp = httpPost(url, "text/xml; charset=UTF-8", xml);

        // Extract fields from JSON
        String id = extractFirst(resp, "\"id\"\\s*:\\s*\"([^\"]+)\"");
        if (id.isEmpty()) id = extractFirst(resp, "\"programId\"\\s*:\\s*\"([^\"]+)\"");
        String name = extractFirst(resp, "\"name\"\\s*:\\s*\"([^\"]+)\"");
        String maxDegStr = extractFirst(resp, "\"maxDegree\"\\s*:\\s*(\\d+)");
        int maxDegree = maxDegStr.isEmpty() ? 0 : Integer.parseInt(maxDegStr);

        List<String> functions = extractStringArray(resp, "\"functions\"\\s*:\\s*\\[([^\\]]*)\\]");
        if (functions.isEmpty()) functions = List.of("(main)");

        return new ProgramInfo(id, name, functions, maxDegree);
    }

    /* ===========================================================
       Expand program (instructions list) -> List<TraceRow>
       Accepts either:
         A) proper JSON rows ([{index:..., type:..., label:..., instr/text:..., cycles:...}, ...])
         B) stringified "TraceRow[index=1, type=S, ...]" form
       =========================================================== */
    public List<TraceRow> expand(String programId, String function, int degree) throws IOException {
        String url = String.format(
                "%s/api/programs/expand?programId=%s&function=%s&degree=%d",
                base, enc(programId), enc(function), degree);

        String resp = httpGet(url);

        // Try JSON object array first
        List<TraceRow> rows = parseRowsAsJsonObjects(resp);
        if (!rows.isEmpty()) return rows;

        // Fallback: parse "TraceRow[...]" strings
        return parseRowsFromTraceStrings(resp);
    }

    /* ===========================================================
       Runs / Debugging
       =========================================================== */

    /** One-shot run. */
    public RunResult runOnce(String programId, String function, List<Integer> inputs, int degree, String architecture) throws IOException {
        String url = base + "/api/runs";
        String body = buildJson(Map.of(
                "programId", programId,
                "function",  function,
                "degree",    degree,
                "architecture", opt(architecture),
                "inputs",    inputs == null ? List.of() : inputs
        ));
        String resp = httpPost(url, "application/json; charset=UTF-8", body);

        int y = parseInt(extractFirst(resp, "\"y\"\\s*:\\s*(\\-?\\d+)"), 0);
        int cycles = parseInt(extractFirst(resp, "\"cycles\"\\s*:\\s*(\\d+)"), 0);
        Map<String,Integer> vars = parseVars(resp);

        return new RunResult(y, cycles, vars);
    }

    /** Start a debug session. Uses the same endpoint to obtain a runId and initial state. */
    public DebugState startDebug(String programId, String function, List<Integer> inputs, int degree, String architecture) throws IOException {
        String url = base + "/api/runs";
        String body = buildJson(Map.of(
                "programId",    programId,
                "function",     function,
                "degree",       degree,
                "architecture", opt(architecture),
                "inputs",       inputs == null ? List.of() : inputs,
                "mode",         "debug"      // harmless hint; server may ignore
        ));
        String resp = httpPost(url, "application/json; charset=UTF-8", body);
        String runId = extractFirst(resp, "\"runId\"\\s*:\\s*\"([^\"]+)\"");
        int cycles = parseInt(extractFirst(resp, "\"cycles\"\\s*:\\s*(\\d+)"), 0);
        Map<String,Integer> vars = parseVars(resp);
        TraceRow current = parseCurrentFromRun(resp);

        return new DebugState(runId, cycles, vars, current);
    }

    /** Resume a paused debug run. */
    public DebugState resume(String runId) throws IOException {
        String url = base + "/api/runs/resume";
        String body = buildJson(Map.of("runId", runId));
        String resp = httpPost(url, "application/json; charset=UTF-8", body);
        return parseDebugState(resp);
    }

    /** Single step a debug run. */
    public DebugState step(String runId) throws IOException {
        String url = base + "/api/runs/step";
        String body = buildJson(Map.of("runId", runId));
        String resp = httpPost(url, "application/json; charset=UTF-8", body);
        return parseDebugState(resp);
    }

    /** Stop a debug run. */
    public DebugState stop(String runId) throws IOException {
        String url = base + "/api/runs/stop";
        String body = buildJson(Map.of("runId", runId));
        String resp = httpPost(url, "application/json; charset=UTF-8", body);
        return parseDebugState(resp);
    }

    /* ===========================================================
       Internal parsers (regex-only)
       =========================================================== */

    private static List<TraceRow> parseRowsAsJsonObjects(String resp) {
        List<TraceRow> rows = new ArrayList<>();
        String rowsBlock = extractFirst(resp, "\"rows\"\\s*:\\s*\\[([\\s\\S]*?)\\]");
        if (rowsBlock.isEmpty()) return rows;
        if (!rowsBlock.contains("{")) return rows;

        List<String> items = splitObjectArray(rowsBlock);
        for (String item : items) {
            int idx = parseInt(extractFirst(item, "\"index\"\\s*:\\s*(\\d+)"), 0);
            String type = extractFirst(item, "\"type\"\\s*:\\s*\"([^\"]*)\"");
            String label = extractFirst(item, "\"label\"\\s*:\\s*\"([^\"]*)\"");
            String instr = extractFirst(item, "\"instr\"\\s*:\\s*\"([^\"]*)\"");
            if (instr.isEmpty()) instr = extractFirst(item, "\"text\"\\s*:\\s*\"([^\"]*)\"");
            int cycles = parseInt(extractFirst(item, "\"cycles\"\\s*:\\s*(\\d+)"), 0);
            rows.add(new TraceRow(idx, type, label, instr, cycles));
        }
        return rows;
    }

    private static List<String> splitObjectArray(String rowsBlock) {
        List<String> out = new ArrayList<>();
        int brace = 0;
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < rowsBlock.length(); i++) {
            char c = rowsBlock.charAt(i);
            sb.append(c);
            if (c == '"' && (i == 0 || rowsBlock.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '{') brace++;
                else if (c == '}') brace--;
                else if (c == ',' && brace == 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
            }
        }
        String last = sb.toString().trim();
        if (!last.isEmpty()) out.add(last);
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i).trim();
            if (!s.startsWith("{")) s = "{" + s;
            if (!s.endsWith("}")) s = s + "}";
            out.set(i, s);
        }
        return out;
    }

    private static List<TraceRow> parseRowsFromTraceStrings(String resp) {
        List<TraceRow> rows = new ArrayList<>();
        var p = java.util.regex.Pattern.compile(
                "index\\s*=\\s*(\\d+)\\s*,\\s*" +
                        "type\\s*=\\s*([^,]+)\\s*,\\s*" +
                        "label\\s*=\\s*([^,]*)\\s*,\\s*" +
                        "(?:instr|text)\\s*=\\s*([^,]+)\\s*,\\s*" +
                        "cycles\\s*=\\s*(\\d+)",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        var m = p.matcher(resp);
        while (m.find()) {
            int idx = parseInt(m.group(1).trim(), 0);
            String type = m.group(2).trim();
            String label = m.group(3).trim();
            String instr = m.group(4).trim();
            int cycles = parseInt(m.group(5).trim(), 0);
            rows.add(new TraceRow(idx, type, label, instr, cycles));
        }
        return rows;
    }

    private static Map<String,Integer> parseVars(String resp) {
        String block = extractFirst(resp, "\"variables\"\\s*:\\s*\\{([\\s\\S]*?)\\}");
        Map<String,Integer> vars = new LinkedHashMap<>();
        if (block.isEmpty()) return vars;
        var m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)").matcher(block);
        while (m.find()) vars.put(m.group(1), parseInt(m.group(2), 0));
        return vars;
    }

    private static TraceRow parseCurrentFromRun(String resp) {
        String obj = extractFirst(resp, "\"current\"\\s*:\\s*\\{([\\s\\S]*?)\\}");
        if (obj.isEmpty()) return null;
        int idx = parseInt(extractFirst(obj, "\"index\"\\s*:\\s*(\\d+)"), 0);
        String type = extractFirst(obj, "\"type\"\\s*:\\s*\"([^\"]*)\"");
        String label = extractFirst(obj, "\"label\"\\s*:\\s*\"([^\"]*)\"");
        String instr = extractFirst(obj, "\"instr\"\\s*:\\s*\"([^\"]*)\"");
        if (instr.isEmpty()) instr = extractFirst(obj, "\"text\"\\s*:\\s*\"([^\"]*)\"");
        int cycles = parseInt(extractFirst(obj, "\"cycles\"\\s*:\\s*(\\d+)"), 0);
        return new TraceRow(idx, type, label, instr, cycles);
    }

    private static DebugState parseDebugState(String resp) {
        String runId = extractFirst(resp, "\"runId\"\\s*:\\s*\"([^\"]+)\"");
        int cycles = parseInt(extractFirst(resp, "\"cycles\"\\s*:\\s*(\\d+)"), 0);
        Map<String,Integer> vars = parseVars(resp);
        TraceRow current = parseCurrentFromRun(resp);
        return new DebugState(runId, cycles, vars, current);
    }

    /* ===========================================================
       Low-level HTTP (UTF-8)
       =========================================================== */

    private static String httpPost(String url, String contentType, String body) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", contentType);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        try (is) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        try (is) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }

    /* ===========================================================
       Tiny helpers
       =========================================================== */

    private static String extractFirst(String text, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static List<String> extractStringArray(String text, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        if (!m.find()) return List.of();
        String inside = m.group(1);
        var m2 = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(inside);
        List<String> out = new ArrayList<>();
        while (m2.find()) out.add(m2.group(1));
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // poor-man JSON builder that handles primitives, strings, lists and maps you pass here.
    private static String buildJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        if (obj instanceof Map<?,?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(buildJson(e.getKey().toString())).append(':').append(buildJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (obj instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                sb.append(buildJson(o));
            }
            sb.append(']');
            return sb.toString();
        }
        return buildJson(obj.toString());
    }
    private static String opt(String s) { return (s == null) ? "" : s; }

    /* ===========================================================
       DTOs for run/debug responses
       =========================================================== */

    public static final class RunResult {
        public final int y;
        public final int cycles;
        public final Map<String,Integer> vars;
        public RunResult(int y, int cycles, Map<String,Integer> vars) {
            this.y = y; this.cycles = cycles; this.vars = vars == null ? Map.of() : vars;
        }
    }

    public static final class DebugState {
        public final String runId;
        public final int cycles;
        public final Map<String,Integer> vars;
        public final TraceRow current; // may be null
        public DebugState(String runId, int cycles, Map<String,Integer> vars, TraceRow current) {
            this.runId = runId; this.cycles = cycles; this.vars = vars == null ? Map.of() : vars; this.current = current;
        }
    }
}
