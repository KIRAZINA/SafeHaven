package com.safehaven.model;

public class FileMetadata {
    private String id;
    private String filename;
    private String owner;
    private long originalSize;
    private long storedSize;
    private byte[] iv; // Wait, IV is part of the file content in CryptoUtils.encrypt(), but DB stores it too?
    // Reviewing DatabaseManager, it has an IV column.
    // CryptoUtils.encrypt() prepends IV. Ideally we should store IV separately if we want to search or if GCM requires it.
    // However, CryptoUtils.decrypt() expects IV prepended.
    // If we store IV in DB, we might redundant it or use it for reference.
    // Let's check DatabaseManager: `iv BINARY(12) NOT NULL`.
    // So we should store IV in DB.
    
    private long timestamp;

    public FileMetadata(String id, String filename, String owner, long originalSize, long storedSize, byte[] iv, long timestamp) {
        this.id = id;
        this.filename = filename;
        this.owner = owner;
        this.originalSize = originalSize;
        this.storedSize = storedSize;
        this.iv = iv;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getOwner() { return owner; }
    public long getOriginalSize() { return originalSize; }
    public long getStoredSize() { return storedSize; }
    public byte[] getIv() { return iv; }
    public long getTimestamp() { return timestamp; }
}
