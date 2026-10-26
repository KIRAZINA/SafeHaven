package com.safehaven.exception;

/**
 * Signals a cryptographic failure such as an encryption/decryption error, a
 * failed authentication-tag check, or an RSA key unwrap failure.
 *
 * <p>Messages attached to this exception must never contain key material or
 * other sensitive cryptographic data.</p>
 */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
