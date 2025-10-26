package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/runs/status")
public class RunStatusServlet extends HttpServlet {

    private EngineFacade facade() {
        return (EngineFacade) getServletContext().getAttribute("facade");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String json = req.getReader().lines().collect(Collectors.joining());
        Map<String, Object> body = SimpleJson.parse(json);
        String runId = String.valueOf(body.get("runId"));
        try {
            var st = facade().status(runId);
            SimpleJson.write(resp.getWriter(), Map.of(
                    "runId", st.runId(),
                    "pc", st.pc(),
                    "cycles", st.cycles(),
                    "finished", st.halted(),
                    "variables", st.variables(),
                    "currentInstruction", st.current() == null ? "" : st.current().instr()
            ));
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }
}
