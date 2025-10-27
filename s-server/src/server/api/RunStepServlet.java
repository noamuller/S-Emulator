package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import server.core.EngineFacade;
import server.core.SimpleJson;

@WebServlet("/api/runs/step")
public class RunStepServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade ef) return ef;
        throw new IllegalStateException("EngineFacade not initialized; check Bootstrap");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var body = SimpleJson.parse(req.getReader().lines().collect(Collectors.joining()));
        long runId = Long.parseLong(String.valueOf(body.get("runId")));
        var st = facade().step(String.valueOf(runId));
        SimpleJson.write(resp.getWriter(), Map.of(
                "runId", st.runId(),
                "pc", st.pc(),
                "cycles", st.cycles(),
                "halted", st.halted(),
                "variables", st.variables()
        ));
    }
}
