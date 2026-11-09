package com.fieldnote.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-exported debug fixture for exercising the real ContentResolver/SAF path.
 *
 * DocumentsProvider enforces exported=true at startup, so this deliberately
 * implements only its documented document data and AOSP contract call protocol
 * as a private ContentProvider. It is not advertised in Android's folder picker.
 * There is no test provider or failure injection in a release APK.
 */
public final class TestDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.fieldnote.app.testdocuments";
    private static final String ROOT_ID = "root";
    private static final AtomicInteger FAIL_CANONICAL_INDEX_WRITES = new AtomicInteger();
    private static final AtomicReference<String> FAIL_WORKING_INDEX_WRITE_AFTER_TRUNCATE = new AtomicReference<>();
    private static final AtomicInteger FAIL_ZIP_WRITES = new AtomicInteger();
    private static final AtomicInteger FAIL_ZIP_WRITES_AFTER_TRUNCATE = new AtomicInteger();
    private static final AtomicReference<ReadFailures> FAIL_ZIP_READS = new AtomicReference<>();
    private static final AtomicReference<WriteBarrier> NEXT_WRITE = new AtomicReference<>();
    private static final Set<WriteBarrier> WRITE_BARRIERS = ConcurrentHashMap.newKeySet();
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private File root;

    @Override public boolean onCreate() {
        root = rootDirectory(getContext());
        return root.isDirectory() || root.mkdirs();
    }

    public static File rootDirectory(Context context) {
        return new File(context.getCacheDir(), "fieldnote-test-documents");
    }

    public static Uri rootTree() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    /** Fails before truncating the canonical index; recovery files remain real. */
    public static void failNextCanonicalIndexWrite() { FAIL_CANONICAL_INDEX_WRITES.set(1); }
    public static void failNextZipWrite() { FAIL_ZIP_WRITES.set(1); }
    /** Models a provider that truncates the archive and then fails the write. */
    public static void failNextZipWriteAfterTruncate() { FAIL_ZIP_WRITES_AFTER_TRUNCATE.set(1); }
    /** Models a provider failure while replacing the working project's index. */
    public static void failNextWorkingIndexWriteAfterTruncate(Uri project) {
        if (project == null || !AUTHORITY.equals(project.getAuthority()))
            throw new IllegalArgumentException("Select a project in the private test provider");
        FAIL_WORKING_INDEX_WRITE_AFTER_TRUNCATE.set(DocumentsContract.getDocumentId(project) + "/index.pending.json");
    }

    /** Models temporary read/access failure without denying writes or changing stored bytes. */
    public static void failZipReads(Uri archive, int count) {
        if (archive == null || !AUTHORITY.equals(archive.getAuthority()) || count < 1)
            throw new IllegalArgumentException("Select a private test ZIP and a positive failure count");
        FAIL_ZIP_READS.set(new ReadFailures(DocumentsContract.getDocumentId(archive), count));
    }

    private static final class ReadFailures {
        final String documentId;
        final AtomicInteger remaining;
        ReadFailures(String documentId, int count) {
            this.documentId = documentId;
            remaining = new AtomicInteger(count);
        }
    }

    /** Blocks one matching ZIP open before truncation; never holds any provider lock. */
    public static WriteBarrier blockNextZipWrite(Uri archive) {
        return blockNextWrite(archive, false);
    }

    public static WriteBarrier blockNextWorkingIndexWrite(Uri project) {
        return blockNextWrite(project, true);
    }

    private static WriteBarrier blockNextWrite(Uri document, boolean index) {
        if (document == null || !AUTHORITY.equals(document.getAuthority()))
            throw new IllegalArgumentException("Select a document in the private test provider");
        WriteBarrier barrier = new WriteBarrier(DocumentsContract.getDocumentId(document), index);
        WRITE_BARRIERS.add(barrier);
        if (!NEXT_WRITE.compareAndSet(null, barrier)) {
            WRITE_BARRIERS.remove(barrier);
            throw new IllegalStateException("A document write barrier is already armed");
        }
        return barrier;
    }

    public static final class WriteBarrier implements AutoCloseable {
        private final String documentId;
        private final boolean index;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean timedOut;

        private WriteBarrier(String documentId, boolean index) {
            this.documentId = documentId;
            this.index = index;
        }

        public boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        public boolean isBlocked() { return entered.getCount() == 0 && released.getCount() != 0; }
        public boolean isReleased() { return released.getCount() == 0; }
        public boolean timedOut() { return timedOut; }

        public void release() {
            released.countDown();
            NEXT_WRITE.compareAndSet(this, null);
            if (entered.getCount() != 0) WRITE_BARRIERS.remove(this);
        }

        @Override public void close() { release(); }

        private void pause() throws IOException {
            entered.countDown();
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    timedOut = true;
                    released.countDown();
                    throw new IOException("Test document write barrier timed out without being released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Test ZIP write barrier was interrupted", interrupted);
            } finally {
                released.countDown();
                WRITE_BARRIERS.remove(this);
            }
        }
    }

    public static void clearFailures() {
        FAIL_CANONICAL_INDEX_WRITES.set(0);
        FAIL_WORKING_INDEX_WRITE_AFTER_TRUNCATE.set(null);
        FAIL_ZIP_WRITES.set(0);
        FAIL_ZIP_WRITES_AFTER_TRUNCATE.set(0);
        FAIL_ZIP_READS.set(null);
        WriteBarrier armed = NEXT_WRITE.getAndSet(null);
        if (armed != null) armed.release();
        for (WriteBarrier barrier : WRITE_BARRIERS) barrier.release();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File document = resolve(documentId(uri));
            MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
            if ("children".equals(uri.getLastPathSegment())) {
                if (!document.isDirectory()) throw new FileNotFoundException("Not a directory");
                File[] children = document.listFiles();
                if (children == null) throw new FileNotFoundException("Directory unavailable");
                Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
                for (File child : children) addRow(cursor, child);
            } else {
                if (!document.exists()) throw new FileNotFoundException("Document unavailable");
                addRow(cursor, document);
            }
            return cursor;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Test document unavailable", invalid);
        }
    }

    @Override public String getType(Uri uri) {
        try { return mime(resolve(documentId(uri))); }
        catch (IOException unavailable) { return null; }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            File file = resolve(documentId(uri));
            if (file.isDirectory()) throw new FileNotFoundException("Cannot open directory as a file");
            ReadFailures readFailures = FAIL_ZIP_READS.get();
            if (!mode.contains("w") && file.getName().endsWith(".zip") && readFailures != null
                    && readFailures.documentId.equals(documentId(uri))
                    && readFailures.remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
                throw new FileNotFoundException("Injected temporary ZIP read failure");
            if (mode.contains("w") && file.getName().equals("index.json")
                    && FAIL_CANONICAL_INDEX_WRITES.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new FileNotFoundException("Injected canonical-index write failure");
            }
            if (mode.contains("w") && file.getName().equals("index.pending.json")) {
                String id = documentId(uri);
                pauseNextWrite(id, true);
                String failingIndex = FAIL_WORKING_INDEX_WRITE_AFTER_TRUNCATE.get();
                if (id.equals(failingIndex) && FAIL_WORKING_INDEX_WRITE_AFTER_TRUNCATE.compareAndSet(failingIndex, null))
                    truncateAndFail(file, "working index");
            }
            if (mode.contains("w") && file.getName().endsWith(".zip")) {
                pauseNextWrite(documentId(uri), false);
                if (FAIL_ZIP_WRITES.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
                    throw new FileNotFoundException("Injected ZIP publication failure before opening");
                if (FAIL_ZIP_WRITES_AFTER_TRUNCATE.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
                    truncateAndFail(file, "ZIP");
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        } catch (IOException invalid) {
            FileNotFoundException error = new FileNotFoundException(invalid.getMessage());
            error.initCause(invalid);
            throw error;
        }
    }

    private static void pauseNextWrite(String documentId, boolean index) throws IOException {
        WriteBarrier barrier = NEXT_WRITE.get();
        if (barrier == null || barrier.index != index) return;
        String expected = index ? barrier.documentId + "/index.pending.json" : barrier.documentId;
        if (expected.equals(documentId) && NEXT_WRITE.compareAndSet(barrier, null)) barrier.pause();
    }

    private static void truncateAndFail(File file, String kind) throws IOException {
        try (FileOutputStream partial = new FileOutputStream(file, false)) {
            partial.write("incomplete provider write".getBytes(StandardCharsets.UTF_8));
        }
        throw new FileNotFoundException("Injected " + kind + " publication failure after truncation");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        return openFile(uri, mode);
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        // Names are the AOSP DocumentsContract call protocol. No hidden API or
        // reflection is used, and the provider is accessible only to this UID.
        if (extras == null) throw new IllegalArgumentException("Missing document parameters");
        Uri uri = extras.getParcelable("uri");
        if (uri == null) throw new IllegalArgumentException("Missing document URI");
        Bundle result = new Bundle();
        try {
            File document = resolve(documentId(uri));
            if ("android:createDocument".equals(method)) {
                if (!document.isDirectory()) throw new IOException("Parent is not a directory");
                String name = safeName(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                File created = new File(document, name);
                requireInside(created);
                if (created.exists()) throw new IOException("Name already exists");
                String type = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
                boolean success = DocumentsContract.Document.MIME_TYPE_DIR.equals(type)
                        ? created.mkdir() : created.createNewFile();
                if (!success) throw new IOException("Could not create document");
                result.putParcelable("uri", resultUri(uri, idFor(created)));
                return result;
            }
            if ("android:renameDocument".equals(method)) {
                if (document.equals(root.getCanonicalFile())) throw new IOException("Cannot rename root");
                File target = new File(document.getParentFile(), safeName(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)));
                requireInside(target);
                if (target.exists() || !document.renameTo(target)) throw new IOException("Could not rename document");
                result.putParcelable("uri", resultUri(uri, idFor(target)));
                return result;
            }
            if ("android:deleteDocument".equals(method)) {
                if (document.equals(root.getCanonicalFile())) throw new IOException("Cannot delete root");
                deleteTree(document);
                return result;
            }
            if ("android:isChildDocument".equals(method)) {
                Uri childUri = extras.getParcelable("android.content.extra.TARGET_URI");
                File child = childUri == null ? null : resolve(documentId(childUri));
                result.putBoolean("result", child != null && child.getCanonicalPath().startsWith(document.getCanonicalPath() + File.separator));
                return result;
            }
            throw new UnsupportedOperationException("Unsupported test document operation: " + method);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Test document operation failed", invalid);
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }

    private String documentId(Uri uri) {
        if (!AUTHORITY.equals(uri.getAuthority())) throw new SecurityException("Wrong test authority");
        try { return DocumentsContract.getDocumentId(uri); }
        catch (IllegalArgumentException treeOnly) { return DocumentsContract.getTreeDocumentId(uri); }
    }

    private Uri resultUri(Uri original, String documentId) {
        return DocumentsContract.isTreeUri(original)
                ? DocumentsContract.buildDocumentUriUsingTree(original, documentId)
                : DocumentsContract.buildDocumentUri(AUTHORITY, documentId);
    }

    private File resolve(String id) throws IOException {
        if (ROOT_ID.equals(id)) return root.getCanonicalFile();
        if (!id.startsWith(ROOT_ID + ":")) throw new IOException("Invalid document ID");
        String relative = id.substring(ROOT_ID.length() + 1);
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\")) throw new IOException("Invalid document path");
        for (String component : relative.split("/", -1)) safeName(component);
        File file = new File(root, relative).getCanonicalFile();
        requireInside(file);
        return file;
    }

    private String idFor(File file) throws IOException {
        File checked = file.getCanonicalFile();
        File base = root.getCanonicalFile();
        if (checked.equals(base)) return ROOT_ID;
        requireInside(checked);
        return ROOT_ID + ":" + checked.getPath().substring(base.getPath().length() + 1).replace(File.separatorChar, '/');
    }

    private void requireInside(File file) throws IOException {
        String base = root.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) throw new IOException("Path leaves test root");
    }

    private static String safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid document name");
        }
        return name;
    }

    private void addRow(MatrixCursor cursor, File file) throws IOException {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) value = idFor(file);
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) value = file.getName();
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) value = mime(file);
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) value = file.isDirectory() ? null : file.length();
            else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) value = file.lastModified();
            else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) value = file.isDirectory()
                    ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                    : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
            row.add(value);
        }
    }

    private static String mime(File file) {
        if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String name = file.getName();
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private void deleteTree(File file) throws IOException {
        requireInside(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list directory for deletion");
            for (File child : children) deleteTree(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Could not delete test document");
    }
}
