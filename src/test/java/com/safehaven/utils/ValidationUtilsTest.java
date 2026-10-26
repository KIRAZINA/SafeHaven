package com.safehaven.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidationUtilsTest {

    // ─── Usernames ──────────────────────────────────────────────────────────

    @Test
    void validUsernames_areAccepted() {
        assertTrue(ValidationUtils.isValidUsername("abc"));
        assertTrue(ValidationUtils.isValidUsername("User_123"));
        assertTrue(ValidationUtils.isValidUsername("a-b-c"));
        assertTrue(ValidationUtils.isValidUsername("A".repeat(32)),
                "32 characters is the inclusive maximum");
        assertTrue(ValidationUtils.isValidUsername("_user-1"));
    }

    @Test
    void invalidUsernames_areRejected() {
        assertFalse(ValidationUtils.isValidUsername(null));
        assertFalse(ValidationUtils.isValidUsername(""));
        assertFalse(ValidationUtils.isValidUsername("   "));
        assertFalse(ValidationUtils.isValidUsername("ab"), "2 characters is below the minimum");
        assertFalse(ValidationUtils.isValidUsername("a".repeat(33)),
                "33 characters exceeds the maximum");
        assertFalse(ValidationUtils.isValidUsername("user name"), "spaces are not allowed");
        assertFalse(ValidationUtils.isValidUsername("user/name"), "slashes are not allowed");
        assertFalse(ValidationUtils.isValidUsername("user\\name"), "backslashes are not allowed");
        assertFalse(ValidationUtils.isValidUsername("user.name"), "dots are not allowed");
        assertFalse(ValidationUtils.isValidUsername("user@name"), "punctuation is not allowed");
        assertFalse(ValidationUtils.isValidUsername("üser"), "non-ASCII letters are not allowed");
        assertFalse(ValidationUtils.isValidUsername("user\n"), "control characters are not allowed");
    }

    @Test
    void usernameErrors_describeTheViolatedRules() {
        assertEquals(List.of("Username is required."),
                ValidationUtils.getUsernameValidationErrors("  "));
        assertTrue(ValidationUtils.getUsernameValidationErrors("ab").stream()
                .anyMatch(e -> e.contains("between 3 and 32")));
        assertTrue(ValidationUtils.getUsernameValidationErrors("bad name").stream()
                .anyMatch(e -> e.contains("only letters, numbers, underscores, and hyphens")));
    }

    // ─── Passwords ──────────────────────────────────────────────────────────

    @Test
    void validPasswords_areAccepted() {
        assertTrue(ValidationUtils.isValidPassword("Password1!"));
        assertTrue(ValidationUtils.isValidPassword("Abcdef1!"), "exactly 8 characters is valid");
        assertTrue(ValidationUtils.isValidPassword("Aa1!" + "a".repeat(124)),
                "exactly 128 characters is valid");
        assertTrue(ValidationUtils.isValidPassword("P@ssw0rd"));
    }

    @Test
    void everyConfiguredSpecialCharacter_isAccepted() {
        for (char special : ValidationUtils.SPECIAL_CHARACTERS.toCharArray()) {
            assertTrue(ValidationUtils.isValidPassword("Abcdef1" + special),
                    "Special character should be accepted: " + special);
        }
    }

    @Test
    void invalidPasswords_areRejected() {
        assertFalse(ValidationUtils.isValidPassword((char[]) null));
        assertFalse(ValidationUtils.isValidPassword((String) null));
        assertFalse(ValidationUtils.isValidPassword(new char[0]));
        assertFalse(ValidationUtils.isValidPassword("Abc1!"), "below minimum length");
        assertFalse(ValidationUtils.isValidPassword("Aa1!" + "a".repeat(125)),
                "above maximum length");
        assertFalse(ValidationUtils.isValidPassword("password1!"), "missing uppercase letter");
        assertFalse(ValidationUtils.isValidPassword("PASSWORD1!"), "missing lowercase letter");
        assertFalse(ValidationUtils.isValidPassword("Password!"), "missing digit");
        assertFalse(ValidationUtils.isValidPassword("Password1"), "missing special character");
        assertFalse(ValidationUtils.isValidPassword("Password1 "), "space is not a special character");
        assertFalse(ValidationUtils.isValidPassword("Password1/"), "slash is not accepted");
    }

    @Test
    void passwordErrors_listEveryMissingRequirement() {
        List<String> errors = ValidationUtils.getPasswordValidationErrors("abc".toCharArray());

        assertEquals(4, errors.size(), "short password missing upper, digit and special");
        assertTrue(errors.get(0).contains("at least 8"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("uppercase")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("digit")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("special")));

        assertEquals(List.of("Password is required."),
                ValidationUtils.getPasswordValidationErrors(null));
        assertEquals(List.of("Password is required."),
                ValidationUtils.getPasswordValidationErrors(new char[0]));
    }

    @Test
    void passwordErrors_reportOnlyTheMaximumWhenTooLong() {
        List<String> errors = ValidationUtils.getPasswordValidationErrors(
                ("Aa1!" + "a".repeat(125)).toCharArray());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("no more than 128"));
    }

    @Test
    void validationDoesNotModifyThePasswordBuffer() {
        char[] password = "Password1!".toCharArray();

        assertTrue(ValidationUtils.isValidPassword(password));
        assertArrayEquals("Password1!".toCharArray(), password,
                "Validation must not wipe or alter the caller's buffer");
    }
}
