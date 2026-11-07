package com.fieldnote.wear;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class WatchRecordingQueue {
    private static final String DIRECTORY = "pending-recordings";
    private static final String EXTENSION = ".m4a";

    record Capture(UUID id, long startedAtMillis, long endedAtMillis, File file) { }

    private WatchRecordingQueue() { }

    static File begin(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create watch recording directory");
        }
        // A failed final rename or interrupted recorder must never be overwritten
        // by the next session. Keep its original available for recovery.
        return File.createTempFile("recording-", ".part", directory);
    }

    static Capture commit(File temporary, UUID id, long startedAtMillis, long endedAtMillis) throws IOException {
        File completed = new File(temporary.getParentFile(),
                id + "_" + startedAtMillis + "_" + endedAtMillis + EXTENSION);
        if (temporary.length() == 0 || !temporary.renameTo(completed)) {
            throw new IOException("Cannot finalize watch recording");
        }
        return new Capture(id, startedAtMillis, endedAtMillis, completed);
    }

    static List<Capture> pending(Context context) {
        List<Capture> captures = new ArrayList<>();
        File directory = new File(context.getFilesDir(), DIRECTORY);
        File[] files = directory.listFiles();
        if (files == null) return captures;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.endsWith(EXTENSION)) continue;
            String[] fields = name.substring(0, name.length() - EXTENSION.length()).split("_");
            if (fields.length != 3) continue;
            try {
                UUID id = UUID.fromString(fields[0]);
                long start = Long.parseLong(fields[1]);
                long end = Long.parseLong(fields[2]);
                if (start > 0 && end >= start) captures.add(new Capture(id, start, end, file));
            } catch (IllegalArgumentException ignored) {
                // A file outside the queue format cannot be delivered as a capture.
            }
        }
        captures.sort(Comparator.comparingLong(Capture::startedAtMillis));
        return captures;
    }
}
