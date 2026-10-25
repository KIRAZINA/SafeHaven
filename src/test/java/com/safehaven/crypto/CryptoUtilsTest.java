package com.safehaven.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
        SecretKey masterKey = CryptoUtils.deriveMasterKey("password".toCharArray(), CryptoUtils.generateSalt());

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

    @Test
    void testDecryptWithWrongKey_throws() {
        String originalText = "Secret message";
        SecretKey originalKey = CryptoUtils.generateFileKey();
        byte[] encrypted = CryptoUtils.encrypt(originalText.getBytes(StandardCharsets.UTF_8), originalKey);

        SecretKey wrongKey = CryptoUtils.generateFileKey();
        assertThrows(RuntimeException.class, () -> CryptoUtils.decrypt(encrypted, wrongKey),
            "Decrypting with a different key should throw");
    }

    @Test
    void testDifferentSalts_ProduceDifferentKeys() {
        char[] password = "samePassword".toCharArray();
        byte[] salt1 = new byte[16];
        byte[] salt2 = new byte[16];
        salt2[0] = 0x01;
        Arrays.fill(salt1, (byte) 0);

        SecretKey key1 = CryptoUtils.generateKeyFromPassword(password, salt1);
        SecretKey key2 = CryptoUtils.generateKeyFromPassword(password, salt2);

        assertFalse(Arrays.equals(key1.getEncoded(), key2.getEncoded()),
            "Different salts must produce different keys for the same password");
    }

    @Test
    void testUnwrapKey_WrongPrivateKey_throws() throws Exception {
        KeyPair alicePair = CryptoUtils.generateRsaKeyPair();
        KeyPair bobPair = CryptoUtils.generateRsaKeyPair();
        SecretKey fek = CryptoUtils.generateFileKey();

        byte[] wrapped = CryptoUtils.wrapKey(fek, alicePair.getPublic());

        assertThrows(Exception.class, () -> CryptoUtils.unwrapKey(wrapped, bobPair.getPrivate()),
            "Unwrapping with wrong private key should throw");
    }

    @Test
    void testDecryptCorruptedCiphertext_throws() {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "Sensitive data".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoUtils.encrypt(data, key);

        // Corrupt a body byte (skip the 12-byte IV)
        int bodyPos = 12 + 1;
        encrypted[bodyPos] = (byte) ~encrypted[bodyPos];

        assertThrows(RuntimeException.class, () -> CryptoUtils.decrypt(encrypted, key),
            "Decrypting with corrupted ciphertext body should throw due to AEAD tag mismatch");
    }

    @Test
    void testDecryptCorruptedAuthTag_throws() {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "Important data".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoUtils.encrypt(data, key);

        // Corrupt the last byte (part of the 128-bit auth tag appended by GCM)
        encrypted[encrypted.length - 1] = (byte) ~encrypted[encrypted.length - 1];

        assertThrows(RuntimeException.class, () -> CryptoUtils.decrypt(encrypted, key),
            "Decrypting with corrupted auth tag should throw");
    }

    @Test
    void testDecryptTruncatedCiphertext_throws() {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "Data".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoUtils.encrypt(data, key);

        // Truncate to fewer bytes than the IV length (triggers data.length < GCM_IV_LENGTH check)
        byte[] truncated = Arrays.copyOf(encrypted, 11);

        assertThrows(Exception.class, () -> CryptoUtils.decrypt(truncated, key),
            "Decrypting data shorter than IV length should throw");
    }

    @Test
    void testDecryptTruncatedToIvOnly_throws() {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "Data".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoUtils.encrypt(data, key);

        // Truncate to exactly the IV length (passes length check but fails in doFinal)
        byte[] truncated = Arrays.copyOf(encrypted, 12);

        assertThrows(Exception.class, () -> CryptoUtils.decrypt(truncated, key),
            "Decrypting truncated ciphertext with only IV should throw");
    }

    @Test
    void testIvUniqueness_AcrossEncryptions() {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "Same content".getBytes(StandardCharsets.UTF_8);
        Set<String> seenIvs = new HashSet<>();
        int count = 20;

        for (int i = 0; i < count; i++) {
            byte[] encrypted = CryptoUtils.encrypt(data, key);
            byte[] iv = Arrays.copyOf(encrypted, 12);
            seenIvs.add(Arrays.toString(iv));
        }

        assertEquals(count, seenIvs.size(),
            "Each encryption must produce a unique IV (no duplicates in " + count + " encryptions)");
    }

    // ── Streaming AES-GCM ────────────────────────────────────────────────────

    @Test
    void streamEncryptDecrypt_RoundTrip() throws Exception {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] original = "Streamed secret payload — 世界 🌍".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        CryptoUtils.encryptStream(new ByteArrayInputStream(original), encrypted, key);

        byte[] cipherBytes = encrypted.toByteArray();
        assertTrue(cipherBytes.length > 12,
                "Ciphertext must contain a 12-byte IV plus ciphertext and tag");

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        CryptoUtils.decryptStream(new ByteArrayInputStream(cipherBytes), decrypted, key);

        assertArrayEquals(original, decrypted.toByteArray(),
                "Stream round-trip must recover the original bytes");
    }

    @Test
    void streamEncrypt_UsesFreshTwelveByteIvHeader() throws Exception {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "IV header check".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        CryptoUtils.encryptStream(new ByteArrayInputStream(data), first, key);
        CryptoUtils.encryptStream(new ByteArrayInputStream(data), second, key);

        byte[] firstBytes = first.toByteArray();
        byte[] secondBytes = second.toByteArray();

        assertFalse(Arrays.equals(Arrays.copyOf(firstBytes, 12),
                                  Arrays.copyOf(secondBytes, 12)),
                "Each stream encryption must use a fresh random IV");

        ByteArrayOutputStream roundTrip = new ByteArrayOutputStream();
        CryptoUtils.decryptStream(new ByteArrayInputStream(firstBytes), roundTrip, key);
        assertArrayEquals(data, roundTrip.toByteArray());
    }

    @Test
    void streamEncryptDecrypt_LargeSyntheticPayload(@TempDir Path tempDir) throws Exception {
        final long payloadSize = 4L * 1024 * 1024; // 4 MiB, never materialised in one array
        SecretKey key = CryptoUtils.generateFileKey();
        Path encrypted = tempDir.resolve("large.enc");
        Path decrypted = tempDir.resolve("large.dec");

        try (InputStream in = syntheticStream(payloadSize);
             OutputStream out = Files.newOutputStream(encrypted)) {
            CryptoUtils.encryptStream(in, out, key);
        }

        try (InputStream in = Files.newInputStream(encrypted);
             OutputStream out = Files.newOutputStream(decrypted)) {
            CryptoUtils.decryptStream(in, out, key);
        }

        assertEquals(payloadSize, Files.size(decrypted),
                "Decrypted size must match the synthetic payload size");

        try (InputStream in = Files.newInputStream(decrypted)) {
            byte[] buffer = new byte[8192];
            long position = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    assertEquals((byte) ((position * 31 + 7) & 0xFF), buffer[i],
                            "Large payload byte mismatch at offset " + position);
                    position++;
                }
            }
            assertEquals(payloadSize, position);
        }
    }

    @Test
    void decryptStream_ShortIv_Throws() {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] tooShort = new byte[11];

        assertThrows(IOException.class, () ->
                CryptoUtils.decryptStream(
                        new ByteArrayInputStream(tooShort),
                        new ByteArrayOutputStream(),
                        key),
                "Streams shorter than the 12-byte IV must be rejected");
    }

    @Test
    void decryptStream_CorruptedTag_Throws() throws Exception {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] data = "Authenticated stream".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        CryptoUtils.encryptStream(new ByteArrayInputStream(data), encrypted, key);
        byte[] cipherBytes = encrypted.toByteArray();

        cipherBytes[cipherBytes.length - 1] ^= 0x01;

        assertThrows(Exception.class, () ->
                CryptoUtils.decryptStream(
                        new ByteArrayInputStream(cipherBytes),
                        new ByteArrayOutputStream(),
                        key),
                "Corrupting the GCM tag must fail stream decryption");
    }

    private static InputStream syntheticStream(long length) {
        return new InputStream() {
            private long position = 0;

            @Override
            public int read() {
                if (position >= length) {
                    return -1;
                }
                return (int) ((position++ * 31 + 7) & 0xFF);
            }

            @Override
            public int read(byte[] buffer, int offset, int len) {
                if (position >= length) {
                    return -1;
                }
                int count = (int) Math.min(len, length - position);
                for (int i = 0; i < count; i++) {
                    buffer[offset + i] = (byte) ((position++ * 31 + 7) & 0xFF);
                }
                return count;
            }
        };
    }
}
