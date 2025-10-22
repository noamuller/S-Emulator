package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.EngineFacade.TraceRow;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/programs/expand")
public class ProgramExpandServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        EngineFacade facade = (EngineFacade) getServletContext().getAttribute("engineFacade");

        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        Map<String,Object> body = SimpleJson.parse(json);
        String programId = String.valueOf(body.get("programId"));
        String function  = String.valueOf(body.getOrDefault("function", "main"));
        int degree = toInt(body.get("degree"), 0);

        try {
            List<TraceRow> rows = facade.expand(programId, function, degree);
            SimpleJson.write(resp.getWriter(), rows.stream().map(StartRunServlet::traceToMap).toList());
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }

    private static int toInt(Object o, int d) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return d; }
    }
}
