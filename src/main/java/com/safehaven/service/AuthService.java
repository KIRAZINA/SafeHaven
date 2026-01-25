package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.User;

import javax.crypto.SecretKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

public class AuthService {

    public boolean register(String username, String password) {
        byte[] salt = CryptoUtils.generateSalt();
        SecretKey key = CryptoUtils.generateKeyFromPassword(password.toCharArray(), salt);
        byte[] passwordHash = key.getEncoded(); // We store the derived key as the "hash" for verification

        String sql = "INSERT INTO USERS (username, password_hash, salt) VALUES (?, ?, ?)";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            pstmt.setBytes(2, passwordHash);
            pstmt.setBytes(3, salt);
            
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            // Error code 23505 is generic for unique constraint violation in H2
            if (e.getErrorCode() == 23505 || e.getMessage().contains("PRIMARY KEY")) {
                System.out.println("Username already exists.");
            } else {
                e.printStackTrace();
            }
            return false;
        }
    }

    public User login(String username, String password) {
        String sql = "SELECT password_hash, salt FROM USERS WHERE username = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    byte[] storedHash = rs.getBytes("password_hash");
                    byte[] salt = rs.getBytes("salt");

                    // Verify password
                    SecretKey key = CryptoUtils.generateKeyFromPassword(password.toCharArray(), salt);
                    byte[] computedHash = key.getEncoded();

                    if (Arrays.equals(storedHash, computedHash)) {
                        return new User(username, storedHash, salt);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
}
