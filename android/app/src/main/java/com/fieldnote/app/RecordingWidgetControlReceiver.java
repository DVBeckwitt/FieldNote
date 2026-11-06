package com.fieldnote.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Stops only the original displayed by the widget, without starting an idle service. */
public final class RecordingWidgetControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        RecordingService.stopFromWidget(context, intent);
    }
}
