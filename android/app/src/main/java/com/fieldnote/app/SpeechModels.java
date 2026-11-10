package com.fieldnote.app;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.zip.CRC32;

/** Stores only bundled or verified speech models in the app's private storage. */
final class SpeechModels {
    private static final String PREFERENCE = "transcription_model";
    private static final String QUARANTINED_PREFIX = "transcription_model_load_failed:";
    private static final String BUNDLED_ASSET = "offline-speech-model";
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
    };
    private static final long SPACE_RESERVE = 16L * 1024 * 1024;
    private static final AtomicBoolean CLEANUP_STARTED = new AtomicBoolean();

    private SpeechModels() {}

    static SpeechModel selected(Context context) {
        SpeechModel saved = SpeechModel.fromId(preferences(context).getString(PREFERENCE, SpeechModel.SMALL.id));
        return saved != null && isReady(context, saved) && isSupported(context, saved)
                && !isQuarantined(context, saved) ? saved : SpeechModel.SMALL;
    }

    static void select(Context context, SpeechModel model) {
        if (!isReady(context, model)) throw new IllegalStateException("Install the speech model first.");
        if (!isSupported(context, model)) throw new IllegalStateException("This phone cannot run that model.");
        preferences(context).edit().putString(PREFERENCE, model.id).remove(QUARANTINED_PREFIX + model.id).apply();
    }

    static boolean isSupported(Context context, SpeechModel model) {
        if (!model.isLarge()) return true;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (!android.os.Process.is64Bit() || manager == null || manager.isLowRamDevice()) return false;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        return memory.totalMem - memory.threshold >= model.installedBytes;
    }

    static boolean isQuarantined(Context context, SpeechModel model) {
        return model.isLarge() && preferences(context).getBoolean(QUARANTINED_PREFIX + model.id, false);
    }

    static void quarantine(Context context, SpeechModel model) throws IOException {
        if (!preferences(context).edit().putBoolean(QUARANTINED_PREFIX + model.id, true).commit()) {
            throw new IOException("Could not save the speech model load failure.");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
    }

    static boolean isReady(Context context, SpeechModel model) {
        return model == SpeechModel.SMALL || Files.isDirectory(installedPath(context, model));
    }

    static void clearInterruptedInstalls(Context context) {
        if (!CLEANUP_STARTED.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            for (SpeechModel model : SpeechModel.values()) {
                synchronized (model) {
                    try { deleteStaging(stagingPath(app, model)); }
                    catch (IOException ignored) { /* A subsequent install can retry cleanup. */ }
                }
            }
        }, "Fieldnote-model-cleanup").start();
    }

    static File directory(Context context, SpeechModel model) throws IOException {
        synchronized (model) {
            Path installed = installedPath(context, model);
            if (Files.isDirectory(installed)) return installed.toFile();
            if (model != SpeechModel.SMALL) throw new IOException("The selected speech model is unavailable.");
            stage(context, model, target -> copyAssets(context.getAssets(), BUNDLED_ASSET, target));
            return installed.toFile();
        }
    }

    /** Copies the expected files from a folder selected in Android's system picker. */
    static void install(Context context, SpeechModel model, Uri tree, IntConsumer progress) throws IOException {
        if (!isSupported(context, model)) throw new IOException("This phone cannot run that speech model.");
        if (model == SpeechModel.SMALL || tree == null || !DocumentsContract.isTreeUri(tree)
                || !ContentResolver.SCHEME_CONTENT.equals(tree.getScheme())) {
            throw new IOException("Choose the extracted model folder in the Android file picker.");
        }
        synchronized (model) {
            if (isReady(context, model)) return;
            try {
                stage(context, model, target -> copyModelFolder(context, model, tree, target, progress));
            } catch (ModelInstallException | InterruptedIOException expected) {
                throw expected;
            } catch (IOException | RuntimeException failure) {
                throw new IOException("Could not install the speech model. Check that the folder is accessible and the phone has enough free storage.", failure);
            }
        }
        progress.accept(100);
    }

    private static Path installedPath(Context context, SpeechModel model) {
        return context.getNoBackupFilesDir().toPath().resolve(model.id);
    }

    private static Path stagingPath(Context context, SpeechModel model) {
        return installedPath(context, model).resolveSibling(model.id + ".pending");
    }

    private static void stage(Context context, SpeechModel model, StagedCopy copy) throws IOException {
        Path installed = installedPath(context, model);
        Path staging = stagingPath(context, model);
        // An install interrupted by process death leaves only this private staging directory.
        deleteStaging(staging);
        if (installed.getParent().toFile().getUsableSpace() < model.installedBytes + SPACE_RESERVE) {
            throw new ModelInstallException("Free enough phone storage for the installed model and try again.");
        }
        Files.createDirectory(staging);
        try {
            copy.into(staging);
            checkInterrupted();
            Files.move(staging, installed, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            try { deleteStaging(staging); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void copyAssets(AssetManager assets, String source, Path target) throws IOException {
        String[] children = assets.list(source);
        if (children == null || children.length == 0) throw new IOException("The bundled speech model is missing.");
        for (String child : children) {
            checkInterrupted();
            String asset = source + "/" + child;
            Path file = target.resolve(child);
            String[] nested = assets.list(asset);
            if (nested != null && nested.length > 0) {
                Files.createDirectory(file);
                copyAssets(assets, asset, file);
            } else {
                try (InputStream input = assets.open(asset); OutputStream output = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = input.read(buffer)) != -1; ) {
                        checkInterrupted();
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private static void copyModelFolder(Context context, SpeechModel model, Uri tree, Path target,
                                        IntConsumer progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Map<String, Map<String, ModelDocument>> folders = new HashMap<>();
        long copied = 0;
        progress.accept(0);
        String manifest = "speech-model-manifests/" + model.id + ".tsv";
        try (BufferedReader entries = new BufferedReader(new InputStreamReader(
                context.getAssets().open(manifest), StandardCharsets.UTF_8))) {
            for (String line; (line = entries.readLine()) != null; ) {
                checkInterrupted();
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) throw new IOException("Invalid model manifest.");
                Path file = target.resolve(fields[0]).normalize();
                if (file.equals(target) || !file.startsWith(target)) throw new IOException("Invalid model path.");
                long size = Long.parseLong(fields[1]);
                if (size < 0 || size > model.installedBytes - copied) throw new IOException("Invalid model size.");
                Uri source = resolve(resolver, tree, rootId, fields[0], folders);
                Files.createDirectories(file.getParent());
                long previous = copied;
                try (InputStream input = resolver.openInputStream(source);
                     OutputStream output = Files.newOutputStream(file)) {
                    if (input == null) throw wrongModel();
                    copyVerified(input, output, size, Long.parseUnsignedLong(fields[2], 16), part -> {
                        int percent = (int) Math.min(99, (previous + part) * 100 / model.installedBytes);
                        progress.accept(percent);
                    });
                }
                copied += size;
            }
        }
        if (copied != model.installedBytes) throw new IOException("Incomplete model manifest.");
    }

    private static Uri resolve(ContentResolver resolver, Uri tree, String rootId, String relative,
                               Map<String, Map<String, ModelDocument>> folders) throws IOException {
        String[] segments = relative.split("/");
        String parent = rootId;
        for (int index = 0; index < segments.length; index++) {
            Map<String, ModelDocument> children = folders.get(parent);
            if (children == null) {
                children = listChildren(resolver, tree, parent);
                folders.put(parent, children);
            }
            ModelDocument child = children.get(segments[index]);
            if (child == null || child.directory != (index < segments.length - 1)) throw wrongModel();
            parent = child.id;
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, parent);
    }

    private static Map<String, ModelDocument> listChildren(ContentResolver resolver, Uri tree, String parent)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        Map<String, ModelDocument> result = new HashMap<>();
        try (Cursor cursor = resolver.query(children, DOCUMENT_COLUMNS, null, null, null)) {
            if (cursor == null) throw wrongModel();
            int rows = 0;
            while (cursor.moveToNext()) {
                checkInterrupted();
                if (++rows > 2_048) throw wrongModel();
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (id == null || name == null || result.containsKey(name)) throw wrongModel();
                result.put(name, new ModelDocument(id, Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
            }
        }
        return result;
    }

    static void copyVerified(InputStream input, OutputStream output, long expectedBytes, long expectedChecksum,
                             LongConsumer progress) throws IOException {
        // The official archive's member checksums detect damaged or incorrect copies.
        CRC32 checksum = new CRC32();
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) != -1; ) {
            checkInterrupted();
            if (count > expectedBytes - copied) throw wrongModel();
            checksum.update(buffer, 0, count);
            output.write(buffer, 0, count);
            copied += count;
            progress.accept(copied);
        }
        if (copied != expectedBytes || checksum.getValue() != expectedChecksum) throw wrongModel();
    }

    private static ModelInstallException wrongModel() {
        return new ModelInstallException("These files do not match the official model. Extract the original download and choose its model folder.");
    }

    private static void deleteStaging(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Model installation was interrupted.");
    }

    private interface StagedCopy { void into(Path target) throws IOException; }
    private record ModelDocument(String id, boolean directory) {}

    private static final class ModelInstallException extends IOException {
        ModelInstallException(String message) { super(message); }
    }
}
