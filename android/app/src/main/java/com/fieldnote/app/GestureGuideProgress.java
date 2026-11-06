package com.fieldnote.app;

/** Visual guidance for an uncommitted gesture; activation belongs to RecordingGesture. */
public final class GestureGuideProgress {
    public enum Haptic { NONE, APPROACH, NEAR, RETREAT }

    public static final class State {
        public final RecordingGesture.Direction direction;
        public final float progress;
        public final int level;
        public final Haptic haptic;

        private State(RecordingGesture.Direction direction, float progress, int level, Haptic haptic) {
            this.direction = direction;
            this.progress = progress;
            this.level = level;
            this.haptic = haptic;
        }
    }

    private static final long HAPTIC_INTERVAL_MS = 80;
    private final float thresholdPx;
    private RecordingGesture.Direction previousDirection = RecordingGesture.Direction.NONE;
    private int previousLevel;
    private boolean emitted;
    private long lastHapticMs;

    public GestureGuideProgress(float thresholdPx) {
        if (!(thresholdPx > 0) || Float.isInfinite(thresholdPx)) {
            throw new IllegalArgumentException("Guide threshold must be a positive finite value.");
        }
        this.thresholdPx = thresholdPx;
    }

    /** Begin a new press, including a fresh haptic interval. */
    public void reset() {
        previousDirection = RecordingGesture.Direction.NONE;
        previousLevel = 0;
        emitted = false;
        lastHapticMs = 0;
    }

    /**
     * Deltas are measured from the press origin; nowMs should use a monotonic clock.
     * Haptics occur only on transitions. Suppressed transitions are not queued for
     * later stationary frames. The host supplies activation and its own lock cue.
     */
    public State update(float dx, float dy, RecordingGesture.Direction activated, long nowMs) {
        if (activated != RecordingGesture.Direction.NONE) {
            previousDirection = activated;
            previousLevel = 3;
            return new State(activated, 1, 3, Haptic.NONE);
        }

        RecordingGesture.Direction direction = RecordingGesture.candidateDirection(dx, dy);
        float distance = direction == RecordingGesture.Direction.MARKER ? (float) Math.hypot(dx, dy)
                : direction == RecordingGesture.Direction.LEFT || direction == RecordingGesture.Direction.RIGHT
                ? Math.abs(dx) : direction == RecordingGesture.Direction.NONE ? 0 : Math.abs(dy);
        float progress = Math.min(1, distance / thresholdPx);
        int level = progress >= .8f ? 3 : progress >= .5f ? 2 : progress >= .25f ? 1 : 0;
        Haptic cue = Haptic.NONE;
        if ((previousLevel > 0 && direction != previousDirection)
                || level < previousLevel) {
            cue = Haptic.RETREAT;
        } else if (level > previousLevel) {
            cue = level == 3 ? Haptic.NEAR : Haptic.APPROACH;
        }
        previousDirection = direction;
        previousLevel = level;
        if (cue != Haptic.NONE) {
            if (emitted && nowMs - lastHapticMs < HAPTIC_INTERVAL_MS) cue = Haptic.NONE;
            else {
                emitted = true;
                lastHapticMs = nowMs;
            }
        }
        return new State(direction, progress, level, cue);
    }

}
