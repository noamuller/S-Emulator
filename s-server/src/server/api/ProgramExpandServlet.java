package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@WebServlet("/api/program:expand")
public class ProgramExpandServlet extends HttpServlet {

    private EngineFacade facade() {
        return (EngineFacade) getServletContext().getAttribute("facade");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String json = req.getReader().lines().collect(Collectors.joining());
        Map<String,Object> body = SimpleJson.parse(json);

        String programId = String.valueOf(body.get("programId"));
        String function  = String.valueOf(body.getOrDefault("function", "main"));
        int degree       = asInt(body.get("degree"), 0);

        try {
            // facade.expand() returns List<TraceRow>
            var rows = facade().expand(programId, function, degree);
            List<String> lines = new ArrayList<>(rows.size());
            int sum = 0;
            for (var r : rows) {
                // TraceRow(int index, String type, String label, String instr, int cycles)
                String lbl = (r.label() == null || r.label().isBlank()) ? "" : (r.label() + ": ");
                lines.add((lbl + r.instr()).trim());
                sum += Math.max(0, r.cycles());
            }
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("lines", lines);
            out.put("sumCycles", sum);
            SimpleJson.write(resp.getWriter(), out);
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }

    private static int asInt(Object v, int d){ try { return Integer.parseInt(String.valueOf(v)); } catch(Exception e){ return d; } }
}
