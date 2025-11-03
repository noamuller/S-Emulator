package gui;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for S-Server (API-first).
 * Uses only /api/... endpoints to avoid legacy path confusion.
 * Keeps session cookies; tolerant parsing; better error surfacing.
 */
public class EngineAdapter {

    /* ===================== DTOs ===================== */

    public static final class UserDto {
        public final String id, username;
        public final int credits;
        public UserDto(String id, String username, int credits) {
            this.id = id; this.username = username; this.credits = credits;
        }
    }

    public static final class ProgramInfo {
        private final String name, id;
        private final int maxDegree;
        private final List<String> functions;
        public ProgramInfo(String name, String id, int maxDegree, List<String> functions) {
            this.name = name; this.id = id; this.maxDegree = maxDegree; this.functions = functions;
        }
        public String getName() { return name; }
        public String getId() { return id; }
        public int getMaxDegree() { return maxDegree; }
        public List<String> getFunctions() { return functions; }
    }

    public static final class TraceRow {
        private final int index, cycles;
        private final String type, text;
        public TraceRow(int index, String type, String text, int cycles) {
            this.index = index; this.type = type; this.text = text; this.cycles = cycles;
        }
        public int index() { return index; }
        public String type() { return type; }
        public String text() { return text; }
        public int cycles() { return cycles; }
    }

    public static final class RunResult {
        public final int y, cycles;
        public final Map<String,Integer> vars;
        public RunResult(int y, int cycles, Map<String,Integer> vars) { this.y=y; this.cycles=cycles; this.vars=vars; }
    }

    public static final class DebugState {
        public final String runId;
        public final int cycles;
        public final TraceRow current;
        public final Map<String,Integer> vars;
        public DebugState(String runId, int cycles, TraceRow current, Map<String,Integer> vars) {
            this.runId = runId; this.cycles = cycles; this.current = current; this.vars = vars;
        }
    }

    /* ===================== Core ===================== */

    private final String base; // e.g. http://localhost:8080/s-server

    static {
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cm);
    }

    public EngineAdapter(String baseUrl) {
        // strip trailing slash to normalize
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
    }

    private String api(String path) { // ensure single slash
        return base + (path.startsWith("/") ? "" : "/") + path;
    }

    /* ===================== Users ===================== */

    public UserDto loginAny(String username) throws IOException {
        // Only /api variants
        String[][] tries = {
                { "/api/users/loginFull", "name" },
                { "/api/users/loginFull", "username" },
                { "/api/users/login",     "name" },
                { "/api/users/login",     "username" },
                { "/api/login",           "name" },
                { "/api/login",           "username" }
        };
        return tryFormsUser(tries, Map.of("name", username, "username", username));
    }

    private UserDto tryFormsUser(String[][] endpoints, Map<String,String> params) throws IOException {
        IOException last = null;
        for (String[] ep : endpoints) {
            String url = api(ep[0]);
            String key = ep[1];
            try {
                String json = postForm(url, Map.of(key, params.get(key)));
                String err = extract(json, "\"error\"\\s*:\\s*\"([^\"]+)\"");
                if (err != null && !err.isBlank()) throw new IOException(url + " → " + err);
                String id = first(extract(json,"\"id\"\\s*:\\s*\"([^\"]+)\""),
                        extract(json,"\"userId\"\\s*:\\s*\"([^\"]+)\""), params.get(key));
                String uname = first(extract(json,"\"username\"\\s*:\\s*\"([^\"]+)\""),
                        extract(json,"\"name\"\\s*:\\s*\"([^\"]+)\""), params.get(key));
                int credits = extractInt(json, "\"credits\"\\s*:\\s*(-?\\d+)", 1, 0);
                return new UserDto(id, uname, credits);
            } catch (FileNotFoundException nf) { last = nf; }
            catch (IOException ioe)         { last = ioe; }
        }
        throw (last != null ? last : new IOException("login: no working /api endpoint"));
    }

    /* ===================== Credits ===================== */

    public int chargeCreditsAny(String userId, int amount) throws IOException {
        String[][] tries = {
                { "/api/credits/charge", "amount,userId" },
                { "/api/users/charge",   "amount,userId" },
                { "/api/credits/add",    "amount,userId" }
        };
        IOException last = null;
        for (String[] t : tries) {
            String url = api(t[0]);
            try {
                Map<String,String> form = new LinkedHashMap<>();
                form.put("amount", String.valueOf(amount));
                if (userId != null && !userId.isBlank()) form.put("userId", userId);
                String json = postForm(url, form);
                String err = extract(json, "\"error\"\\s*:\\s*\"([^\"]+)\"");
                if (err != null && !err.isBlank()) throw new IOException(url + " → " + err);
                int credits = extractInt(json, "\"credits\"\\s*:\\s*(-?\\d+)", 1, Integer.MIN_VALUE);
                if (credits != Integer.MIN_VALUE) return credits;
                credits = extractInt(json, "\"user\"\\s*:\\s*\\{[^}]*\"credits\"\\s*:\\s*(-?\\d+)", 1, Integer.MIN_VALUE);
                if (credits != Integer.MIN_VALUE) return credits;
                last = new IOException(url + " → unexpected response: " + json);
            } catch (FileNotFoundException nf) { last = nf; }
            catch (IOException ioe)         { last = ioe; }
        }
        throw (last != null ? last : new IOException("charge: no working /api endpoint"));
    }

    /* ===================== Programs ===================== */

    public ProgramInfo uploadXmlAny(Path file) throws IOException {
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        String[] paths = {
                "/api/programs/upload",
                "/api/programs/load",
                "/api/programs" // some servers POST-create
        };

        IOException last = null;

        // multipart first
        for (String p : paths) {
            String url = api(p);
            try {
                String json = postMultipart(url, "file", file.getFileName().toString(),
                        "application/xml", xml.getBytes(StandardCharsets.UTF_8));
                return parseProgramInfo(json);
            } catch (FileNotFoundException nf) { last = nf; }
            catch (IOException ioe)         { last = ioe; }
        }
        // urlencoded fallbacks
        for (String p : paths) {
            String url = api(p);
            for (String key : new String[]{"xml","text","content","programXml"}) {
                try {
                    String json = postForm(url, Map.of(key, xml));
                    return parseProgramInfo(json);
                } catch (FileNotFoundException nf) { last = nf; }
                catch (IOException ioe)         { last = ioe; }
            }
        }
        throw (last != null ? last : new IOException("uploadXml: no working /api endpoint"));
    }

    public List<TraceRow> expand(String programId, String function, int degree) throws IOException {
        String url = api(String.format("/api/programs/expand?programId=%s&function=%s&degree=%d",
                enc(programId), enc(function), degree));
        return parseTraceRows(get(url));
    }

    public RunResult runOnce(String programId, String function, List<Integer> inputs, int degree, String arch) throws IOException {
        String url = api("/api/runs/start");
        Map<String,String> form = new LinkedHashMap<>();
        form.put("programId", programId);
        form.put("function", function);
        form.put("degree", String.valueOf(degree));
        form.put("arch", arch == null ? "" : arch);
        form.put("inputs", joinInts(inputs));
        String json = postForm(url, form);
        String err = extract(json, "\"error\"\\s*:\\s*\"([^\"]+)\"");
        if (err != null && !err.isBlank()) throw new IOException(url + " → " + err);
        int y = extractInt(json, "\"y\"\\s*:\\s*(-?\\d+)", 1, 0);
        int cycles = extractInt(json, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0);
        Map<String,Integer> vars = extractVars(json);
        return new RunResult(y, cycles, vars);
    }

    public DebugState startDebug(String programId, String function, List<Integer> inputs, int degree, String arch) throws IOException {
        String url = api("/api/debug/start");
        Map<String,String> form = new LinkedHashMap<>();
        form.put("programId", programId);
        form.put("function", function);
        form.put("degree", String.valueOf(degree));
        form.put("arch", arch == null ? "" : arch);
        form.put("inputs", joinInts(inputs));
        return parseDebugState(postForm(url, form));
    }
    public DebugState resume(String runId) throws IOException { return parseDebugState(postForm(api("/api/debug/resume"), Map.of("runId", runId))); }
    public DebugState step  (String runId) throws IOException { return parseDebugState(postForm(api("/api/debug/step"),   Map.of("runId", runId))); }
    public DebugState stop  (String runId) throws IOException { return parseDebugState(postForm(api("/api/debug/stop"),   Map.of("runId", runId))); }

    /* ===================== HTTP helpers ===================== */

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private String get(String urlStr) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        try (InputStream is = okStream(c)) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private String postForm(String urlStr, Map<String,String> form) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String,String> e : form.entrySet()) {
            if (sb.length()>0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue()==null?"":e.getValue(), StandardCharsets.UTF_8));
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        try (OutputStream os = c.getOutputStream()) { os.write(payload); }
        try (InputStream is = okStream(c)) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private String postMultipart(String urlStr, String field, String filename, String mime, byte[] data) throws IOException {
        String boundary = "----SConsoleBoundary" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        try (OutputStream os = c.getOutputStream(); DataOutputStream dos = new DataOutputStream(os)) {
            dos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            dos.write(("Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            dos.write(("Content-Type: " + (mime == null ? "application/octet-stream" : mime) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            dos.write(data);
            dos.write("\r\n--".getBytes(StandardCharsets.UTF_8));
            dos.write((boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = okStream(c)) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static InputStream okStream(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code >= 200 && code < 300) return c.getInputStream();
        InputStream es = c.getErrorStream();
        String msg = (es != null) ? new String(es.readAllBytes(), StandardCharsets.UTF_8)
                : ("HTTP " + code + " " + c.getResponseMessage());
        if (code == 404) throw new FileNotFoundException(msg);
        throw new IOException(msg);
    }

    /* ===================== Parsing helpers ===================== */

    private static String first(String... xs) {
        for (String s : xs) if (s != null && !s.isBlank()) return s;
        return null;
    }
    private static int extractInt(String text, String regex, int group, int def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? Integer.parseInt(m.group(group)) : def;
    }
    private static String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }
    private static List<String> extractArray(String json, String arrayRegex) {
        Matcher m = Pattern.compile(arrayRegex, Pattern.DOTALL).matcher(json);
        if (!m.find()) return List.of();
        String inner = m.group(1);
        Matcher sm = Pattern.compile("\"([^\"]+)\"").matcher(inner);
        List<String> out = new ArrayList<>();
        while (sm.find()) out.add(sm.group(1));
        return out;
    }
    private static Map<String,Integer> extractVars(String json) {
        Map<String,Integer> out = new TreeMap<>();
        Matcher m = Pattern.compile("\"vars\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(json);
        if (!m.find()) return out;
        String inner = m.group(1);
        Matcher p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)").matcher(inner);
        while (p.find()) out.put(p.group(1), Integer.parseInt(p.group(2)));
        return out;
    }

    private ProgramInfo parseProgramInfo(String json) {
        String name = first(extract(json, "\"name\"\\s*:\\s*\"([^\"]+)\""), "Program");
        String id   = first(extract(json, "\"id\"\\s*:\\s*\"([^\"]+)\""), UUID.randomUUID().toString());
        int maxDeg  = extractInt(json, "\"maxDegree\"\\s*:\\s*(\\d+)", 1, 0);
        List<String> fns = extractArray(json, "\"functions\"\\s*:\\s*\\[(.*?)\\]");
        return new ProgramInfo(name, id, maxDeg, fns);
    }

    private static List<TraceRow> parseTraceRows(String json) {
        List<TraceRow> rows = new ArrayList<>();

        // Find the "rows": [ ... ] part
        java.util.regex.Pattern rowsPattern =
                java.util.regex.Pattern.compile("\"rows\"\\s*:\\s*\\[(.*?)]", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher rowsMatcher = rowsPattern.matcher(json);
        if (!rowsMatcher.find()) {
            return rows;
        }
        String inner = rowsMatcher.group(1);

        // Each row is an object { ... }
        java.util.regex.Pattern objPattern =
                java.util.regex.Pattern.compile("\\{([^}]*)}", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher rowMatcher = objPattern.matcher(inner);

        while (rowMatcher.find()) {
            String r = rowMatcher.group(1);

            // index
            int index = 0;
            java.util.regex.Matcher mIndex =
                    java.util.regex.Pattern.compile("\"index\"\\s*:\\s*(\\d+)").matcher(r);
            if (mIndex.find()) {
                try {
                    index = Integer.parseInt(mIndex.group(1));
                } catch (NumberFormatException ignored) {
                }
            }

            // type
            String type = "";
            java.util.regex.Matcher mType =
                    java.util.regex.Pattern.compile("\"type\"\\s*:\\s*\"([^\"]*)\"").matcher(r);
            if (mType.find()) {
                type = mType.group(1);
            }

            // instruction text: "text" (old) OR "instr" (new)
            String text = "";
            java.util.regex.Matcher mText =
                    java.util.regex.Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"").matcher(r);
            if (mText.find()) {
                text = mText.group(1);
            } else {
                java.util.regex.Matcher mInstr =
                        java.util.regex.Pattern.compile("\"instr\"\\s*:\\s*\"([^\"]*)\"").matcher(r);
                if (mInstr.find()) {
                    text = mInstr.group(1);
                }
            }

            // cycles
            int cycles = 0;
            java.util.regex.Matcher mCycles =
                    java.util.regex.Pattern.compile("\"cycles\"\\s*:\\s*(\\d+)").matcher(r);
            if (mCycles.find()) {
                try {
                    cycles = Integer.parseInt(mCycles.group(1));
                } catch (NumberFormatException ignored) {
                }
            }

            rows.add(new TraceRow(index, type, text, cycles));
        }

        return rows;
    }



    private static String joinInts(List<Integer> xs) {
        if (xs == null || xs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<xs.size();i++) { if (i>0) sb.append(','); sb.append(xs.get(i)); }
        return sb.toString();
    }

    private DebugState parseDebugState(String json) {
        String runId = first(extract(json, "\"runId\"\\s*:\\s*\"([^\"]+)\""), "");
        int cycles = extractInt(json, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0);
        TraceRow cur = null;
        Matcher curM = Pattern.compile("\"current\"\\s*:\\s*\\{([^}]*)}").matcher(json);
        if (curM.find()) {
            String r = curM.group(1);
            int idx = extractInt(r, "\"index\"\\s*:\\s*(\\d+)", 1, 0);
            String type = first(extract(r, "\"type\"\\s*:\\s*\"([^\"]+)\""), "");
            String text = first(extract(r, "\"text\"\\s*:\\s*\"([^\"]*)\""), "");
            int cyc = extractInt(r, "\"cycles\"\\s*:\\s*(\\d+)", 1, 0);
            cur = new TraceRow(idx, type, text, cyc);
        }
        Map<String,Integer> vars = extractVars(json);
        return new DebugState(runId, cycles, cur, vars);
    }
}
