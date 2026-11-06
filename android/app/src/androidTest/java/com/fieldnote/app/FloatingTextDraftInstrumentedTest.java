package com.fieldnote.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.junit.Assert.*;

/** Draft completion races across overlay hosts; no project files or capture hardware are used. */
@RunWith(AndroidJUnit4.class)
public final class FloatingTextDraftInstrumentedTest {
    private static final String DRAFT_KEY = "floating_text_draft";
    private static final Uri PROJECT = Uri.parse("content://com.fieldnote.app.test/document/draft-project.zip");
    private SharedPreferences preferences;

    @Before public void createIsolatedPreferences() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("floating-draft-test-" + UUID.randomUUID(), Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
    }

    @After public void clearIsolatedPreferences() {
        if (preferences != null) assertTrue(preferences.edit().clear().commit());
    }

    @Test public void oldSubmissionCompletionCannotDiscardReopenedEditedDraft() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FloatingTextDraft oldHost = new FloatingTextDraft(preferences);
            String submitted = write(oldHost, "same-entry", "Submitted text");

            FloatingTextDraft reopenedHost = new FloatingTextDraft(preferences);
            assertEquals(submitted, reopenedHost.read());
            String newerRevision = write(reopenedHost, "same-entry", "Edited after reopening");

            oldHost.discard(submitted);

            assertEquals("An older save must leave the newer draft intact", newerRevision, reopenedHost.read());
            assertEquals("Edited after reopening", textOf(reopenedHost.read()));
        });
    }

    @Test public void reopeningSameTextStillProtectsDraftFromEarlierSubmission() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FloatingTextDraft oldHost = new FloatingTextDraft(preferences);
            String submitted = write(oldHost, "same-entry", "Unchanged restored text");

            FloatingTextDraft reopenedHost = new FloatingTextDraft(preferences);
            String reopenedRevision = write(reopenedHost, "same-entry", "Unchanged restored text");
            assertNotEquals("Reopening establishes a new draft revision even when its text is identical",
                    submitted, reopenedRevision);

            oldHost.discard(submitted);

            assertEquals(reopenedRevision, reopenedHost.read());
            assertEquals("Unchanged restored text", textOf(reopenedHost.read()));
        });
    }

    @Test public void matchingSubmissionCompletionDiscardsItsDraft() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FloatingTextDraft host = new FloatingTextDraft(preferences);
            String submitted = write(host, "saved-entry", "Saved text");
            assertTrue(preferences.contains(DRAFT_KEY));

            host.discard(submitted);

            assertFalse("The completed current revision no longer needs recovery", preferences.contains(DRAFT_KEY));
            assertNull(host.read());
            assertNull(new FloatingTextDraft(preferences).read());
        });
    }

    private static String write(FloatingTextDraft drafts, String entry, String text) {
        try { return drafts.write(PROJECT, entry, text); }
        catch (JSONException error) { throw new AssertionError("Could not prepare draft", error); }
    }

    private static String textOf(String serialized) {
        try { return new JSONObject(serialized).getString("text"); }
        catch (JSONException error) { throw new AssertionError("Draft must retain readable text", error); }
    }
}
