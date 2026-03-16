package com.safehaven.model;

/**
 * Represents a single row from the SHARES table joined with file metadata.
 * Used to display and download files shared with the current user.
 */
public class SharedFileEntry {
    private final String shareId;
    private final String fileId;
    private final String filename;
    private final String fromUser;
    private final long   originalSize;
    private final long   timestamp;
    /**
     * The per-file FEK wrapped (encrypted) with THIS user's RSA public key.
     * Decrypt with the user's RSA private key to obtain the raw FEK.
     */
    private final byte[] wrappedFek;

    public SharedFileEntry(String shareId, String fileId, String filename,
                           String fromUser, long originalSize,
                           long timestamp, byte[] wrappedFek) {
        this.shareId     = shareId;
        this.fileId      = fileId;
        this.filename    = filename;
        this.fromUser    = fromUser;
        this.originalSize = originalSize;
        this.timestamp   = timestamp;
        this.wrappedFek  = wrappedFek;
    }

    public String getShareId()      { return shareId; }
    public String getFileId()       { return fileId; }
    public String getFilename()     { return filename; }
    public String getFromUser()     { return fromUser; }
    public long   getOriginalSize() { return originalSize; }
    public long   getTimestamp()    { return timestamp; }
    public byte[] getWrappedFek()   { return wrappedFek; }
}
