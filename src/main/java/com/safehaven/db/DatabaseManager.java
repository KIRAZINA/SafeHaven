package com.safehaven.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static String dbUrl = "jdbc:h2:./safehaven;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
    private static final String DB_USER = "sa";
    private static final String DB_PASSWORD = "";

    public static void setDbUrl(String url) {
        dbUrl = url;
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, DB_USER, DB_PASSWORD);
    }

    public static void initializeDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Create Users table
            String createUsersTable = "CREATE TABLE IF NOT EXISTS USERS (" +
                    "username VARCHAR(255) PRIMARY KEY, " +
                    "password_hash BINARY(32) NOT NULL, " +
                    "salt BINARY(16) NOT NULL" +
                    ")";
            stmt.execute(createUsersTable);

            // Create Files table (anticipating Storage Module)
            String createFilesTable = "CREATE TABLE IF NOT EXISTS FILES (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "filename VARCHAR(255) NOT NULL, " +
                    "owner VARCHAR(255) NOT NULL, " +
                    "original_size BIGINT NOT NULL, " +
                    "stored_size BIGINT NOT NULL, " +
                    "iv BINARY(12) NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "FOREIGN KEY (owner) REFERENCES USERS(username)" +
                    ")";
            stmt.execute(createFilesTable);

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
}
