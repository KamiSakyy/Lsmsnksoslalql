package com.tsuyu.messenger.crypto;

import android.content.Context;
import android.util.Base64;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Signal store adapter: durable records are AES-GCM-encrypted with an Android Keystore key. */
public final class DurableSignalStore extends InMemorySignalProtocolStore {
    private static final int DEVICE_ID = 1;
    private static final String IDENTITY = "local.identity";
    private static final String REGISTRATION = "local.registration";
    private final SecurePrefs securePrefs;

    private static final class Bootstrap {
        final SecurePrefs prefs;
        final IdentityKeyPair identity;
        final int registrationId;

        Bootstrap(SecurePrefs prefs, IdentityKeyPair identity, int registrationId) {
            this.prefs = prefs;
            this.identity = identity;
            this.registrationId = registrationId;
        }
    }

    public static DurableSignalStore open(Context context, String accountUid) {
        return new DurableSignalStore(context.getApplicationContext(), bootstrap(context, accountUid));
    }

    private DurableSignalStore(Context context, Bootstrap bootstrap) {
        super(bootstrap.identity, bootstrap.registrationId);
        this.securePrefs = bootstrap.prefs;
        restoreRecords();
    }

    private static Bootstrap bootstrap(Context context, String accountUid) {
        SecurePrefs prefs = new SecurePrefs(context, accountUid);
        String serialized = prefs.get(IDENTITY);
        IdentityKeyPair identity;
        try {
            if (serialized == null) {
                identity = IdentityKeyPair.generate();
                prefs.put(IDENTITY, Base64.encodeToString(identity.serialize(), Base64.NO_WRAP));
            } else {
                identity = new IdentityKeyPair(Base64.decode(serialized, Base64.NO_WRAP));
            }
        } catch (Exception e) {
            throw new IllegalStateException("The local Signal identity is corrupt; refusing to replace it silently", e);
        }
        String registrationText = prefs.get(REGISTRATION);
        int registrationId;
        if (registrationText == null) {
            registrationId = KeyHelper.generateRegistrationId(false);
            prefs.put(REGISTRATION, Integer.toString(registrationId));
        } else {
            try {
                registrationId = Integer.parseInt(registrationText);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("The local Signal registration id is corrupt", e);
            }
        }
        return new Bootstrap(prefs, identity, registrationId);
    }

    public int getDeviceId() {
        return DEVICE_ID;
    }

    public synchronized String getSetting(String name) {
        return securePrefs.get("setting." + name);
    }

    public synchronized void setSetting(String name, String value) {
        securePrefs.put("setting." + name, value);
    }

    public synchronized void rotateIdentityAndResetSessions() {
        // Explicit user action only. Existing encrypted history cannot be opened with the new key.
        securePrefs.wipeForExplicitKeyRotation();
    }

    private void restoreRecords() {
        for (String key : securePrefs.keysWithPrefix("prekey.")) {
            try {
                int id = Integer.parseInt(key.substring("prekey.".length()));
                super.storePreKey(id, new PreKeyRecord(decodeStored(key)));
            } catch (Exception e) {
                throw corrupt("prekey", key, e);
            }
        }
        for (String key : securePrefs.keysWithPrefix("signed.")) {
            try {
                int id = Integer.parseInt(key.substring("signed.".length()));
                super.storeSignedPreKey(id, new SignedPreKeyRecord(decodeStored(key)));
            } catch (Exception e) {
                throw corrupt("signed prekey", key, e);
            }
        }
        for (String key : securePrefs.keysWithPrefix("kyber.")) {
            try {
                int id = Integer.parseInt(key.substring("kyber.".length()));
                super.storeKyberPreKey(id, new KyberPreKeyRecord(decodeStored(key)));
            } catch (Exception e) {
                throw corrupt("Kyber prekey", key, e);
            }
        }
        for (String key : securePrefs.keysWithPrefix("session.")) {
            try {
                String[] parts = key.split("\\.", 3);
                String name = decodeName(parts[1]);
                int device = Integer.parseInt(parts[2]);
                super.storeSession(new SignalProtocolAddress(name, device), new SessionRecord(decodeStored(key)));
            } catch (Exception e) {
                throw corrupt("session", key, e);
            }
        }
        for (String key : securePrefs.keysWithPrefix("trusted.")) {
            try {
                String[] parts = key.split("\\.", 3);
                String name = decodeName(parts[1]);
                int device = Integer.parseInt(parts[2]);
                super.saveIdentity(new SignalProtocolAddress(name, device), new IdentityKey(decodeStored(key)));
            } catch (Exception e) {
                throw corrupt("trusted identity", key, e);
            }
        }
        for (String key : securePrefs.keysWithPrefix("sender.")) {
            try {
                String[] parts = key.split("\\.", 4);
                String name = decodeName(parts[1]);
                int device = Integer.parseInt(parts[2]);
                UUID distribution = UUID.fromString(parts[3]);
                super.storeSenderKey(new SignalProtocolAddress(name, device), distribution, new SenderKeyRecord(decodeStored(key)));
            } catch (Exception e) {
                throw corrupt("sender key", key, e);
            }
        }
        for (String key : securePrefs.keysWithPrefix("used.")) {
            try {
                String[] parts = key.split("\\.", 4);
                int kyberId = Integer.parseInt(parts[1]);
                int signedId = Integer.parseInt(parts[2]);
                ECPublicKey baseKey = new ECPublicKey(Base64.decode(parts[3], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
                try {
                    super.markKyberPreKeyUsed(kyberId, signedId, baseKey);
                } catch (ReusedBaseKeyException duplicate) {
                    // Already restored; the saved marker is intentionally idempotent.
                }
            } catch (Exception e) {
                throw corrupt("Kyber replay marker", key, e);
            }
        }
    }

    private byte[] decodeStored(String key) {
        String value = securePrefs.get(key);
        if (value == null) throw new IllegalStateException("Missing encrypted record");
        return Base64.decode(value, Base64.NO_WRAP);
    }

    private void saveBytes(String key, byte[] bytes) {
        securePrefs.put(key, Base64.encodeToString(bytes, Base64.NO_WRAP));
    }

    private RuntimeException corrupt(String kind, String key, Exception cause) {
        return new IllegalStateException("Encrypted " + kind + " state is damaged at " + key + "; refusing to discard it", cause);
    }

    private static String encodeName(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String decodeName(String value) {
        return new String(Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
    }

    private static String addressKey(String prefix, SignalProtocolAddress address) {
        return prefix + encodeName(address.getName()) + "." + address.getDeviceId();
    }

    @Override
    public synchronized IdentityKeyStore.IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        IdentityKeyStore.IdentityChange result = super.saveIdentity(address, identityKey);
        saveBytes(addressKey("trusted.", address), identityKey.serialize());
        return result;
    }

    public synchronized void replaceTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        super.saveIdentity(address, identityKey);
        saveBytes(addressKey("trusted.", address), identityKey.serialize());
        deleteSession(address);
    }

    @Override
    public synchronized void storePreKey(int id, PreKeyRecord record) {
        super.storePreKey(id, record);
        saveBytes("prekey." + id, record.serialize());
    }

    @Override
    public synchronized void removePreKey(int id) {
        super.removePreKey(id);
        securePrefs.remove("prekey." + id);
    }

    @Override
    public synchronized void storeSignedPreKey(int id, SignedPreKeyRecord record) {
        super.storeSignedPreKey(id, record);
        saveBytes("signed." + id, record.serialize());
    }

    @Override
    public synchronized void removeSignedPreKey(int id) {
        super.removeSignedPreKey(id);
        securePrefs.remove("signed." + id);
    }

    @Override
    public synchronized void storeKyberPreKey(int id, KyberPreKeyRecord record) {
        super.storeKyberPreKey(id, record);
        saveBytes("kyber." + id, record.serialize());
    }

    @Override
    public synchronized void storeSession(SignalProtocolAddress address, SessionRecord record) {
        super.storeSession(address, record);
        saveBytes(addressKey("session.", address), record.serialize());
    }

    @Override
    public synchronized void deleteSession(SignalProtocolAddress address) {
        super.deleteSession(address);
        securePrefs.remove(addressKey("session.", address));
    }

    @Override
    public synchronized void deleteAllSessions(String name) {
        super.deleteAllSessions(name);
        for (String key : new ArrayList<>(securePrefs.keysWithPrefix("session."))) {
            String[] parts = key.split("\\.", 3);
            if (parts.length == 3 && decodeName(parts[1]).equals(name)) securePrefs.remove(key);
        }
    }

    @Override
    public synchronized void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
        super.storeSenderKey(sender, distributionId, record);
        saveBytes("sender." + encodeName(sender.getName()) + "." + sender.getDeviceId() + "." + distributionId, record.serialize());
    }

    @Override
    public synchronized void markKyberPreKeyUsed(int kyberId, int signedId, ECPublicKey baseKey) throws ReusedBaseKeyException {
        super.markKyberPreKeyUsed(kyberId, signedId, baseKey);
        String encoded = Base64.encodeToString(baseKey.serialize(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        securePrefs.put("used." + kyberId + "." + signedId + "." + encoded, "1");
    }
}
