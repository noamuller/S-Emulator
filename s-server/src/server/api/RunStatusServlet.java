package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.RunManager;

@WebServlet(name = "RunStatusServlet", urlPatterns = {"/api/runs/status"})
public class RunStatusServlet extends HttpServlet {

    private final RunManager runs = RunManager.get();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        long id = Long.parseLong(req.getParameter("id"));
        var s = runs.get(id);

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("runId", s.id);
        out.put("state", s.finished ? "DONE" : "PAUSED");
        out.put("pc", s.pc);
        out.put("currentInstruction", s.currentInstruction);
        out.put("cycles", s.cycles);
        out.put("variables", s.vars);
        out.put("finished", s.finished);
        resp.getWriter().write(json(out));
    }

    private static String json(Object o){ return StartRunServlet.Mini.stringify(o); }
}
