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

/** Foreground-only shake toggle. The Activity owns screen, dialog, and project eligibility. */
public final class MotionCaptureControls {
    public interface Listener { void onShake(); }

    static final String ENABLED_KEY = "shake_recording_enabled";
    private static final int SAMPLE_PERIOD_US = 20_000;
    private final SensorManager manager;
    private final Sensor accelerometer;
    private final SharedPreferences preferences;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ShakeDetector detector = new ShakeDetector();
    private SensorEventListener registeredListener;
    private final boolean available;
    private volatile boolean enabled, running;
    private boolean requested, closed;
    private long generation;

    public MotionCaptureControls(Context context, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("A shake listener is required.");
        this.listener = listener;
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        Object saved = preferences.getAll().get(ENABLED_KEY);
        if (saved == null) saved = preferences.getAll().get("motion_controls");
        enabled = saved == null || Boolean.TRUE.equals(saved);
        // Adopt the old on/off choice once; obsolete trained profiles are neither read nor erased.
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply();
        manager = app.getSystemService(SensorManager.class);
        accelerometer = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        available = accelerometer != null;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAvailable() { return available; }
    public boolean isRunning() { return running; }

    public void setEnabled(boolean value) {
        requireMainThread();
        enabled = value;
        preferences.edit().putBoolean(ENABLED_KEY, value).apply();
        if (!value) stop();
    }

    /** Idempotent because the host refreshes its eligibility regularly. */
    public void start() {
        requireMainThread();
        if (closed || requested || !enabled) return;
        requested = true;
        if (accelerometer == null) return;
        detector.reset();
        final long session = ++generation;
        final long startedAt = SystemClock.elapsedRealtimeNanos();
        registeredListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (!running || closed || !enabled || generation != session || event.sensor != accelerometer
                        || event.timestamp < startedAt) return;
                if (event.values.length < 3) { detector.disarm(); return; }
                long age = SystemClock.elapsedRealtimeNanos() - event.timestamp;
                if (age > 250_000_000L || age < -50_000_000L) { detector.disarm(); return; }
                if (detector.onAccelerometer(event.timestamp, event.values[0], event.values[1], event.values[2]))
                    listener.onShake();
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        };
        try { running = manager.registerListener(registeredListener, accelerometer, SAMPLE_PERIOD_US, main); }
        catch (RuntimeException unavailable) { running = false; }
        if (!running) {
            manager.unregisterListener(registeredListener);
            registeredListener = null;
        }
    }

    /** Discard queued callbacks and partial shakes when the foreground control is unavailable. */
    public void stop() {
        requireMainThread();
        requested = running = false;
        generation++;
        if (manager != null && registeredListener != null) manager.unregisterListener(registeredListener);
        registeredListener = null;
        detector.reset();
    }

    public void close() {
        requireMainThread();
        closed = true;
        stop();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Shake controls belong to the main thread.");
    }
}
