package com.safehaven.ui;

import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.io.IOException;
import java.util.Arrays;

public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    private final AuthService authService = new AuthService();

    @FXML
    private void handleLogin() throws IOException {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Ошибка", "Введите имя пользователя и пароль.");
            return;
        }

        User user = authService.login(username, password);
        if (user != null) {
            char[] passwordChars = password.toCharArray();
            try {
                // Fix #16 — absolute FXML path
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/com/safehaven/ui/main.fxml"));
                Parent root = loader.load();
                MainController controller = loader.getController();
                controller.initData(user, passwordChars);
                usernameField.getScene().setRoot(root);
            } finally {
                Arrays.fill(passwordChars, '\0');  // Fix #2 — clear password from memory
            }
        } else {
            showAlert(Alert.AlertType.ERROR, "Ошибка входа", "Неверное имя пользователя или пароль.");
        }
    }

    @FXML
    private void handleRegister() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Ошибка", "Введите имя пользователя и пароль.");
            return;
        }

        if (authService.register(username, password)) {
            showAlert(Alert.AlertType.INFORMATION, "Готово", "Регистрация выполнена. Войдите в систему.");
        } else {
            showAlert(Alert.AlertType.ERROR, "Ошибка", "Регистрация не выполнена — имя пользователя уже занято.");
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);  // Fix #11 — use correct AlertType
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
