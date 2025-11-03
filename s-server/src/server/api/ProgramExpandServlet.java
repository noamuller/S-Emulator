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

@WebServlet("/api/programs/expand")
public class ProgramExpandServlet extends HttpServlet {

    private EngineFacade facade(HttpServletRequest req) {
        return (EngineFacade) req.getServletContext().getAttribute("facade");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");

        String programId = req.getParameter("programId");
        String function  = req.getParameter("function");
        if (function == null || function.isBlank()) {
            function = "(main)";
        }

        int degree = 0;
        String degreeParam = req.getParameter("degree");
        if (degreeParam != null && !degreeParam.isBlank()) {
            try {
                degree = Integer.parseInt(degreeParam.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            List<EngineFacade.TraceRow> rows = facade(req).expand(programId, function, degree);

            List<Map<String, Object>> rowsJson = new ArrayList<>();
            for (EngineFacade.TraceRow r : rows) {
                rowsJson.add(Map.of(
                        "index",  r.index(),
                        "type",   r.type(),
                        "label",  r.label(),
                        "instr",  r.instr(),
                        "cycles", r.cycles()
                ));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("programId", programId);
            out.put("function",  function);
            out.put("degree",    degree);
            out.put("rows",      rowsJson);

            SimpleJson.write(resp.getWriter(), out);
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(),
                    Map.of("error", ex.getMessage()));
        }
    }
}
