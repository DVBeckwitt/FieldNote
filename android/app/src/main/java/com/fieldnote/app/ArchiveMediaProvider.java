package com.fieldnote.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only, individually grantable playback of checksum-verified archive originals. */
public final class ArchiveMediaProvider extends ContentProvider {
    static File cacheDirectory(Context context) { return new File(context.getCacheDir(), "archive-media"); }

    static Uri uriForFile(Context context, File file, String originalName) throws IOException {
        File base = cacheDirectory(context).getCanonicalFile();
        if (!base.equals(file.getCanonicalFile().getParentFile())) throw new IOException("Media cache path is invalid.");
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".media")
                .appendPath("media").appendPath(file.getName()).appendQueryParameter("name", originalName).build();
    }

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            Context context = getContext();
            if (context == null || !(context.getPackageName() + ".media").equals(uri.getAuthority())) throw new IOException("Wrong media provider.");
            List<String> parts = uri.getPathSegments();
            if (parts.size() != 2 || !"media".equals(parts.get(0))
                    || !parts.get(1).matches("[a-f0-9]{64}\\.(m4a|jpg|mp4)")) throw new IOException("Invalid media path.");
            File base = cacheDirectory(context).getCanonicalFile();
            File file = new File(base, parts.get(1)).getCanonicalFile();
            if (!base.equals(file.getParentFile()) || !file.isFile()) throw new IOException("The playback copy is no longer available. Reopen it from the project.");
            return file;
        } catch (IOException failure) {
            FileNotFoundException error = new FileNotFoundException(failure.getMessage());
            error.initCause(failure);
            throw error;
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Archive media is read-only.");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        return openFile(uri, mode);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            MatrixCursor.RowBuilder row = cursor.newRow();
            String name = uri.getQueryParameter("name");
            for (String column : columns) row.add(OpenableColumns.DISPLAY_NAME.equals(column)
                    ? (name == null ? file.getName() : name) : OpenableColumns.SIZE.equals(column) ? file.length() : null);
            return cursor;
        } catch (FileNotFoundException failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null && name.endsWith(".m4a")) return "audio/mp4";
        if (name != null && name.endsWith(".jpg")) return "image/jpeg";
        if (name != null && name.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only media"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException("Read-only media"); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException("Read-only media"); }
}
