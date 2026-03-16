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
        authService.register("alice", "passwordA");
        authService.register("bob", "passwordB");

        // Login to get full loaded user objects including public/private keys
        alice = authService.login("alice", "passwordA");
        aliceMasterKey = CryptoUtils.generateKeyFromPassword("passwordA".toCharArray(), alice.getSalt());

        bob = authService.login("bob", "passwordB");
        bobMasterKey = CryptoUtils.generateKeyFromPassword("passwordB".toCharArray(), bob.getSalt());
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
        authService.register("charlie", "passwordC");
        User charlie = authService.login("charlie", "passwordC");
        SecretKey charlieMasterKey = CryptoUtils.generateKeyFromPassword("passwordC".toCharArray(), charlie.getSalt());
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
            java.io.IOException ex = assertThrows(java.io.IOException.class, () -> {
                shareService.downloadSharedFile(entry, bobPrivateKey, bobDest);
            });
            assertTrue(ex.getMessage().contains("Shared file not found on disk"));
        } finally {
            bobDest.delete();
        }
    }
}
