package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet(name = "RunStatusServlet", urlPatterns = {"/api/debug/status"})
public class RunStatusServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade ef) {
            return ef;
        }
        throw new IllegalStateException("EngineFacade not initialized; check Bootstrap");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");

        String runId = req.getParameter("runId");
        EngineFacade.DebugState st = facade().status(runId);

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
