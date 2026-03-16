package com.safehaven.model;

public class FileMetadata {
    private final String id;
    private final String filename;
    private final String owner;
    private final long   originalSize;
    private final long   storedSize;
    private final long   timestamp;
    /**
     * The per-file AES-256 FEK (File Encryption Key), wrapped with the owner's
     * AES-GCM master key and stored in the FILES.encrypted_fek column.
     * Needed to share the file: decrypt FEK → re-wrap with recipient's RSA key.
     */
    private final byte[] encryptedFek;

    public FileMetadata(String id, String filename, String owner,
                        long originalSize, long storedSize,
                        long timestamp, byte[] encryptedFek) {
        this.id           = id;
        this.filename     = filename;
        this.owner        = owner;
        this.originalSize = originalSize;
        this.storedSize   = storedSize;
        this.timestamp    = timestamp;
        this.encryptedFek = encryptedFek;
    }

    public String getId()           { return id; }
    public String getFilename()     { return filename; }
    public String getOwner()        { return owner; }
    public long   getOriginalSize() { return originalSize; }
    public long   getStoredSize()   { return storedSize; }
    public long   getTimestamp()    { return timestamp; }
    public byte[] getEncryptedFek() { return encryptedFek; }
}
