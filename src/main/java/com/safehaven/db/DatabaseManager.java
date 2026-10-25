package com.safehaven.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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

    /**
     * Applies pending schema migrations. Flyway is the single source of truth
     * for DDL.
     *
     * <p>Existing non-empty databases are baselined at version {@code 0} so
     * that the idempotent {@code V1__Initial_schema.sql} still runs and can add
     * anything an older installation lacks (RSA/FEK columns, secondary
     * indexes) without touching existing rows. Fresh databases simply run
     * V1 from scratch.</p>
     */
    public static void initializeDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(dbUrl, dbUser, dbPassword)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();

        try {
            MigrateResult result = flyway.migrate();
            logger.info("Database schema up to date; {} migration(s) applied",
                    result.migrationsExecuted);
        } catch (FlywayException e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
}
