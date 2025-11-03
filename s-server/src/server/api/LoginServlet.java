package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import server.core.SimpleJson;
import server.core.User;
import server.core.UserStore;

import java.io.IOException;
import java.util.Map;

@WebServlet("/api/login")
public class LoginServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");
        if (username == null || username.isBlank()) {
            username = req.getParameter("user");
        }

        if (username == null || username.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            SimpleJson.write(resp.getWriter(), Map.of(
                    "ok", false,
                    "error", "missing 'username' (use x-www-form-urlencoded, username=Noa)"
            ));
            return;
        }

        username = username.trim();

        UserStore store = UserStore.get();

        User existing = store.getByName(username);
        if (existing != null) {
            SimpleJson.write(resp.getWriter(), Map.of(
                    "ok", false,
                    "error", "user '" + username + "' is already logged in"
            ));
            return;
        }

        User user = store.getOrCreate(username);

        HttpSession session = req.getSession(true);
        session.setAttribute("userId", String.valueOf(user.getId()));
        session.setAttribute("userIdInt", user.getId());
        session.setAttribute("username", user.getUsername());

        SimpleJson.write(resp.getWriter(), Map.of(
                "ok", true,
                "user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "credits", user.getCredits()
                )
        ));
    }
}
