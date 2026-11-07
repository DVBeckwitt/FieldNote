package com.fieldnote.wear;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.fieldnote.watchlink.WatchTransfer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Owns the microphone independently of the watch Activity. */
public final class WatchRecordingService extends Service {
    static final String ACTION_OPEN = "com.fieldnote.wear.OPEN_RECORDING";
    private static final String ACTION_START = "com.fieldnote.wear.START_RECORDING";
    private static final String ACTION_STOP = "com.fieldnote.wear.STOP_RECORDING";
    private static final String ACTION_STOP_AT_LIMIT = "com.fieldnote.wear.STOP_AT_LIMIT";
    private static final String ACTION_DISCARD = "com.fieldnote.wear.DISCARD_RECORDING";
    private static final String EXTRA_SESSION = "session";
    private static final String CHANNEL = "watch_recording";
    private static final int NOTIFICATION_ID = 1;

    enum State { IDLE, STARTING, RECORDING, SAVING, SENDING }

    record Snapshot(State state, long startedElapsed, int message) { }

    // Service commands, snapshot changes and Activity listeners run on the main thread.
    private static final List<Runnable> listeners = new ArrayList<>();
    private static Snapshot snapshot = new Snapshot(State.IDLE, 0, R.string.ready);
    private static String currentToken;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread recordingThread;
    private Handler worker;
    private String token;
    private boolean closing;
    private boolean sizeLimitReached;
    private volatile boolean destroyed;
    private int finalMessage = R.string.ready;

    // Recorder and file operations are confined to the worker.
    private MediaRecorder recorder;
    private File temporary;
    private long startedAt;
    private long startedElapsed;

    static Snapshot snapshot() { return snapshot; }
    static void addListener(Runnable listener) { listeners.add(listener); }
    static void removeListener(Runnable listener) { listeners.remove(listener); }

    static void start(Activity activity) {
        if (currentToken != null) return;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(State.IDLE, 0, R.string.permission_required);
            return;
        }
        currentToken = UUID.randomUUID().toString();
        try {
            activity.startForegroundService(command(activity, ACTION_START, currentToken));
        } catch (RuntimeException error) {
            currentToken = null;
            publish(State.IDLE, 0, R.string.start_failed);
            return;
        }
        publish(State.STARTING, 0, R.string.starting_recording);
    }

    static void finish(Context context, boolean save) {
        if (currentToken == null || !isCapturing()) return;
        context.startService(command(context, save ? ACTION_STOP : ACTION_DISCARD, currentToken));
    }

    private static void recordingInfo(Context context, String session, int what) {
        if (what != MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING
                && what != MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) return;
        if (!session.equals(currentToken) || !isCapturing()) return;
        context.startService(command(context, ACTION_STOP_AT_LIMIT, session));
    }

    private static boolean isCapturing() {
        return snapshot.state() == State.STARTING || snapshot.state() == State.RECORDING;
    }

    private static Intent command(Context context, String action, String session) {
        return new Intent(context, WatchRecordingService.class).setAction(action)
                .setData(new Uri.Builder().scheme("fieldnote").authority("watch-recording").appendPath(session).build())
                .putExtra(EXTRA_SESSION, session);
    }

    private static void publish(State state, long elapsed, int message) {
        snapshot = new Snapshot(state, elapsed, message);
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.recording), NotificationManager.IMPORTANCE_LOW));
        recordingThread = new HandlerThread("Fieldnote watch recording");
        recordingThread.start();
        worker = new Handler(recordingThread.getLooper());
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String requestedToken = intent == null ? null : intent.getStringExtra(EXTRA_SESSION);
        if (token == null && ACTION_START.equals(action) && currentToken != null && currentToken.equals(requestedToken)) {
            token = requestedToken;
            try {
                startForeground(NOTIFICATION_ID, notification(R.string.starting_recording, true, 0),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                worker.post(this::startCapture);
            } catch (RuntimeException error) {
                end(R.string.start_failed);
            }
        } else if (token == null) {
            stopSelfResult(startId);
        } else if (!closing && token.equals(requestedToken) && isCapturing()
                && (ACTION_STOP.equals(action) || ACTION_STOP_AT_LIMIT.equals(action) || ACTION_DISCARD.equals(action))) {
            boolean save = !ACTION_DISCARD.equals(action);
            sizeLimitReached = ACTION_STOP_AT_LIMIT.equals(action);
            publish(State.SAVING, 0, R.string.saving_recording);
            updateNotification(R.string.saving_recording, false, 0);
            worker.post(() -> {
                WatchRecordingQueue.Capture capture = finishCapture(save);
                main.post(() -> afterCapture(save, capture));
            });
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startCapture() {
        if (destroyed) return;
        MediaRecorder next = null;
        try {
            temporary = WatchRecordingQueue.begin(this);
            next = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            next.setAudioSource(MediaRecorder.AudioSource.MIC);
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            next.setAudioChannels(1);
            next.setAudioSamplingRate(44100);
            next.setAudioEncodingBitRate(128000);
            // Leave container headroom inside the receiver's hard transfer bound.
            // The approaching event finalizes early; reached is the fallback.
            next.setMaxFileSize(WatchTransfer.MAX_BYTES - 1024 * 1024);
            next.setOutputFile(temporary.getAbsolutePath());
            String session = token;
            next.setOnInfoListener((source, what, extra) ->
                    main.post(() -> recordingInfo(this, session, what)));
            next.setOnErrorListener((source, what, extra) -> {
                if (recorder != source) return;
                finishCapture(false);
                main.post(() -> end(R.string.save_failed));
            });
            next.prepare();
            if (destroyed) throw new IllegalStateException("The recording service was stopped");
            startedAt = System.currentTimeMillis();
            startedElapsed = SystemClock.elapsedRealtime();
            next.start();
            recorder = next;
        } catch (IOException | RuntimeException error) {
            if (next != null) next.release();
            deleteTemporary();
            main.post(() -> end(R.string.start_failed));
            return;
        }
        long elapsed = startedElapsed;
        long wallTime = startedAt;
        main.post(() -> {
            if (destroyed || closing || snapshot.state() != State.STARTING) return;
            publish(State.RECORDING, elapsed, R.string.recording);
            updateNotification(R.string.recording, true, wallTime);
        });
    }

    private WatchRecordingQueue.Capture finishCapture(boolean save) {
        MediaRecorder active = recorder;
        if (active == null) return null;
        recorder = null;
        long endedAt = startedAt + (SystemClock.elapsedRealtime() - startedElapsed);
        try {
            if (save) active.stop();
        } catch (RuntimeException error) {
            deleteTemporary();
            return null;
        } finally {
            active.release();
        }
        if (!save) {
            deleteTemporary();
            return null;
        }
        try {
            WatchRecordingQueue.Capture capture = WatchRecordingQueue.commit(temporary, UUID.fromString(token), startedAt, endedAt);
            temporary = null;
            return capture;
        } catch (IOException error) {
            return null;
        }
    }

    private void deleteTemporary() {
        if (temporary != null) temporary.delete();
        temporary = null;
    }

    private void afterCapture(boolean save, WatchRecordingQueue.Capture capture) {
        if (closing || destroyed) return;
        if (!save) {
            end(R.string.ready);
        } else if (capture == null) {
            end(R.string.save_failed);
        } else if (!WatchUploader.hasPermission(this) || WatchUploader.phoneAddress(this) == null) {
            end(sizeLimitReached ? R.string.limit_saved_on_watch : R.string.saved_on_watch);
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification(R.string.sending, false, 0),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                publish(State.SENDING, 0, R.string.sending);
                WatchUploader.sendCapture(this, capture, result -> end(result.error() == null
                        ? (sizeLimitReached ? R.string.limit_sent
                            : (result.pending() == 0 ? R.string.sent : R.string.sent_with_pending))
                        : (sizeLimitReached ? R.string.limit_saved_on_watch : R.string.saved_on_watch)));
            } catch (RuntimeException error) {
                end(sizeLimitReached ? R.string.limit_saved_on_watch : R.string.saved_on_watch);
            }
        }
    }

    private void end(int message) {
        if (closing || destroyed) return;
        finalMessage = message;
        closing = true;
        stopSelf();
    }

    private void updateNotification(int message, boolean canStop, long began) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message, canStop, began));
        }
    }

    private Notification notification(int message, boolean canStop, long began) {
        Intent open = new Intent(this, WatchActivity.class).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(message))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setShowWhen(began != 0);
        if (began != 0) builder.setWhen(began).setUsesChronometer(true);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (canStop) {
            // The token also lives in the URI because PendingIntent identity ignores extras.
            PendingIntent stop = PendingIntent.getService(this, 0, command(this, ACTION_STOP, token), PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build());
        }
        return builder.build();
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (token != null) {
            // Retain the shared session until its worker leaves the recording file alone.
            worker.post(() -> {
                boolean interrupted = recorder != null;
                WatchRecordingQueue.Capture capture = finishCapture(true);
                main.post(() -> {
                    if (!token.equals(currentToken)) return;
                    currentToken = null;
                    int message = finalMessage;
                    if (interrupted) message = capture == null ? R.string.save_failed : R.string.saved_on_watch;
                    publish(State.IDLE, 0, message);
                });
            });
        }
        recordingThread.quitSafely();
        super.onDestroy();
    }
}
