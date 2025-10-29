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
        // Accept both "username" and "user"
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

        User user = UserStore.get().getOrCreate(username);

        // Store BOTH for compatibility: string id (what other code expects) and the int
        HttpSession session = req.getSession(true);
        session.setAttribute("userId", String.valueOf(user.getId())); // <- String
        session.setAttribute("userIdInt", user.getId());              // <- Integer
        session.setAttribute("username", user.getUsername());

        SimpleJson.write(resp.getWriter(), Map.of(
                "ok", true,
                "user", Map.of(
                        "id", user.getId(),                 // int in response
                        "username", user.getUsername(),
                        "credits", user.getCredits()
                )
        ));
    }
}
