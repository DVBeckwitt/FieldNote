package com.fieldnote.app;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native notebook UI for working project folders and legacy imports. */
public class MainActivity extends Activity {
    private static final int PICK_CREATE = 40, PICK_DEFAULT = 41, PICK_IMPORT = 42,
            PICK_LEGACY = 43, PICK_IMPORT_DESTINATION = 44, PICK_OVERLAY = 45, PICK_SPEECH_MODEL = 46;
    private static final int PERMISSION_MIC = 50, PERMISSION_CAMERA = 51, PERMISSION_LOCATION = 52, PERMISSION_FLOATING = 53;
    private static final int PERMISSION_WATCH = 54;
    private static final long HOLD_DELAY_MS = 180;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService localIo = Executors.newSingleThreadExecutor();
    private final ExecutorService quickIo = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private ProjectStore store;
    private Uri projectUri;
    private Uri notesFilterProject;
    private JSONObject project;
    private JSONArray stagedEntries = new JSONArray();
    private Uri stagedProject;
    private volatile long pendingRefreshGeneration;
    private long observedPublicationGeneration = -1;
    private boolean dark, destroyed, newestFirst = true, receiverRegistered, visible, resumed, projectOperation, projectLoading;
    private int background, panel, ink, muted, accent, buttonInk, border, recordingColor, preferredMicSize;
    private LinearLayout root, entryList, capturePanel, capturePage, listPage, recentCard;
    private TextView projectTitle, recordStatus, recordTitle, recordHelp, attachNotice, countLabel;
    private TextView recentTitle, recentPreview;
    private Button closeNotesButton, markerButton, markersFilter, finishProjectButton;
    private EditText notesSearch;
    private LinearLayout searchControls, tagFilters;
    private String searchQuery = "", selectedTag = "", observedMarkerKey, observedMarkerError;
    private boolean markersOnly;
    private int observedMarkerCount;
    private NotesScrollView notesScroll;
    private FrameLayout pages;
    private FrameLayout notesLayer;
    private ValueAnimator notesAnimator;
    private boolean notesDragging, notesOpening;
    private boolean notesExpanded;
    private boolean renderEntriesAfterHold;
    private MotionCaptureControls motionControls;
    private android.window.OnBackInvokedCallback notesBackCallback;
    private Uri pendingImportSource, attemptedUpgrade;
    private EntryViewer entryViewer;
    private AlertDialog noteDialog;
    private AlertDialog settingsDialog;
    private SpeechModel pendingSpeechModel;
    private TranscriptionSettings transcriptionSettings;
    private final Runnable refreshModelSettings = this::refreshTranscriptionSettings;
    // An install can finish while its settings page is closed or recreated.
    private static ModelInstall modelInstall;
    private AlertDialog watchDialog;
    private AlertDialog filesDialog;
    private InlineCamera inlineCamera;
    private FrameLayout inlineCameraContainer;
    private Uri pendingTextProject, pendingCameraProject;
    private String pendingTextEntry;
    private boolean resumeCameraPermission, pendingCameraHeld;
    private String activeNoteEntryId;
    private String finishingTextToken;
    private String textRecordingKey;
    private Bundle restoredNote;
    private RecordView recordButton;
    private GestureGuideView gestureGuide;
    private RecordingGesture gesture;
    private boolean touching, stopOnRelease, touchClick, startedByTouch, permissionRequest;
    private int touchPointerId = -1;
    private float touchStartX, touchStartY;
    private String pendingMicAction;
    private Uri pendingMicProject;
    private int pendingGestureMarkers;
    private String pendingGestureMarkerEntry;
    private Uri pendingGestureMarkerProject;
    private int pendingPhotoCount;
    private Uri pendingPhotoProject;
    private String pendingPhotoEntry;
    private String nextEntryId, pendingName, pendingCameraMode, pendingCameraEntry;
    private RecordingService.State observedState;
    private String observedError;
    private volatile long refreshGeneration;
    // A launch request is consumed before capture starts, never replayed on ordinary resume.
    private static final int QUICK_NONE = 0, QUICK_RECORD = 1, QUICK_FLOATING = 2;
    private int quickMode;
    private final Runnable continueAfterOverlayHide = this::continueQuickCapture;
    private boolean quickResolving, quickReady, quickPermission, overlayPermissionRequest;
    private long quickGeneration;
    private Uri quickProject;
    private String quickExistingEntry;
    private final Runnable beginHeldRecording = () -> {
        if (!touching || stopOnRelease || gesture.getDirection() != RecordingGesture.Direction.NONE) return;
        if (canRecord()) { startedByTouch = true; startRecording(); }
    };
    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (WatchReceiverService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                if (watchDialog != null) watchDialog.setMessage(watchStatus());
                return;
            }
            updateRecording();
            if (ProjectPublisher.ACTION_MEDIA_SAVED.equals(intent.getAction())) refreshProject();
            if (ProjectPublisher.ACTION_STATE.equals(intent.getAction())) refreshPendingEntries();
            if (AudioTranscriptionJob.ACTION_TRANSCRIPT_UPDATED.equals(intent.getAction()) && projectUri != null
                    && projectUri.toString().equals(intent.getStringExtra("projectUri"))) refreshPendingEntries();
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            updateRecording();
            main.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("fieldnote", MODE_PRIVATE);
        dark = preferences.getBoolean("dark_mode", true);
        setTheme(dark ? R.style.FieldnoteDark : R.style.FieldnoteLight);
        super.onCreate(savedInstanceState);
        SpeechModels.clearInterruptedInstalls(this);
        store = new ProjectStore(this);
        projectUri = store.currentProject();
        notesFilterProject = projectUri;
        if (savedInstanceState != null) {
            pendingName = savedInstanceState.getString("pendingName");
            nextEntryId = savedInstanceState.getString("nextEntryId");
            pendingCameraMode = savedInstanceState.getString("pendingCameraMode");
            pendingCameraEntry = savedInstanceState.getString("pendingCameraEntry");
            // Restore old Notebook snapshots as the notes sheet; obsolete Files snapshots return to Record.
            int oldTab = savedInstanceState.getInt("activeTab", 0);
            boolean oldFiles = savedInstanceState.getInt("pageTab", oldTab == 2 ? 1 : 0) == 1;
            notesExpanded = !oldFiles && savedInstanceState.getBoolean("notesExpanded",
                    !savedInstanceState.containsKey("pageTab") && oldTab == 1);
            searchQuery = savedInstanceState.getString("searchQuery", "");
            selectedTag = savedInstanceState.getString("selectedTag", "");
            markersOnly = savedInstanceState.getBoolean("markersOnly");
            String importSource = savedInstanceState.getString("pendingImportSource");
            if (importSource != null) pendingImportSource = Uri.parse(importSource);
            pendingSpeechModel = SpeechModel.fromId(savedInstanceState.getString("pendingSpeechModel"));
            String upgrade = savedInstanceState.getString("attemptedUpgrade");
            if (upgrade != null) attemptedUpgrade = Uri.parse(upgrade);
            restoredNote = savedInstanceState.getBundle("noteDraft");
            String pendingText = savedInstanceState.getString("pendingTextProject");
            if (pendingText != null) pendingTextProject = Uri.parse(pendingText);
            pendingTextEntry = savedInstanceState.getString("pendingTextEntry");
            String pendingCamera = savedInstanceState.getString("pendingCameraProject");
            if (pendingCamera != null) pendingCameraProject = Uri.parse(pendingCamera);
            resumeCameraPermission = savedInstanceState.getBoolean("resumeCameraPermission");
            finishingTextToken = savedInstanceState.getString("finishingTextToken");
            textRecordingKey = savedInstanceState.getString("textRecordingKey");
            pendingMicAction = savedInstanceState.getString("pendingMicAction");
            pendingGestureMarkers = savedInstanceState.getInt("pendingGestureMarkers");
            pendingGestureMarkerEntry = savedInstanceState.getString("pendingGestureMarkerEntry");
            String markerProject = savedInstanceState.getString("pendingGestureMarkerProject");
            if (markerProject != null) pendingGestureMarkerProject = Uri.parse(markerProject);
            String micProject = savedInstanceState.getString("pendingMicProject");
            if (micProject != null) pendingMicProject = Uri.parse(micProject);
            pendingCameraHeld = savedInstanceState.getBoolean("pendingCameraHeld");
            permissionRequest = savedInstanceState.getBoolean("permissionRequest");
            quickMode = savedInstanceState.getInt("quickMode", QUICK_NONE);
            quickPermission = savedInstanceState.getBoolean("quickPermission");
            overlayPermissionRequest = savedInstanceState.getBoolean("overlayPermissionRequest");
            String quickUri = savedInstanceState.getString("quickProject");
            if (quickUri != null) quickProject = Uri.parse(quickUri);
            quickExistingEntry = savedInstanceState.getString("quickExistingEntry");
        }
        palette();
        motionControls = new MotionCaptureControls(this, this::toggleRecordingFromShake);
        buildUi();
        RecordingShortcuts.publish(this);
        if (savedInstanceState == null) acceptQuickIntent(getIntent());
        else if (quickMode != QUICK_NONE) resolveQuickProject();
        if (quickMode == QUICK_NONE) refreshProject();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        acceptQuickIntent(intent);
    }

    private void acceptQuickIntent(Intent intent) {
        int mode = RecordingShortcuts.isRecordIntent(this, intent) ? QUICK_RECORD
                : RecordingShortcuts.isOverlayIntent(this, intent) ? QUICK_FLOATING : QUICK_NONE;
        if (mode == QUICK_NONE) return;
        setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN));
        if (quickMode != QUICK_NONE) return; // Repeated launcher taps coalesce.
        if (projectOperation || cameraWorkPending() || FloatingRecorderService.isBusyForHandoff() || permissionRequest
                || (noteDialog != null && noteDialog.isShowing()) || restoredNote != null) {
            toast("Finish the current capture or editor before quick recording.");
            return;
        }
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (capture.state == RecordingService.State.SAVING) {
            toast("Finish the current recording first.");
            return;
        }
        if (settingsDialog != null) settingsDialog.dismiss();
        if (filesDialog != null) filesDialog.dismiss();
        if (entryViewer != null) entryViewer.dismiss();
        quickMode = mode;
        // An existing capture keeps its identity and project, regardless of newer projects.
        quickProject = RecordingService.isRecording() ? capture.projectUri : null;
        quickExistingEntry = RecordingService.isRecording() ? capture.entryId : null;
        quickReady = false;
        showRecord();
        resolveQuickProject();
    }

    private void resolveQuickProject() {
        if (quickMode == QUICK_NONE || quickResolving || destroyed) return;
        quickResolving = true;
        quickReady = false;
        projectLoading = true;
        ++refreshGeneration;
        final long generation = ++quickGeneration;
        final Uri boundProject = quickProject;
        quickIo.execute(() -> {
            try {
                Uri destination = boundProject != null ? boundProject : store.lastCreatedProject();
                if (destination == null) throw new java.io.IOException("Create a project in Fieldnote, then use the home-screen control.");
                if (!store.isWorkingProject(destination))
                    throw new java.io.IOException("Open an editable project in Fieldnote before using quick capture.");
                // A cached index lets capture start while the provider is temporarily offline.
                JSONObject loaded = store.readCachedProject(destination);
                if (loaded == null) loaded = store.readProject(destination);
                final JSONObject ready = loaded;
                main.post(() -> {
                    if (destroyed || generation != quickGeneration || quickMode == QUICK_NONE) return;
                    if (!store.isWorkingProject(destination)) {
                        cancelQuickCapture();
                        refreshProject();
                        toast("Open an editable project in Fieldnote before using quick capture.");
                        return;
                    }
                    quickResolving = false;
                    projectLoading = false;
                    quickProject = destination;
                    quickReady = true;
                    projectUri = destination;
                    project = ready;
                    store.setCurrentProject(destination);
                    renderEntries();
                    continueQuickCapture();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (destroyed || generation != quickGeneration) return;
                    cancelQuickCapture();
                    renderEntries();
                    toast(errorMessage(failure));
                });
            }
        });
    }

    private void cancelQuickCapture() {
        main.removeCallbacks(continueAfterOverlayHide);
        if (quickResolving) projectLoading = false;
        quickMode = QUICK_NONE;
        quickProject = null;
        quickExistingEntry = null;
        quickReady = quickResolving = quickPermission = overlayPermissionRequest = false;
        ++quickGeneration;
    }

    private void continueQuickCapture() {
        if (quickMode == QUICK_NONE || !quickReady || quickResolving || destroyed || !visible
                || !resumed || !hasWindowFocus() || permissionRequest || overlayPermissionRequest || projectLoading) return;
        if (projectOperation || cameraWorkPending() || FloatingRecorderService.isBusyForHandoff()
                || project == null || !java.util.Objects.equals(quickProject, projectUri)) {
            cancelQuickCapture();
            toast("Quick capture could not start. Open the project and try again.");
            return;
        }
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (quickExistingEntry != null && (!RecordingService.isRecording() || !quickExistingEntry.equals(capture.entryId))) {
            cancelQuickCapture();
            toast("That recording has finished. Use Record now to start another.");
            return;
        }
        if (RecordingService.isBusy() && (!RecordingService.isRecording()
                || !java.util.Objects.equals(capture.projectUri, quickProject))) {
            cancelQuickCapture();
            toast("Finish the current recording first.");
            return;
        }
        if (quickMode == QUICK_FLOATING) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                overlayPermissionRequest = true;
                try {
                    startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())), PICK_OVERLAY);
                } catch (RuntimeException unavailable) {
                    cancelQuickCapture();
                    toast("Enable Display over other apps for Fieldnote in Android settings.");
                }
                return;
            }
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO);
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA);
            addFirstLocationPermission(needed);
            if (!needed.isEmpty()) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS);
                permissionRequest = quickPermission = true;
                requestPermissions(needed.toArray(new String[0]), PERMISSION_FLOATING);
                return;
            }
            Uri destination = quickProject;
            cancelQuickCapture();
            if (inlineCamera != null) inlineCamera.releaseHardware();
            if (FloatingRecorderService.show(this, destination)) moveTaskToBack(true);
            return;
        }
        if (FloatingRecorderService.isShowing()) {
            // The static shortcut takes over the same original when audio already
            // exists. Hide is asynchronous: let it finish before starting fresh audio,
            // or its teardown could stop the new recording as an unlocked capture.
            if (RecordingService.isRecording()) RecordingService.lock(this);
            FloatingRecorderService.hide(this);
            main.removeCallbacks(continueAfterOverlayHide);
            main.postDelayed(continueAfterOverlayHide, 32);
            return;
        }
        if (RecordingService.isRecording()) {
            cancelQuickCapture();
            updateRecording();
            return;
        }
        if (!canRecord()) {
            if (permissionRequest) quickPermission = true;
            else cancelQuickCapture();
            return;
        }
        Uri destination = quickProject;
        cancelQuickCapture();
        nextEntryId = null;
        updateAttachmentNotice();
        stopPlayers();
        RecordingService.start(this, destination, UUID.randomUUID().toString());
        RecordingService.lock(this);
        updateRecording();
    }

    private void palette() {
        background = Color.parseColor(dark ? "#0C1627" : "#F7FAFF");
        panel = Color.parseColor(dark ? "#172943" : "#E7F0FF");
        ink = Color.parseColor(dark ? "#E8F1FF" : "#17314D");
        muted = Color.parseColor(dark ? "#ABC0DB" : "#536C87");
        accent = Color.parseColor(dark ? "#91C5FF" : "#2C68B2");
        recordingColor = Color.parseColor(dark ? "#C69CFF" : "#7C3AED");
        buttonInk = Color.parseColor(dark ? "#0C2340" : "#FFFFFF");
        border = Color.parseColor(dark ? "#2A405C" : "#CEDDF1");
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void buildUi() {
        root = column();
        root.setBackgroundColor(background);
        root.setPadding(dp(20), dp(8), dp(20), dp(8));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                root.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(20) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
                return insets;
            });
        }
        setContentView(root);
        LinearLayout top = row();
        TextView brand = text("Fieldnote", 20, ink);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setSingleLine(true);
        brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        finishProjectButton = smallButton("Finish");
        finishProjectButton.setId(R.id.finish_project_button);
        finishProjectButton.setTextColor(accent);
        finishProjectButton.setContentDescription("Finish project");
        finishProjectButton.setVisibility(View.GONE);
        finishProjectButton.setOnClickListener(v -> finishProjectDialog());
        LinearLayout.LayoutParams finishSpace = new LinearLayout.LayoutParams(-2, dp(48));
        finishSpace.setMarginEnd(dp(8));
        top.addView(finishProjectButton, finishSpace);
        ImageButton files = new ImageButton(this);
        files.setId(R.id.files_button);
        files.setImageResource(R.drawable.ic_folder);
        files.setImageTintList(ColorStateList.valueOf(accent));
        files.setPadding(dp(13), dp(13), dp(13), dp(13));
        files.setBackground(touchSurface(panel, 16, border));
        files.setContentDescription("Project files");
        files.setOnClickListener(v -> projectFiles());
        LinearLayout.LayoutParams filesSpace = new LinearLayout.LayoutParams(dp(48), dp(48));
        filesSpace.setMarginEnd(dp(8));
        top.addView(files, filesSpace);
        ImageButton settings = new ImageButton(this);
        settings.setId(R.id.settings_button);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setImageTintList(ColorStateList.valueOf(accent));
        settings.setPadding(dp(13), dp(13), dp(13), dp(13));
        settings.setBackground(touchSurface(panel, 16, border));
        settings.setContentDescription("Settings");
        settings.setOnClickListener(v -> settings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(top);
        LinearLayout projectBar = row();
        projectBar.setGravity(Gravity.CENTER_VERTICAL);
        projectTitle = text("Choose project  ⌄", 18, ink);
        projectTitle.setId(R.id.project_title);
        projectTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        projectTitle.setMaxLines(1);
        projectTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        projectTitle.setGravity(Gravity.CENTER_VERTICAL);
        projectTitle.setMinHeight(dp(52));
        projectTitle.setPadding(dp(16), dp(12), dp(16), dp(12));
        projectTitle.setBackground(touchSurface(panel, 18, border));
        projectTitle.setContentDescription("Choose project");
        projectTitle.setOnClickListener(v -> projects());
        LinearLayout.LayoutParams projectSpace = new LinearLayout.LayoutParams(0, -2, 1);
        projectSpace.setMarginEnd(dp(8));
        projectBar.addView(projectTitle, projectSpace);
        Button newProject = smallButton("New project");
        newProject.setId(R.id.new_project_button);
        newProject.setTextColor(accent);
        newProject.setMinHeight(dp(52));
        newProject.setMinimumHeight(dp(52));
        newProject.setOnClickListener(v -> newProjectDialog());
        projectBar.addView(newProject, new LinearLayout.LayoutParams(-2, -2));
        margin(projectBar, 12, 10);
        attachNotice = text("", 12, accent);
        attachNotice.setPadding(dp(10), dp(8), dp(10), dp(8));
        attachNotice.setBackground(shape(panel, 8, border));
        attachNotice.setVisibility(View.GONE);
        attachNotice.setOnClickListener(v -> { nextEntryId = null; updateAttachmentNotice(); });
        root.addView(attachNotice);

        pages = new FrameLayout(this);
        pages.setId(R.id.capture_pages);
        pages.setClipChildren(false);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        capturePage = column();
        capturePage.setClipChildren(false);
        pages.addView(capturePage, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout recordSurface = new FrameLayout(this);
        recordSurface.setClipChildren(false);
        capturePage.addView(recordSurface, new LinearLayout.LayoutParams(-1, 0, 1));
        capturePanel = new CapturePanel(this);
        capturePanel.setClipChildren(false);
        capturePanel.setGravity(Gravity.CENTER);
        recordSurface.addView(capturePanel, new FrameLayout.LayoutParams(-1, -1));
        recordStatus = text("Ready", 13, muted);
        recordStatus.setId(R.id.record_status);
        recordStatus.setGravity(Gravity.CENTER);
        recordStatus.setMaxLines(1);
        recordStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // The draggable circle passes behind status, keeping capture/save feedback readable.
        recordStatus.setBackground(shape(background, 8, Color.TRANSPARENT));
        recordStatus.setElevation(dp(2));
        recordStatus.setOnClickListener(v -> {
            ProjectPublisher.Snapshot save = ProjectPublisher.snapshot();
            if (save.error != null && !save.error.isEmpty()) retryPending();
        });
        recordStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        capturePanel.addView(recordStatus);
        recordButton = new RecordView(this);
        recordButton.setId(R.id.record_button);
        recordButton.setContentDescription("Hold to record; slide up to lock; release to save. Double tap with accessibility to start hands-free.");
        recordButton.setFocusable(true);
        recordButton.setClickable(true);
        int size = Math.min(dp(300), Math.min(getResources().getDisplayMetrics().widthPixels - dp(64),
                Math.round(getResources().getDisplayMetrics().heightPixels * .39f)));
        preferredMicSize = size;
        LinearLayout.LayoutParams recordLayout = new LinearLayout.LayoutParams(size, size);
        recordLayout.setMargins(0, dp(14), 0, dp(14));
        capturePanel.addView(recordButton, recordLayout);
        recordTitle = text("Hold to record", 22, ink);
        recordTitle.setId(R.id.record_title);
        recordTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        recordTitle.setGravity(Gravity.CENTER);
        capturePanel.addView(recordTitle);
        recordHelp = text("Slide to a feature · return to center", 12, muted);
        recordHelp.setGravity(Gravity.CENTER);
        recordHelp.setMaxLines(1);
        recordHelp.setEllipsize(android.text.TextUtils.TruncateAt.END);
        recordHelp.setPadding(0, dp(9), 0, dp(8));
        capturePanel.addView(recordHelp);
        markerButton = smallButton("◇  Mark moment");
        markerButton.setId(R.id.add_marker);
        markerButton.setEnabled(false);
        markerButton.setTextSize(13);
        markerButton.setContentDescription("Add an audio marker at this moment");
        markerButton.setOnClickListener(v -> {
            if (!RecordingService.addMarker(this)) toast("Start recording to mark a moment.");
        });
        capturePanel.addView(markerButton, new LinearLayout.LayoutParams(dp(240), dp(48)));
        gesture = new RecordingGesture(dp(RecordingGesture.ACTIVATION_DISTANCE_DP), dp(RecordingGesture.HOME_RADIUS_DP));
        gestureGuide = new GestureGuideView(this, dp(RecordingGesture.ACTIVATION_DISTANCE_DP), dp(RecordingGesture.HOME_RADIUS_DP));
        gestureGuide.configureColors(accent, background, ink);
        recordSurface.addView(gestureGuide, new FrameLayout.LayoutParams(-1, -1));
        recordButton.setOnTouchListener((view, event) -> recordTouch(view, event));
        recordButton.setOnClickListener(v -> {
            if (touchClick) return;
            recordButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (RecordingService.isRecording()) stopRecordingAndVideo();
            else if (canRecord()) { startRecording(); RecordingService.lock(this); }
        });
        inlineCameraContainer = new FrameLayout(this);
        inlineCameraContainer.setId(R.id.inline_camera);
        inlineCameraContainer.setVisibility(View.GONE);
        FrameLayout.LayoutParams inlineLayout = new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.TOP | Gravity.END);
        inlineLayout.topMargin = dp(6);
        recordSurface.addView(inlineCameraContainer, inlineLayout);
        inlineCamera = new InlineCamera(this, inlineCameraContainer, new InlineCamera.Listener() {
            @Override public void onStateChanged(boolean busy, boolean recording) { updateRecording(); }
            @Override public void onSaved() { refreshPendingEntries(); }
            @Override public void onError(String error) {
                // A camera failure must not steal the held microphone's touch/focus.
                if (RecordingService.isRecording()) toast(error);
                else message("Camera", error);
            }
        });
        recentCard = new RecentNoteCard(this, this::showAllNotes);
        ((RecentNoteCard) recentCard).setDragListener(notesDragListener(true));
        recentCard.setOrientation(LinearLayout.VERTICAL);
        recentCard.setId(R.id.recent_note_card);
        recentCard.setPadding(dp(18), dp(10), dp(18), dp(14));
        recentCard.setBackground(touchSurface(panel, 22, border));
        recentCard.setClickable(true);
        recentCard.setFocusable(true);
        View handle = new View(this);
        handle.setBackground(shape(dark ? Color.parseColor("#597593") : Color.parseColor("#A3BAD4"), 2, Color.TRANSPARENT));
        LinearLayout.LayoutParams handleLayout = new LinearLayout.LayoutParams(dp(30), dp(3));
        handleLayout.gravity = Gravity.CENTER_HORIZONTAL;
        handleLayout.bottomMargin = dp(11);
        recentCard.addView(handle, handleLayout);
        recentTitle = text("Your latest note", 15, ink);
        recentTitle.setId(R.id.recent_note_title);
        recentTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        recentTitle.setMaxLines(1);
        recentTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        recentPreview = text("", 12, muted);
        recentPreview.setId(R.id.recent_note_preview);
        recentPreview.setMaxLines(2);
        recentPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        recentPreview.setPadding(0, dp(5), 0, 0);
        recentCard.addView(recentTitle);
        recentCard.addView(recentPreview);
        TextView browseHint = text("Swipe up for all notes  ↑", 11, accent);
        browseHint.setPadding(0, dp(7), 0, 0);
        recentCard.addView(browseHint);
        LinearLayout.LayoutParams recentLayout = new LinearLayout.LayoutParams(-1, -2);
        recentLayout.setMargins(0, dp(8), 0, dp(8));
        capturePage.addView(recentCard, recentLayout);

        listPage = column();
        listPage.setId(R.id.notes_sheet);
        listPage.setBackgroundColor(background);
        // Clip the moving sheet to the page without clipping the microphone's existing drag.
        notesLayer = new FrameLayout(this);
        notesLayer.addView(listPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(notesLayer, new FrameLayout.LayoutParams(-1, -1));
        closeNotesButton = smallButton("↓  Back to Record · swipe down");
        closeNotesButton.setId(R.id.close_notes);
        closeNotesButton.setOnClickListener(v -> closeNotes());
        listPage.addView(closeNotesButton, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout heading = row();
        countLabel = text("All notes", 17, ink);
        countLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        countLabel.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(countLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button sort = smallButton("Newest first ↓");
        sort.setTextSize(11);
        sort.setBackgroundColor(Color.TRANSPARENT);
        sort.setOnClickListener(v -> { newestFirst = !newestFirst; sort.setText(newestFirst ? "Newest first ↓" : "Oldest first ↑"); renderEntries(); });
        heading.addView(sort, new LinearLayout.LayoutParams(dp(122), dp(44)));
        listPage.addView(heading);
        searchControls = column();
        notesSearch = new EditText(this);
        notesSearch.setId(R.id.notes_search);
        notesSearch.setSingleLine(true);
        notesSearch.setTextSize(15);
        notesSearch.setTextColor(ink);
        notesSearch.setHintTextColor(muted);
        notesSearch.setHint("Search notes and transcripts…");
        notesSearch.setBackground(shape(panel, 16, border));
        notesSearch.setPadding(dp(16), dp(10), dp(16), dp(10));
        notesSearch.setContentDescription("Search notes, transcripts, tags and audio markers");
        notesSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        notesSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        notesSearch.setText(searchQuery);
        notesSearch.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            hideSearchKeyboard();
            return true;
        });
        notesSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString();
                renderEntries();
                if (notesScroll != null) notesScroll.scrollTo(0, 0);
            }
            public void afterTextChanged(Editable value) {}
        });
        searchControls.addView(notesSearch, new LinearLayout.LayoutParams(-1, dp(50)));
        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        tagFilters = row();
        filters.addView(tagFilters);
        searchControls.addView(filters, new LinearLayout.LayoutParams(-1, dp(48)));
        listPage.addView(searchControls);
        notesScroll = new NotesScrollView(this, this::closeNotes);
        notesScroll.setDragListener(notesDragListener(false));
        notesScroll.setId(R.id.notes_scroll);
        notesScroll.setFillViewport(true);
        entryList = column();
        entryList.setId(R.id.entry_list);
        notesScroll.addView(entryList);
        listPage.addView(notesScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        updateAttachmentNotice();
        renderPage();
    }

    private void showRecord() {
        notesExpanded = false;
        hideSearchKeyboard();
        renderPage();
    }

    private void showAllNotes() {
        if (touching) return; // A held mic must retain its release event, including with a second finger.
        if (cameraWorkPending()) { toast("Finish the camera capture first."); return; }
        if (notesExpanded && !notesDragging && notesAnimator == null) return;
        recentCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        settleNotes(true);
    }

    private void closeNotes() {
        hideSearchKeyboard();
        settleNotes(false);
    }

    private NotesDragListener notesDragListener(boolean opening) {
        return new NotesDragListener() {
            @Override public boolean onDragStart() {
                if (destroyed || touching || notesDragging || notesAnimator != null
                        || opening == notesExpanded || pages.getHeight() <= 0) return false;
                if (cameraWorkPending()) { toast("Finish the camera capture first."); return false; }
                if (!opening) hideSearchKeyboard();
                notesDragging = true;
                notesOpening = opening;
                prepareNotesMotion();
                recentCard.setEnabled(opening);
                listPage.setTranslationY(opening ? pages.getHeight() : 0);
                updateNotesBackHandler();
                return true;
            }

            @Override public void onDrag(float distancePx) {
                if (!notesDragging || notesOpening != opening) return;
                float distance = Math.max(0, Math.min(pages.getHeight(), distancePx));
                listPage.setTranslationY(opening ? pages.getHeight() - distance : distance);
            }

            @Override public void onDragEnd(boolean commit) {
                if (!notesDragging || notesOpening != opening) return;
                if (commit) recentCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                settleNotes(commit ? opening : !opening);
            }
        };
    }

    private void prepareNotesMotion() {
        capturePage.setVisibility(View.VISIBLE);
        notesLayer.setVisibility(View.VISIBLE);
        listPage.setVisibility(View.VISIBLE);
        closeNotesButton.setVisibility(View.VISIBLE);
        recordButton.setEnabled(false);
    }

    private void cancelNotesMotion() {
        notesDragging = false;
        ValueAnimator previous = notesAnimator;
        notesAnimator = null;
        if (previous != null) previous.cancel();
    }

    private void settleNotes(boolean expanded) {
        float height = pages.getHeight();
        float from = notesLayer.getVisibility() == View.VISIBLE ? listPage.getTranslationY()
                : notesExpanded ? 0 : height;
        cancelNotesMotion();
        notesExpanded = expanded;
        if (height <= 0 || !visible) { renderPage(); return; }
        prepareNotesMotion();
        recentCard.setEnabled(false);
        notesScroll.setDismissEnabled(false);
        float to = expanded ? 0 : height;
        listPage.setTranslationY(from);
        if (Math.abs(from - to) < 1) { renderPage(); return; }
        ValueAnimator animation = ValueAnimator.ofFloat(from, to);
        notesAnimator = animation;
        animation.setDuration(180 + Math.round(80 * Math.min(1f, Math.abs(from - to) / height)));
        animation.setInterpolator(new PathInterpolator(.2f, 0f, .1f, 1f));
        animation.addUpdateListener(value -> {
            if (notesAnimator == animation) listPage.setTranslationY((Float) value.getAnimatedValue());
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                // cancel() also calls onAnimationEnd; only the current owner may settle the UI.
                if (notesAnimator != animation || destroyed) return;
                notesAnimator = null;
                renderPage();
            }
        });
        updateNotesBackHandler();
        animation.start();
    }

    private void renderPage() {
        cancelNotesMotion();
        boolean showList = notesExpanded;
        capturePage.setVisibility(showList ? View.GONE : View.VISIBLE);
        notesLayer.setVisibility(showList ? View.VISIBLE : View.GONE);
        listPage.setVisibility(showList ? View.VISIBLE : View.GONE);
        listPage.setTranslationY(0);
        recordButton.setEnabled(!showList);
        recentCard.setEnabled(!showList);
        closeNotesButton.setVisibility(notesExpanded ? View.VISIBLE : View.GONE);
        notesScroll.setDismissEnabled(notesExpanded);
        updateNotesBackHandler();
        renderEntries();
        updateMotionControls();
    }

    private void updateNotesBackHandler() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean hasNotes = notesExpanded || notesDragging || notesAnimator != null;
        if (hasNotes && notesBackCallback == null) {
            notesBackCallback = this::closeNotes;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, notesBackCallback);
        } else if (!hasNotes && notesBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(notesBackCallback);
            notesBackCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (notesExpanded || notesDragging || notesAnimator != null) closeNotes();
        else super.onBackPressed();
    }

    private boolean recordTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (RecordingService.isBusy() && !RecordingService.isRecording()) return true;
                stopOnRelease = RecordingService.isRecording();
                if (!stopOnRelease && !captureReady()) return true;
                touching = true;
                touchPointerId = event.getPointerId(event.getActionIndex());
                startedByTouch = false;
                // Anchor every hold to the mic's layout center, independent of the
                // actual down point and the circle's press-scale animation.
                int[] micParentLocation = new int[2];
                capturePanel.getLocationOnScreen(micParentLocation);
                touchStartX = micParentLocation[0] + recordButton.getLeft() + recordButton.getWidth() / 2f;
                touchStartY = micParentLocation[1] + recordButton.getTop() + recordButton.getHeight() / 2f;
                recordButton.press(true);
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                view.getParent().requestDisallowInterceptTouchEvent(true);
                gesture.onDownAt(touchStartX, touchStartY, event.getRawX(), event.getRawY());
                int[] guideLocation = new int[2];
                gestureGuide.getLocationOnScreen(guideLocation);
                gestureGuide.begin(touchStartX - guideLocation[0], touchStartY - guideLocation[1]);
                gestureGuide.update(event.getRawX() - touchStartX, event.getRawY() - touchStartY,
                        RecordingGesture.Direction.NONE, gesture.isArmed());
                if (!stopOnRelease) main.postDelayed(beginHeldRecording, HOLD_DELAY_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touching) {
                    int pointer = event.findPointerIndex(touchPointerId);
                    if (pointer < 0) { cancelRecordTouch(view); return true; }
                    Matrix transform = new Matrix(view.getMatrix());
                    float localX = event.getX(pointer), localY = event.getY(pointer);
                    float[] delta = {localX - event.getX(), localY - event.getY()};
                    transform.mapVectors(delta);
                    // Raw indexed coordinates require API 29. Recover the owner's
                    // screen position from pointer zero on all supported versions.
                    float rawX = event.getRawX(), rawY = event.getRawY();
                    rawX += delta[0];
                    rawY += delta[1];
                    if (event.getHistorySize() > 0) {
                        // Android batches motion: a return through home may exist only in history.
                        // Capture the mic's press scale. Its parents
                        // are untransformed, so mapped local deltas recover screen-space samples.
                        for (int h = 0; h < event.getHistorySize() && touching; h++) {
                            delta[0] = event.getHistoricalX(pointer, h) - localX;
                            delta[1] = event.getHistoricalY(pointer, h) - localY;
                            transform.mapVectors(delta);
                            recordMove(view, rawX + delta[0], rawY + delta[1]);
                        }
                    }
                    recordMove(view, rawX, rawY);
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (!touching || event.getPointerId(event.getActionIndex()) != touchPointerId) return true;
                main.removeCallbacks(beginHeldRecording);
                touching = false;
                touchPointerId = -1;
                recordButton.home();
                gestureGuide.finish();
                if (gesture.getDirection() == RecordingGesture.Direction.RIGHT && (stopOnRelease || startedByTouch))
                    stopGestureVideo();
                boolean ordinaryRelease = gesture.onUp();
                if ((stopOnRelease || startedByTouch)
                        && (ordinaryRelease || !RecordingService.snapshot().locked))
                    stopRecordingAndVideo();
                view.getParent().requestDisallowInterceptTouchEvent(false);
                touchClick = true;
                view.performClick();
                touchClick = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelRecordTouch(view);
                return true;
            default: return true;
        }
    }

    private void cancelRecordTouch(View view) {
        main.removeCallbacks(beginHeldRecording);
        recordButton.home();
        gestureGuide.finish();
        if (touching && gesture.getDirection() == RecordingGesture.Direction.RIGHT && (startedByTouch || stopOnRelease))
            stopGestureVideo();
        if (touching && (startedByTouch || stopOnRelease) && !RecordingService.snapshot().locked)
            stopRecordingAndVideo();
        touching = false;
        touchPointerId = -1;
        view.getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void recordMove(View view, float rawX, float rawY) {
        if (!touching) return;
        RecordingGesture.Direction before = gesture.getDirection();
        RecordingGesture.Direction direction = gesture.update(rawX, rawY);
        recordButton.follow(rawX - touchStartX, rawY - touchStartY);
        gestureGuide.update(rawX - touchStartX, rawY - touchStartY, direction, gesture.isArmed());
        if (before == RecordingGesture.Direction.RIGHT && direction != RecordingGesture.Direction.RIGHT)
            stopGestureVideo();
        if (direction != before && direction != RecordingGesture.Direction.NONE) {
            main.removeCallbacks(beginHeldRecording);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (direction == RecordingGesture.Direction.UP) {
                if (!RecordingService.isRecording() && canRecord()) { startedByTouch = true; startRecording(); }
                if (RecordingService.isRecording()) RecordingService.lock(this);
            } else if (direction == RecordingGesture.Direction.MARKER) {
                addGestureMarker();
            } else {
                microphoneAction(direction == RecordingGesture.Direction.LEFT ? "photo"
                        : direction == RecordingGesture.Direction.RIGHT ? "video" : "text");
            }
        }
    }

    private void addGestureMarker() {
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (!RecordingService.isBusy()) {
            if (!canRecord()) return;
            startedByTouch = touching;
            startRecording();
            capture = RecordingService.snapshot();
        }
        if (projectUri == null || !projectUri.equals(capture.projectUri)) return;
        if (capture.state == RecordingService.State.RECORDING) {
            if (!RecordingService.addMarker(this)) toast("Audio is finishing. Mark the next recording instead.");
        } else if (capture.state == RecordingService.State.STARTING) {
            pendingGestureMarkerProject = capture.projectUri;
            pendingGestureMarkerEntry = capture.entryId;
            pendingGestureMarkers = Math.min(16, pendingGestureMarkers + 1);
        }
    }

    private void flushGestureMarkers(RecordingService.Snapshot capture) {
        if (pendingGestureMarkers == 0) return;
        boolean same = java.util.Objects.equals(pendingGestureMarkerProject, capture.projectUri)
                && java.util.Objects.equals(pendingGestureMarkerEntry, capture.entryId);
        if (same && capture.state == RecordingService.State.RECORDING) {
            while (pendingGestureMarkers > 0 && RecordingService.addMarker(this)) pendingGestureMarkers--;
        }
        if (!same || (capture.state != RecordingService.State.STARTING && capture.state != RecordingService.State.RECORDING)
                || pendingGestureMarkers == 0) {
            pendingGestureMarkers = 0;
            pendingGestureMarkerEntry = null;
            pendingGestureMarkerProject = null;
        }
    }

    private boolean captureReady() {
        if (permissionRequest) return false;
        if (quickResolving) { toast("Opening the latest project…"); return false; }
        if (FloatingRecorderService.isShowing() && quickMode != QUICK_RECORD) { toast("Use the floating mic, or close it from its notification."); return false; }
        if (projectOperation || (projectLoading && project == null)) { toast("Opening project…"); return false; }
        if (projectUri == null || project == null || !store.isWorkingProject(projectUri)) { newProjectDialog(); return false; }
        return true;
    }

    private void microphoneAction(String kind) {
        if (!captureReady()) return;
        RecordingService.Snapshot snapshot = RecordingService.snapshot();
        if (RecordingService.isRecording()) {
            if (!projectUri.equals(snapshot.projectUri)) { toast("Return to the recording’s project first."); return; }
            if ("text".equals(kind)) {
                if (inlineCamera != null) inlineCamera.stopVideo();
                pendingMicAction = kind;
                pendingMicProject = projectUri;
                // Text takes over the hold: retain every audio frame until Save/Skip.
                RecordingService.lock(this);
                return;
            }
            if ("photo".equals(kind)) {
                if (pendingPhotoCount >= 8) { toast("Let the pending photos finish before taking more."); return; }
                pendingPhotoProject = snapshot.projectUri;
                pendingPhotoEntry = snapshot.entryId;
                pendingPhotoCount++;
                takeNextPhoto(snapshot);
                return;
            }
            if (snapshot.state == RecordingService.State.STARTING || inlineCamera.isBusy() || pendingPhotoCount > 0) {
                pendingMicAction = kind;
                pendingMicProject = projectUri;
                return;
            }
            camera(kind, snapshot.entryId);
        } else if (RecordingService.isBusy()) toast("Wait for the current save to finish.");
        else if ("text".equals(kind)) textNote(null);
        else camera(kind, null);
    }

    private boolean canRecord() {
        if (!captureReady()) return false;
        if (cameraWorkPending()) { toast("Finish the camera capture first."); return false; }
        if (RecordingService.isBusy()) { toast("Wait for the current file to finish saving."); return false; }
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        addFirstLocationPermission(permissions);
        if (!permissions.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissionRequest = true;
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_MIC);
            return false;
        }
        return true;
    }

    private void addFirstLocationPermission(List<String> permissions) {
        if (!preferences.getBoolean("metadata_enabled", true)
                || preferences.getBoolean("location_permission_asked", false)) return;
        preferences.edit().putBoolean("location_permission_asked", true).apply();
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void startRecording() {
        stopPlayers();
        String entry = nextEntryId != null ? nextEntryId : UUID.randomUUID().toString();
        nextEntryId = null;
        updateAttachmentNotice();
        RecordingService.start(this, projectUri, entry);
    }

    private void stopRecordingAndVideo() {
        // The recorder can become ready between its broadcast and this release.
        // Accept any startup marker on that exact capture before admitting STOP.
        flushGestureMarkers(RecordingService.snapshot());
        pendingGestureMarkers = 0;
        pendingGestureMarkerEntry = null;
        pendingGestureMarkerProject = null;
        if (!"text".equals(pendingMicAction)) {
            pendingMicAction = null;
            pendingMicProject = null;
        }
        if (pendingCameraHeld) {
            pendingCameraHeld = false;
            pendingCameraProject = null;
            pendingCameraMode = null;
            pendingCameraEntry = null;
            resumeCameraPermission = false;
        }
        if (inlineCamera != null) inlineCamera.stopVideo();
        RecordingService.stop(this);
    }

    private void clearPendingPhotos() {
        pendingPhotoCount = 0;
        pendingPhotoProject = null;
        pendingPhotoEntry = null;
    }

    private boolean cameraWorkPending() {
        return pendingPhotoCount > 0 || (inlineCamera != null && inlineCamera.isBusy());
    }

    private void takeNextPhoto(RecordingService.Snapshot capture) {
        if (pendingPhotoCount == 0 || capture.state == RecordingService.State.STARTING
                || capture.state == RecordingService.State.SAVING || project == null
                || !visible || permissionRequest || projectOperation || inlineCamera.isBusy()) return;
        if (!pendingPhotoProject.equals(projectUri) || !pendingPhotoProject.equals(capture.projectUri)
                || !pendingPhotoEntry.equals(capture.entryId)) {
            clearPendingPhotos();
            return;
        }
        String entry = pendingPhotoEntry;
        pendingPhotoCount--;
        if (pendingPhotoCount == 0) { pendingPhotoProject = null; pendingPhotoEntry = null; }
        camera("photo", entry);
    }

    /** Leaving the video region never stops or pauses narration. */
    private void stopGestureVideo() {
        if ("video".equals(pendingMicAction)) { pendingMicAction = null; pendingMicProject = null; }
        if ("video".equals(pendingCameraMode)) {
            pendingCameraHeld = false;
            pendingCameraProject = null;
            pendingCameraMode = null;
            pendingCameraEntry = null;
            resumeCameraPermission = false;
        }
        if (inlineCamera != null) inlineCamera.stopVideo();
    }

    private void updateRecording() {
        if (recordButton == null) return;
        if (!touching && renderEntriesAfterHold) renderEntries();
        updateMotionControls();
        RecordingService.Snapshot snapshot = RecordingService.snapshot();
        flushGestureMarkers(snapshot);
        ProjectPublisher.Snapshot publication = ProjectPublisher.snapshot();
        if (publication.generation != observedPublicationGeneration) {
            observedPublicationGeneration = publication.generation;
            refreshPendingEntries();
        }
        boolean recording = snapshot.state == RecordingService.State.RECORDING;
        boolean starting = snapshot.state == RecordingService.State.STARTING;
        boolean saving = snapshot.state == RecordingService.State.SAVING;
        boolean videoRecording = inlineCamera != null && inlineCamera.isRecording();
        markerButton.setEnabled(recording);
        markerButton.setAlpha(recording ? 1f : .45f);
        markerButton.setText(recording && snapshot.markerCount > 0
                ? "◇  Mark moment · " + snapshot.markerCount + " · " + duration(snapshot.lastMarkerOffsetMs)
                : "◇  Mark moment");
        String markerKey = captureKey(snapshot);
        if (!markerKey.equals(observedMarkerKey)) {
            observedMarkerKey = markerKey;
            observedMarkerCount = snapshot.markerCount;
        } else if (snapshot.markerCount > observedMarkerCount) {
            observedMarkerCount = snapshot.markerCount;
            markerButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            markerButton.announceForAccessibility("Marker " + snapshot.markerCount + " saved at " + duration(snapshot.lastMarkerOffsetMs));
        }
        if (snapshot.markerError != null && !snapshot.markerError.equals(observedMarkerError)) toast(snapshot.markerError);
        observedMarkerError = snapshot.markerError;
        recordButton.recording = recording || starting || videoRecording;
        recordButton.locked = (recording || starting) && snapshot.locked;
        recordButton.invalidate();
        if (recording) {
            boolean writing = captureKey(snapshot).equals(textRecordingKey);
            recordStatus.setText(writing ? "Recording while you write" : snapshot.locked
                    ? shakeEnabled() ? "Recording · tap or shake to save" : "Recording · tap to save"
                    : "Recording · release to save");
            recordTitle.setText(duration(snapshot.elapsedMs()));
            recordHelp.setText(writing ? "Save or skip text to finish" : pendingPhotoCount > 0
                    ? "Taking photos · keep holding" : touching && !gesture.isArmed()
                    && gesture.getDirection() == RecordingGesture.Direction.NONE ? "Return to center to arm gestures"
                    : "Slide ↗ to mark · return to center to repeat");
            recordButton.setContentDescription(snapshot.locked ? "Stop and save locked recording" : "Recording. Slide up to lock or release to save.");
        } else if (starting) {
            recordStatus.setText("Starting…");
            recordTitle.setText("00:00");
            recordHelp.setText(snapshot.locked ? "Locked" : "Keep holding");
        } else if (saving) {
            recordStatus.setText("Saving…");
            recordTitle.setText("Finishing audio");
            recordHelp.setText("");
        } else if (videoRecording) {
            recordStatus.setText("Recording video");
            recordTitle.setText("Video recording");
            recordHelp.setText("Use Stop video in the camera preview to finish");
            recordButton.setContentDescription("Video recording. Use Stop video in the camera preview to finish.");
        } else {
            recordStatus.setText(snapshot.state == RecordingService.State.ERROR ? "Capture needs attention"
                    : publication.error != null && !publication.error.isEmpty() ? "Save needs attention · tap to retry"
                    : publication.finalizedCount > 0 ? "Saving project · " + publication.finalizedCount + " file(s) waiting"
                    : publication.running ? "Checking project saves…" : project == null ? "Choose a project to begin" : "Ready");
            recordTitle.setText("Hold to record");
            recordHelp.setText(shakeEnabled() ? "Shake to start / stop · ↗ adds a marker"
                    : "Slide ↗ to mark · return to center to repeat");
            recordButton.setContentDescription("Hold to record. Swipe diagonally upper right for an audio marker, up to lock, left for photo, right for video, down for text. Return to center to repeat. Double tap with accessibility to start hands-free.");
        }
        if (!"text".equals(pendingMicAction)) takeNextPhoto(snapshot);
        if (pendingMicAction != null && project != null && !projectOperation && visible && recording
                && ("text".equals(pendingMicAction) || (!inlineCamera.isBusy() && pendingPhotoCount == 0))) {
            String action = pendingMicAction;
            Uri target = pendingMicProject;
            pendingMicAction = null;
            pendingMicProject = null;
            if (target != null && target.equals(projectUri) && target.equals(snapshot.projectUri)) {
                if ("text".equals(action)) {
                    textRecordingKey = captureKey(snapshot);
                    textNote(snapshot.entryId);
                }
                else camera(action, snapshot.entryId);
            }
        }
        if (recording && !snapshot.locked) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (snapshot.error != null && !snapshot.error.equals(observedError)) {
            observedError = snapshot.error;
            message("Recording", snapshot.error);
        }
        if (snapshot.error == null) observedError = null;
        if (snapshot.state != observedState) {
            if (recording) recordButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            observedState = snapshot.state;
            if (!recording && !starting && !saving) {
                textRecordingKey = null;
                pendingMicAction = null;
                pendingMicProject = null;
                if (!touching) gesture.reset();
                refreshProject();
                refreshPendingEntries();
            }
        }
    }

    private void updateAttachmentNotice() {
        attachNotice.setText("Next audio note will join this entry. Tap here to cancel.");
        attachNotice.setVisibility(nextEntryId != null ? View.VISIBLE
                : touching && attachNotice.getVisibility() != View.GONE ? View.INVISIBLE : View.GONE);
    }

    private boolean shakeEnabled() {
        return motionControls != null && motionControls.isEnabled() && motionControls.isAvailable();
    }

    private boolean motionReady() {
        return shakeEnabled() && visible && resumed && !destroyed && hasWindowFocus()
                && !notesExpanded && !notesDragging && notesAnimator == null
                && project != null && !projectOperation && !permissionRequest && quickMode == QUICK_NONE
                && !FloatingRecorderService.isShowing()
                && (noteDialog == null || !noteDialog.isShowing())
                && (entryViewer == null || !entryViewer.isShowing())
                && (settingsDialog == null || !settingsDialog.isShowing())
                && (filesDialog == null || !filesDialog.isShowing());
    }

    private void updateMotionControls() {
        if (motionControls == null) return;
        if (motionReady()) motionControls.start(); else motionControls.stop();
    }

    private void toggleRecordingFromShake() {
        if (!motionReady()) return;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        if (capture.state == RecordingService.State.RECORDING || capture.state == RecordingService.State.STARTING) {
            stopRecordingAndVideo();
            main.removeCallbacks(beginHeldRecording);
            touching = false;
            touchPointerId = -1;
            gesture.reset();
            gestureGuide.finish();
            recordButton.home();
            recordButton.getParent().requestDisallowInterceptTouchEvent(false);
            updateAttachmentNotice();
            recordButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else if (!RecordingService.isBusy() && !cameraWorkPending() && canRecord()) {
            startRecording();
            RecordingService.lock(this);
            recordButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    private void camera(String mode, String entryId) {
        if (!captureReady()) return;
        if (inlineCamera.isBusy()) { toast("Finish the camera capture first."); return; }
        RecordingService.Snapshot snapshot = RecordingService.snapshot();
        if (RecordingService.isBusy() && snapshot.state != RecordingService.State.RECORDING) { toast("Wait for the current capture to finish."); return; }
        if (snapshot.state == RecordingService.State.RECORDING) {
            if (!projectUri.equals(snapshot.projectUri)) { toast("Return to the recording’s project first."); return; }
            entryId = snapshot.entryId;
        }
        pendingCameraMode = mode;
        pendingCameraEntry = entryId;
        pendingCameraProject = projectUri;
        pendingCameraHeld = touching && (startedByTouch || stopOnRelease) && !snapshot.locked;
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA);
        if ("video".equals(mode) && !RecordingService.isRecording()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("video_audio_permission_asked", false)) {
            preferences.edit().putBoolean("video_audio_permission_asked", true).apply();
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        addFirstLocationPermission(permissions);
        if (!permissions.isEmpty()) {
            permissionRequest = true;
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_CAMERA);
            return;
        }
        stopPlayers();
        resumeCameraPermission = false;
        pendingCameraProject = null;
        pendingCameraHeld = false;
        showRecord();
        if ("photo".equals(mode)) inlineCamera.takePhoto(projectUri, entryId);
        else inlineCamera.startVideo(projectUri, entryId);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WATCH) {
            permissionRequest = false;
            if (hasWatchPermission()) chooseWatch();
            else toast("Allow Nearby devices to receive watch recordings.");
            return;
        }
        permissionRequest = false;
        if (touching && gesture.getDirection() == RecordingGesture.Direction.RIGHT && (startedByTouch || stopOnRelease))
            stopGestureVideo();
        if (touching && (startedByTouch || stopOnRelease) && !RecordingService.snapshot().locked)
            stopRecordingAndVideo();
        touching = false;
        touchPointerId = -1;
        main.removeCallbacks(beginHeldRecording);
        recordButton.home();
        gestureGuide.finish();
        MetadataCollector.start(this);
        if ((requestCode == PERMISSION_MIC || requestCode == PERMISSION_FLOATING) && quickPermission) {
            quickPermission = false;
            boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean camera = requestCode != PERMISSION_FLOATING || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                    || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (!microphone || !camera) {
                cancelQuickCapture();
                toast(!microphone ? "Microphone permission is needed to record audio." : "Camera permission is needed for the floating mic’s media gestures.");
            } else main.post(this::continueQuickCapture);
            return;
        }
        if (requestCode == PERMISSION_MIC) toast(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "Microphone ready. Hold the button to record." : "Microphone permission is needed to record audio.");
        else if (requestCode == PERMISSION_CAMERA) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (pendingCameraProject != null && pendingCameraMode != null) {
                    resumeCameraPermission = true;
                    continueEditors();
                } else toast("Camera ready. Hold and swipe to capture.");
            } else { pendingCameraProject = null; toast("Camera permission is needed to take photos or video."); }
        } else if (requestCode == PERMISSION_LOCATION) {
            if (pendingTextProject != null) continueEditors();
            else toast("Available location will be recorded with each new capture.");
        }
    }

    private void refreshProject() {
        if (destroyed || projectOperation || quickMode != QUICK_NONE) return;
        long generation = ++refreshGeneration;
        Uri requested = store.currentProject();
        if (!java.util.Objects.equals(projectUri, requested)) {
            projectUri = requested;
            project = null;
            nextEntryId = null;
            updateAttachmentNotice();
        }
        if (requested == null || !store.isWorkingProject(requested)) {
            projectLoading = false;
            project = null;
            refreshPendingEntries();
            renderEntries();
            if (requested != null && pendingImportSource == null && !requested.equals(attemptedUpgrade)) {
                attemptedUpgrade = requested;
                openProject(requested, store.defaultFolder());
            }
            return;
        }
        refreshPendingEntries();
        projectLoading = true;
        io.execute(() -> {
            if (generation != refreshGeneration) return;
            try {
                JSONObject loaded = store.readProject(requested);
                main.post(() -> {
                    if (!destroyed && generation == refreshGeneration) {
                        if (!requested.equals(store.currentProject()) || !store.isWorkingProject(requested)) {
                            refreshProject();
                            return;
                        }
                        projectLoading = false;
                        if (project == null || loaded.optLong("revision") >= project.optLong("revision"))
                            updateProjectIndex(loaded);
                        renderEntries();
                        continueEditors();
                        continueQuickCapture();
                    }
                });
            } catch (Exception error) {
                main.post(() -> { if (!destroyed && generation == refreshGeneration) {
                    projectLoading = false;
                    cancelQuickCapture();
                    // Keep the last verified local view usable while provider access is restored.
                    renderEntries();
                    toast(errorMessage(error));
                } });
            }
        });
    }

    private void refreshPendingEntries() {
        if (destroyed) return;
        Uri requested = projectUri;
        long generation = ++pendingRefreshGeneration;
        if (requested == null || !store.isWorkingProject(requested)) {
            stagedEntries = new JSONArray();
            stagedProject = null;
            return;
        }
        localIo.execute(() -> {
            if (generation != pendingRefreshGeneration) return;
            try {
                JSONObject cached = store.readCachedProject(requested);
                JSONArray pending = store.pendingEntries(requested);
                main.post(() -> {
                    if (destroyed || generation != pendingRefreshGeneration || !requested.equals(projectUri)) return;
                    if (cached != null && (project == null
                            || cached.optLong("revision") > project.optLong("revision"))) updateProjectIndex(cached);
                    stagedProject = requested;
                    stagedEntries = pending;
                    renderEntries();
                });
            } catch (Exception error) {
                main.post(() -> { if (!destroyed && generation == pendingRefreshGeneration) toast(errorMessage(error)); });
            }
        });
    }

    private void updateProjectIndex(JSONObject loaded) {
        project = loaded;
        if (entryViewer != null) entryViewer.refreshTranscripts(projectUri, loaded);
    }

    private List<JSONObject> entries() {
        return entries(true);
    }

    private List<JSONObject> entries(boolean includePending) {
        JSONArray pending = includePending && projectUri != null && projectUri.equals(stagedProject)
                ? stagedEntries : null;
        return NoteEntries.snapshot(project, pending, newestFirst);
    }

    private void renderEntries() {
        if (entryList == null) return;
        if (touching) { renderEntriesAfterHold = true; return; }
        renderEntriesAfterHold = false;
        updateAttachmentNotice();
        if (!java.util.Objects.equals(notesFilterProject, projectUri)) {
            notesFilterProject = projectUri;
            selectedTag = ""; markersOnly = false; searchQuery = "";
            if (notesSearch.length() > 0) notesSearch.setText("");
        }
        entryList.removeAllViews();
        finishProjectButton.setVisibility(project != null && store.isWorkingProject(projectUri) ? View.VISIBLE : View.GONE);
        projectTitle.setText(project == null ? "Choose project  ⌄" : project.optString("name", "Project") + "  ⌄");
        List<JSONObject> list = entries();
        renderRecent(list);
        renderTagFilters(list);
        countLabel.setText("All notes  ·  " + list.size());
        if (project == null) {
            empty("No project open", "");
            addAction(entryList, "Create a project", this::newProjectDialog);
            addAction(entryList, "Import project ZIP", () -> chooseFolder(PICK_IMPORT));
            return;
        }
        int total = list.size();
        list.removeIf(entry -> !NoteEntries.matchesNote(entry, searchQuery, selectedTag, markersOnly));
        boolean filtered = !searchQuery.trim().isEmpty() || !selectedTag.isEmpty() || markersOnly;
        countLabel.setText(filtered ? "Notes  ·  " + list.size() + " of " + total : "All notes  ·  " + total);
        if (list.isEmpty()) empty(filtered ? "No matching notes" : "No entries yet",
                filtered ? "Try another word or clear the filters." : "");
        String day = "";
        for (JSONObject entry : list) {
            long timestamp = NoteEntries.entryTime(entry);
            String nextDay = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp));
            if (!nextDay.equals(day)) {
                TextView date = text(nextDay.toUpperCase(Locale.getDefault()), 10, muted);
                date.setLetterSpacing(.09f);
                date.setPadding(dp(3), dp(10), 0, dp(10));
                entryList.addView(date);
                day = nextDay;
            }
            LinearLayout card = column();
            card.setPadding(dp(17), dp(15), dp(17), dp(15));
            card.setBackground(touchSurface(panel, 20, border));
            JSONArray media = entry.optJSONArray("media");
            int size = media == null ? 0 : media.length();
            TextView time = text(clock(timestamp) + (entry.optBoolean("_pending") ? "  ·  Saving to project…"
                    : size == 0 ? "  ·  Text" : "  ·  " + size + (size == 1 ? " file" : " linked files")), 11, muted);
            card.addView(time);
            TextView title = text(NoteEntries.entryLabel(entry), 16, ink);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(0, dp(8), 0, dp(6));
            card.addView(title);
            String tags = NoteEntries.tagText(entry);
            int markerCount = NoteEntries.markerCount(entry);
            if (!tags.isEmpty() || markerCount > 0) {
                TextView details = text(tags + (!tags.isEmpty() && markerCount > 0 ? "  ·  " : "")
                        + (markerCount > 0 ? "◇ " + markerCount + (markerCount == 1 ? " marker" : " markers") : ""), 12, accent);
                details.setPadding(0, dp(3), 0, 0);
                card.addView(details);
            }
            card.setClickable(true);
            card.setFocusable(true);
            card.setContentDescription(NoteEntries.entryLabel(entry) + ", " + clock(timestamp) + ", " + size + " files"
                    + (tags.isEmpty() ? "" : ", " + tags) + ", " + markerCount + " markers");
            card.setOnClickListener(v -> showEntry(entry));
            card.setOnLongClickListener(v -> { editEntry(entry.optString("id")); return true; });
            addCard(entryList, card, 10);
        }
    }

    private void hideSearchKeyboard() {
        if (notesSearch == null) return;
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(notesSearch.getWindowToken(), 0);
        notesSearch.clearFocus();
    }

    private void renderTagFilters(List<JSONObject> all) {
        if (tagFilters == null) return;
        tagFilters.removeAllViews();
        markersFilter = smallButton(markersOnly ? "◆ Markers only" : "◇ Markers only");
        markersFilter.setId(R.id.markers_filter);
        markersFilter.setSelected(markersOnly);
        markersFilter.setContentDescription("Filter notes with audio markers");
        markersFilter.setBackground(shape(markersOnly ? accent : panel, 14, border));
        markersFilter.setTextColor(markersOnly ? buttonInk : ink);
        markersFilter.setOnClickListener(v -> { markersOnly = !markersOnly; renderEntries(); notesScroll.scrollTo(0, 0); });
        tagFilters.addView(markersFilter);
        if (!searchQuery.isEmpty() || !selectedTag.isEmpty() || markersOnly) {
            Button clear = smallButton("Clear filters");
            clear.setId(R.id.clear_note_filters);
            clear.setOnClickListener(v -> {
                selectedTag = ""; markersOnly = false; searchQuery = "";
                notesSearch.setText(""); renderEntries(); notesScroll.scrollTo(0, 0);
            });
            tagFilters.addView(clear);
        }
        java.util.TreeSet<String> tags = new java.util.TreeSet<>();
        for (JSONObject entry : all) {
            JSONArray values = entry.optJSONArray("tags");
            if (values != null) for (int i = 0; i < values.length(); i++) tags.add(values.optString(i));
        }
        if (!selectedTag.isEmpty()) tags.add(selectedTag);
        for (String tag : tags) {
            if (tag.isEmpty()) continue;
            Button chip = smallButton("#" + tag);
            boolean selected = tag.equalsIgnoreCase(selectedTag);
            chip.setSelected(selected);
            chip.setContentDescription("Filter tag " + tag);
            chip.setBackground(shape(selected ? accent : panel, 14, border));
            chip.setTextColor(selected ? buttonInk : ink);
            chip.setOnClickListener(v -> { selectedTag = selected ? "" : tag; renderEntries(); notesScroll.scrollTo(0, 0); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -1);
            params.setMargins(dp(6), 0, 0, 0);
            tagFilters.addView(chip, params);
        }

    }

    private void renderRecent(List<JSONObject> list) {
        if (recentCard == null) return;
        JSONObject latest = null;
        for (JSONObject entry : list) if (latest == null || NoteEntries.entryTime(entry) > NoteEntries.entryTime(latest)) latest = entry;
        JSONObject entry = latest;
        recentTitle.setText(entry == null ? "Your latest note" : NoteEntries.entryLabel(entry));
        recentPreview.setText(entry == null ? "Hold to record · Swipe down to write" : clock(NoteEntries.entryTime(entry))
                + (entry.optBoolean("_pending") ? " · Saving to project…"
                : entry.optString("text").trim().isEmpty() ? " · Hold to edit" : " · " + entry.optString("text").trim()));
        recentCard.setContentDescription(entry == null ? "No saved notes. Swipe up for all notes."
                : "Latest note: " + NoteEntries.entryLabel(entry) + ". Tap to open; hold to edit; swipe up for all notes.");
        recentCard.setOnClickListener(v -> { if (entry != null) showEntry(entry); });
        recentCard.setOnLongClickListener(v -> { if (entry != null) editEntry(entry.optString("id")); return true; });
    }

    private void showEntry(JSONObject entry) {
        if (entry.optBoolean("_pending")) { toast("This capture is safe locally and is being saved to the project."); return; }
        if (project == null || projectOperation) return;
        hideSearchKeyboard();
        Uri viewedProject = projectUri;
        if (entryViewer != null) entryViewer.dismiss();
        entryViewer = new EntryViewer(this, store, projectUri, entry, dark, (kind, entryId) -> {
            if (destroyed || project == null || projectOperation || !viewedProject.equals(projectUri)) return;
            if (RecordingService.isBusy()) { toast("Finish the current recording first."); return; }
            if ("audio".equals(kind)) {
                nextEntryId = entryId;
                updateAttachmentNotice();
                showRecord();
                recordButton.requestFocus();
            } else if ("text".equals(kind)) textNote(entryId);
            else camera(kind, entryId);
        }, entryId -> {
            if (!destroyed && viewedProject.equals(projectUri)) editEntry(entryId);
        });
        entryViewer.setOnAnnotationsChangedListener(() -> {
            if (!destroyed && viewedProject.equals(projectUri)) refreshProject();
        });
        entryViewer.show();
    }

    private void stopPlayers() {
        if (entryViewer != null) entryViewer.stopPlayback();
    }

    private void textNote(String entryId) {
        if (!captureReady()) return;
        if (noteDialog != null && noteDialog.isShowing()) return;
        String targetEntry = entryId == null ? UUID.randomUUID().toString() : entryId;
        List<String> permissions = new ArrayList<>();
        addFirstLocationPermission(permissions);
        if (!permissions.isEmpty()) {
            pendingTextProject = projectUri;
            pendingTextEntry = targetEntry;
            permissionRequest = true;
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_LOCATION);
            return;
        }
        noteEditor(targetEntry, findEntry(targetEntry));
    }

    private JSONObject findEntry(String entryId) {
        for (JSONObject entry : entries(false)) if (entryId.equals(entry.optString("id"))) return entry;
        return null;
    }

    /** Resume an intended editor only after both its project and permission result are ready. */
    private void continueEditors() {
        if (!visible || destroyed || permissionRequest || projectOperation || project == null) return;
        if (restoredNote != null && (noteDialog == null || !noteDialog.isShowing())) {
            Bundle draft = restoredNote;
            restoredNote = null;
            if (projectUri.toString().equals(draft.getString("project"))) {
                String id = draft.getString("entry");
                noteEditor(id, findEntry(id));
                EditText body = noteDialog.findViewById(R.id.note_text_input);
                body.setText(draft.getString("text", ""));
                EditText title = noteDialog.findViewById(R.id.note_title_input);
                if (title != null) title.setText(draft.getString("title", ""));
                EditText tags = noteDialog.findViewById(R.id.note_tags_input);
                if (tags != null) tags.setText(draft.getString("tags", ""));
                return;
            }
        }
        if (pendingTextProject != null) {
            Uri intendedProject = pendingTextProject;
            String entry = pendingTextEntry;
            pendingTextProject = null;
            pendingTextEntry = null;
            if (intendedProject.equals(projectUri)) { textNote(entry); return; }
        }
        if (resumeCameraPermission && pendingCameraProject != null) {
            Uri intendedProject = pendingCameraProject;
            resumeCameraPermission = false;
            pendingCameraProject = null;
            if (intendedProject.equals(projectUri)) camera(pendingCameraMode, pendingCameraEntry);
            return;
        }
        RecordingService.Snapshot snapshot = RecordingService.snapshot();
        if (snapshot.state == RecordingService.State.RECORDING && captureKey(snapshot).equals(textRecordingKey)
                && projectUri.equals(snapshot.projectUri)
                && !captureKey(snapshot).equals(finishingTextToken) && noteDialog == null) textNote(snapshot.entryId);
    }

    private void finishTextRecording(Uri destination, String entryId, String recordingKey) {
        RecordingService.Snapshot snapshot = RecordingService.snapshot();
        if (recordingKey != null && recordingKey.equals(captureKey(snapshot))
                && RecordingService.isRecording()
                && destination.equals(snapshot.projectUri) && entryId.equals(snapshot.entryId)) {
            finishingTextToken = captureKey(snapshot);
            textRecordingKey = null;
            pendingMicAction = null;
            pendingMicProject = null;
            if (inlineCamera != null) inlineCamera.stopVideo();
            RecordingService.stop(this);
        }
    }

    private String captureKey(RecordingService.Snapshot snapshot) {
        return snapshot.projectUri + "|" + snapshot.entryId + "|" + snapshot.startedElapsedRealtime;
    }

    private void editEntry(String entryId) {
        if (!captureReady()) return;
        for (JSONObject entry : entries()) if (entryId.equals(entry.optString("id"))) {
            if (entry.optBoolean("_pending")) { toast("This capture is being saved. You can edit it when saving finishes."); return; }
            noteEditor(entryId, entry);
            return;
        }
        toast("This note is no longer available.");
    }

    private void noteEditor(String entryId, JSONObject existing) {
        stopPlayers();
        if (noteDialog != null) noteDialog.dismiss();
        activeNoteEntryId = entryId;
        Uri destination = projectUri;
        LinearLayout content = dialogColumn();
        EditText title = null;
        if (existing != null) {
            title = new EditText(this);
            title.setId(R.id.note_title_input);
            title.setHint("Name");
            title.setSingleLine(true);
            title.setTextColor(ink);
            title.setHintTextColor(muted);
            title.setText(existing.optString("title"));
            content.addView(title);
        }
        EditText body = new EditText(this);
        body.setId(R.id.note_text_input);
        body.setHint(existing == null ? "Write a note…" : "Add or edit text…");
        body.setTextColor(ink);
        body.setHintTextColor(muted);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setGravity(Gravity.TOP);
        body.setMinLines(4);
        body.setMaxLines(10);
        body.setText(existing == null ? "" : existing.optString("text"));
        content.addView(body, new LinearLayout.LayoutParams(-1, -2));
        EditText tagsInput = new EditText(this);
        tagsInput.setId(R.id.note_tags_input);
        tagsInput.setHint("Tags, separated by commas");
        tagsInput.setSingleLine(true);
        tagsInput.setTextColor(ink);
        tagsInput.setHintTextColor(muted);
        JSONArray existingTags = existing == null ? null : existing.optJSONArray("tags");
        ArrayList<String> tagValues = new ArrayList<>();
        if (existingTags != null) for (int i = 0; i < existingTags.length(); i++) tagValues.add(existingTags.optString(i));
        tagsInput.setText(String.join(", ", tagValues));
        content.addView(tagsInput, new LinearLayout.LayoutParams(-1, -2));
        content.addView(text("Tags help you find related notes. Example: apparatus, trial 2", 12, muted));
        EditText titleInput = title;
        RecordingService.Snapshot capture = RecordingService.snapshot();
        boolean textCapture = capture.state == RecordingService.State.RECORDING && captureKey(capture).equals(textRecordingKey)
                && destination.equals(capture.projectUri) && entryId.equals(capture.entryId);
        String recordingKey = textCapture ? captureKey(capture) : null;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "Text note" : "Edit note")
                .setView(scroll(content)).setPositiveButton("Save", null)
                .setNegativeButton(textCapture ? "Skip" : "Cancel", (ignored, which) -> finishTextRecording(destination, entryId, recordingKey)).create();
        noteDialog = dialog;
        dialog.setOnCancelListener(ignored -> finishTextRecording(destination, entryId, recordingKey));
        dialog.setOnDismissListener(ignored -> { if (noteDialog == dialog) noteDialog = null; });
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setId(R.id.note_save);
            save.setOnClickListener(view -> {
                if (projectOperation || project == null || !destination.equals(projectUri)) { toast("Wait for the project to finish opening."); return; }
                String value = body.getText().toString();
                String name = titleInput == null ? null : titleInput.getText().toString().trim();
                if (value.length() > 100000) { body.setError("Use 100,000 characters or fewer."); return; }
                if (name != null && name.length() > 200) { titleInput.setError("Use 200 characters or fewer."); return; }
                JSONArray tags;
                try { tags = ProjectStore.normalizeTags(tagsInput.getText().toString()); }
                catch (Exception error) { tagsInput.setError(errorMessage(error)); return; }
                JSONArray media = existing == null ? null : existing.optJSONArray("media");
                if (value.trim().isEmpty() && (media == null || media.length() == 0)) { body.setError("Write some text before saving."); return; }
                JSONObject metadata = MetadataCollector.snapshot(this);
                // Capture ends on submission, without recording the subsequent save.
                finishTextRecording(destination, entryId, recordingKey);
                projectOperation = true;
                ++refreshGeneration;
                body.setEnabled(false);
                tagsInput.setEnabled(false);
                if (titleInput != null) titleInput.setEnabled(false);
                save.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                dialog.setCancelable(false);
                io.execute(() -> {
                    try {
                        if (existing == null) store.saveText(destination, entryId, value, metadata, tags);
                        else store.updateEntry(destination, entryId, name, value, metadata, tags);
                        main.post(() -> {
                            if (destroyed) return;
                            projectOperation = false;
                            dialog.dismiss();
                            if (destination.equals(projectUri)) refreshProject();
                        });
                    } catch (Exception error) {
                        main.post(() -> {
                            if (destroyed) return;
                            projectOperation = false;
                            body.setEnabled(true);
                            tagsInput.setEnabled(true);
                            if (titleInput != null) titleInput.setEnabled(true);
                            save.setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            dialog.setCancelable(true);
                            body.setError("Not saved. " + errorMessage(error));
                        });
                    }
                });
            });
        });
        dialog.show();
    }

    private void projects() {
        if (cameraWorkPending()) { toast("Finish the camera capture first."); return; }
        if (projectOperation || projectLoading) { toast("Opening project…"); return; }
        if (RecordingService.isBusy()) { toast("Finish recording before switching projects."); return; }
        io.execute(() -> {
            List<Uri> uris = store.projects();
            List<String> names = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    JSONObject index = store.readCachedProject(uri);
                    if (index == null) index = store.readProject(uri);
                    String name = index.optString("name", "Project");
                    if (store.isWorkingProject(uri)) names.add(name);
                    else names.add(name + (store.isArchive(uri) ? "  ·  ZIP  ·  " : "  ·  Folder  ·  ") + folderLabel(uri));
                } catch (Exception error) { names.add("Unavailable · " + folderLabel(uri)); }
            }
            names.add("＋  Create a project");
            names.add("Import project ZIP");
            names.add("Import an old project folder");
            main.post(() -> {
                if (destroyed) return;
                if (RecordingService.isBusy()) { toast("Finish recording before switching projects."); return; }
                new AlertDialog.Builder(this).setTitle("Projects").setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (cameraWorkPending()) { toast("Finish the camera capture first."); return; }
                    if (RecordingService.isBusy() || projectOperation || projectLoading) { toast("Finish the current operation first."); return; }
                    if (which == uris.size()) newProjectDialog();
                    else if (which == uris.size() + 1) chooseFolder(PICK_IMPORT);
                    else if (which == uris.size() + 2) chooseFolder(PICK_LEGACY);
                    else openProject(uris.get(which), store.defaultFolder());
                }).setNegativeButton("Close", null).show();
            });
        });
    }

    private void newProjectDialog() {
        if (cameraWorkPending()) { toast("Finish the camera capture first."); return; }
        if (projectOperation || projectLoading) { toast("Opening project…"); return; }
        if (RecordingService.isBusy()) { toast("Finish recording before creating a project."); return; }
        LinearLayout content = dialogColumn();
        EditText name = new EditText(this);
        name.setId(R.id.project_name_input);
        name.setHint("Project name");
        name.setSingleLine(true);
        name.setTextColor(ink);
        name.setHintTextColor(muted);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        content.addView(name);
        Uri defaultFolder = store.defaultFolder();
        TextView destination = text(defaultFolder == null ? "Choose a storage folder next." : "Default location: " + folderLabel(defaultFolder), 12, muted);
        destination.setPadding(0, dp(16), 0, dp(8));
        content.addView(destination);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Create a project").setView(content).setPositiveButton(defaultFolder == null ? "Choose folder" : "Create", null).setNeutralButton("Different folder", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> {
            View.OnClickListener create = v -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) { name.setError("Enter a project name"); return; }
                dialog.dismiss();
                if (v == dialog.getButton(AlertDialog.BUTTON_NEUTRAL) || defaultFolder == null) {
                    pendingName = value;
                    if (!chooseFolder(PICK_CREATE)) pendingName = null;
                } else createProject(defaultFolder, value);
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(create);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(create);
        });
        dialog.show();
    }

    private void createProject(Uri parent, String name) {
        if (cameraWorkPending() || RecordingService.isBusy() || projectOperation) {
            toast("Finish the current operation before creating a project.");
            return;
        }
        projectOperation = true;
        ++refreshGeneration;
        toast("Creating project folder…");
        io.execute(() -> {
            try {
                Uri created = store.createWorkingProject(parent, name);
                JSONObject loaded = store.readProject(created);
                if (store.defaultFolder() == null) store.setDefaultFolder(parent);
                main.post(() -> selectProject(created, loaded, "Project ready."));
            } catch (Exception error) { main.post(() -> projectOperationFailed("Could not create project", error)); }
        });
    }

    private void finishProjectDialog() {
        if (touching || !readyToFinishProject()) return;
        Uri destination = projectUri;
        new AlertDialog.Builder(this).setTitle("Finish project?")
                .setMessage("Close “" + project.optString("name", "this project")
                        + "” and keep its files in the current folder. You can reopen it from Projects to keep working.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Finish and close", (dialog, which) -> finishProject(destination))
                .show();
    }

    private boolean readyToFinishProject() {
        if (project == null || !store.isWorkingProject(projectUri)) return false;
        if (cameraWorkPending() || RecordingService.isBusy()) {
            toast("Finish the current recording first.");
            return false;
        }
        if (FloatingRecorderService.isShowing()) {
            toast("Close the floating mic first.");
            return false;
        }
        if (projectOperation || projectLoading || permissionRequest || quickMode != QUICK_NONE) {
            toast("Wait for the current operation to finish.");
            return false;
        }
        return true;
    }

    private void finishProject(Uri destination) {
        if (!destination.equals(projectUri) || !readyToFinishProject()) return;
        projectOperation = true;
        ++refreshGeneration;
        stopPlayers();
        hideSearchKeyboard();
        toast("Finishing project…");
        io.execute(() -> {
            try {
                Uri folder = store.finishWorkingProject(destination);
                sendBroadcast(new Intent(ProjectPublisher.ACTION_MEDIA_SAVED).setPackage(getPackageName()),
                        "com.fieldnote.app.INTERNAL");
                main.post(() -> {
                    if (destroyed) return;
                    projectOperation = false;
                    refreshProject();
                    showRecord();
                    message("Project finished", "Project files are saved in " + folderLabel(folder)
                            + ".\n\nReopen it from Projects to add or edit notes.");
                });
            } catch (Exception error) { main.post(() -> projectOperationFailed("Could not finish project", error)); }
        });
    }

    private boolean chooseFolder(int request) {
        if (cameraWorkPending()) { toast("Finish the camera capture first."); return false; }
        if (RecordingService.isBusy() || projectOperation || projectLoading) { toast("Wait for the current operation to finish."); return false; }
        boolean zip = request == PICK_IMPORT;
        int access = pickerAccess(request);
        Intent intent = new Intent(zip ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(access | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (zip) {
            intent.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        } else intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (store.defaultFolder() != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, store.defaultFolder());
        try { startActivityForResult(intent, request); return true; }
        catch (ActivityNotFoundException error) { message("Storage picker", "Enable the system Files app to choose a location."); return false; }
    }

    private static int pickerAccess(int request) {
        int read = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        return request == PICK_IMPORT || request == PICK_LEGACY ? read : read | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_SPEECH_MODEL) {
            SpeechModel model = pendingSpeechModel;
            pendingSpeechModel = null;
            if (model != null && result == RESULT_OK && data != null && data.getData() != null) {
                startModelInstall(getApplicationContext(), model, data.getData());
                if (quickMode == QUICK_NONE) showSettingsPage(SettingsPage.TRANSCRIPTION);
            }
            return;
        }
        if (request == PICK_OVERLAY) {
            overlayPermissionRequest = false;
            if (!android.provider.Settings.canDrawOverlays(this)) {
                cancelQuickCapture();
                toast("Floating mic needs Display over other apps permission.");
            } else main.post(this::continueQuickCapture);
            return;
        }
        Uri importSource = null;
        if (request == PICK_IMPORT_DESTINATION) {
            importSource = pendingImportSource;
            pendingImportSource = null;
        }
        String name = request == PICK_CREATE ? pendingName : null;
        if (request == PICK_CREATE) pendingName = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request != PICK_CREATE && request != PICK_DEFAULT && request != PICK_IMPORT
                && request != PICK_LEGACY && request != PICK_IMPORT_DESTINATION) return;
        Uri uri = data.getData();
        try {
            int required = pickerAccess(request);
            if ((data.getFlags() & required) != required) throw new SecurityException(required == Intent.FLAG_GRANT_READ_URI_PERMISSION
                    ? "Choose a readable source." : "Choose a readable, writable location.");
            getContentResolver().takePersistableUriPermission(uri, required);
        } catch (Exception error) { message("Storage access", errorMessage(error)); return; }
        if (request == PICK_DEFAULT) { store.setDefaultFolder(uri); toast("Default save location updated."); }
        else if (request == PICK_CREATE) {
            if (name != null && !name.trim().isEmpty()) createProject(uri, name);
            else { store.setDefaultFolder(uri); newProjectDialog(); }
        } else if (request == PICK_IMPORT_DESTINATION) {
            if (importSource != null) openProject(importSource, uri);
        } else openProject(uri, store.defaultFolder());
    }

    private void openProject(Uri source, Uri destination) {
        if (cameraWorkPending() || RecordingService.isBusy() || projectOperation) {
            toast("Finish the current operation before opening a project.");
            return;
        }
        boolean working = store.isWorkingProject(source);
        if (!working && destination == null) {
            if (pendingImportSource != null) return;
            pendingImportSource = source;
            toast("Choose a folder for the editable project copy.");
            if (!chooseFolder(PICK_IMPORT_DESTINATION)) pendingImportSource = null;
            return;
        }
        projectOperation = true;
        ++refreshGeneration;
        if (!working) toast("Importing an editable project copy…");
        io.execute(() -> {
            try {
                Uri opened = working ? source : store.importWorkingProject(source, destination);
                JSONObject loaded = store.readProject(opened);
                if (working) store.setCurrentProject(opened);
                else if (store.defaultFolder() == null) store.setDefaultFolder(destination);
                main.post(() -> selectProject(opened, loaded, working ? null : "Project imported and ready to edit."));
            } catch (Exception error) { main.post(() -> projectOperationFailed("Could not open project", error)); }
        });
    }

    private void projectOperationFailed(String title, Exception error) {
        if (destroyed) return;
        projectLoading = false;
        projectOperation = false;
        message(title, errorMessage(error));
    }

    private void selectProject(Uri selected, JSONObject loaded, String feedback) {
        if (destroyed) return;
        projectLoading = false;
        projectOperation = false;
        projectUri = selected;
        updateProjectIndex(loaded);
        nextEntryId = null;
        updateAttachmentNotice();
        refreshPendingEntries();
        showRecord();
        if (feedback != null) toast(feedback);
    }

    private void projectFiles() {
        // A second finger must not steal the held microphone's release event.
        if (touching) return;
        if (cameraWorkPending()) { toast("Finish the camera capture first."); return; }
        if (projectOperation || projectLoading || permissionRequest || quickMode != QUICK_NONE) {
            toast("Wait for the project to finish opening.");
            return;
        }
        if (projectUri == null || project == null) { toast("Choose a project first."); return; }
        if (filesDialog != null && filesDialog.isShowing()) return;
        final Uri destination = projectUri;
        final Intent folderIntent;
        try {
            folderIntent = ProjectFiles.folderIntent(destination);
        } catch (IllegalArgumentException unavailable) {
            toast("Open a project before opening its files.");
            return;
        }
        stopPlayers();
        hideSearchKeyboard();
        LinearLayout content = dialogColumn();
        content.setId(R.id.files_dialog);
        TextView location = text(folderLabel(destination), 13, muted);
        location.setPadding(0, dp(4), 0, dp(8));
        content.addView(location);
        if (folderIntent == null) {
            content.addView(text("The project storage folder is unavailable.", 13, muted));
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Project files")
                .setView(scroll(content)).setNegativeButton("Cancel", null).create();
        if (folderIntent != null) {
            addAction(content, "Open storage folder", () -> openProjectFiles(dialog, destination, folderIntent))
                    .setId(R.id.open_project_folder);
        }
        filesDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (filesDialog == dialog) filesDialog = null;
            updateMotionControls();
        });
        dialog.show();
        updateMotionControls();
    }

    private void openProjectFiles(AlertDialog dialog, Uri destination, Intent intent) {
        if (destroyed || filesDialog != dialog || !destination.equals(projectUri)) return;
        if (touching || cameraWorkPending() || projectOperation || projectLoading || permissionRequest
                || quickMode != QUICK_NONE) { toast("Finish the current operation first."); return; }
        dialog.dismiss();
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException unavailable) {
            message("No file explorer available", "No installed app can open this storage folder.");
        } catch (SecurityException | IllegalArgumentException unavailable) {
            message("Files unavailable", "The storage provider could not open this location. Check access to the project folder.");
        }
    }

    private void settings() {
        if (touching) return; // A second finger must not take focus from the held microphone.
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        showSettingsPage(null);
    }

    private enum SettingsPage {
        RECORDING("Recording controls"), TRANSCRIPTION("Transcription"), PRIVACY("Capture privacy"),
        STORAGE("Project storage"), APPEARANCE("Appearance");

        final String title;
        SettingsPage(String title) { this.title = title; }
    }

    private void showSettingsPage(SettingsPage page) {
        LinearLayout content = dialogColumn();
        TranscriptionSettings picker = null;
        if (page == null) {
            settingsLink(content, SettingsPage.RECORDING.title, "Shake to record, widget, and shortcuts",
                    () -> showSettingsPage(SettingsPage.RECORDING)).setId(R.id.settings_recording);
            settingsLink(content, SettingsPage.TRANSCRIPTION.title, SpeechModels.selected(this).label + " selected",
                    () -> showSettingsPage(SettingsPage.TRANSCRIPTION)).setId(R.id.settings_transcription);
            settingsLink(content, "Watch recordings", "Connect or manage a paired watch", this::watchRecordings);
            settingsLink(content, SettingsPage.PRIVACY.title, "Location and sensor metadata",
                    () -> showSettingsPage(SettingsPage.PRIVACY));
            settingsLink(content, SettingsPage.STORAGE.title, "Save location, imports, and pending saves",
                    () -> showSettingsPage(SettingsPage.STORAGE));
            settingsLink(content, SettingsPage.APPEARANCE.title, dark ? "Dark mode on" : "Dark mode off",
                    () -> showSettingsPage(SettingsPage.APPEARANCE)).setId(R.id.settings_appearance);
            settingsHelp(content, getString(R.string.app_version_label));
        } else {
            switch (page) {
                case RECORDING -> addRecordingSettings(content);
                case TRANSCRIPTION -> picker = new TranscriptionSettings(content);
                case PRIVACY -> addPrivacySettings(content);
                case STORAGE -> addStorageSettings(content);
                case APPEARANCE -> addAppearanceSettings(content);
            }
        }
        showSettingsDialog(page == null ? "Settings" : page.title, content,
                page == null ? null : () -> showSettingsPage(null));
        transcriptionSettings = picker;
        refreshTranscriptionSettings();
    }

    private void showSettingsDialog(String title, LinearLayout content, Runnable back) {
        main.removeCallbacks(refreshModelSettings);
        transcriptionSettings = null;
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title)
                .setView(scroll(content)).setNegativeButton("Done", null);
        if (back != null) builder.setNeutralButton("Back", (clicked, which) -> back.run());
        AlertDialog previous = settingsDialog;
        AlertDialog dialog = builder.create();
        settingsDialog = dialog;
        if (previous != null) previous.dismiss();
        dialog.setOnDismissListener(ignored -> {
            if (settingsDialog == dialog) {
                settingsDialog = null;
                transcriptionSettings = null;
                main.removeCallbacks(refreshModelSettings);
            }
            updateMotionControls();
        });
        if (back != null) {
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(ignored -> {
                if (!destroyed && settingsDialog == dialog) back.run();
            });
        }
        dialog.show();
        updateMotionControls();
    }

    private Button settingsLink(LinearLayout content, String title, String description, Runnable open) {
        Button link = addAction(content, title, open);
        link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        link.setMinimumHeight(dp(58));
        link.setTextSize(15);
        SpannableString label = new SpannableString(title + "\n" + description);
        label.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new RelativeSizeSpan(0.86f), title.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(muted), title.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        link.setText(label);
        return link;
    }

    private TextView settingsHelp(LinearLayout content, String description) {
        TextView help = text(description, 13, muted);
        help.setPadding(0, dp(10), 0, dp(8));
        content.addView(help);
        return help;
    }

    private Switch settingsSwitch(LinearLayout content, String label, boolean checked) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(ink);
        control.setTextSize(16);
        control.setChecked(checked);
        control.setMinHeight(dp(48));
        content.addView(control);
        return control;
    }

    private void addAppearanceSettings(LinearLayout content) {
        Switch darkSwitch = settingsSwitch(content, "Dark mode", dark);
        darkSwitch.setId(R.id.dark_mode_switch);
        darkSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("dark_mode", checked).apply();
            if (settingsDialog != null) settingsDialog.dismiss();
            recreate();
        });
    }

    private void addPrivacySettings(LinearLayout content) {
        Switch metadata = settingsSwitch(content, "Capture metadata", preferences.getBoolean("metadata_enabled", true));
        metadata.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("metadata_enabled", checked).apply();
            MetadataCollector.start(this);
        });
        settingsHelp(content, "Include available location and sensor readings. Capture times are always kept.");
        addAction(content, "Allow location metadata", () -> {
            preferences.edit().putBoolean("location_permission_asked", true).apply();
            permissionRequest = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_LOCATION);
        });
    }

    private void addStorageSettings(LinearLayout content) {
        addAction(content, "Default save location", () -> chooseFolder(PICK_DEFAULT));
        addAction(content, "Import project ZIP", () -> chooseFolder(PICK_IMPORT));
        addAction(content, "Import an old project folder", () -> chooseFolder(PICK_LEGACY));
        addAction(content, "Retry pending saves", this::retryPending);
    }

    private void addRecordingSettings(LinearLayout content) {
        Switch motion = settingsSwitch(content, "Shake to record", motionControls.isEnabled());
        motion.setId(R.id.motion_controls_switch);
        motion.setOnCheckedChangeListener((view, enabled) -> {
            motionControls.setEnabled(enabled);
            updateMotionControls();
        });
        settingsHelp(content, "Shake once to start. Shake again to stop. Works while Record is open.");
        if (!motionControls.isAvailable())
            settingsHelp(content, "An acceleration sensor is unavailable. Touch controls still work.");
        addAction(content, "Add Record now shortcut", () -> localIo.execute(() -> {
            boolean accepted = RecordingShortcuts.requestPin(this);
            main.post(() -> { if (!destroyed) toast(accepted ? "Confirm the shortcut in your launcher." : "Long-press the Fieldnote app icon to find Record now."); });
        })).setId(R.id.add_record_shortcut);
        addAction(content, "Add Floating mic shortcut", () -> localIo.execute(() -> {
            boolean accepted = RecordingShortcuts.requestPinFloating(this);
            main.post(() -> { if (!destroyed) toast(accepted ? "Confirm the shortcut in your launcher." : "Long-press the Fieldnote app icon to find Floating mic."); });
        })).setId(R.id.add_floating_shortcut);
        addAction(content, "Add recording widget", () -> localIo.execute(() -> {
            boolean accepted = RecordingWidget.requestPin(this);
            main.post(() -> { if (!destroyed) toast(accepted ? "Confirm the widget in your launcher." : "Add Recorder from your launcher’s widget picker."); });
        }));
        addAction(content, "Show floating mic", () -> acceptQuickIntent(RecordingShortcuts.overlayIntent(this)));
        settingsHelp(content, "Tap the Recorder widget to start or stop audio in your current project. Record now opens Fieldnote and starts audio in your last-created project.");
        settingsHelp(content, "Floating mic uses your last-created project and keeps the swipe controls over other apps. It needs Display over other apps permission.");
    }

    private void showModelInstall(SpeechModel model) {
        if (model.downloadUrl == null) return;
        LinearLayout content = dialogColumn();
        settingsHelp(content, model.description);
        settingsHelp(content, "Download and extract the model outside Fieldnote. Then choose the folder named "
                + model.id + ". Fieldnote keeps its own installed copy, so allow space for both folders.");
        addAction(content, "Download model in browser", () -> {
            Intent download = new Intent(Intent.ACTION_VIEW, Uri.parse(model.downloadUrl)).addCategory(Intent.CATEGORY_BROWSABLE);
            try { startActivity(download); }
            catch (ActivityNotFoundException unavailable) {
                message("Browser unavailable", "Download and extract the model on another device, then copy its folder to this phone.\n\n" + model.downloadUrl);
            }
        });
        addAction(content, "Choose model folder", () -> chooseSpeechModelFolder(model));
        showSettingsDialog(model.label, content, () -> showSettingsPage(SettingsPage.TRANSCRIPTION));
    }

    private void chooseSpeechModelFolder(SpeechModel model) {
        if (pendingSpeechModel != null) return;
        if (modelInstall != null && !modelInstall.finished) {
            showSettingsPage(SettingsPage.TRANSCRIPTION);
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        pendingSpeechModel = model;
        try { startActivityForResult(picker, PICK_SPEECH_MODEL); }
        catch (ActivityNotFoundException unavailable) {
            pendingSpeechModel = null;
            message("Folder picker unavailable", "Enable the system Files app to choose the extracted model folder.");
        }
    }

    private static void startModelInstall(Context app, SpeechModel model, Uri source) {
        if (SpeechModels.isReady(app, model) || (modelInstall != null && !modelInstall.finished)) return;
        ModelInstall operation = new ModelInstall(model);
        modelInstall = operation;
        new Thread(() -> {
            try { SpeechModels.install(app, model, source, percent -> operation.percent = percent); }
            catch (IOException failure) {
                operation.error = failure.getMessage() == null ? "Choose the extracted Vosk model folder and try again." : failure.getMessage();
            } catch (RuntimeException unavailable) {
                operation.error = "Could not read or install the model. Choose the extracted folder and try again.";
            } finally { operation.finished = true; }
        }, "Fieldnote-model-install").start();
    }

    private static final class ModelInstall {
        final SpeechModel model;
        volatile int percent = -1;
        volatile boolean finished;
        String error;

        ModelInstall(SpeechModel model) { this.model = model; }
    }

    private void refreshTranscriptionSettings() {
        main.removeCallbacks(refreshModelSettings);
        if (destroyed || transcriptionSettings == null || settingsDialog == null || !settingsDialog.isShowing()) return;
        if (transcriptionSettings.refresh()) main.postDelayed(refreshModelSettings, 300);
    }

    private final class TranscriptionSettings {
        private final TextView status;
        private final ProgressBar progress;
        private final List<ModelChoice> choices = new ArrayList<>();

        TranscriptionSettings(LinearLayout content) {
            settingsHelp(content, "Choose a model for new transcriptions. Recognition runs offline on this phone.");
            status = text("", 14, ink);
            status.setPadding(0, dp(6), 0, dp(8));
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            content.addView(status);
            progress = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setContentDescription("Model installation progress");
            content.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
            for (SpeechModel model : SpeechModel.values()) {
                RadioButton option = new RadioButton(MainActivity.this);
                option.setText(model.label);
                option.setTextColor(ink);
                option.setTextSize(16);
                option.setMinHeight(dp(48));
                option.setOnClickListener(view -> {
                    if (SpeechModels.isReady(MainActivity.this, model)
                            && SpeechModels.isSupported(MainActivity.this, model)) SpeechModels.select(MainActivity.this, model);
                    if (modelInstall != null && modelInstall.finished && modelInstall.error == null
                            && modelInstall.model == model) modelInstall = null;
                    refreshTranscriptionSettings();
                });
                content.addView(option);
                TextView details = settingsHelp(content, model.description);
                Button install = model.downloadUrl == null ? null
                        : addAction(content, "Get " + model.label, () -> showModelInstall(model));
                choices.add(new ModelChoice(model, option, details, install));
            }
        }

        boolean refresh() {
            ModelInstall operation = modelInstall;
            boolean busy = operation != null && !operation.finished;
            String message = "";
            if (operation != null) {
                if (busy) message = "Installing " + operation.model.label + "…";
                else if (operation.error != null) message = "Could not install " + operation.model.label + ". " + operation.error;
                else message = operation.model.label + " is ready to select.";
            }
            if (!message.contentEquals(status.getText())) status.setText(message);
            status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) {
                int percent = operation.percent;
                progress.setIndeterminate(percent < 0);
                if (percent >= 0) progress.setProgress(percent);
            }
            SpeechModel selected = SpeechModels.selected(MainActivity.this);
            for (ModelChoice choice : choices) {
                boolean ready = SpeechModels.isReady(MainActivity.this, choice.model);
                boolean supported = SpeechModels.isSupported(MainActivity.this, choice.model);
                choice.option.setEnabled(ready && supported);
                choice.option.setChecked(selected == choice.model);
                String description = choice.model.description;
                if (!supported) description += " Requires a 64-bit phone with enough memory.";
                else if (SpeechModels.isQuarantined(MainActivity.this, choice.model)) {
                    description += " The last load was interrupted. Select this model again to retry.";
                }
                if (!description.contentEquals(choice.details.getText())) choice.details.setText(description);
                if (choice.install != null) {
                    choice.install.setVisibility(ready ? View.GONE : View.VISIBLE);
                    choice.install.setEnabled(!busy && supported);
                }
            }
            return busy;
        }
    }

    private static final class ModelChoice {
        final SpeechModel model;
        final RadioButton option;
        final TextView details;
        final Button install;

        ModelChoice(SpeechModel model, RadioButton option, TextView details, Button install) {
            this.model = model;
            this.option = option;
            this.details = details;
            this.install = install;
        }
    }

    private void watchRecordings() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Watch recordings")
                .setMessage(watchStatus())
                .setPositiveButton("Choose watch", (clicked, which) -> chooseWatch())
                .setNeutralButton("Stop receiving", (clicked, which) -> WatchReceiverService.stop(this))
                .setNegativeButton("Done", null).create();
        watchDialog = dialog;
        dialog.setOnDismissListener(ignored -> watchDialog = null);
        dialog.show();
    }

    private String watchStatus() {
        return WatchReceiverService.status() + "\n\nFieldnote receives audio from a paired watch over Bluetooth. Open the project you want before sending a recording.";
    }

    private boolean hasWatchPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void chooseWatch() {
        if (!hasWatchPermission()) {
            permissionRequest = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_WATCH);
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null || !adapter.isEnabled()) {
                toast("Turn on Bluetooth and pair your watch first.");
                return;
            }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            if (devices.isEmpty()) {
                toast("Pair your watch with this phone first.");
                return;
            }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice device = devices.get(i);
                String name = device.getName();
                String address = device.getAddress();
                labels[i] = (name == null || name.isEmpty() ? "Paired device" : name) + " · " + address.substring(12);
            }
            new AlertDialog.Builder(this).setTitle("Receive recordings from")
                    .setItems(labels, (dialog, which) -> {
                        try {
                            WatchReceiverService.start(this, devices.get(which).getAddress());
                            toast("Starting Bluetooth reception. Check Watch recordings for status.");
                        } catch (SecurityException | IllegalStateException error) {
                            toast("Could not start watch reception. Check Bluetooth and Nearby devices access.");
                        }
                    }).setNegativeButton("Cancel", null).show();
        } catch (SecurityException error) {
            toast("Allow Nearby devices to receive watch recordings.");
        }
    }

    private void retryPending() {
        String previousError = ProjectPublisher.snapshot().error;
        ProjectPublisher.retry(this);
        if (previousError != null && !previousError.isEmpty())
            message("Retrying project saves", previousError + "\n\nThe originals remain on this device. Restore folder access or free storage if needed. A new background retry is starting.");
        else toast("Retrying project saves in the background.");
        refreshPendingEntries();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        MetadataCollector.start(this);
        IntentFilter filter = new IntentFilter(RecordingService.ACTION_STATE);
        filter.addAction(ProjectPublisher.ACTION_MEDIA_SAVED);
        filter.addAction(ProjectPublisher.ACTION_STATE);
        filter.addAction(AudioTranscriptionJob.ACTION_TRANSCRIPT_UPDATED);
        filter.addAction(WatchReceiverService.ACTION_STATUS_CHANGED);
        // Signature permission also protects this receiver on API 26–32, where
        // the newer NOT_EXPORTED flag is not enforced by the platform.
        registerReceiver(recordingReceiver, filter, "com.fieldnote.app.INTERNAL", null, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        ProjectPublisher.kick(this);
        AudioTranscriptionJob.kick(this);
        refreshPendingEntries();
        main.post(ticker);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (quickMode == QUICK_NONE) refreshProject();
        updateMotionControls();
        continueQuickCapture();
    }

    @Override protected void onPause() {
        resumed = false;
        if (motionControls != null) motionControls.stop();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // A system permission window can consume the remaining finger events.
        // Only an explicit lock or text entry may keep recording without that hold.
        if (!hasFocus && touching) {
            if (gesture.getDirection() == RecordingGesture.Direction.RIGHT && (startedByTouch || stopOnRelease))
                stopGestureVideo();
            if ((startedByTouch || stopOnRelease) && !RecordingService.snapshot().locked) stopRecordingAndVideo();
            main.removeCallbacks(beginHeldRecording);
            touching = false;
            touchPointerId = -1;
            recordButton.home();
            gestureGuide.finish();
        }
        updateMotionControls();
        if (hasFocus) continueQuickCapture();
    }

    @Override protected void onStop() {
        visible = false;
        if (!isChangingConfigurations() && !permissionRequest && !overlayPermissionRequest) cancelQuickCapture();
        if (motionControls != null) motionControls.stop();
        renderPage();
        clearPendingPhotos();
        main.removeCallbacks(ticker);
        main.removeCallbacks(beginHeldRecording);
        if (!"text".equals(pendingMicAction)) {
            pendingMicAction = null;
            pendingMicProject = null;
        }
        if (receiverRegistered) { unregisterReceiver(recordingReceiver); receiverRegistered = false; }
        if (touching && !gesture.isLocked() && !RecordingService.snapshot().locked) stopRecordingAndVideo();
        touching = false;
        touchPointerId = -1;
        recordButton.home();
        gestureGuide.finish();
        if (inlineCamera != null) inlineCamera.releaseHardware();
        MetadataCollector.stop(this);
        stopPlayers();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacks(continueAfterOverlayHide);
        main.removeCallbacks(refreshModelSettings);
        if (motionControls != null) motionControls.close();
        if (settingsDialog != null) settingsDialog.dismiss();
        if (watchDialog != null) watchDialog.dismiss();
        if (filesDialog != null) filesDialog.dismiss();
        cancelNotesMotion();
        if (Build.VERSION.SDK_INT >= 33 && notesBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(notesBackCallback);
            notesBackCallback = null;
        }
        main.removeCallbacks(ticker);
        if (entryViewer != null) entryViewer.dismiss();
        if (noteDialog != null) noteDialog.dismiss();
        if (inlineCamera != null) inlineCamera.close();
        io.shutdown();
        localIo.shutdown();
        quickIo.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("quickMode", quickMode);
        out.putBoolean("quickPermission", quickPermission);
        out.putBoolean("overlayPermissionRequest", overlayPermissionRequest);
        if (quickProject != null) out.putString("quickProject", quickProject.toString());
        out.putString("quickExistingEntry", quickExistingEntry);
        out.putString("pendingName", pendingName); out.putString("nextEntryId", nextEntryId);
        out.putString("pendingCameraMode", pendingCameraMode); out.putString("pendingCameraEntry", pendingCameraEntry);
        out.putBoolean("notesExpanded", notesExpanded);
        out.putString("searchQuery", searchQuery);
        out.putString("selectedTag", selectedTag);
        out.putBoolean("markersOnly", markersOnly);
        out.putString("finishingTextToken", finishingTextToken);
        out.putString("textRecordingKey", textRecordingKey);
        out.putInt("pendingGestureMarkers", pendingGestureMarkers);
        out.putString("pendingGestureMarkerEntry", pendingGestureMarkerEntry);
        if (pendingGestureMarkerProject != null) out.putString("pendingGestureMarkerProject", pendingGestureMarkerProject.toString());
        if ("text".equals(pendingMicAction)) {
            out.putString("pendingMicAction", pendingMicAction);
            if (pendingMicProject != null) out.putString("pendingMicProject", pendingMicProject.toString());
        }
        out.putBoolean("pendingCameraHeld", pendingCameraHeld);
        if (pendingTextProject != null) out.putString("pendingTextProject", pendingTextProject.toString());
        out.putString("pendingTextEntry", pendingTextEntry);
        if (pendingCameraProject != null) out.putString("pendingCameraProject", pendingCameraProject.toString());
        out.putBoolean("resumeCameraPermission", resumeCameraPermission);
        out.putBoolean("permissionRequest", permissionRequest);
        if (noteDialog != null && noteDialog.isShowing() && activeNoteEntryId != null && projectUri != null) {
            Bundle draft = new Bundle();
            draft.putString("project", projectUri.toString());
            draft.putString("entry", activeNoteEntryId);
            EditText body = noteDialog.findViewById(R.id.note_text_input);
            EditText title = noteDialog.findViewById(R.id.note_title_input);
            EditText tags = noteDialog.findViewById(R.id.note_tags_input);
            draft.putString("text", body == null ? "" : body.getText().toString());
            draft.putString("title", title == null ? "" : title.getText().toString());
            draft.putString("tags", tags == null ? "" : tags.getText().toString());
            out.putBundle("noteDraft", draft);
        } else if (restoredNote != null) out.putBundle("noteDraft", restoredNote);
        if (pendingImportSource != null) out.putString("pendingImportSource", pendingImportSource.toString());
        if (pendingSpeechModel != null) out.putString("pendingSpeechModel", pendingSpeechModel.id);
        if (attemptedUpgrade != null) out.putString("attemptedUpgrade", attemptedUpgrade.toString());
        super.onSaveInstanceState(out);
    }

    private String folderLabel(Uri uri) {
        if (uri == null) return "No folder selected";
        try { return DocumentsContract.getDocumentId(uri).replace("primary:", "Internal storage / ").replace(':', '/'); }
        catch (IllegalArgumentException error) { try { return DocumentsContract.getTreeDocumentId(uri).replace("primary:", "Internal storage / "); } catch (IllegalArgumentException ignored) { return "Selected folder"; } }
    }
    private String clock(long timestamp) { return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp)); }
    private String duration(long milliseconds) { return NoteEntries.duration(milliseconds); }
    private String errorMessage(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private void toast(String message) { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void message(String title, String body) { if (!destroyed && !isFinishing()) new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK", null).show(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); view.setBaselineAligned(false); return view; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout dialogColumn() { LinearLayout view = column(); view.setPadding(dp(22), dp(8), dp(22), dp(14)); return view; }
    private TextView text(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("tnum"); view.setIncludeFontPadding(false); view.setLineSpacing(dp(2), 1f); return view; }
    private Button smallButton(String value) { Button view = new Button(this); view.setText(value); view.setTextSize(13); view.setAllCaps(false); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); view.setTextColor(ink); view.setPadding(dp(12), dp(8), dp(12), dp(8)); view.setMinHeight(dp(44)); view.setMinimumHeight(dp(44)); view.setStateListAnimator(null); view.setElevation(0); view.setBackground(touchSurface(panel, 14, border)); return view; }
    private GradientDrawable shape(int fill, int radius, int stroke) { GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius)); shape.setStroke(dp(1), stroke); return shape; }
    private RippleDrawable touchSurface(int fill, int radius, int stroke) { return new RippleDrawable(ColorStateList.valueOf(Color.argb(dark ? 38 : 28, Color.red(accent), Color.green(accent), Color.blue(accent))), shape(fill, radius, stroke), shape(Color.WHITE, radius, Color.TRANSPARENT)); }
    private ScrollView scroll(View child) { ScrollView view = new ScrollView(this); view.addView(child); return view; }
    private void margin(View child, int top, int bottom) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(top), 0, dp(bottom)); root.addView(child, params); }
    private void addCard(LinearLayout parent, View child, int gap) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, 0, 0, dp(gap)); parent.addView(child, params); }
    private Button addAction(LinearLayout parent, String title, Runnable action) { Button button = smallButton(title); button.setOnClickListener(v -> action.run()); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(8), 0, 0); parent.addView(button, params); return button; }
    private void empty(String title, String detail) { TextView heading = text(title, 17, ink); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); heading.setPadding(dp(5), dp(17), dp(5), dp(10)); entryList.addView(heading); TextView body = text(detail, 13, muted); body.setPadding(dp(5), 0, dp(5), dp(15)); body.setLineSpacing(dp(3), 1f); entryList.addView(body); }

    /** Reserve text/control space before sizing the mic, including two-line recent previews. */
    private final class CapturePanel extends LinearLayout {
        CapturePanel(Context context) { super(context); setOrientation(VERTICAL); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (recordButton != null && preferredMicSize > 0 && !touching
                    && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                int reserved = getPaddingTop() + getPaddingBottom();
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child == recordButton || child.getVisibility() == GONE) continue;
                    measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
                    LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) child.getLayoutParams();
                    reserved += child.getMeasuredHeight() + layout.topMargin + layout.bottomMargin;
                }
                LinearLayout.LayoutParams mic = (LinearLayout.LayoutParams) recordButton.getLayoutParams();
                int availableHeight = MeasureSpec.getSize(heightSpec) - reserved - mic.topMargin - mic.bottomMargin;
                int availableWidth = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                int size = Math.max(0, Math.min(preferredMicSize, Math.min(availableWidth, availableHeight)));
                mic.width = mic.height = size;
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private final class RecordView extends MicrophoneView {
        RecordView(Context context) {
            super(context, MainActivity.this.dark, MainActivity.this.accent,
                    MainActivity.this.recordingColor, new MicrophoneView.ActionListener() {
                        @Override public void onPhoto() { MainActivity.this.microphoneAction("photo"); }
                        @Override public void onVideo() { MainActivity.this.microphoneAction("video"); }
                        @Override public void onText() { MainActivity.this.microphoneAction("text"); }
                        @Override public void onMarker() {
                            if (RecordingService.isRecording()) MainActivity.this.addGestureMarker();
                            else MainActivity.this.toast("Start recording before adding an audio marker.");
                        }
                    });
        }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
