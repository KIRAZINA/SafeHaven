package com.safehaven.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    @Test
    void user_ConstructorAndGetters() {
        byte[] hash = new byte[32];
        byte[] salt = new byte[16];
        byte[] pubKey = new byte[]{1, 2, 3};
        byte[] encPrivKey = new byte[]{4, 5, 6};
        User user = new User("alice", hash, salt, pubKey, encPrivKey);

        assertEquals("alice", user.getUsername());
        assertSame(hash, user.getPasswordHash());
        assertSame(salt, user.getSalt());
        assertSame(pubKey, user.getPublicKey());
        assertSame(encPrivKey, user.getEncryptedPrivateKey());
    }

    @Test
    void user_ConvenienceConstructor_NullKeys() {
        byte[] hash = new byte[32];
        byte[] salt = new byte[16];
        User user = new User("bob", hash, salt);

        assertEquals("bob", user.getUsername());
        assertSame(hash, user.getPasswordHash());
        assertSame(salt, user.getSalt());
        assertNull(user.getPublicKey());
        assertNull(user.getEncryptedPrivateKey());
    }

    @Test
    void fileMetadata_ConstructorAndGetters() {
        byte[] fek = new byte[]{10, 20, 30};
        FileMetadata meta = new FileMetadata("id-1", "doc.txt", "alice",
                1024L, 512L, 9999L, fek);

        assertEquals("id-1", meta.getId());
        assertEquals("doc.txt", meta.getFilename());
        assertEquals("alice", meta.getOwner());
        assertEquals(1024L, meta.getOriginalSize());
        assertEquals(512L, meta.getStoredSize());
        assertEquals(9999L, meta.getTimestamp());
        assertSame(fek, meta.getEncryptedFek());
    }

    @Test
    void sharedFileEntry_ConstructorAndGetters() {
        byte[] wrappedFek = new byte[]{50, 60, 70};
        SharedFileEntry entry = new SharedFileEntry("sid-1", "fid-1", "doc.txt",
                "alice", 2048L, 8888L, wrappedFek);

        assertEquals("sid-1", entry.getShareId());
        assertEquals("fid-1", entry.getFileId());
        assertEquals("doc.txt", entry.getFilename());
        assertEquals("alice", entry.getFromUser());
        assertEquals(2048L, entry.getOriginalSize());
        assertEquals(8888L, entry.getTimestamp());
        assertSame(wrappedFek, entry.getWrappedFek());
    }
}
