package com.noir.p2pchat.core;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM field encryption for local chat text and filenames, with a non-exportable Keystore key. */
final class MessageContentCipher {
    private static final String KEY_ALIAS = "noir_p2p_local_messages_v1";
    private volatile SecretKey key;

    String encrypt(String messageId, String field, String value) {
        if (value == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            cipher.updateAAD(aad(messageId, field));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP | Base64.NO_PADDING)
                    + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt local chat content", e);
        }
    }

    String decrypt(String messageId, String field, String value) {
        if (value == null || !value.startsWith("v1:")) return value;
        try {
            int separator = value.indexOf(':', 3);
            if (separator < 0 || separator == value.length() - 1) throw new IllegalArgumentException("Invalid local envelope");
            byte[] iv = Base64.decode(value.substring(3, separator), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(messageId, field));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not authenticate local chat content", e);
        }
    }

    private byte[] aad(String messageId, String field) {
        return (messageId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }

    private synchronized SecretKey getKey() throws Exception {
        if (key != null) return key;
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            return key;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        key = generator.generateKey();
        return key;
    }
}
