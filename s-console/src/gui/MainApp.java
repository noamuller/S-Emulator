package gui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        // Load FXML
        FXMLLoader fxml = new FXMLLoader(getClass().getResource("main.fxml"));
        Scene scene = new Scene(fxml.load());

        // Add CSS here (reliable)
        // style.css is in the same package (gui). If you move it, update the path accordingly.
        URL css = getClass().getResource("style.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            System.err.println("WARNING: style.css not found on classpath at /gui/style.css");
        }

        stage.setTitle("S-Emulator");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
