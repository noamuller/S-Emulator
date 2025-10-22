package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.EngineFacade.DebugSession;
import server.core.EngineFacade.RunResult;
import server.core.EngineFacade.TraceRow;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/runs")
public class StartRunServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        EngineFacade facade = (EngineFacade) getServletContext().getAttribute("engineFacade");

        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        Map<String, Object> body = SimpleJson.parse(json);

        String userId = asString(body.get("userId"));
        String programId = asString(body.get("programId"));
        String function = asString(body.get("function"));
        String architecture = asStringOrDefault(body.get("architecture"), "Basic");
        int degree = asInt(body.get("degree"), 0);

        @SuppressWarnings("unchecked")
        List<Integer> inputs = (List<Integer>) body.getOrDefault("inputs", List.of());
        boolean debug = Boolean.TRUE.equals(body.get("debug"));

        try {
            if (debug) {
                DebugSession s = facade.startDebug(userId, programId, function, inputs, degree, architecture);
                SimpleJson.write(resp.getWriter(), Map.of(
                        "runId", s.runId(),
                        "state", Map.of(
                                "runId", s.state().runId(),
                                "pc", s.state().pc(),
                                "cycles", s.state().cycles(),
                                "halted", s.state().halted(),
                                "variables", s.state().variables(),
                                "current", traceToMap(s.state().current())
                        )
                ));
            } else {
                RunResult rr = facade.run(userId, programId, function, inputs, degree, architecture);
                SimpleJson.write(resp.getWriter(), Map.of(
                        "y", rr.y(),
                        "cycles", rr.cycles(),
                        "variables", rr.variables(),
                        "trace", rr.trace().stream().map(StartRunServlet::traceToMap).toList()
                ));
            }
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }
    private static String asStringOrDefault(Object o, String d) { return o == null ? d : String.valueOf(o); }
    private static int asInt(Object o, int d) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return d; }
    }

    private static Map<String, Object> traceToMap(TraceRow r) {
        if (r == null) return null;
        return Map.of(
                "index", r.index(),
                "type", r.type(),
                "label", r.label(),
                "instr", r.instr(),
                "cycles", r.cycles()
        );
    }
}
