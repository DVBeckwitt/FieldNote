package com.fieldnote.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class SpeechModelsInstrumentedTest {
    @Test public void installsBundledSmallFromIndividualAssetsOnFreshStorage() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path storage = Files.createTempDirectory(app.getCacheDir().toPath(), "small-model-");
        try {
            Context context = modelStorage(app, storage);
            File model = SpeechModels.directory(context, SpeechModel.SMALL);
            assertEquals(storage.resolve(SpeechModel.SMALL.id).toFile(), model);
            assertTrue(new File(model, "am/final.mdl").isFile());
            assertTrue(new File(model, "graph/HCLr.fst").isFile());
            // Vosk must recognize the asset tree as a complete model, not just as copied files.
            try (OfflineTranscriber ignored = new OfflineTranscriber(context, SpeechModel.SMALL)) {
                assertTrue(SpeechModels.isReady(context, SpeechModel.SMALL));
            }
        } finally { deleteFixture(storage); }
    }

    @Test public void rejectsAnIncompleteFolderWithoutInstallingOrSelectingIt() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path storage = Files.createTempDirectory(app.getCacheDir().toPath(), "model-storage-");
        Path source = Files.createTempDirectory(TestDocumentsProvider.rootDirectory(app).toPath(), "wrong-model-");
        try {
            Context context = modelStorage(app, storage);
            Uri tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root:" + source.getFileName());
            IOException error = assertThrows(IOException.class,
                    () -> SpeechModels.install(context, SpeechModel.LGRAPH, tree, percent -> {}));
            assertTrue(error.getMessage(), error.getMessage().contains("do not match the official model"));
            assertFalse(SpeechModels.isReady(context, SpeechModel.LGRAPH));
            assertEquals(SpeechModel.SMALL, SpeechModels.selected(context));
            assertFalse(Files.exists(storage.resolve(SpeechModel.LGRAPH.id + ".pending")));
        } finally {
            deleteFixture(source);
            deleteFixture(storage);
        }
    }

    @Test public void abandonedLargeModelFallsBackUntilExplicitlySelectedAgain() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(SpeechModels.isSupported(app, SpeechModel.FULL));
        Path storage = Files.createTempDirectory(app.getCacheDir().toPath(), "large-model-");
        String settings = storage.getFileName().toString();
        Context context = new ContextWrapper(app) {
            @Override public File getNoBackupFilesDir() { return storage.toFile(); }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return app.getSharedPreferences(settings, mode);
            }
        };
        try {
            Files.createDirectory(storage.resolve(SpeechModel.FULL.id));
            SpeechModels.select(context, SpeechModel.FULL);
            SpeechModels.quarantine(context, SpeechModel.FULL);
            assertEquals(SpeechModel.SMALL, SpeechModels.selected(context));

            SpeechModels.select(context, SpeechModel.FULL);
            assertEquals(SpeechModel.FULL, SpeechModels.selected(context));
            assertFalse(SpeechModels.isQuarantined(context, SpeechModel.FULL));
        } finally {
            app.deleteSharedPreferences(settings);
            deleteFixture(storage);
        }
    }

    private static Context modelStorage(Context app, Path storage) {
        return new ContextWrapper(app) {
            @Override public File getNoBackupFilesDir() { return storage.toFile(); }
        };
    }

    private static void deleteFixture(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path entry : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator) Files.delete(entry);
        }
    }
}
