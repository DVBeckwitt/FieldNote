package com.fieldnote.app;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Base64;

import com.fieldnote.watchlink.WatchTransfer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Receives a finalized, paired watch original into the normal local publication queue. */
final class WatchRecordingImporter {
    private static final long MAX_DURATION_MS = 24L * 60 * 60 * 1000;
    private static final Object IMPORT_LOCK = new Object();
    private static final String RECEIPTS_DIRECTORY = "watch-recording-receipts";

    private WatchRecordingImporter() {}

    /** Runs on a worker. A return means local durability. The ZIP publishes independently. */
    static void importRecording(Context context, String clipId, long startedAtMs, long endedAtMs,
                                long byteCount, InputStream audio) throws IOException {
        UUID id = canonicalId(clipId);
        if (startedAtMs <= 0 || endedAtMs <= startedAtMs || endedAtMs - startedAtMs > MAX_DURATION_MS) {
            throw new IOException("The watch recording has invalid capture times.");
        }
        if (byteCount < 8 || byteCount > WatchTransfer.MAX_BYTES) {
            throw new IOException("The watch recording exceeds the supported size or is empty.");
        }

        synchronized (IMPORT_LOCK) {
            ProjectStore store = new ProjectStore(context);
            File directory = new File(context.getFilesDir(), RECEIPTS_DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("The phone could not open the watch recording inbox.");
            }
            AtomicFile receiptFile = new AtomicFile(new File(directory, id + ".json"));
            File received = new File(directory, "incoming.m4a");
            try {
                JSONObject receipt = readReceipt(receiptFile);
                Uri project = receipt == null ? store.currentProject() : Uri.parse(receipt.getString("projectUri"));
                if (project == null || !ContentResolver.SCHEME_CONTENT.equals(project.getScheme())) {
                    throw new IOException("Select a Fieldnote project on the phone first.");
                }
                if (receipt == null) {
                    // Bind before reading the body so even a partly received clip keeps
                    // its destination if the phone changes projects while disconnected.
                    receipt = new JSONObject().put("projectUri", project.toString())
                            .put("startedAtMs", startedAtMs).put("endedAtMs", endedAtMs)
                            .put("bytes", byteCount).put("accepted", false);
                    writeReceipt(receiptFile, receipt);
                } else {
                    verifyHeader(receipt, startedAtMs, endedAtMs, byteCount);
                }

                boolean accepted = receipt.getBoolean("accepted");
                String fingerprint = receive(accepted ? null : received, audio, byteCount);
                if ((accepted || receipt.has("fingerprint")) && !receipt.getString("fingerprint").equals(fingerprint)) {
                    throw new IOException("The watch reused a recording ID with different audio.");
                }
                if (!accepted) {
                    long encodedDurationMs = validateAudio(received);
                    receipt.put("fingerprint", fingerprint).put("encodedDurationMs", encodedDurationMs);
                    writeReceipt(receiptFile, receipt);
                    store.adoptWatchRecording(id, received);
                    // Commit the receipt before exposing a finalized journal to the
                    // publisher. After interruption, either the original remains to
                    // stage or it was already published and must never be recreated.
                    writeReceipt(receiptFile, receipt.put("accepted", true));
                }
                JSONObject metadata = new JSONObject().put("source", "wear-os")
                        .put("encodedDurationMs", receipt.getLong("encodedDurationMs"));
                store.publishWatchRecording(project, id, startedAtMs, endedAtMs, metadata);
            } catch (JSONException failure) {
                throw new IOException("The watch recording receipt is unreadable.", failure);
            } finally {
                if (received.exists()) received.delete();
            }
        }
    }

    private static UUID canonicalId(String value) throws IOException {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equals(value)) return parsed;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Reject abbreviated, malformed, or missing IDs before touching a file.
        }
        throw new IOException("The watch recording ID is invalid.");
    }

    private static String receive(File target, InputStream audio, long byteCount) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable.", impossible); }
        try (FileOutputStream output = target == null ? null : new FileOutputStream(target)) {
            DataInputStream input = new DataInputStream(audio);
            byte[] buffer = new byte[64 * 1024];
            long remaining = byteCount;
            while (remaining > 0) {
                int count = (int) Math.min(remaining, buffer.length);
                input.readFully(buffer, 0, count);
                if (output != null) output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                remaining -= count;
            }
            if (output != null) output.getFD().sync();
        }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
    }

    private static long validateAudio(File file) throws IOException {
        try (DataInputStream header = new DataInputStream(new FileInputStream(file))) {
            header.readInt();
            if (header.readInt() != 0x66747970) throw new IOException("The watch recording is not an M4A original.");
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if (extractor.getTrackCount() == 1) {
                MediaFormat format = extractor.getTrackFormat(0);
                if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(format.getString(MediaFormat.KEY_MIME))
                        && format.containsKey(MediaFormat.KEY_DURATION)) {
                    long durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000;
                    extractor.selectTrack(0);
                    // AAC priming can put the first valid sample before time zero.
                    if (durationMs > 0 && extractor.getSampleTrackIndex() == 0) return durationMs;
                }
            }
            throw new IOException("The watch recording has no finalized AAC audio track.");
        } catch (RuntimeException failure) {
            throw new IOException("The watch recording could not be opened.", failure);
        } finally {
            extractor.release();
        }
    }

    private static JSONObject readReceipt(AtomicFile file) throws IOException, JSONException {
        try { return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)); }
        catch (FileNotFoundException missing) {
            if (file.getBaseFile().exists()) throw missing;
            return null;
        }
    }

    private static void verifyHeader(JSONObject receipt, long startedAt, long endedAt,
                                     long byteCount) throws IOException, JSONException {
        if (receipt.getLong("startedAtMs") != startedAt || receipt.getLong("endedAtMs") != endedAt
                || receipt.getLong("bytes") != byteCount) {
            throw new IOException("The watch reused a recording ID with different size or timestamps.");
        }
    }

    private static void writeReceipt(AtomicFile file, JSONObject receipt) throws IOException {
        FileOutputStream output = file.startWrite();
        byte[] serialized = receipt.toString().getBytes(StandardCharsets.UTF_8);
        try {
            output.write(serialized);
            file.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(output);
            throw failure;
        }
        if (!java.util.Arrays.equals(serialized, file.readFully())) {
            throw new IOException("The phone could not preserve the watch recording receipt.");
        }
    }
}
