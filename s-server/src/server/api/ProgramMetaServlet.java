package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.ProgramInfo;
import server.core.ProgramStore;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/programs/meta/one")
public class ProgramMetaServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        ProgramStore store = (ProgramStore) getServletContext().getAttribute("programStore");

        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        Map<String,Object> body = SimpleJson.parse(json);
        String id = String.valueOf(body.get("programId"));

        ProgramInfo info = store.info(id);
        SimpleJson.write(resp.getWriter(), Map.of(
                "id", info.id(),
                "name", info.name(),
                "functions", info.functions(),
                "maxDegree", info.maxDegree()
        ));
    }
}
