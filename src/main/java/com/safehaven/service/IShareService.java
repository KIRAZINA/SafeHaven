package com.safehaven.service;

import com.safehaven.model.FileMetadata;
import com.safehaven.model.SharedFileEntry;
import com.safehaven.model.User;

import javax.crypto.SecretKey;
import java.io.File;
import java.security.PrivateKey;
import java.sql.SQLException;
import java.util.List;

public interface IShareService {
    void shareFile(FileMetadata meta, String recipientUsername, User owner, SecretKey masterKey) throws Exception;
    List<SharedFileEntry> listSharedWithMe(User user);
    void downloadSharedFile(SharedFileEntry entry, PrivateKey recipientPrivateKey, File dest) throws Exception;

    /**
     * Returns one page of files shared with the user, newest share first.
     *
     * @param limit  maximum number of rows to return (must be positive)
     * @param offset zero-based row offset into the ordered result set
     */
    List<SharedFileEntry> listSharedWithMePaginated(User user, int limit, int offset)
            throws SQLException;

    /**
     * Paginated variant that filters by filename or sender on the server, so
     * search matches across the whole inbox rather than only the current page.
     * A {@code null} or blank search matches every entry.
     */
    List<SharedFileEntry> listSharedWithMePaginated(User user, String search,
                                                    int limit, int offset) throws SQLException;

    /** Total number of shares addressed to the user. */
    long getSharedFileCount(User user) throws SQLException;

    /** Total number of shares addressed to the user matching the (optional) search. */
    long getSharedFileCount(User user, String search) throws SQLException;
}
