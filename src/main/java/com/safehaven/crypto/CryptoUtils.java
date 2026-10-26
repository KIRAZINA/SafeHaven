package com.safehaven.crypto;

import com.safehaven.exception.CryptoException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
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
    private static final int    GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH / 8;
    private static final int    STREAM_NONCE_LENGTH = 8;

    /**
     * Chunk size for the versioned chunked-AEAD stream format. Every chunk is
     * independently authenticated, so decryption never needs to retain more
     * than one chunk (plus its tag and IV) in memory.
     */
    public static final int STREAM_CHUNK_SIZE = 64 * 1024;

    /**
     * Magic/version header for the chunked format. The first byte is the
     * format version ({@code 0x02}); the remaining bytes make accidental
     * collision with a legacy random IV vanishingly unlikely.
     */
    static final byte[] STREAM_MAGIC = new byte[] {
            0x02, 'S', 'H', 'A', 'V', 'E', 'N', '2'
    };

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
     * Encrypts the bytes read from {@code in} to {@code out} using the versioned
     * chunked-AEAD stream format. The output is:
     *
     * <pre>
     *   magic/version (8 bytes) || file nonce (8 bytes)
     *   repeated: chunk length (4 bytes) || ciphertext (length bytes) || tag (16 bytes)
     *   end marker: zero chunk length (4 bytes)
     * </pre>
     *
     * Each chunk is independently authenticated, so decryption memory stays
     * bounded regardless of the total stream size. The supplied {@code out}
     * stream is closed by this method.
     */
    public static void encryptStream(InputStream in, OutputStream out, SecretKey key) throws Exception {
        try (OutputStream cipherOut = createEncryptingOutputStream(out, key)) {
            copyStream(in, cipherOut);
        }
    }

    /**
     * Encrypts {@code in} to {@code out} using the legacy v1 single-stream
     * format ({@code IV (12 bytes) || ciphertext+tag}). New data always uses
     * {@link #encryptStream}; this method exists so tests and compatibility
     * tooling can produce payloads that exercise the legacy fallback.
     */
    public static void encryptLegacyStream(InputStream in, OutputStream out, SecretKey key)
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

        out.write(iv);
        try (CipherOutputStream cipherOut = new CipherOutputStream(out, cipher)) {
            copyStream(in, cipherOut);
        }
    }

    /**
     * Wraps {@code out} in a chunked-AEAD encrypting stream. The header is
     * written immediately. The caller owns the returned stream and must close
     * it so that any buffered final chunk and the end marker are written.
     */
    public static OutputStream createEncryptingOutputStream(OutputStream out, SecretKey key)
            throws Exception {
        return new ChunkedGcmOutputStream(out, key);
    }

    /**
     * Decrypts data produced by {@link #encryptStream} (chunked format) or by
     * the legacy v1 single-stream format from {@code in} to {@code out}. The
     * supplied {@code in} stream is closed; {@code out} is flushed but left
     * open for the caller.
     */
    public static void decryptStream(InputStream in, OutputStream out, SecretKey key) throws Exception {
        try (InputStream cipherIn = createDecryptingInputStream(in, key)) {
            copyStream(cipherIn, out);
        }
        out.flush();
    }

    /**
     * Detects the payload format and returns a decrypting {@link InputStream}.
     *
     * <p>Chunked payloads start with {@link #STREAM_MAGIC}; anything else is
     * treated as a legacy v1 payload whose first bytes are the leading part of
     * the 12-byte raw IV. The caller owns the returned stream and must read it
     * to EOF (or close it) so every authentication tag is verified.</p>
     */
    public static InputStream createDecryptingInputStream(InputStream in, SecretKey key)
            throws Exception {
        byte[] header = new byte[8];
        readFully(in, header, 0, header.length, "encrypted stream header");

        if (Arrays.equals(header, STREAM_MAGIC)) {
            byte[] nonce = new byte[STREAM_NONCE_LENGTH];
            readFully(in, nonce, 0, nonce.length, "chunked stream nonce");
            return new ChunkedGcmInputStream(in, key, nonce);
        }

        // Legacy v1.x: the eight bytes already consumed are the first part of
        // the 12-byte IV; read the remaining four bytes.
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(header, 0, iv, 0, header.length);
        readFully(in, iv, header.length, GCM_IV_LENGTH - header.length, "legacy stream IV");

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

    private static void readFully(InputStream in, byte[] buffer, int offset, int length,
                                  String what) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read == -1) {
                throw new EOFException(
                        "Encrypted stream is truncated while reading " + what);
            }
            total += read;
        }
    }

    private static int readChunkLength(InputStream in) throws IOException {
        byte[] lengthBytes = new byte[4];
        readFully(in, lengthBytes, 0, lengthBytes.length, "chunk length");
        return ((lengthBytes[0] & 0xFF) << 24)
                | ((lengthBytes[1] & 0xFF) << 16)
                | ((lengthBytes[2] & 0xFF) << 8)
                | (lengthBytes[3] & 0xFF);
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    /**
     * Buffers plaintext into fixed-size chunks and writes each one as an
     * independent AES-256-GCM ciphertext. Bounded memory: at most one chunk
     * is held in addition to the caller's stream buffers.
     */
    private static final class ChunkedGcmOutputStream extends OutputStream {
        private final OutputStream out;
        private final SecretKey key;
        private final byte[] nonce;
        private final byte[] buffer = new byte[STREAM_CHUNK_SIZE];
        private int buffered;
        private int chunkIndex;
        private boolean closed;

        private ChunkedGcmOutputStream(OutputStream out, SecretKey key) throws IOException {
            this.out = out;
            this.key = key;
            this.nonce = new byte[STREAM_NONCE_LENGTH];
            SECURE_RANDOM.nextBytes(nonce);
            out.write(STREAM_MAGIC);
            out.write(nonce);
        }

        @Override
        public void write(int value) throws IOException {
            if (buffered == STREAM_CHUNK_SIZE) {
                flushChunk();
            }
            buffer[buffered++] = (byte) value;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (data == null) {
                throw new NullPointerException();
            }
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IndexOutOfBoundsException();
            }
            while (length > 0) {
                if (buffered == STREAM_CHUNK_SIZE) {
                    flushChunk();
                }
                int copy = Math.min(length, STREAM_CHUNK_SIZE - buffered);
                System.arraycopy(data, offset, buffer, buffered, copy);
                buffered += copy;
                offset += copy;
                length -= copy;
            }
        }

        @Override
        public void flush() throws IOException {
            flushChunk();
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                flushChunk();
                out.write(new byte[4]); // zero-length end marker
                out.flush();
            } finally {
                out.close();
            }
        }

        private void flushChunk() throws IOException {
            if (buffered == 0) {
                return;
            }

            byte[] lengthBytes = intToBytes(buffered);
            byte[] counterBytes = intToBytes(chunkIndex);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(nonce, 0, iv, 0, STREAM_NONCE_LENGTH);
            System.arraycopy(counterBytes, 0, iv, STREAM_NONCE_LENGTH, counterBytes.length);

            // Bind both the chunk index and its plaintext length so a chunk
            // cannot be silently reordered or reshaped.
            byte[] aad = new byte[8];
            System.arraycopy(lengthBytes, 0, aad, 0, lengthBytes.length);
            System.arraycopy(counterBytes, 0, aad, lengthBytes.length, counterBytes.length);

            byte[] ciphertext;
            try {
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                cipher.updateAAD(aad);
                ciphertext = cipher.doFinal(buffer, 0, buffered);
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not encrypt stream chunk " + chunkIndex, e);
            }

            out.write(lengthBytes);
            out.write(ciphertext);
            chunkIndex++;
            buffered = 0;
        }
    }

    /**
     * Decrypts the chunked format one chunk at a time. Only the current
     * plaintext chunk (at most {@link #STREAM_CHUNK_SIZE} bytes) and its
     * ciphertext are retained, so heap use is independent of file size.
     */
    private static final class ChunkedGcmInputStream extends InputStream {
        private final InputStream in;
        private final SecretKey key;
        private final byte[] nonce;
        private int chunkIndex;
        private byte[] chunk = new byte[0];
        private int position;
        private boolean endOfStream;
        private boolean closed;

        private ChunkedGcmInputStream(InputStream in, SecretKey key, byte[] nonce) {
            this.in = in;
            this.key = key;
            this.nonce = nonce;
        }

        @Override
        public int read() throws IOException {
            if (!ensureChunk()) {
                return -1;
            }
            return chunk[position++] & 0xFF;
        }

        @Override
        public int read(byte[] data, int offset, int length) throws IOException {
            if (data == null) {
                throw new NullPointerException();
            }
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            if (!ensureChunk()) {
                return -1;
            }
            int count = Math.min(length, chunk.length - position);
            System.arraycopy(chunk, position, data, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                // Drain so any remaining chunk tags and the end marker are
                // verified even when the caller stops reading early.
                byte[] drain = new byte[STREAM_BUFFER_SIZE];
                while (read(drain) != -1) {
                    // discard
                }
            } finally {
                in.close();
            }
        }

        private boolean ensureChunk() throws IOException {
            while (position >= chunk.length) {
                if (endOfStream) {
                    return false;
                }

                int length = readChunkLength(in);
                if (length == 0) {
                    endOfStream = true;
                    chunk = new byte[0];
                    position = 0;
                    return false;
                }
                if (length < 0 || length > STREAM_CHUNK_SIZE) {
                    throw new IOException("Invalid encrypted chunk length: " + length);
                }

                byte[] lengthBytes = intToBytes(length);
                byte[] counterBytes = intToBytes(chunkIndex);
                byte[] iv = new byte[GCM_IV_LENGTH];
                System.arraycopy(nonce, 0, iv, 0, STREAM_NONCE_LENGTH);
                System.arraycopy(counterBytes, 0, iv, STREAM_NONCE_LENGTH, counterBytes.length);

                byte[] aad = new byte[8];
                System.arraycopy(lengthBytes, 0, aad, 0, lengthBytes.length);
                System.arraycopy(counterBytes, 0, aad, lengthBytes.length, counterBytes.length);

                byte[] ciphertext = new byte[length + GCM_TAG_LENGTH_BYTES];
                readFully(in, ciphertext, 0, ciphertext.length, "encrypted chunk data");

                try {
                    Cipher cipher = Cipher.getInstance(ALGORITHM);
                    cipher.init(Cipher.DECRYPT_MODE, key,
                            new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                    cipher.updateAAD(aad);
                    chunk = cipher.doFinal(ciphertext);
                } catch (AEADBadTagException e) {
                    throw new IOException("Encrypted chunk failed authentication", e);
                } catch (GeneralSecurityException e) {
                    throw new IOException(
                            "Could not decrypt stream chunk " + chunkIndex, e);
                }

                position = 0;
                chunkIndex++;
            }
            return true;
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
