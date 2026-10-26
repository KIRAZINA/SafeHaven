package com.safehaven.service;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.db.DatabaseManager;
import com.safehaven.exception.StorageException;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.SharedFileEntry;
import com.safehaven.model.User;
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
import java.util.Locale;
import java.util.UUID;

public class ShareService implements IShareService {
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
            throw new StorageException("Could not save the shared file.", e);
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
            logger.error("Failed to list shared files for user {}: {}",
                    user.getUsername(), e.getClass().getSimpleName());
            throw new StorageException("Could not load shared files.", e);
        }
        return shares;
    }

    @Override
    public List<SharedFileEntry> listSharedWithMePaginated(User user, int limit, int offset)
            throws SQLException {
        return listSharedWithMePaginated(user, null, limit, offset);
    }

    @Override
    public List<SharedFileEntry> listSharedWithMePaginated(User user, String search,
                                                           int limit, int offset)
            throws SQLException {
        validatePageArguments(limit, offset);

        boolean filtered = search != null && !search.isBlank();
        StringBuilder sql = new StringBuilder(
                "SELECT S.id as shareId, S.file_id, F.filename, S.from_user, F.original_size," +
                " S.timestamp, S.wrapped_fek " +
                "FROM SHARES S " +
                "JOIN FILES F ON S.file_id = F.id " +
                "WHERE S.to_user = ?");
        if (filtered) {
            sql.append(" AND (LOWER(F.filename) LIKE ? ESCAPE '\\'")
               .append(" OR LOWER(S.from_user) LIKE ? ESCAPE '\\')");
        }
        sql.append(" ORDER BY S.timestamp DESC, S.id DESC LIMIT ? OFFSET ?");

        List<SharedFileEntry> shares = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int index = 1;
            pstmt.setString(index++, user.getUsername());
            if (filtered) {
                String pattern = likePattern(search);
                pstmt.setString(index++, pattern);
                pstmt.setString(index++, pattern);
            }
            pstmt.setInt(index++, limit);
            pstmt.setInt(index, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    shares.add(mapShare(rs));
                }
            }
        }
        return shares;
    }

    @Override
    public long getSharedFileCount(User user) throws SQLException {
        return getSharedFileCount(user, null);
    }

    @Override
    public long getSharedFileCount(User user, String search) throws SQLException {
        boolean filtered = search != null && !search.isBlank();
        String sql = "SELECT COUNT(*) FROM SHARES S"
                + (filtered ? " JOIN FILES F ON S.file_id = F.id" : "")
                + " WHERE S.to_user = ?"
                + (filtered ? " AND (LOWER(F.filename) LIKE ? ESCAPE '\\'"
                        + " OR LOWER(S.from_user) LIKE ? ESCAPE '\\')" : "");

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int index = 1;
            pstmt.setString(index++, user.getUsername());
            if (filtered) {
                String pattern = likePattern(search);
                pstmt.setString(index++, pattern);
                pstmt.setString(index++, pattern);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static SharedFileEntry mapShare(ResultSet rs) throws SQLException {
        return new SharedFileEntry(
                rs.getString("shareId"),
                rs.getString("file_id"),
                rs.getString("filename"),
                rs.getString("from_user"),
                rs.getLong("original_size"),
                rs.getLong("timestamp"),
                rs.getBytes("wrapped_fek")
        );
    }

    private static void validatePageArguments(int limit, int offset) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    /** Case-insensitive LIKE pattern with SQL wildcards escaped. */
    private static String likePattern(String search) {
        String term = search.trim().toLowerCase(Locale.ROOT);
        StringBuilder pattern = new StringBuilder(term.length() + 2).append('%');
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                pattern.append('\\');
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }

    /**
     * Downloads and decrypts a file shared with the user.
     */
    public void downloadSharedFile(SharedFileEntry entry, PrivateKey recipientPrivateKey, File dest) throws Exception {
        // Source file is in the fromUser's folder
        Path sourcePath = Paths.get(StorageService.STORAGE_DIR, entry.getFromUser(), entry.getFileId() + ".enc");
        if (!Files.exists(sourcePath)) {
            throw new StorageException(
                    "Shared file not found on disk. The owner might have deleted it.");
        }

        // 1. Unwrap FEK using our RSA private key
        SecretKey fek = CryptoUtils.unwrapKey(entry.getWrappedFek(), recipientPrivateKey);

        // 2. Stream decrypt & decompress directly to the destination.
        //    Partial output is removed if decryption/GCM verification fails.
        StorageService.decryptToFile(sourcePath, fek, dest);
    }
}
