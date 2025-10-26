package gui;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Thin HTTP client used by MainController to talk to s-server.
 * No external JSON deps; includes a tiny MiniJson for simple Maps/Lists.
 */
public class EngineAdapter {

    private final String base; // e.g. http://localhost:8080/s-server/api

    /** Default base for local Tomcat */
    public EngineAdapter() {
        this("http://localhost:8080/s-server/api");
    }

    public EngineAdapter(String baseUrl) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /* -------------------- Public API used by MainController -------------------- */

    public Map<String, Object> login(String username) throws IOException {
        Map<String, Object> body = Map.of("username", username);
        return postJson("/login", body);
    }

    public Map<String, Object> uploadProgram(File xmlFile, String userId) throws IOException {
        String xml = Files.readString(xmlFile.toPath(), StandardCharsets.UTF_8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("name", xmlFile.getName());
        body.put("xml", xml);
        return postJson("/programs:upload", body);
    }

    /** Optional: may 404 if server doesn’t provide it; caller should catch. */
    public Map<String, Object> programMeta(String programId) throws IOException {
        return postJson("/program:meta", Map.of("programId", programId));
    }

    /** Expand program to degree. Returns map with keys: lines(List<String>), sumCycles(int). */
    public Map<String, Object> expand(String programId, String function, int degree) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("programId", programId);
        body.put("function", function);
        body.put("degree", degree);
        return postJson("/program:expand", body);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> startRun(String userId, String programId, int degree,
                                        List<Integer> inputs, boolean debug) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("programId", programId);
        body.put("function", "main");
        body.put("degree", degree);
        body.put("inputs", inputs == null ? List.of() : inputs);
        body.put("architecture", "Basic");
        body.put("debug", debug);
        return postJson("/runs", body);
    }

    public Map<String, Object> getRunStatus(long runId) throws IOException {
        return postJson("/runs/status", Map.of("runId", String.valueOf(runId)));
    }

    public Map<String, Object> resume(long runId) throws IOException {
        return postJson("/runs/resume", Map.of("runId", String.valueOf(runId)));
    }

    public Map<String, Object> step(long runId) throws IOException {
        return postJson("/runs/step", Map.of("runId", String.valueOf(runId)));
    }

    public Map<String, Object> stop(long runId) throws IOException {
        return postJson("/runs/stop", Map.of("runId", String.valueOf(runId)));
    }

    /* -------------------- HTTP helpers -------------------- */

    private Map<String, Object> postJson(String path, Map<String, Object> body) throws IOException {
        String url = base + path;
        String payload = MiniJson.stringify(body);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " – " + text);
        }
        Object parsed = MiniJson.parse(text);
        if (parsed instanceof Map<?,?> m) {
            //noinspection unchecked
            return (Map<String, Object>) m;
        }
        return Map.of("value", parsed);
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            for (String line; (line = br.readLine()) != null; ) sb.append(line);
            return sb.toString();
        }
    }

    /* -------------------- Minimal JSON -------------------- */

    static final class MiniJson {
        static String stringify(Object o) {
            StringBuilder sb = new StringBuilder();
            writeVal(sb, o);
            return sb.toString();
        }

        static Object parse(String s) {
            return new Parser(s == null ? "" : s).parseValue();
        }

        private static void writeVal(StringBuilder sb, Object v) {
            if (v == null) { sb.append("null"); return; }
            if (v instanceof String str) { writeStr(sb, str); return; }
            if (v instanceof Number || v instanceof Boolean) { sb.append(String.valueOf(v)); return; }
            if (v instanceof Map<?,?> map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?,?> e : map.entrySet()) {
                    if (!first) sb.append(',');
                    writeStr(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeVal(sb, e.getValue());
                    first = false;
                }
                sb.append('}');
                return;
            }
            if (v instanceof Iterable<?> it) {
                sb.append('[');
                boolean first = true;
                for (Object x : it) {
                    if (!first) sb.append(',');
                    writeVal(sb, x);
                    first = false;
                }
                sb.append(']');
                return;
            }
            writeStr(sb, String.valueOf(v));
        }

        private static void writeStr(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }

        private static final class Parser {
            final String s; int i=0, n;
            Parser(String s){ this.s=s; this.n=s.length(); }

            Object parseValue() {
                skipWs();
                if (i>=n) return null;
                char c = s.charAt(i);
                if (c=='{') return parseObj();
                if (c=='[') return parseArr();
                if (c=='"') return parseStr();
                if (c=='t' || c=='f') return parseBool();
                if (c=='n') return parseNull();
                return parseNumOrToken();
            }

            Map<String,Object> parseObj() {
                i++;
                Map<String,Object> m = new LinkedHashMap<>();
                skipWs();
                if (peek('}')) { i++; return m; }
                while (true) {
                    String k = parseStr();
                    skipWs(); expect(':'); i++;
                    Object v = parseValue();
                    m.put(k, v);
                    skipWs();
                    if (peek('}')) { i++; break; }
                    expect(','); i++;
                    skipWs();
                }
                return m;
            }

            List<Object> parseArr() {
                i++;
                List<Object> list = new ArrayList<>();
                skipWs();
                if (peek(']')) { i++; return list; }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    if (peek(']')) { i++; break; }
                    expect(','); i++;
                    skipWs();
                }
                return list;
            }

            String parseStr() {
                expect('"'); i++;
                StringBuilder sb = new StringBuilder();
                while (i<n) {
                    char c = s.charAt(i++);
                    if (c=='"') break;
                    if (c=='\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': {
                                int code = Integer.parseInt(s.substring(i, i+4), 16);
                                sb.append((char) code);
                                i += 4; break;
                            }
                            default: sb.append(e);
                        }
                    } else sb.append(c);
                }
                return sb.toString();
            }

            Boolean parseBool() {
                if (s.startsWith("true", i)) { i+=4; return Boolean.TRUE; }
                if (s.startsWith("false", i)) { i+=5; return Boolean.FALSE; }
                throw new RuntimeException("Invalid boolean at " + i);
            }

            Object parseNull() {
                if (s.startsWith("null", i)) { i+=4; return null; }
                throw new RuntimeException("Invalid null at " + i);
            }

            Object parseNumOrToken() {
                int j=i;
                while (i<n) {
                    char c = s.charAt(i);
                    if ((c>='0' && c<='9') || c=='+' || c=='-' || c=='.' || c=='e' || c=='E') { i++; continue; }
                    break;
                }
                String token = s.substring(j,i).trim();
                try {
                    if (token.contains(".") || token.contains("e") || token.contains("E")) return Double.parseDouble(token);
                    long L = Long.parseLong(token);
                    if (L >= Integer.MIN_VALUE && L <= Integer.MAX_VALUE) return (int)L;
                    return L;
                } catch (NumberFormatException e) {
                    return token;
                }
            }

            void skipWs(){ while (i<n && Character.isWhitespace(s.charAt(i))) i++; }
            boolean peek(char c){ return i<n && s.charAt(i)==c; }
            void expect(char c){ if (!peek(c)) throw new RuntimeException("Expected '"+c+"' at "+i); }
        }
    }
}
