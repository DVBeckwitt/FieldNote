package com.fieldnote.app;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Fixed synthetic notebook workload; optional timings are evidence, never a flaky speed gate. */
@RunWith(AndroidJUnit4.class)
public final class NoteEntriesPerformanceInstrumentedTest {
    private static final long BASE_TIME = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli();
    private static volatile int resultSink;

    @Test public void largeNotebookKeepsOrderReferencesAndSearchResults() throws Exception {
        boolean benchmark = "true".equals(InstrumentationRegistry.getArguments().getString("fieldnoteBenchmark"));
        for (int count : new int[]{100, 1000}) {
            JSONObject project = fixture(count);
            String before = project.toString();
            List<JSONObject> ascending = NoteEntries.snapshot(project, null, false);
            List<JSONObject> descending = NoteEntries.snapshot(project, null, true);
            assertEquals(count, ascending.size());
            for (int i = 0; i < count; i++) {
                assertEquals("entry-" + i, ascending.get(i).getString("id"));
                assertSame("Sorting retains the exact original entry objects", ascending.get(i), descending.get(count - 1 - i));
                JSONObject entry = ascending.get(i);
                assertTrue(NoteEntries.matchesNote(entry, " \t\r\n", "", false));
                assertEquals(i % 7 == 0, NoteEntries.matchesNote(entry, "needle", "", false));
                assertEquals(i % 2 == 0, NoteEntries.matchesNote(entry, "", "LAB", false));
                assertEquals(i % 3 == 0, NoteEntries.matchesNote(entry, "", "", true));
                assertEquals(i % 6 == 0, NoteEntries.matchesNote(entry, " \t", "lab", true));
                assertEquals(i % 3 == 0, NoteEntries.matchesNote(entry, "calibration", "", false));
                assertFalse(NoteEntries.matchesNote(entry, "not-present", "", false));
            }
            assertEquals(before, project.toString());
            if (benchmark) {
                JSONObject timings = new JSONObject().put("entries", count).put("warmups", 2).put("samples", 7)
                        .put("snapshotNs", measure(() -> checksum(NoteEntries.snapshot(project, null, true))))
                        .put("emptyFilterNs", measure(() -> filterCount(ascending, "")))
                        .put("textFilterNs", measure(() -> filterCount(ascending, "needle")));
                Bundle report = new Bundle();
                report.putString("fieldnoteBenchmark", timings.toString());
                InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
            }
        }
    }

    @Test public void equalCaptureTimesKeepStableOrderInBothDirections() throws Exception {
        String time = Instant.ofEpochMilli(BASE_TIME).toString();
        JSONObject first = new JSONObject().put("id", "first").put("createdAt", time);
        JSONObject second = new JSONObject().put("id", "second").put("createdAt", time);
        JSONObject project = new JSONObject().put("entries", new JSONArray().put(first).put(second).put(first));
        assertEquals(Arrays.asList(first, second, first), NoteEntries.snapshot(project, null, false));
        assertEquals(Arrays.asList(first, second, first), NoteEntries.snapshot(project, null, true));
        project.getJSONArray("entries").put(new JSONObject().put("id", "third").put("createdAt", time));
        assertEquals("third", NoteEntries.snapshot(project, null, true).get(0).getString("id"));
        assertSame(second, NoteEntries.snapshot(project, null, false).get(1));
    }

    private static JSONObject fixture(int count) throws Exception {
        ArrayList<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long firstCapture = BASE_TIME + i * 1000L;
            JSONArray media = new JSONArray();
            for (int m = 0; m <= i % 3; m++) {
                JSONObject original = new JSONObject().put("id", "original-" + i + "-" + m)
                        .put("kind", m == 0 ? "audio" : m == 1 ? "photo" : "video")
                        .put("startedAt", Instant.ofEpochMilli(firstCapture + m * 300L).toString())
                        .put("durationMs", m == 0 ? 5000L : 0L);
                if (m == 0 && i % 3 == 0)
                    original.put("markers", new JSONArray().put(new JSONObject().put("label", "Calibration start")));
                media.put(original);
            }
            entries.add(new JSONObject().put("id", "entry-" + i)
                    .put("createdAt", Instant.ofEpochMilli(firstCapture + 2000L).toString())
                    .put("title", "Trial " + i).put("text", "Apparatus observation: temperature stable. "
                            + "The original files and their timestamps remain separate. " + (i % 7 == 0 ? "Needle alignment." : "Position checked."))
                    .put("tags", new JSONArray().put(i % 2 == 0 ? "lab" : "field").put("trial"))
                    .put("media", media));
        }
        Collections.shuffle(entries, new Random(23061));
        JSONArray values = new JSONArray();
        for (JSONObject entry : entries) values.put(entry);
        return new JSONObject().put("entries", values);
    }

    private static int filterCount(List<JSONObject> entries, String query) {
        int matches = 0;
        for (JSONObject entry : entries) if (NoteEntries.matchesNote(entry, query, "", false)) matches++;
        return matches;
    }

    private static int checksum(List<JSONObject> entries) {
        int value = 1;
        for (JSONObject entry : entries) value = 31 * value + entry.optString("id").hashCode();
        return value;
    }

    private static JSONArray measure(Work work) {
        for (int i = 0; i < 2; i++) resultSink = work.run();
        JSONArray samples = new JSONArray();
        for (int i = 0; i < 7; i++) {
            long started = SystemClock.elapsedRealtimeNanos();
            resultSink = work.run();
            samples.put(SystemClock.elapsedRealtimeNanos() - started);
        }
        return samples;
    }

    private interface Work { int run(); }
}
