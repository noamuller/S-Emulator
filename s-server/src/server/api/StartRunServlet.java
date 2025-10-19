package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.RunManager;

@WebServlet(name = "StartRunServlet", urlPatterns = {"/api/runs"})
public class StartRunServlet extends HttpServlet {

    private final RunManager runs = RunManager.get();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        Map<String, Object> body = readJsonObject(req);
        String userId    = Objects.toString(body.get("userId"), "");
        String programId = Objects.toString(body.get("programId"), "");
        int degree       = ((Number) body.getOrDefault("degree", 0)).intValue();

        @SuppressWarnings("unchecked")
        List<Number> xs  = (List<Number>) body.getOrDefault("inputs", List.of());
        List<Integer> inputs = new ArrayList<>();
        for (Number n : xs) inputs.add(n == null ? 0 : n.intValue());

        String modeStr   = Objects.toString(body.getOrDefault("mode", "regular"), "regular").toLowerCase(Locale.ROOT);

        try {
            RunManager.Mode mode = "debug".equals(modeStr) ? RunManager.Mode.DEBUG : RunManager.Mode.REGULAR;
            var s = runs.start(userId, programId, degree, inputs, mode);

            Map<String,Object> out = new LinkedHashMap<>();
            out.put("runId", s.id);
            out.put("state", s.finished ? "DONE" : (mode == RunManager.Mode.DEBUG ? "PAUSED" : "RUNNING"));
            out.put("pc", s.pc);
            out.put("currentInstruction", s.currentInstruction);
            out.put("cycles", s.cycles);
            out.put("variables", s.vars);
            out.put("finished", s.finished);
            resp.getWriter().write(json(out));
        } catch (Exception ex) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"" + ex.getMessage().replace("\"","'") + "\"}");
        }
    }

    // ---- JSON helpers ----
    @SuppressWarnings("unchecked")
    private Map<String,Object> readJsonObject(HttpServletRequest req) throws IOException {
        String s = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // very small tolerant parser: try Jackson-like structure via org.json is not allowed,
        // so we accept an empty map if parsing isn't available. Client code sends correct JSON.
        return (s == null || s.isBlank()) ? new LinkedHashMap<>() : Mini.parseObj(s);
    }

    private static String json(Object o) { return Mini.stringify(o); }

    /* Minimal JSON (object/array/string/number/boolean/null) */
    static final class Mini {
        static Map<String,Object> parseObj(String s){ Object v=parse(s); return v instanceof Map? (Map<String,Object>)v:new LinkedHashMap<>(); }
        static Object parse(String s){ return new P(s).v(); }
        static String stringify(Object o){ StringBuilder b=new StringBuilder(); w(o,b); return b.toString(); }
        @SuppressWarnings("unchecked") static void w(Object o,StringBuilder b){
            if(o==null){b.append("null");return;}
            if(o instanceof String){b.append('"').append(((String)o).replace("\\","\\\\").replace("\"","\\\"")).append('"');return;}
            if(o instanceof Number||o instanceof Boolean){b.append(o.toString());return;}
            if(o instanceof Map){b.append('{');boolean f=true;for(var e:((Map<String,Object>)o).entrySet()){if(!f)b.append(',');f=false;b.append('"').append(e.getKey().replace("\\","\\\\").replace("\"","\\\"")).append("\":");w(e.getValue(),b);}b.append('}');return;}
            if(o instanceof List){b.append('[');boolean f=true;for(Object x:(List<?>)o){if(!f)b.append(',');f=false;w(x,b);}b.append(']');return;}
            b.append('"').append(String.valueOf(o)).append('"');
        }
        static final class P{String s;int i;P(String s){this.s=s;}void sp(){while(i<s.length()){char c=s.charAt(i);if(c==' '||c=='\n'||c=='\r'||c=='\t')i++;else break;}}
            Object v(){sp(); if(i>=s.length()) return null; char c=s.charAt(i);
                if(c=='{')return o(); if(c=='[')return a(); if(c=='"')return st();
                if(s.startsWith("true",i)){i+=4;return true;} if(s.startsWith("false",i)){i+=5;return false;}
                if(s.startsWith("null",i)){i+=4;return null;} return num();}
            Map<String,Object> o(){Map<String,Object>m=new LinkedHashMap<>();i++;sp(); if(s.charAt(i)=='}'){i++;return m;}
                while(true){String k=st(); sp(); i++; // :
                    Object v=v(); m.put(k,v); sp(); char c=s.charAt(i++); if(c=='}')break;}
                return m;}
            List<Object> a(){List<Object>l=new ArrayList<>();i++;sp(); if(s.charAt(i)==']'){i++;return l;}
                while(true){Object v=v(); l.add(v); sp(); char c=s.charAt(i++); if(c==']')break;} return l;}
            String st(){StringBuilder b=new StringBuilder();i++; while(true){char c=s.charAt(i++); if(c=='"')break; if(c=='\\'){char n=s.charAt(i++); if(n=='"'||n=='\\')b.append(n); else if(n=='n')b.append('\n'); else if(n=='t')b.append('\t'); else b.append(n);} else b.append(c);} return b.toString();}
            Number num(){int j=i; while(i<s.length()){char c=s.charAt(i); if((c>='0'&&c<='9')||c=='-'||c=='+')i++; else break;} String t=s.substring(j,i);
                try{return Integer.parseInt(t);}catch(Exception e){} try{return Long.parseLong(t);}catch(Exception e){} return Double.parseDouble(t);}
        }
    }
}
