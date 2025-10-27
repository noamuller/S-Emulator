package server.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.ProgramInfo;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet(urlPatterns = "/api/programs/upload")
public class UploadProgramServlet extends HttpServlet {

    private EngineFacade facade() {
        return (EngineFacade) getServletContext().getAttribute("facade");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String body = req.getReader().lines().reduce("", (a, b) -> a + b + "\n");

        ProgramInfo info = facade().loadProgram(body);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", info.id());
        out.put("programId", info.id());
        out.put("name", info.name());
        out.put("functions", info.functions());
        out.put("maxDegree", info.maxDegree());

        resp.setContentType("application/json; charset=UTF-8");
        SimpleJson.write(resp.getWriter(), out);
    }
}
