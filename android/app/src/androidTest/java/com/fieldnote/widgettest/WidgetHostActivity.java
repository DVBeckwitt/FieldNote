package com.fieldnote.widgettest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

/** Test-APK process: executes the real widget click from a separate visible UID. */
@SuppressWarnings("deprecation")
public final class WidgetHostActivity extends Activity {
    public static final String CLOSE = "com.fieldnote.widgettest.CLOSE";
    private ResultReceiver results;
    private View microphone;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (CLOSE.equals(getIntent().getAction())) { finish(); return; }
        results = getIntent().getParcelableExtra("results");
        RemoteViews views = getIntent().getParcelableExtra("views");
        if (views == null || results == null) { finish(); return; }
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xffeef4ff);
        TextView title = new TextView(this);
        title.setText("External widget test host");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        page.addView(title, new FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP));
        View widget = views.apply(this, page);
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        placement.topMargin = dp(80);
        page.addView(widget, placement);
        microphone = widget.findViewById(getIntent().getIntExtra("micId", 0));
        setContentView(page);
        widget.post(this::report);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (CLOSE.equals(intent.getAction())) finish();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (microphone != null) microphone.post(this::report);
    }

    private void report() {
        if (results == null || microphone == null) return;
        int[] location = new int[2];
        microphone.getLocationOnScreen(location);
        Bundle values = new Bundle();
        values.putInt("uid", Process.myUid());
        values.putInt("pid", Process.myPid());
        values.putBoolean("focused", hasWindowFocus());
        values.putFloat("x", location[0] + microphone.getWidth() / 2f);
        values.putFloat("y", location[1] + microphone.getHeight() / 2f);
        values.putInt("width", microphone.getWidth());
        results.send(1, values);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
