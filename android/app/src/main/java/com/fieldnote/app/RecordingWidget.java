package com.fieldnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

/** A launcher button only. MainActivity owns permissions and the actual floating microphone. */
public final class RecordingWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        ComponentName provider = new ComponentName(context, RecordingWidget.class);
        try {
            RemoteViews views = views(context);
            for (int id : ids) {
                AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
                if (info != null && provider.equals(info.provider)) manager.updateAppWidget(id, views);
            }
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteWidget", "The floating microphone widget could not be updated.");
        }
    }

    static RemoteViews views(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_mic);
        PendingIntent open = PendingIntent.getActivity(context, 61, RecordingShortcuts.overlayIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_mic_button, open);
        return views;
    }

    /** True means the launcher accepted the request; placement still needs the user's approval. */
    public static boolean requestPin(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            return manager.isRequestPinAppWidgetSupported()
                    && manager.requestPinAppWidget(new ComponentName(context, RecordingWidget.class), null, null);
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteWidget", "Pinning a floating microphone widget is unavailable.");
            return false;
        }
    }
}
