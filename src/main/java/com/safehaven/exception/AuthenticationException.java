package com.safehaven.exception;

/**
 * Signals an authentication or account-registration problem that can be
 * described safely to the user.
 *
 * <p>Extends {@link IllegalArgumentException} so existing credential
 * validation callers that catch invalid-argument errors keep working.</p>
 */
public class AuthenticationException extends IllegalArgumentException {

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
