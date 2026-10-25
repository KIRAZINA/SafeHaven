package com.safehaven.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InactivityServiceTest {

    @Test
    void firesAfterConfiguredInactivity() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);

        try (InactivityService service = new InactivityService(
                300, TimeUnit.MILLISECONDS,
                () -> {
                    invocations.incrementAndGet();
                    fired.countDown();
                })) {

            service.start();
            assertTrue(service.isRunning());

            assertTrue(fired.await(3, TimeUnit.SECONDS),
                    "Inactivity timeout must fire the session termination handler");
            assertEquals(1, invocations.get(), "Timeout handler must run exactly once");
            assertFalse(service.isRunning(), "Service must stop after firing");
        }
    }

    @Test
    void userActivityResetsInactivityTimer() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);

        try (InactivityService service = new InactivityService(
                300, TimeUnit.MILLISECONDS,
                () -> {
                    invocations.incrementAndGet();
                    fired.countDown();
                })) {

            service.start();

            Thread.sleep(200);
            service.recordActivity(); // reset before the original deadline

            assertFalse(fired.await(200, TimeUnit.MILLISECONDS),
                    "Activity must postpone the timeout");
            assertEquals(0, invocations.get());

            assertTrue(fired.await(2, TimeUnit.SECONDS),
                    "Timer must fire once inactivity resumes");
            assertEquals(1, invocations.get());
        }
    }

    @Test
    void stopCancelsPendingTimeout() throws Exception {
        AtomicInteger invocations = new AtomicInteger();

        try (InactivityService service = new InactivityService(
                150, TimeUnit.MILLISECONDS, invocations::incrementAndGet)) {

            service.start();
            service.stop();

            Thread.sleep(400);
            assertEquals(0, invocations.get(),
                    "Stopped service must not invoke the timeout handler");
            assertFalse(service.isRunning());
        }
    }

    @Test
    void concurrentActivityKeepsSessionAlive() throws Exception {
        AtomicInteger invocations = new AtomicInteger();

        try (InactivityService service = new InactivityService(
                200, TimeUnit.MILLISECONDS, invocations::incrementAndGet)) {

            service.start();
            for (int i = 0; i < 6; i++) {
                Thread.sleep(100);
                service.recordActivity();
            }
            assertEquals(0, invocations.get(),
                    "Continuous activity must keep resetting the timer");
        }
    }

    @Test
    void configuredTimeoutDefaultsToFiveMinutes() {
        long timeout = InactivityService.loadConfiguredTimeoutSeconds();
        assertEquals(300L, timeout,
                "application.properties must define a 300-second inactivity timeout");
    }
}
