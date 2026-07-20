package com.safehaven.service;

import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;

import javax.crypto.SecretKey;
import java.io.File;
import java.util.List;

public interface IStorageService {
    void uploadFile(File source, User user, SecretKey masterKey) throws Exception;
    void downloadFile(FileMetadata meta, User user, SecretKey masterKey, File dest) throws Exception;
    List<FileMetadata> listFiles(User user);
    void deleteFile(FileMetadata meta, User user);
}
