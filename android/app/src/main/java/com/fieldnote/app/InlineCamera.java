package com.fieldnote.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
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
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Automatic in-place capture. A gesture starts one original, without leaving the Record page. */
@SuppressWarnings("deprecation")
public final class InlineCamera {
    public interface Listener {
        void onStateChanged(boolean busy, boolean recording);
        /** The original and final journal are durable locally; ZIP publication continues independently. */
        void onSaved();
        void onError(String message);
    }
    public static final int PREVIEW_ID = 0x00f20001;
    public static final int STATUS_ID = 0x00f20002;
    public static final int STOP_VIDEO_ID = 0x00f20003;

    private final Context context;
    private final FrameLayout container;
    private final Listener listener;
    private final ProjectStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("InlineCamera");
    private final Handler cameraHandler;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final PreviewTexture texture;
    private final TextView status;
    private final Button stop;
    private final OrientationEventListener orientation;
    private volatile int deviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private volatile boolean closed, busy, recording;
    private volatile Job current;
    // Camera resources belong exclusively to cameraHandler.
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private MediaRecorder recorder;
    private int pendingOpens;
    private boolean shutdownRequested;

    public InlineCamera(Context context, FrameLayout container, Listener listener) {
        this.context = context;
        this.container = container;
        this.listener = listener;
        store = new ProjectStore(context);
        thread.start();
        cameraHandler = new Handler(thread.getLooper());
        container.setBackgroundColor(Color.BLACK);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        FrameLayout preview = new FrameLayout(context);
        texture = new PreviewTexture(context);
        texture.setId(PREVIEW_ID);
        texture.setContentDescription("Inline camera preview");
        preview.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        layout.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(context);
        status.setId(STATUS_ID);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(4), dp(3), dp(4), dp(3));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status, new LinearLayout.LayoutParams(-1, -2));
        stop = new Button(context);
        stop.setId(STOP_VIDEO_ID);
        stop.setText("Stop video");
        stop.setContentDescription("Stop and save inline video");
        stop.setAllCaps(false);
        stop.setTextSize(12);
        stop.setMinHeight(0);
        stop.setMinimumHeight(0);
        stop.setTextColor(Color.parseColor("#0C2340"));
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#91C5FF")));
        stop.setOnClickListener(view -> stopVideo());
        layout.addView(stop, new LinearLayout.LayoutParams(-1, dp(48)));
        container.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        container.setVisibility(View.GONE);
        orientation = new OrientationEventListener(context) {
            @Override public void onOrientationChanged(int value) { deviceOrientation = value; }
        };
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Job job = current;
                if (job != null) cameraHandler.post(() -> open(job));
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Job job = current;
                if (job != null) transform(job);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Job job = current;
                // A late callback from the preceding hidden preview must not cancel a new capture.
                if (job != null && !job.finished && job.surfaceTexture == surface) {
                    job.cancelled = true;
                    cameraHandler.post(() -> release(job));
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
    }

    public boolean isBusy() { return busy; }
    public boolean isRecording() { return recording; }
    public void takePhoto(Uri projectUri, String entryId) { begin(projectUri, entryId, false); }
    public void startVideo(Uri projectUri, String entryId) { begin(projectUri, entryId, true); }

    private void begin(Uri projectUri, String entryId, boolean video) {
        if (closed || busy) return;
        if (projectUri == null || context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("Choose a project and allow camera access first.");
            return;
        }
        Job job = new Job(projectUri, entryId, video);
        current = job;
        busy = true;
        recording = false;
        stop.setVisibility(video ? View.VISIBLE : View.GONE);
        stop.setEnabled(false);
        status.setText(video ? "Starting video…" : "Taking photo…");
        container.setVisibility(View.VISIBLE);
        if (orientation.canDetectOrientation()) orientation.enable();
        listener.onStateChanged(true, false);
        io.execute(() -> {
            try {
                job.file = store.newPendingFile(video ? "video" : "photo");
                store.beginCapture(job.project, job.entryId, video ? "video" : "photo", job.file,
                        job.startedAt, job.metadata);
                job.storageReady = true;
                if (!valid(job) || !cameraHandler.post(() -> open(job))) {
                    store.abandonCapture(job.file, "Camera closed before preparation completed.");
                }
            } catch (Exception error) {
                if (!cameraHandler.post(() -> fail(job, "Could not prepare capture storage. " + message(error))))
                    finish(job, false, "Could not prepare capture storage. " + message(error));
            }
        });
        cameraHandler.postDelayed(() -> {
            if (valid(job) && !job.captureIssued && !job.recording) fail(job, "Camera did not become ready. Try again.");
        }, 15_000);
    }

    /** Ends video normally. It is also safe while preparation is still in progress. */
    public void stopVideo() {
        Job job = current;
        if (job == null || !job.video || job.finished) return;
        if (!job.recording && !job.videoStarted) {
            // Remember the caller's decision even if recorder.start() is currently
            // returning on the camera thread and marks the job recording next.
            job.cancelBeforeVideoStart = true;
            job.cancelled = true;
        }
        cameraHandler.post(() -> {
            if (job.cancelBeforeVideoStart) cancelStartingVideo(job);
            else if (job.recording) finalizeVideo(job, false);
            else if (!job.finished) fail(job, "Video cancelled before recording started.", false);
        });
    }

    private void cancelStartingVideo(Job job) {
        if (job.finished || current != job) return;
        if (job.recording && recorder != null) {
            try { recorder.stop(); }
            catch (RuntimeException ignored) { /* A cancelled startup need not contain a playable frame. */ }
        }
        fail(job, "Video cancelled before recording started.", false);
    }

    /** Called when the host stops or removes its window; valid originals still finish local staging. */
    public void releaseHardware() {
        Job job = current;
        if (job != null) job.cancelled = true;
        orientation.disable();
        cameraHandler.post(() -> release(job));
    }

    /** Permanent host cleanup; finalized originals finish staging, then the publisher owns them. */
    public void close() {
        if (closed) return;
        closed = true;
        Job job = current;
        if (job != null) job.cancelled = true;
        orientation.disable();
        main.removeCallbacksAndMessages(null);
        cameraHandler.post(() -> {
            release(job);
            io.shutdown();
            shutdownRequested = true;
            quitWhenCameraOpenResolved();
        });
    }

    private boolean valid(Job job) { return !closed && current == job && !job.cancelled && !job.finished; }

    private void open(Job job) {
        if (!valid(job) || !job.storageReady || job.opening || camera != null || !texture.isAvailable()) return;
        job.opening = true;
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) throw new IllegalStateException("No camera is available.");
            job.cameraId = ids[0];
            for (String id : ids) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { job.cameraId = id; break; }
            }
            job.characteristics = manager.getCameraCharacteristics(job.cameraId);
            Integer angle = job.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            job.sensorOrientation = angle == null ? 0 : angle;
            Integer facing = job.characteristics.get(CameraCharacteristics.LENS_FACING);
            job.front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            StreamConfigurationMap map = job.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("Camera outputs are unavailable.");
            job.captureSize = job.video ? videoSize(map.getOutputSizes(MediaRecorder.class)) : photoSize(map.getOutputSizes(ImageFormat.JPEG));
            job.previewSize = previewSize(map.getOutputSizes(SurfaceTexture.class), job.captureSize);
            put(job.metadata, "camera", cameraMetadata(job));
            ui(job, () -> transform(job));
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Camera permission was removed.");
            job.openCallbackPending = true;
            pendingOpens++;
            manager.openCamera(job.cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (!valid(job)) { device.close(); resolveOpen(job); return; }
                    resolveOpen(job);
                    camera = device;
                    if (job.video) prepareVideo(job); else preparePhoto(job);
                }
                @Override public void onDisconnected(CameraDevice device) {
                    if (camera == device) {
                        if (job.recording) finalizeVideo(job, true);
                        else fail(job, "Camera disconnected before capture completed.");
                    } else if (valid(job)) fail(job, "Camera disconnected.");
                    device.close();
                    resolveOpen(job);
                }
                @Override public void onError(CameraDevice device, int error) {
                    if (camera == device && job.recording) finalizeVideo(job, true);
                    else if (valid(job)) fail(job, "Camera unavailable (" + error + "). Check camera privacy access.");
                    device.close();
                    resolveOpen(job);
                }
            }, cameraHandler);
        } catch (Exception error) {
            resolveOpen(job);
            fail(job, "Could not open camera. " + message(error));
        }
    }

    private Surface preview(Job job) {
        SurfaceTexture surface = texture.getSurfaceTexture();
        if (surface == null) throw new IllegalStateException("Camera preview closed.");
        job.surfaceTexture = surface;
        surface.setDefaultBufferSize(job.previewSize.getWidth(), job.previewSize.getHeight());
        previewSurface = new Surface(surface);
        return previewSurface;
    }

    private void preparePhoto(Job job) {
        try {
            reader = ImageReader.newInstance(job.captureSize.getWidth(), job.captureSize.getHeight(), ImageFormat.JPEG, 2);
            ImageReader ownedReader = reader;
            ownedReader.setOnImageAvailableListener(source -> {
                if (source != reader || current != job || job.finished) return;
                try (Image image = source.acquireNextImage()) {
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    job.jpeg = new byte[buffer.remaining()];
                    buffer.get(job.jpeg);
                    put(job.metadata, "imageTimestampNanos", image.getTimestamp());
                    savePhoto(job);
                } catch (Exception error) { fail(job, "Could not read photo. " + message(error)); }
            }, cameraHandler);
            Surface surface = preview(job);
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            autoFocus(job, request);
            camera.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (!valid(job) || camera == null) { configured.close(); return; }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureCompleted(CameraCaptureSession ignored, CaptureRequest request, TotalCaptureResult result) {
                                if (!valid(job) || job.scheduled) return;
                                job.scheduled = true;
                                // Give continuous AF/AE a few preview frames before the automatic shutter.
                                cameraHandler.postDelayed(() -> capturePhoto(job), 450);
                            }
                        }, cameraHandler);
                    } catch (Exception error) { fail(job, "Could not start photo preview. " + message(error)); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    configured.close();
                    if (valid(job)) fail(job, "Camera could not configure a photo.");
                }
            }, cameraHandler);
        } catch (Exception error) { fail(job, "Could not prepare photo. " + message(error)); }
    }

    private void capturePhoto(Job job) {
        if (!valid(job) || session == null || camera == null || reader == null) return;
        job.captureIssued = true;
        job.startedAt = System.currentTimeMillis();
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            job.orientation = captureRotation(job);
            request.set(CaptureRequest.JPEG_ORIENTATION, job.orientation);
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            autoFocus(job, request);
            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frame) {
                    if (job.finished) return;
                    Integer source = job.characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
                    boolean realtime = source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
                    long elapsed = SystemClock.elapsedRealtimeNanos();
                    job.startedAt = CameraMath.sensorWallTime(timestamp, realtime, System.currentTimeMillis(), elapsed);
                    boolean mapped = realtime && timestamp > 0 && elapsed >= timestamp && elapsed - timestamp <= 30_000_000_000L;
                    put(job.metadata, "timeSource", mapped ? "camera_sensor_realtime_mapped_to_wall_clock" : "camera_capture_callback_wall_clock");
                    put(job.metadata, "sensorTimestampNanos", timestamp);
                    put(job.metadata, "sensorTimestampSource", realtime ? "elapsedRealtimeNanos" : "camera_device_clock");
                    put(job.metadata, "frameNumber", frame);
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
                    put(job.metadata, "camera", settings);
                    job.resultReceived = true;
                    savePhoto(job);
                }
                @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    if (!job.finished) fail(job, "Photo capture failed (" + failure.getReason() + ").");
                }
            }, cameraHandler);
            cameraHandler.postDelayed(() -> {
                if (current != job || job.finished) return;
                if (job.jpeg != null) {
                    put(job.metadata, "captureResultStatus", "unavailable_after_timeout");
                    job.resultReceived = true;
                    savePhoto(job);
                } else fail(job, "Camera did not return a photo. Try again.");
            }, 8_000);
        } catch (Exception error) { fail(job, "Could not take photo. " + message(error)); }
    }

    private void savePhoto(Job job) {
        if (job.finished || !job.resultReceived || job.jpeg == null) return;
        job.finished = true;
        closeCamera();
        ui(job, () -> { status.setText("Saving photo…"); container.setVisibility(View.GONE); });
        io.execute(() -> {
            try {
                try (FileOutputStream output = new FileOutputStream(job.file)) {
                    output.write(job.jpeg);
                    output.getFD().sync();
                }
                store.stageCapture(job.project, job.entryId, "photo", job.file, job.startedAt, job.startedAt,
                        job.metadata, "complete");
                finish(job, true, null);
            } catch (Exception error) {
                finish(job, false, "Photo could not be saved locally. Its original is retained for retry. " + message(error));
            }
        });
    }

    private void prepareVideo(Job job) {
        try {
            // Narration ownership is latched when the gesture starts. Ending narration during
            // camera startup must never turn that linked video into a new microphone recording.
            boolean narrationOwnsMicrophone = RecordingService.isBusy();
            job.embeddedAudio = !job.separateAudio && !narrationOwnsMicrophone
                    && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            put(job.metadata, "audioTrack", job.embeddedAudio ? "embedded_microphone_audio"
                    : job.separateAudio ? "separate_linked_audio_recording"
                    : narrationOwnsMicrophone ? "none_microphone_owned_by_narration" : "none_microphone_permission_not_granted");
            recorder = new MediaRecorder();
            if (job.embeddedAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(job.file.getAbsolutePath());
            recorder.setVideoEncodingBitRate(6_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(job.captureSize.getWidth(), job.captureSize.getHeight());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (job.embeddedAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(128_000);
                recorder.setAudioSamplingRate(48_000);
                recorder.setAudioChannels(1);
            }
            job.orientation = captureRotation(job);
            recorder.setOrientationHint(job.orientation);
            recorder.setOnErrorListener((failed, what, extra) -> cameraHandler.post(() -> {
                if (failed != recorder || current != job) return;
                if (job.recording) finalizeVideo(job, true);
                else fail(job, "Video recorder could not start (" + what + "/" + extra + ").");
            }));
            recorder.prepare();
            Surface surface = preview(job);
            Surface output = recorder.getSurface();
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(surface);
            request.addTarget(output);
            autoFocus(job, request);
            camera.createCaptureSession(Arrays.asList(surface, output), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (!valid(job) || camera == null || recorder == null) { configured.close(); return; }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                        recorder.start();
                        job.videoStarted = true;
                        job.startedAt = System.currentTimeMillis();
                        job.startedElapsed = SystemClock.elapsedRealtime();
                        job.recording = true;
                        recording = true;
                        put(job.metadata, "timeSource", "recorder_start_returned_wall_clock");
                        put(job.metadata, "videoWidthPixels", job.captureSize.getWidth());
                        put(job.metadata, "videoHeightPixels", job.captureSize.getHeight());
                        put(job.metadata, "requestedFrameRate", 30);
                        put(job.metadata, "orientationDegrees", job.orientation);
                        ui(job, () -> { stop.setEnabled(true); listener.onStateChanged(true, true); });
                        main.post(timer);
                    } catch (Exception error) { fail(job, "Video could not start. " + message(error)); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    configured.close();
                    if (valid(job)) fail(job, "Camera could not configure video.");
                }
            }, cameraHandler);
        } catch (Exception error) { fail(job, "Could not prepare video. " + message(error)); }
    }

    private final Runnable timer = new Runnable() {
        @Override public void run() {
            Job job = current;
            if (closed || job == null || !job.recording) return;
            long seconds = Math.max(0, SystemClock.elapsedRealtime() - job.startedElapsed) / 1000;
            status.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
                    + (job.separateAudio ? " · linked audio" : job.embeddedAudio ? " · mic audio" : " · silent"));
            main.postDelayed(this, 500);
        }
    };

    private void finalizeVideo(Job job, boolean interrupted) {
        if (job.cancelBeforeVideoStart) { cancelStartingVideo(job); return; }
        if (job.finished || !job.recording || recorder == null) return;
        job.recording = false;
        recording = false;
        long endedElapsed = SystemClock.elapsedRealtime();
        long endedAt = CameraMath.endTime(job.startedAt, job.startedElapsed, endedElapsed);
        put(job.metadata, "durationElapsedRealtimeMs", Math.max(0, endedElapsed - job.startedElapsed));
        put(job.metadata, "endedWallClockObserved", System.currentTimeMillis());
        put(job.metadata, "endTimeSource", "capture_start_plus_monotonic_recording_interval");
        put(job.metadata, "interrupted", interrupted);
        closeSession();
        boolean finalized = false;
        String error = null;
        try { recorder.stop(); finalized = job.file.length() > 0; }
        catch (RuntimeException failure) { error = message(failure); }
        recorder.release();
        recorder = null;
        if (!finalized) {
            fail(job, "Video was too short or interrupted before it could be saved."
                    + (error == null ? "" : " " + error));
            return;
        }
        job.finished = true;
        closeCamera();
        ui(job, () -> { stop.setEnabled(false); status.setText("Saving video…"); listener.onStateChanged(true, false); });
        io.execute(() -> {
            try {
                put(job.metadata, "encodedDurationMs", validateVideo(job.file));
            } catch (Exception invalid) {
                try { store.abandonCapture(job.file, "Video validation failed: " + message(invalid)); }
                catch (Exception ignored) { /* Retain any interrupted original and its recovery journal. */ }
                finish(job, false, "Video could not be finalized and was not marked saved. " + message(invalid));
                return;
            }
            try {
                store.stageCapture(job.project, job.entryId, "video", job.file, job.startedAt, endedAt,
                        job.metadata, interrupted ? "interrupted" : "complete");
                finish(job, true, null);
            } catch (Exception failure) {
                finish(job, false, "Video could not be saved locally. Its original is retained for retry. " + message(failure));
            }
        });
    }

    private void release(Job job) {
        if (job == null || current != job) return;
        if (job != null && current == job && !job.finished) {
            if (job.recording) { finalizeVideo(job, true); return; }
            if (job.jpeg != null) {
                job.resultReceived = true;
                put(job.metadata, "captureResultStatus", "camera_closed_after_jpeg_received");
                savePhoto(job);
                return;
            }
            fail(job, "Capture interrupted before completion.");
        } else closeCamera();
    }

    private void fail(Job job, String reason) {
        fail(job, reason, !job.cancelBeforeVideoStart);
    }

    private void fail(Job job, String reason, boolean reportError) {
        if (job.finished || current != job) return;
        // A delivered original remains useful even when its result callback or camera fails.
        if (!job.video && job.jpeg != null) {
            job.resultReceived = true;
            put(job.metadata, "captureResultStatus", reason);
            savePhoto(job);
            return;
        }
        job.finished = true;
        job.cancelled = true;
        job.recording = false;
        recording = false;
        closeCamera();
        io.execute(() -> {
            if (job.file != null) {
                try { store.abandonCapture(job.file, reason); }
                catch (Exception ignored) { /* The journal and any original remain available for recovery. */ }
            }
            finish(job, false, reportError ? reason : null);
        });
    }

    private void finish(Job job, boolean saved, String error) {
        // Only capture/staging completes a Job. Later ZIP publication never touches camera state.
        main.post(() -> {
            if (current != job) return;
            current = null;
            busy = false;
            recording = false;
            orientation.disable();
            main.removeCallbacks(timer);
            if (closed) return;
            container.setVisibility(View.GONE);
            listener.onStateChanged(false, false);
            if (saved) listener.onSaved();
            else if (error != null) listener.onError(error);
        });
    }

    private void closeSession() {
        if (session == null) return;
        try { session.stopRepeating(); } catch (CameraAccessException | IllegalStateException ignored) { }
        session.close();
        session = null;
    }

    private void resolveOpen(Job job) {
        if (job.openCallbackPending) {
            job.openCallbackPending = false;
            pendingOpens--;
        }
        quitWhenCameraOpenResolved();
    }

    private void quitWhenCameraOpenResolved() {
        // onOpened/onError must remain deliverable so an in-flight open can always be closed.
        if (shutdownRequested && pendingOpens == 0) thread.quitSafely();
    }

    private static long validateVideo(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                long duration = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
                if (duration <= 0) throw new IOException("The video track has no positive duration.");
                extractor.selectTrack(i);
                if (extractor.getSampleTime() < 0 || extractor.readSampleData(ByteBuffer.allocate(4 * 1024 * 1024), 0) <= 0)
                    throw new IOException("The video track contains no readable encoded frame.");
                return Math.max(1, duration / 1000);
            }
            throw new IOException("The file contains no video track.");
        } catch (RuntimeException error) {
            throw new IOException("The recorded video could not be read.", error);
        } finally { extractor.release(); }
    }

    private void closeCamera() {
        closeSession();
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) { }
            recorder.release(); recorder = null;
        }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
    }

    private void autoFocus(Job job, CaptureRequest.Builder request) {
        int wanted = job.video ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        int[] modes = job.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes != null) for (int mode : modes) if (mode == wanted) { request.set(CaptureRequest.CONTROL_AF_MODE, wanted); break; }
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
    }

    private JSONObject cameraMetadata(Job job) {
        JSONObject data = new JSONObject();
        put(data, "cameraId", job.cameraId);
        put(data, "lensFacing", job.front ? "front" : "back");
        put(data, "sensorOrientationDegrees", job.sensorOrientation);
        put(data, "hardwareLevel", job.characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
        put(data, "captureApi", "Camera2");
        put(data, "originalEncodingPreserved", true);
        if (!job.video) {
            put(data, "jpegWidthPixels", job.captureSize.getWidth());
            put(data, "jpegHeightPixels", job.captureSize.getHeight());
        }
        return data;
    }

    private int captureRotation(Job job) {
        if (deviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
            return CameraMath.deviceCaptureRotation(job.sensorOrientation, deviceOrientation, job.front);
        return CameraMath.jpegRotation(job.sensorOrientation, displayRotation() * 90, job.front);
    }

    private int displayRotation() {
        return context.getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
    }

    private void transform(Job job) {
        if (job.previewSize == null || texture.getWidth() == 0 || texture.getHeight() == 0) return;
        int rotation = displayRotation();
        boolean swap = ((job.sensorOrientation - rotation * 90 + 360) % 180) != 0;
        texture.setAspectRatio(swap ? job.previewSize.getHeight() : job.previewSize.getWidth(),
                swap ? job.previewSize.getWidth() : job.previewSize.getHeight());
        int width = texture.getWidth(), height = texture.getHeight();
        Matrix matrix = new Matrix();
        RectF view = new RectF(0, 0, width, height);
        RectF buffer = new RectF(0, 0, job.previewSize.getHeight(), job.previewSize.getWidth());
        float cx = view.centerX(), cy = view.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY());
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) height / job.previewSize.getHeight(), (float) width / job.previewSize.getWidth());
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) matrix.postRotate(180, cx, cy);
        texture.setTransform(matrix);
    }

    private void ui(Job job, Runnable action) { main.post(() -> { if (!closed && current == job) action.run(); }); }
    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); } catch (JSONException ignored) { }
    }
    private static long area(Size size) { return (long) size.getWidth() * size.getHeight(); }
    private static Size photoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("JPEG capture is unavailable.");
        List<Size> practical = new ArrayList<>();
        for (Size size : sizes) if (area(size) <= 16_000_000L) practical.add(size);
        return practical.isEmpty() ? Arrays.stream(sizes).min(Comparator.comparingLong(InlineCamera::area)).get()
                : practical.stream().max(Comparator.comparingLong(InlineCamera::area)).get();
    }
    private static Size videoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("Video capture is unavailable.");
        for (Size size : sizes) if (size.getWidth() == 1280 && size.getHeight() == 720) return size;
        List<Size> practical = new ArrayList<>();
        for (Size size : sizes) if (size.getWidth() <= 1920 && size.getHeight() <= 1080) practical.add(size);
        if (practical.isEmpty()) practical.addAll(Arrays.asList(sizes));
        return practical.stream().min(Comparator.comparingLong(size -> Math.abs(area(size) - 1280L * 720L))).get();
    }
    private static Size previewSize(Size[] sizes, Size capture) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("Preview is unavailable.");
        double ratio = (double) capture.getWidth() / capture.getHeight();
        List<Size> matches = new ArrayList<>();
        for (Size size : sizes) if (size.getWidth() <= 1920 && size.getHeight() <= 1080
                && Math.abs((double) size.getWidth() / size.getHeight() - ratio) < .03) matches.add(size);
        if (matches.isEmpty()) for (Size size : sizes) if (area(size) <= 1920L * 1080L) matches.add(size);
        if (matches.isEmpty()) matches.addAll(Arrays.asList(sizes));
        return matches.stream().min(Comparator.comparingLong(size -> Math.abs(area(size) - 1280L * 720L))).get();
    }

    private final class Job {
        final Uri project;
        final String entryId;
        final boolean video;
        final boolean separateAudio;
        final JSONObject metadata;
        volatile File file;
        volatile boolean cancelled, finished, recording, storageReady, videoStarted, cancelBeforeVideoStart;
        boolean opening, openCallbackPending, scheduled, captureIssued, resultReceived, embeddedAudio, front;
        volatile SurfaceTexture surfaceTexture;
        String cameraId;
        CameraCharacteristics characteristics;
        Size captureSize, previewSize;
        int sensorOrientation, orientation;
        long startedAt = System.currentTimeMillis(), startedElapsed;
        byte[] jpeg;
        Job(Uri project, String entryId, boolean video) {
            this.project = project;
            this.entryId = entryId == null || entryId.isEmpty() ? UUID.randomUUID().toString() : entryId;
            this.video = video;
            separateAudio = RecordingService.isBusy();
            metadata = MetadataCollector.snapshot(context);
            put(metadata, "timeSource", "capture_request_wall_clock");
        }
    }

    private static final class PreviewTexture extends TextureView {
        private int ratioWidth, ratioHeight;
        PreviewTexture(Context context) { super(context); }
        void setAspectRatio(int width, int height) {
            if (ratioWidth == width && ratioHeight == height) return;
            ratioWidth = width; ratioHeight = height; requestLayout();
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            if (ratioWidth == 0 || ratioHeight == 0) setMeasuredDimension(width, height);
            else if (width < height * ratioWidth / ratioHeight) setMeasuredDimension(width, width * ratioHeight / ratioWidth);
            else setMeasuredDimension(height * ratioWidth / ratioHeight, height);
        }
    }
}
