package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import server.core.*;

@WebServlet("/api/me")
public class MeServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade ef) return ef;
        throw new IllegalStateException("EngineFacade not initialized; check Bootstrap");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bodyText = req.getReader().lines().collect(Collectors.joining());
        Map<String,Object> body = SimpleJson.parse(bodyText);

        String userId = String.valueOf(body.get("userId"));
        var cs = facade().getCredits(userId);
        SimpleJson.write(resp.getWriter(), Map.of(
                "userId", cs.userId(),
                "credits", cs.credits()
        ));
    }
}
