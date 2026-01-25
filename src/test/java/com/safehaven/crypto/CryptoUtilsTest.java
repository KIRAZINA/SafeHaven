package com.safehaven.crypto;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class CryptoUtilsTest {

    @Test
    void testEncryptionDecryption() {
        String originalText = "Hello, SafeHaven!";
        char[] password = "strongpassword".toCharArray();
        byte[] salt = CryptoUtils.generateSalt();
        
        SecretKey key = CryptoUtils.generateKeyFromPassword(password, salt);
        
        byte[] encrypted = CryptoUtils.encrypt(originalText.getBytes(StandardCharsets.UTF_8), key);
        byte[] decrypted = CryptoUtils.decrypt(encrypted, key);
        
        String decryptedText = new String(decrypted, StandardCharsets.UTF_8);
        
        assertEquals(originalText, decryptedText, "Decrypted text should match original");
    }

    @Test
    void testSaltGeneration() {
        byte[] salt1 = CryptoUtils.generateSalt();
        byte[] salt2 = CryptoUtils.generateSalt();
        
        assertFalse(Arrays.equals(salt1, salt2), "Salts should be unique");
        assertEquals(16, salt1.length, "Salt length should be 16 bytes");
    }

    @Test
    void testKeyGenerationDeterministic() {
        char[] password = "password".toCharArray();
        byte[] salt = new byte[16]; // Zero salt for deterministic test
        
        SecretKey key1 = CryptoUtils.generateKeyFromPassword(password, salt);
        SecretKey key2 = CryptoUtils.generateKeyFromPassword(password, salt);
        
        assertArrayEquals(key1.getEncoded(), key2.getEncoded(), "Same password and salt should generate same key");
    }
}
