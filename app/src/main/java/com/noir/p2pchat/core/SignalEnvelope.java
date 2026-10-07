package com.noir.p2pchat.core;

import java.nio.ByteBuffer;

/** Versioned, content-free DataChannel frame for Signal ciphertext. */
public final class SignalEnvelope {
    private static final byte MARKER = (byte) 'S';
    private static final byte VERSION = 1;
    private static final int HEADER_BYTES = 7;
    public static final int MAX_CIPHERTEXT_BYTES = 128 * 1024;

    private SignalEnvelope() { }

    public static byte[] encode(int signalType, byte[] ciphertext) {
        if ((signalType != 2 && signalType != 3) || ciphertext == null || ciphertext.length == 0
                || ciphertext.length > MAX_CIPHERTEXT_BYTES) {
            throw new IllegalArgumentException("Invalid Signal ciphertext envelope");
        }
        ByteBuffer result = ByteBuffer.allocate(HEADER_BYTES + ciphertext.length);
        result.put(MARKER);
        result.put(VERSION);
        result.put((byte) signalType);
        result.putInt(ciphertext.length);
        result.put(ciphertext);
        return result.array();
    }

    public static Envelope decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_BYTES + 1 || frame.length > HEADER_BYTES + MAX_CIPHERTEXT_BYTES
                || frame[0] != MARKER || frame[1] != VERSION) return null;
        int type = frame[2] & 0xff;
        int length = ByteBuffer.wrap(frame, 3, Integer.BYTES).getInt();
        if ((type != 2 && type != 3) || length <= 0 || length != frame.length - HEADER_BYTES) return null;
        byte[] ciphertext = new byte[length];
        System.arraycopy(frame, HEADER_BYTES, ciphertext, 0, length);
        return new Envelope(type, ciphertext);
    }

    public static final class Envelope {
        public final int type;
        public final byte[] ciphertext;

        private Envelope(int type, byte[] ciphertext) {
            this.type = type;
            this.ciphertext = ciphertext;
        }
    }
}
