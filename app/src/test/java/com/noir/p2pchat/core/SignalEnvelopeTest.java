package com.noir.p2pchat.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class SignalEnvelopeTest {
    @Test
    public void roundTripsPrekeyAndRatchetCiphertext() {
        byte[] ciphertext = new byte[]{1, 2, 3, 4, 5};
        for (int type : new int[]{2, 3}) {
            SignalEnvelope.Envelope decoded = SignalEnvelope.decode(SignalEnvelope.encode(type, ciphertext));
            assertEquals(type, decoded.type);
            assertArrayEquals(ciphertext, decoded.ciphertext);
        }
    }

    @Test
    public void rejectsInvalidHeadersLengthsAndTypes() {
        assertNull(SignalEnvelope.decode(null));
        assertNull(SignalEnvelope.decode(new byte[7]));
        assertNull(SignalEnvelope.decode(new byte[]{'S', 1, 4, 0, 0, 0, 1, 0}));
        assertNull(SignalEnvelope.decode(new byte[]{'S', 2, 3, 0, 0, 0, 1, 0}));
        assertNull(SignalEnvelope.decode(new byte[]{'S', 1, 3, 0, 0, 0, 2, 0}));
    }
}
