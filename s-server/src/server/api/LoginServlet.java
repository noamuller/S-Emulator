package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import server.core.*;

@WebServlet("/api/login")
public class LoginServlet extends HttpServlet {
    @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String username = req.getParameter("username"); // POST form or query string
        try {
            User u = UserStore.get().loginOrCreate(username);
            String json = "{"
                    + SimpleJson.kv("userId", u.getId()) + ","
                    + SimpleJson.kv("username", u.getUsername()) + ","
                    + SimpleJson.kv("credits", u.getCredits())
                    + "}";
            resp.getWriter().println(json);
        } catch (Exception ex) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().println("{\"error\":"+ SimpleJson.str(ex.getMessage()) +"}");
        }
    }
}
