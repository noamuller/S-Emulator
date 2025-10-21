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
            resp.getWriter().write(Mini.stringify(out));
        } catch (Exception ex) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"" + ex.getMessage().replace("\"","'") + "\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> readJsonObject(HttpServletRequest req) throws IOException {
        String s = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Object o = Mini.parse(s);
        return (o instanceof Map) ? (Map<String,Object>) o : new LinkedHashMap<>();
    }

    /** Minimal JSON (object/array/string/number/boolean/null) parser + writer */
    static final class Mini {
        static Object parse(String s){ return new P(s==null? "": s).v(); }
        static Map<String,Object> parseObj(String s){ Object o=parse(s); return o instanceof Map? (Map<String,Object>)o : new LinkedHashMap<>(); }
        static String stringify(Object o){ StringBuilder b=new StringBuilder(); w(o,b); return b.toString(); }

        @SuppressWarnings("unchecked")
        static void w(Object o,StringBuilder b){
            if(o==null){b.append("null");return;}
            if(o instanceof String){b.append('"').append(((String)o).replace("\\","\\\\").replace("\"","\\\"")).append('"');return;}
            if(o instanceof Number||o instanceof Boolean){b.append(o.toString());return;}
            if(o instanceof Map){b.append('{');boolean first=true;for(var e:((Map<String,Object>)o).entrySet()){if(!first)b.append(',');first=false;w(String.valueOf(e.getKey()),b);b.append(':');w(e.getValue(),b);}b.append('}');return;}
            if(o instanceof List){b.append('[');boolean first=true;for(Object x:(List<?>)o){if(!first)b.append(',');first=false;w(x,b);}b.append(']');return;}
            b.append('"').append(String.valueOf(o)).append('"');
        }

        static final class P {
            final String s; int i=0; P(String s){this.s=s;}
            void sp(){ while(i<s.length()){ char c=s.charAt(i); if(c==' '||c=='\n'||c=='\r'||c=='\t') i++; else break; } }
            char peek(){ return i<s.length()? s.charAt(i): '\0'; }
            void expect(char c){ sp(); if(peek()!=c) throw new IllegalArgumentException("Expected '"+c+"' at "+i); i++; }

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
                Map<String,Object> m=new LinkedHashMap<>();
                i++; sp();
                if(peek()=='}'){ i++; return m; }
                while(true){
                    sp(); String k=(String)st();
                    expect(':');
                    Object val=v(); m.put(k,val);
                    sp(); char c=peek();
                    if(c==','){ i++; continue; }
                    if(c=='}'){ i++; break; }
                    throw new IllegalArgumentException("Bad object sep at "+i);
                }
                return m;
            }

            List<Object> a(){
                List<Object> l=new ArrayList<>();
                i++; sp();
                if(peek()==']'){ i++; return l; }
                while(true){
                    Object val=v(); l.add(val);
                    sp(); char c=peek();
                    if(c==','){ i++; continue; }
                    if(c==']'){ i++; break; }
                    throw new IllegalArgumentException("Bad array sep at "+i);
                }
                return l;
            }

            Object st(){
                StringBuilder b=new StringBuilder();
                i++; // open "
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
