package com.tsuyu.messenger.crypto;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.signal.libsignal.protocol.message.CiphertextMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public final class SignalRatchetRoundTripTest {
    @Test
    public void firstContactAndSubsequentRatchetMessagesDecryptAcrossRestarts() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String aliceUid = "test-alice-" + UUID.randomUUID();
        String bobUid = "test-bob-" + UUID.randomUUID();
        SignalE2ee alice = new SignalE2ee(context, aliceUid);
        SignalE2ee bob = new SignalE2ee(context, bobUid);
        Map<String, Object> aliceBundle = alice.publicKeyBundle();
        Map<String, Object> bobBundle = bob.publicKeyBundle();

        byte[] first = "PQXDH first message".getBytes(StandardCharsets.UTF_8);
        SignalE2ee.Encrypted initial = alice.encrypt(bobUid, bobBundle, first);
        assertEquals(CiphertextMessage.PREKEY_TYPE, initial.signalType);
        assertArrayEquals(first, bob.decrypt(aliceUid, initial.signalType, initial.cipher));

        byte[] response = "Double Ratchet reply".getBytes(StandardCharsets.UTF_8);
        SignalE2ee.Encrypted reply = bob.encrypt(aliceUid, aliceBundle, response);
        assertArrayEquals(response, alice.decrypt(bobUid, reply.signalType, reply.cipher));

        // Reopening the account must restore the encrypted ratchet record and advance it safely.
        alice = new SignalE2ee(context, aliceUid);
        bob = new SignalE2ee(context, bobUid);
        byte[] next = "Persisted ratchet state".getBytes(StandardCharsets.UTF_8);
        SignalE2ee.Encrypted nextMessage = alice.encrypt(bobUid, bobBundle, next);
        assertArrayEquals(next, bob.decrypt(aliceUid, nextMessage.signalType, nextMessage.cipher));
    }
}
