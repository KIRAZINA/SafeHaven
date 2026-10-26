package com.safehaven.security;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.service.AuthService;
import com.safehaven.service.ShareService;
import com.safehaven.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class SecurityRegressionTest {

    private static final String TEST_DB = "jdbc:h2:mem:test_sec_" +
            System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private AuthService authService;
    private StorageService storageService;
    private ShareService shareService;

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
    }

    // ─────────────────────────────────────────────────────────────────
    // P0-1: password_hash is NOT the master key
    // ─────────────────────────────────────────────────────────────────

    @Test
    void storedPasswordHash_CannotDecryptPrivateKey() throws Exception {
        authService.register("alice", "Secret123!");

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT password_hash, salt, encrypted_private_key FROM USERS WHERE username = ?")) {
            ps.setString(1, "alice");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                byte[] passwordHash = rs.getBytes("password_hash");
                byte[] salt = rs.getBytes("salt");
                byte[] encPrivKey = rs.getBytes("encrypted_private_key");

                SecretKey forgedKey = new SecretKeySpec(passwordHash, "AES");
                assertThrows(Exception.class,
                        () -> CryptoUtils.decryptPrivateKey(encPrivKey, forgedKey),
                        "password_hash must not be usable as a decryption key");

                SecretKey realMasterKey = CryptoUtils.deriveMasterKey("Secret123!".toCharArray(), salt);
                assertThat(CryptoUtils.decryptPrivateKey(encPrivKey, realMasterKey)).isNotNull();
            }
        }
    }

    @Test
    void storedPasswordHash_CannotDecryptFileFek() throws Exception {
        authService.register("alice", "Secret123!");
        User alice = authService.login("alice", "Secret123!");
        SecretKey masterKey = CryptoUtils.deriveMasterKey("Secret123!".toCharArray(), alice.getSalt());

        File tmp = File.createTempFile("sec_test", ".txt");
        try {
            Files.writeString(tmp.toPath(), "sensitive data");
            storageService.uploadFile(tmp, alice, masterKey);
            FileMetadata meta = storageService.listFiles(alice).get(0);

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT password_hash FROM USERS WHERE username = ?")) {
                ps.setString(1, "alice");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    byte[] passwordHash = rs.getBytes("password_hash");

                    SecretKey forgedKey = new SecretKeySpec(passwordHash, "AES");
                    assertThrows(Exception.class,
                            () -> CryptoUtils.decrypt(meta.getEncryptedFek(), forgedKey),
                            "password_hash must not decrypt a file's encrypted FEK");
                }
            }

            byte[] rawFek = CryptoUtils.decrypt(meta.getEncryptedFek(), masterKey);
            assertThat(rawFek).isNotNull().hasSize(32);
        } finally {
            tmp.delete();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // P0-1: Same password => different hash/master key
    // ─────────────────────────────────────────────────────────────────

    @Test
    void samePassword_DifferentHashAndMasterKey() throws Exception {
        authService.register("userA", "Samepass1!");
        authService.register("userB", "Samepass1!");

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT username, password_hash, salt FROM USERS ORDER BY username")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                byte[] hashA = rs.getBytes("password_hash");
                byte[] saltA = rs.getBytes("salt");

                assertThat(rs.next()).isTrue();
                byte[] hashB = rs.getBytes("password_hash");
                byte[] saltB = rs.getBytes("salt");

                assertThat(saltA).isNotEqualTo(saltB);
                assertThat(hashA).isNotEqualTo(hashB);

                SecretKey mkA = CryptoUtils.deriveMasterKey("Samepass1!".toCharArray(), saltA);
                SecretKey mkB = CryptoUtils.deriveMasterKey("Samepass1!".toCharArray(), saltB);
                assertThat(mkA.getEncoded()).isNotEqualTo(mkB.getEncoded());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // P1-3: Atomic deletion using dynamic proxy to simulate partial
    //       failure in the second DELETE statement
    // ─────────────────────────────────────────────────────────────────

    @Test
    void deleteFile_RollsBackOnPartialFailure() throws Exception {
        authService.register("alice", "Passw0rd!");
        authService.register("bob", "Passw0rd!");
        User alice = authService.login("alice", "Passw0rd!");
        SecretKey aliceMk = CryptoUtils.deriveMasterKey("Passw0rd!".toCharArray(), alice.getSalt());

        File tmp = File.createTempFile("atomic", ".txt");
        try {
            Files.writeString(tmp.toPath(), "atomic data");
            storageService.uploadFile(tmp, alice, aliceMk);
            FileMetadata meta = storageService.listFiles(alice).get(0);
            shareService.shareFile(meta, "bob", alice, aliceMk);

            assertThat(shareService.listSharedWithMe(authService.login("bob", "Passw0rd!"))).hasSize(1);

            Connection realConn = DatabaseManager.getConnection();
            Connection proxyConn = buildFailingConnection(realConn);

            try {
                storageService.deleteFileWithConnection(meta, alice, proxyConn);
                fail("Expected RuntimeException");
            } catch (RuntimeException e) {
                assertThat(e.getMessage()).contains("Failed to delete file record");
            }

            // After rollback, SHARES must still exist
            assertThat(shareService.listSharedWithMe(authService.login("bob", "Passw0rd!")))
                    .as("SHARES row must survive rollback after failed FILES delete")
                    .hasSize(1);

            // FILES must also still exist
            assertThat(storageService.listFiles(alice))
                    .as("FILES row must survive rollback after failed FILES delete")
                    .hasSize(1);

            realConn.close();
        } finally {
            tmp.delete();
        }
    }

    /** Creates a Connection proxy that lets the first {@code prepareStatement}
     *  through normally, but wraps the second PreparedStatement so its
     *  {@code executeUpdate()} throws {@code SQLException}. */
    private static Connection buildFailingConnection(Connection real) {
        final int[] callCount = {0};
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        callCount[0]++;
                        PreparedStatement realPs = (PreparedStatement)
                                method.invoke(real, args);
                        if (callCount[0] == 2) {
                            return Proxy.newProxyInstance(
                                    PreparedStatement.class.getClassLoader(),
                                    new Class[]{PreparedStatement.class},
                                    (psProxy, psMethod, psArgs) -> {
                                        if (psMethod.getName().equals("executeUpdate")) {
                                            throw new SQLException("Simulated failure");
                                        }
                                        return psMethod.invoke(realPs, psArgs);
                                    });
                        }
                        return realPs;
                    }
                    return method.invoke(real, args);
                });
    }
}
