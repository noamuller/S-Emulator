package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;

@WebServlet(name="Bootstrap", urlPatterns={"/api/bootstrap"}, loadOnStartup=1)
public class Bootstrap extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().write("ok");
    }
}
