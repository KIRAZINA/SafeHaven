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

    @Test
    void testRsaKeyPairGeneration() {
        java.security.KeyPair keyPair = CryptoUtils.generateRsaKeyPair();
        assertNotNull(keyPair, "KeyPair should not be null");
        assertNotNull(keyPair.getPublic(), "PublicKey should not be null");
        assertNotNull(keyPair.getPrivate(), "PrivateKey should not be null");
        assertEquals("RSA", keyPair.getPublic().getAlgorithm(), "Algorithm should be RSA");
    }

    @Test
    void testWrapAndUnwrapFek() throws Exception {
        java.security.KeyPair keyPair = CryptoUtils.generateRsaKeyPair();
        SecretKey fek = CryptoUtils.generateFileKey();

        byte[] wrapped = CryptoUtils.wrapKey(fek, keyPair.getPublic());
        assertNotNull(wrapped, "Wrapped key should not be null");

        SecretKey unwrappedFek = CryptoUtils.unwrapKey(wrapped, keyPair.getPrivate());
        assertNotNull(unwrappedFek, "Unwrapped key should not be null");
        assertArrayEquals(fek.getEncoded(), unwrappedFek.getEncoded(), "Unwrapped key should match original FEK");
    }

    @Test
    void testEncryptAndDecryptPrivateKey() {
        java.security.KeyPair keyPair = CryptoUtils.generateRsaKeyPair();
        SecretKey masterKey = CryptoUtils.generateKeyFromPassword("password".toCharArray(), CryptoUtils.generateSalt());

        byte[] encryptedPkcs8 = CryptoUtils.encryptPrivateKey(keyPair.getPrivate(), masterKey);
        assertNotNull(encryptedPkcs8, "Encrypted private key bytes should not be null");

        java.security.PrivateKey decryptedKey = CryptoUtils.decryptPrivateKey(encryptedPkcs8, masterKey);
        assertNotNull(decryptedKey, "Decrypted private key should not be null");
        assertArrayEquals(keyPair.getPrivate().getEncoded(), decryptedKey.getEncoded(), "Decoded private key should match original");
    }

    @Test
    void testEncryptWithCorruptedIv() {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "Top Secret".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoUtils.encrypt(data, key);

        // Corrupt IV (first 12 bytes)
        encrypted[0] = (byte) ~encrypted[0];

        assertThrows(RuntimeException.class, () -> CryptoUtils.decrypt(encrypted, key), 
            "Decrypting with corrupted IV should throw an exception due to AEAD tag mismatch");
    }
}
