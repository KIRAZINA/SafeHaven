package com.safehaven.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    private final Map<String, String> systemProperties = new HashMap<>();
    private final Map<String, String> environment = new HashMap<>();
    private Properties fileProperties;

    @BeforeEach
    void setUp() {
        systemProperties.clear();
        environment.clear();
        fileProperties = new Properties();

        AppConfig.setSystemPropertyLookupForTesting(systemProperties::get);
        AppConfig.setEnvironmentLookupForTesting(environment::get);
        AppConfig.setFilePropertiesForTesting(fileProperties);
    }

    @AfterEach
    void tearDown() {
        AppConfig.resetForTesting();
    }

    @Test
    void systemPropertiesOverrideEnvironmentAndFileProperties() {
        fileProperties.setProperty(AppConfig.KEY_JDBC_URL, "file-url");
        environment.put("SAFEHAVEN_JDBC_URL", "environment-url");
        systemProperties.put(AppConfig.KEY_JDBC_URL, "system-url");

        assertEquals("system-url", AppConfig.getJdbcUrl());
    }

    @Test
    void environmentOverridesFileProperties() {
        fileProperties.setProperty(AppConfig.KEY_JDBC_URL, "file-url");
        environment.put("SAFEHAVEN_JDBC_URL", "environment-url");

        assertEquals("environment-url", AppConfig.getJdbcUrl());
    }

    @Test
    void filePropertiesAreUsedWhenNoHigherPrioritySourceExists() {
        fileProperties.setProperty(AppConfig.KEY_JDBC_USER, "file-user");

        assertEquals("file-user", AppConfig.getJdbcUser());
    }

    @Test
    void unprefixedEnvironmentVariableNamesAreAccepted() {
        environment.put("JDBC_USER", "environment-user");

        assertEquals("environment-user", AppConfig.getJdbcUser());
    }

    @Test
    void hardcodedDefaultsAreUsedWhenNothingIsConfigured() {
        systemProperties.put("user.home", "/home/tester");

        assertEquals(AppConfig.DEFAULT_JDBC_URL, AppConfig.getJdbcUrl());
        assertEquals(AppConfig.DEFAULT_JDBC_USER, AppConfig.getJdbcUser());
        assertEquals(AppConfig.DEFAULT_JDBC_PASSWORD, AppConfig.getJdbcPassword());
        assertEquals("/home/tester/.securestorage", AppConfig.getStorageDir());
        assertEquals(AppConfig.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                AppConfig.getInactivityTimeoutSeconds());
        assertEquals(AppConfig.DEFAULT_DB_POOL_MAX_SIZE, AppConfig.getDbPoolMaxSize());
        assertEquals(AppConfig.DEFAULT_DB_POOL_MIN_IDLE, AppConfig.getDbPoolMinIdle());
    }

    @Test
    void mapsConfigurationKeysToEnvironmentVariableNames() {
        assertEquals("SAFEHAVEN_JDBC_URL", AppConfig.toEnvironmentKey("jdbc.url"));
        assertEquals("SAFEHAVEN_JDBC_USER", AppConfig.toEnvironmentKey("jdbc.user"));
        assertEquals("SAFEHAVEN_STORAGE_DIR", AppConfig.toEnvironmentKey("storage.dir"));
        assertEquals("SAFEHAVEN_DB_POOL_MAX_SIZE",
                AppConfig.toEnvironmentKey("db.pool.max.size"));
        assertEquals("SAFEHAVEN_INACTIVITY_TIMEOUT",
                AppConfig.toEnvironmentKey(AppConfig.KEY_INACTIVITY_TIMEOUT));

        assertEquals(List.of("SAFEHAVEN_JDBC_URL", "JDBC_URL"),
                AppConfig.environmentCandidates("jdbc.url"));
    }

    @Test
    void storageDirExpandsUserHomePlaceholderAtCallTime() {
        systemProperties.put("user.home", "/home/alice");
        systemProperties.put(AppConfig.KEY_STORAGE_DIR, "${user.home}/vault");

        assertEquals("/home/alice/vault", AppConfig.getStorageDir());

        systemProperties.put("user.home", "/home/bob");
        assertEquals("/home/bob/vault", AppConfig.getStorageDir(),
                "The placeholder must resolve against the current user.home");
    }

    @Test
    void storageDirCanBeOverriddenByEnvironment() {
        systemProperties.put("user.home", "/home/alice");
        environment.put("SAFEHAVEN_STORAGE_DIR", "/srv/safehaven/storage");

        assertEquals("/srv/safehaven/storage", AppConfig.getStorageDir());
    }

    @Test
    void validNumericOverridesAreParsed() {
        environment.put("SAFEHAVEN_DB_POOL_MAX_SIZE", "32");
        environment.put("SAFEHAVEN_DB_POOL_MIN_IDLE", "4");
        environment.put("SAFEHAVEN_INACTIVITY_TIMEOUT", "45");

        assertEquals(32, AppConfig.getDbPoolMaxSize());
        assertEquals(4, AppConfig.getDbPoolMinIdle());
        assertEquals(45L, AppConfig.getInactivityTimeoutSeconds());
    }

    @Test
    void nonNumericOverridesFallBackToHardcodedDefaults() {
        environment.put("SAFEHAVEN_DB_POOL_MAX_SIZE", "not-a-number");
        environment.put("SAFEHAVEN_INACTIVITY_TIMEOUT", "soon");

        assertEquals(AppConfig.DEFAULT_DB_POOL_MAX_SIZE, AppConfig.getDbPoolMaxSize());
        assertEquals(AppConfig.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                AppConfig.getInactivityTimeoutSeconds());
    }

    @Test
    void negativeAndZeroOverridesFallBackToHardcodedDefaults() {
        systemProperties.put(AppConfig.KEY_DB_POOL_MIN_IDLE, "-5");
        environment.put("SAFEHAVEN_DB_POOL_MAX_SIZE", "0");
        environment.put("SAFEHAVEN_INACTIVITY_TIMEOUT", "0");

        assertEquals(AppConfig.DEFAULT_DB_POOL_MIN_IDLE, AppConfig.getDbPoolMinIdle());
        assertEquals(AppConfig.DEFAULT_DB_POOL_MAX_SIZE, AppConfig.getDbPoolMaxSize());
        assertEquals(AppConfig.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                AppConfig.getInactivityTimeoutSeconds());
    }

    @Test
    void invalidSystemPropertyWinsOverValidEnvironmentThenFallsBackSafely() {
        systemProperties.put(AppConfig.KEY_DB_POOL_MAX_SIZE, "invalid");
        environment.put("SAFEHAVEN_DB_POOL_MAX_SIZE", "20");

        assertEquals(AppConfig.DEFAULT_DB_POOL_MAX_SIZE, AppConfig.getDbPoolMaxSize());
    }

    @Test
    void unknownKeysReturnNullOrExplicitFallback() {
        assertNull(AppConfig.get("unknown.key"));
        assertEquals("fallback",
                AppConfig.getOrDefault("unknown.key", "fallback"));
    }

    @Test
    void blankKeysAreRejected() {
        assertTrue(throwsIllegalArgument(() -> AppConfig.get("  ")));
        assertTrue(throwsIllegalArgument(() -> AppConfig.toEnvironmentKey(null)));
    }

    private static boolean throwsIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
