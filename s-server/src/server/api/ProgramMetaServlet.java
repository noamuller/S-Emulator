package server.api;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.ArrayList;

import server.core.ProgramStore;
import static server.api.StartRunServlet.Mini;

import sengine.Program;
import sengine.ProgramParser;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet(name = "ProgramMetaServlet", urlPatterns = {"/api/programs/meta"})
public class ProgramMetaServlet extends HttpServlet {

    // Row DTO for JSON
    public static final class Row {
        final int index;
        final String type;
        final String label;
        final String instructionText;
        final int cycles;
        Row(int index, String type, String label, String instructionText, int cycles) {
            this.index = index;
            this.type = type;
            this.label = label;
            this.instructionText = instructionText;
            this.cycles = cycles;
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String userId    = req.getParameter("userId");
        String programId = req.getParameter("programId");

        ProgramStore.ProgramEntry e = ProgramStore.get()
                .list(userId)
                .stream()
                .filter(p -> p.getId().equals(programId))
                .findFirst()
                .orElse(null);

        if (e == null) {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "Unknown program for this user"));
            return;
        }

        Program p = parseProgramFromXmlString(e.getXml());
        int maxDeg = safeMaxDegree(p);
        Program.Rendered rendered = obtainRendered(p);

        List<Row> rows = asRows(rendered);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxDegree", maxDeg);

        List<Map<String, Object>> rowMaps = new ArrayList<>(rows.size());
        for (Row r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", r.index);
            m.put("type", r.type);
            m.put("label", r.label);
            m.put("instructionText", r.instructionText);
            m.put("cycles", r.cycles);
            rowMaps.add(m);
        }
        out.put("rows", rowMaps);

        writeJson(resp, out);
    }

    private static void writeJson(HttpServletResponse resp, Map<String, Object> obj) throws IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");
        resp.getWriter().write(Mini.stringify(obj));
    }

    private static Program parseProgramFromXmlString(String xml) throws ServletException {
        try {
            File tmp = File.createTempFile("program-", ".xml");
            java.nio.file.Files.writeString(tmp.toPath(), xml == null ? "" : xml, StandardCharsets.UTF_8);
            try {
                Method m = ProgramParser.class.getMethod("parseFromXml", File.class);
                return (Program) m.invoke(null, tmp);
            } catch (NoSuchMethodException ignore) {
                Method m = ProgramParser.class.getMethod("parse", File.class);
                return (Program) m.invoke(null, tmp);
            } finally {
                try { java.nio.file.Files.deleteIfExists(tmp.toPath()); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            throw new ServletException("Failed to parse program XML", e);
        }
    }

    private static int safeMaxDegree(Program p) throws ServletException {
        try {
            Method m = Program.class.getMethod("maxDegree");
            Object v = m.invoke(p);
            return (v instanceof Number) ? ((Number) v).intValue() : 0;
        } catch (Exception e) {
            throw new ServletException("Program.maxDegree() not available", e);
        }
    }

    private static Program.Rendered obtainRendered(Program p) throws ServletException {
        try {
            Method m = Program.class.getMethod("rendered");
            return (Program.Rendered) m.invoke(p);
        } catch (NoSuchMethodException nsme) {
            try {
                Method m = Program.class.getMethod("expandToDegree", int.class);
                return (Program.Rendered) m.invoke(p, 0);
            } catch (Exception e) {
                throw new ServletException("Unable to obtain rendered program", e);
            }
        } catch (Exception e) {
            throw new ServletException("Unable to obtain rendered program", e);
        }
    }

    public static List<Row> asRows(Program.Rendered r) throws ServletException{
        try {
            // Get the instructions list from Program.Rendered
            List<?> list;
            try {
                // Prefer a public accessor if it exists
                var m = r.getClass().getMethod("list");
                list = (List<?>) m.invoke(r);
            } catch (NoSuchMethodException e) {
                // Fall back to the field
                var f = r.getClass().getDeclaredField("list");
                f.setAccessible(true);
                list = (List<?>) f.get(r);
            }

            List<Row> out = new ArrayList<>(list.size());
            AtomicInteger index = new AtomicInteger(0);

            for (Object inst : list) {
                // IMPORTANT: never access fields like inst.kind directly.
                String kind  = readString(inst, "getKind",  "kind");
                String label = readString(inst, "getLabel", "label");
                String text  = readString(inst, "getText",  "text");
                int cycles   = readInt   (inst, "getCycles","cycles");

                out.add(new Row(index.getAndIncrement(), kind, label, text, cycles));
            }
            return out;
        } catch (ReflectiveOperationException re) {
            throw new ServletException("Failed to convert Program.Rendered to rows", re);
        }
    }



    private static String readString(Object obj, String publicMethod, String altMethodOrField)
            throws ReflectiveOperationException {
        // Try a public getter first
        try {
            var m = obj.getClass().getMethod(publicMethod);
            Object v = m.invoke(obj);
            return v == null ? "" : String.valueOf(v);
        } catch (NoSuchMethodException ignore) { /* fall through */ }

        // Try a non-public method with the same simple name
        try {
            var m = obj.getClass().getDeclaredMethod(altMethodOrField);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            return v == null ? "" : String.valueOf(v);
        } catch (NoSuchMethodException ignore) { /* fall through */ }

        // Finally, try a field
        var f = obj.getClass().getDeclaredField(altMethodOrField);
        f.setAccessible(true);
        Object v = f.get(obj);
        return v == null ? "" : String.valueOf(v);
    }

    private static int readInt(Object obj, String publicMethod, String altMethodOrField)
            throws ReflectiveOperationException {
        // Try a public getter first
        try {
            var m = obj.getClass().getMethod(publicMethod);
            Object v = m.invoke(obj);
            return v == null ? 0 : ((Number) v).intValue();
        } catch (NoSuchMethodException ignore) { /* fall through */ }

        // Try a non-public method
        try {
            var m = obj.getClass().getDeclaredMethod(altMethodOrField);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            return v == null ? 0 : ((Number) v).intValue();
        } catch (NoSuchMethodException ignore) { /* fall through */ }

        // Finally, try a field
        var f = obj.getClass().getDeclaredField(altMethodOrField);
        f.setAccessible(true);
        Object v = f.get(obj);
        return v == null ? 0 : ((Number) v).intValue();
    }



    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String stringProp(Object obj, String... candidates) throws ServletException {
        for (String name : candidates) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object v = m.invoke(obj);
                return v == null ? null : String.valueOf(v);
            } catch (NoSuchMethodException ignore) {
                try {
                    Field f = obj.getClass().getField(name);
                    Object v = f.get(obj);
                    return v == null ? null : String.valueOf(v);
                } catch (NoSuchFieldException ignoreField) {
                    // continue
                } catch (Exception e) {
                    throw new ServletException("Failed reading field '"+name+"'", e);
                }
            } catch (Exception e) {
                throw new ServletException("Failed calling '"+name+"()'", e);
            }
        }
        return null;
    }

    private static int intMethod(Object obj, String name, int defVal) throws ServletException {
        try {
            Method m = obj.getClass().getMethod(name);
            Object v = m.invoke(obj);
            return (v instanceof Number) ? ((Number) v).intValue() : defVal;
        } catch (NoSuchMethodException ignore) {
            return defVal;
        } catch (Exception e) {
            throw new ServletException("Failed calling '"+name+"()'", e);
        }
    }
}
