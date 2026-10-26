package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        String password = "Password123!";

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
        String password = "Password123!";

        authService.register(username, password);
        boolean secondAttempt = authService.register(username, "Newpassword1!");

        assertFalse(secondAttempt, "Duplicate registration should fail");
    }

    @Test
    void testWrongPassword() {
        String username = "wrongpass";
        String password = "Password123!";

        authService.register(username, password);
        User user = authService.login(username, "WrongPass1!");

        assertNull(user, "Login with wrong password should return null");
    }

    @Test
    void testLogin_ReturnsValidRsaKeys() throws Exception {
        String username = "rsa_user";
        String password = "Password123!";

        authService.register(username, password);
        User user = authService.login(username, password);

        assertNotNull(user.getPublicKey(), "Public key should be populated");
        assertNotNull(user.getEncryptedPrivateKey(), "Encrypted private key should be populated");

        // Verify private key can be decrypted with master key
        javax.crypto.SecretKey masterKey = com.safehaven.crypto.CryptoUtils.deriveMasterKey(
                password.toCharArray(), user.getSalt());
        java.security.PrivateKey privateKey = com.safehaven.crypto.CryptoUtils.decryptPrivateKey(
                user.getEncryptedPrivateKey(), masterKey);
        
        assertNotNull(privateKey, "Private key should be successfully decrypted");
        assertEquals("RSA", privateKey.getAlgorithm(), "Should be an RSA key");
    }

    @Test
    void testPasswordHash_CannotDecryptPrivateKey() throws Exception {
        // Regression test for P0-1: the password_hash stored in the database
        // (the auth verifier) MUST NOT be usable as a decryption key for the
        // user's encrypted private key. Only the domain-separated master key
        // should work.
        String username = "verifier_is_not_mk";
        String password = "MySecret123!";

        authService.register(username, password);

        // Read the stored password_hash directly from the DB
        String sql = "SELECT password_hash, salt, encrypted_private_key FROM USERS WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "User must exist");
                byte[] storedHash = rs.getBytes("password_hash");
                byte[] salt = rs.getBytes("salt");
                byte[] encPrivKey = rs.getBytes("encrypted_private_key");

                assertNotNull(storedHash, "password_hash must be present");
                assertNotNull(encPrivKey, "encrypted_private_key must be present");

                // Attempt to decrypt the private key using storedHash as an AES key
                SecretKey forgedKey = new SecretKeySpec(storedHash, "AES");
                assertThrows(Exception.class, () -> {
                    CryptoUtils.decryptPrivateKey(encPrivKey, forgedKey);
                }, "Decrypting private key with stored password_hash must fail");

                // Verify the real master key still works
                SecretKey realMasterKey = CryptoUtils.deriveMasterKey(password.toCharArray(), salt);
                java.security.PrivateKey realPrivKey = CryptoUtils.decryptPrivateKey(encPrivKey, realMasterKey);
                assertNotNull(realPrivKey, "Real master key must still decrypt the private key");
                assertEquals("RSA", realPrivKey.getAlgorithm());
            }
        }
    }

    @Test
    void testLogin_NonExistentUsername_returnsNull() {
        User user = authService.login("nonexistent_user", "AnyPassword1!");
        assertNull(user, "Login for a nonexistent username should return null without throwing");
    }

    @Test
    void testRegister_ThrowsOnDbError() throws Exception {
        // Change URL to an invalid one to force an SQLException on next connection
        // Then register should return false and log error.
        DatabaseManager.setDbUrl("jdbc:invalid:url");
        
        boolean success = authService.register("failuser", "Passw0rd!");
        assertFalse(success, "Registration should fail gracefully on DB error");
        
        // Restore for cleanup (important because of @BeforeEach)
        DatabaseManager.setDbUrl(TEST_DB);
    }

    @Test
    void testRegister_RejectsInvalidUsernames() {
        assertThrows(IllegalArgumentException.class,
                () -> authService.register("bad name", "Password1!"));
        assertThrows(IllegalArgumentException.class,
                () -> authService.register("bad/name", "Password1!"));
        assertThrows(IllegalArgumentException.class,
                () -> authService.register("ab", "Password1!"));
        assertThrows(IllegalArgumentException.class,
                () -> authService.register("a".repeat(33), "Password1!"));
        assertThrows(IllegalArgumentException.class,
                () -> authService.register(null, "Password1!"));
    }

    @Test
    void testRegister_RejectsWeakPasswordWithDescriptiveMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.register("valid_user", "weak"));

        String message = ex.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("Invalid password"), message);
        assertTrue(message.contains("at least 8"), message);
        assertTrue(message.contains("uppercase"), message);
        assertTrue(message.contains("digit"), message);
        assertTrue(message.contains("special"), message);
    }

    @Test
    void testRegister_DoesNotPersistInvalidRegistration() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> authService.register("valid_user", "alllowercase1!"));

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM USERS")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1),
                    "Validation must reject the account before any row is written");
        }
    }

    @Test
    void testRegister_AcceptsBoundaryLengths() {
        assertTrue(authService.register("abc", "Abcdef1!"),
                "3-character username and 8-character password are valid");
        assertTrue(authService.register("a".repeat(32), "Aa1!" + "a".repeat(124)),
                "32-character username and 128-character password are valid");
    }

    @Test
    void testRegisterWithCharArray_WipesPasswordBuffer() {
        char[] password = "Password1!".toCharArray();

        assertTrue(authService.register("char_user", password));
        assertArrayEquals(new char[password.length], password,
                "The service should wipe the caller's password buffer");
    }
}
