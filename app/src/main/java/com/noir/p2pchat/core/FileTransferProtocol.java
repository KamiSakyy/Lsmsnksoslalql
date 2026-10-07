package com.noir.p2pchat.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Pure-Java framing and offset validation shared by the DataChannel transfer path and unit tests. */
public final class FileTransferProtocol {
    public static final int MAX_FILE_BYTES = 50 * 1024 * 1024;
    public static final int CHUNK_BYTES = 15_000;
    public static final int ACK_WINDOW_CHUNKS = 4;
    public static final int HEADER_BYTES = 41;
    private static final int ID_BYTES = 36;

    private FileTransferProtocol() { }

    public static boolean isValidMessageId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,80}");
    }

    public static byte[] encodeChunk(String id, int index, byte[] payload, int offset, int length) {
        if (id == null || id.isEmpty() || id.length() > ID_BYTES) {
            throw new IllegalArgumentException("Invalid transfer id");
        }
        if (index < 0 || payload == null || offset < 0 || length <= 0
                || length > CHUNK_BYTES || offset > payload.length - length) {
            throw new IllegalArgumentException("Invalid chunk range");
        }
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + length);
        frame.put((byte) 'F');
        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        byte[] paddedId = new byte[ID_BYTES];
        System.arraycopy(idBytes, 0, paddedId, 0, idBytes.length);
        frame.put(paddedId);
        frame.putInt(index);
        frame.put(payload, offset, length);
        return frame.array();
    }

    public static Chunk decodeChunk(byte[] frame) {
        if (frame == null || frame.length <= HEADER_BYTES || frame[0] != (byte) 'F') return null;
        String id = new String(frame, 1, ID_BYTES, StandardCharsets.US_ASCII).trim();
        int index = ByteBuffer.wrap(frame, 1 + ID_BYTES, Integer.BYTES).getInt();
        int payloadLength = frame.length - HEADER_BYTES;
        if (!isValidMessageId(id) || index < 0 || payloadLength <= 0 || payloadLength > CHUNK_BYTES) {
            return null;
        }
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, HEADER_BYTES, payload, 0, payloadLength);
        return new Chunk(id, index, payload);
    }

    public static boolean isValidResumeOffset(long offset, long fileSize) {
        return fileSize > 0L && fileSize <= MAX_FILE_BYTES && offset >= 0L && offset <= fileSize
                && (offset == fileSize || offset % CHUNK_BYTES == 0L);
    }

    /** Aligns a peer-provided resume offset to a complete chunk and clamps it to the file size. */
    public static long normalizeResumeOffset(long requestedOffset, long fileSize) {
        if (fileSize < 0 || fileSize > MAX_FILE_BYTES) throw new IllegalArgumentException("Invalid file size");
        long offset = Math.max(0L, Math.min(requestedOffset, fileSize));
        if (offset < fileSize) offset -= offset % CHUNK_BYTES;
        return offset;
    }

    public static final class Chunk {
        public final String id;
        public final int index;
        public final byte[] payload;

        private Chunk(String id, int index, byte[] payload) {
            this.id = id;
            this.index = index;
            this.payload = payload;
        }
    }
}
