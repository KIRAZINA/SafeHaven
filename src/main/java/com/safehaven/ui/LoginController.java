package com.safehaven.ui;

import com.safehaven.MainApp;
import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.io.IOException;

public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;
    
    private final AuthService authService = new AuthService();

    @FXML
    private void handleLogin() throws IOException {
        String username = usernameField.getText();
        String password = passwordField.getText();
        
        if (username.isEmpty() || password.isEmpty()) {
            showAlert("Error", "Please enter both username and password.");
            return;
        }
        
        User user = authService.login(username, password);
        if (user != null) {
            // Switch to MainScene and pass user
            // We need a way to pass user to MainController.
            // MainApp.setRoot doesn't easily support passing data unless we do custom loading.
            // Let's modify MainApp or handle it here.
            
            // Loading MainController manually to set user
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(MainApp.class.getResource("ui/main.fxml"));
            javafx.scene.Parent root = loader.load();
            MainController controller = loader.getController();
            controller.initData(user, password.toCharArray()); // Pass password for key generation
            
            usernameField.getScene().setRoot(root);
        } else {
            showAlert("Login Failed", "Invalid credentials.");
        }
    }

    @FXML
    private void handleRegister() {
        String username = usernameField.getText();
        String password = passwordField.getText();
        
        if (username.isEmpty() || password.isEmpty()) {
            showAlert("Error", "Please enter both username and password.");
            return;
        }
        
        if (authService.register(username, password)) {
            showAlert("Success", "Registration successful. Please login.");
        } else {
            showAlert("Error", "Registration failed. Username may be taken.");
        }
    }
    
    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
