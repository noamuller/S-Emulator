package server.api;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import server.core.*;

@WebServlet("/api/programs/upload")
@MultipartConfig
public class UploadProgramServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");

        String userId = req.getParameter("userId");
        String name   = req.getParameter("name");

        try {
            Part file = req.getPart("file");
            if (file == null) throw new IllegalArgumentException("missing file part 'file'");

            byte[] bytes = file.getInputStream().readAllBytes();
            String xml = new String(bytes, StandardCharsets.UTF_8);

            // (phase 2) here we will parse with engine.ProgramParser
            ProgramStore.ProgramEntry e = ProgramStore.get().add(userId, name, xml);

            String json = "{"
                    + SimpleJson.kv("programId", e.getId()) + ","
                    + SimpleJson.kv("name", e.getName())
                    + "}";
            resp.getWriter().println(json);

        } catch (Exception ex) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().println("{\"error\":"+ SimpleJson.str(ex.getMessage()) +"}");
        }
    }
}
