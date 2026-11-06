package com.fieldnote.app;

import java.util.ArrayList;
import java.util.List;

/** A bounded local demonstration: nanosecond timestamps, m/s^2 acceleration and rad/s rotation. */
public final class MotionSequence {
    static final int POINTS = 24, AXES = 6, MAX_SAMPLES = 256;
    final long[] times;
    final float[][] samples;

    private MotionSequence(List<Long> times, List<float[]> samples) {
        if (times.size() < 6 || times.size() > MAX_SAMPLES) throw new IllegalArgumentException("Use a clear short motion with at least six samples.");
        this.times = new long[times.size()];
        this.samples = new float[times.size()][AXES];
        for (int i = 0; i < times.size(); i++) {
            long timestamp = times.get(i);
            if (timestamp < 0 || (i > 0 && timestamp <= this.times[i - 1])) throw new IllegalArgumentException("Motion timestamps must increase.");
            this.times[i] = timestamp;
            for (int a = 0; a < AXES; a++) {
                float value = samples.get(i)[a];
                if (!Float.isFinite(value) || Math.abs(value) > (a < 3 ? 100 : 30)) throw new IllegalArgumentException("Motion samples are invalid or too abrupt.");
                this.samples[i][a] = value;
            }
        }
        if (durationMs() < 100 || durationMs() > 1800) throw new IllegalArgumentException("Make one quick motion lasting about a fraction of a second.");
    }

    public long durationMs() { return (times[times.length - 1] - times[0]) / 1_000_000L; }

    public static final class Builder {
        private final List<Long> times = new ArrayList<>();
        private final List<float[]> samples = new ArrayList<>();
        public Builder add(long timestampNs, float ax, float ay, float az, float gx, float gy, float gz) {
            if (times.size() >= MAX_SAMPLES) throw new IllegalArgumentException("The demonstration is too long.");
            times.add(timestampNs);
            samples.add(new float[]{ax, ay, az, gx, gy, gz});
            return this;
        }
        public MotionSequence build() { return new MotionSequence(times, samples); }
    }

    static final class Shape {
        final float[] values;
        final float durationMs, peak, rms;
        Shape(float[] values, float durationMs, float peak, float rms) {
            this.values = values.clone(); this.durationMs = durationMs; this.peak = peak; this.rms = rms;
        }
    }

    Shape shape() {
        float[] values = new float[POINTS * AXES];
        double sum = 0, peak = 0;
        int right = 1;
        for (int p = 0; p < POINTS; p++) {
            long at = times[0] + (times[times.length - 1] - times[0]) * p / (POINTS - 1);
            while (right < times.length - 1 && times[right] < at) right++;
            int left = right - 1;
            double fraction = (double) (at - times[left]) / (times[right] - times[left]);
            double energy = 0;
            for (int a = 0; a < AXES; a++) {
                double value = (samples[left][a] + fraction * (samples[right][a] - samples[left][a])) / (a < 3 ? 9.81 : 4.0);
                values[p * AXES + a] = (float) value;
                energy += value * value;
            }
            sum += energy;
            peak = Math.max(peak, Math.sqrt(energy));
        }
        double rms = Math.sqrt(sum / POINTS);
        if (peak < .28 || rms < .12) throw new IllegalArgumentException("Make the motion a little clearer; that example was too small.");
        for (int i = 0; i < values.length; i++) values[i] /= rms;
        double roughness = 0;
        for (int p = 1; p < POINTS; p++) {
            double change = 0;
            for (int a = 0; a < AXES; a++) {
                double delta = values[p * AXES + a] - values[(p - 1) * AXES + a];
                change += delta * delta;
            }
            roughness += Math.sqrt(change);
        }
        if (roughness > 20) throw new IllegalArgumentException("That example was too jittery. Make one smooth, distinct motion.");
        return new Shape(values, durationMs(), (float) peak, (float) rms);
    }
}
