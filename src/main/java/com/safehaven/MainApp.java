package com.safehaven;

import com.safehaven.db.DatabaseManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;

public class MainApp extends Application {

    private static Scene scene;

    @Override
    public void start(Stage stage) throws IOException {
        DatabaseManager.initializeDatabase();
        
        scene = new Scene(loadFXML("ui/login"), 640, 480);
        scene.getStylesheets().add(MainApp.class.getResource("/com/safehaven/ui/style.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("SafeHaven - Secure Storage");
        stage.show();
    }

    @Override
    public void stop() {
        DatabaseManager.shutdownDatabase();
    }

    public static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }
    
    public static void setRoot(String fxml, Object controller) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(MainApp.class.getResource(fxml + ".fxml"));
        if (controller != null) {
            fxmlLoader.setController(controller);
        }
        scene.setRoot(fxmlLoader.load());
    }

    /**
     * Shows a notice on the login screen (also used after an inactivity logout).
     */
    public static void showLoginNotice(String message) {
        Runnable show = () -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Session Expired");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        };

        if (Platform.isFxApplicationThread()) {
            show.run();
        } else {
            Platform.runLater(show);
        }
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(MainApp.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch();
    }
}
