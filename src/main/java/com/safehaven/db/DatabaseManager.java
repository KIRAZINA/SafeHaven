package com.safehaven.db;

import com.safehaven.utils.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    static final String POOL_NAME = "SafeHaven-HikariPool";
    static final int DEFAULT_MAX_POOL_SIZE = AppConfig.DEFAULT_DB_POOL_MAX_SIZE;
    static final int DEFAULT_MIN_IDLE = AppConfig.DEFAULT_DB_POOL_MIN_IDLE;
    private static final long IDLE_TIMEOUT_MILLIS = 30_000L;
    private static final long CONNECTION_TIMEOUT_MILLIS = 10_000L;

    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;
    private static int maxPoolSize;
    private static int minIdle;

    private static volatile HikariDataSource dataSource;

    static {
        loadConfig();
        Runtime.getRuntime().addShutdownHook(
                new Thread(DatabaseManager::shutdownDatabase, "safehaven-db-shutdown"));
    }

    private static void loadConfig() {
        dbUrl      = AppConfig.getJdbcUrl();
        dbUser     = AppConfig.getJdbcUser();
        dbPassword = AppConfig.getJdbcPassword();

        maxPoolSize = AppConfig.getDbPoolMaxSize();
        minIdle     = AppConfig.getDbPoolMinIdle();
        if (minIdle > maxPoolSize) {
            logger.warn("db.pool.min.idle ({}) exceeds db.pool.max.size ({}); using {}",
                    minIdle, maxPoolSize, maxPoolSize);
            minIdle = maxPoolSize;
        }
    }

    /**
     * Overrides the DB URL (used by tests). Any pool bound to the previous URL
     * is closed first, so callers must invoke {@link #initializeDatabase()}
     * before requesting connections again.
     */
    public static synchronized void setDbUrl(String url) {
        closePool();
        dbUrl      = url;
        dbUser     = "sa";
        dbPassword = "";
    }

    /**
     * Borrows a connection from the pool. Closing it returns it to the pool
     * instead of closing the underlying physical connection.
     */
    public static Connection getConnection() throws SQLException {
        HikariDataSource ds = dataSource;
        if (ds == null || ds.isClosed()) {
            throw new SQLException("DatabasePool is not initialized");
        }
        return ds.getConnection();
    }

    /** Visible for tests: the underlying pool, or {@code null} when not initialized. */
    static HikariDataSource getDataSource() {
        return dataSource;
    }

    /**
     * Creates the pool (if needed) and applies pending Flyway migrations.
     *
     * <p>Existing non-empty databases are baselined at version {@code 0} so
     * that the idempotent {@code V1__Initial_schema.sql} still runs and can add
     * anything an older installation lacks (RSA/FEK columns, secondary
     * indexes) without touching existing rows. Fresh databases simply run
     * V1 from scratch.</p>
     */
    public static synchronized void initializeDatabase() {
        HikariDataSource ds = dataSource;
        if (ds == null || ds.isClosed()) {
            try {
                ds = new HikariDataSource(buildConfig());
                dataSource = ds;
            } catch (RuntimeException e) {
                dataSource = null;
                logger.error("Failed to initialize database pool: {}",
                        e.getClass().getSimpleName());
                throw new RuntimeException("Failed to initialize database", e);
            }
        }

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();

        try {
            MigrateResult result = flyway.migrate();
            logger.info("Database schema up to date; {} migration(s) applied",
                    result.migrationsExecuted);
        } catch (FlywayException e) {
            logger.error("Failed to initialize database schema: {}",
                    e.getClass().getSimpleName());
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    /** Closes the connection pool. Safe to call multiple times. */
    public static synchronized void shutdownDatabase() {
        closePool();
    }

    private static HikariConfig buildConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setPoolName(POOL_NAME);
        config.setIdleTimeout(IDLE_TIMEOUT_MILLIS);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        return config;
    }

    private static void closePool() {
        HikariDataSource ds = dataSource;
        dataSource = null;
        if (ds != null && !ds.isClosed()) {
            try {
                ds.close();
            } catch (RuntimeException e) {
                logger.warn("Failed to close database pool cleanly: {}",
                        e.getClass().getSimpleName());
            }
        }
    }
}
