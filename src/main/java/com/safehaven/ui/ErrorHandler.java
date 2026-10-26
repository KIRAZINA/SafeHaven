package com.safehaven.ui;

import com.safehaven.exception.AuthenticationException;
import com.safehaven.exception.CryptoException;
import com.safehaven.exception.StorageException;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Centralized JavaFX dialog handling.
 *
 * <p>Every dialog is dispatched onto the JavaFX Application Thread, no matter
 * which thread calls this class. Failed operations are logged with a sanitized
 * cause chain, while users see a stable, non-technical explanation.</p>
 */
public final class ErrorHandler {

    private static final Logger logger = LoggerFactory.getLogger(ErrorHandler.class);

    private static final String GENERIC_MESSAGE =
            "An unexpected error occurred. Please try again.";

    private static final Pattern WINDOWS_PATH =
            Pattern.compile("[A-Za-z]:\\\\[^\\s\"']+");
    private static final Pattern WINDOWS_UNC_PATH =
            Pattern.compile("\\\\\\\\[^\\s\"']+");
    private static final Pattern UNIX_ABSOLUTE_PATH =
            Pattern.compile("(?<![\\w:/])/(?:[^\\s\"':]+/)*[^\\s\"':]+");

    /** Minimal alert abstraction, replaceable in tests. */
    @FunctionalInterface
    interface AlertPresenter {
        void show(Alert.AlertType type, String title, String header, String content);
    }

    @FunctionalInterface
    interface FxDispatcher {
        void dispatch(Runnable action);
    }

    private static volatile AlertPresenter presenter = ErrorHandler::showJavaFxAlert;
    private static volatile FxDispatcher dispatcher = ErrorHandler::dispatchToFxThread;

    private ErrorHandler() {
    }

    public static void showError(String title, String header, String content) {
        show(Alert.AlertType.ERROR, title, header, content);
    }

    public static void showWarning(String title, String header, String content) {
        show(Alert.AlertType.WARNING, title, header, content);
    }

    public static void showInfo(String title, String header, String content) {
        show(Alert.AlertType.INFORMATION, title, header, content);
    }

    /**
     * Reports a failed user action. Technical details stay out of the dialog
     * and only a sanitized exception chain is written to the log.
     */
    public static void handleException(String userAction, Throwable throwable) {
        String action = (userAction == null || userAction.isBlank())
                ? "The operation"
                : userAction;

        logger.error("{} failed [{}]", action, sanitizedCauseChain(throwable));
        showError("Operation Failed",
                action + " could not be completed.",
                mapExceptionToUserMessage(throwable));
    }

    /** Maps an exception type to a clear, non-technical user message. */
    static String mapExceptionToUserMessage(Throwable throwable) {
        if (throwable == null) {
            return GENERIC_MESSAGE;
        }

        // A domain failure may be wrapped by a technical exception. Prefer the
        // domain message wherever it appears in the chain, because those
        // messages are curated and safe to show.
        String domainMessage = findDomainMessage(throwable);
        if (domainMessage != null) {
            return domainMessage;
        }

        if (throwable instanceof SQLException) {
            return "A database error prevented this action. Please try again.";
        }
        if (throwable instanceof IOException) {
            return "A file could not be read or written. "
                    + "Please check the file location and try again.";
        }
        if (throwable instanceof SecurityException) {
            return "You do not have permission to perform this action.";
        }
        if (throwable instanceof IllegalArgumentException) {
            return "The information provided is not valid. Please review it and try again.";
        }
        return GENERIC_MESSAGE;
    }

    private static String findDomainMessage(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof AuthenticationException) {
                return "Authentication failed. Please check your username and password.";
            }
            if (current instanceof StorageException) {
                return "The file could not be accessed. "
                        + "Please check that it exists and that you have permission.";
            }
            if (current instanceof CryptoException) {
                return "The encrypted data could not be processed. "
                        + "The file may be corrupted or the key is invalid.";
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Builds a log-safe description of an exception chain. Only exception
     * class names are included for technical exceptions; messages are included
     * for SafeHaven domain exceptions, whose messages are deliberately
     * path-free and contain no secrets.
     */
    static String sanitizedCauseChain(Throwable throwable) {
        if (throwable == null) {
            return "none";
        }

        StringBuilder chain = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;

        while (current != null && depth < 5) {
            if (depth > 0) {
                chain.append(" <- ");
            }
            chain.append(current.getClass().getSimpleName());

            if (isDomainException(current)) {
                String message = current.getMessage();
                if (message != null && !message.isBlank()) {
                    chain.append(": ").append(redactPaths(message));
                }
            }

            current = current.getCause();
            depth++;
        }
        return chain.toString();
    }

    private static boolean isDomainException(Throwable throwable) {
        return throwable instanceof AuthenticationException
                || throwable instanceof StorageException
                || throwable instanceof CryptoException;
    }

    /**
     * Replaces filesystem-looking fragments in a domain message with a neutral
     * token, so an unexpected message can never expose a local directory.
     */
    private static String redactPaths(String message) {
        String redacted = WINDOWS_PATH.matcher(message).replaceAll("<path>");
        redacted = WINDOWS_UNC_PATH.matcher(redacted).replaceAll("<path>");
        redacted = UNIX_ABSOLUTE_PATH.matcher(redacted).replaceAll("<path>");
        return redacted;
    }

    private static void show(Alert.AlertType type, String title, String header, String content) {
        String safeTitle = title == null ? "" : title;
        String safeContent = content == null ? "" : content;
        String safeHeader = header;

        dispatcher.dispatch(() -> {
            try {
                presenter.show(type, safeTitle, safeHeader, safeContent);
            } catch (RuntimeException e) {
                // A dialog failure must never break the calling workflow.
                logger.warn("Could not display {} dialog: {}",
                        type, e.getClass().getSimpleName());
            }
        });
    }

    private static void dispatchToFxThread(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (RuntimeException e) {
            // The toolkit may be unavailable (for example in headless tests).
            logger.debug("JavaFX toolkit unavailable; suppressing dialog");
        }
    }

    private static void showJavaFxAlert(Alert.AlertType type, String title,
                                        String header, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // ── Test seams ──────────────────────────────────────────────────────────

    static void setPresenterForTesting(AlertPresenter testPresenter) {
        presenter = testPresenter;
    }

    static void setFxDispatcherForTesting(FxDispatcher testDispatcher) {
        dispatcher = testDispatcher;
    }

    static void resetForTesting() {
        presenter = ErrorHandler::showJavaFxAlert;
        dispatcher = ErrorHandler::dispatchToFxThread;
    }
}
