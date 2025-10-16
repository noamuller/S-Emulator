package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import server.core.*;

@WebServlet("/api/me")
public class MeServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String userId = req.getParameter("userId");
        User u = UserStore.get().getById(userId);
        if (u == null) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.getWriter().println("{\"error\":\"user not found\"}");
            return;
        }
        String json = "{"
                + SimpleJson.kv("userId", u.getId()) + ","
                + SimpleJson.kv("credits", u.getCredits())
                + "}";
        resp.getWriter().println(json);
    }
}
