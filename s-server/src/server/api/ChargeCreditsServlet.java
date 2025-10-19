package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import server.core.UserStore;

@WebServlet(name = "ChargeCreditsServlet", urlPatterns = {"/api/charge"})
public class ChargeCreditsServlet extends HttpServlet {

    private final UserStore users = UserStore.get();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");

        String userId = req.getParameter("userId");
        String amountStr = req.getParameter("amount");
        int amount = 0;
        try { amount = Integer.parseInt(amountStr); } catch (Exception ignore) {}

        try {
            int credits = users.charge(userId, amount);
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("userId", userId);
            out.put("credits", credits);
            resp.getWriter().write(StartRunServlet.Mini.stringify(out));
        } catch (Exception ex) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"" + ex.getMessage().replace("\"","'") + "\"}");
        }
    }
}
