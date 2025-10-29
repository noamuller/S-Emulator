package server.api;

import jakarta.servlet.http.*;
import jakarta.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.*;
import server.core.*;

@WebServlet("/api/programs")
public class ListProgramsServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");

        Object f = getServletContext().getAttribute("facade");
        ProgramStore store = ProgramStore.get(); // use the singleton

        List<ProgramInfo> items = store.list();
        SimpleJson.write(resp.getWriter(), Map.of("programs", items));
    }
}
