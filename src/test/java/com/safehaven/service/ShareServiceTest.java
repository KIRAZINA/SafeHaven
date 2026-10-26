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
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShareServiceTest {

    private static final String TEST_DB = "jdbc:h2:mem:test_share_" +
            System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private AuthService authService;
    private StorageService storageService;
    private ShareService shareService;

    // Alice - owner
    private User alice;
    private SecretKey aliceMasterKey;
    // Bob - recipient
    private User bob;
    private SecretKey bobMasterKey;
    private PrivateKey bobPrivateKey;

    private File tempFile;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager.setDbUrl(TEST_DB);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM SHARES");
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        authService = new AuthService();
        storageService = new StorageService();
        shareService = new ShareService();

        // Register Users
        authService.register("alice", "PasswordA1!");
        authService.register("bob", "PasswordB1!");

        // Login to get full loaded user objects including public/private keys
        alice = authService.login("alice", "PasswordA1!");
        aliceMasterKey = CryptoUtils.deriveMasterKey("PasswordA1!".toCharArray(), alice.getSalt());

        bob = authService.login("bob", "PasswordB1!");
        bobMasterKey = CryptoUtils.deriveMasterKey("PasswordB1!".toCharArray(), bob.getSalt());
        bobPrivateKey = CryptoUtils.decryptPrivateKey(bob.getEncryptedPrivateKey(), bobMasterKey);

        tempFile = File.createTempFile("test_share", ".txt");
        Files.writeString(tempFile.toPath(), "Shared Secret Document");
    }

    @AfterEach
    void tearDown() throws Exception {
        tempFile.delete();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM SHARES");
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        deleteUserDir("alice");
        deleteUserDir("bob"); // Bob theoretically has no directory here, but to be sure
    }

    private void deleteUserDir(String username) throws Exception {
        Path userDir = Paths.get(System.getProperty("user.home"), ".securestorage", username);
        if (Files.exists(userDir)) {
            try (var stream = Files.walk(userDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    @Test
    void testShareFile_andDownload_success() throws Exception {
        // 1. Alice uploads file
        storageService.uploadFile(tempFile, alice, aliceMasterKey);
        List<FileMetadata> aliceFiles = storageService.listFiles(alice);
        assertEquals(1, aliceFiles.size());
        FileMetadata fileMeta = aliceFiles.get(0);

        // 2. Alice shares with Bob
        shareService.shareFile(fileMeta, "bob", alice, aliceMasterKey);

        // 3. Bob lists shared files
        List<SharedFileEntry> bobsInbox = shareService.listSharedWithMe(bob);
        assertEquals(1, bobsInbox.size(), "Bob should see 1 shared file");
        SharedFileEntry entry = bobsInbox.get(0);
        assertEquals(tempFile.getName(), entry.getFilename());
        assertEquals("alice", entry.getFromUser());

        // 4. Bob downloads shared file
        File bobDest = File.createTempFile("bob_dl", ".txt");
        try {
            shareService.downloadSharedFile(entry, bobPrivateKey, bobDest);
            String content = Files.readString(bobDest.toPath());
            assertEquals("Shared Secret Document", content, "Document content should match after decryption");
        } finally {
            bobDest.delete();
        }
    }

    @Test
    void testShareFile_withUnknownUser_throwsException() throws Exception {
        storageService.uploadFile(tempFile, alice, aliceMasterKey);
        FileMetadata fileMeta = storageService.listFiles(alice).get(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            shareService.shareFile(fileMeta, "charlie", alice, aliceMasterKey);
        });
        assertTrue(ex.getMessage().contains("not found or lacks a public key"));
    }

    @Test
    void testShareFile_withOneself_throwsException() throws Exception {
        storageService.uploadFile(tempFile, alice, aliceMasterKey);
        FileMetadata fileMeta = storageService.listFiles(alice).get(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            shareService.shareFile(fileMeta, "alice", alice, aliceMasterKey);
        });
        assertEquals("Cannot share a file with yourself", ex.getMessage());
    }
    
    @Test
    void testShareFile_MultipleRecipients() throws Exception {
        // Register and login Charlie
        authService.register("charlie", "PasswordC1!");
        User charlie = authService.login("charlie", "PasswordC1!");
        SecretKey charlieMasterKey = CryptoUtils.deriveMasterKey("PasswordC1!".toCharArray(), charlie.getSalt());
        PrivateKey charliePrivateKey = CryptoUtils.decryptPrivateKey(charlie.getEncryptedPrivateKey(), charlieMasterKey);

        storageService.uploadFile(tempFile, alice, aliceMasterKey);
        FileMetadata fileMeta = storageService.listFiles(alice).get(0);

        // Share with both Bob and Charlie
        shareService.shareFile(fileMeta, "bob", alice, aliceMasterKey);
        shareService.shareFile(fileMeta, "charlie", alice, aliceMasterKey);

        // Verify Bob can download
        SharedFileEntry entryBob = shareService.listSharedWithMe(bob).get(0);
        File bobDest = File.createTempFile("bob_multiple", ".txt");
        try {
            shareService.downloadSharedFile(entryBob, bobPrivateKey, bobDest);
            assertEquals("Shared Secret Document", Files.readString(bobDest.toPath()));
        } finally {
            bobDest.delete();
        }

        // Verify Charlie can download
        SharedFileEntry entryCharlie = shareService.listSharedWithMe(charlie).get(0);
        File charlieDest = File.createTempFile("charlie_multiple", ".txt");
        try {
            shareService.downloadSharedFile(entryCharlie, charliePrivateKey, charlieDest);
            assertEquals("Shared Secret Document", Files.readString(charlieDest.toPath()));
        } finally {
            charlieDest.delete();
        }
        
        deleteUserDir("charlie");
    }

    @Test
    void testDeleteSharedFile_RemovesShares() throws Exception {
        storageService.uploadFile(tempFile, alice, aliceMasterKey);
        FileMetadata fileMeta = storageService.listFiles(alice).get(0);

        shareService.shareFile(fileMeta, "bob", alice, aliceMasterKey);
        assertEquals(1, shareService.listSharedWithMe(bob).size(), "Bob should see the file initially");

        // Alice deletes the file
        storageService.deleteFile(fileMeta, alice);

        // Bob should no longer see it
        assertEquals(0, shareService.listSharedWithMe(bob).size(), "Bob should not see the file after Alice deletes it");
    }

    @Test
    void testDownloadSharedFile_CorruptedGcmTag_DeletesPartialOutput() throws Exception {
        storageService.uploadFile(tempFile, alice, aliceMasterKey);
        FileMetadata fileMeta = storageService.listFiles(alice).get(0);
        shareService.shareFile(fileMeta, "bob", alice, aliceMasterKey);

        SharedFileEntry entry = shareService.listSharedWithMe(bob).get(0);

        Path storedFile = Paths.get(System.getProperty("user.home"), ".securestorage",
                "alice", fileMeta.getId() + ".enc");
        byte[] cipherBytes = Files.readAllBytes(storedFile);
        cipherBytes[cipherBytes.length - 1] ^= 0x01;
        Files.write(storedFile, cipherBytes);

        File bobDest = File.createTempFile("bob_corrupt", ".txt");
        Files.writeString(bobDest.toPath(), "partial output");
        try {
            assertThrows(Exception.class, () ->
                    shareService.downloadSharedFile(entry, bobPrivateKey, bobDest),
                    "A corrupted GCM tag must fail shared-file download");
            assertFalse(Files.exists(bobDest.toPath()),
                    "Partial output must be deleted when GCM verification fails");
        } finally {
            Files.deleteIfExists(bobDest.toPath());
        }
    }

    @Test
    void testDownloadSharedFile_MissingFile() throws Exception {
        storageService.uploadFile(tempFile, alice, aliceMasterKey);
        FileMetadata fileMeta = storageService.listFiles(alice).get(0);
        shareService.shareFile(fileMeta, "bob", alice, aliceMasterKey);

        SharedFileEntry entry = shareService.listSharedWithMe(bob).get(0);

        // Physically delete Alice's file to simulate data loss
        Path storedFile = Paths.get(System.getProperty("user.home"), ".securestorage", 
                "alice", fileMeta.getId() + ".enc");
        Files.deleteIfExists(storedFile);

        File bobDest = File.createTempFile("bob_missing", ".txt");
        try {
            com.safehaven.exception.StorageException ex = assertThrows(
                    com.safehaven.exception.StorageException.class, () -> {
                shareService.downloadSharedFile(entry, bobPrivateKey, bobDest);
            });
            assertTrue(ex.getMessage().contains("Shared file not found on disk"));
        } finally {
            bobDest.delete();
        }
    }

    @Test
    void listSharedWithMePaginated_ReturnsPagesNewestShareFirst() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertFile("shared-file-" + i, "shared-" + i + ".txt", "alice", 1_000L + i);
            insertShare("share-" + i, "shared-file-" + i, "alice", "bob", 2_000L + i);
        }

        assertEquals(List.of("shared-5.txt", "shared-4.txt"),
                sharedFilenames(shareService.listSharedWithMePaginated(bob, 2, 0)),
                "First page must contain the two most recent shares");
        assertEquals(List.of("shared-3.txt", "shared-2.txt"),
                sharedFilenames(shareService.listSharedWithMePaginated(bob, 2, 2)),
                "Offset must skip the first page");
        assertEquals(List.of("shared-1.txt"),
                sharedFilenames(shareService.listSharedWithMePaginated(bob, 2, 4)),
                "Last page may contain fewer than limit rows");
        assertTrue(shareService.listSharedWithMePaginated(bob, 2, 6).isEmpty(),
                "An offset past the end must return an empty page");
    }

    @Test
    void getSharedFileCount_CountsOnlySharesToTheUser() throws Exception {
        insertFile("count-file-1", "one.txt", "alice", 1L);
        insertFile("count-file-2", "two.txt", "alice", 2L);
        insertShare("count-share-1", "count-file-1", "alice", "bob", 10L);
        insertShare("count-share-2", "count-file-2", "alice", "bob", 20L);
        // A share addressed to Alice must not count toward Bob's inbox.
        insertShare("count-share-3", "count-file-1", "bob", "alice", 30L);

        assertEquals(2L, shareService.getSharedFileCount(bob));
        assertEquals(1L, shareService.getSharedFileCount(alice));
        assertEquals(0L, shareService.getSharedFileCount(
                new User("charlie", new byte[0], new byte[16])));
    }

    @Test
    void paginatedSharedSearch_FiltersByFilenameAndSenderAcrossAllPages() throws Exception {
        insertFile("search-file-1", "needle-report.txt", "alice", 1L);
        insertFile("search-file-2", "other.txt", "alice", 2L);
        insertShare("search-share-1", "search-file-1", "alice", "bob", 10L);
        insertShare("search-share-2", "search-file-2", "alice", "bob", 20L);

        assertEquals(1L, shareService.getSharedFileCount(bob, "needle"));
        assertEquals(List.of("needle-report.txt"),
                sharedFilenames(shareService.listSharedWithMePaginated(bob, "NEEDLE", 10, 0)),
                "Filename search must be case-insensitive");
        assertEquals(2L, shareService.getSharedFileCount(bob, "alice"),
                "Search must also match the sender's username");
    }

    @Test
    void listSharedWithMePaginated_RejectsInvalidPageArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> shareService.listSharedWithMePaginated(bob, 0, 0),
                "A non-positive limit must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> shareService.listSharedWithMePaginated(bob, 10, -1),
                "A negative offset must be rejected");
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

    private void insertShare(String id, String fileId, String fromUser, String toUser,
                             long timestamp) throws Exception {
        String sql = "INSERT INTO SHARES (id, file_id, from_user, to_user, wrapped_fek," +
                " timestamp) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, fileId);
            pstmt.setString(3, fromUser);
            pstmt.setString(4, toUser);
            pstmt.setBytes(5, new byte[16]);
            pstmt.setLong(6, timestamp);
            pstmt.executeUpdate();
        }
    }

    private static List<String> sharedFilenames(List<SharedFileEntry> entries) {
        return entries.stream().map(SharedFileEntry::getFilename).toList();
    }
}
