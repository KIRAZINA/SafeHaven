package com.safehaven.service;

import com.safehaven.db.DatabaseManager;
import com.safehaven.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class AuthServiceTest {

    private static final String TEST_DB = "jdbc:h2:mem:test_auth_" +
            System.nanoTime() + ";DB_CLOSE_DELAY=-1";  // Fix #12 — unique DB per class load

    private AuthService authService;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager.setDbUrl(TEST_DB);
        DatabaseManager.initializeDatabase();

        // Fix #13 — clear data before every test so tests don't see each other's rows
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        authService = new AuthService();
    }

    @Test
    void testRegisterAndLogin() {
        String username = "testuser";
        String password = "password123";

        boolean registered = authService.register(username, password);
        assertTrue(registered, "Registration should succeed");

        User user = authService.login(username, password);
        assertNotNull(user, "Login should succeed");
        assertEquals(username, user.getUsername(), "Username should match");
        assertNotNull(user.getPasswordHash(), "Password hash should be present");
        assertNotNull(user.getSalt(), "Salt should be present");
    }

    @Test
    void testDuplicateRegistration() {
        String username = "duplicate";
        String password = "password";

        authService.register(username, password);
        boolean secondAttempt = authService.register(username, "newpassword");

        assertFalse(secondAttempt, "Duplicate registration should fail");
    }

    @Test
    void testWrongPassword() {
        String username = "wrongpass";
        String password = "password";

        authService.register(username, password);
        User user = authService.login(username, "wrong");

        assertNull(user, "Login with wrong password should return null");
    }

    @Test
    void testLogin_ReturnsValidRsaKeys() throws Exception {
        String username = "rsa_user";
        String password = "password";

        authService.register(username, password);
        User user = authService.login(username, password);

        assertNotNull(user.getPublicKey(), "Public key should be populated");
        assertNotNull(user.getEncryptedPrivateKey(), "Encrypted private key should be populated");

        // Verify private key can be decrypted with master key
        javax.crypto.SecretKey masterKey = com.safehaven.crypto.CryptoUtils.generateKeyFromPassword(
                password.toCharArray(), user.getSalt());
        java.security.PrivateKey privateKey = com.safehaven.crypto.CryptoUtils.decryptPrivateKey(
                user.getEncryptedPrivateKey(), masterKey);
        
        assertNotNull(privateKey, "Private key should be successfully decrypted");
        assertEquals("RSA", privateKey.getAlgorithm(), "Should be an RSA key");
    }

    @Test
    void testRegister_ThrowsOnDbError() throws Exception {
        // Change URL to an invalid one to force an SQLException on next connection
        // Then register should return false and log error.
        DatabaseManager.setDbUrl("jdbc:invalid:url");
        
        boolean success = authService.register("failuser", "pass");
        assertFalse(success, "Registration should fail gracefully on DB error");
        
        // Restore for cleanup (important because of @BeforeEach)
        DatabaseManager.setDbUrl(TEST_DB);
    }
}
