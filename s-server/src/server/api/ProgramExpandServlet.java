package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import server.core.ProgramStore;
import server.core.UserStore;
import sengine.Program;
import sengine.ProgramParser;

@WebServlet(name = "ProgramExpandServlet", urlPatterns = {"/api/programs/expand"})
public class ProgramExpandServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        String userId = req.getParameter("userId");
        String programId = req.getParameter("programId");
        int degree = 0;
        try { degree = Integer.parseInt(Objects.toString(req.getParameter("degree"), "0")); }
        catch (Exception ignore){}

        if (userId == null || userId.isBlank() || programId == null || programId.isBlank()) {
            resp.setStatus(400);
            write(resp, "{\"error\":\"userId and programId are required\"}");
            return;
        }

        if (UserStore.get().getById(userId) == null) {
            resp.setStatus(400);
            write(resp, "{\"error\":\"Unknown user\"}");
            return;
        }

        ProgramStore.ProgramEntry e = ProgramStore.get().list(userId).stream()
                .filter(p -> p.getId().equals(programId))
                .findFirst().orElse(null);

        if (e == null) {
            resp.setStatus(400);
            write(resp, "{\"error\":\"Unknown program for this user\"}");
            return;
        }

        Program p = parseProgramFromXmlString(e.getXml());
        degree = Math.max(0, Math.min(degree, p.maxDegree()));
        Program.Rendered r = p.expandToDegree(degree);

        var rows = ProgramMetaServlet.asRows(r);
        write(resp, StartRunServlet.Mini.stringify(rows));
    }

    private static Program parseProgramFromXmlString(String xml) throws ServletException {
        try {
            // Prefer String-based API if available
            try {
                Method m = ProgramParser.class.getMethod("parseFromXml", String.class);
                return (Program) m.invoke(null, xml);
            } catch (NoSuchMethodException ignore) {
                // Fall back to File-based signatures
                File tmp = File.createTempFile("program-", ".xml");
                try {
                    Files.writeString(tmp.toPath(), xml == null ? "" : xml, StandardCharsets.UTF_8);
                    try {
                        Method m = ProgramParser.class.getMethod("parseFromXml", File.class);
                        return (Program) m.invoke(null, tmp);
                    } catch (NoSuchMethodException ignore2) {
                        Method m = ProgramParser.class.getMethod("parse", File.class);
                        return (Program) m.invoke(null, tmp);
                    }
                } finally {
                    try { Files.deleteIfExists(tmp.toPath()); } catch (Exception ignored) {}
                }
            }
        } catch (InvocationTargetException | IllegalAccessException |
                 NoSuchMethodException | java.io.IOException e) {
            throw new ServletException("Failed to parse program XML via reflection", e);
        }
    }

    private static void write(HttpServletResponse resp, String s) {
        try { resp.getWriter().write(s); } catch (Exception ignore) {}
    }
}
