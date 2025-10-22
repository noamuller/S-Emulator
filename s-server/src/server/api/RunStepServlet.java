package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.EngineFacade.DebugState;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/runs/step")
public class RunStepServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        EngineFacade facade = (EngineFacade) getServletContext().getAttribute("engineFacade");

        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        Map<String,Object> body = SimpleJson.parse(json);
        String runId = String.valueOf(body.get("runId"));

        try {
            DebugState st = facade.step(runId);
            SimpleJson.write(resp.getWriter(), Map.of(
                    "runId", st.runId(),
                    "pc", st.pc(),
                    "cycles", st.cycles(),
                    "halted", st.halted(),
                    "variables", st.variables(),
                    "current", StartRunServlet.traceToMap(st.current())
            ));
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }
}
