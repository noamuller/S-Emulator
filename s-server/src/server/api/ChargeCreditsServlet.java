package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import server.core.*;

@WebServlet("/api/credits/charge")
public class ChargeCreditsServlet extends HttpServlet {
    @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String userId = req.getParameter("userId");
        String amountStr = req.getParameter("amount");
        try {
            int amount = Integer.parseInt(amountStr);
            int newCredits = UserStore.get().charge(userId, amount);
            String json = "{"
                    + SimpleJson.kv("userId", userId) + ","
                    + SimpleJson.kv("credits", newCredits)
                    + "}";
            resp.getWriter().println(json);
        } catch (Exception ex) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().println("{\"error\":"+ SimpleJson.str(ex.getMessage()) +"}");
        }
    }
}
