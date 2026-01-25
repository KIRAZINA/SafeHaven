package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
import com.safehaven.utils.CompressionUtils;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class StorageService {
    private static final String STORAGE_DIR = System.getProperty("user.home") + "/.securestorage";

    public StorageService() {
        File dir = new File(STORAGE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public void uploadFile(File source, User user, SecretKey key) throws Exception {
        byte[] fileData = Files.readAllBytes(source.toPath());
        long originalSize = fileData.length;

        // Compress
        byte[] compressedData = CompressionUtils.compress(fileData);

        // Encrypt
        byte[] encryptedData = CryptoUtils.encrypt(compressedData, key);
        
        // Extract IV (first 12 bytes from encryptedData as per CryptoUtils)
        byte[] iv = Arrays.copyOfRange(encryptedData, 0, 12);
        long storedSize = encryptedData.length;

        String fileId = UUID.randomUUID().toString();
        
        // Save to disk
        Path userDir = Paths.get(STORAGE_DIR, user.getUsername());
        if (!Files.exists(userDir)) {
            Files.createDirectories(userDir);
        }
        Path destPath = userDir.resolve(fileId + ".enc");
        Files.write(destPath, encryptedData);

        // Save metadata to DB
        String sql = "INSERT INTO FILES (id, filename, owner, original_size, stored_size, iv, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileId);
            pstmt.setString(2, source.getName());
            pstmt.setString(3, user.getUsername());
            pstmt.setLong(4, originalSize);
            pstmt.setLong(5, storedSize);
            pstmt.setBytes(6, iv);
            pstmt.setLong(7, System.currentTimeMillis());
            pstmt.executeUpdate();
        }
    }

    public void downloadFile(FileMetadata meta, User user, SecretKey key, File dest) throws Exception {
        Path sourcePath = Paths.get(STORAGE_DIR, user.getUsername(), meta.getId() + ".enc");
        if (!Files.exists(sourcePath)) {
            throw new IOException("File not found on disk");
        }

        byte[] encryptedData = Files.readAllBytes(sourcePath);

        // Decrypt
        byte[] compressedData = CryptoUtils.decrypt(encryptedData, key);

        // Decompress
        byte[] originalData = CompressionUtils.decompress(compressedData);

        // Save
        Files.write(dest.toPath(), originalData);
    }

    public List<FileMetadata> listFiles(User user) {
        List<FileMetadata> files = new ArrayList<>();
        String sql = "SELECT * FROM FILES WHERE owner = ?";
        
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
                            rs.getBytes("iv"),
                            rs.getLong("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return files;
    }
    
    public void deleteFile(FileMetadata meta, User user) {
         // Remove from DB
         String sql = "DELETE FROM FILES WHERE id = ? AND owner = ?";
         try (Connection conn = DatabaseManager.getConnection();
              PreparedStatement pstmt = conn.prepareStatement(sql)) {
             pstmt.setString(1, meta.getId());
             pstmt.setString(2, user.getUsername());
             pstmt.executeUpdate();
         } catch (SQLException e) {
             e.printStackTrace();
         }
         
         // Remove from disk
         Path path = Paths.get(STORAGE_DIR, user.getUsername(), meta.getId() + ".enc");
         try {
             Files.deleteIfExists(path);
         } catch (IOException e) {
             e.printStackTrace();
         }
    }
}
