package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@WebServlet("/api/runs")
public class StartRunServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        return (EngineFacade) f;  // Bootstrap must have put it here
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String json = req.getReader().lines().collect(Collectors.joining());
        Map<String, Object> body = SimpleJson.parse(json);

        String userId    = String.valueOf(body.getOrDefault("userId", ""));
        String programId = String.valueOf(body.getOrDefault("programId", ""));
        String function  = String.valueOf(body.getOrDefault("function", "main"));
        int degree       = asInt(body.get("degree"), 0);
        String arch      = String.valueOf(body.getOrDefault("architecture", "Basic"));
        boolean debug    = Boolean.TRUE.equals(body.get("debug"));

        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) body.getOrDefault("inputs", List.of());
        List<Integer> inputs = new ArrayList<>();
        for (Object o : raw) inputs.add(asInt(o, 0));

        try {
            if (debug) {
                var s = facade().startDebug(userId, programId, function, inputs, degree, arch);
                var st = s.state();
                Map<String,Object> out = new LinkedHashMap<>();
                out.put("runId", s.runId());
                out.put("pc", st.pc());
                out.put("cycles", st.cycles());
                out.put("finished", st.halted());
                out.put("variables", st.variables());
                out.put("currentInstruction", st.current() == null ? "" : st.current().instr());
                SimpleJson.write(resp.getWriter(), out);
            } else {
                var r = facade().run(userId, programId, function, inputs, degree, arch);
                Map<String,Object> out = new LinkedHashMap<>();
                out.put("runId", r.runId());         // UI expects this
                out.put("y", r.y());
                out.put("cycles", r.cycles());
                out.put("variables", r.variables());
                SimpleJson.write(resp.getWriter(), out);
            }
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }

    private static int asInt(Object v, int def){ try { return Integer.parseInt(String.valueOf(v)); } catch(Exception e){ return def; } }
}
