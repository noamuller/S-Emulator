package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@WebServlet("/api/login")
public class LoginServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        EngineFacade facade = (EngineFacade) getServletContext().getAttribute("engineFacade");

        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        Map<String,Object> body = SimpleJson.parse(json);
        String username = String.valueOf(body.getOrDefault("username", "guest"));

        // For now: create user id from name (or generate) and ensure credits exist
        String userId = username.isBlank() ? UUID.randomUUID().toString() : username;
        facade.chargeCredits(userId, 0); // ensure user exists (no-op top-up)

        var credits = facade.getCredits(userId);
        SimpleJson.write(resp.getWriter(), Map.of(
                "userId", credits.userId(),
                "username", username,
                "credits", credits.credits()
        ));
    }
}
