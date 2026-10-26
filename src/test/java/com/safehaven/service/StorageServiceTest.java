package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.SharedFileEntry;
import com.safehaven.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        String rawPassword = "Passw0rd!";
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
                secretKey = CryptoUtils.deriveMasterKey(
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
    void listFilesPaginated_ReturnsPagesNewestFirst() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertFile("page-file-" + i, "report-" + i + ".txt", "storage_user", 1_000L + i);
        }

        assertEquals(List.of("report-5.txt", "report-4.txt"),
                filenames(storageService.listFilesPaginated(testUser, 2, 0)),
                "First page must contain the two newest files");
        assertEquals(List.of("report-3.txt", "report-2.txt"),
                filenames(storageService.listFilesPaginated(testUser, 2, 2)),
                "Offset must skip the first page");
        assertEquals(List.of("report-1.txt"),
                filenames(storageService.listFilesPaginated(testUser, 2, 4)),
                "Last page may contain fewer than limit rows");
        assertTrue(storageService.listFilesPaginated(testUser, 2, 6).isEmpty(),
                "An offset past the end must return an empty page");
    }

    @Test
    void getFileCount_CountsOnlyTheUsersFiles() throws Exception {
        new AuthService().register("other_user", "PasswordO1!");
        insertFile("owned-1", "a.txt", "storage_user", 10L);
        insertFile("owned-2", "b.txt", "storage_user", 20L);
        insertFile("foreign-1", "c.txt", "other_user", 30L);

        assertEquals(2L, storageService.getFileCount(testUser),
                "Count must exclude files owned by other users");
    }

    @Test
    void getFileCount_ReturnsZeroForEmptyList() throws Exception {
        assertEquals(0L, storageService.getFileCount(testUser));
        assertTrue(storageService.listFilesPaginated(testUser, 10, 0).isEmpty());
    }

    @Test
    void paginatedSearch_AppliesToWholeResultSetNotJustCurrentPage() throws Exception {
        // The only match is the oldest row, which is not on the first unfiltered page.
        insertFile("needle-file", "needle-report.txt", "storage_user", 1_000L);
        for (int i = 1; i <= 4; i++) {
            insertFile("other-file-" + i, "other-" + i + ".txt", "storage_user", 2_000L + i);
        }

        assertEquals(1L, storageService.getFileCount(testUser, "needle"));
        assertEquals(List.of("needle-report.txt"),
                filenames(storageService.listFilesPaginated(testUser, "NEEDLE", 2, 0)),
                "Search must match case-insensitively across the whole table");
    }

    @Test
    void paginatedSearch_TreatsSqlWildcardsLiterally() throws Exception {
        insertFile("percent-file", "100%_done.txt", "storage_user", 10L);
        insertFile("plain-file", "100Xdone.txt", "storage_user", 20L);
        insertFile("other-file", "plain.txt", "storage_user", 30L);

        assertEquals(1L, storageService.getFileCount(testUser, "100%"),
                "% must be treated as a literal character");
        assertEquals(List.of("100%_done.txt"),
                filenames(storageService.listFilesPaginated(testUser, "100%_", 10, 0)),
                "_ must be treated as a literal character");
    }

    @Test
    void listFilesPaginated_RejectsInvalidPageArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> storageService.listFilesPaginated(testUser, 0, 0),
                "A non-positive limit must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> storageService.listFilesPaginated(testUser, 10, -1),
                "A negative offset must be rejected");
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
    void testDeleteTransactional_RemovesSharesAndFileAtomically() throws Exception {
        // Register a second user and share the file with them
        AuthService auth = new AuthService();
        auth.register("recipient_user", "PasswordR1!");

        storageService.uploadFile(tempFile, testUser, secretKey);
        FileMetadata meta = storageService.listFiles(testUser).get(0);

        // Share the file
        ShareService shareService = new ShareService();
        shareService.shareFile(meta, "recipient_user", testUser, secretKey);

        // Verify share exists
        assertEquals(1, countSharesForFile(meta.getId()),
                "Share record must exist before deletion");

        // Delete the file
        storageService.deleteFile(meta, testUser);

        // Verify both file and share records are gone
        assertTrue(storageService.listFiles(testUser).isEmpty(),
                "File must be removed from FILES table");
        assertEquals(0, countSharesForFile(meta.getId()),
                "Share record must also be removed (same transaction)");

        // Verify encrypted blob is gone from disk
        Path storedFile = Paths.get(System.getProperty("user.home"),
                ".securestorage", testUser.getUsername(), meta.getId() + ".enc");
        assertFalse(Files.exists(storedFile),
                "Encrypted file must be deleted from disk");
    }

    private int countSharesForFile(String fileId) throws Exception {
        String sql = "SELECT COUNT(*) FROM SHARES WHERE file_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void insertFile(String id, String filename, String owner, long timestamp)
            throws Exception {
        String sql = "INSERT INTO FILES (id, filename, owner, original_size, stored_size," +
                " timestamp, encrypted_fek) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, filename);
            pstmt.setString(3, owner);
            pstmt.setLong(4, 100L);
            pstmt.setLong(5, 100L);
            pstmt.setLong(6, timestamp);
            pstmt.setBytes(7, new byte[16]);
            pstmt.executeUpdate();
        }
    }

    private static List<String> filenames(List<FileMetadata> files) {
        return files.stream().map(FileMetadata::getFilename).toList();
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
            assertThrows(com.safehaven.exception.StorageException.class, () -> {
                storageService.downloadFile(meta, testUser, secretKey, destFile);
            }, "Should throw StorageException if physical file is missing");
        } finally {
            destFile.delete();
        }
    }

    @Test
    void testUploadDownload_ZeroByteFile() throws Exception {
        File zeroFile = File.createTempFile("empty", ".txt");
        try {
            Files.write(zeroFile.toPath(), new byte[0]);
            storageService.uploadFile(zeroFile, testUser, secretKey);
            FileMetadata meta = storageService.listFiles(testUser).get(0);

            File dest = File.createTempFile("empty_dl", ".txt");
            try {
                storageService.downloadFile(meta, testUser, secretKey, dest);
                byte[] downloaded = Files.readAllBytes(dest.toPath());
                assertEquals(0, downloaded.length, "Zero-byte file should download as empty");
            } finally {
                dest.delete();
            }
        } finally {
            zeroFile.delete();
        }
    }

    @Test
    void testUploadDownload_LargeFile10MB() throws Exception {
        int sizeMB = 10;
        byte[] largeData = new byte[sizeMB * 1024 * 1024];
        new java.security.SecureRandom().nextBytes(largeData);

        File largeFile = File.createTempFile("large10MB", ".dat");
        try {
            Files.write(largeFile.toPath(), largeData);

            storageService.uploadFile(largeFile, testUser, secretKey);
            FileMetadata meta = storageService.listFiles(testUser).get(0);

            File dest = File.createTempFile("large10MB_dl", ".dat");
            try {
                storageService.downloadFile(meta, testUser, secretKey, dest);
                byte[] downloaded = Files.readAllBytes(dest.toPath());
                assertArrayEquals(largeData, downloaded, "10MB file content should match exactly");
            } finally {
                dest.delete();
            }
        } finally {
            largeFile.delete();
        }
    }

    @Test
    void testUploadFile_PopulatesStreamedSizes() throws Exception {
        byte[] content = new byte[64 * 1024];
        new java.security.SecureRandom().nextBytes(content);
        File source = File.createTempFile("sized", ".dat");
        try {
            Files.write(source.toPath(), content);
            storageService.uploadFile(source, testUser, secretKey);

            FileMetadata meta = storageService.listFiles(testUser).get(0);
            Path storedFile = Paths.get(System.getProperty("user.home"),
                    ".securestorage", testUser.getUsername(), meta.getId() + ".enc");

            assertEquals(content.length, meta.getOriginalSize(),
                    "original_size must reflect the streamed source length");
            assertEquals(Files.size(storedFile), meta.getStoredSize(),
                    "stored_size must reflect the actual encrypted file on disk");
        } finally {
            source.delete();
        }
    }

    @Test
    void testDownloadFile_CorruptedGcmTag_DeletesPartialOutput() throws Exception {
        storageService.uploadFile(tempFile, testUser, secretKey);
        FileMetadata meta = storageService.listFiles(testUser).get(0);

        Path storedFile = Paths.get(System.getProperty("user.home"),
                ".securestorage", testUser.getUsername(), meta.getId() + ".enc");

        // Corrupt the trailing GCM authentication tag.
        byte[] cipherBytes = Files.readAllBytes(storedFile);
        cipherBytes[cipherBytes.length - 1] ^= 0x01;
        Files.write(storedFile, cipherBytes);

        File dest = File.createTempFile("corrupt_dl", ".txt");
        Files.writeString(dest.toPath(), "pre-existing partial content");
        try {
            assertThrows(Exception.class, () ->
                    storageService.downloadFile(meta, testUser, secretKey, dest),
                    "A corrupted GCM tag must fail the download");
            assertFalse(Files.exists(dest.toPath()),
                    "Partially written destination must be deleted after GCM failure");
        } finally {
            Files.deleteIfExists(dest.toPath());
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
