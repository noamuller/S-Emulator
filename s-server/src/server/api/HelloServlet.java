package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@WebServlet(name = "HelloServlet", urlPatterns = {"/api/hello"})
public class HelloServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json");
        resp.getWriter().write("{\"ok\":true,\"service\":\"s-server\",\"version\":\"t3\"}");
    }
}
