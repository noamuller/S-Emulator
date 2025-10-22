package server.api;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import server.core.EngineFacade;
import server.core.EngineFacadeImpl;
import server.core.ProgramStore;
import server.core.RunManager;
import server.core.UserStore;

@WebListener
public class Bootstrap implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();

        ProgramStore programStore = new ProgramStore();
        UserStore userStore = new UserStore();
        RunManager runManager = new RunManager();

        EngineFacade facade = new EngineFacadeImpl(programStore, userStore, runManager);

        ctx.setAttribute("programStore", programStore);
        ctx.setAttribute("userStore", userStore);
        ctx.setAttribute("runManager", runManager);
        ctx.setAttribute("engineFacade", facade);
    }
}
