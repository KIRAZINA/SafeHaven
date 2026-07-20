package com.safehaven.integration;

import com.safehaven.db.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

    @Test
    void initializeDatabase_AddsNewColumns_ToOldSchema(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.resolve("old_schema").toAbsolutePath().toString();
        String url = "jdbc:h2:" + dbPath + ";DB_CLOSE_DELAY=-1";

        DatabaseManager.setDbUrl(url);

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE USERS (" +
                    "username VARCHAR(255) PRIMARY KEY, " +
                    "password_hash BINARY(32) NOT NULL, " +
                    "salt BINARY(16) NOT NULL" +
                    ")");

            stmt.execute("CREATE TABLE FILES (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "filename VARCHAR(255) NOT NULL, " +
                    "owner VARCHAR(255) NOT NULL, " +
                    "original_size BIGINT NOT NULL, " +
                    "stored_size BIGINT NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "FOREIGN KEY (owner) REFERENCES USERS(username)" +
                    ")");

            stmt.execute("INSERT INTO USERS (username, password_hash, salt) VALUES " +
                    "('legacy_user', X'0102030405060708090A0B0C0D0E0F10', X'101112131415161718191A1B1C1D1E1F')");
            stmt.execute("INSERT INTO FILES (id, filename, owner, original_size, stored_size, timestamp) VALUES " +
                    "('legacy-file-id', 'doc.txt', 'legacy_user', 100, 50, 1000)");
        }

        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT username, public_key, encrypted_private_key FROM USERS WHERE username = 'legacy_user'")) {

            assertTrue(rs.next(), "Legacy user must still exist after migration");
            assertEquals("legacy_user", rs.getString("username"));
            assertNull(rs.getObject("public_key"), "public_key should be NULL for migrated legacy row");
            assertNull(rs.getObject("encrypted_private_key"), "encrypted_private_key should be NULL for migrated legacy row");
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, encrypted_fek FROM FILES WHERE id = 'legacy-file-id'")) {

            assertTrue(rs.next(), "Legacy file must still exist after migration");
            assertNull(rs.getObject("encrypted_fek"), "encrypted_fek should be NULL for migrated legacy row");
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM USERS")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Should still have exactly 1 user after migration");
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM FILES")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Should still have exactly 1 file after migration");
        }
    }
}
