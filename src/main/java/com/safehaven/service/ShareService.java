package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.SharedFileEntry;
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
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShareService {
    private static final Logger logger = LoggerFactory.getLogger(ShareService.class);

    /**
     * Shares a file with another user by wrapping the FEK with their RSA public key.
     */
    public void shareFile(FileMetadata meta, String recipientUsername, User owner, SecretKey masterKey) throws Exception {
        if (owner.getUsername().equalsIgnoreCase(recipientUsername)) {
            throw new IllegalArgumentException("Cannot share a file with yourself");
        }

        // 1. Get recipient's public key
        String sqlUser = "SELECT public_key FROM USERS WHERE username = ?";
        byte[] recipientPubKeyEncoded = null;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sqlUser)) {
            pstmt.setString(1, recipientUsername);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    recipientPubKeyEncoded = rs.getBytes("public_key");
                }
            }
        }

        if (recipientPubKeyEncoded == null) {
            throw new IllegalArgumentException("Recipient '" + recipientUsername + "' not found or lacks a public key.");
        }

        PublicKey recipientPubKey = CryptoUtils.decodePublicKey(recipientPubKeyEncoded);

        // 2. Recover the FEK from FileMetadata
        byte[] rawFek = CryptoUtils.decrypt(meta.getEncryptedFek(), masterKey);
        SecretKey fek = new SecretKeySpec(rawFek, "AES");

        // 3. Wrap FEK with recipient's RSA public key
        byte[] wrappedFek = CryptoUtils.wrapKey(fek, recipientPubKey);

        // 4. Save to SHARES table
        String shareId = UUID.randomUUID().toString();
        String sqlShare = "INSERT INTO SHARES (id, file_id, from_user, to_user, wrapped_fek, timestamp) " +
                          "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sqlShare)) {
            pstmt.setString(1, shareId);
            pstmt.setString(2, meta.getId());
            pstmt.setString(3, owner.getUsername());
            pstmt.setString(4, recipientUsername);
            pstmt.setBytes(5, wrappedFek);
            pstmt.setLong(6, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 23505) { // Unique constraint / primary key - unlikely with UUID
                // If we hit a true duplicate logically, we could handle it. 
                // Currently allows sharing multiple times which is fine (overwrite UI will handle).
            }
            throw e;
        }
    }

    /**
     * Lists files that have been shared with the given user.
     */
    public List<SharedFileEntry> listSharedWithMe(User user) {
        List<SharedFileEntry> shares = new ArrayList<>();
        String sql = "SELECT S.id as shareId, S.file_id, F.filename, S.from_user, F.original_size, S.timestamp, S.wrapped_fek " +
                     "FROM SHARES S " +
                     "JOIN FILES F ON S.file_id = F.id " +
                     "WHERE S.to_user = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, user.getUsername());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    shares.add(new SharedFileEntry(
                            rs.getString("shareId"),
                            rs.getString("file_id"),
                            rs.getString("filename"),
                            rs.getString("from_user"),
                            rs.getLong("original_size"),
                            rs.getLong("timestamp"),
                            rs.getBytes("wrapped_fek")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list shared files for user: {}", user.getUsername(), e);
        }
        return shares;
    }

    /**
     * Downloads and decrypts a file shared with the user.
     */
    public void downloadSharedFile(SharedFileEntry entry, PrivateKey recipientPrivateKey, File dest) throws Exception {
        // Source file is in the fromUser's folder
        Path sourcePath = Paths.get(StorageService.STORAGE_DIR, entry.getFromUser(), entry.getFileId() + ".enc");
        if (!Files.exists(sourcePath)) {
            throw new IOException("Shared file not found on disk. The owner might have deleted it.");
        }

        // 1. Unwrap FEK using our RSA private key
        SecretKey fek = CryptoUtils.unwrapKey(entry.getWrappedFek(), recipientPrivateKey);

        // 2. Decrypt & Decompress
        byte[] encryptedData  = Files.readAllBytes(sourcePath);
        byte[] compressedData = CryptoUtils.decrypt(encryptedData, fek);
        byte[] originalData   = CompressionUtils.decompress(compressedData);

        Files.write(dest.toPath(), originalData);
    }
}
