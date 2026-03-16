package com.safehaven.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public class CryptoUtils {
    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    private static final String RSA_ALGORITHM  = "RSA";
    private static final String RSA_CIPHER     = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int    AES_KEY_SIZE   = 256;
    private static final int    RSA_KEY_SIZE   = 2048;
    private static final int    GCM_IV_LENGTH  = 12;
    private static final int    GCM_TAG_LENGTH = 128;
    private static final int    PBKDF2_ITERATIONS = 65536;
    private static final int    SALT_LENGTH    = 16;

    // ── Password / Master key ────────────────────────────────────────────────

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static SecretKey generateKeyFromPassword(char[] password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE);
            SecretKey secret = factory.generateSecret(spec);
            return new SecretKeySpec(secret.getEncoded(), "AES");
        } catch (Exception e) {
            throw new RuntimeException("Error generating key from password", e);
        }
    }

    // ── Symmetric AES-GCM ────────────────────────────────────────────────────

    /** Generates a random AES-256 file encryption key (FEK). */
    public static SecretKey generateFileKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_SIZE, new SecureRandom());
            return kg.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("AES not available", e);
        }
    }

    /**
     * Encrypts {@code data} with {@code key} using AES-256-GCM.
     * Returns {@code IV (12 bytes) || ciphertext+tag}.
     */
    public static byte[] encrypt(byte[] data, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(data);

            byte[] result = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(cipherText, 0, result, iv.length, cipherText.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Error encrypting data", e);
        }
    }

    /**
     * Decrypts data produced by {@link #encrypt}.
     * Input format: {@code IV (12 bytes) || ciphertext+tag}.
     */
    public static byte[] decrypt(byte[] data, SecretKey key) {
        try {
            if (data.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Data too short to contain IV");
            }
            byte[] iv         = Arrays.copyOfRange(data, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(data, GCM_IV_LENGTH, data.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new RuntimeException("Error decrypting data", e);
        }
    }

    // ── RSA-2048 OAEP ────────────────────────────────────────────────────────

    /** Generates a fresh RSA-2048 key pair for a new user. */
    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(RSA_ALGORITHM);
            kpg.initialize(RSA_KEY_SIZE, new SecureRandom());
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA not available", e);
        }
    }

    /**
     * Wraps (encrypts) a symmetric FEK with a recipient's RSA public key.
     * Uses RSA/ECB/OAEPWithSHA-256AndMGF1Padding.
     */
    public static byte[] wrapKey(SecretKey fek, PublicKey recipientPublicKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_CIPHER);
        cipher.init(Cipher.WRAP_MODE, recipientPublicKey);
        return cipher.wrap(fek);
    }

    /**
     * Unwraps (decrypts) a wrapped FEK using the owner's RSA private key.
     */
    public static SecretKey unwrapKey(byte[] wrappedKey, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_CIPHER);
        cipher.init(Cipher.UNWRAP_MODE, privateKey);
        return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);
    }

    // ── Private-key serialisation (PKCS#8 + AES-GCM) ────────────────────────

    /**
     * Encrypts the RSA private key (PKCS#8 bytes) with the user's AES master key.
     * Stored in USERS.encrypted_private_key.
     */
    public static byte[] encryptPrivateKey(PrivateKey privateKey, SecretKey masterKey) {
        return encrypt(privateKey.getEncoded(), masterKey);
    }

    /**
     * Restores an RSA {@link PrivateKey} from {@code encrypted} bytes
     * using the user's AES master key.
     */
    public static PrivateKey decryptPrivateKey(byte[] encrypted, SecretKey masterKey) {
        try {
            byte[] pkcs8 = decrypt(encrypted, masterKey);
            KeyFactory kf = KeyFactory.getInstance(RSA_ALGORITHM);
            return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new RuntimeException("Error decrypting private key", e);
        }
    }

    /**
     * Deserialises an RSA {@link PublicKey} from raw X.509/SubjectPublicKeyInfo bytes.
     * Stored as-is (plaintext) in USERS.public_key.
     */
    public static PublicKey decodePublicKey(byte[] encoded) {
        try {
            KeyFactory kf = KeyFactory.getInstance(RSA_ALGORITHM);
            return kf.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new RuntimeException("Error decoding public key", e);
        }
    }
}
