package com.fieldnote.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class SpeechModelsTest {
    private static final long ABC_CRC32 = 0x352441c2L;

    @Test public void verifiedFileKeepsExactBytes() throws IOException {
        byte[] original = "abc".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        SpeechModels.copyVerified(new ByteArrayInputStream(original), copy, 3, ABC_CRC32, bytes -> {});
        assertArrayEquals(original, copy.toByteArray());
    }

    @Test public void sameSizeReplacementFailsVerification() {
        assertThrows(IOException.class, () -> SpeechModels.copyVerified(
                new ByteArrayInputStream("abd".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
                3, ABC_CRC32, bytes -> {}));
    }

    @Test public void oversizedFileCannotWriteBeyondItsAllowedBytes() {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> SpeechModels.copyVerified(
                new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8)), copy, 3, ABC_CRC32, bytes -> {}));
        assertEquals(0, copy.size());
    }
}
