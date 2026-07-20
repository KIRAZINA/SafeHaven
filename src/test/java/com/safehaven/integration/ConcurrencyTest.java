package com.safehaven.integration;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import com.safehaven.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {

    private static final String TEST_DB = "jdbc:h2:mem:test_conc_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private AuthService authService;
    private StorageService storageService;
    private User testUser;
    private SecretKey masterKey;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager.setDbUrl(TEST_DB);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        authService = new AuthService();
        storageService = new StorageService();

        authService.register("concurrent_user", "password");
        testUser = authService.login("concurrent_user", "password");
        masterKey = CryptoUtils.deriveMasterKey("password".toCharArray(), testUser.getSalt());
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        Path dir = Paths.get(System.getProperty("user.home"), ".securestorage", "concurrent_user");
        if (Files.exists(dir)) {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        }
    }

    @Test
    void concurrentUploads_Downloads_NoCorruption() throws Exception {
        int threadCount = 8;
        int filesPerThread = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Exception> exceptions = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < filesPerThread; i++) {
                        byte[] content = ("Thread-" + threadId + "-File-" + i).getBytes();
                        File tmp = File.createTempFile("conc_" + threadId + "_" + i, ".txt");
                        try {
                            Files.write(tmp.toPath(), content);
                            storageService.uploadFile(tmp, testUser, masterKey);
                        } finally {
                            tmp.delete();
                        }
                    }
                } catch (Exception e) {
                    synchronized (exceptions) { exceptions.add(e); }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent uploads timed out");
        assertTrue(exceptions.isEmpty(), "Concurrent uploads threw exceptions: " + exceptions);

        List<FileMetadata> files = storageService.listFiles(testUser);
        int expectedFiles = threadCount * filesPerThread;
        assertEquals(expectedFiles, files.size(),
            "Expected " + expectedFiles + " files after concurrent uploads, got " + files.size());

        for (FileMetadata meta : files) {
            File dest = File.createTempFile("verify_" + meta.getId(), ".dat");
            try {
                storageService.downloadFile(meta, testUser, masterKey, dest);
                byte[] downloaded = Files.readAllBytes(dest.toPath());
                assertNotNull(downloaded);
                assertTrue(downloaded.length > 0, "Downloaded file " + meta.getId() + " should not be empty");
            } finally {
                dest.delete();
            }
        }

        executor.shutdown();
    }

    @Test
    void concurrentUploadAndDelete_NoRaceConditions() throws Exception {
        int fileCount = 10;
        List<File> tempFiles = new ArrayList<>();
        List<FileMetadata> metadataList = new ArrayList<>();

        for (int i = 0; i < fileCount; i++) {
            File tmp = File.createTempFile("race_" + i, ".txt");
            Files.writeString(tmp.toPath(), "Race condition test data " + i);
            storageService.uploadFile(tmp, testUser, masterKey);
            tempFiles.add(tmp);
        }

        metadataList.addAll(storageService.listFiles(testUser));
        assertEquals(fileCount, metadataList.size());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(fileCount);
        List<Exception> exceptions = new ArrayList<>();

        for (FileMetadata meta : metadataList) {
            executor.submit(() -> {
                try {
                    storageService.deleteFile(meta, testUser);
                } catch (Exception e) {
                    synchronized (exceptions) { exceptions.add(e); }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent deletes timed out");
        assertTrue(exceptions.isEmpty(), "Concurrent deletes threw exceptions: " + exceptions);

        assertTrue(storageService.listFiles(testUser).isEmpty(),
            "All files should be deleted after concurrent deletes");

        for (File f : tempFiles) {
            f.delete();
        }

        executor.shutdown();
    }
}
