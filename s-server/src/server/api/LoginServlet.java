package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.UserStore;

@WebServlet(name = "LoginServlet", urlPatterns = {"/api/login"})
public class LoginServlet extends HttpServlet {

    private final UserStore users = UserStore.get();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        String username = req.getParameter("username");
        if (username == null || username.isBlank()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"username is required\"}");
            return;
        }
        var u = users.loginOrCreate(username.trim());

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("userId", u.getId());
        out.put("username", u.getUsername());
        out.put("credits", u.getCredits());
        resp.getWriter().write(StartRunServlet.Mini.stringify(out));
    }
}
