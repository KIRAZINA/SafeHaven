package com.safehaven.service;

import com.safehaven.db.DatabaseManager;
import com.safehaven.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

public class AuthServiceTest {

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // Use in-memory DB for testing
        DatabaseManager.setDbUrl("jdbc:h2:mem:test_auth;DB_CLOSE_DELAY=-1");
        DatabaseManager.initializeDatabase();
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
}
