package com.safehaven.db;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariProxyConnection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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

    @Test
    void initialize_CreatesSecondaryIndexesOnExpectedTables() throws Exception {
        String url = "jdbc:h2:mem:test_indexes_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            assertEquals("FILES", indexTable(conn, "IDX_FILES_OWNER"),
                    "idx_files_owner must exist on FILES(owner)");
            assertEquals("SHARES", indexTable(conn, "IDX_SHARES_TO_USER"),
                    "idx_shares_to_user must exist on SHARES(to_user)");
            assertEquals("SHARES", indexTable(conn, "IDX_SHARES_FILE_ID"),
                    "idx_shares_file_id must exist on SHARES(file_id)");
        }
    }

    @Test
    void initialize_DoesNotDuplicateIndexesOnRepeatedStartup() throws Exception {
        String url = "jdbc:h2:mem:test_index_idem_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            assertEquals(1, indexCount(conn, "IDX_FILES_OWNER"));
            assertEquals(1, indexCount(conn, "IDX_SHARES_TO_USER"));
            assertEquals(1, indexCount(conn, "IDX_SHARES_FILE_ID"));
        }
    }

    @Test
    void initialize_RecordsInitialSchemaInFlywayHistory() throws Exception {
        String url = "jdbc:h2:mem:test_flyway_history_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT \"version\", \"script\", \"success\" " +
                     "FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {

            assertTrue(rs.next(), "Flyway must record the applied V1 migration");
            assertEquals("V1__Initial_schema.sql", rs.getString("script"));
            assertTrue(rs.getBoolean("success"));
            assertFalse(rs.next(), "Exactly one V1 migration row expected");
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"type\" = 'BASELINE'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1),
                    "A fresh database must be migrated, not baselined");
        }
    }

    @Test
    void initialize_CreatesForeignKeys() throws Exception {
        String url = "jdbc:h2:mem:test_fks_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            assertEquals(1, foreignKeyCount(conn, "FILES"),
                    "FILES(owner) must reference USERS(username)");
            assertEquals(3, foreignKeyCount(conn, "SHARES"),
                    "SHARES must reference FILES(id) and both USERS(username) columns");
        }
    }

    @Test
    void getConnection_IsManagedByConfiguredHikariPool() throws Exception {
        String url = "jdbc:h2:mem:test_pool_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            assertInstanceOf(HikariProxyConnection.class, conn,
                    "Connections must be allocated by HikariCP");
            assertFalse(conn.isClosed());
        }

        HikariDataSource pool = DatabaseManager.getDataSource();
        assertNotNull(pool, "initializeDatabase must create the Hikari pool");
        assertEquals(DatabaseManager.DEFAULT_MAX_POOL_SIZE, pool.getMaximumPoolSize());
        assertEquals(DatabaseManager.DEFAULT_MIN_IDLE, pool.getMinimumIdle());
        assertEquals("SafeHaven-HikariPool", pool.getPoolName());
        assertEquals(30_000L, pool.getIdleTimeout());
        assertEquals(10_000L, pool.getConnectionTimeout());
    }

    @Test
    void getConnection_ReturnsToPoolOnClose() throws Exception {
        String url = "jdbc:h2:mem:test_pool_reuse_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        HikariDataSource pool = DatabaseManager.getDataSource();
        assertNotNull(pool);

        // Let the pool reach its minimum size so sequential checkouts must
        // reuse physical connections instead of opening new ones.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getHikariPoolMXBean().getTotalConnections()
                < DatabaseManager.DEFAULT_MIN_IDLE && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        int connectionsBefore = pool.getHikariPoolMXBean().getTotalConnections();

        try (Connection conn = DatabaseManager.getConnection()) {
            assertFalse(conn.isClosed());
            assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections());
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(),
                "Closing a pooled connection must return it to the pool");

        try (Connection conn = DatabaseManager.getConnection()) {
            assertFalse(conn.isClosed());
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        assertEquals(connectionsBefore, pool.getHikariPoolMXBean().getTotalConnections(),
                "Sequential checkouts must reuse existing pooled connections");
    }

    @Test
    void shutdownDatabase_ClosesPoolAndAllowsReinitialization() throws Exception {
        String url = "jdbc:h2:mem:test_pool_shutdown_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DatabaseManager.setDbUrl(url);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            assertFalse(conn.isClosed());
        }

        DatabaseManager.shutdownDatabase();
        assertNull(DatabaseManager.getDataSource(), "shutdown must dispose the pool");

        SQLException notInitialized = assertThrows(SQLException.class,
                DatabaseManager::getConnection);
        assertTrue(notInitialized.getMessage().contains("not initialized"),
                "Connections must not be handed out after shutdown");

        // A fresh pool can be created again for the same URL.
        DatabaseManager.initializeDatabase();
        try (Connection conn = DatabaseManager.getConnection()) {
            assertInstanceOf(HikariProxyConnection.class, conn);
            assertFalse(conn.isClosed());
        } finally {
            DatabaseManager.shutdownDatabase();
        }
    }

    @Test
    void setDbUrl_ReconfiguresPoolWithoutCrossDatabaseLeakage() throws Exception {
        String first = "jdbc:h2:mem:test_switch_a_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        String second = "jdbc:h2:mem:test_switch_b_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

        DatabaseManager.setDbUrl(first);
        DatabaseManager.initializeDatabase();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO USERS (username, password_hash, salt) VALUES (?, ?, ?)")) {
            ps.setString(1, "pool_user");
            ps.setBytes(2, new byte[32]);
            ps.setBytes(3, new byte[16]);
            assertEquals(1, ps.executeUpdate());
        }

        DatabaseManager.setDbUrl(second);
        DatabaseManager.initializeDatabase();
        try (Connection conn = DatabaseManager.getConnection()) {
            assertInstanceOf(HikariProxyConnection.class, conn,
                    "The new URL must be served by a new pool");
            assertEquals(0, countUsers(conn),
                    "Switching URLs must not leak rows from the previous database");
        }

        DatabaseManager.setDbUrl(first);
        DatabaseManager.initializeDatabase();
        try (Connection conn = DatabaseManager.getConnection()) {
            assertEquals(1, countUsers(conn),
                    "Returning to the first URL must reuse its migrated schema");
        }
    }

    private static int countUsers(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM USERS")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    /** Returns the table an index belongs to, or {@code null} when absent. */
    private static String indexTable(Connection conn, String indexName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) = ?")) {
            ps.setString(1, indexName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1).toUpperCase(Locale.ROOT) : null;
            }
        }
    }

    private static int indexCount(Connection conn, String indexName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) = ?")) {
            ps.setString(1, indexName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private static int foreignKeyCount(Connection conn, String tableName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                "WHERE CONSTRAINT_TYPE = 'FOREIGN KEY' AND UPPER(TABLE_NAME) = ?")) {
            ps.setString(1, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }
}
