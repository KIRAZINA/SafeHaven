package com.safehaven.model;

public class User {
    private final String username;
    private final byte[] passwordHash;
    private final byte[] salt;
    /** RSA-2048 public key (X.509/SubjectPublicKeyInfo encoded). Stored in plaintext in DB. */
    private final byte[] publicKey;
    /** RSA-2048 private key (PKCS#8), encrypted with this user's AES-GCM master key. */
    private final byte[] encryptedPrivateKey;

    /** Full constructor used after login (all fields populated). */
    public User(String username, byte[] passwordHash, byte[] salt,
                byte[] publicKey, byte[] encryptedPrivateKey) {
        this.username            = username;
        this.passwordHash        = passwordHash;
        this.salt                = salt;
        this.publicKey           = publicKey;
        this.encryptedPrivateKey = encryptedPrivateKey;
    }

    /** Convenience constructor for test stubs that don't need RSA keys. */
    public User(String username, byte[] passwordHash, byte[] salt) {
        this(username, passwordHash, salt, null, null);
    }

    public String getUsername()             { return username; }
    public byte[] getPasswordHash()         { return passwordHash; }
    public byte[] getSalt()                 { return salt; }
    public byte[] getPublicKey()            { return publicKey; }
    public byte[] getEncryptedPrivateKey()  { return encryptedPrivateKey; }
}
