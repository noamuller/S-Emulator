package server.api;

import server.core.EngineFacade;
import server.core.SimpleJson;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@WebServlet("/api/runs")
public class StartRunServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        return (f instanceof EngineFacade ef) ? ef : null;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");

        Map<String, Object> body = SimpleJson.parse(req.getReader().lines().collect(Collectors.joining()));

        String userId    = body.get("userId")    == null ? null : String.valueOf(body.get("userId"));
        String programId = body.get("programId") == null ? null : String.valueOf(body.get("programId"));
        String function  = body.get("function")  == null ? "(main)" : String.valueOf(body.get("function"));
        int degree       = body.get("degree")    == null ? 0 : ((Number) body.get("degree")).intValue();
        String arch      = body.get("architecture") == null ? "Basic" : String.valueOf(body.get("architecture"));

        @SuppressWarnings("unchecked")
        List<Object> rawInputs = (List<Object>) body.getOrDefault("inputs", List.of());
        List<Integer> inputs = new ArrayList<>();
        for (Object o : rawInputs) {
            if (o instanceof Number n) inputs.add(n.intValue());
            else if (o != null && !o.toString().isBlank()) inputs.add(Integer.parseInt(o.toString().trim()));
        }

        if (programId == null || programId.isBlank()) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", "programId is required"));
            return;
        }

        try {
            var rr = facade().run(userId, programId, function, inputs, degree, arch);

            Map<String,Object> out = new LinkedHashMap<>();
            out.put("runId", rr.runId());
            out.put("y", rr.y());
            out.put("cycles", rr.cycles());
            out.put("variables", rr.variables());

            List<Map<String,Object>> trace = new ArrayList<>();
            for (var t : rr.trace()) {
                trace.add(Map.of(
                        "index",  t.index(),
                        "type",   t.type(),
                        "label",  t.label(),
                        "instr",  t.instr(),
                        "cycles", t.cycles()
                ));
            }
            out.put("trace", trace);

            SimpleJson.write(resp.getWriter(), out);
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }
}
