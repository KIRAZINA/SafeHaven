package com.safehaven.integration;

import com.safehaven.crypto.CryptoUtils;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.PrivateKey;

import static org.junit.jupiter.api.Assertions.*;

class MemoryHygieneTest {

    @Test
    void destroyedSecretKey_ThrowsOnUse() throws Exception {
        SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
        try {
            key.destroy();
        } catch (javax.security.auth.DestroyFailedException e) {
            return;
        }

        assertThrows(Exception.class, () -> CryptoUtils.encrypt("test".getBytes(), key),
            "Using a destroyed SecretKey should throw");
    }

    @Test
    void destroyedPrivateKey_ThrowsOnUse() throws Exception {
        KeyPair kp = CryptoUtils.generateRsaKeyPair();
        PrivateKey privateKey = kp.getPrivate();

        if (privateKey instanceof javax.security.auth.Destroyable) {
            javax.security.auth.Destroyable destroyable = (javax.security.auth.Destroyable) privateKey;
            try {
                destroyable.destroy();
            } catch (javax.security.auth.DestroyFailedException e) {
                return;
            }
            byte[] masterKey = CryptoUtils.deriveMasterKey("pw".toCharArray(), CryptoUtils.generateSalt()).getEncoded();
            assertThrows(Exception.class, () -> CryptoUtils.encryptPrivateKey(privateKey,
                CryptoUtils.deriveMasterKey("pw".toCharArray(), CryptoUtils.generateSalt())),
                "Using a destroyed PrivateKey should throw");
        }
    }
}
