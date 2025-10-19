package server.api;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.ProgramStore;

@WebServlet(name = "UploadProgramServlet", urlPatterns = {"/api/programs:upload"})
@MultipartConfig
public class UploadProgramServlet extends HttpServlet {

    private final ProgramStore programs = ProgramStore.get();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        String userId = req.getParameter("userId");
        if (userId == null || userId.isBlank()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"userId is required\"}");
            return;
        }
        Part xmlPart = req.getPart("file");
        if (xmlPart == null) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"file part 'file' is required\"}");
            return;
        }
        String fileName = Optional.ofNullable(xmlPart.getSubmittedFileName()).orElse("program.xml");
        String baseName = fileName.replaceAll("\\.xml$","");

        try (InputStream is = xmlPart.getInputStream()) {
            String xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var entry = programs.add(userId, baseName, xml);

            Map<String,Object> out = new LinkedHashMap<>();
            out.put("id", entry.getId());
            out.put("name", entry.getName());
            resp.getWriter().write(json(out));
        } catch (Exception ex) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"" + ex.getMessage().replace("\"","'") + "\"}");
        }
    }

    private static String json(Object o){ return StartRunServlet.Mini.stringify(o); }
}
