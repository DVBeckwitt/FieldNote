package com.fieldnote.app;

/** Pure gesture intent. Recording timing and media actions belong to the host. */
public final class RecordingGesture {
    public enum Direction { NONE, UP, LEFT, RIGHT, DOWN, MARKER, CLOSE }

    public static final int ACTIVATION_DISTANCE_DP = 112;
    public static final int HOME_RADIUS_DP = 28;
    static final float DEFAULT_CENTER_FRACTION = .35f;
    private static final float AXIS_DOMINANCE = 1.25f;
    private final float distancePx;
    private final float homeRadiusPx;
    private final boolean closeEnabled;
    private float startX;
    private float startY;
    private boolean pressed;
    private boolean centered;
    private boolean armed;
    private boolean actionOccurred;
    private Direction direction = Direction.NONE;

    public RecordingGesture(float distancePx) {
        this(distancePx, distancePx * DEFAULT_CENTER_FRACTION);
    }

    public RecordingGesture(float distancePx, float homeRadiusPx) {
        this(distancePx, homeRadiusPx, false);
    }

    /** The floating host can reserve the southeast sector for closing its control. */
    public RecordingGesture(float distancePx, float homeRadiusPx, boolean closeEnabled) {
        validateDistances(distancePx, homeRadiusPx);
        this.distancePx = distancePx;
        this.homeRadiusPx = homeRadiusPx;
        this.closeEnabled = closeEnabled;
    }

    static void validateDistances(float distancePx, float homeRadiusPx) {
        if (!(distancePx > 0) || Float.isInfinite(distancePx)) {
            throw new IllegalArgumentException("Gesture distance must be a positive finite value.");
        }
        if (!(homeRadiusPx > 0) || Float.isInfinite(homeRadiusPx) || homeRadiusPx >= distancePx) {
            throw new IllegalArgumentException("Home radius must be positive, finite, and smaller than the gesture distance.");
        }
    }

    /** Compatibility entry point for the original vertical-only recording gesture. */
    public void onDown(float y) {
        onDown(0, y);
    }

    /** Coordinates must be screen/raw coordinates so moving the control does not move the origin. */
    public void onDown(float x, float y) {
        onDownAt(x, y, x, y);
    }

    /**
     * Starts a press around a fixed control center. An off-center press may record,
     * but must visit home before it can select a directional action.
     */
    public void onDownAt(float originX, float originY, float pointerX, float pointerY) {
        startX = originX;
        startY = originY;
        pressed = true;
        centered = Math.hypot(pointerX - originX, pointerY - originY) <= homeRadiusPx;
        armed = centered;
        actionOccurred = false;
        direction = Direction.NONE;
    }

    /** Compatibility entry point: only upward movement can select an intent here. */
    public boolean onMove(float y) {
        if (!pressed) return false;
        if (Float.isNaN(y) || Float.isInfinite(y)) return false;
        centered = Math.abs(y - startY) <= homeRadiusPx;
        if (armed && direction == Direction.NONE && startY - y >= distancePx) {
            activate(Direction.UP);
            return true;
        }
        return false;
    }

    /**
     * Returns the current intent. LEFT and MARKER remain active until the finger
     * returns to the small radial home region, allowing another action in the hold.
     * RIGHT ends immediately when the finger leaves its activation region; another
     * action then requires a return home. UP, DOWN, and optional CLOSE stay latched until release.
     * The host observes direction transitions to start or stop its media actions.
     */
    public Direction update(float x, float y) {
        if (!pressed) return direction;
        float dx = x - startX;
        float dy = y - startY;
        if (Float.isNaN(dx) || Float.isNaN(dy) || Float.isInfinite(dx) || Float.isInfinite(dy)) return direction;
        centered = Math.hypot(dx, dy) <= homeRadiusPx;
        if (direction == Direction.UP || direction == Direction.DOWN || direction == Direction.CLOSE) return direction;
        if (direction == Direction.LEFT || direction == Direction.MARKER) {
            if (centered) {
                direction = Direction.NONE;
                armed = true;
            }
            return direction;
        }
        if (direction == Direction.RIGHT) {
            if (insideVideoRegion(dx, dy)) return direction;
            direction = Direction.NONE;
            armed = centered;
            return direction;
        }
        if (!armed) {
            if (centered) armed = true;
            return Direction.NONE;
        }
        Direction candidate = candidateDirection(dx, dy, closeEnabled);
        double distance = candidate == Direction.MARKER || candidate == Direction.CLOSE ? Math.hypot(dx, dy)
                : candidate == Direction.LEFT || candidate == Direction.RIGHT ? Math.abs(dx)
                : candidate == Direction.NONE ? 0 : Math.abs(dy);
        if (candidate != Direction.NONE && distance >= distancePx) activate(candidate);
        return direction;
    }

    private boolean insideVideoRegion(float dx, float dy) {
        return dx >= distancePx && candidateDirection(dx, dy, closeEnabled) == Direction.RIGHT;
    }

    /** Shared angular intent for activation and guidance; distance is applied by the caller. */
    static Direction candidateDirection(float dx, float dy) {
        return candidateDirection(dx, dy, false);
    }

    static Direction candidateDirection(float dx, float dy, boolean closeEnabled) {
        if (Float.isNaN(dx) || Float.isNaN(dy) || Float.isInfinite(dx) || Float.isInfinite(dy)) {
            return Direction.NONE;
        }
        float horizontal = Math.abs(dx), vertical = Math.abs(dy);
        // Reserve the whole northeast approach sector before testing the cardinal axes.
        // Otherwise a diagonal marker could accidentally lock or start video first.
        if (dx > 0 && dy < 0 && vertical >= horizontal * .5f && vertical <= horizontal * 2) {
            return Direction.MARKER;
        }
        if (closeEnabled && dx > 0 && dy > 0 && vertical >= horizontal * .5f && vertical <= horizontal * 2) {
            return Direction.CLOSE;
        }
        if (horizontal > 0 && horizontal >= vertical * AXIS_DOMINANCE) {
            return dx < 0 ? Direction.LEFT : Direction.RIGHT;
        }
        if (vertical > 0 && vertical >= horizontal * AXIS_DOMINANCE) {
            return dy < 0 ? Direction.UP : Direction.DOWN;
        }
        return Direction.NONE;
    }

    private void activate(Direction selected) {
        direction = selected;
        armed = false;
        actionOccurred = true;
    }

    public Direction getDirection() {
        return direction;
    }

    /** True only if this press never activated an action, even if it has returned home. */
    public boolean onUp() {
        boolean shouldStop = pressed && !actionOccurred;
        pressed = false;
        centered = false;
        armed = false;
        return shouldStop;
    }

    /** Whether the held finger is within the inclusive radial home region. */
    public boolean isCentered() { return pressed && centered; }

    /** Whether the current hold can activate a new directional action. */
    public boolean isArmed() { return pressed && armed; }

    public boolean isLocked() {
        return direction == Direction.UP;
    }

    public void reset() {
        pressed = false;
        centered = false;
        armed = false;
        actionOccurred = false;
        direction = Direction.NONE;
    }
}
