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
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StorageServiceTest {

    private StorageService storageService;
    private User testUser;
    private SecretKey secretKey;
    private File tempFile;

    @BeforeEach
    void setUp() throws IOException {
        DatabaseManager.setDbUrl("jdbc:h2:mem:test_storage;DB_CLOSE_DELAY=-1");
        DatabaseManager.initializeDatabase();
        storageService = new StorageService();

        testUser = new User("storage_user", new byte[0], CryptoUtils.generateSalt());
        secretKey = CryptoUtils.generateKeyFromPassword("password".toCharArray(), testUser.getSalt());
        
        // Insert user into DB to satisfy foreign key constraint
        new AuthService().register("storage_user", "password");

        tempFile = File.createTempFile("testfile", ".txt");
        Files.writeString(tempFile.toPath(), "Secret Content");
    }

    @AfterEach
    void tearDown() throws SQLException, IOException {
        tempFile.delete();
        
        // Clean up DB
        try (java.sql.Connection conn = DatabaseManager.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }
        
        // Clean up disk storage
        Path userDir = Paths.get(System.getProperty("user.home"), ".securestorage", "storage_user");
        if (Files.exists(userDir)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(userDir)) {
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
        storageService.downloadFile(meta, testUser, secretKey, destFile);

        String content = Files.readString(destFile.toPath());
        assertEquals("Secret Content", content, "Downloaded content should match original");
        
        destFile.delete();
    }

    @Test
    void testDelete() throws Exception {
        storageService.uploadFile(tempFile, testUser, secretKey);
        FileMetadata meta = storageService.listFiles(testUser).get(0);

        storageService.deleteFile(meta, testUser);

        List<FileMetadata> files = storageService.listFiles(testUser);
        assertTrue(files.isEmpty(), "File list should be empty after delete");
    }
}
