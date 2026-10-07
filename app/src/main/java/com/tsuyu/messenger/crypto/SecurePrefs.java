package com.tsuyu.messenger.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts protocol state before it reaches app preferences. The AES key is non-exportable. */
final class SecurePrefs {
    private static final String PREFS = "signal_state";
    private static final String KEY_ALIAS_PREFIX = "com.tsuyu.signal-state.aes.v1.";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private final SharedPreferences preferences;
    private final String keyAlias;
    private final File largeFileDir;

    SecurePrefs(Context context, String accountUid) {
        String namespace = shortHash(accountUid);
        Context appContext = context.getApplicationContext();
        keyAlias = KEY_ALIAS_PREFIX + namespace;
        preferences = appContext.getSharedPreferences(PREFS + "_" + namespace, Context.MODE_PRIVATE);
        largeFileDir = new File(appContext.getNoBackupFilesDir(), "tsuyu-message-cache-" + namespace);
    }

    private static String shortHash(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING).substring(0, 22);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized String get(String name) {
        String encoded = preferences.getString(name, null);
        if (encoded == null) return null;
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length < 13) throw new GeneralSecurityException("Invalid encrypted state");
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(cipherText), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt local Signal state; refusing to reset it silently", e);
        }
    }

    synchronized void put(String name, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer packed = ByteBuffer.allocate(iv.length + ciphertext.length);
            packed.put(iv).put(ciphertext);
            boolean saved = preferences.edit()
                    .putString(name, Base64.encodeToString(packed.array(), Base64.NO_WRAP))
                    .commit();
            if (!saved) throw new IllegalStateException("Could not durably save protocol state");
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt local Signal state", e);
        }
    }

    synchronized void putLarge(String name, String value) {
        try {
            if (!largeFileDir.exists() && !largeFileDir.mkdirs()) throw new IOException("Could not create cache directory");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File target = cacheFile(name);
            File temporary = new File(largeFileDir, target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(iv); out.write(ciphertext); out.getFD().sync(); }
            if (target.exists() && !target.delete()) throw new IOException("Could not replace cache entry");
            if (!temporary.renameTo(target)) throw new IOException("Could not commit cache entry");
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt large local message", e);
        }
    }

    synchronized String getLarge(String name) {
        File file = cacheFile(name);
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024]; int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            byte[] packed = out.toByteArray();
            if (packed.length < 13) throw new GeneralSecurityException("Invalid encrypted cache entry");
            ByteBuffer data = ByteBuffer.wrap(packed);
            byte[] iv = new byte[12]; data.get(iv);
            byte[] ciphertext = new byte[data.remaining()]; data.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt large local message cache", e);
        }
    }

    synchronized void removeLarge(String name) {
        File file = cacheFile(name);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not remove local message cache");
    }

    private File cacheFile(String name) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return new File(largeFileDir, hex + ".bin");
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    synchronized void remove(String name) {
        if (!preferences.edit().remove(name).commit()) {
            throw new IllegalStateException("Could not durably remove protocol state");
        }
        removeLarge(name);
    }

    synchronized List<String> keysWithPrefix(String prefix) {
        List<String> names = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) names.add(key);
        }
        return names;
    }

    synchronized void wipeForExplicitKeyRotation() {
        try {
            preferences.edit().clear().commit();
            File[] cached = largeFileDir.listFiles();
            if (cached != null) for (File file : cached) if (!file.delete()) throw new IOException("Could not remove encrypted cache file");
            if (largeFileDir.exists() && !largeFileDir.delete()) throw new IOException("Could not remove encrypted cache directory");
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias);
        } catch (Exception e) {
            throw new IllegalStateException("Could not clear local keys", e);
        }
    }

    private SecretKey getKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(keyAlias)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(keyAlias, null);
            return entry.getSecretKey();
        }
        if (!create) throw new GeneralSecurityException("Android Keystore key is missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
