package com.fieldnote.app;

import android.Manifest;
import android.app.Notification;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/** Owns the microphone independently of the Activity, including while the camera is visible. */
public final class RecordingService extends Service {
    public static final String ACTION_STATE = "com.fieldnote.app.RECORDING_STATE";
    private static final String ACTION_START = "com.fieldnote.app.START_RECORDING";
    private static final String ACTION_STOP = "com.fieldnote.app.STOP_RECORDING";
    private static final String ACTION_LOCK = "com.fieldnote.app.LOCK_RECORDING";
    private static final String CHANNEL = "fieldnote_recording";
    private static final int NOTIFICATION_ID = 1001;
    static final int WIDGET_SETUP_NOTIFICATION_ID = 1004;
    private static final long MAX_FILE_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_DURATION_MS = 6 * 60 * 60 * 1000;
    private static final Object STATE_LOCK = new Object();

    public enum State { IDLE, STARTING, RECORDING, SAVING, ERROR }

    public static final class Snapshot {
        public final State state;
        public final Uri projectUri;
        public final String entryId;
        public final long startedAt;
        public final long startedElapsedRealtime;
        public final boolean locked;
        /** Null unless a failure or an interruption needs explaining to the user. */
        public final String error;
        /** Markers acknowledged only after their capture journal has been written. */
        public final int markerCount;
        /** Monotonic offset of the last acknowledged marker, or -1 when none was saved. */
        public final long lastMarkerOffsetMs;
        /** A marker failure is separate from the health of the ongoing recording. */
        public final String markerError;
        private final String token;

        private Snapshot(State state, Uri projectUri, String entryId, long startedAt,
                         long startedElapsedRealtime,
                         boolean locked, String error, String token) {
            this(state, projectUri, entryId, startedAt, startedElapsedRealtime,
                    locked, error, token, 0, -1, null);
        }

        private Snapshot(State state, Uri projectUri, String entryId, long startedAt,
                         long startedElapsedRealtime,
                         boolean locked, String error, String token, int markerCount,
                         long lastMarkerOffsetMs, String markerError) {
            this.state = state;
            this.projectUri = projectUri;
            this.entryId = entryId;
            this.startedAt = startedAt;
            this.startedElapsedRealtime = startedElapsedRealtime;
            this.locked = locked;
            this.error = error;
            this.token = token;
            this.markerCount = markerCount;
            this.lastMarkerOffsetMs = lastMarkerOffsetMs;
            this.markerError = markerError;
        }

        public long elapsedMs() {
            return state == State.RECORDING && startedElapsedRealtime > 0
                    ? Math.max(0, SystemClock.elapsedRealtime() - startedElapsedRealtime) : 0;
        }
    }

    private static volatile Snapshot current = new Snapshot(State.IDLE, null, null,
            0, 0, false, null, null);
    private static volatile boolean stopRequested;
    private static volatile boolean lockRequested;
    private static volatile RecordingService activeInstance;
    private HandlerThread recorderThread;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker;
    private MediaRecorder recorder;
    private AudioManager.AudioRecordingCallback silenceCallback;
    private PowerManager.WakeLock wakeLock;
    private ProjectStore store;
    private File pendingFile;
    private Uri project;
    private String entryId;
    private String token;
    private JSONObject startMetadata;
    private long startedAt;
    private long startedElapsed;
    private boolean recorderStarted;
    private boolean widgetStarted;
    private volatile boolean foregroundStarted;
    private volatile boolean admissionOnly;
    private volatile boolean finishing;
    private volatile boolean destroyed;
    private State terminalState;
    private String terminalMessage;
    /** Admission and removal use STATE_LOCK; only the recorder worker performs journal I/O. */
    private final ArrayDeque<MarkerRequest> pendingMarkers = new ArrayDeque<>();
    /** Worker-owned failed requests retain their stable IDs for one retry at finalization. */
    private final ArrayDeque<MarkerRequest> failedMarkers = new ArrayDeque<>();

    private static final class MarkerRequest {
        final String token, id;
        final long offsetMs, markedAt;

        MarkerRequest(String token, long offsetMs, long markedAt) {
            this.token = token;
            this.id = UUID.randomUUID().toString();
            this.offsetMs = offsetMs;
            this.markedAt = markedAt;
        }
    }

    public static Snapshot snapshot() { return current; }

    /** Internal capability identity: entry IDs can contain several successive audio originals. */
    static String widgetStopToken(Snapshot expected) {
        return expected != null && (expected.state == State.STARTING || expected.state == State.RECORDING)
                ? expected.token : null;
    }

    public static boolean isRecording() {
        State state = current.state;
        return state == State.STARTING || state == State.RECORDING;
    }

    public static boolean isBusy() {
        return isRecording() || current.state == State.SAVING;
    }

    /** Call from a visible Activity after RECORD_AUDIO has been granted. */
    public static void start(Context context, Uri projectUri, String targetEntryId) {
        Context app = context.getApplicationContext();
        String requestToken;
        synchronized (STATE_LOCK) {
            if (isBusy()) return;
            requestToken = UUID.randomUUID().toString();
            stopRequested = false;
            lockRequested = false;
            String id = targetEntryId == null || targetEntryId.isEmpty()
                    ? UUID.randomUUID().toString() : targetEntryId;
            current = new Snapshot(State.STARTING, projectUri, id, 0, 0, false, null, requestToken);
        }
        if (projectUri == null) {
            externalFailure(app, requestToken, "Choose a project folder before recording.");
            return;
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            externalFailure(app, requestToken, "Microphone permission is required to record audio.");
            return;
        }
        broadcast(app);
        Intent intent = new Intent(app, RecordingService.class).setAction(ACTION_START)
                .putExtra("token", requestToken);
        try {
            app.startForegroundService(intent);
        } catch (RuntimeException error) {
            externalFailure(app, requestToken,
                    "Android could not start the microphone service. Keep Fieldnote open and try again. "
                            + safeMessage(error));
        }
    }

    public static void stop(Context context) {
        Snapshot before;
        synchronized (STATE_LOCK) {
            before = current;
            if (before.state != State.STARTING && before.state != State.RECORDING) return;
            // This flag is checked even if STOP is delivered while prepare() is still running.
            stopRequested = true;
        }
        try {
            context.getApplicationContext().startService(new Intent(context, RecordingService.class)
                    .setAction(ACTION_STOP).putExtra("token", before.token));
        } catch (RuntimeException ignored) {
            finishOnActiveWorker(before.token);
            // Before service creation, beginRecording observes stopRequested itself.
        }
    }

    /**
     * Queue a marker for this exact audio original. True acknowledges admission, not durability:
     * markerCount/lastMarkerOffsetMs advance after the local journal write, or markerError explains failure.
     * The tap's offset is captured here, independent of worker or storage latency.
     */
    public static boolean addMarker(Context context) {
        RecordingService service;
        MarkerRequest request;
        boolean queued;
        synchronized (STATE_LOCK) {
            Snapshot before = current;
            service = activeInstance;
            if (before.state != State.RECORDING || stopRequested
                    || before.startedElapsedRealtime <= 0 || service == null || service.destroyed
                    || service.worker == null || before.token == null || !before.token.equals(service.token)) return false;
            request = new MarkerRequest(before.token,
                    Math.max(0, SystemClock.elapsedRealtime() - before.startedElapsedRealtime), System.currentTimeMillis());
            service.pendingMarkers.addLast(request);
            // Queue admission and the stop flag use one lock. Finalization also drains this queue
            // if an earlier stop/interruption callback was already ahead of this runnable.
            queued = service.worker.post(() -> service.persistPendingMarkers(request.token));
            if (!queued) service.pendingMarkers.removeLastOccurrence(request);
        }
        if (!queued) service.markerResult(request, false, "The marker could not be queued. Try again while recording.");
        return queued;
    }

    private static void finishOnActiveWorker(String requestToken) {
        RecordingService service = activeInstance;
        if (service == null || service.destroyed || !requestToken.equals(service.token) || service.worker == null)
            return;
        service.worker.post(() -> {
            if (!requestToken.equals(current.token) || !requestToken.equals(service.token)) return;
            service.finishRecording("complete", null);
        });
    }

    public static void lock(Context context) {
        Snapshot before;
        synchronized (STATE_LOCK) {
            before = current;
            if (before.state != State.STARTING && before.state != State.RECORDING) return;
            lockRequested = true;
            current = new Snapshot(before.state, before.projectUri, before.entryId, before.startedAt,
                    before.startedElapsedRealtime, true, before.error, before.token,
                    before.markerCount, before.lastMarkerOffsetMs, before.markerError);
        }
        broadcast(context);
        try {
            context.getApplicationContext().startService(new Intent(context, RecordingService.class)
                    .setAction(ACTION_LOCK).putExtra("token", before.token));
        } catch (RuntimeException ignored) {
            // Recording is already locked in process state; notification will update on start.
        }
    }

    private static void externalFailure(Context context, String requestToken, String message) {
        synchronized (STATE_LOCK) {
            Snapshot before = current;
            if (!requestToken.equals(before.token)) return;
            current = new Snapshot(State.ERROR, before.projectUri, before.entryId, before.startedAt,
                    before.startedElapsedRealtime, before.locked, message, before.token,
                    before.markerCount, before.lastMarkerOffsetMs, before.markerError);
        }
        broadcast(context);
    }

    private static void broadcast(Context context) {
        context.sendBroadcast(new Intent(ACTION_STATE).setPackage(context.getPackageName()));
        RecordingWidget.refresh(context);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new ProjectStore(this);
        recorderThread = new HandlerThread("FieldnoteAudio");
        recorderThread.start();
        worker = new Handler(recorderThread.getLooper());
        activeInstance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Audio recording",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when Fieldnote is recording, with a Stop action.");
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Even a rejected getForegroundService click must be promoted before stopping.
        // The brief admission phase needs no microphone grant and performs no provider I/O.
        boolean widgetRequest = intent != null
                && RecordingShortcuts.ACTION_WIDGET_RECORD.equals(intent.getAction());
        if (widgetRequest && !startWidgetAdmission()) return START_NOT_STICKY;
        if (intent == null || finishing || destroyed) { finishService(); return START_NOT_STICKY; }
        if (RecordingShortcuts.isRecordingWidgetIntent(this, intent)) {
            toggleFromWidget();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        String requestedToken = intent.getStringExtra("token");
        if (requestedToken == null || !requestedToken.equals(current.token)) {
            if (token == null) finishService();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (token != null) return START_NOT_STICKY;
            token = requestedToken;
            project = current.projectUri;
            entryId = current.entryId;
            if (!startMicrophoneForeground()) return START_NOT_STICKY;
            worker.post(this::beginRecording);
        } else if (ACTION_STOP.equals(action)) {
            synchronized (STATE_LOCK) {
                if (!requestedToken.equals(current.token)) return START_NOT_STICKY;
                stopRequested = true;
            }
            FloatingRecorderService.stopLinkedVideoForAudio(current);
            worker.post(() -> finishRecording("complete", null));
        } else if (ACTION_LOCK.equals(action)) {
            lockRequested = true;
            updateNotification();
        }
        return START_NOT_STICKY;
    }

    /** A stale Stop is a broadcast, so it never creates a service or foreground obligation. */
    static void stopFromWidget(Context context, Intent intent) {
        if (!RecordingShortcuts.isRecordingWidgetStopIntent(context, intent)) return;
        String expectedEntry = intent.getStringExtra(RecordingShortcuts.EXTRA_WIDGET_ENTRY);
        String expectedSession = intent.getStringExtra(RecordingShortcuts.EXTRA_WIDGET_SESSION);
        synchronized (STATE_LOCK) {
            boolean matches = expectedEntry != null && expectedEntry.equals(current.entryId)
                    && expectedSession != null && expectedSession.equals(current.token)
                    && isRecording();
            // Keep admission and stop's token snapshot atomic with respect to a new original.
            if (matches) stop(context);
        }
        RecordingWidget.refresh(context);
    }

    /** A launcher widget click grants microphone foreground admission without an Activity. */
    private void toggleFromWidget() {
        if (token == null && isBusy() && (stopRequested || current.state == State.SAVING)) {
            // A new widget service can arrive while the previous instance's worker
            // finishes teardown. It owns no recorder and must not inherit that token.
            finishService();
            return;
        }
        if (!isBusy() && FloatingRecorderService.isBusyForHandoff()) {
            widgetSetupRequired("Finish the floating capture first, then tap the recording widget again.");
            return;
        }
        boolean stopExisting = false;
        boolean admitQueuedStart = false;
        synchronized (STATE_LOCK) {
            if (isRecording()) {
                // Repeated taps during provider lookup or prepare() cancel the same request.
                // Never replace its token, project, or original with a second recording.
                stopRequested = true;
                stopExisting = true;
                if (token == null) {
                    // A normal in-app START may still be queued behind this widget intent.
                    token = current.token;
                    project = current.projectUri;
                    entryId = current.entryId;
                    admitQueuedStart = true;
                }
            } else {
                // The old service must finish teardown before another original can begin.
                if (isBusy() || token != null || destroyed) return;
                token = UUID.randomUUID().toString();
                entryId = UUID.randomUUID().toString();
                stopRequested = false;
                lockRequested = true;
                widgetStarted = true;
                current = new Snapshot(State.STARTING, null, entryId, 0, 0, true, null, token);
            }
        }
        if (stopExisting) {
            if (admitQueuedStart) {
                if (startMicrophoneForeground()) worker.post(this::beginRecording);
            }
            stop(this);
            return;
        }
        broadcast(this);
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || (keyguard != null && keyguard.isKeyguardLocked())) {
            widgetSetupRequired("Unlock the phone, then tap the recording widget again.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            widgetSetupRequired("Open Fieldnote and allow microphone access, then tap the recording widget again.");
            return;
        }
        // Bind the selected project to this tap before asynchronous cache/provider I/O.
        // Opening another project while capture starts or records must not redirect it.
        final Uri destination;
        try {
            destination = store.currentProject();
            if (destination == null) {
                widgetSetupRequired("Open a project in Fieldnote, then tap the recording widget again.");
                return;
            }
        } catch (RuntimeException failure) {
            widgetSetupRequired("Open a project in Fieldnote, then tap the recording widget again.");
            return;
        }
        project = destination;
        update(State.STARTING, null);
        // Declare the while-in-use type before any provider lookup outlives the click grant.
        if (!startMicrophoneForeground()) return;
        worker.post(() -> {
            try {
                if (stopRequested || destroyed) { cancelBeforeRecording(); return; }
                if (!store.isWorkingProject(destination)) throw new IOException("Open an editable project in Fieldnote first.");
                if (store.readCachedProject(destination) == null) store.readProject(destination);
                if (stopRequested || destroyed) { cancelBeforeRecording(); return; }
                // Overlay state belongs to the main thread. Recheck after provider I/O.
                main.post(() -> {
                    boolean floatingBusy = FloatingRecorderService.isBusyForHandoff();
                    worker.post(() -> {
                        if (stopRequested || destroyed) { cancelBeforeRecording(); return; }
                        if (floatingBusy) {
                            widgetSetupRequired("Finish the floating capture first, then tap the recording widget again.");
                            return;
                        }
                        project = destination;
                        update(State.STARTING, null);
                        NotificationManager notifications = getSystemService(NotificationManager.class);
                        if (notifications != null) notifications.cancel(WIDGET_SETUP_NOTIFICATION_ID);
                        beginRecording();
                    });
                });
            } catch (Exception failure) {
                if (stopRequested || destroyed) cancelBeforeRecording();
                else widgetSetupRequired(safeMessage(failure));
            }
        });
    }

    private boolean startWidgetAdmission() {
        if (foregroundStarted) return true; // Never downgrade an existing microphone service.
        try {
            Notification preparing = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Preparing Fieldnote recording")
                    .setOngoing(true).setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build();
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION_ID, preparing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            else if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, preparing, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
            else startForeground(NOTIFICATION_ID, preparing);
            foregroundStarted = true;
            admissionOnly = true;
            return true;
        } catch (RuntimeException failure) {
            widgetSetupRequired("Android could not start the recording widget. Open Fieldnote and try again. "
                    + safeMessage(failure));
            return false;
        }
    }

    private boolean startMicrophoneForeground() {
        try {
            Notification notification = notification("Starting microphone…");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
            admissionOnly = false;
            return true;
        } catch (RuntimeException failure) {
            String explanation = "Android denied microphone recording. Check permission and try while Fieldnote is open. "
                    + safeMessage(failure);
            if (widgetStarted) widgetSetupRequired(explanation);
            else { terminalState = State.ERROR; terminalMessage = explanation; stopSelf(); }
            return false;
        }
    }

    /** Setup is explicit: a failed widget tap never launches an Activity or an overlay. */
    private void widgetSetupRequired(String explanation) {
        terminalState = State.ERROR;
        terminalMessage = explanation;
        main.post(() -> {
            Toast.makeText(getApplicationContext(), explanation, Toast.LENGTH_LONG).show();
            NotificationManager notifications = getSystemService(NotificationManager.class);
            Intent openFieldnote = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent setup = PendingIntent.getActivity(this, 2005, openFieldnote,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Set up the recording widget")
                    .setContentText(explanation).setStyle(new Notification.BigTextStyle().bigText(explanation))
                    .setContentIntent(setup).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
            try { if (notifications != null) notifications.notify(WIDGET_SETUP_NOTIFICATION_ID, notification); }
            catch (RuntimeException ignored) { /* The toast still explains setup without notification access. */ }
        });
        finishService();
    }

    private void cancelBeforeRecording() {
        releaseRecorder();
        releaseWakeLock();
        abandon("The widget recording was cancelled before capture started.");
        MetadataCollector.stop(this);
        terminalState = State.IDLE;
        terminalMessage = null;
        finishService();
    }

    private void beginRecording() {
        try {
            if (stopRequested) {
                if (widgetStarted) cancelBeforeRecording();
                else fail("The press was too short to start recording. Hold the microphone a little longer.");
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Microphone permission was not granted.");
            }
            MetadataCollector.start(this);
            startMetadata = MetadataCollector.snapshot(this);
            pendingFile = store.newPendingFile("audio");
            if (pendingFile.getParentFile().getUsableSpace() < 4L * 1024L * 1024L) {
                throw new IOException("Not enough device storage to begin an audio recording.");
            }
            put(startMetadata, "audio", audioSettings(48000));
            put(startMetadata, "capturePhase", "preparing");
            put(startMetadata, "requestedAt", Instant.now().toString());
            store.beginCapture(project, entryId, "audio", pendingFile, System.currentTimeMillis(), startMetadata);
            try {
                prepareRecorder(48000);
            } catch (IOException | RuntimeException firstError) {
                releaseRecorder();
                prepareRecorder(44100);
                put(startMetadata, "audio", audioSettings(44100));
            }
            if (stopRequested) {
                if (widgetStarted) cancelBeforeRecording();
                else fail("The press was too short to start recording. Hold the microphone a little longer.");
                return;
            }
            registerSilenceDetection();
            startedAt = System.currentTimeMillis();
            startedElapsed = SystemClock.elapsedRealtime();
            recorder.start();
            recorderStarted = true;
            acquireWakeLock(MAX_DURATION_MS + 60000L);
            put(startMetadata, "capturePhase", "recording");
            put(startMetadata, "startedAtSource", "wall-clock at successful MediaRecorder.start invocation");
            store.beginCapture(project, entryId, "audio", pendingFile, startedAt, startMetadata);
            update(State.RECORDING, null);
            updateNotification();
            if (microphoneIsSilenced()) {
                finishRecording("interrupted", "Android did not make the microphone available to Fieldnote.");
            } else if (stopRequested) {
                finishRecording("complete", null);
            }
        } catch (Exception error) {
            if (recorderStarted) {
                finishRecording("interrupted", "Recording was interrupted: " + safeMessage(error));
            } else {
                fail("Could not start audio recording. " + safeMessage(error));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void prepareRecorder(int sampleRate) throws IOException {
        recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioChannels(1);
        recorder.setAudioSamplingRate(sampleRate);
        recorder.setAudioEncodingBitRate(128000);
        recorder.setOutputFile(pendingFile.getAbsolutePath());
        recorder.setMaxDuration(MAX_DURATION_MS);
        long available = pendingFile.getParentFile().getUsableSpace();
        recorder.setMaxFileSize(Math.min(MAX_FILE_BYTES, Math.max(1024L * 1024L, available - 2L * 1024L * 1024L)));
        recorder.setOnErrorListener((source, what, extra) -> worker.post(() ->
                finishRecording("interrupted", "The microphone recorder reported an error (" + what + ", " + extra + ").")));
        recorder.setOnInfoListener((source, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                worker.post(() -> finishRecording("limit_reached", "The six-hour recording limit was reached."));
            } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                worker.post(() -> finishRecording("limit_reached", "The recording reached its available file-size limit."));
            }
        });
        recorder.prepare();
    }

    private void registerSilenceDetection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || recorder == null) return;
        silenceCallback = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                if (!recorderStarted) return;
                for (AudioRecordingConfiguration config : configs) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && config.isClientSilenced()) {
                        finishRecording("interrupted", "Android interrupted microphone access. The available audio was kept.");
                        return;
                    }
                }
            }
        };
        try {
            // Register before start: a system-silenced start need not emit another change later.
            recorder.registerAudioRecordingCallback(command -> worker.post(command), silenceCallback);
        } catch (RuntimeException error) {
            silenceCallback = null;
            put(startMetadata, "audioSilenceMonitoring", "unavailable: " + safeMessage(error));
        }
    }

    private boolean microphoneIsSilenced() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || recorder == null) return false;
        try {
            AudioDeviceInfo routed = recorder.getRoutedDevice();
            if (routed != null) {
                JSONObject device = new JSONObject();
                put(device, "type", routed.getType());
                put(device, "name", String.valueOf(routed.getProductName()));
                put(startMetadata, "audioInputDevice", device);
            }
            AudioRecordingConfiguration configuration = recorder.getActiveRecordingConfiguration();
            return configuration != null && configuration.isClientSilenced();
        } catch (RuntimeException error) {
            put(startMetadata, "audioSilenceMonitoring", "unavailable: " + safeMessage(error));
            return false;
        }
    }

    private void finishRecording(String status, String warning) {
        if (!recorderStarted) return;
        recorderStarted = false;
        update(State.SAVING, null);
        updateNotification();
        acquireWakeLock(10 * 60 * 1000L);
        long finalizationElapsed = SystemClock.elapsedRealtime();
        long elapsed = Math.max(0, finalizationElapsed - startedElapsed);
        long observedStopWall = System.currentTimeMillis();
        // Keep elapsed duration truthful if the user changes the wall clock while recording.
        long endedAt = startedAt + elapsed;
        boolean stoppedCleanly = true;
        String stopFailure = null;
        try {
            recorder.stop();
        } catch (RuntimeException error) {
            stoppedCleanly = false;
            stopFailure = safeMessage(error);
        } finally {
            releaseRecorder();
        }
        // Do not wait for marker I/O before stopping the microphone. Accepted markers can
        // be queued behind this callback, so drain them before the journal is finalized.
        persistPendingMarkers(token, true);
        try {
            long encodedDuration = validateAudio(pendingFile);
            JSONObject metadata = startMetadata == null ? new JSONObject() : startMetadata;
            put(metadata, "endSnapshot", MetadataCollector.snapshot(this));
            put(metadata, "finalizationSnapshot", MetadataCollector.snapshot(this));
            put(metadata, "finalizedAt", Instant.now().toString());
            put(metadata, "monotonicDurationMs", elapsed);
            put(metadata, "encodedDurationMs", encodedDuration);
            put(metadata, "observedWallClockAtStop", Instant.ofEpochMilli(observedStopWall).toString());
            put(metadata, "finalizationStartedAt", Instant.ofEpochMilli(observedStopWall).toString());
            put(metadata, "endedAtSource", "start wall-clock plus monotonic elapsed time at MediaRecorder.stop invocation");
            put(metadata, "wallClockDriftMs", observedStopWall - endedAt);
            put(metadata, "capturePhase", "finalized");
            if (!stoppedCleanly) {
                if (!"limit_reached".equals(status)) status = "interrupted";
                put(metadata, "recorderStopError", stopFailure);
                if (warning == null) warning = "Recording was interrupted; a playable partial audio file was saved.";
            }
            if (warning != null) put(metadata, "interruptionReason", warning);
            // The original and its finalized journal become durable locally. stageCapture
            // queues ZIP publication independently and reports queue-start failures there;
            // it never waits for the archive/provider or throws after successful staging.
            store.stageCapture(project, entryId, "audio", pendingFile, startedAt, endedAt, metadata, status);
            terminalState = State.IDLE;
            terminalMessage = warning;
        } catch (Exception error) {
            String detail = safeMessage(error);
            if (error instanceof InvalidRecordingException) {
                abandon("No valid audio frames: " + detail);
                terminalState = State.ERROR;
                terminalMessage = "No playable audio was produced. Hold the button longer and check microphone access. " + detail;
            } else {
                terminalState = State.ERROR;
                terminalMessage = "Audio is retained in pending storage, but its local capture journal could not be finalized. " + detail;
            }
        } finally {
            releaseWakeLock();
            MetadataCollector.stop(this);
            finishService();
        }
    }

    private static long validateAudio(File file) throws InvalidRecordingException {
        if (file == null || !file.isFile() || file.length() == 0) {
            throw new InvalidRecordingException("The recorder produced an empty file.");
        }
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            String hasAudio = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            String duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long millis = duration == null ? 0 : Long.parseLong(duration);
            if (!"yes".equals(hasAudio) || millis <= 0) {
                throw new InvalidRecordingException("No valid audio duration was found.");
            }
            return millis;
        } catch (InvalidRecordingException error) {
            throw error;
        } catch (Exception error) {
            throw new InvalidRecordingException("The audio file could not be finalized.");
        } finally {
            try { reader.release(); } catch (Exception ignored) { /* Reader is already closing. */ }
        }
    }

    private void fail(String message) {
        releaseRecorder();
        releaseWakeLock();
        abandon(message);
        MetadataCollector.stop(this);
        if (widgetStarted) { widgetSetupRequired(message); return; }
        terminalState = State.ERROR;
        terminalMessage = message;
        finishService();
    }

    private void abandon(String reason) {
        if (pendingFile == null) return;
        try { store.abandonCapture(pendingFile, reason); }
        catch (IOException ignored) { /* Never delete evidence because its failure journal could not be updated. */ }
    }

    private void releaseRecorder() {
        if (recorder == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && silenceCallback != null) {
            try { recorder.unregisterAudioRecordingCallback(silenceCallback); } catch (RuntimeException ignored) { }
        }
        silenceCallback = null;
        try { recorder.release(); } catch (RuntimeException ignored) { }
        recorder = null;
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    private void acquireWakeLock(long timeoutMs) {
        if (wakeLock == null) {
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":audio");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(timeoutMs);
    }

    private void update(State state, String error) {
        synchronized (STATE_LOCK) {
            if (token == null || !token.equals(current.token)) return;
            Snapshot before = current;
            current = new Snapshot(state, project, entryId, startedAt, startedElapsed,
                    lockRequested, error, token,
                    before.markerCount, before.lastMarkerOffsetMs, before.markerError);
        }
        broadcast(this);
    }

    /** Runs only on the recorder worker, including the final drain after recorder.stop(). */
    private void persistPendingMarkers(String requestToken) {
        persistPendingMarkers(requestToken, false);
    }

    private void persistPendingMarkers(String requestToken, boolean finalAttempt) {
        ArrayDeque<MarkerRequest> batch = new ArrayDeque<>();
        synchronized (STATE_LOCK) {
            if (requestToken == null || !requestToken.equals(token) || !requestToken.equals(current.token)) return;
            if (finalAttempt) {
                batch.addAll(failedMarkers);
                failedMarkers.clear();
            }
            batch.addAll(pendingMarkers);
            pendingMarkers.clear();
        }
        for (MarkerRequest request : batch) {
            if (!requestToken.equals(request.token)) continue;
            String failure = null;
            try {
                store.addPendingAudioMarker(pendingFile, request.id, request.offsetMs, "", request.markedAt);
            } catch (IOException | RuntimeException error) {
                failedMarkers.addLast(request);
                failure = "Could not save that audio marker. " + safeMessage(error);
            }
            markerResult(request, failure == null, failure);
        }
    }

    private void markerResult(MarkerRequest request, boolean saved, String failure) {
        synchronized (STATE_LOCK) {
            Snapshot before = current;
            if (!request.token.equals(token) || !request.token.equals(before.token)) return;
            current = new Snapshot(before.state, before.projectUri, before.entryId, before.startedAt,
                    before.startedElapsedRealtime, before.locked, before.error, before.token,
                    saved ? before.markerCount + 1 : before.markerCount,
                    saved ? Math.max(before.lastMarkerOffsetMs, request.offsetMs) : before.lastMarkerOffsetMs,
                    saved && !failedMarkers.isEmpty() ? before.markerError : failure);
        }
        broadcast(this);
    }

    private void updateNotification() {
        Snapshot state = current;
        String text = state.state == State.SAVING ? "Saving the original audio and its timestamps…"
                : state.state == State.STARTING ? "Starting microphone…"
                : state.locked ? "Recording is locked. Tap Stop to save." : "Release the button to save. Slide up to lock.";
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent stopIntent = new Intent(this, RecordingService.class).setAction(ACTION_STOP)
                .putExtra("token", current.token);
        PendingIntent stop = PendingIntent.getService(this, 10, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(current.state == State.SAVING ? "Saving Fieldnote audio" : "Fieldnote audio recording")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "Stop & save", stop).build());
        if (startedAt > 0 && current.state == State.RECORDING) {
            builder.setWhen(startedAt).setUsesChronometer(true);
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            builder.setContentIntent(PendingIntent.getActivity(this, 11, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return builder.build();
    }

    private void finishService() {
        finishing = true;
        foregroundStarted = false;
        admissionOnly = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (!destroyed) stopSelf();
    }

    @Override public void onTimeout(int startId) {
        // Admission normally ends synchronously, before any storage access.
        if (admissionOnly) finishService();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        synchronized (STATE_LOCK) { stopRequested = true; }
        if (worker != null) worker.post(() -> finishRecording("interrupted", "Recording ended when the app was removed from recent apps."));
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        synchronized (STATE_LOCK) {
            destroyed = true;
            if (activeInstance == this) activeInstance = null;
            if (token != null && token.equals(current.token)) stopRequested = true;
        }
        if (worker != null) {
            worker.post(() -> {
                if (recorderStarted) finishRecording("interrupted", "Android stopped the recording service.");
                else {
                    releaseRecorder();
                    releaseWakeLock();
                    if (token != null) MetadataCollector.stop(this);
                }
                // ZIP publication continues independently after local staging. Do not accept
                // another recording until this service instance is fully torn down.
                // Otherwise a rapid next press can reach the old instance while stopSelf is pending.
                update(terminalState == null ? State.ERROR : terminalState,
                        terminalState == null ? "The recording service ended before audio could be saved." : terminalMessage);
                recorderThread.quitSafely();
            });
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static JSONObject audioSettings(int sampleRate) {
        JSONObject result = new JSONObject();
        put(result, "container", "MPEG-4");
        put(result, "codec", "AAC");
        put(result, "channelsRequested", 1);
        put(result, "sampleRateHzRequested", sampleRate);
        put(result, "bitRateBpsRequested", 128000);
        put(result, "source", "MIC");
        put(result, "maxDurationMs", MAX_DURATION_MS);
        return result;
    }

    private static void put(JSONObject object, String name, Object value) {
        try { object.put(name, value); } catch (JSONException ignored) { /* Only finite primitive values are used. */ }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class InvalidRecordingException extends IOException {
        InvalidRecordingException(String message) { super(message); }
    }
}
