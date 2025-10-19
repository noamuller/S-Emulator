package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.UserStore;

@WebServlet(name = "MeServlet", urlPatterns = {"/api/me"})
public class MeServlet extends HttpServlet {

    private final UserStore users = UserStore.get();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        String userId = req.getParameter("userId");
        var u = users.getById(userId);
        if (u == null) {
            resp.setStatus(404);
            resp.getWriter().write("{\"error\":\"user not found\"}");
            return;
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("userId", u.getId());
        out.put("username", u.getUsername());
        out.put("credits", u.getCredits());
        resp.getWriter().write(StartRunServlet.Mini.stringify(out));
    }
}
