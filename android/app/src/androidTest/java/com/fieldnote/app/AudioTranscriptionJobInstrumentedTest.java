package com.fieldnote.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class AudioTranscriptionJobInstrumentedTest {
    @Test public void legacyCheckpointKeepsTheBundledModel() throws Exception {
        verifyCheckpointModel(null, SpeechModel.SMALL);
    }

    @Test public void laterCheckpointKeepsTheRecordingModel() throws Exception {
        verifyCheckpointModel(SpeechModel.LGRAPH.id, SpeechModel.LGRAPH);
    }

    @Test public void abandonedLargeModelLoadQuarantinesTheModelBeforeAnotherAttempt() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File modelStorage = Files.createTempDirectory(target.getCacheDir().toPath(), "large-model-load-").toFile();
        String preferencePrefix = "model-test-" + UUID.randomUUID() + "-";
        Set<String> fixturePreferences = new HashSet<>();
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return modelStorage; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                String fixture = preferencePrefix + name;
                fixturePreferences.add(fixture);
                return super.getSharedPreferences(fixture, mode);
            }
        };
        try {
            Method recover = AudioTranscriptionJob.class.getDeclaredMethod("recoverAbandonedModelLoad", Context.class);
            Method load = AudioTranscriptionJob.class.getDeclaredMethod("loadEngine", Context.class, SpeechModel.class);
            recover.setAccessible(true);
            load.setAccessible(true);

            SharedPreferences marker = context.getSharedPreferences("fieldnote-transcription", Context.MODE_PRIVATE);
            assertTrue(marker.edit().putString("loading_large_model", SpeechModel.FULL.id).commit());
            recover.invoke(null, context);
            assertTrue(SpeechModels.isQuarantined(context, SpeechModel.FULL));
            assertFalse(marker.contains("loading_large_model"));

            // Full is absent in this isolated directory. The interrupted-load error proves
            // the loader skips it before touching model files or trying native allocation.
            Throwable skipped = assertThrows(InvocationTargetException.class,
                    () -> load.invoke(null, context, SpeechModel.FULL)).getCause();
            assertTrue(skipped instanceof IOException);
            assertEquals("The previous load of this speech model was interrupted.", skipped.getMessage());

            Throwable unavailable = assertThrows(InvocationTargetException.class,
                    () -> load.invoke(null, context, SpeechModel.GIGASPEECH)).getCause();
            assertTrue(unavailable instanceof IOException);
            assertFalse(marker.contains("loading_large_model"));
            recover.invoke(null, context);
            assertFalse(SpeechModels.isQuarantined(context, SpeechModel.GIGASPEECH));
        } finally {
            for (String fixture : fixturePreferences) target.deleteSharedPreferences(fixture);
            assertTrue(modelStorage.delete());
        }
    }

    private static void verifyCheckpointModel(String originalModelId, SpeechModel expectedModel) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("transcription-checkpoint-", ".json", context.getCacheDir());
        try {
            JSONObject original = new JSONObject()
                    .put("projectUri", "content://example/recording-project")
                    .put("projectId", "project-1")
                    .put("mediaId", "audio-1")
                    .put("sha256", "abc")
                    .put("resumeAtUs", 10_000_000L)
                    .put("priorText", "The recording begins.");
            if (originalModelId != null) original.put("modelId", originalModelId);
            Files.write(file.toPath(), original.toString().getBytes(StandardCharsets.UTF_8));

            // Exercise the private queue path without scheduling a real transcription.
            Method read = AudioTranscriptionJob.class.getDeclaredMethod("read", File.class);
            Method checkpoint = AudioTranscriptionJob.class.getDeclaredMethod("checkpoint", File.class,
                    AudioTranscriptionJob.Request.class, long.class, String.class);
            read.setAccessible(true);
            checkpoint.setAccessible(true);
            Object request = read.invoke(null, file);
            String resumedText = "The recording begins. The recording continues.";
            checkpoint.invoke(null, file, request, 20_000_000L, resumedText);

            JSONObject updated = ProjectJson.parseObject(new String(new AtomicFile(file).readFully(),
                    StandardCharsets.UTF_8));
            assertEquals(expectedModel.id, updated.getString("modelId"));
            assertEquals(20_000_000L, updated.getLong("resumeAtUs"));
            assertEquals(resumedText, updated.getString("priorText"));
        } finally {
            new AtomicFile(file).delete();
        }
    }
}
