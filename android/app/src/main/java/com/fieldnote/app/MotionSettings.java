package com.fieldnote.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

/** Immutable, small on-device motion profiles. Stored templates contain no audio, images or location. */
public final class MotionSettings {
    private static final int FORMAT = 1;
    private final EnumMap<MotionAction, Integer> sensitivities = new EnumMap<>(MotionAction.class);
    private final EnumMap<MotionAction, List<MotionSequence.Shape>> templates = new EnumMap<>(MotionAction.class);

    public MotionSettings() {
        for (MotionAction action : MotionAction.values()) {
            sensitivities.put(action, 50);
            templates.put(action, new ArrayList<>());
        }
    }

    private MotionSettings(MotionSettings source) {
        this();
        sensitivities.putAll(source.sensitivities);
        for (MotionAction action : MotionAction.values()) templates.put(action, new ArrayList<>(source.templates.get(action)));
    }

    public int getSensitivity(MotionAction action) { return sensitivities.get(action); }
    public boolean isTrained(MotionAction action) { return templates.get(action).size() == 3; }
    public MotionSettings withSensitivity(MotionAction action, int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Sensitivity must be between 0 and 100.");
        MotionSettings result = new MotionSettings(this);
        result.sensitivities.put(action, value);
        return result;
    }
    public MotionSettings resetTraining(MotionAction action) {
        MotionSettings result = new MotionSettings(this);
        result.templates.put(action, new ArrayList<>());
        return result;
    }
    MotionSettings withTraining(MotionAction action, List<MotionSequence.Shape> examples) {
        if (examples.size() != 3) throw new IllegalArgumentException("Three examples are required.");
        MotionSettings result = new MotionSettings(this);
        result.templates.put(action, new ArrayList<>(examples));
        return result;
    }

    /** One completed motion is judged once. Conflicting default/taught actions never both run. */
    public MotionAction recognize(MotionSequence sequence) {
        MotionSequence.Shape shape;
        try { shape = sequence.shape(); } catch (IllegalArgumentException noise) { return null; }
        MotionAction winner = null;
        double winningScore = Double.POSITIVE_INFINITY;
        EnumMap<MotionAction, Double> shapeScores = new EnumMap<>(MotionAction.class);
        for (MotionAction action : MotionAction.values()) {
            double score = isTrained(action) ? score(shape, action) : Double.POSITIVE_INFINITY;
            shapeScores.put(action, isTrained(action) ? shapeScore(shape, action) : Double.POSITIVE_INFINITY);
            if (score <= tolerance(action)) {
                if (winner != null) return null;
                winner = action;
                winningScore = score;
            }
        }
        if (winner == null) {
            // A near taught motion must not accidentally execute a different builtin.
            for (MotionAction action : MotionAction.values())
                if (shapeScores.get(action) <= tolerance(action) + .15) return null;
            EnumSet<MotionAction> defaults = defaultMatches(sequence);
            return defaults.size() == 1 ? defaults.iterator().next() : null;
        }
        for (MotionAction action : MotionAction.values())
            if (action != winner && shapeScores.get(action) <= tolerance(action) + .10
                    && shapeScores.get(action) - winningScore < .16) return null;
        return winner;
    }

    boolean conflicts(MotionSequence sequence, MotionSequence.Shape shape, MotionAction target) {
        for (MotionAction action : MotionAction.values()) {
            if (action == target) continue;
            if (isTrained(action)) {
                if (shapeScore(shape, action) <= Math.max(.50, tolerance(action)) + .10) return true;
            }
        }
        return false;
    }

    private double tolerance(MotionAction action) { return .29 + getSensitivity(action) * .0032; }

    private double shapeScore(MotionSequence.Shape candidate, MotionAction action) {
        List<MotionSequence.Shape> examples = templates.get(action);
        double[] scores = new double[examples.size()];
        for (int i = 0; i < examples.size(); i++) scores[i] = distance(candidate, examples.get(i));
        Arrays.sort(scores);
        return scores.length == 3 ? scores[1] : Double.POSITIVE_INFINITY;
    }

    private double score(MotionSequence.Shape candidate, MotionAction action) {
        List<MotionSequence.Shape> examples = templates.get(action);
        double[] scores = new double[examples.size()];
        int sensitivity = getSensitivity(action);
        for (int i = 0; i < examples.size(); i++) {
            MotionSequence.Shape reference = examples.get(i);
            boolean strength = candidate.peak >= Math.max(.22, reference.peak * (.72 - sensitivity * .004))
                    && candidate.rms >= Math.max(.10, reference.rms * (.65 - sensitivity * .0035))
                    && candidate.peak <= reference.peak * 3.2;
            boolean timing = candidate.durationMs >= reference.durationMs * .45
                    && candidate.durationMs <= reference.durationMs * 2.25;
            scores[i] = strength && timing ? distance(candidate, reference) : Double.POSITIVE_INFINITY;
        }
        Arrays.sort(scores);
        return scores.length == 3 ? scores[1] : Double.POSITIVE_INFINITY;
    }

    static double distance(MotionSequence.Shape left, MotionSequence.Shape right) {
        int n = MotionSequence.POINTS;
        double[][] cost = new double[n + 1][n + 1];
        int[][] steps = new int[n + 1][n + 1];
        for (double[] row : cost) Arrays.fill(row, Double.POSITIVE_INFINITY);
        cost[0][0] = 0;
        for (int i = 1; i <= n; i++) for (int j = Math.max(1, i - 6); j <= Math.min(n, i + 6); j++) {
            int pi = i - 1, pj = j - 1;
            if (cost[i - 1][j] < cost[pi][pj]) { pi = i - 1; pj = j; }
            if (cost[i][j - 1] < cost[pi][pj]) { pi = i; pj = j - 1; }
            double delta = 0;
            for (int a = 0; a < MotionSequence.AXES; a++) {
                double value = left.values[(i - 1) * MotionSequence.AXES + a] - right.values[(j - 1) * MotionSequence.AXES + a];
                delta += value * value;
            }
            cost[i][j] = cost[pi][pj] + Math.sqrt(delta);
            steps[i][j] = steps[pi][pj] + 1;
        }
        return cost[n][n] / steps[n][n];
    }

    private EnumSet<MotionAction> defaultMatches(MotionSequence sequence) {
        EnumSet<MotionAction> result = EnumSet.noneOf(MotionAction.class);
        MotionGestureDetector defaults = new MotionGestureDetector(true, true,
                getSensitivity(MotionAction.AUDIO), getSensitivity(MotionAction.PHOTO));
        long start = 1_000_000_000L;
        for (long t = start; t < start + 600_000_000L; t += 20_000_000L) {
            defaults.onGyroscope(t, 0, 0, 0); defaults.onLinearAcceleration(t, 0, 0, 0);
        }
        for (int i = 0; i < sequence.times.length; i++) {
            long t = start + 600_000_000L + sequence.times[i] - sequence.times[0];
            float[] sample = sequence.samples[i];
            addDefault(result, defaults.onGyroscope(t, sample[3], sample[4], sample[5]));
            addDefault(result, defaults.onLinearAcceleration(t, sample[0], sample[1], sample[2]));
        }
        if (isTrained(MotionAction.AUDIO)) result.remove(MotionAction.AUDIO);
        if (isTrained(MotionAction.PHOTO)) result.remove(MotionAction.PHOTO);
        return result;
    }

    private static void addDefault(EnumSet<MotionAction> result, MotionGestureDetector.Gesture gesture) {
        if (gesture == MotionGestureDetector.Gesture.FORWARD_SHAKE) result.add(MotionAction.AUDIO);
        if (gesture == MotionGestureDetector.Gesture.TWIST) result.add(MotionAction.PHOTO);
    }

    public String encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(FORMAT);
            for (MotionAction action : MotionAction.values()) {
                output.writeInt(getSensitivity(action));
                List<MotionSequence.Shape> models = templates.get(action);
                output.writeInt(models.size());
                for (MotionSequence.Shape shape : models) {
                    output.writeFloat(shape.durationMs); output.writeFloat(shape.peak); output.writeFloat(shape.rms);
                    for (float value : shape.values) output.writeFloat(value);
                }
            }
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static MotionSettings decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new MotionSettings();
        if (encoded.length() > 12000) throw new IllegalArgumentException("Motion settings are too large.");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            if (input.readInt() != FORMAT) throw new IOException("Unsupported motion settings version.");
            MotionSettings result = new MotionSettings();
            for (MotionAction action : MotionAction.values()) {
                result = result.withSensitivity(action, input.readInt());
                int count = input.readInt();
                if (count != 0 && count != 3) throw new IOException("Incomplete motion model.");
                List<MotionSequence.Shape> models = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    float duration = input.readFloat(), peak = input.readFloat(), rms = input.readFloat();
                    if (!Float.isFinite(duration) || duration < 100 || duration > 1800
                            || !Float.isFinite(peak) || peak < .28 || peak > 30
                            || !Float.isFinite(rms) || rms < .12 || rms > peak) throw new IOException("Invalid motion model.");
                    float[] values = new float[MotionSequence.POINTS * MotionSequence.AXES];
                    for (int j = 0; j < values.length; j++) {
                        values[j] = input.readFloat();
                        if (!Float.isFinite(values[j]) || Math.abs(values[j]) > 12) throw new IOException("Invalid motion shape.");
                    }
                    double energy = 0, normalizedPeak = 0;
                    for (int point = 0; point < MotionSequence.POINTS; point++) {
                        double magnitudeSquared = 0;
                        for (int axis = 0; axis < MotionSequence.AXES; axis++) {
                            double value = values[point * MotionSequence.AXES + axis];
                            magnitudeSquared += value * value;
                        }
                        energy += magnitudeSquared;
                        normalizedPeak = Math.max(normalizedPeak, Math.sqrt(magnitudeSquared));
                    }
                    // shape() derives these together. Finite but impossible templates
                    // would otherwise disable a builtin while remaining unrecognizable.
                    if (Math.abs(energy / MotionSequence.POINTS - 1) > .005
                            || Math.abs(normalizedPeak * rms - peak) > Math.max(.0001, peak * .005))
                        throw new IOException("Inconsistent normalized motion shape.");
                    models.add(new MotionSequence.Shape(values, duration, peak, rms));
                }
                result.templates.put(action, models);
            }
            if (input.available() != 0) throw new IOException("Unexpected motion settings data.");
            return result;
        } catch (IOException | RuntimeException failure) { throw new IllegalArgumentException("Saved motion settings are invalid.", failure); }
    }

    /** Missing preferences mean defaults; malformed existing values require explicit recovery. */
    static MotionSettings decodePreference(Object stored) {
        if (stored == null) return new MotionSettings();
        if (!(stored instanceof String) || ((String) stored).isEmpty())
            throw new IllegalArgumentException("Saved motion profiles have an invalid preference type or value.");
        return decode((String) stored);
    }
}
