package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.exception.AuthenticationException;
import com.safehaven.model.User;
import com.safehaven.utils.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

public class AuthService implements IAuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    @Override
    public boolean register(String username, String password) {
        if (password == null) {
            throw new AuthenticationException(
                    "Invalid password: " + String.join(" ",
                            ValidationUtils.getPasswordValidationErrors(null)));
        }

        char[] passwordChars = password.toCharArray();
        try {
            return register(username, passwordChars);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    /**
     * Registers a user after enforcing the username and password rules.
     *
     * @throws IllegalArgumentException if either credential violates the rules
     */
    @Override
    public boolean register(String username, char[] password) {
        try {
            validateRegistrationInput(username, password);

            byte[] salt = CryptoUtils.generateSalt();

            // Derive two independent values from the password + salt.
            // The auth verifier is stored as password_hash and used only for login
            // comparison. The master key is NEVER persisted — it encrypts the user's
            // RSA private key and wraps every file's FEK.  Domain separation ensures
            // that leaking password_hash alone does NOT reveal the master key.
            byte[] passwordHash = CryptoUtils.deriveAuthVerifier(password, salt);
            SecretKey masterKey = CryptoUtils.deriveMasterKey(password, salt);

            // Generate RSA key pair for secure file sharing
            KeyPair rsaPair = CryptoUtils.generateRsaKeyPair();
            byte[] publicKey = rsaPair.getPublic().getEncoded();
            byte[] encryptedPrivateKey = CryptoUtils.encryptPrivateKey(rsaPair.getPrivate(), masterKey);

            String sql = "INSERT INTO USERS (username, password_hash, salt, public_key, encrypted_private_key) " +
                         "VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, username);
                pstmt.setBytes(2, passwordHash);
                pstmt.setBytes(3, salt);
                pstmt.setBytes(4, publicKey);
                pstmt.setBytes(5, encryptedPrivateKey);

                pstmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                // Error code 23505/42001 = unique constraint violation in H2
                if (e.getErrorCode() == 23505 || e.getMessage().contains("PRIMARY KEY")) {
                    logger.info("Registration failed — username already exists: {}", username);
                } else {
                    logger.error("Registration failed for user {}: {}",
                            username, e.getClass().getSimpleName());
                }
                return false;
            }
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private static void validateRegistrationInput(String username, char[] password) {
        List<String> usernameErrors = ValidationUtils.getUsernameValidationErrors(username);
        if (!usernameErrors.isEmpty()) {
            throw new AuthenticationException(
                    "Invalid username: " + String.join(" ", usernameErrors));
        }

        List<String> passwordErrors = ValidationUtils.getPasswordValidationErrors(password);
        if (!passwordErrors.isEmpty()) {
            throw new AuthenticationException(
                    "Invalid password: " + String.join(" ", passwordErrors));
        }
    }

    public User login(String username, String password) {
        String sql = "SELECT password_hash, salt, public_key, encrypted_private_key FROM USERS WHERE username = ?";
        char[] passwordChars = password.toCharArray();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    byte[] storedHash = rs.getBytes("password_hash");
                    byte[] salt = rs.getBytes("salt");

                    // Compare using the AUTH verifier (NOT the master key)
                    byte[] computedHash = CryptoUtils.deriveAuthVerifier(passwordChars, salt);

                    if (Arrays.equals(storedHash, computedHash)) {
                        byte[] pubKey = rs.getBytes("public_key");
                        byte[] encPrivKey = rs.getBytes("encrypted_private_key");
                        return new User(username, storedHash, salt, pubKey, encPrivKey);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Login failed for user {}: {}",
                    username, e.getClass().getSimpleName());
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
        return null;
    }
}
