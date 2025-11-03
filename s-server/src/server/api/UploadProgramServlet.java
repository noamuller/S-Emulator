package server.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.annotation.MultipartConfig;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import server.core.EngineFacade;
import server.core.ProgramInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@WebServlet(name = "UploadProgramServlet", urlPatterns = {"/api/programs/upload"})
@MultipartConfig
public class UploadProgramServlet extends HttpServlet {

    private EngineFacade facade(HttpServletRequest req) {
        return (EngineFacade) req.getServletContext().getAttribute("facade");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");

        try {
            String xml = readXmlFromRequest(req);
            if (xml == null || xml.isBlank()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().write("{\"ok\":false,\"error\":\"Missing XML\"}");
                return;
            }

            ProgramInfo info = facade(req).loadProgram(xml);

            StringBuilder json = new StringBuilder();
            json.append("{")
                    .append("\"ok\":true,")
                    .append("\"program\":{")
                    .append("\"id\":\"").append(esc(info.id())).append("\",")
                    .append("\"name\":\"").append(esc(info.name())).append("\",")
                    .append("\"maxDegree\":").append(info.maxDegree()).append(",")
                    .append("\"functions\":[");
            for (int i = 0; i < info.functions().size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(esc(info.functions().get(i))).append("\"");
            }
            json.append("]}")
                    .append("}");

            resp.getWriter().write(json.toString());

        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            resp.getWriter().write("{\"ok\":false,\"error\":\"" + esc(msg) + "\"}");
        }
    }

    private static String readXmlFromRequest(HttpServletRequest req) throws Exception {
        String ct = req.getContentType();
        if (ct != null && ct.toLowerCase().contains("multipart/")) {
            Part part = req.getPart("file");
            if (part == null) return null;
            try (InputStream in = part.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
