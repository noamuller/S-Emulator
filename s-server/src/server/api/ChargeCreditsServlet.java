package server.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@WebServlet(urlPatterns = "/api/credits/charge")
public class ChargeCreditsServlet extends HttpServlet {

    private EngineFacade facade(HttpServletRequest req) {
        return (EngineFacade) req.getServletContext().getAttribute("facade");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");

        try {
            // who is logged in?
            String userId = (String) req.getSession(true).getAttribute("userId");
            if (userId == null || userId.isBlank()) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.getWriter().write("{\"ok\":false,\"error\":\"not logged in\"}");
                return;
            }

            // amount
            String s = req.getParameter("amount");
            int amount = (s == null || s.isBlank()) ? 0 : Integer.parseInt(s.trim());
            if (amount <= 0) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().write("{\"ok\":false,\"error\":\"amount must be > 0\"}");
                return;
            }

            var cs = facade(req).chargeCredits(userId, amount);
            String json = new StringBuilder()
                    .append("{\"ok\":true,")
                    .append("\"credits\":").append(cs.credits())
                    .append("}")
                    .toString();

            resp.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            String msg = e.getMessage() == null ? "error" : e.getMessage().replace("\"","\\\"");
            resp.getWriter().write("{\"ok\":false,\"error\":\"" + msg + "\"}");
        }
    }
}
