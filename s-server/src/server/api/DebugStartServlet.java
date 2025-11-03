package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet(name = "DebugStartServlet", urlPatterns = {"/api/debug/start"})
public class DebugStartServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade ef) {
            return ef;
        }
        throw new IllegalStateException("EngineFacade not initialized; check Bootstrap");
    }

    private static List<Integer> parseInputs(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(p));
            } catch (NumberFormatException ignore) {
                out.add(0);
            }
        }
        return out;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");

        String programId = req.getParameter("programId");
        String function  = req.getParameter("function");
        String degreeStr = req.getParameter("degree");
        String arch      = req.getParameter("arch");
        String inputsStr = req.getParameter("inputs");

        if (function == null || function.isBlank()) {
            function = "(main)";
        }
        if (arch == null || arch.isBlank()) {
            arch = "Basic";
        }

        int degree = 0;
        try {
            degree = Integer.parseInt(degreeStr);
        } catch (Exception ignored) {
        }

        List<Integer> inputs = parseInputs(inputsStr);

        EngineFacade.DebugSession session =
                facade().startDebug(null, programId, function, inputs, degree, arch);

        EngineFacade.DebugState st = session.state();

        Map<String, Object> current = null;
        if (st.current() != null) {
            current = new LinkedHashMap<>();
            current.put("index",  st.current().index());
            current.put("type",   st.current().type());
            current.put("text",   st.current().instr());
            current.put("instr",  st.current().instr());
            current.put("cycles", st.current().cycles());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("runId",   st.runId());
        out.put("pc",      st.pc());
        out.put("cycles",  st.cycles());
        out.put("halted",  st.halted());
        out.put("variables", st.variables());
        out.put("vars",      st.variables());
        if (current != null) {
            out.put("current", current);
        }

        SimpleJson.write(resp.getWriter(), out);
    }
}
