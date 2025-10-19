package gui;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

public class EngineAdapter {

    private final String base; // e.g. http://localhost:8080/s-server/api

    public EngineAdapter(String baseUrl) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
    }

    // ---------- Users ----------
    public Map<String,Object> login(String name) throws IOException {
        return postForm("/login", Map.of("username", name));
    }
    public Map<String,Object> me(String userId) throws IOException {
        return getObject("/me?userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
    }
    public Map<String,Object> charge(String userId, int amount) throws IOException {
        return postForm("/charge", Map.of("userId", userId, "amount", String.valueOf(amount)));
    }

    // ---------- Programs ----------
    public Map<String,Object> uploadProgram(File xml, String userId) throws IOException {
        String boundary = "----SBoundary" + System.currentTimeMillis();
        URL url = new URL(base + "/programs:upload");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = c.getOutputStream();
             PrintWriter w = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {

            // userId part
            w.append("--").append(boundary).append("\r\n");
            w.append("Content-Disposition: form-data; name=\"userId\"\r\n\r\n");
            w.append(userId).append("\r\n");

            // file part
            w.append("--").append(boundary).append("\r\n");
            w.append("Content-Disposition: form-data; name=\"file\"; filename=\"program.xml\"\r\n");
            w.append("Content-Type: application/xml\r\n\r\n").flush();

            try (InputStream is = new FileInputStream(xml)) {
                is.transferTo(os);
            }
            os.flush();
            w.append("\r\n--").append(boundary).append("--\r\n").flush();
        }
        return readJsonObject(c);
    }

    public List<Map<String,Object>> listPrograms(String userId) throws IOException {
        return getArray("/programs?userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
    }

    // ---------- Runs ----------
    public Map<String,Object> startRun(String userId, String programId, int degree, List<Integer> inputs, boolean debug) throws IOException {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("programId", programId);
        body.put("degree", degree);
        body.put("inputs", inputs);
        body.put("mode", debug ? "debug" : "regular");
        return postJson("/runs", body);
    }
    public Map<String,Object> getRunStatus(long runId) throws IOException {
        return getObject("/runs/status?id=" + runId);
    }
    public Map<String,Object> step(long runId) throws IOException {
        return postJson("/runs/step", Map.of("runId", runId));
    }
    public Map<String,Object> resume(long runId) throws IOException {
        return postJson("/runs/resume", Map.of("runId", runId));
    }
    public Map<String,Object> stop(long runId) throws IOException {
        return postJson("/runs/stop", Map.of("runId", runId));
    }

    // ---------- HTTP helpers ----------
    private Map<String,Object> postForm(String path, Map<String,String> form) throws IOException {
        HttpURLConnection c = open("POST", path);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : form.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        try (OutputStream os = c.getOutputStream()) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readJsonObject(c);
    }

    private Map<String,Object> getObject(String path) throws IOException {
        HttpURLConnection c = open("GET", path);
        return readJsonObject(c);
    }
    private List<Map<String,Object>> getArray(String path) throws IOException {
        HttpURLConnection c = open("GET", path);
        return readJsonArray(c);
    }
    private Map<String,Object> postJson(String path, Map<String,Object> body) throws IOException {
        HttpURLConnection c = open("POST", path);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(MiniJson.stringify(body).getBytes(StandardCharsets.UTF_8));
        }
        return readJsonObject(c);
    }
    private HttpURLConnection open(String method, String path) throws IOException {
        URL url = new URL(base + (path.startsWith("/") ? path : "/" + path));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> readJsonObject(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        try (InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()) {
            String s = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return (Map<String, Object>) MiniJson.parse(s);
        }
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> readJsonArray(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        try (InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()) {
            String s = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return (List<Map<String, Object>>) MiniJson.parse(s);
        }
    }

    // ---- Minimal JSON (same as before) ----
    static final class MiniJson {
        static Object parse(String s) { return new Parser(s).parseValue(); }
        static String stringify(Object o) {
            StringBuilder sb = new StringBuilder(); write(o, sb); return sb.toString();
        }
        @SuppressWarnings("unchecked")
        private static void write(Object o, StringBuilder sb) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String) { sb.append('"').append(((String)o).replace("\\","\\\\").replace("\"","\\\"")).append('"'); return; }
            if (o instanceof Number || o instanceof Boolean) { sb.append(o.toString()); return; }
            if (o instanceof Map) {
                sb.append('{'); boolean first = true;
                for (Map.Entry<String,Object> e : ((Map<String,Object>)o).entrySet()) {
                    if (!first) sb.append(','); first = false;
                    sb.append('"').append(e.getKey().replace("\\","\\\\").replace("\"","\\\"")).append("\":");
                    write(e.getValue(), sb);
                } sb.append('}'); return;
            }
            if (o instanceof List) {
                sb.append('['); boolean first = true;
                for (Object x : (List<?>)o) { if (!first) sb.append(','); first=false; write(x, sb); }
                sb.append(']'); return;
            }
            sb.append('"').append(String.valueOf(o)).append('"');
        }
        private static final class Parser {
            private final String s; private int i=0; Parser(String s){this.s=s;}
            Object parseValue(){ skip(); if(i>=s.length()) return null; char c=s.charAt(i);
                if(c=='{') return parseObj(); if(c=='[') return parseArr(); if(c=='"') return parseStr();
                if(c=='t'||c=='f') return parseBool(); if(c=='n'){i+=4;return null;} return parseNum();}
            Map<String,Object> parseObj(){ Map<String,Object> m=new LinkedHashMap<>(); i++; skip();
                if(s.charAt(i)=='}'){i++; return m;} while(true){ String k=parseStr(); skip(); i++; // :
                    Object v=parseValue(); m.put(k,v); skip(); char c=s.charAt(i++); if(c=='}') break; } return m; }
            List<Object> parseArr(){ List<Object>a=new ArrayList<>(); i++; skip();
                if(s.charAt(i)==']'){i++; return a;} while(true){ Object v=parseValue(); a.add(v); skip();
                    char c=s.charAt(i++); if(c==']') break; } return a; }
            String parseStr(){ StringBuilder sb=new StringBuilder(); i++;
                while(true){ char c=s.charAt(i++); if(c=='"') break;
                    if(c=='\\'){ char n=s.charAt(i++); if(n=='"'||n=='\\') sb.append(n); else if(n=='n') sb.append('\n'); else if(n=='t') sb.append('\t'); else sb.append(n); }
                    else sb.append(c); } return sb.toString(); }
            Boolean parseBool(){ if(s.startsWith("true",i)){i+=4; return true;} i+=5; return false; }
            Number parseNum(){ int j=i; while(i<s.length()){ char c=s.charAt(i);
                if((c>='0'&&c<='9')||c=='-'||c=='+') i++; else break; }
                String t=s.substring(j,i);
                try { return Integer.parseInt(t); } catch(Exception ignore) {}
                try { return Long.parseLong(t); } catch(Exception ignore) {}
                return Double.parseDouble(t); }
            void skip(){ while(i<s.length()){ char c=s.charAt(i);
                if(c==' '||c=='\n'||c=='\r'||c=='\t') i++; else break; } }
        }
    }
}
