package com.safehaven.exception;

/**
 * Signals a file-storage failure such as a missing file, an inaccessible
 * location, or a database write that could not be completed.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
