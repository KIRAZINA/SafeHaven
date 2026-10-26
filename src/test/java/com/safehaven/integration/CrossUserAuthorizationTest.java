package com.safehaven.integration;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import com.safehaven.service.ShareService;
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
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class CrossUserAuthorizationTest {

    private static final String TEST_DB = "jdbc:h2:mem:test_authz_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private AuthService authService;
    private StorageService storageService;
    private ShareService shareService;

    private User alice;
    private SecretKey aliceMk;
    private User bob;
    private SecretKey bobMk;
    private FileMetadata aliceFileMeta;

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

        authService.register("alice", "PasswordA1!");
        authService.register("bob", "PasswordB1!");

        alice = authService.login("alice", "PasswordA1!");
        aliceMk = CryptoUtils.deriveMasterKey("PasswordA1!".toCharArray(), alice.getSalt());

        bob = authService.login("bob", "PasswordB1!");
        bobMk = CryptoUtils.deriveMasterKey("PasswordB1!".toCharArray(), bob.getSalt());

        File tmp = File.createTempFile("alice_file", ".txt");
        try {
            Files.writeString(tmp.toPath(), "Alice's secret data");
            storageService.uploadFile(tmp, alice, aliceMk);
        } finally {
            tmp.delete();
        }

        aliceFileMeta = storageService.listFiles(alice).get(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM SHARES");
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }

        for (String user : new String[]{"alice", "bob"}) {
            Path dir = Paths.get(System.getProperty("user.home"), ".securestorage", user);
            if (Files.exists(dir)) {
                Files.walk(dir)
                     .sorted(Comparator.reverseOrder())
                     .map(Path::toFile)
                     .forEach(File::delete);
            }
        }
    }

    @Test
    void userA_CannotDownloadUserB_File() throws Exception {
        File bobDest = File.createTempFile("bob_dl", ".txt");
        try {
            assertThrows(Exception.class, () ->
                storageService.downloadFile(aliceFileMeta, bob, bobMk, bobDest),
                "User B must not be able to download User A's file even with valid FileMetadata");
        } finally {
            bobDest.delete();
        }
    }

    @Test
    void userA_CannotDeleteUserB_File() throws Exception {
        storageService.deleteFile(aliceFileMeta, bob);

        assertFalse(storageService.listFiles(alice).isEmpty(),
            "User A's file must still exist after User B attempts to delete it");
        assertTrue(storageService.listFiles(bob).isEmpty(),
            "User B should have no files to list");
    }

    @Test
    void sharedFile_NotAccessibleByUserC() throws Exception {
        shareService.shareFile(aliceFileMeta, "bob", alice, aliceMk);

        authService.register("charlie", "PasswordC1!");
        User charlie = authService.login("charlie", "PasswordC1!");
        SecretKey charlieMk = CryptoUtils.deriveMasterKey("PasswordC1!".toCharArray(), charlie.getSalt());

        assertTrue(shareService.listSharedWithMe(charlie).isEmpty(),
            "User C must not see files shared with User B");
    }

    @Test
    void sharedFile_DownloadWithWrongUserFails() throws Exception {
        shareService.shareFile(aliceFileMeta, "bob", alice, aliceMk);

        var bobEntry = shareService.listSharedWithMe(bob).get(0);

        authService.register("charlie", "PasswordC1!");
        User charlie = authService.login("charlie", "PasswordC1!");
        SecretKey charlieMk = CryptoUtils.deriveMasterKey("PasswordC1!".toCharArray(), charlie.getSalt());
        var charliePrivKey = CryptoUtils.decryptPrivateKey(charlie.getEncryptedPrivateKey(), charlieMk);

        File dest = File.createTempFile("charlie_dl", ".txt");
        try {
            assertThrows(Exception.class, () ->
                shareService.downloadSharedFile(bobEntry, charliePrivKey, dest),
                "User C must not be able to download a file shared with User B using their own private key");
        } finally {
            dest.delete();
        }
    }
}
