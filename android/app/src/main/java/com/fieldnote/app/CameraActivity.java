package com.fieldnote.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Internal camera: photographing an observation never takes over the narration microphone. */
@SuppressWarnings("deprecation")
public final class CameraActivity extends Activity {
    public static final String ACTION_MEDIA_SAVED = "com.fieldnote.app.MEDIA_SAVED";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fileIo = Executors.newSingleThreadExecutor();
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private ProjectStore store;
    private Uri projectUri;
    private String targetEntryId;
    private boolean videoMode;
    private volatile boolean resumed;
    private volatile boolean destroyed;
    private volatile int lifecycleGeneration;
    private int sessionGeneration;
    private boolean opening;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CameraCharacteristics characteristics;
    private String cameraId;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size previewSize;
    private Size photoSize;
    private Size videoSize;
    private int sensorOrientation;
    private boolean frontFacing;
    private OrientationEventListener orientationListener;
    private volatile int deviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private volatile boolean previewReady;
    private volatile boolean photoBusy;
    private volatile boolean videoBusy;
    private volatile boolean videoRecording;
    private PhotoJob photoJob;
    private MediaRecorder videoRecorder;
    private File videoFile;
    private JSONObject videoMetadata;
    private String videoEntryId;
    private long videoStartedAt;
    private long videoStartedElapsed;
    private int videoOrientation;
    private boolean videoHasAudio;
    private boolean videoHasSeparateAudio;
    private long videoAttemptToken;
    private PreviewTexture texture;
    private TextView title;
    private TextView message;
    private TextView audioStatus;
    private TextView timer;
    private Button stopAudio;
    private Button shutter;
    private Button photoModeButton;
    private Button videoModeButton;
    private LinearLayout audioStrip;
    private int ink;
    private int muted;
    private int background;
    private int panel;

    @Override public void onCreate(Bundle state) {
        boolean dark = getSharedPreferences("fieldnote", MODE_PRIVATE).getBoolean("dark_mode", true);
        setTheme(dark ? R.style.FieldnoteDark : R.style.FieldnoteLight);
        super.onCreate(state);
        String project = getIntent().getStringExtra("projectUri");
        if (project == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Choose a project and allow camera access first.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        projectUri = Uri.parse(project);
        targetEntryId = getIntent().getStringExtra("entryId");
        videoMode = "video".equals(getIntent().getStringExtra("mode"));
        store = new ProjectStore(this);
        cameraThread = new HandlerThread("FieldnoteCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        background = Color.parseColor(dark ? "#0C1627" : "#F7FAFF");
        panel = Color.parseColor(dark ? "#172943" : "#E7F0FF");
        ink = Color.parseColor(dark ? "#E8F1FF" : "#17314D");
        muted = Color.parseColor(dark ? "#AABFD8" : "#5A7190");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                if (orientation != ORIENTATION_UNKNOWN) deviceOrientation = orientation;
            }
        };
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        MetadataCollector.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (cameraHandler == null) return;
        resumed = true;
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        int generation = ++lifecycleGeneration;
        if (texture.isAvailable()) cameraHandler.post(() -> openCamera(generation));
        main.post(ticker);
    }

    @Override protected void onPause() {
        resumed = false;
        if (orientationListener != null) orientationListener.disable();
        ++lifecycleGeneration;
        main.removeCallbacks(ticker);
        if (cameraHandler != null) cameraHandler.post(() -> {
            if (videoRecording) stopVideo(true);
            else if (videoBusy) cancelVideoStart("Camera closed before video recording started.");
            closeCamera();
        });
        super.onPause();
    }

    @Override protected void onStop() {
        MetadataCollector.stop(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (cameraHandler != null) {
            cameraHandler.post(() -> {
                closeCamera();
                fileIo.shutdown();
                cameraThread.quitSafely();
            });
        } else fileIo.shutdown();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        configureTransform();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = horizontal();
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button back = button("‹ Record");
        back.setContentDescription("Back to Record, keep locked audio recording");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(126), dp(48)));
        title = label("Camera", 18, ink);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(header);

        audioStrip = horizontal();
        audioStrip.setBackgroundColor(panel);
        audioStrip.setPadding(dp(16), dp(8), dp(12), dp(8));
        audioStatus = label("", 12, ink);
        audioStrip.addView(audioStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stopAudio = button("Stop audio");
        stopAudio.setId(R.id.camera_stop_audio);
        stopAudio.setContentDescription("Stop and save the linked audio recording");
        stopAudio.setOnClickListener(v -> {
            stopAudio.setEnabled(false);
            RecordingService.stop(this);
        });
        audioStrip.addView(stopAudio, new LinearLayout.LayoutParams(dp(112), dp(48)));
        root.addView(audioStrip);

        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackgroundColor(Color.BLACK);
        texture = new PreviewTexture(this);
        texture.setContentDescription("Live camera preview");
        previewBox.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        timer = label("", 16, Color.WHITE);
        timer.setGravity(Gravity.CENTER);
        timer.setBackground(round(Color.parseColor("#A629201B"), 10));
        FrameLayout.LayoutParams timerParams = new FrameLayout.LayoutParams(dp(120), dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        timerParams.topMargin = dp(18);
        previewBox.addView(timer, timerParams);
        root.addView(previewBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(dp(18), dp(10), dp(18), dp(16));
        message = label("Opening camera…", 12, muted);
        message.setId(R.id.camera_status);
        message.setGravity(Gravity.CENTER);
        message.setMinHeight(dp(42));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout modes = horizontal();
        modes.setGravity(Gravity.CENTER);
        photoModeButton = button("PHOTO");
        videoModeButton = button("VIDEO");
        photoModeButton.setOnClickListener(v -> changeMode(false));
        videoModeButton.setOnClickListener(v -> changeMode(true));
        modes.addView(photoModeButton, new LinearLayout.LayoutParams(dp(100), dp(44)));
        modes.addView(videoModeButton, new LinearLayout.LayoutParams(dp(100), dp(44)));
        footer.addView(modes);
        shutter = button("●");
        shutter.setId(R.id.camera_shutter);
        shutter.setTextSize(32);
        shutter.setTextColor(Color.WHITE);
        shutter.setPadding(0, 0, 0, 0);
        shutter.setBackground(round(Color.parseColor("#2C68B2"), 100));
        LinearLayout.LayoutParams shutterParams = new LinearLayout.LayoutParams(dp(78), dp(78));
        shutterParams.topMargin = dp(8);
        footer.addView(shutter, shutterParams);
        shutter.setOnClickListener(v -> {
            if (videoRecording) cameraHandler.post(() -> stopVideo(false));
            else if (videoMode) cameraHandler.post(this::startVideo);
            else cameraHandler.post(this::capturePhoto);
        });
        root.addView(footer);
        setContentView(root);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (resumed) {
                    int generation = lifecycleGeneration;
                    cameraHandler.post(() -> openCamera(generation));
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { configureTransform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        updateControls();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            RecordingService.Snapshot audio = RecordingService.snapshot();
            boolean ongoing = RecordingService.isRecording();
            audioStrip.setVisibility(ongoing ? View.VISIBLE : View.GONE);
            stopAudio.setEnabled(ongoing);
            if (ongoing) {
                long elapsed = audio.startedElapsedRealtime > 0
                        ? Math.max(0, SystemClock.elapsedRealtime() - audio.startedElapsedRealtime) : 0;
                audioStatus.setText(audio.state == RecordingService.State.STARTING
                        ? "Preparing audio…"
                        : "● Audio " + elapsed(elapsed));
            }
            timer.setVisibility(videoRecording ? View.VISIBLE : View.GONE);
            if (videoRecording) timer.setText("● " + elapsed(SystemClock.elapsedRealtime() - videoStartedElapsed));
            main.postDelayed(this, 250);
        }
    };

    private void updateControls() {
        ui(() -> {
            boolean busy = photoBusy || videoBusy;
            shutter.setEnabled(videoRecording || (previewReady && !busy));
            shutter.setText(videoRecording ? "■" : "●");
            shutter.setContentDescription(videoRecording ? "Stop and save video" : videoMode ? "Start video recording" : "Take photo");
            photoModeButton.setEnabled(!busy && !videoRecording);
            videoModeButton.setEnabled(!busy && !videoRecording);
            photoModeButton.setTextColor(!videoMode ? ink : muted);
            videoModeButton.setTextColor(videoMode ? ink : muted);
            photoModeButton.setTypeface(null, !videoMode ? Typeface.BOLD : Typeface.NORMAL);
            videoModeButton.setTypeface(null, videoMode ? Typeface.BOLD : Typeface.NORMAL);
            title.setText(videoMode ? "Video" : "Photo");
            if (!videoRecording) timer.setVisibility(View.GONE);
        });
    }

    private void changeMode(boolean video) {
        if (photoBusy || videoBusy || videoRecording || videoMode == video) return;
        videoMode = video;
        previewReady = false;
        updateControls();
        status("Preparing " + (video ? "video" : "photo") + "…");
        cameraHandler.post(() -> {
            if (camera == null || characteristics == null || !resumed) return;
            selectPreviewSize();
            createPreviewSession();
        });
    }

    private void openCamera(int generation) {
        if (!resumed || generation != lifecycleGeneration || camera != null || opening || !texture.isAvailable()) return;
        opening = true;
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) throw new IllegalStateException("No camera is available on this device.");
            cameraId = ids[0];
            for (String id : ids) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
            }
            characteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("Camera stream configuration is unavailable.");
            photoSize = choosePhotoSize(map.getOutputSizes(ImageFormat.JPEG));
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            selectPreviewSize();
            imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Camera permission was removed.");
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    opening = false;
                    if (!resumed || generation != lifecycleGeneration) { device.close(); return; }
                    camera = device;
                    createPreviewSession();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    opening = false;
                    if (camera == device) {
                        if (videoRecording) stopVideo(true);
                        else if (videoBusy) cancelVideoStart("Camera disconnected while preparing video.");
                        closeCamera();
                    }
                    device.close();
                    status("Camera disconnected. Return to Record and reopen it.");
                }
                @Override public void onError(CameraDevice device, int error) {
                    opening = false;
                    if (camera == device) {
                        if (videoRecording) stopVideo(true);
                        else if (videoBusy) cancelVideoStart("Camera failed while preparing video.");
                        closeCamera();
                    }
                    device.close();
                    status("Camera could not open (" + error + "). Check camera privacy permissions and try again.");
                }
            }, cameraHandler);
        } catch (Exception error) {
            opening = false;
            closeCamera();
            failure("Could not open camera", error);
        }
    }

    private void selectPreviewSize() {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size aspect = videoMode ? videoSize : photoSize;
        Size[] choices = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (choices == null || choices.length == 0) throw new IllegalStateException("Camera preview is unavailable.");
        double ratio = (double) aspect.getWidth() / aspect.getHeight();
        List<Size> matches = new ArrayList<>();
        for (Size size : choices) {
            if (size.getWidth() <= 1920 && size.getHeight() <= 1080
                    && Math.abs((double) size.getWidth() / size.getHeight() - ratio) < 0.03) matches.add(size);
        }
        if (matches.isEmpty()) {
            for (Size size : choices) if (area(size) <= 1920L * 1080L) matches.add(size);
        }
        if (matches.isEmpty()) matches.addAll(Arrays.asList(choices));
        previewSize = matches.stream().min(Comparator.comparingLong(s -> Math.abs(area(s) - 1280L * 720L))).get();
        ui(this::configureTransform);
    }

    private void createPreviewSession() {
        if (camera == null || !resumed || !texture.isAvailable()) return;
        closeSession();
        previewReady = false;
        updateControls();
        final int token = ++sessionGeneration;
        try {
            Surface preview = preparePreviewSurface();
            final CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(preview);
            applyAutoFocus(request, false);
            camera.createCaptureSession(Arrays.asList(preview, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (!resumed || camera == null || token != sessionGeneration) { configured.close(); return; }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                        previewReady = true;
                        updateControls();
                        if (!photoBusy && !videoBusy) status(videoMode
                                ? (RecordingService.isRecording() ? "Video · separate linked audio" : "Ready to record")
                                : "Ready");
                    } catch (Exception error) { failure("Could not start preview", error); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    configured.close();
                    if (token == sessionGeneration) failure("Camera preview configuration failed", null);
                }
            }, cameraHandler);
        } catch (Exception error) { failure("Could not prepare camera preview", error); }
    }

    private Surface preparePreviewSurface() {
        if (previewSurface != null) previewSurface.release();
        SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
        if (surfaceTexture == null) throw new IllegalStateException("Preview surface is not ready.");
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(surfaceTexture);
        return previewSurface;
    }

    private void capturePhoto() {
        if (!previewReady || photoBusy || videoBusy || videoRecording || session == null || camera == null || !resumed) return;
        photoBusy = true;
        updateControls();
        status("Capturing photo…");
        final PhotoJob job = new PhotoJob();
        job.entryId = captureEntryId();
        job.startedAt = System.currentTimeMillis();
        job.metadata = MetadataCollector.snapshot(this);
        put(job.metadata, "camera", cameraMetadata());
        put(job.metadata, "timeSource", "shutter_request_wall_clock");
        photoJob = job;
        fileIo.execute(() -> {
            try {
                job.file = store.newPendingFile("photo");
                store.beginCapture(projectUri, job.entryId, "photo", job.file, job.startedAt, job.metadata);
                if (destroyed || !cameraHandler.post(() -> requestPhoto(job)))
                    store.abandonCapture(job.file, "Camera closed before photo preparation completed.");
            } catch (Exception error) {
                cameraHandler.post(() -> failPhoto(job, "Could not prepare photo storage", error));
            }
        });
    }

    private void requestPhoto(PhotoJob job) {
        if (job.finished) {
            if (job.file != null) abandon(job.file, "Camera closed before photo preparation completed.");
            return;
        }
        if (!resumed || session == null || camera == null || photoJob != job) {
            failPhoto(job, "Photo interrupted before capture", null); return;
        }
        try {
            CaptureRequest.Builder capture = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            capture.addTarget(imageReader.getSurface());
            job.orientation = jpegOrientation();
            capture.set(CaptureRequest.JPEG_ORIENTATION, job.orientation);
            capture.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            applyAutoFocus(capture, false);
            session.capture(capture.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                    if (job.finished) return;
                    long now = System.currentTimeMillis();
                    long elapsedNow = SystemClock.elapsedRealtimeNanos();
                    Integer source = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
                    boolean realtime = source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
                    job.startedAt = CameraMath.sensorWallTime(timestamp, realtime, now, elapsedNow);
                    boolean mapped = realtime && timestamp > 0 && elapsedNow >= timestamp && elapsedNow - timestamp <= 30_000_000_000L;
                    put(job.metadata, "timeSource", mapped ? "camera_sensor_realtime_mapped_to_wall_clock" : "camera_capture_callback_wall_clock");
                    put(job.metadata, "sensorTimestampNanos", timestamp);
                    put(job.metadata, "sensorTimestampSource", realtime ? "elapsedRealtimeNanos" : "camera_device_clock");
                    put(job.metadata, "frameNumber", frameNumber);
                }
                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    if (job.finished) return;
                    JSONObject settings = job.metadata.optJSONObject("camera");
                    if (settings == null) settings = new JSONObject();
                    put(settings, "iso", result.get(CaptureResult.SENSOR_SENSITIVITY));
                    put(settings, "exposureTimeNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                    put(settings, "focalLengthMm", result.get(CaptureResult.LENS_FOCAL_LENGTH));
                    put(settings, "apertureFNumber", result.get(CaptureResult.LENS_APERTURE));
                    put(settings, "focusDistanceDiopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE));
                    put(settings, "autoFocusState", result.get(CaptureResult.CONTROL_AF_STATE));
                    put(settings, "autoExposureState", result.get(CaptureResult.CONTROL_AE_STATE));
                    put(settings, "autoWhiteBalanceState", result.get(CaptureResult.CONTROL_AWB_STATE));
                    put(settings, "jpegOrientationDegrees", job.orientation);
                    put(settings, "missingValueMeaning", "not reported by this camera");
                    put(job.metadata, "camera", settings);
                    job.resultReceived = true;
                    savePhotoWhenReady(job);
                }
                @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    failPhoto(job, "Photo capture failed (" + failure.getReason() + ")", null);
                }
            }, cameraHandler);
            cameraHandler.postDelayed(() -> {
                if (photoJob == job && !job.finished) {
                    if (job.bytes != null) {
                        put(job.metadata, "captureResultStatus", "unavailable_after_timeout");
                        job.resultReceived = true;
                        savePhotoWhenReady(job);
                    } else failPhoto(job, "The camera did not return a photo. Try again.", null);
                }
            }, 8000);
        } catch (Exception error) { failPhoto(job, "Could not take photo", error); }
    }

    private void onImageAvailable(ImageReader reader) {
        if (reader != imageReader) return;
        try (Image image = reader.acquireNextImage()) {
            if (image == null || photoJob == null || photoJob.finished) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            photoJob.bytes = new byte[buffer.remaining()];
            buffer.get(photoJob.bytes);
            put(photoJob.metadata, "imageTimestampNanos", image.getTimestamp());
            savePhotoWhenReady(photoJob);
        } catch (Exception error) {
            if (photoJob != null) failPhoto(photoJob, "Could not read captured photo", error);
        }
    }

    private void savePhotoWhenReady(PhotoJob job) {
        if (job.finished || !job.resultReceived || job.bytes == null) return;
        job.finished = true;
        photoJob = null;
        status("Saving photo…");
        fileIo.execute(() -> {
            try {
                try (FileOutputStream output = new FileOutputStream(job.file)) {
                    output.write(job.bytes);
                    output.getFD().sync();
                }
                store.stageCapture(projectUri, job.entryId, "photo", job.file,
                        job.startedAt, job.startedAt, job.metadata, "complete");
                stagedEntry(job.entryId);
                status("Photo captured · saving project");
            } catch (Exception error) {
                failure("Photo could not be saved locally. Its original is retained for retry", error);
            } finally {
                photoBusy = false;
                updateControls();
            }
        });
    }

    private void failPhoto(PhotoJob job, String reason, Exception error) {
        if (job.finished) return;
        job.finished = true;
        if (photoJob == job) photoJob = null;
        photoBusy = false;
        if (job.file != null) abandon(job.file, reason);
        updateControls();
        failure(reason, error);
    }

    private void startVideo() {
        if (!previewReady || photoBusy || videoBusy || videoRecording || camera == null || !resumed) return;
        videoBusy = true;
        previewReady = false;
        updateControls();
        status("Preparing video…");
        videoEntryId = captureEntryId();
        videoMetadata = MetadataCollector.snapshot(this);
        put(videoMetadata, "camera", cameraMetadata());
        put(videoMetadata, "timeSource", "recorder_start_returned_wall_clock");
        videoHasSeparateAudio = RecordingService.isRecording();
        videoHasAudio = !videoHasSeparateAudio
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        put(videoMetadata, "audioTrack", videoHasAudio ? "embedded_microphone_audio"
                : videoHasSeparateAudio ? "separate_linked_audio_recording" : "none_microphone_permission_not_granted");
        long requestedAt = System.currentTimeMillis();
        final int generation = lifecycleGeneration;
        final long attempt = ++videoAttemptToken;
        final String entryId = videoEntryId;
        final JSONObject metadata = videoMetadata;
        fileIo.execute(() -> {
            try {
                File file = store.newPendingFile("video");
                store.beginCapture(projectUri, entryId, "video", file, requestedAt, metadata);
                if (destroyed) {
                    store.abandonCapture(file, "Camera closed before video preparation completed.");
                    return;
                }
                boolean posted = cameraHandler.post(() -> {
                    if (attempt != videoAttemptToken || !resumed || generation != lifecycleGeneration) {
                        abandon(file, "Camera closed before video recording started.");
                        return;
                    }
                    videoFile = file;
                    prepareVideoRecorder();
                });
                if (!posted) store.abandonCapture(file, "Camera closed before video preparation completed.");
            } catch (Exception error) {
                cameraHandler.post(() -> {
                    if (attempt != videoAttemptToken) return;
                    videoBusy = false;
                    failure("Could not prepare video storage", error);
                    if (resumed) createPreviewSession();
                });
            }
        });
    }

    private void prepareVideoRecorder() {
        if (camera == null || !resumed) { cancelVideoStart("Camera is no longer available."); return; }
        closeSession();
        try {
            videoRecorder = new MediaRecorder();
            if (videoHasAudio) videoRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            videoRecorder.setOutputFile(videoFile.getAbsolutePath());
            videoRecorder.setVideoEncodingBitRate(6_000_000);
            videoRecorder.setVideoFrameRate(30);
            videoRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (videoHasAudio) {
                videoRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                videoRecorder.setAudioEncodingBitRate(128_000);
                videoRecorder.setAudioSamplingRate(48_000);
                videoRecorder.setAudioChannels(1);
            }
            videoOrientation = jpegOrientation();
            videoRecorder.setOrientationHint(videoOrientation);
            videoRecorder.setOnErrorListener((recorder, what, extra) -> cameraHandler.post(() -> {
                if (recorder != videoRecorder) return;
                if (videoRecording) stopVideo(true);
                else cancelVideoStart("Video recorder reported an error.");
            }));
            videoRecorder.prepare();
            Surface preview = preparePreviewSurface();
            Surface recordingSurface = videoRecorder.getSurface();
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(preview);
            request.addTarget(recordingSurface);
            applyAutoFocus(request, true);
            final int token = ++sessionGeneration;
            camera.createCaptureSession(Arrays.asList(preview, recordingSurface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (!resumed || camera == null || token != sessionGeneration || videoRecorder == null) {
                        configured.close(); return;
                    }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                        videoRecorder.start();
                        videoStartedAt = System.currentTimeMillis();
                        videoStartedElapsed = SystemClock.elapsedRealtime();
                        videoRecording = true;
                        videoBusy = false;
                        put(videoMetadata, "videoWidthPixels", videoSize.getWidth());
                        put(videoMetadata, "videoHeightPixels", videoSize.getHeight());
                        put(videoMetadata, "requestedFrameRate", 30);
                        put(videoMetadata, "orientationDegrees", videoOrientation);
                        updateControls();
                        status(videoHasAudio ? "Video · microphone audio"
                                : videoHasSeparateAudio ? "Video · separate linked audio"
                                : "Silent video · microphone permission off");
                    } catch (Exception error) {
                        cancelVideoStart("Video could not start: " + messageOf(error));
                        if (resumed) createPreviewSession();
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    configured.close();
                    if (token == sessionGeneration) {
                        cancelVideoStart("This camera could not configure video recording.");
                        if (resumed) createPreviewSession();
                    }
                }
            }, cameraHandler);
        } catch (Exception error) {
            cancelVideoStart("Could not prepare video: " + messageOf(error));
            if (resumed) createPreviewSession();
        }
    }

    private void stopVideo(boolean interrupted) {
        if (!videoRecording || videoRecorder == null) return;
        videoRecording = false;
        videoBusy = true;
        long endedElapsed = SystemClock.elapsedRealtime();
        long endedAt = CameraMath.endTime(videoStartedAt, videoStartedElapsed, endedElapsed);
        File file = videoFile;
        JSONObject metadata = videoMetadata;
        String entryId = videoEntryId;
        long startedAt = videoStartedAt;
        final long attempt = videoAttemptToken;
        put(metadata, "endedWallClockObserved", System.currentTimeMillis());
        put(metadata, "durationElapsedRealtimeMs", Math.max(0, endedElapsed - videoStartedElapsed));
        put(metadata, "endTimeSource", "capture_start_plus_monotonic_recording_interval");
        put(metadata, "interrupted", interrupted);
        updateControls();
        status("Saving video…");
        closeSession();
        boolean finalized = false;
        try {
            videoRecorder.stop();
            finalized = file != null && file.length() > 0;
        } catch (RuntimeException error) {
            failure("Video was too short or interrupted before a valid file could be finalized. It has not been marked saved", error);
        } finally {
            videoRecorder.release();
            videoRecorder = null;
            videoFile = null;
        }
        if (resumed) createPreviewSession();
        if (!finalized) {
            if (file != null) abandon(file, "MediaRecorder could not finalize video.");
            videoBusy = false;
            updateControls();
            return;
        }
        fileIo.execute(() -> {
            try {
                store.stageCapture(projectUri, entryId, "video", file, startedAt, endedAt, metadata,
                        interrupted ? "interrupted" : "complete");
                stagedEntry(entryId);
                finishVideoStaging(attempt, interrupted ? "Interrupted video captured · saving project"
                        : "Video captured · saving project", null);
            } catch (Exception error) {
                finishVideoStaging(attempt, "Video could not be saved locally. Its original is retained for retry", error);
            }
        });
    }

    private void finishVideoStaging(long attempt, String text, Exception error) {
        cameraHandler.post(() -> {
            // Pause/resume may already have started a newer capture while the old original was staged.
            if (attempt != videoAttemptToken) return;
            videoBusy = false;
            if (error == null) status(text); else failure(text, error);
            updateControls();
        });
    }

    private void cancelVideoStart(String reason) {
        ++videoAttemptToken;
        ++sessionGeneration;
        closeSession();
        if (videoRecorder != null) {
            try { videoRecorder.reset(); } catch (RuntimeException ignored) { }
            videoRecorder.release();
            videoRecorder = null;
        }
        if (videoFile != null) abandon(videoFile, reason);
        videoFile = null;
        videoBusy = false;
        videoRecording = false;
        updateControls();
        status(reason);
    }

    private String captureEntryId() {
        RecordingService.Snapshot audio = RecordingService.snapshot();
        if (RecordingService.isRecording() && projectUri.equals(audio.projectUri) && audio.entryId != null) return audio.entryId;
        return targetEntryId == null || targetEntryId.isEmpty() ? UUID.randomUUID().toString() : targetEntryId;
    }

    private JSONObject cameraMetadata() {
        JSONObject data = new JSONObject();
        put(data, "cameraId", cameraId);
        put(data, "lensFacing", frontFacing ? "front" : "back");
        put(data, "sensorOrientationDegrees", sensorOrientation);
        put(data, "jpegOrientationDegrees", jpegOrientation());
        put(data, "jpegWidthPixels", photoSize.getWidth());
        put(data, "jpegHeightPixels", photoSize.getHeight());
        put(data, "hardwareLevel", characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
        put(data, "captureApi", "Camera2");
        put(data, "originalEncodingPreserved", true);
        return data;
    }

    private int jpegOrientation() {
        if (deviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
            return CameraMath.deviceCaptureRotation(sensorOrientation, deviceOrientation, frontFacing);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        return CameraMath.jpegRotation(sensorOrientation, rotation * 90, frontFacing);
    }

    private void applyAutoFocus(CaptureRequest.Builder request, boolean video) {
        int[] available = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int wanted = video ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        if (available != null) for (int mode : available) if (mode == wanted) {
            request.set(CaptureRequest.CONTROL_AF_MODE, wanted); break;
        }
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
    }

    private void configureTransform() {
        if (previewSize == null || texture == null || texture.getWidth() == 0 || texture.getHeight() == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        boolean swap = ((sensorOrientation - rotation * 90 + 360) % 180) != 0;
        texture.setAspectRatio(swap ? previewSize.getHeight() : previewSize.getWidth(),
                swap ? previewSize.getWidth() : previewSize.getHeight());
        int width = texture.getWidth();
        int height = texture.getHeight();
        Matrix matrix = new Matrix();
        RectF view = new RectF(0, 0, width, height);
        RectF buffer = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = view.centerX();
        float centerY = view.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            buffer.offset(centerX - buffer.centerX(), centerY - buffer.centerY());
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) height / previewSize.getHeight(), (float) width / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) matrix.postRotate(180, centerX, centerY);
        texture.setTransform(matrix);
    }

    private void closeSession() {
        ++sessionGeneration;
        previewReady = false;
        if (session != null) {
            try { session.stopRepeating(); } catch (CameraAccessException | IllegalStateException ignored) { }
            session.close();
            session = null;
        }
    }

    private void closeCamera() {
        closeSession();
        if (camera != null) { camera.close(); camera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (photoJob != null && !photoJob.finished) {
            if (photoJob.bytes != null) {
                put(photoJob.metadata, "captureResultStatus", "unavailable_camera_closed_after_jpeg_received");
                photoJob.resultReceived = true;
                savePhotoWhenReady(photoJob);
            } else failPhoto(photoJob, "Camera closed before the photo finished.", null);
        }
        opening = false;
        updateControls();
    }

    private void abandon(File file, String reason) {
        if (fileIo.isShutdown()) return;
        fileIo.execute(() -> {
            try { store.abandonCapture(file, reason); }
            catch (Exception error) { status(reason + " Local recovery data was retained."); }
        });
    }

    private void stagedEntry(String id) {
        // The application publisher announces ZIP completion; a local capture result must not claim it.
        ui(() -> setResult(RESULT_OK, new Intent().putExtra("entryId", id)
                .putExtra("projectUri", projectUri.toString()).putExtra("pendingPublication", true)));
    }

    private void status(String text) { ui(() -> message.setText(text)); }
    private void failure(String prefix, Exception error) { status(prefix + (error == null ? "" : ": " + messageOf(error))); }
    private static String messageOf(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private void ui(Runnable action) { if (!destroyed) main.post(() -> { if (!destroyed) action.run(); }); }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); } catch (JSONException ignored) { }
    }
    private static long area(Size size) { return (long) size.getWidth() * size.getHeight(); }

    private static Size choosePhotoSize(Size[] choices) {
        if (choices == null || choices.length == 0) throw new IllegalStateException("JPEG photos are not supported.");
        List<Size> practical = new ArrayList<>();
        for (Size size : choices) if (area(size) <= 16_000_000L) practical.add(size);
        if (practical.isEmpty()) return Arrays.stream(choices).min(Comparator.comparingLong(CameraActivity::area)).get();
        return practical.stream().max(Comparator.comparingLong(CameraActivity::area)).get();
    }

    private static Size chooseVideoSize(Size[] choices) {
        if (choices == null || choices.length == 0) return new Size(1280, 720);
        for (Size size : choices) if (size.getWidth() == 1280 && size.getHeight() == 720) return size;
        List<Size> practical = new ArrayList<>();
        for (Size size : choices) if (size.getWidth() <= 1920 && size.getHeight() <= 1080) practical.add(size);
        if (practical.isEmpty()) practical.addAll(Arrays.asList(choices));
        return practical.stream().min(Comparator.comparingLong(s -> Math.abs(area(s) - 1280L * 720L))).get();
    }

    private static String elapsed(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text); button.setTextColor(ink); button.setTextSize(13); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setBackground(round(panel, 12));
        return button;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private static final class PhotoJob {
        String entryId;
        long startedAt;
        int orientation;
        File file;
        JSONObject metadata;
        byte[] bytes;
        boolean resultReceived;
        boolean finished;
    }

    private static final class PreviewTexture extends TextureView {
        private int ratioWidth;
        private int ratioHeight;
        PreviewTexture(Context context) { super(context); }
        void setAspectRatio(int width, int height) {
            if (ratioWidth == width && ratioHeight == height) return;
            ratioWidth = width; ratioHeight = height; requestLayout();
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            if (ratioWidth == 0 || ratioHeight == 0) setMeasuredDimension(width, height);
            else if (width < height * ratioWidth / ratioHeight) setMeasuredDimension(width, width * ratioHeight / ratioWidth);
            else setMeasuredDimension(height * ratioWidth / ratioHeight, height);
        }
    }
}
