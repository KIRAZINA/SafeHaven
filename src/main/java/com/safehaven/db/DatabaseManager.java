package com.safehaven.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;

    static {
        loadConfig();
    }

    private static void loadConfig() {
        Properties props = new Properties();
        try (InputStream in = DatabaseManager.class
                .getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            logger.warn("Could not load application.properties, using defaults", e);
        }
        dbUrl      = props.getProperty("jdbc.url",
                "jdbc:h2:./safehaven;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE");
        dbUser     = props.getProperty("jdbc.user",     "sa");
        dbPassword = props.getProperty("jdbc.password", "");
    }

    /** Overrides the DB URL (used by tests). */
    public static void setDbUrl(String url) {
        dbUrl      = url;
        dbUser     = "sa";
        dbPassword = "";
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    public static void initializeDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Base Users table
            stmt.execute("CREATE TABLE IF NOT EXISTS USERS (" +
                    "username VARCHAR(255) PRIMARY KEY, " +
                    "password_hash BINARY(32) NOT NULL, " +
                    "salt BINARY(16) NOT NULL" +
                    ")");

            // Migration: Add RSA keys to USERS if they don't exist
            stmt.execute("ALTER TABLE USERS ADD COLUMN IF NOT EXISTS public_key VARBINARY(2048)");
            stmt.execute("ALTER TABLE USERS ADD COLUMN IF NOT EXISTS encrypted_private_key VARBINARY(4096)");

            // Base Files table
            stmt.execute("CREATE TABLE IF NOT EXISTS FILES (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "filename VARCHAR(255) NOT NULL, " +
                    "owner VARCHAR(255) NOT NULL, " +
                    "original_size BIGINT NOT NULL, " +
                    "stored_size BIGINT NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "FOREIGN KEY (owner) REFERENCES USERS(username)" +
                    ")");

            // Migration: Add FEK column to FILES
            stmt.execute("ALTER TABLE FILES ADD COLUMN IF NOT EXISTS encrypted_fek VARBINARY(512)");

            // Create Shares table for sharing functionality
            stmt.execute("CREATE TABLE IF NOT EXISTS SHARES (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "file_id VARCHAR(36) NOT NULL REFERENCES FILES(id), " +
                    "from_user VARCHAR(255) NOT NULL REFERENCES USERS(username), " +
                    "to_user VARCHAR(255) NOT NULL REFERENCES USERS(username), " +
                    "wrapped_fek VARBINARY(512) NOT NULL, " +
                    "timestamp BIGINT NOT NULL" +
                    ")");

        } catch (SQLException e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
}
