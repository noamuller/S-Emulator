package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import server.core.*;

@WebServlet("/api/login")
public class LoginServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade ef) return ef;
        throw new IllegalStateException("EngineFacade not initialized; check Bootstrap");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bodyText = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining());
        Map<String,Object> body = SimpleJson.parse(bodyText);

        String username = String.valueOf(body.get("username")).trim();
        if (username.isEmpty()) { resp.setStatus(400); SimpleJson.write(resp.getWriter(), Map.of("error","username required")); return; }

        // Login or create via UserStore (no credits are charged here)
        User u = UserStore.get().loginOrCreate(username);

        // return user info + current credits
        SimpleJson.write(resp.getWriter(), Map.of(
                "userId",   u.getId(),
                "username", u.getUsername(),
                "credits",  u.getCredits()
        ));
    }
}
