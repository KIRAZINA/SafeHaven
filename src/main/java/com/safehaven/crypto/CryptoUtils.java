package com.safehaven.crypto;

import com.safehaven.exception.CryptoException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
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
    private static final int    STREAM_BUFFER_SIZE = 8192;
    private static final String AUTH_DOMAIN    = "safehaven-auth-v1";
    private static final String MASTER_KEY_DOMAIN = "safehaven-mk-v1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
            throw new CryptoException("Could not derive a key from the password.", e);
        }
    }

    /**
     * Derives a 256-bit authentication verifier from password + salt using
     * domain-separated PBKDF2. The verifier is used ONLY for login comparison
     * and is stored as USERS.password_hash. It CANNOT be used as an encryption
     * key — that would require the separately-derived master key.
     */
    public static byte[] deriveAuthVerifier(char[] password, byte[] salt) {
        try {
            byte[] domainSalt = concat(AUTH_DOMAIN.getBytes(StandardCharsets.UTF_8), salt);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password, domainSalt, PBKDF2_ITERATIONS, AES_KEY_SIZE);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new CryptoException("Could not derive the authentication verifier.", e);
        }
    }

    /**
     * Derives the AES-256 master encryption key from password + salt using
     * domain-separated PBKDF2. This key encrypts the user's RSA private key
     * and wraps every file's FEK. It is NEVER stored in the database.
     */
    public static SecretKey deriveMasterKey(char[] password, byte[] salt) {
        try {
            byte[] domainSalt = concat(MASTER_KEY_DOMAIN.getBytes(StandardCharsets.UTF_8), salt);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password, domainSalt, PBKDF2_ITERATIONS, AES_KEY_SIZE);
            SecretKey secret = factory.generateSecret(spec);
            return new SecretKeySpec(secret.getEncoded(), "AES");
        } catch (Exception e) {
            throw new CryptoException("Could not derive the master key.", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    // ── Symmetric AES-GCM ────────────────────────────────────────────────────

    /** Generates a random AES-256 file encryption key (FEK). */
    public static SecretKey generateFileKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_SIZE, new SecureRandom());
            return kg.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException("Could not generate a file encryption key.", e);
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
            throw new CryptoException("Could not encrypt the data.", e);
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
            throw new CryptoException("Could not decrypt the data.", e);
        }
    }

    // ── Symmetric AES-GCM stream processing ──────────────────────────────────

    /**
     * Encrypts the bytes read from {@code in} to {@code out} using AES-256-GCM.
     * The output format is {@code IV (12 bytes) || ciphertext+tag}. A fresh
     * random IV is generated for every call and written before the ciphertext.
     * The supplied {@code out} stream is closed by this method.
     */
    public static void encryptStream(InputStream in, OutputStream out, SecretKey key) throws Exception {
        try (CipherOutputStream cipherOut = createEncryptingOutputStream(out, key)) {
            copyStream(in, cipherOut);
        }
    }

    /**
     * Wraps {@code out} in an AES-256-GCM {@link CipherOutputStream}. The
     * 12-byte IV is generated and written to {@code out} immediately, before
     * any ciphertext. The caller owns the returned stream and must close it so
     * that the GCM authentication tag is written.
     */
    public static CipherOutputStream createEncryptingOutputStream(OutputStream out, SecretKey key)
            throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);

        Cipher cipher;
        try {
            cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Could not initialize encryption.", e);
        }

        // Header must be written before any ciphertext.
        out.write(iv);
        out.flush();

        return new CipherOutputStream(out, cipher);
    }

    /**
     * Decrypts AES-256-GCM data produced by {@link #encryptStream} from
     * {@code in} to {@code out}. The first 12 bytes of {@code in} are consumed
     * as the IV. The supplied {@code in} stream is closed; {@code out} is
     * flushed but left open for the caller.
     */
    public static void decryptStream(InputStream in, OutputStream out, SecretKey key) throws Exception {
        try (CipherInputStream cipherIn = createDecryptingInputStream(in, key)) {
            copyStream(cipherIn, out);
        }
        out.flush();
    }

    /**
     * Wraps {@code in} in an AES-256-GCM {@link CipherInputStream}, reading
     * exactly the leading 12-byte IV first. The caller owns the returned
     * stream and must read it to EOF so the GCM tag is verified.
     */
    public static CipherInputStream createDecryptingInputStream(InputStream in, SecretKey key)
            throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        int offset = 0;
        while (offset < GCM_IV_LENGTH) {
            int read = in.read(iv, offset, GCM_IV_LENGTH - offset);
            if (read == -1) {
                throw new IOException(
                        "Encrypted stream is too short to contain a " + GCM_IV_LENGTH + "-byte IV");
            }
            offset += read;
        }

        Cipher cipher;
        try {
            cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Could not initialize decryption.", e);
        }
        return new CipherInputStream(in, cipher);
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
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
            throw new CryptoException("Could not generate an RSA key pair.", e);
        }
    }

    /**
     * Wraps (encrypts) a symmetric FEK with a recipient's RSA public key.
     * Uses RSA/ECB/OAEPWithSHA-256AndMGF1Padding.
     */
    public static byte[] wrapKey(SecretKey fek, PublicKey recipientPublicKey) throws Exception {
        try {
            Cipher cipher = Cipher.getInstance(RSA_CIPHER);
            cipher.init(Cipher.WRAP_MODE, recipientPublicKey);
            return cipher.wrap(fek);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Could not wrap the file key.", e);
        }
    }

    /**
     * Unwraps (decrypts) a wrapped FEK using the owner's RSA private key.
     */
    public static SecretKey unwrapKey(byte[] wrappedKey, PrivateKey privateKey) throws Exception {
        try {
            Cipher cipher = Cipher.getInstance(RSA_CIPHER);
            cipher.init(Cipher.UNWRAP_MODE, privateKey);
            return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Could not unwrap the file key.", e);
        }
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
            throw new CryptoException("Could not decrypt the private key.", e);
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
            throw new CryptoException("Could not decode the public key.", e);
        }
    }
}
