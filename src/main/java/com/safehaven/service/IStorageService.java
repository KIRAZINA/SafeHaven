package com.safehaven.service;

import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;

import javax.crypto.SecretKey;
import java.io.File;
import java.sql.SQLException;
import java.util.List;

public interface IStorageService {
    void uploadFile(File source, User user, SecretKey masterKey) throws Exception;
    void downloadFile(FileMetadata meta, User user, SecretKey masterKey, File dest) throws Exception;
    List<FileMetadata> listFiles(User user);
    void deleteFile(FileMetadata meta, User user);

    /**
     * Returns one page of the user's files, newest first.
     *
     * @param limit  maximum number of rows to return (must be positive)
     * @param offset zero-based row offset into the ordered result set
     */
    List<FileMetadata> listFilesPaginated(User user, int limit, int offset) throws SQLException;

    /**
     * Paginated variant that filters by filename on the server, so search
     * results are drawn from the whole table rather than the current page.
     * A {@code null} or blank search matches every file.
     */
    List<FileMetadata> listFilesPaginated(User user, String search, int limit, int offset)
            throws SQLException;

    /** Total number of files owned by the user. */
    long getFileCount(User user) throws SQLException;

    /** Total number of the user's files matching the (optional) filename search. */
    long getFileCount(User user, String search) throws SQLException;
}
