package com.fieldnote.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class TranscriptViewerInstrumentedTest {
    private static final String TIME = "2026-11-09T13:45:00Z";
    private static final String TRANSCRIPT = "The optical bench is aligned.";

    @Test public void viewerUpdatesOnlyMatchingClipAndKeepsPlaybackControls() throws Exception {
        Uri project = Uri.parse("content://com.fieldnote.test/entry-viewer-test.zip");
        Uri otherProject = Uri.parse("content://com.fieldnote.test/other.zip");
        JSONObject pending = entry(audio("first", "pending", ""), audio("second", "no_speech", ""),
                audio("old", "", ""), audio("failed", "failed", ""));
        JSONObject completed = new JSONObject().put("entries", new JSONArray().put(
                entry(audio("first", "complete", TRANSCRIPT))));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                EntryViewer viewer = new EntryViewer(activity, new ProjectStore(activity), project,
                        pending, true, null);
                viewer.show();
                try {
                    View first = viewer.findViewById(EntryViewer.VIEWER_ROOT_ID).findViewWithTag("first");
                    View play = first.findViewById(EntryViewer.AUDIO_PLAY_ID);
                    TextView firstText = first.findViewById(EntryViewer.TRANSCRIPT_BODY_ID);
                    assertEquals(View.VISIBLE, ((View) firstText.getParent()).getVisibility());
                    assertEquals("Waiting for transcription…", firstText.getText().toString());
                    assertFalse(firstText.isTextSelectable());
                    assertEquals("No speech recognized.", transcript(viewer, "second").getText().toString());
                    assertEquals(View.GONE, ((View) transcript(viewer, "old").getParent()).getVisibility());
                    assertEquals("Transcription couldn’t finish. The audio is saved.",
                            transcript(viewer, "failed").getText().toString());

                    viewer.refreshTranscripts(otherProject, completed);
                    assertEquals("Waiting for transcription…", firstText.getText().toString());
                    viewer.refreshTranscripts(project, completed);
                    assertSame(play, first.findViewById(EntryViewer.AUDIO_PLAY_ID));
                    assertEquals(TRANSCRIPT, firstText.getText().toString());
                    assertTrue(firstText.isTextSelectable());
                    assertEquals("No speech recognized.", transcript(viewer, "second").getText().toString());
                } finally {
                    viewer.dismiss();
                }
            });
        }
    }

    @Test public void searchCombinesNoteFieldsWithCompletedAudioTranscripts() throws Exception {
        JSONObject video = audio("video", "complete", "Unrelated video words").put("kind", "video");
        JSONObject pending = audio("pending", "pending", "Unfinished transcription");
        JSONObject entry = entry(audio("first", "complete", TRANSCRIPT),
                audio("second", "complete", "Diffraction peak"), pending, video)
                .put("title", "Alignment check").put("tags", new JSONArray().put("Lab"));
        assertTrue(NoteEntries.matchesNote(entry, "alignment BENCH peak", "LAB", false));
        assertFalse(NoteEntries.matchesNote(entry, "alignment BENCH peak", "Other", false));
        assertFalse(NoteEntries.matchesNote(entry, "video words", "", false));
        assertFalse(NoteEntries.matchesNote(entry, "unfinished", "", false));
        assertFalse(NoteEntries.matchesNote(entry, "bench", "", true));
    }

    private static TextView transcript(EntryViewer viewer, String clipId) {
        View card = viewer.findViewById(EntryViewer.VIEWER_ROOT_ID).findViewWithTag(clipId);
        return card.findViewById(EntryViewer.TRANSCRIPT_BODY_ID);
    }

    private static JSONObject entry(JSONObject... clips) throws JSONException {
        JSONArray media = new JSONArray();
        for (JSONObject clip : clips) media.put(clip);
        return new JSONObject().put("id", "entry-1").put("createdAt", TIME).put("media", media);
    }

    private static JSONObject audio(String id, String status, String text) throws JSONException {
        JSONObject media = new JSONObject().put("id", id).put("kind", "audio")
                .put("startedAt", TIME).put("path", "media/" + id + ".m4a");
        if (!status.isEmpty()) media.put("transcript", new JSONObject().put("status", status).put("text", text));
        return media;
    }
}
