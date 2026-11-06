package com.fieldnote.widgettest;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

/** Test-APK process: executes the real widget click from a separate visible UID. */
@SuppressWarnings("deprecation")
public final class WidgetHostActivity extends Activity {
    public static final String CLOSE = "com.fieldnote.widgettest.CLOSE";
    public static final String CACHE = "com.fieldnote.widgettest.CACHE";
    public static final String REPLAY = "com.fieldnote.widgettest.REPLAY";
    private ResultReceiver results;
    private View microphone;
    private AppWidgetHost host;
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int micId;
    private TestHostView hostView;
    private RemoteViews latestViews, cachedViews;
    private boolean replayingCache;
    private int updateCount;
    private static final int HOST_ID = 23061;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (CLOSE.equals(getIntent().getAction())) { finish(); return; }
        results = getIntent().getParcelableExtra("results");
        micId = getIntent().getIntExtra("micId", 0);
        ComponentName provider = getIntent().getParcelableExtra("provider");
        if (provider == null) {
            String flattened = getIntent().getStringExtra("providerComponent");
            if (flattened != null) provider = ComponentName.unflattenFromString(flattened);
        }
        // A cold shell-driven smoke may omit the instrumentation ResultReceiver.
        // Keep that manual route limited to Fieldnote's own widget provider.
        if (provider == null || !"com.fieldnote.app".equals(provider.getPackageName())
                || !"com.fieldnote.app.RecordingWidget".equals(provider.getClassName())) { finish(); return; }
        if (micId == 0) {
            try {
                micId = getPackageManager().getResourcesForApplication(provider.getPackageName())
                        .getIdentifier("widget_mic_button", "id", provider.getPackageName());
            } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { finish(); return; }
            if (micId == 0) { finish(); return; }
        }
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xffeef4ff);
        TextView title = new TextView(this);
        title.setText("External widget test host");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        page.addView(title, new FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP));
        host = new AppWidgetHost(this, HOST_ID) {
            @Override protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
                hostView = new TestHostView(context);
                return hostView;
            }
        };
        host.deleteHost(); // Only this test APK's dedicated host ID.
        host.startListening();
        widgetId = host.allocateAppWidgetId();
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (!manager.bindAppWidgetIdIfAllowed(widgetId, provider)) {
            Bundle failure = new Bundle();
            failure.putString("error", "Test widget binding permission was not granted");
            if (results != null) results.send(2, failure);
            finish();
            return;
        }
        AppWidgetHostView widget = host.createView(this, widgetId, manager.getAppWidgetInfo(widgetId));
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        placement.topMargin = dp(80);
        page.addView(widget, placement);
        widget.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            microphone = widget.findViewById(micId);
            report();
        });
        setContentView(page);
        widget.post(this::report);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (CLOSE.equals(intent.getAction())) finish();
        else if (CACHE.equals(intent.getAction())) { cachedViews = latestViews; report(); }
        else if (REPLAY.equals(intent.getAction()) && cachedViews != null && hostView != null) {
            replayingCache = true;
            hostView.showCached(cachedViews);
        }
    }

    @Override protected void onDestroy() {
        if (host != null) {
            host.stopListening();
            host.deleteHost();
        }
        if (results != null) results.send(3, new Bundle());
        super.onDestroy();
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
        values.putInt("widgetId", widgetId);
        values.putInt("updates", updateCount);
        values.putBoolean("cached", cachedViews != null);
        values.putBoolean("replaying", replayingCache);
        CharSequence description = microphone.getContentDescription();
        values.putString("description", description == null ? "" : description.toString());
        Drawable background = microphone.getBackground();
        if (background != null && microphone.getWidth() > 0) {
            Bitmap sample = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
            Rect original = new Rect(background.getBounds());
            background.setBounds(0, 0, 48, 48);
            background.draw(new Canvas(sample));
            values.putInt("backgroundCenter", sample.getPixel(24, 24));
            background.setBounds(original);
            sample.recycle();
        }
        results.send(1, values);
    }

    /** Replays one launcher-held RemoteViews snapshot until its click is delivered. */
    private final class TestHostView extends AppWidgetHostView {
        TestHostView(Context context) { super(context); }

        @Override public void updateAppWidget(RemoteViews views) {
            latestViews = views;
            if (replayingCache) return;
            updateCount++;
            super.updateAppWidget(views);
            reportLater();
        }

        void showCached(RemoteViews views) {
            super.updateAppWidget(views);
            reportLater();
        }

        private void reportLater() {
            post(() -> { microphone = findViewById(micId); report(); });
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            boolean consumed = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                replayingCache = false;
                reportLater();
            }
            return consumed;
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
