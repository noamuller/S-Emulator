package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.ProgramInfo;
import server.core.ProgramStore;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@WebServlet("/api/programs/meta")
public class ListProgramsServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        ProgramStore store = (ProgramStore) getServletContext().getAttribute("programStore");

        List<ProgramInfo> items = store.list(); // your ProgramStore should expose this
        SimpleJson.write(resp.getWriter(), Map.of("programs", items));
    }
}
