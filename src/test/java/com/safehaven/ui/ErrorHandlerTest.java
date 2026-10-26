package com.safehaven.ui;

import com.safehaven.exception.AuthenticationException;
import com.safehaven.exception.CryptoException;
import com.safehaven.exception.StorageException;
import javafx.scene.control.Alert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorHandlerTest {

    /** Captures the dialog that ErrorHandler would have shown. */
    private record ShownDialog(Alert.AlertType type, String title,
                               String header, String content) { }

    private final List<ShownDialog> shown = new ArrayList<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        shown.clear();
        pending.clear();
        ErrorHandler.setPresenterForTesting(
                (type, title, header, content) ->
                        shown.add(new ShownDialog(type, title, header, content)));
        ErrorHandler.setFxDispatcherForTesting(pending::add);
    }

    @AfterEach
    void tearDown() {
        ErrorHandler.resetForTesting();
    }

    // ── Exception mapping ───────────────────────────────────────────────────

    @Test
    void mapsAuthenticationExceptionToCredentialMessage() {
        String message = ErrorHandler.mapExceptionToUserMessage(
                new AuthenticationException("Invalid username: too short"));
        assertTrue(message.toLowerCase().contains("authentication"));
        assertFalse(message.contains("too short"),
                "Validation details must not be copied into the dialog");
    }

    @Test
    void mapsStorageExceptionToFileAccessMessage() {
        String message = ErrorHandler.mapExceptionToUserMessage(
                new StorageException("C:\\Users\\alice\\.securestorage\\file.enc missing"));
        assertTrue(message.toLowerCase().contains("file"));
        assertFalse(message.contains("C:\\Users"),
                "Filesystem paths must not leak into user-facing text");
    }

    @Test
    void mapsCryptoExceptionToIntegrityMessage() {
        String message = ErrorHandler.mapExceptionToUserMessage(
                new CryptoException("Could not decrypt the data."));
        assertTrue(message.toLowerCase().contains("encrypted"));
    }

    @Test
    void mapsDomainExceptionEvenWhenWrappedByTechnicalFailure() {
        Throwable wrapped = new RuntimeException("outer failure",
                new IllegalStateException("middle failure",
                        new CryptoException("tag mismatch at /var/data/secret.enc")));

        String message = ErrorHandler.mapExceptionToUserMessage(wrapped);

        assertTrue(message.toLowerCase().contains("encrypted"));
        assertFalse(message.contains("/var/data"));
        assertFalse(message.contains("tag mismatch"));
    }

    @Test
    void mapsJdbcAndIoExceptionsWithoutRevealingDetails() {
        assertTrue(ErrorHandler.mapExceptionToUserMessage(
                new SQLException("SELECT * FROM USERS failed at line 12"))
                .toLowerCase().contains("database"));

        String io = ErrorHandler.mapExceptionToUserMessage(
                new IOException("C:\\temp\\out.txt is locked"));
        assertTrue(io.toLowerCase().contains("file"));
        assertFalse(io.contains("C:\\temp"));
    }

    @Test
    void mapsPermissionsAndInvalidInput() {
        assertTrue(ErrorHandler.mapExceptionToUserMessage(
                new SecurityException("denied")).toLowerCase().contains("permission"));
        assertTrue(ErrorHandler.mapExceptionToUserMessage(
                new IllegalArgumentException("bad input")).toLowerCase().contains("valid"));
    }

    @Test
    void unknownAndNullThrowablesUseGenericMessage() {
        String generic = ErrorHandler.mapExceptionToUserMessage(
                new IllegalStateException("internal detail"));
        assertNotNull(generic);
        assertFalse(generic.contains("internal detail"));

        assertEquals(generic, ErrorHandler.mapExceptionToUserMessage(null));
    }

    // ── Sanitized cause chain ───────────────────────────────────────────────

    @Test
    void causeChainOmitsTechnicalMessages() {
        Throwable error = new RuntimeException(
                "C:\\secret\\store.db connection refused for user alice",
                new IOException("/home/alice/.securestorage/file.enc"));

        String chain = ErrorHandler.sanitizedCauseChain(error);

        assertEquals("RuntimeException <- IOException", chain);
        assertFalse(chain.contains("C:\\secret"));
        assertFalse(chain.contains("/home/alice"));
        assertFalse(chain.contains("alice"));
    }

    @Test
    void causeChainKeepsDomainMessagesAndRedactsPaths() {
        Throwable error = new StorageException(
                "C:\\Users\\alice\\.securestorage\\x.enc could not be read",
                new CryptoException("Could not decrypt the data."));

        String chain = ErrorHandler.sanitizedCauseChain(error);

        assertTrue(chain.startsWith("StorageException: "));
        assertTrue(chain.contains("<path>"));
        assertFalse(chain.contains("C:\\Users"));
        assertTrue(chain.contains("CryptoException: Could not decrypt the data."));
    }

    @Test
    void causeChainRedactsArbitraryUnixAndUncPaths() {
        Throwable error = new StorageException(
                "Could not read /var/lib/safehaven/cache or \\\\server\\share\\file.enc");

        String chain = ErrorHandler.sanitizedCauseChain(error);

        assertTrue(chain.contains("<path>"));
        assertFalse(chain.contains("/var/lib"));
        assertFalse(chain.contains("\\\\server"));
    }

    @Test
    void causeChainHandlesNullAndLimitsDepth() {
        assertEquals("none", ErrorHandler.sanitizedCauseChain(null));

        Throwable deep = new RuntimeException("level");
        for (int i = 0; i < 10; i++) {
            deep = new RuntimeException("level", deep);
        }
        String chain = ErrorHandler.sanitizedCauseChain(deep);
        assertEquals(4, chain.split("<-", -1).length - 1,
                "Only five entries in the chain should be described");
    }

    // ── Dialog dispatch ─────────────────────────────────────────────────────

    @Test
    void alertIsDispatchedAndCarriesContent() {
        ErrorHandler.showWarning("Heads Up", "Check this", "Something to note");

        assertTrue(shown.isEmpty(), "Dialog must wait for the FX dispatcher");
        assertEquals(1, pending.size());

        pending.remove().run();

        assertEquals(1, shown.size());
        ShownDialog dialog = shown.get(0);
        assertEquals(Alert.AlertType.WARNING, dialog.type());
        assertEquals("Heads Up", dialog.title());
        assertEquals("Check this", dialog.header());
        assertEquals("Something to note", dialog.content());
    }

    @Test
    void showHelpersUseExpectedAlertTypes() {
        ErrorHandler.showError("e", null, null);
        ErrorHandler.showInfo("i", null, null);
        ErrorHandler.showWarning("w", null, null);

        pending.forEach(Runnable::run);

        assertEquals(Alert.AlertType.ERROR, shown.get(0).type());
        assertEquals(Alert.AlertType.INFORMATION, shown.get(1).type());
        assertEquals(Alert.AlertType.WARNING, shown.get(2).type());
    }

    @Test
    void nullTitleAndContentAreNormalised() {
        ErrorHandler.showInfo(null, null, null);
        pending.remove().run();

        ShownDialog dialog = shown.get(0);
        assertEquals("", dialog.title());
        assertEquals("", dialog.content());
        assertNull(dialog.header());
    }

    @Test
    void presenterFailureDoesNotEscape() {
        ErrorHandler.setPresenterForTesting((type, title, header, content) -> {
            throw new IllegalStateException("FX broken");
        });

        ErrorHandler.showError("Title", null, "Content");

        // Must not throw.
        pending.remove().run();
    }

    // ── handleException ─────────────────────────────────────────────────────

    @Test
    void handleExceptionShowsGenericDialogForTechnicalFailure() {
        Throwable failure = new RuntimeException(
                "jdbc:h2:./safehaven failed: C:\\Users\\alice\\.securestorage\\a.enc",
                new SQLException("SELECT failed"));

        ErrorHandler.handleException("Loading your files", failure);
        pending.remove().run();

        assertEquals(1, shown.size());
        ShownDialog dialog = shown.get(0);
        assertEquals(Alert.AlertType.ERROR, dialog.type());
        assertEquals("Operation Failed", dialog.title());
        assertTrue(dialog.header().contains("Loading your files"));
        assertTrue(dialog.content().toLowerCase().contains("unexpected"));
        assertNotEquals("", dialog.content());

        String allText = dialog.title() + dialog.header() + dialog.content();
        assertFalse(allText.contains("C:\\Users"),
                "Local paths must never appear in the dialog");
        assertFalse(allText.contains("SELECT"),
                "SQL text must never appear in the dialog");
    }

    @Test
    void handleExceptionMapsDomainFailures() {
        ErrorHandler.handleException("Downloading your file",
                new CryptoException("tag mismatch"));
        pending.remove().run();

        assertTrue(shown.get(0).content().toLowerCase().contains("encrypted"));
    }

    @Test
    void handleExceptionToleratesNullThrowableAndBlankAction() {
        ErrorHandler.handleException("  ", null);
        pending.remove().run();

        ShownDialog dialog = shown.get(0);
        assertEquals("Operation Failed", dialog.title());
        assertTrue(dialog.header().contains("operation"));
        assertTrue(dialog.content().toLowerCase().contains("unexpected"));
    }

    @Test
    void handleExceptionRespectsDispatcherThreading() {
        Thread caller = Thread.currentThread();
        Thread[] dispatchedOn = new Thread[1];
        ErrorHandler.setFxDispatcherForTesting(action -> {
            dispatchedOn[0] = caller;
            pending.add(action);
        });

        ErrorHandler.handleException("Saving", new StorageException("gone"));

        pending.remove().run();
        assertEquals(caller, dispatchedOn[0],
                "Dialog must run through the dispatcher, not inline");
        assertEquals(Alert.AlertType.ERROR, shown.get(0).type());
    }
}
