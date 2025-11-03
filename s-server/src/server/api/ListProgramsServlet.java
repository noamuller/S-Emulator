package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import server.core.ProgramInfo;
import server.core.ProgramStore;

import java.io.IOException;
import java.util.List;

@WebServlet(name = "ListProgramsServlet", urlPatterns = {"/api/programs"})
public class ListProgramsServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");

        List<ProgramInfo> list = ProgramStore.get().list();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"programs\":[");
        for (int i = 0; i < list.size(); i++) {
            ProgramInfo p = list.get(i);
            if (i > 0) sb.append(',');
            sb.append("{")
                    .append("\"id\":\"").append(esc(p.id())).append("\",")
                    .append("\"name\":\"").append(esc(p.name())).append("\",")
                    .append("\"maxDegree\":").append(p.maxDegree()).append(",")
                    .append("\"functions\":[");
            for (int j = 0; j < p.functions().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append("\"").append(esc(p.functions().get(j))).append("\"");
            }
            sb.append("]}");
        }
        sb.append("]}");

        resp.getWriter().write(sb.toString());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
