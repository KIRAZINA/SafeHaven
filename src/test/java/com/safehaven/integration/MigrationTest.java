package com.safehaven.integration;

import com.safehaven.db.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

        // The legacy database was created without secondary indexes; startup
        // must add them via IF NOT EXISTS.
        Map<String, String> indexes = new HashMap<>();
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT INDEX_NAME, TABLE_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                     "WHERE UPPER(INDEX_NAME) IN " +
                     "('IDX_FILES_OWNER', 'IDX_SHARES_TO_USER', 'IDX_SHARES_FILE_ID')")) {
            while (rs.next()) {
                indexes.put(rs.getString(1).toUpperCase(Locale.ROOT),
                        rs.getString(2).toUpperCase(Locale.ROOT));
            }
        }

        assertEquals("FILES", indexes.get("IDX_FILES_OWNER"),
                "idx_files_owner must be added to the pre-existing schema");
        assertEquals("SHARES", indexes.get("IDX_SHARES_TO_USER"),
                "idx_shares_to_user must be added to the pre-existing schema");
        assertEquals("SHARES", indexes.get("IDX_SHARES_FILE_ID"),
                "idx_shares_file_id must be added to the pre-existing schema");

        // SHARES did not exist in this legacy schema; Flyway must create it.
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM SHARES")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Migrated SHARES table must start empty");
        }

        // A pre-existing non-empty schema is baselined at version 0, then the
        // idempotent V1 script is applied.
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT \"version\", \"type\", \"success\" " +
                     "FROM \"flyway_schema_history\" WHERE \"type\" = 'BASELINE'")) {

            assertTrue(rs.next(), "Flyway must record the baseline for an existing schema");
            assertEquals("0", rs.getString("version"));
            assertTrue(rs.getBoolean("success"));
            assertFalse(rs.next(), "Exactly one baseline row expected");
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT \"version\", \"type\", \"success\" " +
                     "FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {

            assertTrue(rs.next(), "Flyway must apply V1 to the baselined schema");
            assertEquals("SQL", rs.getString("type"));
            assertTrue(rs.getBoolean("success"));
            assertFalse(rs.next(), "Exactly one V1 migration row expected");
        }

        // Application startup calls initialization every launch; repeat it to
        // prove migration stays non-destructive and idempotent.
        assertDoesNotThrow(DatabaseManager::initializeDatabase,
                "Re-running initialization on a migrated database must not fail");

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1),
                    "Repeated startup must not append duplicate migration rows");
        }
    }
}
