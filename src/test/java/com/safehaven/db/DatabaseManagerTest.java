package com.safehaven.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {

    @Test
    void initializeAndConnect() throws Exception {
        String url = "jdbc:h2:mem:test_db_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            assertFalse(conn.isClosed());
            assertNotNull(stmt);
        }
    }

    @Test
    void initialize_Idempotent() throws Exception {
        String url = "jdbc:h2:mem:test_idem_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM USERS")) {
            assertTrue(rs.next());
        }
    }

    @Test
    void tablesExistAfterInit() throws Exception {
        String url = "jdbc:h2:mem:test_schema_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM USERS");
            assertTrue(rs.next());
            rs = stmt.executeQuery("SELECT COUNT(*) FROM FILES");
            assertTrue(rs.next());
            rs = stmt.executeQuery("SELECT COUNT(*) FROM SHARES");
            assertTrue(rs.next());
        }
    }

    @Test
    void setDbUrl_OverridesDefault() throws Exception {
        String custom = "jdbc:h2:mem:test_custom_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(custom);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void schemaHasRequiredColumns() throws Exception {
        String url = "jdbc:h2:mem:test_columns_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT username, password_hash, salt, public_key, encrypted_private_key FROM USERS WHERE 1=0")) {
            assertNotNull(rs);
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, filename, owner, original_size, stored_size, timestamp, encrypted_fek FROM FILES WHERE 1=0")) {
            assertNotNull(rs);
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, file_id, from_user, to_user, wrapped_fek, timestamp FROM SHARES WHERE 1=0")) {
            assertNotNull(rs);
        }
    }
}
