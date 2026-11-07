package com.fieldnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-place audio toggle. RemoteViews stays in the launcher; capture belongs to the private service. */
public final class RecordingWidget extends AppWidgetProvider {
    private static final ExecutorService UPDATES = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DIRTY = new AtomicBoolean();
    private static final AtomicBoolean UPDATING = new AtomicBoolean();

    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            PendingResult pending = goAsync();
            UPDATES.execute(() -> {
                try { refreshNow(context.getApplicationContext()); }
                finally { pending.finish(); }
            });
            return;
        }
        super.onReceive(context, intent);
    }

    /** Coalesces state broadcasts without doing launcher IPC on the recorder or UI thread. */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        DIRTY.set(true);
        if (!UPDATING.compareAndSet(false, true)) return;
        UPDATES.execute(() -> {
            try {
                do {
                    DIRTY.set(false);
                    refreshNow(app);
                } while (DIRTY.get());
            } finally {
                UPDATING.set(false);
                if (DIRTY.get()) refresh(app);
            }
        });
    }

    private static void refreshNow(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            new RecordingWidget().onUpdate(context, manager,
                    manager.getAppWidgetIds(new ComponentName(context, RecordingWidget.class)));
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteWidget", "The recorder widget could not be refreshed.");
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        ComponentName provider = new ComponentName(context, RecordingWidget.class);
        try {
            RemoteViews views = views(context);
            for (int id : ids) {
                AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
                if (info != null && provider.equals(info.provider)) manager.updateAppWidget(id, views);
            }
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteWidget", "The recorder widget could not be updated.");
        }
    }

    static RemoteViews views(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_mic);
        RecordingService.Snapshot capture = RecordingService.snapshot();
        RecordingService.State state = capture.state;
        boolean active = state == RecordingService.State.STARTING || state == RecordingService.State.RECORDING;
        // A launcher can retain the purple view after process death. Its Stop must
        // never turn into Start, or stop a later original after a delayed click.
        Intent action = active ? RecordingShortcuts.recordingWidgetStopIntent(context, capture)
                : RecordingShortcuts.recordingWidgetIntent(context);
        // Start keeps the visible launcher's foreground admission. Stop is a broadcast
        // so a cached purple widget can refresh itself without creating a new service.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent toggle = active ? PendingIntent.getBroadcast(context, 63, action, flags)
                : PendingIntent.getForegroundService(context, 62, action, flags);
        views.setOnClickPendingIntent(R.id.widget_mic_button, toggle);
        views.setInt(R.id.widget_mic_button, "setBackgroundResource",
                active ? R.drawable.widget_mic_recording_background : R.drawable.widget_mic_background);
        int description = state == RecordingService.State.STARTING ? R.string.widget_mic_starting
                : active ? R.string.widget_mic_recording
                : state == RecordingService.State.SAVING ? R.string.widget_mic_saving : R.string.widget_mic_description;
        views.setContentDescription(R.id.widget_mic_button, context.getString(description));
        views.setBoolean(R.id.widget_mic_button, "setEnabled", state != RecordingService.State.SAVING);
        return views;
    }

    /** True means the launcher accepted the request; placement still needs the user's approval. */
    public static boolean requestPin(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            return manager.isRequestPinAppWidgetSupported()
                    && manager.requestPinAppWidget(new ComponentName(context, RecordingWidget.class), null, null);
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteWidget", "Pinning a recorder widget is unavailable.");
            return false;
        }
    }
}
