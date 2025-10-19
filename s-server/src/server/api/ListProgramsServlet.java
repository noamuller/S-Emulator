package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.ProgramStore;

@WebServlet(name = "ListProgramsServlet", urlPatterns = {"/api/programs"})
public class ListProgramsServlet extends HttpServlet {

    private final ProgramStore programs = ProgramStore.get();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        String userId = req.getParameter("userId");
        if (userId == null || userId.isBlank()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"userId is required\"}");
            return;
        }

        List<Map<String,Object>> arr = new ArrayList<>();
        for (var e : programs.list(userId)) {
            Map<String,Object> o = new LinkedHashMap<>();
            o.put("id", e.getId());
            o.put("name", e.getName());
            o.put("uploadedAt", String.valueOf(e.getUploadedAt()));
            arr.add(o);
        }
        resp.getWriter().write(json(arr));
    }

    private static String json(Object o){ return StartRunServlet.Mini.stringify(o); }
}
