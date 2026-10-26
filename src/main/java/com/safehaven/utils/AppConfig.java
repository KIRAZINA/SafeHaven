package com.safehaven.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Central configuration provider.
 *
 * <p>Values are resolved in this order:</p>
 * <ol>
 *   <li>System properties ({@code -Dkey=value})</li>
 *   <li>Environment variables</li>
 *   <li>{@code /application.properties} on the classpath</li>
 *   <li>Hardcoded defaults</li>
 * </ol>
 *
 * <p>Environment names use the {@code SAFEHAVEN_} prefix and upper snake case
 * ({@code jdbc.url} becomes {@code SAFEHAVEN_JDBC_URL}). The unprefixed form
 * ({@code JDBC_URL}) is also accepted as a fallback.</p>
 */
public final class AppConfig {

    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    public static final String KEY_JDBC_URL = "jdbc.url";
    public static final String KEY_JDBC_USER = "jdbc.user";
    public static final String KEY_JDBC_PASSWORD = "jdbc.password";
    public static final String KEY_STORAGE_DIR = "storage.dir";
    public static final String KEY_INACTIVITY_TIMEOUT = "session.inactivity.timeout.seconds";
    public static final String KEY_DB_POOL_MAX_SIZE = "db.pool.max.size";
    public static final String KEY_DB_POOL_MIN_IDLE = "db.pool.min.idle";

    public static final String DEFAULT_JDBC_URL =
            "jdbc:h2:./safehaven;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
    public static final String DEFAULT_JDBC_USER = "sa";
    public static final String DEFAULT_JDBC_PASSWORD = "";
    public static final String DEFAULT_STORAGE_DIR = "${user.home}/.securestorage";
    public static final long DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 300L;
    public static final int DEFAULT_DB_POOL_MAX_SIZE = 10;
    public static final int DEFAULT_DB_POOL_MIN_IDLE = 2;

    private static final String ENV_PREFIX = "SAFEHAVEN_";
    private static final String ENV_INACTIVITY_TIMEOUT = "SAFEHAVEN_INACTIVITY_TIMEOUT";
    private static final String USER_HOME_PLACEHOLDER = "${user.home}";

    private static final Map<String, String> DEFAULTS = Map.of(
            KEY_JDBC_URL, DEFAULT_JDBC_URL,
            KEY_JDBC_USER, DEFAULT_JDBC_USER,
            KEY_JDBC_PASSWORD, DEFAULT_JDBC_PASSWORD,
            KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR,
            KEY_INACTIVITY_TIMEOUT, Long.toString(DEFAULT_INACTIVITY_TIMEOUT_SECONDS),
            KEY_DB_POOL_MAX_SIZE, Integer.toString(DEFAULT_DB_POOL_MAX_SIZE),
            KEY_DB_POOL_MIN_IDLE, Integer.toString(DEFAULT_DB_POOL_MIN_IDLE)
    );

    private static volatile Function<String, String> systemPropertyLookup = System::getProperty;
    private static volatile Function<String, String> environmentLookup = System::getenv;
    private static volatile Properties fileProperties = loadClasspathProperties();

    private AppConfig() {
    }

    /** Resolves a configuration key using the documented priority order. */
    public static String get(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Configuration key must not be blank");
        }

        String systemValue = systemPropertyLookup.apply(key);
        if (hasValue(systemValue)) {
            return systemValue;
        }

        for (String environmentKey : environmentCandidates(key)) {
            String environmentValue = environmentLookup.apply(environmentKey);
            if (hasValue(environmentValue)) {
                return environmentValue;
            }
        }

        String fileValue = fileProperties.getProperty(key);
        if (hasValue(fileValue)) {
            return fileValue;
        }

        return DEFAULTS.get(key);
    }

    /** Resolves a key and applies the supplied fallback when it is not configured. */
    public static String getOrDefault(String key, String fallback) {
        String value = get(key);
        return value != null ? value : fallback;
    }

    public static String getJdbcUrl() {
        return getOrDefault(KEY_JDBC_URL, DEFAULT_JDBC_URL);
    }

    public static String getJdbcUser() {
        return getOrDefault(KEY_JDBC_USER, DEFAULT_JDBC_USER);
    }

    public static String getJdbcPassword() {
        return getOrDefault(KEY_JDBC_PASSWORD, DEFAULT_JDBC_PASSWORD);
    }

    /**
     * Returns the storage directory with {@code ${user.home}} expanded at call
     * time, so the value follows the current runtime user.
     */
    public static String getStorageDir() {
        String configured = getOrDefault(KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR);
        String home = systemPropertyLookup.apply("user.home");
        if (home == null || home.isBlank()) {
            home = ".";
        }
        return configured.replace(USER_HOME_PLACEHOLDER, home);
    }

    public static long getInactivityTimeoutSeconds() {
        return positiveLong(KEY_INACTIVITY_TIMEOUT, DEFAULT_INACTIVITY_TIMEOUT_SECONDS);
    }

    public static int getDbPoolMaxSize() {
        return positiveInt(KEY_DB_POOL_MAX_SIZE, DEFAULT_DB_POOL_MAX_SIZE);
    }

    public static int getDbPoolMinIdle() {
        return positiveInt(KEY_DB_POOL_MIN_IDLE, DEFAULT_DB_POOL_MIN_IDLE);
    }

    /**
     * Returns the primary environment-variable name for a configuration key.
     * For example, {@code jdbc.url} maps to {@code SAFEHAVEN_JDBC_URL}.
     */
    public static String toEnvironmentKey(String key) {
        return environmentCandidates(key).get(0);
    }

    /**
     * Returns every accepted environment-variable name for a key, most
     * specific first. The unprefixed form is accepted for deployment
     * environments that already namespace their variables.
     */
    static List<String> environmentCandidates(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Configuration key must not be blank");
        }

        String normalized = key.toUpperCase(Locale.ROOT)
                .replace('.', '_')
                .replace('-', '_');

        Set<String> candidates = new LinkedHashSet<>();
        if (KEY_INACTIVITY_TIMEOUT.equals(key)) {
            candidates.add(ENV_INACTIVITY_TIMEOUT);
        }
        candidates.add(ENV_PREFIX + normalized);
        candidates.add(normalized);
        return new ArrayList<>(candidates);
    }

    private static int positiveInt(String key, int fallback) {
        String raw = get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the safe default.
        }
        logger.warn("Invalid value for {}; using default {}", key, fallback);
        return fallback;
    }

    private static long positiveLong(String key, long fallback) {
        String raw = get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the safe default.
        }
        logger.warn("Invalid value for {}; using default {}", key, fallback);
        return fallback;
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static Properties loadClasspathProperties() {
        Properties properties = new Properties();
        try (InputStream in = AppConfig.class
                .getResourceAsStream("/application.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not load application.properties; "
                    + "using environment variables and defaults");
        }
        return properties;
    }

    // ── Test seams ──────────────────────────────────────────────────────────

    static void setSystemPropertyLookupForTesting(Function<String, String> lookup) {
        systemPropertyLookup = lookup == null ? System::getProperty : lookup;
    }

    static void setEnvironmentLookupForTesting(Function<String, String> lookup) {
        environmentLookup = lookup == null ? System::getenv : lookup;
    }

    static void setFilePropertiesForTesting(Properties properties) {
        fileProperties = properties == null ? new Properties() : properties;
    }

    static void resetForTesting() {
        systemPropertyLookup = System::getProperty;
        environmentLookup = System::getenv;
        fileProperties = loadClasspathProperties();
    }
}
