package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StorageServiceTest {

    private static final String TEST_DB = "jdbc:h2:mem:test_storage_" +
            System.nanoTime() + ";DB_CLOSE_DELAY=-1";  // Fix #12 — unique DB per class load

    private StorageService storageService;
    private User testUser;
    private SecretKey secretKey;
    private File tempFile;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager.setDbUrl(TEST_DB);
        DatabaseManager.initializeDatabase();

        // Fix #13 — always clean state before each test
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        storageService = new StorageService();

        // Fix #3 — register user via AuthService so testUser.getSalt() matches the DB salt
        String rawPassword = "password";
        new AuthService().register("storage_user", rawPassword);

        // Retrieve the salt that was actually stored
        try (Connection conn = DatabaseManager.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT salt FROM USERS WHERE username = ?")) {
            ps.setString(1, "storage_user");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "User must have been registered");
                byte[] storedSalt = rs.getBytes("salt");
                testUser  = new User("storage_user", new byte[0], storedSalt);
                secretKey = CryptoUtils.generateKeyFromPassword(
                        rawPassword.toCharArray(), storedSalt);
            }
        }

        tempFile = File.createTempFile("testfile", ".txt");
        Files.writeString(tempFile.toPath(), "Secret Content");
    }

    @AfterEach
    void tearDown() throws Exception {
        tempFile.delete();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        Path userDir = Paths.get(System.getProperty("user.home"),
                ".securestorage", "storage_user");
        if (Files.exists(userDir)) {
            try (var stream = Files.walk(userDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .map(Path::toFile)
                      .forEach(File::delete);
            }
        }
    }

    @Test
    void testUploadAndList() throws Exception {
        storageService.uploadFile(tempFile, testUser, secretKey);

        List<FileMetadata> files = storageService.listFiles(testUser);
        assertEquals(1, files.size(), "Should have 1 file listed");
        assertEquals(tempFile.getName(), files.get(0).getFilename(), "Filename should match");
    }

    @Test
    void testDownload() throws Exception {
        storageService.uploadFile(tempFile, testUser, secretKey);
        FileMetadata meta = storageService.listFiles(testUser).get(0);

        File destFile = File.createTempFile("downloaded", ".txt");
        try {
            storageService.downloadFile(meta, testUser, secretKey, destFile);
            String content = Files.readString(destFile.toPath());
            assertEquals("Secret Content", content, "Downloaded content should match original");
        } finally {
            destFile.delete();
        }
    }

    @Test
    void testDelete() throws Exception {
        storageService.uploadFile(tempFile, testUser, secretKey);
        FileMetadata meta = storageService.listFiles(testUser).get(0);

        storageService.deleteFile(meta, testUser);

        List<FileMetadata> files = storageService.listFiles(testUser);
        assertTrue(files.isEmpty(), "File list should be empty after delete");
    }
    
    @Test
    void testUploadFile_LargeSize() throws Exception {
        // Create a 2MB file
        File largeFile = File.createTempFile("largefile", ".dat");
        try {
            byte[] largeData = new byte[2 * 1024 * 1024];
            new java.security.SecureRandom().nextBytes(largeData);
            Files.write(largeFile.toPath(), largeData);

            storageService.uploadFile(largeFile, testUser, secretKey);

            List<FileMetadata> files = storageService.listFiles(testUser);
            assertEquals(1, files.size());
            FileMetadata meta = files.get(0);

            File destFile = File.createTempFile("large_downloaded", ".dat");
            try {
                storageService.downloadFile(meta, testUser, secretKey, destFile);
                byte[] downloadedData = Files.readAllBytes(destFile.toPath());
                assertArrayEquals(largeData, downloadedData, "Large file content should match exactly");
            } finally {
                destFile.delete();
            }
        } finally {
            largeFile.delete();
        }
    }

    @Test
    void testDownloadFile_FileNotFoundOnDisk() throws Exception {
        storageService.uploadFile(tempFile, testUser, secretKey);
        FileMetadata meta = storageService.listFiles(testUser).get(0);

        // Physically delete the file to simulate corruption/loss
        Path storedFile = Paths.get(System.getProperty("user.home"), ".securestorage", 
                testUser.getUsername(), meta.getId() + ".enc");
        Files.deleteIfExists(storedFile);

        File destFile = File.createTempFile("fail", ".txt");
        try {
            assertThrows(IOException.class, () -> {
                storageService.downloadFile(meta, testUser, secretKey, destFile);
            }, "Should throw IOException if physical file is missing");
        } finally {
            destFile.delete();
        }
    }

    @Test
    void testDeleteFile_DeletesFromDiskAndDb() throws Exception {
        storageService.uploadFile(tempFile, testUser, secretKey);
        FileMetadata meta = storageService.listFiles(testUser).get(0);

        Path storedFile = Paths.get(System.getProperty("user.home"), ".securestorage", 
                testUser.getUsername(), meta.getId() + ".enc");
        assertTrue(Files.exists(storedFile), "File should exist on disk before deletion");

        storageService.deleteFile(meta, testUser);

        assertFalse(Files.exists(storedFile), "File should be deleted from disk");
        assertTrue(storageService.listFiles(testUser).isEmpty(), "File should be removed from DB");
    }
}
