package com.fieldnote.app;

import java.util.ArrayList;
import java.util.List;

/** Finite whole-motion windows shared by teaching and recognition; no capture side effects. */
final class MotionSegmenter {
    private static final long GAP = 250_000_000L, QUIET = 350_000_000L, END_QUIET = 180_000_000L;
    private long previous = -1, quietSince = -1, lastQuiet = -1, cooldownUntil, started = -1, endQuietSince = -1;
    private boolean armed;
    private final List<Long> times = new ArrayList<>();
    private final List<float[]> samples = new ArrayList<>();

    void reset() { previous = quietSince = lastQuiet = started = endQuietSince = -1; cooldownUntil = 0; armed = false; times.clear(); samples.clear(); }

    MotionSequence add(long timestamp, float ax, float ay, float az, float gx, float gy, float gz) {
        if (timestamp < 0 || (previous >= 0 && (timestamp <= previous || timestamp - previous > GAP))) {
            reset(); previous = timestamp; return null;
        }
        previous = timestamp;
        float[] values = {ax, ay, az, gx, gy, gz};
        double energy = 0;
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) { reset(); return null; }
            double scaled = values[i] / (i < 3 ? 9.81 : 4.0);
            energy += scaled * scaled;
        }
        energy = Math.sqrt(energy);
        if (started < 0) {
            if (energy < .12) {
                lastQuiet = timestamp;
                if (quietSince < 0) quietSince = timestamp;
                if (timestamp - quietSince >= QUIET && timestamp >= cooldownUntil) armed = true;
            } else {
                quietSince = -1;
                if (armed && lastQuiet >= 0 && timestamp - lastQuiet <= 180_000_000L && energy >= .20) {
                    armed = false;
                    started = timestamp;
                    times.add(timestamp); samples.add(values);
                } else if (lastQuiet < 0 || timestamp - lastQuiet > 180_000_000L) armed = false;
            }
            return null;
        }
        if (timestamp - started > 1_800_000_000L || samples.size() >= MotionSequence.MAX_SAMPLES) {
            discard(timestamp); return null;
        }
        times.add(timestamp); samples.add(values);
        if (energy < .12) {
            if (endQuietSince < 0) endQuietSince = timestamp;
            if (timestamp - endQuietSince >= END_QUIET) {
                MotionSequence.Builder builder = new MotionSequence.Builder();
                // Retain a short braking/settling tail, not the user's wait between demonstrations.
                for (int i = 0; i < times.size(); i++) {
                    if (times.get(i) > endQuietSince + 60_000_000L) break;
                    float[] v = samples.get(i);
                    builder.add(times.get(i), v[0], v[1], v[2], v[3], v[4], v[5]);
                }
                discard(timestamp);
                try { return builder.build(); } catch (IllegalArgumentException noisy) { return null; }
            }
        } else endQuietSince = -1;
        return null;
    }

    private void discard(long timestamp) {
        started = endQuietSince = quietSince = -1;
        armed = false;
        times.clear(); samples.clear();
        cooldownUntil = timestamp + 1_200_000_000L;
    }
}
