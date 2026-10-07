package com.noir.p2pchat.core;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Persistent libsignal state. Serialized protocol records are encrypted with an Android Keystore
 * AES-GCM key before SQLite storage; no private protocol key is embedded in an APK or written as plaintext.
 */
public final class EncryptedSignalProtocolStore implements SignalProtocolStore {
    private static final String PREFS = "signal_protocol_metadata";
    private static final String DB_NAME = "signal_protocol_state.db";
    private static final String KEY_ALIAS = "noir_p2p_signal_state_v1";
    private static final String IDENTITY_KEY = "identity.local";
    private static final String REGISTRATION_KEY = "meta.registration";
    private static final String CURRENT_SIGNED_KEY = "meta.current_signed";
    private static final String CURRENT_KYBER_KEY = "meta.current_kyber";
    private static final String PREKEY_PREFIX = "prekey.";
    private static final String SIGNED_PREFIX = "signed.";
    private static final String KYBER_PREFIX = "kyber.";
    private static final String SESSION_PREFIX = "session.";
    private static final String TRUST_PREFIX = "trust.";
    private static final String VERIFIED_PREFIX = "verified.";
    private static final String SENDER_PREFIX = "sender.";
    private static final String KYBER_USED_PREFIX = "kyber_used.";
    private static final long PREKEY_ROTATION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long PREKEY_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int DEVICE_ID = 1;

    private final SharedPreferences preferences;
    private final StateDatabase database;
    private final SecureRandom random = new SecureRandom();
    private volatile IdentityKeyPair identityKeyPair;
    private volatile SecretKey encryptionKey;

    public EncryptedSignalProtocolStore(Context context) {
        Context appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        database = new StateDatabase(appContext);
    }

    /** Creates/rotates public prekeys and returns the bundle safe to publish in Firebase. */
    public synchronized JSONObject publicBundle() {
        try {
            ensureIdentity();
            int signedId = currentSignedPreKeyId();
            int kyberId = currentKyberPreKeyId();
            SignedPreKeyRecord signed = loadSignedPreKey(signedId);
            KyberPreKeyRecord kyber = loadKyberPreKey(kyberId);
            ECKeyPair signedPair = signed.getKeyPair();
            ECPublicKey signedPublic = signedPair.getPublicKey();
            KEMKeyPair kyberPair = kyber.getKeyPair();
            KEMPublicKey kyberPublic = kyberPair.getPublicKey();
            JSONObject result = new JSONObject();
            result.put("version", 1);
            result.put("deviceId", DEVICE_ID);
            result.put("registrationId", getLocalRegistrationId());
            result.put("identityKey", encode(identityKeyPair.getPublicKey().serialize()));
            result.put("signedPreKeyId", signedId);
            result.put("signedPreKey", encode(signedPublic.serialize()));
            result.put("signedPreKeySignature", encode(signed.getSignature()));
            result.put("kyberPreKeyId", kyberId);
            result.put("kyberPreKey", encode(kyberPublic.serialize()));
            result.put("kyberPreKeySignature", encode(kyber.getSignature()));
            return result;
        } catch (Exception e) {
            throw storageFailure("Could not prepare the Signal prekey bundle", e);
        }
    }

    public synchronized int getLocalDeviceId() {
        return DEVICE_ID;
    }

    /** Returns a stable SHA-256 fingerprint of the first-seen peer identity, or null if absent. */
    public synchronized String fingerprint(String peerUid) {
        IdentityKey identity = getIdentity(new SignalProtocolAddress(peerUid, DEVICE_ID));
        if (identity == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.serialize());
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02X", b));
            return value.toString();
        } catch (Exception e) {
            throw storageFailure("Could not calculate identity fingerprint", e);
        }
    }

    public synchronized boolean hasSession(String peerUid) {
        ensureIdentity();
        return containsSession(new SignalProtocolAddress(peerUid, DEVICE_ID));
    }

    public synchronized boolean isVerified(String peerUid) {
        String fingerprint = fingerprint(peerUid);
        return fingerprint != null && fingerprint.equals(preferences.getString(VERIFIED_PREFIX + addressKey(peerUid, DEVICE_ID), null));
    }

    public synchronized void markVerified(String peerUid) {
        String fingerprint = fingerprint(peerUid);
        if (fingerprint == null) return;
        if (!preferences.edit().putString(VERIFIED_PREFIX + addressKey(peerUid, DEVICE_ID), fingerprint).commit()) {
            throw new IllegalStateException("Could not persist identity verification state");
        }
    }

    private void ensureIdentity() {
        if (identityKeyPair != null) return;
        try {
            byte[] serialized = readRecord(IDENTITY_KEY);
            if (serialized == null) {
                if (!recordKeys().isEmpty()) {
                    throw new IllegalStateException("Signal store has records but no identity key");
                }
                IdentityKeyPair generated = IdentityKeyPair.generate();
                int registrationId = KeyHelper.generateRegistrationId(false);
                writeRecord(IDENTITY_KEY, generated.serialize());
                boolean committed = preferences.edit().putInt(REGISTRATION_KEY, registrationId).commit();
                if (!committed) throw new IllegalStateException("Could not persist the Signal registration ID");
                identityKeyPair = generated;
                return;
            }
            int registrationId = preferences.getInt(REGISTRATION_KEY, 0);
            if (registrationId < 1 || registrationId > 16380) {
                throw new IllegalStateException("Invalid Signal registration ID");
            }
            identityKeyPair = new IdentityKeyPair(serialized);
        } catch (Exception e) {
            throw storageFailure("Signal identity state is unavailable; refusing to replace it silently", e);
        }
    }

    private synchronized int currentSignedPreKeyId() throws Exception {
        ensureIdentity();
        int current = preferences.getInt(CURRENT_SIGNED_KEY, -1);
        if (current > 0 && containsSignedPreKey(current)) {
            SignedPreKeyRecord record = loadSignedPreKey(current);
            long age = System.currentTimeMillis() - record.getTimestamp();
            if (age >= 0 && age < PREKEY_ROTATION_MS) return current;
        }
        int id = newRecordId(SIGNED_PREFIX);
        ECKeyPair pair = ECKeyPair.generate();
        byte[] signature = identityKeyPair.getPrivateKey().calculateSignature(pair.getPublicKey().serialize());
        SignedPreKeyRecord record = new SignedPreKeyRecord(id, System.currentTimeMillis(), pair, signature);
        storeSignedPreKey(id, record);
        if (!preferences.edit().putInt(CURRENT_SIGNED_KEY, id).commit()) {
            throw new IllegalStateException("Could not update the active signed prekey");
        }
        removeExpiredSignedPreKeys(id);
        return id;
    }

    private synchronized int currentKyberPreKeyId() throws Exception {
        ensureIdentity();
        int current = preferences.getInt(CURRENT_KYBER_KEY, -1);
        if (current > 0 && containsKyberPreKey(current)) {
            KyberPreKeyRecord record = loadKyberPreKey(current);
            long age = System.currentTimeMillis() - record.getTimestamp();
            if (age >= 0 && age < PREKEY_ROTATION_MS) return current;
        }
        int id = newRecordId(KYBER_PREFIX);
        KEMKeyPair pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        KEMPublicKey publicKey = pair.getPublicKey();
        byte[] signature = identityKeyPair.getPrivateKey().calculateSignature(publicKey.serialize());
        KyberPreKeyRecord record = new KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature);
        storeKyberPreKey(id, record);
        if (!preferences.edit().putInt(CURRENT_KYBER_KEY, id).commit()) {
            throw new IllegalStateException("Could not update the active Kyber prekey");
        }
        removeExpiredKyberPreKeys(id);
        return id;
    }

    private int newRecordId(String prefix) {
        for (int i = 0; i < 32; i++) {
            int candidate = random.nextInt(65_535) + 1;
            if (!hasRecord(prefix + candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique Signal prekey ID");
    }

    private void removeExpiredSignedPreKeys(int current) {
        long cutoff = System.currentTimeMillis() - PREKEY_RETENTION_MS;
        for (String key : new ArrayList<>(recordKeys())) {
            if (!key.startsWith(SIGNED_PREFIX)) continue;
            try {
                int id = Integer.parseInt(key.substring(SIGNED_PREFIX.length()));
                if (id == current) continue;
                SignedPreKeyRecord record = loadSignedPreKey(id);
                boolean expired = record.getTimestamp() < cutoff;
                if (expired) removeSignedPreKey(id);
            } catch (Exception ignored) { }
        }
    }

    private void removeExpiredKyberPreKeys(int current) {
        long cutoff = System.currentTimeMillis() - PREKEY_RETENTION_MS;
        for (String key : new ArrayList<>(recordKeys())) {
            if (!key.startsWith(KYBER_PREFIX)) continue;
            try {
                int id = Integer.parseInt(key.substring(KYBER_PREFIX.length()));
                if (id == current) continue;
                KyberPreKeyRecord record = loadKyberPreKey(id);
                boolean expired = record.getTimestamp() < cutoff;
                if (expired) removeKyberPreKey(id);
            } catch (Exception ignored) { }
        }
    }

    private byte[] readRecord(String key) {
        String encoded = null;
        try (Cursor cursor = database.getReadableDatabase().query(
                "signal_state", new String[]{"value"}, "key=?", new String[]{key}, null, null, null, "1")) {
            if (cursor.moveToFirst()) encoded = cursor.getString(0);
        }
        return encoded == null ? null : decrypt(key, encoded);
    }

    private void writeRecord(String key, byte[] serialized) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", encrypt(key, serialized));
        long row = database.getWritableDatabase().insertWithOnConflict(
                "signal_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (row < 0) throw new IllegalStateException("Could not persist Signal protocol state");
    }

    private boolean hasRecord(String key) {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT 1 FROM signal_state WHERE key=? LIMIT 1", new String[]{key})) {
            return cursor.moveToFirst();
        }
    }

    private boolean removeRecord(String key) {
        return database.getWritableDatabase().delete("signal_state", "key=?", new String[]{key}) > 0;
    }

    private List<String> recordKeys() {
        ArrayList<String> result = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT key FROM signal_state", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }

    private List<Integer> ids(String prefix) {
        ArrayList<Integer> result = new ArrayList<>();
        for (String key : recordKeys()) {
            if (!key.startsWith(prefix)) continue;
            try { result.add(Integer.parseInt(key.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        Collections.sort(result);
        return result;
    }

    private static String addressKey(SignalProtocolAddress address) {
        String name = Base64.encodeToString(address.getName().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return name + "." + address.getDeviceId();
    }

    private static String addressKey(String name, int deviceId) {
        String encoded = Base64.encodeToString(name.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return encoded + "." + deviceId;
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public static byte[] decodePublicKey(String value) throws InvalidKeyException {
        if (value == null || value.length() > 4096) throw new InvalidKeyException("Invalid public key encoding");
        try { return Base64.decode(value, Base64.NO_WRAP); }
        catch (IllegalArgumentException e) { throw new InvalidKeyException(e); }
    }

    private String encrypt(String storageKey, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey());
            cipher.updateAAD(storageKey.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return encode(cipher.getIV()) + "." + encode(ciphertext);
        } catch (Exception e) {
            throw storageFailure("Could not encrypt Signal state", e);
        }
    }

    private byte[] decrypt(String storageKey, String envelope) {
        try {
            int separator = envelope.indexOf('.');
            if (separator <= 0 || separator == envelope.length() - 1) throw new IllegalArgumentException("Invalid envelope");
            byte[] iv = Base64.decode(envelope.substring(0, separator), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(envelope.substring(separator + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(storageKey.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw storageFailure("Signal state failed authentication; refusing to reset keys", e);
        }
    }

    private synchronized SecretKey getEncryptionKey() throws Exception {
        if (encryptionKey != null) return encryptionKey;
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            encryptionKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            return encryptionKey;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        encryptionKey = generator.generateKey();
        return encryptionKey;
    }

    private static IllegalStateException storageFailure(String message, Exception cause) {
        return new IllegalStateException(message, cause);
    }

    @Override public synchronized IdentityKeyPair getIdentityKeyPair() {
        ensureIdentity();
        return identityKeyPair;
    }

    @Override public synchronized int getLocalRegistrationId() {
        ensureIdentity();
        return preferences.getInt(REGISTRATION_KEY, 0);
    }

    @Override public synchronized IdentityKeyStore.IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        String key = TRUST_PREFIX + addressKey(address);
        byte[] previous = readRecord(key);
        boolean unchanged = false;
        if (previous != null) {
            try { unchanged = new IdentityKey(previous).equals(identityKey); }
            catch (InvalidKeyException e) { throw storageFailure("Stored peer identity is invalid", e); }
        }
        writeRecord(key, identityKey.serialize());
        return previous == null || unchanged
                ? IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
                : IdentityKeyStore.IdentityChange.REPLACED_EXISTING;
    }

    @Override public synchronized boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey,
                                                                  IdentityKeyStore.Direction direction) {
        String key = TRUST_PREFIX + addressKey(address);
        byte[] previous = readRecord(key);
        if (previous == null) return true;
        try { return new IdentityKey(previous).equals(identityKey); }
        catch (InvalidKeyException e) { throw storageFailure("Stored peer identity is invalid", e); }
    }

    @Override public synchronized IdentityKey getIdentity(SignalProtocolAddress address) {
        byte[] bytes = readRecord(TRUST_PREFIX + addressKey(address));
        if (bytes == null) return null;
        try { return new IdentityKey(bytes); }
        catch (InvalidKeyException e) { throw storageFailure("Stored peer identity is invalid", e); }
    }

    @Override public synchronized PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
        byte[] bytes = readRecord(PREKEY_PREFIX + preKeyId);
        if (bytes == null) throw new InvalidKeyIdException("No Signal prekey " + preKeyId);
        try { return new PreKeyRecord(bytes); }
        catch (InvalidMessageException e) { throw storageFailure("Stored Signal prekey is invalid", e); }
    }

    @Override public synchronized void storePreKey(int preKeyId, PreKeyRecord record) {
        writeRecord(PREKEY_PREFIX + preKeyId, record.serialize());
    }

    @Override public synchronized boolean containsPreKey(int preKeyId) {
        return hasRecord(PREKEY_PREFIX + preKeyId);
    }

    @Override public synchronized void removePreKey(int preKeyId) {
        removeRecord(PREKEY_PREFIX + preKeyId);
    }

    @Override public synchronized SessionRecord loadSession(SignalProtocolAddress address) {
        byte[] bytes = readRecord(SESSION_PREFIX + addressKey(address));
        if (bytes == null) return null;
        try { return new SessionRecord(bytes); }
        catch (InvalidMessageException e) { throw storageFailure("Stored Signal session is invalid", e); }
    }

    @Override public synchronized List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses)
            throws NoSessionException {
        ArrayList<SessionRecord> records = new ArrayList<>(addresses.size());
        for (SignalProtocolAddress address : addresses) {
            SessionRecord record = loadSession(address);
            if (record == null) throw new NoSessionException(address, "No Signal session for " + address);
            records.add(record);
        }
        return records;
    }

    @Override public synchronized List<Integer> getSubDeviceSessions(String name) {
        String prefix = SESSION_PREFIX + addressKey(name, 0).replaceFirst("\\.0$", ".");
        ArrayList<Integer> result = new ArrayList<>();
        for (String key : recordKeys()) {
            if (!key.startsWith(prefix)) continue;
            try {
                int deviceId = Integer.parseInt(key.substring(prefix.length()));
                if (deviceId != DEVICE_ID) result.add(deviceId);
            } catch (NumberFormatException ignored) { }
        }
        Collections.sort(result);
        return result;
    }

    @Override public synchronized void storeSession(SignalProtocolAddress address, SessionRecord record) {
        writeRecord(SESSION_PREFIX + addressKey(address), record.serialize());
    }

    @Override public synchronized boolean containsSession(SignalProtocolAddress address) {
        return hasRecord(SESSION_PREFIX + addressKey(address));
    }

    @Override public synchronized void deleteSession(SignalProtocolAddress address) {
        removeRecord(SESSION_PREFIX + addressKey(address));
    }

    @Override public synchronized void deleteAllSessions(String name) {
        String prefix = SESSION_PREFIX + addressKey(name, 0).replaceFirst("\\.0$", ".");
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            for (String key : recordKeys()) if (key.startsWith(prefix)) {
                db.delete("signal_state", "key=?", new String[]{key});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override public synchronized SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
        byte[] bytes = readRecord(SIGNED_PREFIX + signedPreKeyId);
        if (bytes == null) throw new InvalidKeyIdException("No Signal signed prekey " + signedPreKeyId);
        try { return new SignedPreKeyRecord(bytes); }
        catch (InvalidMessageException e) { throw storageFailure("Stored signed prekey is invalid", e); }
    }

    @Override public synchronized List<SignedPreKeyRecord> loadSignedPreKeys() {
        ArrayList<SignedPreKeyRecord> result = new ArrayList<>();
        for (int id : ids(SIGNED_PREFIX)) {
            try { result.add(loadSignedPreKey(id)); }
            catch (InvalidKeyIdException e) { throw storageFailure("Stored signed prekey is missing", e); }
        }
        return result;
    }

    @Override public synchronized void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        writeRecord(SIGNED_PREFIX + signedPreKeyId, record.serialize());
    }

    @Override public synchronized boolean containsSignedPreKey(int signedPreKeyId) {
        return hasRecord(SIGNED_PREFIX + signedPreKeyId);
    }

    @Override public synchronized void removeSignedPreKey(int signedPreKeyId) {
        removeRecord(SIGNED_PREFIX + signedPreKeyId);
    }

    @Override public synchronized void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
        writeRecord(SENDER_PREFIX + addressKey(sender) + "." + distributionId, record.serialize());
    }

    @Override public synchronized SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
        byte[] bytes = readRecord(SENDER_PREFIX + addressKey(sender) + "." + distributionId);
        if (bytes == null) return null;
        try { return new SenderKeyRecord(bytes); }
        catch (InvalidMessageException e) { throw storageFailure("Stored sender key is invalid", e); }
    }

    @Override public synchronized KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
        byte[] bytes = readRecord(KYBER_PREFIX + kyberPreKeyId);
        if (bytes == null) throw new InvalidKeyIdException("No Signal Kyber prekey " + kyberPreKeyId);
        try { return new KyberPreKeyRecord(bytes); }
        catch (InvalidMessageException e) { throw storageFailure("Stored Kyber prekey is invalid", e); }
    }

    @Override public synchronized List<KyberPreKeyRecord> loadKyberPreKeys() {
        ArrayList<KyberPreKeyRecord> result = new ArrayList<>();
        for (int id : ids(KYBER_PREFIX)) {
            try { result.add(loadKyberPreKey(id)); }
            catch (InvalidKeyIdException e) { throw storageFailure("Stored Kyber prekey is missing", e); }
        }
        return result;
    }

    @Override public synchronized void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        writeRecord(KYBER_PREFIX + kyberPreKeyId, record.serialize());
    }

    @Override public synchronized boolean containsKyberPreKey(int kyberPreKeyId) {
        return hasRecord(KYBER_PREFIX + kyberPreKeyId);
    }

    @Override public synchronized void markKyberPreKeyUsed(int kyberPreKeyId, int signedPreKeyId, ECPublicKey baseKey)
            throws ReusedBaseKeyException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(baseKey.serialize());
            String key = KYBER_USED_PREFIX + kyberPreKeyId + "." + signedPreKeyId + "." + encode(digest);
            if (preferences.contains(key)) throw new ReusedBaseKeyException("Signal Kyber prekey base key was reused");
            if (!preferences.edit().putBoolean(key, true).commit()) {
                throw storageFailure("Could not persist Kyber prekey usage", null);
            }
        } catch (ReusedBaseKeyException e) {
            throw e;
        } catch (Exception e) {
            throw storageFailure("Could not record Kyber prekey usage", e);
        }
    }

    private static final class StateDatabase extends SQLiteOpenHelper {
        private static final int VERSION = 1;

        StateDatabase(Context context) {
            super(context, DB_NAME, null, VERSION);
            setWriteAheadLoggingEnabled(true);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE signal_state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // State records are versioned by libsignal itself; migrations must preserve each encrypted row.
        }
    }

}
