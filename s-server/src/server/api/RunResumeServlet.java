package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.RunManager;

@WebServlet(name = "RunResumeServlet", urlPatterns = {"/api/runs/resume"})
public class RunResumeServlet extends HttpServlet {

    private final RunManager runs = RunManager.get();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        Map<String,Object> body = readJsonObject(req);
        long id = ((Number) body.get("runId")).longValue();

        var s = runs.resume(id);

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

    @SuppressWarnings("unchecked")
    private Map<String,Object> readJsonObject(HttpServletRequest req) throws IOException {
        String s = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return (s == null || s.isBlank()) ? new LinkedHashMap<>() : StartRunServlet.Mini.parseObj(s);
    }
    private static String json(Object o){ return StartRunServlet.Mini.stringify(o); }
}
