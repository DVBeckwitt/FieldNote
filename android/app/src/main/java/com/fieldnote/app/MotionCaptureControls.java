package com.fieldnote.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Foreground-only motion listener. The Activity owns screen/dialog/preference eligibility. */
public final class MotionCaptureControls {
    public interface Listener {
        void onForwardShake();
        void onTwist();
        default void onAudioMarker() { }
    }

    public interface TrainingListener {
        void onProgress(int completed, int required, String message);
        void onComplete(MotionAction action);
        void onError(String message);
        void onCancelled();
    }

    public static final class Availability {
        public final boolean forwardShake, twist, accelerometerFallback;
        private Availability(boolean forwardShake, boolean twist, boolean accelerometerFallback) {
            this.forwardShake = forwardShake;
            this.twist = twist;
            this.accelerometerFallback = accelerometerFallback;
        }
    }

    private static final int SAMPLE_PERIOD_US = 20_000; // 50 Hz; no high-rate permission needed.
    private final SensorManager manager;
    private final Sensor acceleration, gyroscope;
    private final boolean fallback;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MotionGestureDetector detector;
    private final MotionSegmenter segmenter = new MotionSegmenter();
    private MotionSettings settings;
    private boolean modelsValid = true;
    private String settingsLoadError;
    private MotionCalibration calibration;
    private MotionAction trainingAction;
    private TrainingListener trainingListener;
    private Runnable trainingTimeout;
    private long gyroAt = -1;
    private float gyroX, gyroY, gyroZ;
    private boolean accelerationReady, gyroReady;
    private SensorEventListener registeredListener;
    private volatile boolean running;
    private boolean requested;
    private volatile Availability availability;
    private long generation;

    public MotionCaptureControls(Context context, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("A motion listener is required.");
        this.listener = listener;
        preferences = context.getApplicationContext().getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        try { settings = MotionSettings.decodePreference(preferences.getAll().get("motion_models_v1")); }
        catch (IllegalArgumentException invalid) {
            settings = new MotionSettings();
            modelsValid = false;
            settingsLoadError = "Saved motion profiles could not be read. Motion controls are off. Reset or teach a motion, then enable motion controls again. Unreadable profiles are kept until you reset or teach.";
        }
        Object enabled = preferences.getAll().get("motion_controls");
        boolean invalidEnabled = enabled != null && !(enabled instanceof Boolean);
        if (!modelsValid || invalidEnabled) {
            // An unreadable custom mapping must never silently become a different builtin action.
            preferences.edit().putBoolean("motion_controls", false).apply();
            if (settingsLoadError == null)
                settingsLoadError = "The saved motion-controls switch was invalid and has been turned off. Review the settings before enabling it again.";
        }
        manager = context.getApplicationContext().getSystemService(SensorManager.class);
        Sensor linear = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        acceleration = linear != null ? linear : manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        fallback = acceleration != null && linear == null;
        availability = new Availability(acceleration != null, gyroscope != null, fallback);
    }

    public Availability availability() { return availability; }
    public boolean isRunning() { return running; }
    public boolean isTraining() { return calibration != null; }
    public String settingsLoadError() { return settingsLoadError; }
    public boolean hasUnreadableProfiles() { return !modelsValid; }
    public void acknowledgeSettingsWarning() {
        requireMainThread();
        if (modelsValid) settingsLoadError = null;
    }
    public int getSensitivity(MotionAction action) { return settings.getSensitivity(action); }
    public boolean isTrained(MotionAction action) { return settings.isTrained(action); }

    public void setSensitivity(MotionAction action, int sensitivity) {
        requireMainThread();
        if (isTraining()) throw new IllegalStateException("Finish teaching before changing sensitivity.");
        MotionSettings next = settings.withSensitivity(action, sensitivity);
        if (!modelsValid) { settings = next; return; } // Preserve unreadable profiles until explicit Reset/Teach.
        saveSettings(next);
    }

    public void resetTraining(MotionAction action) {
        requireMainThread();
        if (isTraining()) throw new IllegalStateException("Finish teaching before resetting a motion.");
        saveSettings(settings.resetTraining(action));
    }

    private void saveSettings(MotionSettings next) {
        String encoded = next.encode();
        preferences.edit().putString("motion_models_v1", encoded).apply();
        settings = next;
        modelsValid = true;
        settingsLoadError = null;
        segmenter.reset();
        if (detector != null) detector.reset();
    }

    public boolean beginTraining(MotionAction action, TrainingListener progress) {
        requireMainThread();
        if (action == null || progress == null) throw new IllegalArgumentException("An action and teaching listener are required.");
        if (isTraining()) return false;
        stopSensors();
        calibration = new MotionCalibration(action, settings);
        trainingAction = action;
        trainingListener = progress;
        startSensors();
        if (!accelerationReady || !gyroReady) {
            failTraining("Teaching needs working acceleration and rotation sensors on this phone.");
            return false;
        }
        trainingTimeout = () -> failTraining("Teaching timed out. Try again with three clear, similar motions.");
        main.postDelayed(trainingTimeout, 90_000);
        progress.onProgress(0, MotionCalibration.REQUIRED, "Hold still, then demonstrate your motion. Capture actions are disabled while learning.");
        return true;
    }

    /** The same bounded handoff used by live segmentation; also accepts recorded local examples. */
    void submitTrainingDemonstration(MotionSequence sequence) {
        requireMainThread();
        if (!isTraining()) return;
        MotionCalibration.Result result = calibration.addDemonstration(sequence);
        TrainingListener progress = trainingListener;
        if (result.settings != null) {
            MotionAction action = trainingAction;
            saveSettings(result.settings);
            endTraining();
            progress.onComplete(action);
        } else if (result.terminal) failTraining(result.message);
        else progress.onProgress(result.completed, result.required, result.message);
    }

    public void cancelTraining() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::cancelTraining); return; }
        if (!isTraining()) return;
        TrainingListener progress = trainingListener;
        calibration.cancel();
        endTraining();
        progress.onCancelled();
    }

    private void failTraining(String message) {
        if (!isTraining()) return;
        TrainingListener progress = trainingListener;
        calibration.cancel();
        endTraining();
        progress.onError(message);
    }

    private void endTraining() {
        if (trainingTimeout != null) main.removeCallbacks(trainingTimeout);
        trainingTimeout = null;
        calibration = null;
        trainingAction = null;
        trainingListener = null;
        stopSensors();
    }

    /** Idempotent: the UI may call this on each status refresh without restarting calibration. */
    public void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::start); return; }
        if (isTraining()) return;
        startSensors();
    }

    private void startSensors() {
        if (!modelsValid && !isTraining()) return;
        if (requested) return;
        requested = true;
        if (manager == null) return;
        segmenter.reset();
        gyroAt = -1;
        final long session = ++generation;
        final long startedAt = SystemClock.elapsedRealtimeNanos();
        SensorEventListener events = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (!running || generation != session || event.timestamp < startedAt || event.values.length < 3) return;
                long age = SystemClock.elapsedRealtimeNanos() - event.timestamp;
                if (age > 250_000_000L || age < -50_000_000L) {
                    detector.reset(); segmenter.reset(); gyroAt = -1; return;
                }
                int type = event.sensor.getType();
                if (type == Sensor.TYPE_LINEAR_ACCELERATION)
                    detector.onLinearAcceleration(event.timestamp, event.values[0], event.values[1], event.values[2]);
                else if (type == Sensor.TYPE_ACCELEROMETER)
                    detector.onAccelerometer(event.timestamp, event.values[0], event.values[1], event.values[2]);
                else if (type == Sensor.TYPE_GYROSCOPE) {
                    if (event.timestamp <= gyroAt) { segmenter.reset(); return; }
                    gyroAt = event.timestamp;
                    gyroX = event.values[0]; gyroY = event.values[1]; gyroZ = event.values[2];
                    detector.onGyroscope(event.timestamp, gyroX, gyroY, gyroZ);
                    if (accelerationReady) return;
                }
                else return;
                if (accelerationReady && detector.linearSampleAt() != event.timestamp) return;
                if (gyroReady && (gyroAt < 0 || Math.abs(event.timestamp - gyroAt) > 150_000_000L)) { segmenter.reset(); return; }
                MotionSequence sequence = segmenter.add(event.timestamp,
                        accelerationReady ? detector.linearX() : 0, accelerationReady ? detector.linearY() : 0,
                        accelerationReady ? detector.linearZ() : 0, gyroReady ? gyroX : 0, gyroReady ? gyroY : 0, gyroReady ? gyroZ : 0);
                if (!running || generation != session) return;
                if (sequence == null) return;
                if (isTraining()) { submitTrainingDemonstration(sequence); return; }
                MotionAction action = settings.recognize(sequence);
                if (action == MotionAction.AUDIO) listener.onForwardShake();
                else if (action == MotionAction.PHOTO) listener.onTwist();
                else if (action == MotionAction.MARKER) listener.onAudioMarker();
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        };
        accelerationReady = register(events, acceleration);
        gyroReady = register(events, gyroscope);
        // If a real gyro is present but registration failed, do not silently weaken
        // the translation detector by removing its expected rotation rejection signal.
        boolean shakeReady = accelerationReady && (gyroscope == null || gyroReady);
        availability = new Availability(shakeReady, gyroReady, fallback && shakeReady);
        detector = new MotionGestureDetector(shakeReady, gyroReady);
        registeredListener = events;
        running = shakeReady || gyroReady;
        if (!running) manager.unregisterListener(events);
    }

    private boolean register(SensorEventListener events, Sensor sensor) {
        if (sensor == null) return false;
        try { return manager.registerListener(events, sensor, SAMPLE_PERIOD_US, main); }
        catch (RuntimeException unavailable) { return false; }
    }

    /** Unregister and forget partial motion; a later start requires fresh, quiet samples. */
    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::stop); return; }
        if (isTraining()) { cancelTraining(); return; }
        stopSensors();
    }

    private void stopSensors() {
        if (!requested && !running) return;
        requested = false;
        running = false;
        generation++;
        if (manager != null && registeredListener != null) manager.unregisterListener(registeredListener);
        registeredListener = null;
        if (detector != null) detector.reset();
        segmenter.reset();
        accelerationReady = gyroReady = false;
        gyroAt = -1;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Motion settings and teaching must run on the main thread.");
    }
}
