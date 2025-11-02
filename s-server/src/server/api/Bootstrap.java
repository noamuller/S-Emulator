package server.api;

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
        // Create singletons
        ProgramStore programStore = ProgramStore.get();
        UserStore userStore       = UserStore.get();
        RunManager runManager     = new RunManager();

        EngineFacade facade = new EngineFacadeImpl(programStore, userStore, runManager);

        // Share one Facade across all servlets
        sce.getServletContext().setAttribute("facade", facade);
        System.out.println("[Bootstrap] EngineFacade registered under key 'facade'");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // nothing to clean
    }
}
