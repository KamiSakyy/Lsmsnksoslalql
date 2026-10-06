package com.noir.p2pchat.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores Firebase anonymous-auth refresh tokens encrypted with an Android Keystore AES-GCM key. */
public final class TokenStore {
    private static final String PREFS = "auth_tokens";
    private static final String BLOB = "encrypted_session";
    private static final String KEY_ALIAS = "noir_p2p_auth_v1";
    private final SharedPreferences preferences;

    public TokenStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Session load() {
        String encoded = preferences.getString(BLOB, null);
        if (encoded == null) return null;
        try {
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) throw new IllegalStateException("Bad token envelope");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            JSONObject json = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            return new Session(
                    json.getString("idToken"),
                    json.getString("refreshToken"),
                    json.getString("uid"),
                    json.optLong("expiresAt", 0L));
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    public synchronized void save(Session session) throws Exception {
        JSONObject json = new JSONObject();
        json.put("idToken", session.idToken);
        json.put("refreshToken", session.refreshToken);
        json.put("uid", session.uid);
        json.put("expiresAt", session.expiresAtMillis);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        String envelope = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        preferences.edit().putString(BLOB, envelope).apply();
    }

    public synchronized void clear() {
        preferences.edit().remove(BLOB).apply();
    }

    private SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public static final class Session {
        public final String idToken;
        public final String refreshToken;
        public final String uid;
        public final long expiresAtMillis;

        public Session(String idToken, String refreshToken, String uid, long expiresAtMillis) {
            this.idToken = idToken;
            this.refreshToken = refreshToken;
            this.uid = uid;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
