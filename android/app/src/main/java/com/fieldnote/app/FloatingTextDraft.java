package com.fieldnote.app;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Revision-scoped draft storage shared by successive floating editor instances. */
final class FloatingTextDraft {
    private static final String KEY = "floating_text_draft";
    private final SharedPreferences preferences;

    FloatingTextDraft(SharedPreferences preferences) { this.preferences = preferences; }

    String write(Uri project, String entry, String text) throws JSONException {
        requireMainThread();
        String revision = new JSONObject().put("project", project.toString()).put("entry", entry)
                .put("text", text).put("revision", UUID.randomUUID().toString()).toString();
        preferences.edit().putString(KEY, revision).apply();
        return revision;
    }

    String read() {
        requireMainThread();
        return preferences.getString(KEY, null);
    }

    /** An older save/cancel cannot erase text restored or edited by a newer editor. */
    void discard(String expectedRevision) {
        requireMainThread();
        if (expectedRevision != null && expectedRevision.equals(read())) preferences.edit().remove(KEY).apply();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Floating text drafts belong to the main thread.");
    }
}
