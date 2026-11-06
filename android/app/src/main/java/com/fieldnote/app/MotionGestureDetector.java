package com.fieldnote.app;

/**
 * Conservative, device-relative motion gestures. Inputs use SensorEvent timestamps
 * (nanoseconds), linear acceleration in m/s^2, and angular velocity in rad/s.
 * Android +Z points out of the screen: a push away begins with negative Z.
 * Thresholds describe deliberate gestures, not validated human-motion classification.
 */
public final class MotionGestureDetector {
    public enum Gesture { NONE, FORWARD_SHAKE, TWIST }

    private static final long FRESH_NS = 150_000_000L;
    private static final long GAP_NS = 250_000_000L;
    private static final long QUIET_NS = 350_000_000L;
    private static final long COOLDOWN_NS = 1_200_000_000L;
    private static final long GESTURE_NS = 550_000_000L;
    private final boolean accelerationAvailable, gyroscopeAvailable;
    private final double shakeScale, twistScale;
    private float linearX, linearY, linearZ;
    private long accelerationAt = -1, gyroscopeAt = -1, quietSince = -1, lastQuiet = -1, cooldownUntil;
    private double accelerationMagnitude, rotationMagnitude, previousZ, previousRotationZ;
    private boolean armed;
    private int shakePhase;
    private long shakeStarted, lastForwardAt;
    private double forwardImpulse, brakeImpulse, forwardPeak, brakePeak;
    private long twistStarted = -1;
    private double twistSign, twistAngle, twistPeak;
    private long gravityAt = -1, gravityStarted;
    private double gravityX, gravityY, gravityZ;

    public MotionGestureDetector(boolean accelerationAvailable, boolean gyroscopeAvailable) {
        this(accelerationAvailable, gyroscopeAvailable, 50, 50);
    }

    public MotionGestureDetector(boolean accelerationAvailable, boolean gyroscopeAvailable,
                                 int shakeSensitivity, int twistSensitivity) {
        this.accelerationAvailable = accelerationAvailable;
        this.gyroscopeAvailable = gyroscopeAvailable;
        shakeScale = sensitivityScale(shakeSensitivity);
        twistScale = sensitivityScale(twistSensitivity);
    }

    private static double sensitivityScale(int sensitivity) {
        if (sensitivity < 0 || sensitivity > 100) throw new IllegalArgumentException("Sensitivity must be between 0 and 100.");
        return sensitivity <= 50 ? 1 + (50 - sensitivity) * .008 : 1 - (sensitivity - 50) * .006;
    }

    long linearSampleAt() { return accelerationAt; }
    float linearX() { return linearX; }
    float linearY() { return linearY; }
    float linearZ() { return linearZ; }

    public void reset() {
        accelerationAt = gyroscopeAt = quietSince = lastQuiet = gravityAt = -1;
        accelerationMagnitude = rotationMagnitude = previousZ = previousRotationZ = 0;
        cooldownUntil = 0;
        armed = false;
        clearCandidates();
    }

    /** Raw-accelerometer fallback: time-based low-pass gravity estimate, with startup warmup. */
    public Gesture onAccelerometer(long timestampNs, float x, float y, float z) {
        if (!valid(timestampNs, x, y, z)) { disarm(); return Gesture.NONE; }
        if (gravityAt < 0 || timestampNs - gravityAt > GAP_NS) {
            gravityX = x; gravityY = y; gravityZ = z;
            gravityAt = gravityStarted = timestampNs;
            disarm();
            return Gesture.NONE;
        }
        if (timestampNs <= gravityAt) { disarm(); return Gesture.NONE; }
        double dt = (timestampNs - gravityAt) * 1e-9;
        gravityAt = timestampNs;
        double alpha = .35 / (.35 + dt);
        gravityX = alpha * gravityX + (1 - alpha) * x;
        gravityY = alpha * gravityY + (1 - alpha) * y;
        gravityZ = alpha * gravityZ + (1 - alpha) * z;
        if (timestampNs - gravityStarted < 800_000_000L) return Gesture.NONE;
        return onLinearAcceleration(timestampNs, (float) (x - gravityX),
                (float) (y - gravityY), (float) (z - gravityZ));
    }

    public Gesture onLinearAcceleration(long timestampNs, float x, float y, float z) {
        if (!accelerationAvailable || !valid(timestampNs, x, y, z)) { disarm(); return Gesture.NONE; }
        if (accelerationAt >= 0 && timestampNs <= accelerationAt) { disarm(); return Gesture.NONE; }
        double dt = accelerationAt < 0 ? 0 : (timestampNs - accelerationAt) * 1e-9;
        if (accelerationAt >= 0 && timestampNs - accelerationAt > GAP_NS) { disarm(); dt = 0; }
        accelerationAt = timestampNs;
        linearX = x; linearY = y; linearZ = z;
        double priorZ = previousZ;
        previousZ = z;
        accelerationMagnitude = magnitude(x, y, z);
        long now = Math.max(accelerationAt, gyroscopeAt);
        updateQuiet(now);

        if (twistStarted >= 0) {
            if (accelerationMagnitude > 5.5) disarm();
            return Gesture.NONE;
        }
        if (!rotationSafe(timestampNs) || timestampNs < cooldownUntil) {
            if (shakePhase != 0) disarm();
            return Gesture.NONE;
        }
        double sideways = Math.hypot(x, y);
        boolean axial = sideways <= Math.max(2.5, Math.abs(z) * .55);
        if (shakePhase == 0) {
            if (armed && axial && z <= -6.5 * shakeScale) {
                armed = false;
                shakePhase = 1;
                shakeStarted = lastForwardAt = timestampNs;
                forwardImpulse = brakeImpulse = brakePeak = 0;
                forwardPeak = -z;
            } else if (armed && (z > 3.5 || sideways > 4 || staleQuiet(now))) disarm();
            return Gesture.NONE;
        }
        if (!axial || timestampNs - shakeStarted > GESTURE_NS) { disarm(); return Gesture.NONE; }
        if (shakePhase == 1) {
            forwardImpulse += (Math.max(0, -priorZ) + Math.max(0, -z)) * .5 * dt;
            forwardPeak = Math.max(forwardPeak, -z);
            if (z < -2) lastForwardAt = timestampNs;
            if (z >= 3.8 * shakeScale) {
                if (forwardPeak < 7.5 * shakeScale || forwardImpulse < .45 * shakeScale || lastForwardAt - shakeStarted < 40_000_000L) {
                    disarm();
                    return Gesture.NONE;
                }
                shakePhase = 2;
                brakeImpulse = z * .5 * dt;
                brakePeak = z;
            }
        } else {
            if (z < -3) { disarm(); return Gesture.NONE; }
            brakeImpulse += (Math.max(0, priorZ) + Math.max(0, z)) * .5 * dt;
            brakePeak = Math.max(brakePeak, z);
        }
        if (shakePhase == 2 && brakePeak >= 4.5 * shakeScale && brakeImpulse >= .25 * shakeScale
                && timestampNs - shakeStarted >= 100_000_000L)
            return emit(Gesture.FORWARD_SHAKE, now);
        return Gesture.NONE;
    }

    public Gesture onGyroscope(long timestampNs, float x, float y, float z) {
        if (!gyroscopeAvailable || !valid(timestampNs, x, y, z)) { disarm(); return Gesture.NONE; }
        if (gyroscopeAt >= 0 && timestampNs <= gyroscopeAt) { disarm(); return Gesture.NONE; }
        double dt = gyroscopeAt < 0 ? 0 : (timestampNs - gyroscopeAt) * 1e-9;
        if (gyroscopeAt >= 0 && timestampNs - gyroscopeAt > GAP_NS) { disarm(); dt = 0; }
        gyroscopeAt = timestampNs;
        double priorZ = previousRotationZ;
        previousRotationZ = z;
        rotationMagnitude = magnitude(x, y, z);
        long now = Math.max(accelerationAt, gyroscopeAt);
        updateQuiet(now);

        if (shakePhase != 0) {
            if (rotationMagnitude > 1.3) disarm();
            return Gesture.NONE;
        }
        if (timestampNs < cooldownUntil || !translationSafe(timestampNs)) {
            if (twistStarted >= 0) disarm();
            return Gesture.NONE;
        }
        double sideways = Math.hypot(x, y);
        boolean axial = sideways <= Math.max(.6, Math.abs(z) * .4) && sideways < 1.6;
        if (twistStarted < 0) {
            if (armed && axial && Math.abs(z) >= 2.4 * twistScale) {
                armed = false;
                twistStarted = timestampNs;
                twistSign = Math.signum(z);
                twistAngle = 0;
                twistPeak = Math.abs(z);
            } else if (armed && (sideways > 1.3 || staleQuiet(now))) disarm();
            return Gesture.NONE;
        }
        if (!axial || timestampNs - twistStarted > GESTURE_NS) { disarm(); return Gesture.NONE; }
        twistAngle += twistSign * (priorZ + z) * .5 * dt;
        twistPeak = Math.max(twistPeak, Math.abs(z));
        if (twistAngle < -.05 || twistAngle > 1.4) { disarm(); return Gesture.NONE; }
        if (twistSign * z < .8) {
            if (twistAngle >= .55 * twistScale && twistPeak >= 3 * twistScale && timestampNs - twistStarted >= 100_000_000L)
                return emit(Gesture.TWIST, now);
            disarm();
        }
        return Gesture.NONE;
    }

    private void updateQuiet(long now) {
        boolean quiet = (!accelerationAvailable || (fresh(accelerationAt, now) && accelerationMagnitude < 1.6))
                && (!gyroscopeAvailable || (fresh(gyroscopeAt, now) && rotationMagnitude < .45));
        if (quiet) {
            lastQuiet = now;
            if (quietSince < 0) quietSince = now;
            if (shakePhase == 0 && twistStarted < 0 && now >= cooldownUntil && now - quietSince >= QUIET_NS)
                armed = true;
        } else quietSince = -1;
    }

    private boolean rotationSafe(long timestamp) {
        return !gyroscopeAvailable || (fresh(gyroscopeAt, timestamp) && rotationMagnitude <= 1.3);
    }

    private boolean translationSafe(long timestamp) {
        return !accelerationAvailable || (fresh(accelerationAt, timestamp) && accelerationMagnitude <= 5.5);
    }

    private boolean staleQuiet(long now) { return lastQuiet < 0 || now - lastQuiet > 180_000_000L; }
    private static boolean fresh(long sample, long now) { return sample >= 0 && Math.abs(now - sample) <= FRESH_NS; }
    private static double magnitude(float x, float y, float z) { return Math.sqrt((double) x * x + (double) y * y + (double) z * z); }
    private static boolean valid(long time, float x, float y, float z) {
        return time >= 0 && Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }

    private Gesture emit(Gesture gesture, long now) {
        disarm();
        cooldownUntil = now + COOLDOWN_NS;
        return gesture;
    }

    private void disarm() { armed = false; quietSince = -1; clearCandidates(); }
    private void clearCandidates() { shakePhase = 0; twistStarted = -1; }
}
