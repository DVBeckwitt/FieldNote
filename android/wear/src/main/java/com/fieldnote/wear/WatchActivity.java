package com.fieldnote.wear;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class WatchActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 1;
    private static final int BLUETOOTH_REQUEST = 2;
    private static final int NOTIFICATION_REQUEST = 3;
    private static final int ACCENT = 0xffc49bff;
    private static final int INK = 0xff0c1627;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recordingChanged = this::renderServiceState;
    private final Runnable updateTimer = new Runnable() {
        @Override public void run() {
            WatchRecordingService.Snapshot capture = WatchRecordingService.snapshot();
            if (capture.state() != WatchRecordingService.State.RECORDING) return;
            long seconds = (SystemClock.elapsedRealtime() - capture.startedElapsed()) / 1000;
            timer.setText(String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
            handler.postDelayed(this, 1000);
        }
    };

    private TextView status;
    private TextView timer;
    private Button primary;
    private Button secondary;
    private AlertDialog dialog;
    private boolean resumed;
    private boolean startOnResume;
    private boolean choosePhoneOnResume;
    private boolean requestingMicrophone;
    private boolean requestingNotifications;
    private boolean sending;
    private int sendGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        createControls();
        startOnResume = state == null && isCaptureShortcut(getIntent());
    }

    @Override protected void onStart() {
        super.onStart();
        WatchRecordingService.addListener(recordingChanged);
        renderServiceState();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!isCaptureShortcut(intent) || requestingMicrophone || requestingNotifications) return;
        startOnResume = true;
        startIfRequested();
    }

    private boolean isCaptureShortcut(Intent intent) {
        return Intent.ACTION_MAIN.equals(intent.getAction())
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
                && WatchUploader.phoneAddress(this) != null;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (choosePhoneOnResume) {
            choosePhoneOnResume = false;
            if (WatchUploader.hasPermission(this)) choosePhone();
        }
        startIfRequested();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onStop() {
        dismissDialog();
        WatchRecordingService.removeListener(recordingChanged);
        handler.removeCallbacks(updateTimer);
        super.onStop();
    }

    private void createControls() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(28), dp(18), dp(28), dp(18));
        scroll.addView(layout);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        timer = new TextView(this);
        timer.setTextColor(ACCENT);
        timer.setTextSize(26);
        timer.setGravity(Gravity.CENTER);
        layout.addView(timer, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        primary = new Button(this);
        primary.setAllCaps(false);
        primary.setTextColor(INK);
        primary.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        primaryParams.topMargin = dp(5);
        layout.addView(primary, primaryParams);

        secondary = new Button(this, null, android.R.attr.borderlessButtonStyle);
        secondary.setAllCaps(false);
        secondary.setTextColor(ACCENT);
        layout.addView(secondary, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        setContentView(scroll);
    }

    private void renderIdle(int message) {
        status.setText(message);
        timer.setVisibility(View.GONE);
        primary.setText(canRequestMicrophone() ? R.string.start : R.string.open_settings);
        primary.setEnabled(true);
        primary.setOnClickListener(view -> {
            startOnResume = true;
            if (canRequestMicrophone()) startIfRequested();
            else openAppSettings();
        });
        secondary.setText(WatchUploader.phoneAddress(this) == null ? R.string.connect_phone : R.string.phone_transfer);
        secondary.setEnabled(!sending);
        secondary.setOnClickListener(view -> showTransferOptions());
    }

    private void renderRecording() {
        status.setText(R.string.recording);
        timer.setVisibility(View.VISIBLE);
        updateTimer.run();
        primary.setText(R.string.stop);
        primary.setEnabled(true);
        primary.setOnClickListener(view -> WatchRecordingService.finish(this, true));
        secondary.setText(R.string.discard);
        secondary.setEnabled(true);
        secondary.setOnClickListener(view -> showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.discard_title)
                .setNegativeButton(R.string.continue_recording, null)
                .setPositiveButton(R.string.discard, (dialog, which) -> WatchRecordingService.finish(this, false))));
    }

    private void renderServiceState() {
        handler.removeCallbacks(updateTimer);
        dismissDialog();
        WatchRecordingService.Snapshot capture = WatchRecordingService.snapshot();
        switch (capture.state()) {
            case RECORDING -> renderRecording();
            case IDLE -> {
                int message = sending ? R.string.sending : capture.message();
                if (message == R.string.ready && WatchUploader.phoneAddress(this) == null) {
                    message = R.string.setup_phone;
                }
                renderIdle(message);
            }
            case STARTING, SAVING, SENDING -> {
                status.setText(capture.message());
                timer.setVisibility(View.GONE);
                primary.setText(R.string.stop);
                primary.setEnabled(capture.state() == WatchRecordingService.State.STARTING);
                primary.setOnClickListener(view -> WatchRecordingService.finish(this, true));
                secondary.setEnabled(false);
            }
        }
    }

    private boolean hasMicrophone() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canRequestMicrophone() {
        return hasMicrophone() || !getPreferences(MODE_PRIVATE).getBoolean("asked_microphone", false)
                || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
    }

    private void startIfRequested() {
        if (!resumed || !startOnResume || requestingMicrophone || requestingNotifications) return;
        startOnResume = false;
        dismissDialog();
        WatchRecordingService.State state = WatchRecordingService.snapshot().state();
        if (state == WatchRecordingService.State.RECORDING || state == WatchRecordingService.State.STARTING) {
            WatchRecordingService.finish(this, true);
            return;
        }
        if (state != WatchRecordingService.State.IDLE) return;
        if (hasMicrophone()) {
            startWithNotificationPermission();
        } else if (canRequestMicrophone()) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_microphone", true).apply();
            requestingMicrophone = true;
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MICROPHONE_REQUEST);
        } else {
            renderIdle(R.string.permission_required);
        }
    }

    private void startWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("asked_recording_notifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_recording_notifications", true).apply();
            requestingNotifications = true;
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATION_REQUEST);
            return;
        }
        WatchRecordingService.start(this);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MICROPHONE_REQUEST) {
            requestingMicrophone = false;
            if (hasMicrophone()) {
                startOnResume = true;
                startIfRequested();
            } else {
                renderIdle(R.string.permission_required);
            }
        } else if (requestCode == NOTIFICATION_REQUEST) {
            requestingNotifications = false;
            startOnResume = true;
            startIfRequested();
        } else if (requestCode == BLUETOOTH_REQUEST) {
            if (WatchUploader.hasPermission(this)) choosePhone();
            else Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show();
        }
    }

    private void showTransferOptions() {
        if (WatchUploader.phoneAddress(this) == null) {
            choosePhone();
            return;
        }
        showDialog(new AlertDialog.Builder(this).setItems(new CharSequence[] {
                getText(R.string.send_saved), getText(R.string.choose_phone)
        }, (dialog, item) -> {
            if (item == 0 && WatchUploader.hasPermission(this)) chooseSavedRecording();
            else choosePhone();
        }));
    }

    private void choosePhone() {
        if (!WatchUploader.hasPermission(this)) {
            if (!getPreferences(MODE_PRIVATE).getBoolean("asked_bluetooth", false)
                    || shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("asked_bluetooth", true).apply();
                requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, BLUETOOTH_REQUEST);
            } else {
                choosePhoneOnResume = true;
                openAppSettings();
            }
            return;
        }
        List<WatchUploader.Phone> phones = WatchUploader.bondedPhones(this);
        if (phones.isEmpty()) {
            Toast.makeText(this, R.string.no_bonded_phones, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] names = new CharSequence[phones.size()];
        for (int i = 0; i < names.length; i++) names[i] = phones.get(i).name();
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.choose_phone)
                .setItems(names, (dialog, item) -> {
                    WatchUploader.setPhone(this, phones.get(item).address());
                    renderIdle(WatchRecordingQueue.pending(this).isEmpty() ? R.string.ready : R.string.saved_on_watch);
                }));
    }

    private void chooseSavedRecording() {
        List<WatchRecordingQueue.Capture> pending = WatchRecordingQueue.pending(this);
        if (pending.isEmpty()) {
            renderIdle(R.string.nothing_to_send);
            return;
        }
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        CharSequence[] entries = new CharSequence[pending.size()];
        for (int i = 0; i < entries.length; i++) {
            WatchRecordingQueue.Capture capture = pending.get(i);
            long seconds = (capture.endedAtMillis() - capture.startedAtMillis()) / 1000;
            entries[i] = getString(R.string.saved_recording_label,
                    dateFormat.format(new Date(capture.startedAtMillis())), DateUtils.formatElapsedTime(seconds));
        }
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.send_saved)
                .setItems(entries, (dialog, item) -> showDialog(new AlertDialog.Builder(this)
                        .setMessage(R.string.confirm_destination)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.send, (confirmation, button) -> sendSaved(pending.get(item))))));
    }

    private void sendSaved(WatchRecordingQueue.Capture capture) {
        int generation = ++sendGeneration;
        sending = true;
        renderIdle(R.string.sending);
        WatchUploader.sendCapture(this, capture, result -> {
            if (generation != sendGeneration || isDestroyed()) return;
            sending = false;
            if (WatchRecordingService.snapshot().state() != WatchRecordingService.State.IDLE) return;
            if (result.error() == null) {
                renderIdle(result.pending() == 0 ? R.string.sent : R.string.sent_with_pending);
            } else {
                renderIdle(R.string.saved_on_watch);
                if (resumed) {
                    Toast.makeText(this, result.error(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException ex) {
            startOnResume = false;
            choosePhoneOnResume = false;
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void showDialog(AlertDialog.Builder builder) {
        dismissDialog();
        dialog = builder.show();
    }

    private void dismissDialog() {
        if (dialog == null) return;
        dialog.dismiss();
        dialog = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
