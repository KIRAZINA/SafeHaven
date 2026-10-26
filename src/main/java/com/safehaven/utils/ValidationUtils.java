package com.safehaven.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Thread-safe validation rules for account credentials.
 *
 * <p>All methods are static and operate only on their arguments; the class
 * holds no mutable state, so it is safe to call from any thread.</p>
 */
public final class ValidationUtils {

    public static final int USERNAME_MIN_LENGTH = 3;
    public static final int USERNAME_MAX_LENGTH = 32;

    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 128;

    /** Special characters accepted by the password complexity rule. */
    public static final String SPECIAL_CHARACTERS = "!@#$%^&*()_+-=[]{}|;:,.<>?";

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_-]{" + USERNAME_MIN_LENGTH + ","
                    + USERNAME_MAX_LENGTH + "}$");

    private ValidationUtils() {
    }

    /** Returns {@code true} when the username satisfies all account rules. */
    public static boolean isValidUsername(String username) {
        return getUsernameValidationErrors(username).isEmpty();
    }

    /**
     * Returns every username rule the value violates, in a stable order.
     * An empty list means the username is valid.
     */
    public static List<String> getUsernameValidationErrors(String username) {
        List<String> errors = new ArrayList<>();

        if (username == null || username.isBlank()) {
            errors.add("Username is required.");
            return List.copyOf(errors);
        }

        if (username.length() < USERNAME_MIN_LENGTH
                || username.length() > USERNAME_MAX_LENGTH) {
            errors.add("Username must be between " + USERNAME_MIN_LENGTH + " and "
                    + USERNAME_MAX_LENGTH + " characters long.");
        }

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            errors.add("Username may contain only letters, numbers, underscores, and hyphens.");
        }

        return List.copyOf(errors);
    }

    /** Returns {@code true} when the password satisfies every rule. */
    public static boolean isValidPassword(char[] password) {
        return getPasswordValidationErrors(password).isEmpty();
    }

    /** Returns {@code true} when the password satisfies every rule. */
    public static boolean isValidPassword(String password) {
        if (password == null) {
            return false;
        }
        char[] chars = password.toCharArray();
        try {
            return isValidPassword(chars);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Returns every password rule the value violates, in a stable order.
     * An empty list means the password is valid.
     *
     * <p>The supplied array is never modified.</p>
     */
    public static List<String> getPasswordValidationErrors(char[] password) {
        List<String> errors = new ArrayList<>();

        if (password == null || password.length == 0) {
            errors.add("Password is required.");
            return List.copyOf(errors);
        }

        if (password.length < PASSWORD_MIN_LENGTH) {
            errors.add("Password must be at least " + PASSWORD_MIN_LENGTH + " characters long.");
        }
        if (password.length > PASSWORD_MAX_LENGTH) {
            errors.add("Password must be no more than " + PASSWORD_MAX_LENGTH + " characters long.");
        }

        boolean hasUppercase = false;
        boolean hasLowercase = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char c : password) {
            if (c >= 'A' && c <= 'Z') {
                hasUppercase = true;
            } else if (c >= 'a' && c <= 'z') {
                hasLowercase = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (SPECIAL_CHARACTERS.indexOf(c) >= 0) {
                hasSpecial = true;
            }
        }

        if (!hasUppercase) {
            errors.add("Password must contain at least one uppercase letter.");
        }
        if (!hasLowercase) {
            errors.add("Password must contain at least one lowercase letter.");
        }
        if (!hasDigit) {
            errors.add("Password must contain at least one digit.");
        }
        if (!hasSpecial) {
            errors.add("Password must contain at least one special character ("
                    + SPECIAL_CHARACTERS + ").");
        }

        return List.copyOf(errors);
    }
}
