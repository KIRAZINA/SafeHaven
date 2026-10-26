package com.safehaven.integration;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.exception.StorageException;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import com.safehaven.service.ShareService;
import com.safehaven.service.StorageService;
import com.safehaven.utils.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for cross-user file deletion: a non-owner must not be
 * able to remove another user's file, its encrypted blob, or any of its SHARES
 * rows.
 */
class CrossUserShareAuthorizationTest {

    private static final String TEST_DB =
            "jdbc:h2:mem:test_share_authz_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    @TempDir
    static Path tempDir;

    private AuthService authService;
    private StorageService storageService;
    private ShareService shareService;

    private User alice;
    private User bob;
    private SecretKey aliceMk;
    private FileMetadata aliceFile;

    @BeforeAll
    static void configureStorage() {
        System.setProperty(AppConfig.KEY_STORAGE_DIR, tempDir.resolve("storage").toString());
    }

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager.setDbUrl(TEST_DB);
        DatabaseManager.initializeDatabase();
        clearDatabase();
        clearStorage();

        authService = new AuthService();
        storageService = new StorageService();
        shareService = new ShareService();

        authService.register("alice", "PasswordA1!");
        authService.register("bob", "PasswordB1!");

        alice = authService.login("alice", "PasswordA1!");
        aliceMk = CryptoUtils.deriveMasterKey("PasswordA1!".toCharArray(), alice.getSalt());
        bob = authService.login("bob", "PasswordB1!");

        File source = File.createTempFile("alice_share_authz_", ".txt");
        try {
            Files.writeString(source.toPath(), "Alice's private data");
            storageService.uploadFile(source, alice, aliceMk);
        } finally {
            Files.deleteIfExists(source.toPath());
        }
        aliceFile = storageService.listFiles(alice).get(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        clearDatabase();
        clearStorage();
    }

    @Test
    void nonOwnerCannotDeleteFileOrItsShares() throws Exception {
        shareService.shareFile(aliceFile, "bob", alice, aliceMk);
        assertEquals(1, shareService.listSharedWithMe(bob).size(),
                "Precondition: Alice's file is shared with Bob");

        Path blob = blobPath("alice", aliceFile.getId());
        assertTrue(Files.exists(blob), "Precondition: Alice's encrypted blob exists");

        StorageException failure = assertThrows(StorageException.class,
                () -> storageService.deleteFile(aliceFile, bob),
                "A non-owner must not be able to delete the file");
        assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("not authorized"),
                "The failure should describe the authorization problem: " + failure.getMessage());

        assertEquals(1, shareCount(), "SHARES rows must survive a denied delete");
        assertEquals(1, shareService.listSharedWithMe(bob).size(),
                "Bob must still see the file shared with him");
        assertEquals(1, storageService.listFiles(alice).size(),
                "Alice's FILES row must survive a denied delete");
        assertTrue(Files.exists(blob), "Alice's encrypted blob must survive a denied delete");
    }

    @Test
    void forgedMetadataOwnerCannotBypassDatabaseOwnership() throws Exception {
        shareService.shareFile(aliceFile, "bob", alice, aliceMk);

        // A caller can construct arbitrary FileMetadata; ownership must come
        // from the FILES table, not from the object being passed in.
        FileMetadata forged = new FileMetadata(
                aliceFile.getId(),
                aliceFile.getFilename(),
                "bob",
                aliceFile.getOriginalSize(),
                aliceFile.getStoredSize(),
                aliceFile.getTimestamp(),
                aliceFile.getEncryptedFek());

        assertThrows(StorageException.class,
                () -> storageService.deleteFile(forged, bob),
                "Forged metadata must not bypass the database ownership check");

        assertEquals(1, shareCount(), "SHARES must survive a forged-owner delete attempt");
        assertEquals(1, storageService.listFiles(alice).size(),
                "Alice's file must survive a forged-owner delete attempt");
        assertTrue(Files.exists(blobPath("alice", aliceFile.getId())),
                "Alice's encrypted blob must survive a forged-owner delete attempt");
    }

    @Test
    void ownerCanStillDeleteFileAndItsShares() throws Exception {
        shareService.shareFile(aliceFile, "bob", alice, aliceMk);
        Path blob = blobPath("alice", aliceFile.getId());
        assertTrue(Files.exists(blob));

        storageService.deleteFile(aliceFile, alice);

        assertEquals(0, storageService.listFiles(alice).size(),
                "The owner's FILES row should be removed");
        assertEquals(0, shareCount(), "The owner's delete should remove SHARES");
        assertTrue(shareService.listSharedWithMe(bob).isEmpty(),
                "Bob should no longer see the deleted file");
        assertFalse(Files.exists(blob), "The encrypted blob should be removed");
    }

    private static Path blobPath(String username, String fileId) {
        return Paths.get(AppConfig.getStorageDir(), username, fileId + ".enc");
    }

    private static int shareCount() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM SHARES");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void clearDatabase() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM SHARES");
            stmt.execute("DELETE FROM FILES");
            stmt.execute("DELETE FROM USERS");
        }
    }

    private static void clearStorage() throws Exception {
        Path storage = Paths.get(AppConfig.getStorageDir());
        if (!Files.exists(storage)) {
            return;
        }
        try (var paths = Files.walk(storage)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(storage))
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}
