package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.utils.CompressionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public class StorageService implements IStorageService {
    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);
    private static final int BUFFER_SIZE = 8192;
    static final String STORAGE_DIR;

    static {
        Properties props = new Properties();
        String dir = null;
        try (InputStream in = StorageService.class
                .getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
                dir = props.getProperty("storage.dir");
            }
        } catch (IOException e) {
            logger.warn("Could not load application.properties, using default storage dir", e);
        }
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home") + "/.securestorage";
        }
        // Resolve ${user.home} placeholder used in the default property value
        if (dir.contains("${user.home}")) {
            dir = dir.replace("${user.home}", System.getProperty("user.home"));
        }
        STORAGE_DIR = dir;
    }

    public StorageService() {
        File dirFile = new File(STORAGE_DIR);
        if (!dirFile.exists()) {
            if (!dirFile.mkdirs()) {
                throw new RuntimeException(
                        "Cannot create storage directory: " + dirFile.getAbsolutePath());
            }
        }
    }

    public void uploadFile(File source, User user, SecretKey masterKey) throws Exception {
        // Generate per-file logic (FEK)
        SecretKey fek = CryptoUtils.generateFileKey();

        // Encrypt the FEK with the user's master key for storage
        byte[] encryptedFek = CryptoUtils.encrypt(fek.getEncoded(), masterKey);

        String fileId = UUID.randomUUID().toString();

        Path userDir = Paths.get(STORAGE_DIR, user.getUsername());
        Files.createDirectories(userDir);
        Path destPath = userDir.resolve(fileId + ".enc");

        long originalSize;
        long storedSize;

        // Upload pipeline:
        // FileInputStream -> GZIPOutputStream -> CipherOutputStream -> BufferedOutputStream -> File
        try {
            try (InputStream fileIn = new BufferedInputStream(
                         new FileInputStream(source), BUFFER_SIZE);
                 OutputStream fileOut = new BufferedOutputStream(
                         new FileOutputStream(destPath.toFile()), BUFFER_SIZE);
                 CipherOutputStream cipherOut =
                         CryptoUtils.createEncryptingOutputStream(fileOut, fek)) {

                originalSize = CompressionUtils.compressStream(fileIn, cipherOut);
            }
            storedSize = Files.size(destPath);
        } catch (Exception e) {
            deletePartialFile(destPath, e);
            throw e;
        }

        // Save metadata to DB
        String sql = "INSERT INTO FILES (id, filename, owner, original_size, stored_size, timestamp, encrypted_fek)" +
                     " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileId);
            pstmt.setString(2, source.getName());
            pstmt.setString(3, user.getUsername());
            pstmt.setLong(4, originalSize);
            pstmt.setLong(5, storedSize);
            pstmt.setLong(6, System.currentTimeMillis());
            pstmt.setBytes(7, encryptedFek);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            deletePartialFile(destPath, e);
            throw e;
        }
    }

    public void downloadFile(FileMetadata meta, User user, SecretKey masterKey, File dest)
            throws Exception {
        Path sourcePath = Paths.get(STORAGE_DIR, user.getUsername(), meta.getId() + ".enc");
        if (!Files.exists(sourcePath)) {
            throw new IOException("File not found on disk");
        }

        // 1. Recover FEK
        byte[] rawFek = CryptoUtils.decrypt(meta.getEncryptedFek(), masterKey);
        SecretKey fek = new SecretKeySpec(rawFek, "AES");

        // 2. Stream decrypt & decompress directly to the destination
        decryptToFile(sourcePath, fek, dest);
    }

    /**
     * Download pipeline:
     * BufferedInputStream(FileInputStream) -> CipherInputStream -> GZIPInputStream -> FileOutputStream.
     * If any stage fails (including GCM tag verification), the partially
     * written destination file is deleted before the exception propagates.
     */
    static void decryptToFile(Path sourcePath, SecretKey fek, File dest) throws Exception {
        Path destPath = dest.toPath();
        try {
            try (InputStream fileIn = new BufferedInputStream(
                         new FileInputStream(sourcePath.toFile()), BUFFER_SIZE);
                 CipherInputStream cipherIn =
                         CryptoUtils.createDecryptingInputStream(fileIn, fek);
                 OutputStream destOut = new BufferedOutputStream(
                         new FileOutputStream(dest), BUFFER_SIZE)) {

                CompressionUtils.decompressStream(cipherIn, destOut);
            }
        } catch (Exception e) {
            deletePartialFile(destPath, e);
            throw e;
        }
    }

    private static void deletePartialFile(Path path, Exception cause) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
            logger.warn("Could not delete partially written file: {}", path, cleanupFailure);
        }
    }

    public List<FileMetadata> listFiles(User user) {
        List<FileMetadata> files = new ArrayList<>();
        String sql = "SELECT id, filename, owner, original_size, stored_size, timestamp, encrypted_fek" +
                     " FROM FILES WHERE owner = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, user.getUsername());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    files.add(new FileMetadata(
                            rs.getString("id"),
                            rs.getString("filename"),
                            rs.getString("owner"),
                            rs.getLong("original_size"),
                            rs.getLong("stored_size"),
                            rs.getLong("timestamp"),
                            rs.getBytes("encrypted_fek")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list files for user: {}", user.getUsername(), e);
        }
        return files;
    }

    public void deleteFile(FileMetadata meta, User user) {
        try (Connection conn = DatabaseManager.getConnection()) {
            deleteFileWithConnection(meta, user, conn);
        } catch (SQLException e) {
            logger.error("Failed to acquire connection for delete: {}", meta.getId(), e);
            throw new RuntimeException("Failed to delete file record", e);
        }
    }

    /**
     * Test-only seam: performs the transactional delete using a caller-provided
     * Connection so tests can inject spy/mock connections to verify rollback.
     * The caller is responsible for closing the connection.
     */
    public void deleteFileWithConnection(FileMetadata meta, User user, Connection conn) {
        Path path = Paths.get(STORAGE_DIR, user.getUsername(), meta.getId() + ".enc");
        try {
            conn.setAutoCommit(false);

            // Delete DB rows FIRST so that a crash between DB and disk
            // leaves an orphaned blob (recoverable) rather than DB rows
            // pointing to a missing file (unrecoverable data loss).

            // 1. Delete from SHARES first to avoid FK constraint violation
            String sqlShares = "DELETE FROM SHARES WHERE file_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sqlShares)) {
                pstmt.setString(1, meta.getId());
                pstmt.executeUpdate();
            }

            // 2. Delete from FILES
            String sqlFiles = "DELETE FROM FILES WHERE id = ? AND owner = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sqlFiles)) {
                pstmt.setString(1, meta.getId());
                pstmt.setString(2, user.getUsername());
                pstmt.executeUpdate();
            }

            // 3. Delete the physical encrypted blob
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // If disk deletion fails, rollback the DB changes so the
                // file record remains intact.
                conn.rollback();
                logger.error("Could not delete file from disk: {}", path, e);
                throw new RuntimeException("Failed to delete file from disk", e);
            }

            conn.commit();
        } catch (SQLException e) {
            logger.error("Failed to delete file record from DB: {}", meta.getId(), e);
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("Failed to delete file record", e);
        }
    }
}
