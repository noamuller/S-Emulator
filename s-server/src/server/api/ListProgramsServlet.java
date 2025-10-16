package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import server.core.*;

@WebServlet("/api/programs")
public class ListProgramsServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String userId = req.getParameter("userId");
        List<ProgramStore.ProgramEntry> list = ProgramStore.get().list(userId);

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
                    .append(SimpleJson.kv("programId", e.getId())).append(",")
                    .append(SimpleJson.kv("name", e.getName()))
                    .append("}");
        }
        sb.append("]");
        resp.getWriter().println(sb.toString());
    }
}
