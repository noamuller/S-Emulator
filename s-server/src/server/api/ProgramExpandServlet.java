package server.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import server.core.EngineFacade;
import server.core.EngineFacadeImpl;
import server.core.ProgramStore;
import server.core.RunManager;
import server.core.SimpleJson;
import server.core.UserStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet(urlPatterns = "/api/programs/expand")
public class ProgramExpandServlet extends HttpServlet {

    private EngineFacade facade;

    @Override
    public void init() throws ServletException {
        Object f = getServletContext().getAttribute("facade");
        if (f instanceof EngineFacade) {
            facade = (EngineFacade) f;
        } else {
            facade = new EngineFacadeImpl(ProgramStore.get(), UserStore.get(), new RunManager());
            getServletContext().setAttribute("facade", facade);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String programId = req.getParameter("programId");
        String function  = req.getParameter("function");
        String degreeStr = req.getParameter("degree");
        int degree = (degreeStr == null || degreeStr.isBlank()) ? 0 : Integer.parseInt(degreeStr);

        List<EngineFacade.TraceRow> rows = facade.expand(programId, function, degree);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("function", (function == null || function.isBlank()) ? "(main)" : function);
        out.put("degree", degree);
        out.put("rows", rows);

        resp.setContentType("application/json; charset=UTF-8");
        SimpleJson.write(resp.getWriter(), out);
    }
}
