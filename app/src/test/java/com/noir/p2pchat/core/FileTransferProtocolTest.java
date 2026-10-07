package com.noir.p2pchat.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class FileTransferProtocolTest {
    private static final String MESSAGE_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    public void chunkRoundTripsWithStableHeader() {
        byte[] source = new byte[FileTransferProtocol.CHUNK_BYTES];
        for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 31);

        byte[] frame = FileTransferProtocol.encodeChunk(MESSAGE_ID, 7, source, 125, 4096);
        FileTransferProtocol.Chunk decoded = FileTransferProtocol.decodeChunk(frame);

        assertEquals(MESSAGE_ID, decoded.id);
        assertEquals(7, decoded.index);
        assertArrayEquals(Arrays.copyOfRange(source, 125, 125 + 4096), decoded.payload);
    }

    @Test
    public void rejectsMalformedFramesAndIds() {
        assertNull(FileTransferProtocol.decodeChunk(null));
        assertNull(FileTransferProtocol.decodeChunk(new byte[FileTransferProtocol.HEADER_BYTES]));
        assertNull(FileTransferProtocol.decodeChunk(new byte[]{'x', 0, 1}));
        assertTrue(FileTransferProtocol.isValidMessageId(MESSAGE_ID));
        assertTrue(!FileTransferProtocol.isValidMessageId("../../private"));
    }

    @Test
    public void resumeOffsetIsChunkAlignedAndBounded() {
        long size = FileTransferProtocol.CHUNK_BYTES * 4L + 17L;
        assertEquals(FileTransferProtocol.CHUNK_BYTES * 2L,
                FileTransferProtocol.normalizeResumeOffset(FileTransferProtocol.CHUNK_BYTES * 2L + 99L, size));
        assertEquals(size, FileTransferProtocol.normalizeResumeOffset(size + 1_000L, size));
        assertEquals(0L, FileTransferProtocol.normalizeResumeOffset(-10L, size));
    }
}
