package com.safehaven.service;

import com.safehaven.utils.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Monitors user inactivity and invokes a callback once the configured timeout
 * elapses without any registered activity.
 *
 * <p>The service is thread-safe: every call to {@link #recordActivity()} bumps
 * an activity version and reschedules the timeout. A scheduled check only
 * fires if the version it captured is still current, preventing a stale timer
 * from ending a session after the user has interacted.</p>
 */
public class InactivityService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(InactivityService.class);

    public static final long DEFAULT_TIMEOUT_SECONDS = 300L;
    public static final String TIMEOUT_PROPERTY = AppConfig.KEY_INACTIVITY_TIMEOUT;

    private final long timeoutMillis;
    private final Runnable onTimeout;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    private boolean running;
    private long activityVersion;
    private ScheduledFuture<?> scheduledTimeout;

    public InactivityService(long timeoutSeconds, Runnable onTimeout) {
        this(timeoutSeconds, TimeUnit.SECONDS, onTimeout);
    }

    InactivityService(long timeout, TimeUnit unit, Runnable onTimeout) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Inactivity timeout must be positive");
        }
        this.timeoutMillis = unit.toMillis(timeout);
        this.onTimeout = Objects.requireNonNull(onTimeout, "onTimeout");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "safehaven-inactivity-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Builds a service using {@code session.inactivity.timeout.seconds} from
     * {@link AppConfig}, falling back to 300 seconds.
     */
    public static InactivityService fromConfiguration(Runnable onTimeout) {
        return new InactivityService(loadConfiguredTimeoutSeconds(), onTimeout);
    }

    /**
     * Reads the configured inactivity timeout through {@link AppConfig}.
     * Invalid/missing values fall back to {@link #DEFAULT_TIMEOUT_SECONDS}.
     */
    public static long loadConfiguredTimeoutSeconds() {
        return AppConfig.getInactivityTimeoutSeconds();
    }

    /** Starts (or restarts) inactivity monitoring. */
    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            activityVersion++;
            scheduleLocked(activityVersion);
        }
    }

    /**
     * Registers a user action and resets the inactivity timer.
     */
    public void recordActivity() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            activityVersion++;
            scheduleLocked(activityVersion);
        }
    }

    /** Stops monitoring and cancels any pending timeout. */
    public void stop() {
        synchronized (lock) {
            running = false;
            if (scheduledTimeout != null) {
                scheduledTimeout.cancel(false);
                scheduledTimeout = null;
            }
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    public long getTimeoutSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(timeoutMillis);
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
    }

    private void scheduleLocked(long version) {
        if (scheduledTimeout != null) {
            scheduledTimeout.cancel(false);
        }
        scheduledTimeout = scheduler.schedule(
                () -> checkTimeout(version), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void checkTimeout(long version) {
        boolean shouldNotify = false;

        synchronized (lock) {
            if (running && activityVersion == version) {
                running = false;
                scheduledTimeout = null;
                shouldNotify = true;
            }
        }

        if (shouldNotify) {
            try {
                onTimeout.run();
            } catch (RuntimeException e) {
                logger.error("Inactivity timeout handler failed: {}",
                        e.getClass().getSimpleName());
            }
        }
    }
}
