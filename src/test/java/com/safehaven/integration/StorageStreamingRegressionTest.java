package com.safehaven.integration;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import com.safehaven.service.StorageService;
import com.safehaven.utils.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves that download decryption is bounded-memory.
 *
 * <p>The actual work runs in a child JVM with a 64 MB heap and a payload larger
 * than that heap. If the download path buffered the whole ciphertext (as
 * {@code CipherInputStream} does for AES-GCM in SunJCE), the child would die
 * with {@link OutOfMemoryError}.</p>
 */
public class StorageStreamingRegressionTest {

    private static final long CHILD_HEAP_MB = 64L;
    private static final long PAYLOAD_BYTES = 96L * 1024 * 1024; // 96 MiB > 64 MiB heap

    @Test
    void largeFileDownload_CompletesWithinBoundedHeap(@TempDir Path tempDir) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }

        String javaBinary = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();

        Path logFile = tempDir.resolve("streaming-harness.log");
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary,
                "-Xmx" + CHILD_HEAP_MB + "m",
                "-XX:+DisableAttachMechanism",
                "-cp", classpath,
                StreamingHarness.class.getName(),
                tempDir.toString(),
                Long.toString(PAYLOAD_BYTES));
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());

        Process process = builder.start();
        boolean finished = process.waitFor(8, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            fail("Streaming harness did not finish within the timeout");
        }

        String output = Files.readString(logFile);
        assertEquals(0, process.exitValue(),
                "Child JVM failed (likely OutOfMemoryError). Output:\n" + output);
        assertTrue(output.contains("MAX_HEAP_MB=" + CHILD_HEAP_MB),
                "Child JVM should run with the constrained heap. Output:\n" + output);
        assertTrue(output.contains("ROUNDTRIP_OK=true"),
                "Large-file round-trip failed. Output:\n" + output);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Runs in a separate, small-heap JVM. Uploads and downloads a synthetic
     * payload larger than the heap without ever materialising it as one array.
     */
    public static final class StreamingHarness {

        private static final int BUFFER_SIZE = 8192;

        public static void main(String[] args) throws Exception {
            Path root = Paths.get(args[0]);
            long payloadBytes = Long.parseLong(args[1]);

            System.setProperty(AppConfig.KEY_JDBC_URL,
                    "jdbc:h2:mem:stream_child_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            System.setProperty(AppConfig.KEY_STORAGE_DIR, root.resolve("storage").toString());

            boolean ok = false;
            try {
                DatabaseManager.initializeDatabase();

                AuthService authService = new AuthService();
                authService.register("streamuser", "ValidPass1!");
                User user = authService.login("streamuser", "ValidPass1!");
                SecretKey masterKey = CryptoUtils.deriveMasterKey(
                        "ValidPass1!".toCharArray(), user.getSalt());

                Path source = root.resolve("payload.bin");
                String sourceHash = writeSyntheticPayload(source, payloadBytes);

                StorageService storage = new StorageService();
                storage.uploadFile(source.toFile(), user, masterKey);
                FileMetadata meta = storage.listFiles(user).get(0);

                Path target = root.resolve("downloaded.bin");
                storage.downloadFile(meta, user, masterKey, target.toFile());
                String targetHash = sha256(target);

                boolean hashMatches = sourceHash.equals(targetHash);
                boolean sizeMatches = Files.size(target) == payloadBytes;

                System.out.println("MAX_HEAP_MB=" + (Runtime.getRuntime().maxMemory()
                        / (1024 * 1024)));
                System.out.println("PAYLOAD_BYTES=" + payloadBytes);
                System.out.println("DOWNLOADED_BYTES=" + Files.size(target));
                System.out.println("HASH_MATCH=" + hashMatches);
                ok = hashMatches && sizeMatches;
                System.out.println("ROUNDTRIP_OK=" + ok);
            } finally {
                DatabaseManager.shutdownDatabase();
            }

            if (!ok) {
                System.exit(1);
            }
        }

        private static String writeSyntheticPayload(Path path, long length) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            Random random = new Random(20261026L);
            long remaining = length;

            try (OutputStream out = Files.newOutputStream(path)) {
                while (remaining > 0) {
                    int count = (int) Math.min(buffer.length, remaining);
                    random.nextBytes(buffer);
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
            }
            return toHex(digest.digest());
        }

        private static String sha256(Path path) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream in = Files.newInputStream(path)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        }

        private static String toHex(byte[] bytes) {
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >>> 4) & 0x0F, 16));
                hex.append(Character.forDigit(b & 0x0F, 16));
            }
            return hex.toString();
        }
    }
}
