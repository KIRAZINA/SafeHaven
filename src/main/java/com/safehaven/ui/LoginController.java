package com.safehaven.ui;

import com.safehaven.exception.AuthenticationException;
import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import com.safehaven.service.IAuthService;
import com.safehaven.utils.ValidationUtils;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    private IAuthService authService = new AuthService();

    /** For dependency injection (tests). */
    public void setAuthService(IAuthService authService) {
        this.authService = authService;
    }

    @FXML
    private void handleLogin() throws IOException {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText();

        List<String> validationErrors = credentialValidationErrors(username, password);
        if (!validationErrors.isEmpty()) {
            showValidationAlert("Invalid Login Details", validationErrors);
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
            ErrorHandler.showError("Login Error", "Authentication failed",
                    "Invalid username or password.");
        }
    }

    @FXML
    private void handleRegister() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText();

        List<String> validationErrors = credentialValidationErrors(username, password);
        if (!validationErrors.isEmpty()) {
            showValidationAlert("Registration Failed", validationErrors);
            return;
        }

        char[] passwordChars = password.toCharArray();
        try {
            if (authService.register(username, passwordChars)) {
                ErrorHandler.showInfo("Registration Complete", "Account created",
                        "Registration successful. Please log in.");
            } else {
                ErrorHandler.showError("Registration Failed", "Username unavailable",
                        "That username is already taken. Please choose another one.");
            }
        } catch (AuthenticationException e) {
            // Defense in depth: AuthService enforces the same rules.
            ErrorHandler.showError("Registration Failed", "Invalid details", e.getMessage());
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    /**
     * Collects every credential rule violation so the UI can list exactly what
     * the user must fix instead of failing one rule at a time.
     */
    private static List<String> credentialValidationErrors(String username, String password) {
        List<String> errors = new ArrayList<>(
                ValidationUtils.getUsernameValidationErrors(username));

        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        try {
            errors.addAll(ValidationUtils.getPasswordValidationErrors(passwordChars));
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
        return errors;
    }

    private void showValidationAlert(String title, List<String> errors) {
        StringBuilder message = new StringBuilder("Please correct the following:");
        for (String error : errors) {
            message.append("\n\n• ").append(error);
        }
        ErrorHandler.showError(title, "Please review your input", message.toString());
    }
}
