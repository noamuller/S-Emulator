package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.ProgramInfo;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/programs:upload")
public class UploadProgramServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        EngineFacade facade = (EngineFacade) getServletContext().getAttribute("engineFacade");

        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        Map<String,Object> body = SimpleJson.parse(json);
        String xml = String.valueOf(body.get("xml"));

        try {
            ProgramInfo info = facade.loadProgram(xml);
            SimpleJson.write(resp.getWriter(), Map.of(
                    "id", info.id(),
                    "name", info.name(),
                    "functions", info.functions(),
                    "maxDegree", info.maxDegree()
            ));
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }
}
