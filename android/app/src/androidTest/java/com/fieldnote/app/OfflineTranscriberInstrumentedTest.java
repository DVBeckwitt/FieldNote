package com.fieldnote.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@RunWith(AndroidJUnit4.class)
public final class OfflineTranscriberInstrumentedTest {
    @Test public void transcribesSavedStereoSpeechAndReportsNoSpeechForWatchSilence() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File original = File.createTempFile("transcription-test-", ".m4a", context.getCacheDir());
        try (OfflineTranscriber transcriber = new OfflineTranscriber(context)) {
            // Windows SAPI Microsoft David Desktop, rate -1. FFmpeg joins both phrases with
            // a 1.2-second pause and encodes the result as stereo 48 kHz AAC.
            copyFixture("spoken-aac-stereo.m4a", original);
            String transcript = transcriber.transcribe(original);
            assertTrue(transcript, transcript.contains("blue notebook is on the table"));
            assertTrue(transcript, transcript.contains("please turn the page"));

            copyFixture("watch-aac-silence.m4a", original);
            assertEquals("", transcriber.transcribe(original));
        } finally {
            Files.deleteIfExists(original.toPath());
        }
    }

    @Test public void resumesAtSavedAacFrameWithoutRepeatingTheTranscriptPrefix() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File original = File.createTempFile("transcription-resume-test-", ".m4a", context.getCacheDir());
        // These thresholds place the checkpoint in the silence between the two phrases.
        try (OfflineTranscriber transcriber = new OfflineTranscriber(context, 3_100_000L, 3_300_000L)) {
            copyFixture("spoken-aac-stereo.m4a", original);
            String uninterrupted = transcriber.transcribe(original, 0L, "", (position, text) -> {});
            long[] position = {0L};
            String[] prefix = {""};
            assertThrows(InterruptedIOException.class, () -> transcriber.transcribe(original, 0L, "", (next, text) -> {
                position[0] = next;
                prefix[0] = text;
                throw new InterruptedIOException("Test interruption after checkpoint");
            }));
            assertTrue(position[0] >= 3_100_000L && position[0] < 4_175_000L);
            assertTrue(prefix[0], prefix[0].contains("blue notebook is on the table"));
            assertFalse(prefix[0], prefix[0].contains("please turn the page"));

            String resumed = transcriber.transcribe(original, position[0], prefix[0], (next, text) -> {});
            assertTrue(resumed, resumed.contains("please turn the page"));
            assertEquals(uninterrupted, resumed);
        } finally {
            Files.deleteIfExists(original.toPath());
        }
    }

    private static void copyFixture(String assetName, File destination) throws Exception {
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = test.getAssets().open(assetName)) {
            Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
