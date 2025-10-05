package gui;

import javafx.fxml.FXMLLoader;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.util.Objects;


public class MainApp extends Application {
    @Override
    public void start(javafx.stage.Stage stage) throws Exception {
        javafx.fxml.FXMLLoader fxml = new javafx.fxml.FXMLLoader(MainApp.class.getResource("/gui/main.fxml"));
        javafx.scene.Scene scene = new javafx.scene.Scene(fxml.load());
        stage.setTitle("S-Emulator");
        stage.setScene(scene);
        stage.show();
    }


    public static void main(String[] args) {
        launch(args);
    }
}
