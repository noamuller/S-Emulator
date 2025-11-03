package server.api;

import server.core.EngineFacade;
import server.core.SimpleJson;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@WebServlet(urlPatterns = {"/api/runs", "/api/runs/start"})

public class StartRunServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        return (f instanceof EngineFacade ef) ? ef : null;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");

        Map<String, Object> body;
        String ct = req.getContentType();
        if (ct != null && ct.toLowerCase().contains("application/json")) {
            body = SimpleJson.parse(req.getReader().lines().collect(Collectors.joining()));
        } else {
            body = new LinkedHashMap<>();

            HttpSession session = req.getSession(false);
            if (session != null) {
                Object uid = session.getAttribute("userId");
                if (uid != null) {
                    body.put("userId", String.valueOf(uid));
                }
            }

            body.put("programId", req.getParameter("programId"));
            body.put("function", req.getParameter("function"));

            String degreeStr = req.getParameter("degree");
            if (degreeStr != null && !degreeStr.isBlank()) {
                try {
                    body.put("degree", Integer.parseInt(degreeStr.trim()));
                } catch (NumberFormatException ignore) {
                }
            }

            String arch = req.getParameter("arch");
            if (arch != null && !arch.isBlank()) {
                body.put("architecture", arch);
            }

            String inputsStr = req.getParameter("inputs");
            if (inputsStr != null && !inputsStr.isBlank()) {
                java.util.List<Integer> inputs = new java.util.ArrayList<>();
                for (String tok : inputsStr.split(",")) {
                    String t = tok.trim();
                    if (!t.isEmpty()) {
                        try {
                            inputs.add(Integer.parseInt(t));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
                body.put("inputs", inputs);
            }
        }

        String userId = body.get("userId") == null ? null : String.valueOf(body.get("userId"));
        String programId = body.get("programId") == null ? null : String.valueOf(body.get("programId"));
        String function = body.get("function") == null ? "(main)" : String.valueOf(body.get("function"));

        int degree = 0;
        Object degreeObj = body.get("degree");
        if (degreeObj instanceof Number n) {
            degree = n.intValue();
        } else if (degreeObj != null) {
            try {
                degree = Integer.parseInt(degreeObj.toString().trim());
            } catch (NumberFormatException ignore) {
                degree = 0;
            }
        }

        String arch = body.get("architecture") == null ? "Basic" : String.valueOf(body.get("architecture"));

        @SuppressWarnings("unchecked")
        java.util.List<Object> rawInputs = (java.util.List<Object>) body.getOrDefault("inputs", java.util.List.of());
        java.util.List<Integer> inputs = new java.util.ArrayList<>();
        for (Object o : rawInputs) {
            if (o instanceof Number n) {
                inputs.add(n.intValue());
            } else if (o != null && !o.toString().isBlank()) {
                inputs.add(Integer.parseInt(o.toString().trim()));
            }
        }

        if (programId == null || programId.isBlank()) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), java.util.Map.of("error", "programId is required"));
            return;
        }

        try {
            var rr = facade().run(userId, programId, function, inputs, degree, arch);

            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("runId", rr.runId());
            out.put("y", rr.y());
            out.put("cycles", rr.cycles());
            out.put("variables", rr.variables());

            java.util.List<java.util.Map<String, Object>> trace = new java.util.ArrayList<>();
            for (var t : rr.trace()) {
                trace.add(java.util.Map.of(
                        "index", t.index(),
                        "type", t.type(),
                        "label", t.label(),
                        "instr", t.instr(),
                        "cycles", t.cycles()
                ));
            }
            out.put("trace", trace);

            SimpleJson.write(resp.getWriter(), out);
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), java.util.Map.of("error", ex.getMessage()));
        }
    }
}