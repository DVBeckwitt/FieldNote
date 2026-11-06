package com.fieldnote.app;

/** A deliberate shake is two acceleration peaks, followed by a quiet rearm period. */
public final class ShakeDetector {
    private static final double GRAVITY = 9.80665;
    private static final double PEAK_G = 2.2;
    private static final double QUIET_MIN_G = .7;
    private static final double QUIET_MAX_G = 1.3;
    private static final long QUIET_NS = 400_000_000L;
    private static final long PEAK_HOLD_NS = 20_000_000L;
    private static final long PEAK_SPACING_NS = 80_000_000L;
    private static final long BURST_NS = 700_000_000L;
    private static final long COOLDOWN_NS = 1_200_000_000L;
    private static final long SAMPLE_GAP_NS = 250_000_000L;

    private long lastTimestamp = -1, quietSince = -1, peakSince = -1, firstPeakAt = -1;
    private long cooldownUntil;
    private boolean armed, peakCounted;

    /** Raw accelerometer values include gravity and use m/s²; timestamps are monotonic nanoseconds. */
    public boolean onAccelerometer(long timestamp, float x, float y, float z) {
        if (timestamp < 0 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || (lastTimestamp >= 0 && timestamp <= lastTimestamp)) {
            disarm();
            return false;
        }
        if (lastTimestamp >= 0 && timestamp - lastTimestamp > SAMPLE_GAP_NS) disarm();
        lastTimestamp = timestamp;
        // The norm has no assigned direction and needs no gyroscope or gravity calibration.
        double g = Math.sqrt((double) x * x + (double) y * y + (double) z * z) / GRAVITY;
        boolean quiet = g >= QUIET_MIN_G && g <= QUIET_MAX_G;
        if (quiet) {
            peakSince = -1;
            peakCounted = false;
            if (quietSince < 0) quietSince = timestamp;
            if (timestamp >= cooldownUntil && timestamp - quietSince >= QUIET_NS) {
                armed = true;
                firstPeakAt = -1;
            }
            return false;
        }
        quietSince = -1;
        if (!armed || timestamp < cooldownUntil) return false;
        if (firstPeakAt >= 0 && timestamp - firstPeakAt > BURST_NS) {
            disarm();
            return false;
        }
        if (g < PEAK_G) {
            peakSince = -1;
            return false;
        }
        if (peakSince < 0) peakSince = timestamp;
        if (peakCounted || timestamp - peakSince < PEAK_HOLD_NS) return false;
        peakCounted = true;
        if (firstPeakAt < 0) {
            firstPeakAt = timestamp;
            return false;
        }
        if (timestamp - firstPeakAt < PEAK_SPACING_NS) return false;
        cooldownUntil = timestamp + COOLDOWN_NS;
        disarm();
        return true;
    }

    /** A new sensor session must observe quiet samples before accepting a shake. */
    public void reset() {
        lastTimestamp = -1;
        cooldownUntil = 0;
        disarm();
    }

    /** Invalid/stale input abandons a partial shake without shortening its cooldown. */
    void disarm() {
        armed = peakCounted = false;
        quietSince = peakSince = firstPeakAt = -1;
    }
}
