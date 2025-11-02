package gui;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EngineAdapter {

    private final String base; // e.g. "http://localhost:8080/s-server"
    public EngineAdapter(String base) { this.base = base; }

    /* =================== Public DTOs (UI-facing) =================== */

    public static final class ProgramInfo {
        public final String id, name;
        public final int maxDegree;
        public final List<String> functions;
        public ProgramInfo(String id, String name, int maxDegree, List<String> functions) {
            this.id = id; this.name = name; this.maxDegree = maxDegree; this.functions = functions;
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public int getMaxDegree() { return maxDegree; }
        public List<String> getFunctions() { return functions; }
    }

    public static final class TraceRow {
        public final int index; public final String type; public final String text; public final int cycles;
        public TraceRow(int index, String type, String text, int cycles) {
            this.index = index; this.type = type; this.text = text; this.cycles = cycles;
        }
        public int index() { return index; }
        public String type() { return type; }
        public String text() { return text; }
        public int cycles() { return cycles; }
    }

    public static final class RunResult {
        public final String runId;
        public final int y;
        public final int cycles;
        public final Map<String,Integer> vars;
        public final List<TraceRow> trace;
        public RunResult(String runId, int y, int cycles, Map<String,Integer> vars, List<TraceRow> trace) {
            this.runId = runId; this.y = y; this.cycles = cycles; this.vars = vars; this.trace = trace;
        }
    }

    public static final class DebugState {
        public final String runId;
        public final int pc, cycles;
        public final boolean halted;
        public final Map<String,Integer> vars;
        public final TraceRow current;
        public DebugState(String runId, int pc, int cycles, boolean halted,
                          Map<String,Integer> vars, TraceRow current) {
            this.runId = runId; this.pc = pc; this.cycles = cycles; this.halted = halted;
            this.vars = vars; this.current = current;
        }
    }

    /* =================== High-level calls used by Controller =================== */

    /** POST /api/login (form); returns credits as int. */
    public int login(String username) throws IOException {
        String url = base + "/api/login";
        String body = postForm(url, Map.of("username", username));
        // {"user":{"credits":1000,...},"ok":true}
        return extractInt(body, "\"credits\"\\s*:\\s*(\\d+)", 1, 0);
    }

    /** POST /api/credits/charge (form) -> {"credits":1234} */
    public int chargeCredits(int amount) throws IOException {
        String url = base + "/api/credits/charge";
        String body = postForm(url, Map.of("amount", String.valueOf(amount)));
        return extractInt(body, "\"credits\"\\s*:\\s*(\\d+)", 1, 0);
    }

    /** POST multipart /api/programs/upload (file) -> {"ok":true,"program":{...}} */
    public ProgramInfo uploadXml(Path xmlFile) throws IOException {
        String url = base + "/api/programs/upload";
        String json = postMultipart(url, "file", xmlFile);

        String obj = extractObject(json, "\"program\"\\s*:\\s*\\{", "\\}");
        String id     = extractString(obj, "\"id\"");
        String name   = extractString(obj, "\"name\"");
        int maxDeg    = extractInt(obj, "\"maxDegree\"\\s*:\\s*(\\d+)", 1, 0);
        List<String> functions = extractStringArray(obj, "\"functions\"");

        return new ProgramInfo(id, name, maxDeg, functions);
    }

    /** GET /api/programs -> {"programs":[{id,name,maxDegree,functions:[..]},...]} */
    public List<ProgramInfo> listPrograms() throws IOException {
        String url = base + "/api/programs";
        String json = httpGet(url);

        List<String> objs = splitTopLevelObjects(extractArray(json, "\"programs\""));
        List<ProgramInfo> out = new ArrayList<>();
        for (String p : objs) {
            String id     = extractString(p, "\"id\"");
            String name   = extractString(p, "\"name\"");
            int maxDeg    = extractInt(p, "\"maxDegree\"\\s*:\\s*(\\d+)", 1, 0);
            List<String> functions = extractStringArray(p, "\"functions\"");
            out.add(new ProgramInfo(id, name, maxDeg, functions));
        }
        return out;
    }

    /** GET /api/programs/expand?programId=&function=&degree= */
    public List<TraceRow> expand(String programId, String function, int degree) throws IOException {
        String url = base + "/api/programs/expand?programId=" + enc(programId)
                + "&function=" + enc(function)
                + "&degree=" + degree;
        String json = httpGet(url);
        // {"rows":[{"index":1,"type":"B","label":"","instr":"...","cycles":1},...]}
        List<String> rows = splitTopLevelObjects(extractArray(json, "\"rows\""));
        List<TraceRow> out = new ArrayList<>();
        for (String r : rows) {
            int index = extractInt(r, "\"index\"\\s*:\\s*(\\d+)", 1, 0);
            String type = extractString(r, "\"type\"");
            String instr = extractString(r, "\"instr\"");
            int cycles = extractInt(r, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0);
            out.add(new TraceRow(index, type, instr, cycles));
        }
        return out;
    }

    /** POST /api/runs/runOnce (form) -> RunResult */
    public RunResult runOnce(String programId, String function, List<Integer> inputs, int degree, String arch) throws IOException {
        // your API set doesn’t include a dedicated RunOnce servlet in the names,
        // but we’ll use /api/runs/runOnce which EngineFacadeImpl supports.
        // If your mapping is different, adjust ONLY this line’s path.
        String url = base + "/api/runs/runOnce";

        Map<String,String> params = new LinkedHashMap<>();
        params.put("programId", programId);
        params.put("function", function);
        params.put("degree", String.valueOf(degree));
        params.put("arch", arch == null ? "" : arch);
        params.put("inputs", joinInts(inputs)); // e.g., "7,3"

        String json = postForm(url, params);
        // {"y":..,"cycles":..,"variables":{"y":..,"x1":..}, "trace":[{...}]}
        String runId = extractString(json, "\"runId\"");
        int y = extractInt(json, "\"y\"\\s*:\\s*(-?\\d+)", 1, 0);
        int cycles = extractInt(json, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0);
        Map<String,Integer> vars = extractVars(json, "\"variables\"");
        List<TraceRow> trace = extractTrace(json, "\"trace\"");
        return new RunResult(runId, y, cycles, vars, trace);
    }

    /** POST /api/runs/start (form) -> Debug session + first state */
    public DebugState startDebug(String programId, String function, List<Integer> inputs, int degree, String arch) throws IOException {
        String url = base + "/api/runs/start";
        Map<String,String> params = new LinkedHashMap<>();
        params.put("programId", programId);
        params.put("function", function);
        params.put("degree", String.valueOf(degree));
        params.put("arch", arch == null ? "" : arch);
        params.put("inputs", joinInts(inputs));

        String json = postForm(url, params);
        return parseDebugState(json);
    }

    /** POST /api/runs/step (form runId) */
    public DebugState step(String runId) throws IOException {
        String json = postForm(base + "/api/runs/step", Map.of("runId", runId));
        return parseDebugState(json);
    }

    /** POST /api/runs/resume (form runId) */
    public DebugState resume(String runId) throws IOException {
        String json = postForm(base + "/api/runs/resume", Map.of("runId", runId));
        return parseDebugState(json);
    }

    /** POST /api/runs/stop (form runId) */
    public DebugState stop(String runId) throws IOException {
        String json = postForm(base + "/api/runs/stop", Map.of("runId", runId));
        return parseDebugState(json);
    }

    /* =================== HTTP helpers =================== */

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return s; }
    }
    private static String joinInts(List<Integer> xs) {
        if (xs == null || xs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<xs.size();i++) { if (i>0) sb.append(','); sb.append(xs.get(i)); }
        return sb.toString();
    }

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setDoInput(true);
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes()); }
        catch (IOException e) {
            InputStream err = c.getErrorStream();
            String msg = (err != null) ? new String(err.readAllBytes()) : e.getMessage();
            throw new IOException(urlStr + "\n" + msg, e);
        }
    }

    private String postForm(String urlStr, Map<String,String> params) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String,String> e : params.entrySet()) {
            if (!first) body.append('&'); first = false;
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()==null?"":e.getValue()));
        }

        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes()); }

        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes()); }
        catch (IOException e) {
            InputStream err = c.getErrorStream();
            String msg = (err != null) ? new String(err.readAllBytes()) : e.getMessage();
            throw new IOException(urlStr + "\n" + msg, e);
        }
    }

    private String postMultipart(String urlStr, String fieldName, Path file) throws IOException {
        String boundary = "----SBoundary" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = c.getOutputStream()) {
            var w = new PrintWriter(new OutputStreamWriter(out, "UTF-8"), true);

            w.append("--").append(boundary).append("\r\n");
            w.append("Content-Disposition: form-data; name=\"").append(fieldName)
                    .append("\"; filename=\"").append(file.getFileName().toString()).append("\"\r\n");
            w.append("Content-Type: application/xml\r\n\r\n").flush();

            Files.copy(file, out);
            out.flush();

            w.append("\r\n").flush();
            w.append("--").append(boundary).append("--").append("\r\n").flush();
        }

        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes()); }
        catch (IOException e) {
            InputStream err = c.getErrorStream();
            String msg = (err != null) ? new String(err.readAllBytes()) : e.getMessage();
            throw new IOException(urlStr + "\n" + msg, e);
        }
    }

    /* =================== Tiny JSON-ish parsing helpers =================== */

    private static String extractObject(String text, String prefixRegex, String closing) {
        Pattern p = Pattern.compile(prefixRegex);
        Matcher m = p.matcher(text);
        if (!m.find()) return "{}";
        int start = m.end(); int depth = 1; int i = start;
        for (; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) break; }
        }
        return text.substring(start, Math.min(i, text.length()-1));
    }
    private static String extractArray(String text, String keyRegex) {
        Pattern p = Pattern.compile(keyRegex + "\\s*:\\s*\\[");
        Matcher m = p.matcher(text);
        if (!m.find()) return "[]";
        int start = m.end(); int depth = 1; int i = start;
        for (; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']') { depth--; if (depth == 0) break; }
        }
        return text.substring(start, Math.min(i, text.length()-1));
    }
    private static List<String> splitTopLevelObjects(String arrayInner) {
        List<String> out = new ArrayList<>();
        int depth = 0; int start = -1;
        for (int i=0;i<arrayInner.length();i++) {
            char ch = arrayInner.charAt(i);
            if (ch == '{') { if (depth==0) start = i+1; depth++; }
            else if (ch == '}') { depth--; if (depth==0 && start>=0) { out.add(arrayInner.substring(start-1, i+1)); start = -1; } }
        }
        return out;
    }
    private static String extractString(String text, String keyLiteralRegex) {
        Pattern p = Pattern.compile(keyLiteralRegex + "\\s*:\\s*\"(.*?)\"");
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }
    private static int extractInt(String text, String regex, int group, int def) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        return m.find() ? Integer.parseInt(m.group(group)) : def;
    }
    private static List<String> extractStringArray(String text, String keyLiteralRegex) {
        String arr = extractArray(text, keyLiteralRegex);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"(.*?)\"").matcher(arr);
        while (m.find()) out.add(m.group(1));
        return out;
    }
    private static Map<String,Integer> extractVars(String json, String keyLiteralRegex) {
        String obj = extractObject(json, keyLiteralRegex + "\\s*:\\s*\\{", "}");
        Map<String,Integer> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)").matcher(obj);
        while (m.find()) out.put(m.group(1), Integer.parseInt(m.group(2)));
        return out;
    }
    private static List<TraceRow> extractTrace(String json, String keyLiteralRegex) {
        List<String> rows = splitTopLevelObjects(extractArray(json, keyLiteralRegex));
        List<TraceRow> out = new ArrayList<>();
        for (String r : rows) {
            int index = extractInt(r, "\"index\"\\s*:\\s*(\\d+)", 1, 0);
            String type = extractString(r, "\"type\"");
            String instr = extractString(r, "\"instr\"");
            int cycles = extractInt(r, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0);
            out.add(new TraceRow(index, type, instr, cycles));
        }
        return out;
    }
    private DebugState parseDebugState(String json) {
        String runId = extractString(json, "\"runId\"");
        int pc = extractInt(json, "\"pc\"\\s*:\\s*(\\d+)", 1, 0);
        int cycles = extractInt(json, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0);
        boolean halted = json.contains("\"halted\"\\s*:\\s*true");
        Map<String,Integer> vars = extractVars(json, "\"variables\"");
        // Current row:
        String curr = extractObject(json, "\"current\"\\s*:\\s*\\{", "}");
        TraceRow tr = curr.isEmpty() ? null :
                new TraceRow(
                        extractInt(curr, "\"index\"\\s*:\\s*(\\d+)", 1, 0),
                        extractString(curr, "\"type\""),
                        extractString(curr, "\"instr\""),
                        extractInt(curr, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0)
                );
        return new DebugState(runId, pc, cycles, halted, vars, tr);
    }
}
