package gui;

import javafx.fxml.FXMLLoader;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.util.Objects;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/gui/main.fxml"));
        Scene scene = new Scene(loader.load());

        // Add CSS from classpath (bulletproof)
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/gui/style.css")).toExternalForm()
        );

        stage.setTitle("S-Emulator – GUI");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
