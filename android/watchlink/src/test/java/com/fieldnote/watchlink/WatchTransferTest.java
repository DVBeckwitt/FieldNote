package com.fieldnote.watchlink;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WatchTransferTest {
    private final UUID id = UUID.fromString("4fc5feee-0ee1-43a2-9a6b-0b0421e10053");

    @Test public void headerLeavesRecordingBytesForReceiver() throws IOException {
        WatchTransfer.Header expected = new WatchTransfer.Header(id, 100, 200, 8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        WatchTransfer.writeHeader(output, expected);
        output.writeLong(0x0102030405060708L);
        DataInputStream input = stream(bytes.toByteArray());
        assertEquals(expected, WatchTransfer.readHeader(input));
        assertEquals(0x0102030405060708L, input.readLong());
    }

    @Test public void rejectsMalformedAndTruncatedHeaders() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        WatchTransfer.writeHeader(output, new WatchTransfer.Header(id, 100, 200, 8));
        byte[] header = bytes.toByteArray();
        assertThrows(EOFException.class, () -> WatchTransfer.readHeader(stream(Arrays.copyOf(header, header.length - 1))));
        header[0] = 0;
        assertThrows(IOException.class, () -> WatchTransfer.readHeader(stream(header)));
        assertThrows(IOException.class, () -> WatchTransfer.writeHeader(output,
                new WatchTransfer.Header(id, 100, 200, WatchTransfer.MAX_BYTES + 1)));
    }

    @Test public void onlySuccessAcknowledgementAuthorizesDeletion() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        WatchTransfer.writeResult(output, false);
        WatchTransfer.writeResult(output, true);
        DataInputStream input = stream(bytes.toByteArray());
        assertFalse(WatchTransfer.readResult(input));
        assertTrue(WatchTransfer.readResult(input));
        assertThrows(EOFException.class, () -> WatchTransfer.readResult(input));
        assertThrows(IOException.class, () -> WatchTransfer.readResult(stream(new byte[]{2})));
    }

    private DataInputStream stream(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
