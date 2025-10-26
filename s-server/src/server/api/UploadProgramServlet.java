package server.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import server.core.EngineFacade;
import server.core.SimpleJson;

@WebServlet("/api/programs:upload")
public class UploadProgramServlet extends HttpServlet {

    private EngineFacade facade() {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade ef) return ef;
        throw new IllegalStateException("EngineFacade not initialized; check Bootstrap");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bodyText = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining());
        Map<String,Object> body = SimpleJson.parse(bodyText);

        String xml = String.valueOf(body.get("xml"));
        try {
            var info = facade().loadProgram(xml); // returns ProgramInfo
            SimpleJson.write(resp.getWriter(), Map.of(
                    "programId", info.id(),
                    "name", info.name(),
                    "maxDegree", info.maxDegree()
            ));
        } catch (Exception ex) {
            resp.setStatus(400);
            SimpleJson.write(resp.getWriter(), Map.of("error", ex.getMessage()));
        }
    }
}
