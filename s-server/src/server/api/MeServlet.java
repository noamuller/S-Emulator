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

@WebServlet("/api/me")
public class MeServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession s = req.getSession(false);
        if (s == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            SimpleJson.write(resp.getWriter(), Map.of("ok", false, "error", "no session"));
            return;
        }
        String username = (String) s.getAttribute("username");
        Integer id = (Integer) s.getAttribute("userId");

        if (username == null || id == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            SimpleJson.write(resp.getWriter(), Map.of("ok", false, "error", "not logged in"));
            return;
        }

        User u = UserStore.get().getByName(username);
        if (u == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            SimpleJson.write(resp.getWriter(), Map.of("ok", false, "error", "user not found"));
            return;
        }

        SimpleJson.write(resp.getWriter(), Map.of(
                "ok", true,
                "user", Map.of(
                        "id", u.getId(),
                        "username", u.getUsername(),
                        "credits", u.getCredits()
                )
        ));
    }
}
