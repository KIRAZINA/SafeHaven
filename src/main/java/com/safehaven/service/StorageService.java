package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.utils.CompressionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StorageService {
    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);
    static final String STORAGE_DIR =
            System.getProperty("user.home") + "/.securestorage";

    public StorageService() {
        File dir = new File(STORAGE_DIR);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new RuntimeException(
                        "Cannot create storage directory: " + dir.getAbsolutePath());
            }
        }
    }

    public void uploadFile(File source, User user, SecretKey masterKey) throws Exception {
        byte[] fileData = Files.readAllBytes(source.toPath());
        long originalSize = fileData.length;

        // Generate per-file logic (FEK)
        SecretKey fek = CryptoUtils.generateFileKey();
        
        // Compress then encrypt with FEK (not master key)
        byte[] compressedData = CompressionUtils.compress(fileData);
        byte[] encryptedData  = CryptoUtils.encrypt(compressedData, fek);
        long storedSize = encryptedData.length;

        // Encrypt the FEK with the user's master key for storage
        byte[] encryptedFek = CryptoUtils.encrypt(fek.getEncoded(), masterKey);

        String fileId = UUID.randomUUID().toString();

        // Save to disk FIRST
        Path userDir = Paths.get(STORAGE_DIR, user.getUsername());
        if (!Files.exists(userDir)) {
            Files.createDirectories(userDir);
        }
        Path destPath = userDir.resolve(fileId + ".enc");
        Files.write(destPath, encryptedData);

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
            Files.deleteIfExists(destPath);
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

        // 2. Decrypt & Decompress
        byte[] encryptedData  = Files.readAllBytes(sourcePath);
        byte[] compressedData = CryptoUtils.decrypt(encryptedData, fek);
        byte[] originalData   = CompressionUtils.decompress(compressedData);

        Files.write(dest.toPath(), originalData);
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
        Path path = Paths.get(STORAGE_DIR, user.getUsername(), meta.getId() + ".enc");
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.error("Could not delete file from disk: {}", path, e);
            throw new RuntimeException("Failed to delete file from disk", e);
        }

        // Delete from SHARES first to avoid constraint violation
        String sqlShares = "DELETE FROM SHARES WHERE file_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sqlShares)) {
            pstmt.setString(1, meta.getId());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove shares from DB: {}", meta.getId(), e);
        }

        String sql = "DELETE FROM FILES WHERE id = ? AND owner = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, meta.getId());
            pstmt.setString(2, user.getUsername());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove file record from DB: {}", meta.getId(), e);
            throw new RuntimeException("Failed to delete file record", e);
        }
    }
}
