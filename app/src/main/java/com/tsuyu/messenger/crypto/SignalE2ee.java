package com.tsuyu.messenger.crypto;

import android.content.Context;
import android.util.Base64;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Session setup and message encryption using Signal's maintained libsignal implementation.
 * The RTDB receives only public prekey material and Signal ciphertext.
 */
public final class SignalE2ee {
    private static final int DEVICE_ID = 1;
    private static final String ACTIVE_SIGNED = "active.signed";
    private static final String ACTIVE_KYBER = "active.kyber";
    private final Context context;
    private final String localUid;
    private DurableSignalStore store;

    public static final class Encrypted {
        public final int signalType;
        public final String cipher;

        Encrypted(int signalType, String cipher) {
            this.signalType = signalType;
            this.cipher = cipher;
        }
    }

    public SignalE2ee(Context context, String localUid) {
        if (localUid == null || localUid.isEmpty()) throw new IllegalArgumentException("Missing account id");
        this.context = context.getApplicationContext();
        this.localUid = localUid;
        this.store = DurableSignalStore.open(this.context, localUid);
    }

    public synchronized Map<String, Object> publicKeyBundle() {
        try {
            ensurePrekeys();
            int signedId = Integer.parseInt(store.getSetting(ACTIVE_SIGNED));
            int kyberId = Integer.parseInt(store.getSetting(ACTIVE_KYBER));
            SignedPreKeyRecord signed = store.loadSignedPreKey(signedId);
            KyberPreKeyRecord kyber = store.loadKyberPreKey(kyberId);
            Map<String, Object> bundle = new HashMap<>();
            bundle.put("deviceId", DEVICE_ID);
            bundle.put("registrationId", store.getLocalRegistrationId());
            bundle.put("identityKey", b64(store.getIdentityKeyPair().getPublicKey().serialize()));
            bundle.put("signedPreKeyId", signedId);
            bundle.put("signedPreKey", b64(signed.getKeyPair().getPublicKey().serialize()));
            bundle.put("signedPreKeySignature", b64(signed.getSignature()));
            bundle.put("kyberPreKeyId", kyberId);
            bundle.put("kyberPreKey", b64(kyber.getKeyPair().getPublicKey().serialize()));
            bundle.put("kyberPreKeySignature", b64(kyber.getSignature()));
            bundle.put("protocol", "libsignal-pqxdh-dr-v1");
            return bundle;
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare Signal public prekeys", e);
        }
    }

    private void ensurePrekeys() throws Exception {
        String signedText = store.getSetting(ACTIVE_SIGNED);
        if (signedText == null || !store.containsSignedPreKey(Integer.parseInt(signedText))) {
            int id = freshId();
            ECKeyPair pair = ECKeyPair.generate();
            byte[] signature = store.getIdentityKeyPair().getPrivateKey()
                    .calculateSignature(pair.getPublicKey().serialize());
            store.storeSignedPreKey(id, new SignedPreKeyRecord(id, System.currentTimeMillis(), pair, signature));
            store.setSetting(ACTIVE_SIGNED, Integer.toString(id));
        }
        String kyberText = store.getSetting(ACTIVE_KYBER);
        if (kyberText == null || !store.containsKyberPreKey(Integer.parseInt(kyberText))) {
            int id = freshId();
            KEMKeyPair pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] signature = store.getIdentityKeyPair().getPrivateKey()
                    .calculateSignature(pair.getPublicKey().serialize());
            store.storeKyberPreKey(id, new KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature));
            store.setSetting(ACTIVE_KYBER, Integer.toString(id));
        }
    }

    private static int freshId() {
        return new SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1;
    }

    public synchronized Encrypted encrypt(String recipientUid, Map<String, Object> remoteBundle, byte[] plaintext)
            throws Exception {
        if (recipientUid == null || recipientUid.isEmpty() || recipientUid.equals(localUid)) {
            throw new IllegalArgumentException("Invalid recipient");
        }
        SignalProtocolAddress remote = new SignalProtocolAddress(recipientUid, DEVICE_ID);
        SignalProtocolAddress local = new SignalProtocolAddress(localUid, DEVICE_ID);
        IdentityKey candidateIdentity = new IdentityKey(unb64(remoteBundle == null ? null : remoteBundle.get("identityKey")));
        IdentityKey trustedIdentity = store.getIdentity(remote);
        if (trustedIdentity != null && !Arrays.equals(trustedIdentity.serialize(), candidateIdentity.serialize())) {
            throw new SecurityException("Собеседник сменил ключ Signal. Сверьте отпечаток в профиле перед продолжением.");
        }
        if (!store.containsSession(remote)) {
            PreKeyBundle preKeyBundle = createBundle(remoteBundle);
            new SessionBuilder(store, remote, local).process(preKeyBundle);
        }
        CiphertextMessage ciphertext = new SessionCipher(store, local, remote).encrypt(plaintext);
        return new Encrypted(ciphertext.getType(), b64(ciphertext.serialize()));
    }

    public synchronized byte[] decrypt(String senderUid, int signalType, String encodedCiphertext) throws Exception {
        byte[] ciphertext = android.util.Base64.decode(encodedCiphertext, android.util.Base64.NO_WRAP);
        SignalProtocolAddress remote = new SignalProtocolAddress(senderUid, DEVICE_ID);
        SignalProtocolAddress local = new SignalProtocolAddress(localUid, DEVICE_ID);
        SessionCipher cipher = new SessionCipher(store, local, remote);
        if (signalType == CiphertextMessage.PREKEY_TYPE) {
            return cipher.decrypt(new PreKeySignalMessage(ciphertext));
        }
        if (signalType == CiphertextMessage.WHISPER_TYPE) {
            return cipher.decrypt(new SignalMessage(ciphertext));
        }
        throw new IllegalArgumentException("Unsupported Signal ciphertext type: " + signalType);
    }

    private static PreKeyBundle createBundle(Map<String, Object> values) throws Exception {
        if (values == null || !"libsignal-pqxdh-dr-v1".equals(values.get("protocol"))) {
            throw new IllegalArgumentException("Recipient has no compatible Signal prekey bundle");
        }
        int registration = number(values.get("registrationId"));
        int device = values.containsKey("deviceId") ? number(values.get("deviceId")) : DEVICE_ID;
        int signedId = number(values.get("signedPreKeyId"));
        int kyberId = number(values.get("kyberPreKeyId"));
        ECPublicKey signedKey = new ECPublicKey(unb64(values.get("signedPreKey")));
        IdentityKey identity = new IdentityKey(unb64(values.get("identityKey")));
        KEMPublicKey kyberKey = new KEMPublicKey(unb64(values.get("kyberPreKey")));
        byte[] signedSignature = unb64(values.get("signedPreKeySignature"));
        byte[] kyberSignature = unb64(values.get("kyberPreKeySignature"));
        // This is a signed last-resort Kyber key; libsignal's PQXDH handshake then advances into
        // the Double Ratchet. Identity signatures are checked by libsignal before a session is saved.
        return new PreKeyBundle(registration, device, PreKeyBundle.NULL_PRE_KEY_ID, null,
                signedId, signedKey, signedSignature, identity, kyberId, kyberKey, kyberSignature);
    }

    public synchronized void trustRemoteIdentity(String remoteUid, Map<String, Object> remoteBundle) throws Exception {
        if (remoteUid == null || remoteUid.isEmpty() || remoteUid.equals(localUid)) throw new IllegalArgumentException("Invalid peer");
        if (remoteBundle == null || !"libsignal-pqxdh-dr-v1".equals(remoteBundle.get("protocol"))) {
            throw new IllegalArgumentException("Missing compatible Signal public key");
        }
        IdentityKey identity = new IdentityKey(unb64(remoteBundle.get("identityKey")));
        SignalProtocolAddress remote = new SignalProtocolAddress(remoteUid, DEVICE_ID);
        store.replaceTrustedIdentity(remote, identity);
    }

    public static String identityFingerprint(Map<String, Object> bundle) throws Exception {
        if (bundle == null) throw new IllegalArgumentException("Missing public key bundle");
        byte[] identity = unb64(bundle.get("identityKey"));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity);
        StringBuilder fingerprint = new StringBuilder("SHA-256 · ");
        for (int i = 0; i < digest.length; i++) {
            fingerprint.append(String.format(java.util.Locale.ROOT, "%02X", digest[i] & 0xff));
            if (i % 2 == 1 && i < digest.length - 1) fingerprint.append(i % 8 == 7 ? "  " : " ");
        }
        return fingerprint.toString();
    }

    public synchronized void rotateIdentity() {
        store.rotateIdentityAndResetSessions();
        store = DurableSignalStore.open(context, localUid);
    }

    public String localUid() {
        return localUid;
    }

    private static int number(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static byte[] unb64(Object value) {
        if (value == null) throw new IllegalArgumentException("Missing public key field");
        return android.util.Base64.decode(String.valueOf(value), android.util.Base64.NO_WRAP);
    }

    private static String b64(byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }
}
