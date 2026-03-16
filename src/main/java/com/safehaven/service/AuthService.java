package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    public boolean register(String username, String password) {
        byte[] salt = CryptoUtils.generateSalt();
        char[] passwordChars = password.toCharArray();
        try {
            SecretKey masterKey = CryptoUtils.generateKeyFromPassword(passwordChars, salt);
            byte[] passwordHash = masterKey.getEncoded();

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
                    logger.error("Registration failed for user: {}", username, e);
                }
                return false;
            }
        } finally {
            Arrays.fill(passwordChars, '\0');
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

                    SecretKey key = CryptoUtils.generateKeyFromPassword(passwordChars, salt);
                    byte[] computedHash = key.getEncoded();

                    if (Arrays.equals(storedHash, computedHash)) {
                        byte[] pubKey = rs.getBytes("public_key");
                        byte[] encPrivKey = rs.getBytes("encrypted_private_key");
                        return new User(username, storedHash, salt, pubKey, encPrivKey);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Login failed for user: {}", username, e);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
        return null;
    }
}
