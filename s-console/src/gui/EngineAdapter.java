package gui;

import javafx.util.Pair;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class EngineAdapter {

    // Base URL of your Tomcat webapp:
    private static final String BASE = "http://localhost:8080/s-server";

    private final HttpClient http = HttpClient.newHttpClient();

    private String userId;       // current user
    private String programId;    // last uploaded / selected program
    private int maxDegree = 0;

    // Debug/run session
    private Long runId = null;
    private boolean halted = true;
    private int cycles = 0;
    private int pc = -1;
    private String currentInstruction = null;
    private LinkedHashMap<String,Integer> vars = new LinkedHashMap<>();
    private Map<String,Integer> changed = new LinkedHashMap<>();
    private final StringBuilder log = new StringBuilder();

    /* ===== Public API used by MainController ===== */

    public boolean isLoaded() { return programId != null; }

    public void load(File xml) throws Exception {
        ensureUser();
        // 1) upload
        var mp = multipartUpload(xml, Map.of("userId", userId));
        var resp = postRaw("/api/programs:upload", mp.contentType(), mp.bytes());
        Map<String,Object> up = (Map<String,Object>) json(resp);
        programId = String.valueOf(up.get("id"));

        // 2) fetch meta (original rows + maxDegree)
        Map<String,Object> meta = (Map<String,Object>) json(get("/api/programs/meta?userId="+enc(userId)+"&programId="+enc(programId)));
        maxDegree = ((Number) meta.getOrDefault("maxDegree", 0)).intValue();

        // cache original rows
        this.originalRows = (List<Object>) meta.getOrDefault("rows", List.of());
        this.expandedCache.clear();
    }

    public int getMaxDegree() { return maxDegree; }

    public List<Object> getOriginalRows() {
        return originalRows == null ? List.of() : originalRows;
    }

    public List<Object> getExpandedRows(int degree) throws Exception {
        degree = Math.max(0, Math.min(degree, maxDegree));
        if (degree == 0) return getOriginalRows();
        if (expandedCache.containsKey(degree)) return expandedCache.get(degree);
        String url = "/api/programs/expand?userId="+enc(userId)+"&programId="+enc(programId)+"&degree="+degree;
        List<Object> rows = (List<Object>) json(get(url));
        expandedCache.put(degree, rows);
        return rows;
    }

    public void dbgStart(int degree, List<Integer> inputs) throws Exception {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("programId", programId);
        body.put("degree", degree);
        body.put("inputs", inputs == null ? List.of() : inputs);
        body.put("mode", "debug");

        Map<String,Object> s = (Map<String,Object>) json(post("/api/runs", body));
        readRunState(s);
    }

    public void dbgResume() throws Exception {
        ensureRun();
        Map<String,Object> s = (Map<String,Object>) json(post("/api/runs/resume", Map.of("runId", runId)));
        readRunState(s);
    }

    public void dbgStep() throws Exception {
        ensureRun();
        Map<String,Object> s = (Map<String,Object>) json(post("/api/runs/step", Map.of("runId", runId)));
        readRunState(s);
    }

    public void dbgStop() throws Exception {
        ensureRun();
        Map<String,Object> s = (Map<String,Object>) json(post("/api/runs/stop", Map.of("runId", runId)));
        readRunState(s);
    }

    public boolean isHalted() { return halted; }
    public int getCycles() { return cycles; }
    public int getCurrentY() { return vars.getOrDefault("y", 0); }
    public int getPcIndex() { return pc; }
    public String getPcText() {
        if (pc < 0) return "(halted)";
        return "PC = " + pc + (currentInstruction == null ? "" : "  |  " + currentInstruction);
    }
    public Map<String,Integer> getVars() { return vars; }
    public Map<String,Integer> getChanged() { return changed; }
    public String getLog() { return log.toString(); }

    public List<Object> getDebuggerRows() throws Exception {
        // show current expanded rows at the same degree the run started with.
        if (this.lastDegree <= 0) return getOriginalRows();
        return getExpandedRows(this.lastDegree);
    }

    /* ===== internals ===== */

    private List<Object> originalRows;
    private final Map<Integer,List<Object>> expandedCache = new HashMap<>();
    private int lastDegree = 0;

    private void ensureUser() throws Exception {
        if (userId != null) return;
        // Minimal auto-login: create/get "Noa" as in MainController demo
        var form = new String("username=Noa").getBytes(StandardCharsets.UTF_8);
        var r = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/login"))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofByteArray(form)).build(), HttpResponse.BodyHandlers.ofString());
        Map<String,Object> me = (Map<String,Object>) json(r.body());
        userId = String.valueOf(me.get("userId"));
    }

    private void ensureRun() {
        if (runId == null) throw new IllegalStateException("No active debug run. Start first.");
    }

    private void readRunState(Map<String,Object> s) {
        this.runId = ((Number)s.get("runId")).longValue();
        this.halted = Boolean.TRUE.equals(s.get("finished")) || "DONE".equalsIgnoreCase(String.valueOf(s.get("state")));
        this.pc = ((Number) s.getOrDefault("pc", -1)).intValue();
        this.cycles = ((Number) s.getOrDefault("cycles", 0)).intValue();
        this.currentInstruction = Objects.toString(s.get("currentInstruction"), null);
        this.lastDegree = Math.max(lastDegree, 0); // we keep degree separately
        Object mv = s.get("variables");
        LinkedHashMap<String,Integer> newVars = new LinkedHashMap<>();
        if (mv instanceof Map<?,?> m) {
            for (var e : m.entrySet()) {
                newVars.put(String.valueOf(e.getKey()), ((Number)e.getValue()).intValue());
            }
        }
        // compute changed vars
        Map<String,Integer> diff = new LinkedHashMap<>();
        for (var e : newVars.entrySet()) {
            Integer old = vars.get(e.getKey());
            if (old == null || !old.equals(e.getValue())) diff.put(e.getKey(), e.getValue());
        }
        this.changed = diff;
        this.vars = newVars;
        if (currentInstruction != null) log.append(currentInstruction).append('\n');
    }

    /* ===== HTTP helpers ===== */

    private Object json(String s) throws RuntimeException {
        return Mini.parse(s);
    }

    private String get(String pathAndQuery) throws IOException, InterruptedException {
        var r = http.send(HttpRequest.newBuilder(URI.create(BASE + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("GET " + pathAndQuery + " -> " + r.statusCode() + " : " + r.body());
        return r.body();
    }

    private String post(String path, Map<String,Object> body) throws IOException, InterruptedException {
        var r = http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                        .header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Mini.stringify(body), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("POST " + path + " -> " + r.statusCode() + " : " + r.body());
        return r.body();
    }

    private String postRaw(String path, String contentType, byte[] bytes) throws IOException, InterruptedException {
        var r = http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("POST " + path + " -> " + r.statusCode() + " : " + r.body());
        return r.body();
    }

    private static String enc(String s){ return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static Multipart multipartUpload(File xml, Map<String,String> fields) throws IOException {
        String boundary = "----Semu" + System.currentTimeMillis();
        var out = new ByteArrayOutputStream();
        var w = new OutputStreamWriter(out, StandardCharsets.UTF_8);

        for (var e : fields.entrySet()) {
            w.write("--" + boundary + "\r\n");
            w.write("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n");
            w.write(e.getValue() + "\r\n");
        }
        w.write("--" + boundary + "\r\n");
        w.write("Content-Disposition: form-data; name=\"file\"; filename=\"" + xml.getName() + "\"\r\n");
        w.write("Content-Type: application/xml\r\n\r\n");
        w.flush();
        Files.copy(xml.toPath(), out);
        w.write("\r\n--" + boundary + "--\r\n");
        w.flush();
        return new Multipart("multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    private record Multipart(String contentType, byte[] bytes){}

    /* === Minimal JSON parser from StartRunServlet.Mini === */
    static final class Mini {
        static Object parse(String s){ return new P(s==null? "": s).v(); }
        static String stringify(Object o){ StringBuilder b=new StringBuilder(); w(o,b); return b.toString(); }
        @SuppressWarnings("unchecked") static void w(Object o,StringBuilder b){
            if(o==null){b.append("null");return;}
            if(o instanceof String){b.append('"').append(((String)o).replace("\\","\\\\").replace("\"","\\\"")).append('"');return;}
            if(o instanceof Number||o instanceof Boolean){b.append(o.toString());return;}
            if(o instanceof Map){b.append('{');boolean f=true;for(var e:((Map<String,Object>)o).entrySet()){if(!f)b.append(',');f=false;w(String.valueOf(e.getKey()),b);b.append(':');w(e.getValue(),b);}b.append('}');return;}
            if(o instanceof List){b.append('[');boolean f=true;for(Object x:(List<?>)o){if(!f)b.append(',');f=false;w(x,b);}b.append(']');return;}
            b.append('"').append(String.valueOf(o)).append('"');
        }
        static final class P {
            final String s; int i=0; P(String s){this.s=s;}
            void sp(){ while(i<s.length()){ char c=s.charAt(i); if(c==' '||c=='\n'||c=='\r'||c=='\t') i++; else break; } }
            char peek(){ return i<s.length()? s.charAt(i): '\0'; }
            Object v(){ sp(); char c=peek();
                if(c=='{') return o();
                if(c=='[') return a();
                if(c=='"') return st();
                if(s.startsWith("true",i)){ i+=4; return true; }
                if(s.startsWith("false",i)){ i+=5; return false; }
                if(s.startsWith("null",i)){ i+=4; return null; }
                return num();
            }
            Map<String,Object> o(){
                Map<String,Object> m=new LinkedHashMap<>(); i++; sp();
                if(peek()=='}'){ i++; return m; }
                while(true){
                    sp(); String k=(String)st();
                    if (peek()!=':') throw new IllegalArgumentException("Expected ':'");
                    i++; Object val=v(); m.put(k,val); sp();
                    char c=peek(); if(c==','){ i++; continue; } if(c=='}'){ i++; break; }
                    throw new IllegalArgumentException("Bad object sep at "+i);
                }
                return m;
            }
            List<Object> a(){
                List<Object> l=new ArrayList<>(); i++; sp();
                if(peek()==']'){ i++; return l; }
                while(true){
                    Object val=v(); l.add(val); sp();
                    char c=peek(); if(c==','){ i++; continue; } if(c==']'){ i++; break; }
                    throw new IllegalArgumentException("Bad array sep at "+i);
                }
                return l;
            }
            Object st(){
                StringBuilder b=new StringBuilder(); i++;
                while(i<s.length()){
                    char c=s.charAt(i++);
                    if(c=='"') break;
                    if(c=='\\'){
                        if(i>=s.length()) break;
                        char n=s.charAt(i++);
                        if(n=='"'||n=='\\'||n=='/') b.append(n);
                        else if(n=='n') b.append('\n');
                        else if(n=='t') b.append('\t');
                        else if(n=='r') b.append('\r');
                        else b.append(n);
                    } else b.append(c);
                }
                return b.toString();
            }
            Number num(){
                int j=i;
                while(i<s.length()){
                    char c=s.charAt(i);
                    if((c>='0' && c<='9') || c=='-' || c=='+' || c=='.' || c=='e' || c=='E') i++;
                    else break;
                }
                String t = s.substring(j,i).trim();
                if(t.isEmpty() || "-".equals(t) || "+".equals(t)) return 0;
                try { return Integer.parseInt(t); } catch(Exception ignore){}
                try { return Long.parseLong(t); } catch(Exception ignore){}
                try { return Double.parseDouble(t); } catch(Exception ignore){}
                return 0;
            }
        }
    }
}
