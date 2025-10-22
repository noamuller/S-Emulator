package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.SimpleJson;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@WebServlet("/api/me")
public class MeServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        EngineFacade facade = (EngineFacade) getServletContext().getAttribute("engineFacade");

        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        Map<String,Object> body = SimpleJson.parse(json);
        String userId = String.valueOf(body.get("userId"));

        var credits = facade.getCredits(userId);
        SimpleJson.write(resp.getWriter(), Map.of(
                "userId", credits.userId(),
                "credits", credits.credits()
        ));
    }
}
