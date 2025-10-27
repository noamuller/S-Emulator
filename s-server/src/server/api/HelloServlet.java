package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import server.core.SimpleJson;

@WebServlet("/api/hello")
public class HelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SimpleJson.write(resp.getWriter(), Map.of("ok", true, "service", "s-server", "version", "t3"));
    }
}
