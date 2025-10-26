package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.SimpleJson;
import server.core.User;
import server.core.UserStore;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/login")
public class LoginServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String json = req.getReader().lines().collect(Collectors.joining());
        Map<String, Object> body = SimpleJson.parse(json);

        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        if (username.isEmpty()) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", "username is required"));
            return;
        }

        // Login or create user (NO charging here)
        User u = UserStore.get().loginOrCreate(username);

        SimpleJson.write(resp.getWriter(), Map.of(
                "userId", u.getId(),
                "username", u.getUsername(),
                "credits", u.getCredits()
        ));
    }
}
