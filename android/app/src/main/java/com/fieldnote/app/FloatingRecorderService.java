package com.fieldnote.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The visible, compact overlay host. Audio originals remain owned by RecordingService. */
@SuppressWarnings("deprecation")
public final class FloatingRecorderService extends Service {
    private static final String SHOW = "com.fieldnote.app.SHOW_FLOATING_RECORDER";
    private static final String HIDE = "com.fieldnote.app.HIDE_FLOATING_RECORDER";
    private static final String STOP = "com.fieldnote.app.STOP_FLOATING_RECORDER";
    private static final String CHANNEL = "fieldnote_floating_recorder";
    private static final int NOTIFICATION_ID = 1002;
    private static final int SETUP_NOTIFICATION_ID = 1003;
    private static final int MIC_SIZE_DP = 96;
    private static final int COMPACT_SIZE_DP = 160;
    private static final int GESTURE_SIZE_DP = 336;
    private static final long HOLD_DELAY_MS = 180;
    // A non-exported service additionally rejects arbitrary/replayed process launches.
    private static final String ADMISSION = UUID.randomUUID().toString();
    private static volatile FloatingRecorderService active;
    private static volatile boolean showing;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<PhotoRequest> photos = new ArrayDeque<>();
    private final String instanceToken = UUID.randomUUID().toString();
    private SharedPreferences preferences;
    private FloatingTextDraft drafts;
    private ProjectStore store;
    private Context visualContext;
    private WindowManager windows;
    private WindowManager.LayoutParams micWindow;
    private FrameLayout surface, preview;
    private MicrophoneView microphone;
    private ImageButton moveHandle;
    private GestureGuideView guide;
    private RecordingGesture gesture;
    private InlineCamera camera;
    private AppOpsManager appOps;
    private Uri project;
    private CaptureRef heldCapture, pendingText, pendingVideo, markerCapture;
    private boolean touching, touchClick, stopOnRelease, startedByTouch, closeOnlyTouch;
    private int touchPointerId = -1;
    private int movePointerId = -1, moveStartX, moveStartY;
    private boolean moving, moveDragged;
    private float moveDownX, moveDownY;
    private boolean destroyed, closing, receiverRegistered, previewAttached, textSaving;
    private boolean foregroundReady, cameraForeground, dark;
    private boolean widgetAdmission, widgetCameraAdmission, widgetResolving;
    private long widgetGeneration;
    private int centerX, centerY, gestureSizePx, pendingMarkers, ink, muted;
    private float touchX, touchY;
    private String observedError, observedMarkerError;
    private AlertDialog textDialog;
    private EditText textInput;
    private CaptureRef textCapture;

    /** MainActivity admits this only after a project and the required permissions are ready. */
    public static boolean show(Context context, Uri project) {
        if (project == null) return false;
        Context app = context.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) return false;
        try {
            app.startForegroundService(new Intent(app, FloatingRecorderService.class).setAction(SHOW)
                    .putExtra("admission", ADMISSION).putExtra("project", project.toString()));
            return true;
        } catch (RuntimeException failure) {
            Toast.makeText(app, "Could not show the floating microphone. " + message(failure), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    public static boolean isShowing() { return showing; }

    public static boolean isCameraBusy() {
        FloatingRecorderService service = active;
        return service != null && (service.cameraWorkPending() || service.pendingVideo != null);
    }

    /** Main-thread handoff gate, including a video that owns an embedded microphone track. */
    public static boolean isBusyForHandoff() {
        FloatingRecorderService service = active;
        return service != null && (service.widgetResolving || service.touching || service.moving || isCameraBusy()
                || service.textDialog != null || service.pendingText != null || service.textSaving);
    }

    /** Main-thread audio Stop also finishes video linked by this overlay to that original. */
    static void stopLinkedVideoForAudio(RecordingService.Snapshot expected) {
        FloatingRecorderService service = active;
        if (service == null || expected == null) return;
        CaptureRef target = CaptureRef.of(expected);
        Runnable stop = () -> {
            if (active == service && !service.closing && !service.destroyed
                    && service.sameProject(expected) && target.matches(RecordingService.snapshot()))
                service.stopGestureVideo();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run();
        else service.main.post(stop);
    }

    /** Hiding preserves an explicitly locked audio recording, which has its own notification. */
    public static void hide(Context context) {
        FloatingRecorderService service = active;
        if (service != null) service.main.post(() -> service.dismissOverlay(false));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        preferences = getSharedPreferences("fieldnote", MODE_PRIVATE);
        drafts = new FloatingTextDraft(preferences);
        store = new ProjectStore(this);
        dark = preferences.getBoolean("dark_mode", true);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Floating microphone", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("The microphone control is visible over other apps.");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notifications.createNotificationChannel(channel);
        try { setForeground(false, false); }
        catch (RuntimeException failure) { toast("Could not show the floating microphone. " + message(failure)); stopSelf(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundReady || intent == null || closing || destroyed) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (RecordingShortcuts.isWidgetIntent(this, intent)) {
            showFromWidget();
            return START_NOT_STICKY;
        }
        if ((HIDE.equals(action) || STOP.equals(action)) && instanceToken.equals(intent.getStringExtra("instance"))) {
            dismissOverlay(STOP.equals(action));
            return START_NOT_STICKY;
        }
        if (!SHOW.equals(action) || !ADMISSION.equals(intent.getStringExtra("admission"))) {
            if (!showing) stopSelf();
            return START_NOT_STICKY;
        }
        String value = intent.getStringExtra("project");
        if (value == null || !Settings.canDrawOverlays(this)) { dismissOverlay(false); return START_NOT_STICKY; }
        ++widgetGeneration;
        widgetResolving = false;
        showProject(Uri.parse(value));
        return START_NOT_STICKY;
    }

    /** Widget interaction grants the service start; no Activity is needed on this path. */
    private void showFromWidget() {
        if (!Settings.canDrawOverlays(this)) {
            widgetSetupRequired("Enable Display over other apps for Fieldnote, then tap the widget again.");
            return;
        }
        boolean hasCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || (hasCamera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) {
            widgetSetupRequired("Open Fieldnote to allow microphone and camera access, then tap the widget again.");
            return;
        }
        if (!screenAvailable()) { dismissOverlay(false); return; }
        if (widgetResolving || touching || cameraWorkPending() || textDialog != null
                || pendingText != null || pendingVideo != null || textSaving) return;
        try {
            // Declare while-in-use types while the widget's user interaction still
            // admits them, before any project/provider I/O. Retain them until Hide.
            widgetAdmission = true;
            widgetCameraAdmission = hasCamera;
            setForeground(false, false);
        } catch (RuntimeException failure) {
            widgetSetupRequired("Android could not enable the floating microphone. Open Fieldnote to finish setup. " + message(failure));
            return;
        }
        widgetResolving = true;
        final long generation = ++widgetGeneration;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        final Uri boundProject = RecordingService.isBusy() ? capture.projectUri : null;
        io.execute(() -> {
            try {
                Uri destination = boundProject != null ? boundProject : store.lastCreatedProject();
                if (destination == null) throw new java.io.IOException("Create a project in Fieldnote, then tap the widget again.");
                if (store.readCachedProject(destination) == null) {
                    if (!store.isArchive(destination)) throw new java.io.IOException("Open and convert the latest project in Fieldnote first.");
                    store.readProject(destination);
                }
                main.post(() -> {
                    if (destroyed || closing || generation != widgetGeneration) return;
                    widgetResolving = false;
                    if (!Settings.canDrawOverlays(this) || !screenAvailable()) { dismissOverlay(false); return; }
                    showProject(destination);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (destroyed || closing || generation != widgetGeneration) return;
                    widgetResolving = false;
                    widgetSetupRequired(message(failure));
                });
            }
        });
    }

    /** Failure stays on the home screen; only an explicit notification tap opens setup. */
    private void widgetSetupRequired(String explanation) {
        toast(explanation);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) {
            PendingIntent setup = PendingIntent.getActivity(this, 2004, RecordingShortcuts.overlayIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Set up the home-screen microphone")
                    .setContentText(explanation).setStyle(new Notification.BigTextStyle().bigText(explanation))
                    .setContentIntent(setup).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
            try { notifications.notify(SETUP_NOTIFICATION_ID, notification); }
            catch (RuntimeException ignored) { /* The toast still explains setup if notifications are unavailable. */ }
        }
        dismissOverlay(false);
    }

    private void showProject(Uri requested) {
        RecordingService.Snapshot capture = RecordingService.snapshot();
        // The current original is authoritative; never redirect a live recording.
        if (RecordingService.isBusy() && capture.projectUri != null) requested = capture.projectUri;
        if (project != null && !project.equals(requested)
                && (touching || cameraWorkPending() || textDialog != null || pendingText != null || pendingVideo != null || textSaving)) {
            toast("Finish the current floating capture before switching projects.");
            return;
        }
        project = requested;
        if (surface == null) {
            try { createOverlay(); }
            catch (RuntimeException failure) { toast("Could not show the floating microphone. " + message(failure)); dismissOverlay(false); }
        }
        if (!closing) {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            if (notifications != null) notifications.cancel(SETUP_NOTIFICATION_ID);
        }
        updateState();
    }

    private void createOverlay() {
        Context windowContext = this;
        if (Build.VERSION.SDK_INT >= 30) {
            DisplayManager displays = getSystemService(DisplayManager.class);
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("No display is available for the floating microphone.");
            windowContext = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        visualContext = new ContextThemeWrapper(windowContext, dark ? R.style.FieldnoteDark : R.style.FieldnoteLight);
        windows = visualContext.getSystemService(WindowManager.class);
        ink = Color.parseColor(dark ? "#E8F1FF" : "#17314D");
        muted = Color.parseColor(dark ? "#ABC0DB" : "#536C87");
        int panel = Color.parseColor(dark ? "#172943" : "#E7F0FF");
        int accent = Color.parseColor(dark ? "#91C5FF" : "#2C68B2");
        int recordingColor = Color.parseColor(dark ? "#C69CFF" : "#7C3AED");
        gesture = new RecordingGesture(dp(RecordingGesture.ACTIVATION_DISTANCE_DP), dp(RecordingGesture.HOME_RADIUS_DP), true);
        surface = new FrameLayout(visualContext);
        surface.setClipChildren(false);
        surface.setClipToPadding(false);
        guide = new GestureGuideView(visualContext, dp(RecordingGesture.ACTIVATION_DISTANCE_DP), dp(RecordingGesture.HOME_RADIUS_DP), true);
        guide.configureColors(accent, Color.parseColor(dark ? "#0C1627" : "#F7FAFF"), ink);
        surface.addView(guide, new FrameLayout.LayoutParams(-1, -1));
        microphone = new MicrophoneView(visualContext, dark, accent, recordingColor, new MicrophoneView.ActionListener() {
            @Override public void onPhoto() { microphoneAction("photo"); }
            @Override public void onVideo() { microphoneAction("video"); }
            @Override public void onText() { microphoneAction("text"); }
            @Override public void onMarker() { addMarker(); }
        });
        microphone.setId(R.id.record_button);
        microphone.setClickable(true);
        microphone.setFocusable(true);
        microphone.setOnTouchListener(this::recordTouch);
        microphone.setOnClickListener(view -> {
            if (touchClick || moving || !captureAllowed()) return;
            RecordingService.Snapshot capture = RecordingService.snapshot();
            if (RecordingService.isRecording() && sameProject(capture)) stopCapture(CaptureRef.of(capture));
            else if (canStartAudio()) { startAudio(); RecordingService.lock(this); }
        });
        surface.addView(microphone, new FrameLayout.LayoutParams(dp(MIC_SIZE_DP), dp(MIC_SIZE_DP), Gravity.CENTER));
        moveHandle = new ImageButton(visualContext);
        moveHandle.setId(R.id.floating_move_handle);
        moveHandle.setImageResource(R.drawable.ic_move_handle);
        moveHandle.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
        moveHandle.setContentDescription("Move floating microphone");
        moveHandle.setTooltipText("Drag to move");
        moveHandle.setPadding(dp(12), dp(4), dp(12), dp(4));
        android.graphics.drawable.GradientDrawable grip = new android.graphics.drawable.GradientDrawable();
        grip.setColor(panel);
        grip.setCornerRadius(dp(16));
        grip.setStroke(dp(1), accent);
        moveHandle.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x3391c5ff), grip, null));
        moveHandle.setOnTouchListener(this::moveTouch);
        moveHandle.setOnClickListener(view -> toast("Drag this handle to move the microphone. Swipe the mic down-right to close."));
        moveHandle.setTranslationY(-dp(64));
        surface.addView(moveHandle, new FrameLayout.LayoutParams(dp(48), dp(32), Gravity.CENTER));
        measurePosition();
        micWindow = overlayParams(dp(COMPACT_SIZE_DP), dp(COMPACT_SIZE_DP));
        micWindow.setTitle("Fieldnote floating microphone");
        placeMicWindow(dp(COMPACT_SIZE_DP));
        windows.addView(surface, micWindow);
        showing = true;
        preview = new FrameLayout(visualContext);
        preview.setId(R.id.inline_camera);
        camera = new InlineCamera(visualContext, preview, new InlineCamera.Listener() {
            @Override public void onStateChanged(boolean busy, boolean recording) {
                if (destroyed) return;
                if (!busy) {
                    removePreview();
                    if (cameraForeground) {
                        try { setForeground(false, false); }
                        catch (RuntimeException failure) { dismissOverlay(false); return; }
                    }
                    if (closing) { finishClosing(); return; }
                }
                updateState();
            }
            @Override public void onSaved() { if (!destroyed && !closing) updateState(); }
            @Override public void onError(String error) { if (!destroyed && !closing) toast(error); }
        });
        // Motion sensors remain Activity-owned. View.isShown() cannot distinguish an
        // overlay that another app has force-hidden with setHideOverlayWindows(true).
        IntentFilter filter = new IntentFilter(RecordingService.ACTION_STATE);
        registerReceiver(recordingReceiver, filter, "com.fieldnote.app.INTERNAL", main, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, screenFilter);
        receiverRegistered = true;
        appOps = getSystemService(AppOpsManager.class);
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), permissionListener);
        MetadataCollector.start(this);
        main.post(visibilityCheck);
        restoreTextDraft();
    }

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateState(); }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { dismissOverlay(false); }
    };
    private final AppOpsManager.OnOpChangedListener permissionListener = (op, packageName) -> main.post(() -> {
        if (!Settings.canDrawOverlays(this)) dismissOverlay(false);
    });
    private final Runnable visibilityCheck = new Runnable() {
        @Override public void run() {
            if (closing || destroyed) return;
            if (!Settings.canDrawOverlays(FloatingRecorderService.this) || !screenAvailable()) { dismissOverlay(false); return; }
            updateState();
            main.postDelayed(this, 500);
        }
    };

    private WindowManager.LayoutParams overlayParams(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        return params;
    }

    private void placeMicWindow(int size) {
        // Keep the idle hit area narrow so transparent space beside the handle
        // does not cover unrelated launcher icons. Gesture mode remains square.
        micWindow.width = size == dp(COMPACT_SIZE_DP) ? dp(MIC_SIZE_DP) : size;
        micWindow.height = size;
        micWindow.x = centerX - micWindow.width / 2;
        micWindow.y = centerY - size / 2;
    }

    /** Moving has its own hit target and pointer; it never starts or transfers a mic hold. */
    private boolean moveTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!captureAllowed() || touching || cameraWorkPending() || pendingVideo != null
                    || textDialog != null || pendingText != null || textSaving) return true;
            main.removeCallbacks(contractGesture);
            moving = true;
            moveDragged = false;
            movePointerId = event.getPointerId(event.getActionIndex());
            moveDownX = event.getRawX();
            moveDownY = event.getRawY();
            moveStartX = centerX;
            moveStartY = centerY;
            view.setPressed(true);
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return true;
        }
        if (!moving) return true;
        if (action == MotionEvent.ACTION_MOVE) {
            int pointer = event.findPointerIndex(movePointerId);
            if (pointer < 0) { finishMove(view, true); return true; }
            float rawX = event.getRawX() + event.getX(pointer) - event.getX();
            float rawY = event.getRawY() + event.getY(pointer) - event.getY();
            float dx = rawX - moveDownX, dy = rawY - moveDownY;
            moveDragged |= Math.hypot(dx, dy) > android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            Rect available = screenBounds();
            int half = gestureSizePx / 2;
            centerX = Math.max(half, Math.min(available.width() - half, moveStartX + Math.round(dx)));
            centerY = Math.max(half, Math.min(available.height() - half, moveStartY + Math.round(dy)));
            updateMicPosition();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            finishMove(view, true);
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getPointerId(event.getActionIndex()) == movePointerId) {
            finishMove(view, false);
        }
        return true;
    }

    private void finishMove(View view, boolean cancelled) {
        moving = false;
        movePointerId = -1;
        view.setPressed(false);
        if (cancelled) {
            centerX = moveStartX;
            centerY = moveStartY;
        } else {
            Rect available = screenBounds();
            int half = gestureSizePx / 2;
            int spanX = available.width() - gestureSizePx, spanY = available.height() - gestureSizePx;
            preferences.edit().putFloat("floating_position_x", spanX > 0 ? (float) (centerX - half) / spanX : .5f)
                    .putFloat("floating_position_y", spanY > 0 ? (float) (centerY - half) / spanY : .5f).apply();
        }
        updateMicPosition();
        main.post(contractGesture);
        if (!cancelled && !moveDragged) view.performClick();
    }

    private void updateMicPosition() {
        if (closing || destroyed || surface == null) return;
        placeMicWindow(micWindow.height);
        try { windows.updateViewLayout(surface, micWindow); }
        catch (RuntimeException failure) { dismissOverlay(false); }
    }

    private float positionFraction(String key, float fallback) {
        Object value = preferences.getAll().get(key);
        if (!(value instanceof Float)) return fallback;
        float fraction = (Float) value;
        return Float.isNaN(fraction) || Float.isInfinite(fraction) ? fallback : Math.max(0, Math.min(1, fraction));
    }

    private void expandGesture() {
        main.removeCallbacks(contractGesture);
        placeMicWindow(gestureSizePx);
        try { windows.updateViewLayout(surface, micWindow); }
        catch (RuntimeException failure) { dismissOverlay(false); }
    }

    private final Runnable contractGesture = () -> {
        if (destroyed || closing || touching || moving || surface == null) return;
        placeMicWindow(dp(COMPACT_SIZE_DP));
        try { windows.updateViewLayout(surface, micWindow); }
        catch (RuntimeException failure) { dismissOverlay(false); }
    };

    private final Runnable beginHeldRecording = () -> {
        if (!touching || closeOnlyTouch || stopOnRelease || gesture.getDirection() != RecordingGesture.Direction.NONE || !canStartAudio()) return;
        startAudio();
        heldCapture = CaptureRef.of(RecordingService.snapshot());
        startedByTouch = true;
    };

    private boolean recordTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!captureAllowed() || moving || textDialog != null || textSaving) return true;
                RecordingService.Snapshot capture = RecordingService.snapshot();
                boolean foreignCapture = RecordingService.isBusy() && !sameProject(capture);
                if (RecordingService.isBusy() && !RecordingService.isRecording() && !foreignCapture) return true;
                touching = true;
                closeOnlyTouch = foreignCapture;
                touchPointerId = event.getPointerId(event.getActionIndex());
                stopOnRelease = !closeOnlyTouch && RecordingService.isRecording();
                startedByTouch = false;
                heldCapture = stopOnRelease ? CaptureRef.of(capture) : null;
                // Take the physical center before resizing. Both window sizes retain this center.
                int[] location = new int[2];
                surface.getLocationOnScreen(location);
                touchX = location[0] + microphone.getLeft() + microphone.getWidth() / 2f;
                touchY = location[1] + microphone.getTop() + microphone.getHeight() / 2f;
                expandGesture();
                if (closing) return true;
                microphone.press(true);
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                gesture.onDownAt(touchX, touchY, event.getRawX(), event.getRawY());
                guide.begin(gestureSizePx / 2f, gestureSizePx / 2f);
                guide.update(event.getRawX() - touchX, event.getRawY() - touchY, RecordingGesture.Direction.NONE, gesture.isArmed());
                if (closeOnlyTouch) toast("Another project is recording. Swipe down-right to close this control.");
                else if (!stopOnRelease) main.postDelayed(beginHeldRecording, HOLD_DELAY_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touching) {
                    int pointer = event.findPointerIndex(touchPointerId);
                    if (pointer < 0) { finishTouch(view, true); return true; }
                    Matrix transform = new Matrix(view.getMatrix());
                    float localX = event.getX(pointer), localY = event.getY(pointer);
                    float[] delta = {localX - event.getX(), localY - event.getY()};
                    transform.mapVectors(delta);
                    // Indexed raw coordinates require API 29. Map the owner's
                    // offset from pointer zero on every supported Android version.
                    float rawX = event.getRawX() + delta[0], rawY = event.getRawY() + delta[1];
                    for (int h = 0; h < event.getHistorySize() && touching; h++) {
                        delta[0] = event.getHistoricalX(pointer, h) - localX;
                        delta[1] = event.getHistoricalY(pointer, h) - localY;
                        transform.mapVectors(delta);
                        recordMove(rawX + delta[0], rawY + delta[1]);
                    }
                    recordMove(rawX, rawY);
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (!touching || event.getPointerId(event.getActionIndex()) != touchPointerId) return true;
                finishTouch(view, false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishTouch(view, true);
                return true;
            default: return true;
        }
    }

    private void finishTouch(View view, boolean cancelled) {
        touchPointerId = -1;
        if (!touching) return;
        main.removeCallbacks(beginHeldRecording);
        touching = false;
        closeOnlyTouch = false;
        microphone.home();
        guide.finish();
        if (gesture.getDirection() == RecordingGesture.Direction.RIGHT && (stopOnRelease || startedByTouch))
            stopGestureVideo();
        boolean ordinaryRelease = gesture.onUp();
        RecordingService.Snapshot beforeStop = RecordingService.snapshot();
        if ((stopOnRelease || startedByTouch) && heldCapture != null && heldCapture.matches(beforeStop)
                && ((!cancelled && ordinaryRelease) || !beforeStop.locked)) stopCapture(heldCapture);
        heldCapture = null;
        main.postDelayed(contractGesture, 240);
        if (!cancelled) { touchClick = true; view.performClick(); touchClick = false; }
        updateState();
    }

    private void recordMove(float rawX, float rawY) {
        if (!touching || !captureAllowed()) return;
        RecordingGesture.Direction before = gesture.getDirection();
        float dx = rawX - touchX, dy = rawY - touchY;
        // An overlay for another project can still close, but never takes ownership
        // of that narration. Ignored directions cannot latch or falsely rearm home.
        boolean allowedMotion = !closeOnlyTouch
                || Math.hypot(dx, dy) <= dp(RecordingGesture.HOME_RADIUS_DP)
                || RecordingGesture.candidateDirection(dx, dy, true) == RecordingGesture.Direction.CLOSE;
        RecordingGesture.Direction direction = allowedMotion ? gesture.update(rawX, rawY) : before;
        // Keep the moving artwork inside the finite window while intent uses the full raw path.
        float max = (gestureSizePx - dp(MIC_SIZE_DP)) / 2f;
        microphone.follow(Math.max(-max, Math.min(max, rawX - touchX)), Math.max(-max, Math.min(max, rawY - touchY)));
        guide.update(allowedMotion ? dx : 0, allowedMotion ? dy : 0, direction, gesture.isArmed());
        if (closeOnlyTouch && direction != RecordingGesture.Direction.CLOSE) return;
        if (before == RecordingGesture.Direction.RIGHT && direction != RecordingGesture.Direction.RIGHT) stopGestureVideo();
        if (direction == before || direction == RecordingGesture.Direction.NONE) return;
        main.removeCallbacks(beginHeldRecording);
        microphone.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (direction == RecordingGesture.Direction.CLOSE) {
            dismissOverlay(true);
        } else if (direction == RecordingGesture.Direction.UP) {
            if (!RecordingService.isRecording() && canStartAudio()) {
                startAudio(); startedByTouch = true; heldCapture = CaptureRef.of(RecordingService.snapshot());
            }
            if (heldCapture != null && heldCapture.matches(RecordingService.snapshot())) RecordingService.lock(this);
        } else if (direction == RecordingGesture.Direction.MARKER) addMarker();
        else microphoneAction(direction == RecordingGesture.Direction.LEFT ? "photo"
                : direction == RecordingGesture.Direction.RIGHT ? "video" : "text");
    }

    private boolean captureAllowed() {
        return !closing && !destroyed && showing && project != null && Settings.canDrawOverlays(this) && screenAvailable();
    }

    private boolean screenAvailable() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    private boolean sameProject(RecordingService.Snapshot capture) { return project != null && project.equals(capture.projectUri); }
    private boolean cameraWorkPending() { return !photos.isEmpty() || (camera != null && camera.isBusy()); }
    private boolean canStartAudio() {
        return captureAllowed() && !moving && !closeOnlyTouch && !RecordingService.isBusy() && !cameraWorkPending() && pendingVideo == null
                && textDialog == null && pendingText == null && !textSaving
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    private void startAudio() { RecordingService.start(this, project, UUID.randomUUID().toString()); updateState(); }

    private void stopCapture(CaptureRef target) {
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (target == null || !target.matches(capture)) return;
        flushMarkers(capture);
        pendingMarkers = 0;
        markerCapture = null;
        stopGestureVideo();
        RecordingService.stop(this);
    }

    private void addMarker() {
        if (!captureAllowed() || closeOnlyTouch || textDialog != null || pendingText != null) return;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (!RecordingService.isBusy()) {
            if (!canStartAudio()) return;
            startAudio();
            capture = RecordingService.snapshot();
            if (touching) { startedByTouch = true; heldCapture = CaptureRef.of(capture); }
            else RecordingService.lock(this);
        }
        if (!sameProject(capture)) return;
        if (capture.state == RecordingService.State.RECORDING) {
            if (!RecordingService.addMarker(this)) toast("Audio is finishing. Mark the next recording instead.");
        } else if (capture.state == RecordingService.State.STARTING) {
            markerCapture = CaptureRef.of(capture);
            pendingMarkers = Math.min(16, pendingMarkers + 1);
        }
    }

    private void flushMarkers(RecordingService.Snapshot capture) {
        if (pendingMarkers == 0) return;
        boolean same = markerCapture != null && markerCapture.matches(capture);
        if (same && capture.state == RecordingService.State.RECORDING) {
            while (pendingMarkers > 0 && RecordingService.addMarker(this)) pendingMarkers--;
        }
        if (!same || (capture.state != RecordingService.State.STARTING && capture.state != RecordingService.State.RECORDING)
                || pendingMarkers == 0) { pendingMarkers = 0; markerCapture = null; }
    }

    private void microphoneAction(String kind) {
        if (!captureAllowed() || closeOnlyTouch || textSaving || textDialog != null || pendingText != null) return;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (RecordingService.isBusy() && !sameProject(capture)) { toast("Finish the current recording first."); return; }
        if (capture.state == RecordingService.State.SAVING) { toast("Wait for the current audio to finish saving."); return; }
        CaptureRef audio = RecordingService.isRecording() ? CaptureRef.of(capture) : null;
        if ("text".equals(kind)) {
            stopGestureVideo();
            if (audio != null) {
                pendingText = audio;
                RecordingService.lock(this);
                updateState();
            } else if (!cameraWorkPending()) openText(project, UUID.randomUUID().toString(), null);
            else {
                pendingText = new CaptureRef(project, UUID.randomUUID().toString(), -1);
                drainActions();
            }
        } else if ("photo".equals(kind)) {
            if (photos.size() >= 8) { toast("Let the pending photos finish before taking more."); return; }
            photos.addLast(new PhotoRequest(project, audio == null ? UUID.randomUUID().toString() : audio.entry, audio));
            drainActions();
        } else {
            if (camera != null && camera.isRecording()) { stopGestureVideo(); return; }
            pendingVideo = audio == null ? new CaptureRef(project, UUID.randomUUID().toString(), -1) : audio;
            drainActions();
        }
    }

    private void drainActions() {
        if (!captureAllowed() || textDialog != null || textSaving || camera == null || camera.isBusy()) return;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (pendingText != null) {
            if (pendingText.startedElapsed < 0) {
                CaptureRef target = pendingText;
                pendingText = null;
                if (project.equals(target.project)) openText(target.project, target.entry, null);
                return;
            }
            if (!pendingText.matches(capture)) { pendingText = null; return; }
            if (capture.state == RecordingService.State.STARTING) return;
            CaptureRef target = pendingText;
            pendingText = null;
            if (capture.state == RecordingService.State.RECORDING)
                openText(target.project, target.entry, target);
            return;
        }
        if (!photos.isEmpty()) {
            PhotoRequest request = photos.peekFirst();
            if (!project.equals(request.project)) { photos.clear(); return; }
            if (request.audio != null) {
                if (RecordingService.isBusy() && !request.audio.matches(capture)) { photos.clear(); return; }
                if (capture.state == RecordingService.State.STARTING || capture.state == RecordingService.State.SAVING) return;
            }
            photos.removeFirst();
            beginCamera(false, request.project, request.entry);
            return;
        }
        if (pendingVideo != null) {
            CaptureRef target = pendingVideo;
            if (!project.equals(target.project) || (target.startedElapsed >= 0 && !target.matches(capture))) { pendingVideo = null; return; }
            if (capture.state == RecordingService.State.STARTING) return;
            if (target.startedElapsed >= 0 && capture.state != RecordingService.State.RECORDING) { pendingVideo = null; return; }
            pendingVideo = null;
            beginCamera(true, target.project, target.entry);
        }
    }

    private void beginCamera(boolean video, Uri destination, String entry) {
        if (!captureAllowed() || camera.isBusy()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Fieldnote and allow camera access to take photos or videos."); return;
        }
        boolean microphoneAudio = video && !RecordingService.isBusy();
        if (microphoneAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Fieldnote and allow microphone access to record video."); return;
        }
        try {
            // Acquire capture types before hardware opens. Widget launches already
            // retain them from the initial user interaction that admitted the service.
            setForeground(true, microphoneAudio);
            WindowManager.LayoutParams params = previewParams();
            if (!previewAttached) { windows.addView(preview, params); previewAttached = true; }
            if (video) camera.startVideo(destination, entry); else camera.takePhoto(destination, entry);
            if (!camera.isBusy()) { removePreview(); setForeground(false, false); }
        } catch (RuntimeException failure) {
            if (camera.isBusy()) camera.releaseHardware();
            else {
                removePreview();
                try { setForeground(false, false); } catch (RuntimeException ignored) { dismissOverlay(false); }
            }
            toast("Could not start camera capture. " + message(failure));
        }
    }

    /** Centering ends video only. Narration continues until release, Stop, or text submission. */
    private void stopGestureVideo() {
        pendingVideo = null;
        if (camera != null) camera.stopVideo();
    }

    private WindowManager.LayoutParams previewParams() {
        Rect available = screenBounds();
        int width = dp(184), height = dp(220), gap = dp(8), halfMic = dp(MIC_SIZE_DP) / 2;
        int aboveGuide = centerY - gestureSizePx / 2 - gap;
        WindowManager.LayoutParams params;
        if (aboveGuide >= dp(160)) {
            height = Math.min(height, aboveGuide);
            params = overlayParams(width, height);
            params.x = centerX - width / 2;
            params.y = aboveGuide - height;
        } else if (centerX - halfMic - gap >= width) {
            // Landscape: put the preview beside the mic, never over its touch target.
            height = Math.min(height, available.height());
            params = overlayParams(width, height);
            params.x = centerX - halfMic - gap - width;
            params.y = Math.max(0, Math.min(available.height() - height, centerY - height / 2));
        } else {
            // A short portrait display still leaves the 96dp mic fully accessible.
            height = Math.min(height, centerY - halfMic - gap);
            params = overlayParams(width, Math.max(1, height));
            params.x = centerX - width / 2;
            params.y = centerY - halfMic - gap - height;
        }
        params.setTitle("Fieldnote capture preview");
        return params;
    }

    private void updateState() {
        if (destroyed || closing || microphone == null) return;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (heldCapture != null) heldCapture.matches(capture);
        flushMarkers(capture);
        boolean audioActive = RecordingService.isRecording();
        boolean videoRecording = camera != null && camera.isRecording();
        microphone.recording = audioActive || videoRecording;
        microphone.locked = audioActive && capture.locked;
        microphone.setContentDescription(audioActive && !sameProject(capture)
                ? "Audio is recording in another project. Swipe down-right to close this control; use the Recorder widget or recording notification to stop audio."
                : audioActive
                ? microphone.locked ? "Stop and save locked recording" : "Recording. Slide up to lock or release to save."
                : videoRecording ? "Video recording. Use Stop video in the camera preview, or swipe the microphone down-right to stop and close."
                : "Floating microphone. Hold to record. Slide up to lock, left for photo, right for video, down for text, or diagonally upper right for a marker.");
        microphone.invalidate();
        if (capture.error != null && !capture.error.equals(observedError)) toast(capture.error);
        observedError = capture.error;
        if (capture.markerError != null && !capture.markerError.equals(observedMarkerError)) toast(capture.markerError);
        observedMarkerError = capture.markerError;
        drainActions();
    }

    private void openText(Uri destination, String entry, CaptureRef recording) {
        if (!captureAllowed() || textDialog != null || textSaving) return;
        textCapture = recording;
        EditText body = new EditText(visualContext);
        textInput = body;
        body.setId(R.id.note_text_input);
        body.setHint("Write a note…");
        body.setTextColor(ink);
        body.setHintTextColor(muted);
        body.setMinLines(4);
        body.setMaxLines(10);
        body.setGravity(Gravity.TOP);
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        String[] editorRevision = {null};
        AlertDialog dialog = new AlertDialog.Builder(visualContext).setTitle("Text note").setView(body)
                .setPositiveButton("Save", null).setNegativeButton(recording == null ? "Cancel" : "Skip", (ignored, which) -> {
                    finishTextRecording(recording);
                    drafts.discard(editorRevision[0]);
                }).create();
        textDialog = dialog;
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> { finishTextRecording(recording); drafts.discard(editorRevision[0]); });
        dialog.setOnDismissListener(ignored -> {
            if (textDialog == dialog) { textDialog = null; textInput = null; textCapture = null; }
            updateState();
        });
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setId(R.id.note_save);
            save.setOnClickListener(view -> {
                if (textSaving || closing || destroyed) return;
                String value = body.getText().toString();
                if (value.length() > 100000) { body.setError("Use 100,000 characters or fewer."); return; }
                if (value.trim().isEmpty()) { body.setError("Write some text before saving."); return; }
                JSONObject metadata = MetadataCollector.snapshot(FloatingRecorderService.this);
                String submittedRevision = persistDraft(destination, entry, value);
                if (submittedRevision != null) editorRevision[0] = submittedRevision;
                finishTextRecording(recording);
                textSaving = true;
                body.setEnabled(false);
                save.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                dialog.setCancelable(false);
                io.execute(() -> {
                    try {
                        store.saveText(destination, entry, value, metadata);
                        main.post(() -> {
                            // This old service may already be gone and a newer editor
                            // may own the same entry. Check/remove one revision atomically
                            // on the main thread shared by every editor instance.
                            drafts.discard(submittedRevision);
                            textSaving = false;
                            if (!destroyed && textDialog == dialog) dialog.dismiss();
                            if (closing) finishClosing();
                        });
                    } catch (Exception failure) {
                        main.post(() -> {
                            textSaving = false;
                            if (destroyed) return;
                            if (closing) { finishClosing(); return; }
                            save.setEnabled(true);
                            body.setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            dialog.setCancelable(true);
                            body.setError("Not saved. " + message(failure));
                        });
                    }
                });
            });
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!textSaving) {
                    String revision = persistDraft(destination, entry, s.toString());
                    if (revision != null) editorRevision[0] = revision;
                }
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        try { dialog.show(); body.requestFocus(); }
        catch (RuntimeException failure) {
            finishTextRecording(recording);
            textDialog = null; textInput = null; textCapture = null;
            toast("Could not open the text note. " + message(failure));
        }
    }

    private void finishTextRecording(CaptureRef capture) {
        if (capture == null) return;
        if (pendingText == capture) pendingText = null;
        stopCapture(capture);
    }

    private String persistDraft(Uri destination, String entry, String text) {
        if (active != this || closing || destroyed) return null;
        try {
            return drafts.write(destination, entry, text);
        } catch (Exception ignored) { /* The still-open editor retains the text. */ }
        return null;
    }

    private void restoreTextDraft() {
        try {
            String stored = drafts.read();
            JSONObject draft = new JSONObject(stored == null ? "{}" : stored);
            String entry = draft.optString("entry"), text = draft.optString("text");
            if (project.toString().equals(draft.optString("project")) && !entry.isEmpty() && !text.isEmpty()) {
                openText(project, entry, null);
                if (textInput != null) { textInput.setText(text); textInput.setSelection(text.length()); }
            }
        } catch (Exception ignored) { }
    }

    private void setForeground(boolean cameraActive, boolean microphoneActive) {
        int type = 0;
        if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (Build.VERSION.SDK_INT >= 30 && (cameraActive || widgetCameraAdmission)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        if (Build.VERSION.SDK_INT >= 30 && (microphoneActive || widgetAdmission)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Fieldnote floating microphone")
                .setContentText(cameraActive ? "Camera capture in progress" : "Hold the floating microphone to capture")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Hide", controlIntent(HIDE, 2001)).build())
                .addAction(new Notification.Action.Builder(null, "Stop all", controlIntent(STOP, 2002)).build());
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        builder.setContentIntent(PendingIntent.getActivity(this, 2003, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, builder.build(), type);
        else startForeground(NOTIFICATION_ID, builder.build());
        foregroundReady = true;
        cameraForeground = cameraActive;
    }

    private PendingIntent controlIntent(String action, int request) {
        return PendingIntent.getService(this, request, new Intent(this, FloatingRecorderService.class)
                .setAction(action).putExtra("instance", instanceToken), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void dismissOverlay(boolean stopLocked) {
        if (destroyed || closing) return;
        closing = true;
        ++widgetGeneration;
        widgetResolving = false;
        showing = false;
        moving = false;
        movePointerId = -1;
        touching = false;
        closeOnlyTouch = false;
        touchPointerId = -1;
        heldCapture = null;
        main.removeCallbacks(beginHeldRecording);
        main.removeCallbacks(contractGesture);
        main.removeCallbacks(visibilityCheck);
        pendingVideo = null;
        photos.clear();
        pendingMarkers = 0;
        markerCapture = null;
        // TextWatcher already persists every edit, and submission disables editing.
        // Teardown must never rewrite an older instance's draft over a reopened editor.
        CaptureRef automaticTextLock = textCapture != null ? textCapture : pendingText;
        pendingText = null;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (sameProject(capture) && (stopLocked || !capture.locked || (automaticTextLock != null && automaticTextLock.matches(capture))))
            RecordingService.stop(this);
        if (textDialog != null) textDialog.dismiss();
        removeSurface();
        if (camera != null && camera.isBusy()) {
            camera.stopVideo();
            camera.releaseHardware();
            // Keep the existing FGS type until hardware and its local original finish.
            main.postDelayed(this::finishClosing, 5000);
        } else finishClosing();
    }

    private void finishClosing() {
        if (!closing || destroyed) return;
        // Text staging has its own durable draft and can finish after host teardown.
        stopSelf();
    }

    private void removePreview() {
        if (!previewAttached || windows == null || preview == null) return;
        previewAttached = false;
        try { windows.removeView(preview); } catch (RuntimeException ignored) { }
    }

    private void removeSurface() {
        if (windows != null && surface != null && surface.isAttachedToWindow()) {
            try { windows.removeView(surface); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void onDestroy() {
        if (!closing) dismissOverlay(false);
        destroyed = true;
        showing = false;
        if (active == this) active = null;
        main.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(recordingReceiver);
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        if (appOps != null) appOps.stopWatchingMode(permissionListener);
        if (camera != null) camera.close();
        if (textDialog != null) textDialog.dismiss();
        removePreview();
        removeSurface();
        MetadataCollector.stop(this);
        io.shutdown();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private Rect screenBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = windows.getCurrentWindowMetrics();
            Rect available = new Rect(metrics.getBounds());
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            available.left += insets.left;
            available.top += insets.top;
            available.right -= insets.right;
            available.bottom -= insets.bottom;
            return available;
        }
        android.graphics.Point size = new android.graphics.Point();
        windows.getDefaultDisplay().getSize(size);
        return new Rect(0, 0, size.x, size.y);
    }

    private void measurePosition() {
        Rect available = screenBounds();
        gestureSizePx = Math.min(dp(GESTURE_SIZE_DP), Math.min(available.width(), available.height()));
        if (gestureSizePx < dp(2 * (RecordingGesture.ACTIVATION_DISTANCE_DP + GestureGuideView.TARGET_OUTSET_DP)))
            throw new IllegalStateException("The display is too small for the floating microphone gestures.");
        int half = gestureSizePx / 2;
        int spanX = Math.max(0, available.width() - gestureSizePx);
        int spanY = Math.max(0, available.height() - gestureSizePx);
        float fallbackY = spanY == 0 ? .5f : Math.max(0, 1f - (float) dp(40) / spanY);
        centerX = half + Math.round(positionFraction("floating_position_x", .5f) * spanX);
        centerY = half + Math.round(positionFraction("floating_position_y", fallbackY) * spanY);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (closing || destroyed || surface == null) return;
        // Rotation changes the available coordinate space. End the old hold before
        // positioning both the idle and expanded bounds around a new safe center.
        main.removeCallbacks(beginHeldRecording);
        main.removeCallbacks(contractGesture);
        if (touching && heldCapture != null && !RecordingService.snapshot().locked) stopCapture(heldCapture);
        touching = false;
        closeOnlyTouch = false;
        touchPointerId = -1;
        moving = false;
        movePointerId = -1;
        heldCapture = null;
        microphone.home();
        guide.finish();
        gesture.reset();
        stopGestureVideo();
        if (camera != null && camera.isBusy()) camera.releaseHardware();
        try {
            measurePosition();
            placeMicWindow(dp(COMPACT_SIZE_DP));
            windows.updateViewLayout(surface, micWindow);
        } catch (RuntimeException failure) { dismissOverlay(false); }
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    /** A fresh entry UUID plus the eventual monotonic start identifies one original. */
    private static final class CaptureRef {
        final Uri project;
        final String entry;
        long startedElapsed;
        CaptureRef(Uri project, String entry, long startedElapsed) {
            this.project = project; this.entry = entry; this.startedElapsed = startedElapsed;
        }
        static CaptureRef of(RecordingService.Snapshot capture) {
            return new CaptureRef(capture.projectUri, capture.entryId, capture.startedElapsedRealtime);
        }
        boolean matches(RecordingService.Snapshot capture) {
            if (project == null || entry == null || !project.equals(capture.projectUri) || !entry.equals(capture.entryId)) return false;
            if (startedElapsed == 0 && capture.startedElapsedRealtime > 0) startedElapsed = capture.startedElapsedRealtime;
            return startedElapsed == capture.startedElapsedRealtime;
        }
    }

    private static final class PhotoRequest {
        final Uri project;
        final String entry;
        final CaptureRef audio;
        PhotoRequest(Uri project, String entry, CaptureRef audio) { this.project = project; this.entry = entry; this.audio = audio; }
    }
}
