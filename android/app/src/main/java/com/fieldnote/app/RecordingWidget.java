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

/** Shows the interactive overlay directly over the launcher; setup remains in MainActivity. */
public final class RecordingWidget extends AppWidgetProvider {
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) refresh(context);
    }

    /** Replaces existing launcher bindings after an APK update. */
    static void refresh(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            new RecordingWidget().onUpdate(context, manager,
                    manager.getAppWidgetIds(new ComponentName(context, RecordingWidget.class)));
        } catch (RuntimeException unavailable) {
            Log.w("FieldnoteWidget", "The launcher widget could not be refreshed after the update.");
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
            Log.w("FieldnoteWidget", "The floating microphone widget could not be updated.");
        }
    }

    static RemoteViews views(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_mic);
        PendingIntent open = PendingIntent.getForegroundService(context, 61, RecordingShortcuts.widgetIntent(context),
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
