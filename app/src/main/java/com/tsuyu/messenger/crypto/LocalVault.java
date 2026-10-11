package com.tsuyu.messenger.crypto;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts locally cached message payloads before they reach the on-device database.
 * The AES key never leaves the Android Keystore and is scoped to one account.
 */
public final class LocalVault {
    private static final String KEY_ALIAS_PREFIX = "com.tsuyu.local-vault.aes.v1.";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;

    private final String keyAlias;

    public LocalVault(String accountUid) {
        this.keyAlias = KEY_ALIAS_PREFIX + shortHash(accountUid == null ? "default" : accountUid);
    }

    public synchronized String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer packed = ByteBuffer.allocate(iv.length + encrypted.length);
            packed.put(iv).put(encrypted);
            return Base64.encodeToString(packed.array(), Base64.NO_WRAP);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encrypt local cache payload", error);
        }
    }

    /** Returns null when the stored blob is missing or belongs to another account key. */
    public synchronized String decrypt(String packedValue) {
        if (packedValue == null || packedValue.isEmpty()) return null;
        try {
            byte[] packed = Base64.decode(packedValue, Base64.NO_WRAP);
            if (packed.length <= IV_LENGTH) return null;
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // A rotated or missing Keystore key simply invalidates the local cache entry.
            return null;
        }
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(keyAlias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) throw new IllegalStateException("Local cache key is missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String shortHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING).substring(0, 22);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
