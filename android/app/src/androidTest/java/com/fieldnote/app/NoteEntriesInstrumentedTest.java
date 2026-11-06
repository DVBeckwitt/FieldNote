package com.fieldnote.app;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Characterizes notebook display rules using Android's actual JSON implementation. */
@RunWith(AndroidJUnit4.class)
public final class NoteEntriesInstrumentedTest {
    private static final String EARLY = "2026-11-05T10:00:00Z";
    private static final String MIDDLE = "2026-11-05T11:00:00Z";
    private static final String LATE = "2026-11-05T12:00:00Z";

    @Test public void pendingMediaMergePreservesSourcesAndCommittedFields() throws Exception {
        JSONObject untouched = entry("untouched", EARLY);
        JSONObject originalAudio = media("audio-1", "audio", EARLY).put("path", "original.m4a");
        JSONObject merged = entry("merged", MIDDLE, originalAudio)
                .put("title", "Committed title")
                .put("futureField", new JSONObject().put("retained", true));
        JSONObject duplicateOnly = entry("duplicate-only", MIDDLE, media("photo-1", "photo", MIDDLE));
        JSONObject project = new JSONObject().put("entries", new JSONArray()
                .put(untouched).put(merged).put(JSONObject.NULL).put("invalid entry").put(duplicateOnly));
        JSONObject incomingPhoto = media("photo-2", "photo", MIDDLE);
        JSONObject newPending = entry("pending-only", LATE, media("video-1", "video", LATE)).put("_pending", true);
        JSONArray pending = new JSONArray()
                .put(entry("merged", LATE,
                        media("audio-1", "audio", LATE).put("path", "must-not-replace.m4a"), incomingPhoto)
                        .put("title", "Must not replace the committed title"))
                .put(entry("duplicate-only", LATE, media("photo-1", "photo", LATE)))
                .put(newPending).put(JSONObject.NULL);
        String projectBefore = project.toString();
        String pendingBefore = pending.toString();

        List<JSONObject> snapshot = NoteEntries.snapshot(project, pending, false);
        assertEquals(4, snapshot.size());
        assertSame("Unchanged committed notes retain their identity", untouched, byId(snapshot, "untouched"));
        JSONObject visible = byId(snapshot, "merged");
        assertNotSame(merged, visible);
        assertEquals("Committed title", visible.getString("title"));
        assertTrue(visible.getJSONObject("futureField").getBoolean("retained"));
        assertEquals(2, visible.getJSONArray("media").length());
        assertEquals("original.m4a", visible.getJSONArray("media").getJSONObject(0).getString("path"));
        assertNotSame(originalAudio, visible.getJSONArray("media").getJSONObject(0));
        assertNotSame(incomingPhoto, visible.getJSONArray("media").getJSONObject(1));
        assertTrue(visible.getBoolean("_pending"));
        assertNotSame(duplicateOnly, byId(snapshot, "duplicate-only"));
        assertFalse("A duplicate staged file alone does not mark a note pending",
                byId(snapshot, "duplicate-only").has("_pending"));
        assertNotSame(newPending, byId(snapshot, "pending-only"));

        visible.getJSONObject("futureField").put("retained", false);
        visible.getJSONArray("media").getJSONObject(1).put("path", "display-only.jpg");
        byId(snapshot, "pending-only").getJSONArray("media").getJSONObject(0).put("path", "display-only.mp4");
        assertEquals("Merging and editing copied notes must not change the saved index", projectBefore, project.toString());
        assertEquals("The staging snapshot must also remain untouched", pendingBefore, pending.toString());
    }

    @Test public void savedFilesExcludePendingAndOrderingKeepsCaptureTimesAndTieRules() throws Exception {
        JSONObject firstTie = entry("first-tie", LATE, media("early-photo", "photo", EARLY));
        JSONObject secondTie = entry("second-tie", EARLY);
        JSONObject invalidTime = entry("invalid-time", "invalid", media("valid-audio", "audio", MIDDLE));
        JSONObject project = new JSONObject().put("entries", new JSONArray().put(firstTie).put(secondTie).put(invalidTime));
        JSONArray pending = new JSONArray()
                .put(entry("first-tie", LATE, media("later-audio", "audio", LATE)))
                .put(entry("pending-only", MIDDLE, media("pending-video", "video", MIDDLE)).put("_pending", true));

        assertEquals(Instant.parse(EARLY).toEpochMilli(), NoteEntries.entryTime(firstTie));
        assertEquals("Invalid creation time retains the zero fallback even with valid media", 0, NoteEntries.entryTime(invalidTime));
        assertEquals("Invalid media time also retains the zero fallback", 0,
                NoteEntries.entryTime(entry("invalid-media", LATE, media("broken-time", "photo", "invalid"))));
        assertIds(NoteEntries.snapshot(project, pending, false), "invalid-time", "first-tie", "second-tie", "pending-only");
        assertIds(NoteEntries.snapshot(project, pending, true), "pending-only", "second-tie", "first-tie", "invalid-time");

        List<JSONObject> savedFiles = NoteEntries.snapshot(project, null, false);
        assertIds(savedFiles, "invalid-time", "first-tie", "second-tie");
        assertSame(firstTie, byId(savedFiles, "first-tie"));
        assertEquals("Files shows only the committed media", 1, byId(savedFiles, "first-tie").getJSONArray("media").length());
        assertFalse(byId(savedFiles, "first-tie").has("_pending"));
        assertTrue(NoteEntries.snapshot(null, null, false).isEmpty());
    }

    @Test public void labelsAndFiltersKeepTitleTextTagsAndAudioMarkerRules() throws Exception {
        JSONObject audio = media("audio-1", "audio", EARLY).put("durationMs", 1000)
                .put("path", "filename-is-not-searchable.m4a")
                .put("markers", new JSONArray().put(new JSONObject().put("label", "Calibration start"))
                        .put(JSONObject.NULL));
        JSONObject video = media("video-1", "video", EARLY)
                .put("markers", new JSONArray().put(new JSONObject().put("label", "Ignored video marker")));
        JSONObject note = entry("note", EARLY, audio, video)
                .put("title", "  Apparatus setup  ")
                .put("text", "First line\nTemperature stable")
                .put("tags", new JSONArray().put("Lab").put("run-2"));

        assertEquals("Apparatus setup", NoteEntries.entryLabel(note));
        assertEquals("#Lab  #run-2", NoteEntries.tagText(note));
        assertEquals("Only audio marker arrays are counted, retaining their recorded length", 2, NoteEntries.markerCount(note));
        assertTrue(NoteEntries.matchesNote(note, "  SETUP\tstable CALIBRATION #lab ", "LAB", true));
        assertTrue(NoteEntries.matchesNote(note, "", "", false));
        assertFalse(NoteEntries.matchesNote(note, "setup missing", "", false));
        assertFalse(NoteEntries.matchesNote(note, "", "other-tag", false));
        assertFalse(NoteEntries.matchesNote(note, "ignored video", "", false));
        assertFalse(NoteEntries.matchesNote(note, "filename-is-not-searchable", "", false));
        assertFalse(NoteEntries.matchesNote(entry("no-audio", EARLY, video), "", "", true));

        note.put("title", "   ");
        assertEquals("First line", NoteEntries.entryLabel(note));
        char[] longLine = new char[121];
        Arrays.fill(longLine, 'x');
        note.put("text", new String(longLine) + "\nSecond line");
        assertEquals(new String(longLine, 0, 120) + "…", NoteEntries.entryLabel(note));
        note.put("text", " ");
        assertEquals("Audio 00:01 + Video", NoteEntries.entryLabel(note));
        note.getJSONArray("media").put(media("audio-2", "audio", EARLY).put("durationMs", 3600500))
                .put(media("photo-1", "photo", EARLY)).put(media("photo-2", "photo", EARLY))
                .put(media("video-2", "video", EARLY));
        assertEquals("Audio 1:00:01 + 2 photos + 2 videos", NoteEntries.entryLabel(note));
        assertEquals("Photo", NoteEntries.entryLabel(entry("photo", EARLY, media("photo", "photo", EARLY))));
        assertEquals("Note", NoteEntries.entryLabel(entry("empty", EARLY)));
        assertEquals("00:00", NoteEntries.duration(-1000));
        assertEquals("59:59", NoteEntries.duration(3599999));
        assertEquals("1:00:00", NoteEntries.duration(3600000));
    }

    private static JSONObject entry(String id, String createdAt, JSONObject... media) throws Exception {
        JSONArray files = new JSONArray();
        for (JSONObject item : media) files.put(item);
        return new JSONObject().put("id", id).put("createdAt", createdAt).put("media", files);
    }

    private static JSONObject media(String id, String kind, String startedAt) throws Exception {
        return new JSONObject().put("id", id).put("kind", kind).put("startedAt", startedAt);
    }

    private static JSONObject byId(List<JSONObject> entries, String id) {
        for (JSONObject entry : entries) if (id.equals(entry.optString("id"))) return entry;
        throw new AssertionError("Missing entry " + id);
    }

    private static void assertIds(List<JSONObject> entries, String... expected) {
        String[] actual = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) actual[i] = entries.get(i).optString("id");
        assertArrayEquals(expected, actual);
    }
}
