package com.safehaven.service;

import com.safehaven.model.FileMetadata;
import com.safehaven.model.SharedFileEntry;
import com.safehaven.model.User;

import javax.crypto.SecretKey;
import java.io.File;
import java.security.PrivateKey;
import java.util.List;

public interface IShareService {
    void shareFile(FileMetadata meta, String recipientUsername, User owner, SecretKey masterKey) throws Exception;
    List<SharedFileEntry> listSharedWithMe(User user);
    void downloadSharedFile(SharedFileEntry entry, PrivateKey recipientPrivateKey, File dest) throws Exception;
}
