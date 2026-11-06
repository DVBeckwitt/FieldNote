package com.fieldnote.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.util.Log;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launcher entry points authenticate an action; the Activity chooses the project and entry. */
public final class RecordingShortcuts {
    public static final String ACTION_RECORD = "com.fieldnote.app.action.RECORD_NOW";
    public static final String ACTION_FLOATING = "com.fieldnote.app.action.FLOATING_MIC";
    public static final String ACTION_WIDGET_FLOATING = "com.fieldnote.app.action.WIDGET_FLOATING_MIC";
    public static final String ACTION_WIDGET_RECORD = "com.fieldnote.app.action.WIDGET_RECORD";
    public static final String ACTION_WIDGET_STOP = "com.fieldnote.app.action.WIDGET_STOP";
    static final String EXTRA_WIDGET_ENTRY = "com.fieldnote.app.extra.WIDGET_ENTRY";
    static final String EXTRA_WIDGET_SESSION = "com.fieldnote.app.extra.WIDGET_SESSION";
    static final String SHORTCUT_ID = "record-now";
    static final String FLOATING_SHORTCUT_ID = "floating-mic";
    static final String NONCE_KEY = "record_shortcut_nonce_v1";
    static final String EXTRA_NONCE = "com.fieldnote.app.extra.RECORD_SHORTCUT_NONCE";
    private static final String LABEL = "Record now";
    private static final String FLOATING_LABEL = "Floating mic";
    private static final String VERSION_KEY = "record_shortcut_version";
    private static final int VERSION = 2;
    private static final int LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP;
    private static final Object NONCE_LOCK = new Object();
    private static final ExecutorService PUBLISHER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean PUBLISH_QUEUED = new AtomicBoolean();

    private RecordingShortcuts() { }

    /** Best effort and asynchronous: launcher IPC must not delay Activity creation. */
    public static void publish(Context context) {
        if (!PUBLISH_QUEUED.compareAndSet(false, true)) return;
        try {
            Context app = application(context);
            PUBLISHER.execute(() -> {
                try {
                    ShortcutManager manager = app.getSystemService(ShortcutManager.class);
                    if (manager == null) return;
                    publishOne(app, manager, SHORTCUT_ID, LABEL, recordIntent(app), false);
                    publishOne(app, manager, FLOATING_SHORTCUT_ID, FLOATING_LABEL, overlayIntent(app), true);
                } catch (RuntimeException unavailable) {
                    Log.w("FieldnoteShortcuts", "Recording shortcut publication is unavailable.");
                } finally { PUBLISH_QUEUED.set(false); }
            });
        } catch (RuntimeException unavailable) {
            PUBLISH_QUEUED.set(false);
            Log.w("FieldnoteShortcuts", "Recording shortcut publication could not be queued.");
        }
    }

    /** A failed token write falls back to opening Fieldnote without starting capture. */
    public static Intent recordIntent(Context context) {
        return launchIntent(context, ACTION_RECORD);
    }

    public static Intent overlayIntent(Context context) {
        return launchIntent(context, ACTION_FLOATING);
    }

    /** A reusable widget capability; its identity survives process recreation. */
    public static Intent widgetIntent(Context context) {
        Intent intent = new Intent(context, FloatingRecorderService.class);
        try {
            return intent.setAction(ACTION_WIDGET_FLOATING).putExtra(EXTRA_NONCE, nonce(context));
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteShortcuts", "Widget authentication is unavailable.");
            return intent; // The private service rejects an unauthenticated request.
        }
    }

    public static boolean isWidgetIntent(Context context, Intent intent) {
        return authenticated(context, intent, ACTION_WIDGET_FLOATING,
                new ComponentName(context, FloatingRecorderService.class));
    }

    /** Direct home-screen audio control; the private service chooses the project. */
    public static Intent recordingWidgetIntent(Context context) {
        Intent intent = new Intent(context, RecordingService.class).setAction(ACTION_WIDGET_RECORD);
        try {
            return intent.setAction(ACTION_WIDGET_RECORD).putExtra(EXTRA_NONCE, nonce(context));
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteShortcuts", "Widget authentication is unavailable.");
            return intent;
        }
    }

    public static boolean isRecordingWidgetIntent(Context context, Intent intent) {
        return authenticated(context, intent, ACTION_WIDGET_RECORD,
                new ComponentName(context, RecordingService.class));
    }

    /** A purple widget can only stop its displayed original, including after process death. */
    public static Intent recordingWidgetStopIntent(Context context, RecordingService.Snapshot expected) {
        String expectedEntryId = expected == null ? null : expected.entryId;
        String session = RecordingService.widgetStopToken(expected);
        Intent intent = new Intent(context, RecordingWidgetControlReceiver.class)
                .setData(new Uri.Builder().scheme("fieldnote").authority("recording-widget").appendPath("stop")
                        .appendPath(expectedEntryId == null ? "unavailable" : expectedEntryId)
                        .appendPath(session == null ? "unavailable" : session).build());
        try {
            return intent.setAction(ACTION_WIDGET_STOP).putExtra(EXTRA_NONCE, nonce(context))
                    .putExtra(EXTRA_WIDGET_ENTRY, expectedEntryId).putExtra(EXTRA_WIDGET_SESSION, session);
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteShortcuts", "Widget authentication is unavailable.");
            return intent;
        }
    }

    public static boolean isRecordingWidgetStopIntent(Context context, Intent intent) {
        return authenticated(context, intent, ACTION_WIDGET_STOP,
                new ComponentName(context, RecordingWidgetControlReceiver.class));
    }

    private static Intent launchIntent(Context context, String action) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(LAUNCH_FLAGS);
        try {
            return intent.setAction(action).putExtra(EXTRA_NONCE, nonce(context));
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteShortcuts", "Recording shortcut authentication is unavailable.");
            return intent.setAction(Intent.ACTION_MAIN);
        }
    }

    /** Checking an external request never creates a token or trusts caller-supplied targets. */
    public static boolean isRecordIntent(Context context, Intent intent) {
        return authenticated(context, intent, ACTION_RECORD);
    }

    public static boolean isOverlayIntent(Context context, Intent intent) {
        return authenticated(context, intent, ACTION_FLOATING);
    }

    private static boolean authenticated(Context context, Intent intent, String action) {
        return authenticated(context, intent, action, new ComponentName(context, MainActivity.class));
    }

    private static boolean authenticated(Context context, Intent intent, String action, ComponentName target) {
        if (intent == null || !action.equals(intent.getAction())
                || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
                || !target.equals(intent.getComponent())) return false;
        try {
            Object expected = preferences(context).getAll().get(NONCE_KEY);
            return validNonce(expected) && expected.equals(intent.getStringExtra(EXTRA_NONCE));
        } catch (RuntimeException invalid) { return false; }
    }

    /** Call off the UI thread while the app is foreground; true means the launcher accepted the request. */
    public static boolean requestPin(Context context) {
        return requestPin(context, false);
    }

    /** Pins the overlay action separately from the immediate in-app recording action. */
    public static boolean requestPinFloating(Context context) {
        return requestPin(context, true);
    }

    private static boolean requestPin(Context context, boolean floating) {
        try {
            Context app = application(context);
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return false;
            String id = floating ? FLOATING_SHORTCUT_ID : SHORTCUT_ID;
            String label = floating ? FLOATING_LABEL : LABEL;
            Intent intent = floating ? overlayIntent(app) : recordIntent(app);
            boolean authenticated = floating ? isOverlayIntent(app, intent) : isRecordIntent(app, intent);
            return authenticated && refreshPinned(app, manager, id, label, intent)
                    && manager.requestPinShortcut(shortcut(app, id, label, intent), null);
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteShortcuts", "Pinning a Fieldnote shortcut is unavailable.");
            return false;
        }
    }

    private static void publishOne(Context context, ShortcutManager manager, String id, String label,
                                   Intent intent, boolean optional) {
        if (!authenticated(context, intent, intent.getAction())) return;
        boolean existing = false;
        java.util.List<ShortcutInfo> dynamic = manager.getDynamicShortcuts();
        for (ShortcutInfo current : dynamic) {
            if (!id.equals(current.getId())) continue;
            existing = true;
            if (matches(context, current, label, intent)) return;
        }
        if (!refreshPinned(context, manager, id, label, intent)) return;
        if (!existing) {
            ComponentName activity = new ComponentName(context, MainActivity.class);
            int count = 0;
            for (ShortcutInfo current : dynamic) if (activity.equals(current.getActivity())) count++;
            for (ShortcutInfo current : manager.getManifestShortcuts()) if (activity.equals(current.getActivity())) count++;
            if (count >= manager.getMaxShortcutCountPerActivity()) {
                if (!optional) Log.w("FieldnoteShortcuts", "The launcher has no room for a recording shortcut.");
                return;
            }
        }
        // Adding this ID updates it without replacing other future app shortcuts.
        manager.addDynamicShortcuts(Collections.singletonList(shortcut(context, id, label, intent)));
    }

    private static ShortcutInfo shortcut(Context context, String id, String label, Intent intent) {
        PersistableBundle metadata = new PersistableBundle();
        metadata.putInt(VERSION_KEY, VERSION); // Icon/label revision, never the authentication token.
        return new ShortcutInfo.Builder(context, id)
                .setActivity(new ComponentName(context, MainActivity.class))
                .setShortLabel(label).setLongLabel(longLabel(id))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_record))
                .setIntent(intent).setExtras(metadata).build();
    }

    private static boolean refreshPinned(Context context, ShortcutManager manager, String id, String label, Intent intent) {
        for (ShortcutInfo pinned : manager.getPinnedShortcuts()) {
            if (id.equals(pinned.getId()) && !matches(context, pinned, label, intent)) {
                // Repair existing pins even when unrelated shortcuts fill the dynamic quota.
                return manager.updateShortcuts(Collections.singletonList(shortcut(context, id, label, intent)));
            }
        }
        return true;
    }

    private static boolean matches(Context context, ShortcutInfo current, String label, Intent expected) {
        Intent[] intents = current.getIntents();
        PersistableBundle metadata = current.getExtras();
        return current.isEnabled() && TextUtils.equals(label, current.getShortLabel())
                && TextUtils.equals(longLabel(current.getId()), current.getLongLabel())
                && new ComponentName(context, MainActivity.class).equals(current.getActivity())
                && metadata != null && metadata.getInt(VERSION_KEY) == VERSION
                && intents != null && intents.length == 1 && intents[0] != null
                && expected.filterEquals(intents[0])
                && (intents[0].getFlags() & LAUNCH_FLAGS) == LAUNCH_FLAGS
                && authenticated(context, intents[0], expected.getAction());
    }

    private static String longLabel(String id) {
        return FLOATING_SHORTCUT_ID.equals(id) ? "Show floating mic" : "Record now in app";
    }

    private static String nonce(Context context) {
        synchronized (NONCE_LOCK) {
            SharedPreferences preferences = preferences(context);
            Object stored = preferences.getAll().get(NONCE_KEY);
            if (validNonce(stored)) return (String) stored;
            String created = UUID.randomUUID().toString();
            // Persist before distributing a reusable launcher capability.
            if (!preferences.edit().putString(NONCE_KEY, created).commit()) {
                preferences.edit().remove(NONCE_KEY).apply();
                throw new IllegalStateException("Shortcut token could not be saved.");
            }
            return created;
        }
    }

    private static boolean validNonce(Object value) {
        return value instanceof String && ((String) value).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static SharedPreferences preferences(Context context) {
        return application(context).getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
