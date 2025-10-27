package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import server.core.EngineFacade;
import server.core.SimpleJson;

@WebServlet("/api/charge-credits")
public class ChargeCreditsServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade ef) return ef;
        throw new IllegalStateException("EngineFacade not initialized; check Bootstrap");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var body = SimpleJson.parse(req.getReader().lines().collect(Collectors.joining()));
        String userId = String.valueOf(body.get("userId"));
        int amount = ((Number) body.getOrDefault("amount", 0)).intValue();

        try {
            var cs = facade().chargeCredits(userId, amount);
            SimpleJson.write(resp.getWriter(), Map.of("userId", cs.userId(), "credits", cs.credits()));
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }
}
