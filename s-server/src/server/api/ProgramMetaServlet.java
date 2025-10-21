package server.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import server.core.ProgramStore;
// We will reuse the existing JSON mini helper from StartRunServlet:
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

    // ---- DTO used to build JSON easily -------------------------------------
    static final class Row {
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

        // Look up the uploaded program XML for this user
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

        // Parse the XML that we store in memory
        Program p = parseProgramFromXmlString(e.getXml());
        int maxDeg = safeMaxDegree(p);

        // Try Program.rendered(), else expandToDegree(0)
        Program.Rendered rendered = obtainRendered(p);

        // Convert rendered list to simple rows
        List<Row> rows = asRows(rendered);

        // Build the response map
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

    // ---- JSON writer (uses Mini helper you already have) --------------------

    private static void writeJson(HttpServletResponse resp, Map<String, Object> obj) throws IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");
        resp.getWriter().write(Mini.stringify(obj));
    }

    // ---- Program parsing (reflection covers parse(File) vs parseFromXml(File)) ----

    private static Program parseProgramFromXmlString(String xml) throws ServletException {
        try {
            File tmp = File.createTempFile("program-", ".xml");
            java.nio.file.Files.writeString(tmp.toPath(), xml == null ? "" : xml, StandardCharsets.UTF_8);
            try {
                // Prefer parseFromXml(File)
                Method m = ProgramParser.class.getMethod("parseFromXml", File.class);
                return (Program) m.invoke(null, tmp);
            } catch (NoSuchMethodException ignore) {
                // Fallback to parse(File)
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
            // Try p.rendered()
            Method m = Program.class.getMethod("rendered");
            return (Program.Rendered) m.invoke(p);
        } catch (NoSuchMethodException nsme) {
            // Fallback: p.expandToDegree(0)
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

    // ---- Rendered -> Rows (via reflection so it compiles with your Instruction) ----

    private static List<Row> asRows(Program.Rendered r) throws ServletException {
        List<Row> out = new ArrayList<>(r.list.size());
        int idx = 0;
        for (Object inst : r.list) {
            String type  = stringProp(inst, "getType", "getKind", "kind");
            String label = stringProp(inst, "getLabel", "label");
            String text  = stringProp(inst, "getInstructionText", "text");
            int cycles   = intMethod(inst, "cycles", 0);
            out.add(new Row(idx++, nullToEmpty(type), nullToEmpty(label), nullToEmpty(text), Math.max(0, cycles)));
        }
        return out;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    // tries getters first, then public field
    private static String stringProp(Object obj, String... candidates) throws ServletException {
        for (String name : candidates) {
            try {
                // try method with no args
                Method m = obj.getClass().getMethod(name);
                Object v = m.invoke(obj);
                return v == null ? null : String.valueOf(v);
            } catch (NoSuchMethodException ignore) {
                // try field
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
