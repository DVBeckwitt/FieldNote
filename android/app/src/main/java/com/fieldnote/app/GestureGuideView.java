package com.fieldnote.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Touch-through directional targets, anchored to the microphone's fixed layout center. */
public final class GestureGuideView extends View {
    public static final int TARGET_RADIUS_DP = 18;
    /** Maximum target overhang beyond its activation point, including the outline. */
    public static final int TARGET_OUTSET_DP = TARGET_RADIUS_DP + 2;
    private static final float DIAGONAL_UNIT = (float) Math.sqrt(.5);
    private final float threshold;
    private final float homeRadius;
    private final GestureGuideProgress feedback;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path path = new Path();
    private GestureGuideProgress.State state;
    private RecordingGesture.Direction activated = RecordingGesture.Direction.NONE;
    private boolean active;
    private boolean awayFromHome;
    private long returnedAt;
    private long activatedAt;
    private ValueAnimator visibilityAnimator;
    private float originX, originY, dx, dy;
    private int accent = Color.rgb(145, 197, 255);
    private int background = Color.rgb(12, 22, 39);
    private int ink = Color.rgb(232, 241, 255);

    public GestureGuideView(Context context, float thresholdPx) {
        this(context, thresholdPx, thresholdPx * RecordingGesture.DEFAULT_CENTER_FRACTION);
    }

    public GestureGuideView(Context context, float thresholdPx, float homeRadiusPx) {
        super(context);
        RecordingGesture.validateDistances(thresholdPx, homeRadiusPx);
        feedback = new GestureGuideProgress(thresholdPx);
        threshold = thresholdPx;
        homeRadius = homeRadiusPx;
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }

    public void configureColors(int accent, int background, int ink) {
        this.accent = accent;
        this.background = background;
        this.ink = ink;
        invalidate();
    }

    public void begin(float originX, float originY) {
        float previousAlpha = getVisibility() == VISIBLE ? getAlpha() : 0;
        cancelVisibilityAnimation();
        this.originX = originX;
        this.originY = originY;
        dx = 0;
        dy = 0;
        activated = RecordingGesture.Direction.NONE;
        awayFromHome = false;
        returnedAt = 0;
        activatedAt = 0;
        feedback.reset();
        state = feedback.update(0, 0, activated, SystemClock.uptimeMillis());
        active = true;
        setVisibility(VISIBLE);
        setAlpha(animationsEnabled() ? previousAlpha : 1);
        animateVisibility(1, 150);
        invalidate();
    }

    public void update(float dx, float dy, RecordingGesture.Direction activated) {
        update(dx, dy, activated, true);
    }

    public void update(float dx, float dy, RecordingGesture.Direction activated, boolean armed) {
        if (!active) return;
        this.dx = dx;
        this.dy = dy;
        long now = SystemClock.uptimeMillis();
        RecordingGesture.Direction next = activated == null ? RecordingGesture.Direction.NONE : activated;
        if (next != this.activated) activatedAt = next == RecordingGesture.Direction.NONE ? 0 : now;
        this.activated = next;
        boolean showApproach = armed || this.activated != RecordingGesture.Direction.NONE;
        state = feedback.update(showApproach ? dx : 0, showApproach ? dy : 0, this.activated, now);
        float distance = (float) Math.hypot(dx, dy);
        boolean centered = distance <= homeRadius();
        boolean returned = centered && awayFromHome && this.activated == RecordingGesture.Direction.NONE;
        if (distance > homeRadius() + dp(3)) awayFromHome = true;
        if (centered) awayFromHome = false;
        if (returned) {
            returnedAt = now;
            if (Build.VERSION.SDK_INT >= 30) performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            else performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else {
            switch (state.haptic) {
                case APPROACH: performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); break;
                case NEAR: performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); break;
                case RETREAT: performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); break;
                default: break;
            }
        }
        invalidate();
    }

    public void finish() {
        if (!active) return;
        active = false;
        awayFromHome = false;
        returnedAt = 0;
        activatedAt = 0;
        feedback.reset();
        cancelVisibilityAnimation();
        // Interaction ends now; only the last visual frame remains during this short fade.
        animateVisibility(0, 110);
    }

    private void cancelVisibilityAnimation() {
        ValueAnimator previous = visibilityAnimator;
        visibilityAnimator = null;
        if (previous != null) previous.cancel();
    }

    private void animateVisibility(float alpha, long durationMs) {
        if (!animationsEnabled() || getAlpha() == alpha) {
            setAlpha(alpha);
            if (!active) hideGuide();
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(getAlpha(), alpha);
        visibilityAnimator = animation;
        animation.setDuration(durationMs);
        animation.addUpdateListener(value -> {
            if (visibilityAnimator == animation) setAlpha((Float) value.getAnimatedValue());
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (visibilityAnimator != animation) return;
                visibilityAnimator = null;
                if (!active) hideGuide();
            }
        });
        animation.start();
    }

    private void hideGuide() {
        activated = RecordingGesture.Direction.NONE;
        state = null;
        setVisibility(GONE);
        setAlpha(1);
    }

    @Override protected void onDetachedFromWindow() {
        cancelVisibilityAnimation();
        active = false;
        awayFromHome = false;
        returnedAt = activatedAt = 0;
        feedback.reset();
        hideGuide();
        super.onDetachedFromWindow();
    }

    public boolean isActive() { return active; }
    public float getApproachProgress() { return !active || state == null ? 0 : state.progress; }
    public RecordingGesture.Direction getApproachDirection() {
        return !active || state == null ? RecordingGesture.Direction.NONE : state.direction;
    }

    @Override public boolean onTouchEvent(MotionEvent event) { return false; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active && visibilityAnimator == null) return;
        drawDirection(canvas, RecordingGesture.Direction.LEFT, -1, 0);
        drawDirection(canvas, RecordingGesture.Direction.RIGHT, 1, 0);
        drawDirection(canvas, RecordingGesture.Direction.UP, 0, -1);
        drawDirection(canvas, RecordingGesture.Direction.DOWN, 0, 1);
        drawDirection(canvas, RecordingGesture.Direction.MARKER, DIAGONAL_UNIT, -DIAGONAL_UNIT);
        drawHome(canvas);
    }

    private void drawDirection(Canvas canvas, RecordingGesture.Direction direction, float vx, float vy) {
        // A previous home/pulse color may be translucent; shaders inherit Paint alpha.
        paint.setAlpha(255);
        boolean selected = state != null && state.direction == direction;
        boolean fired = activated == direction;
        float progress = selected ? state.progress : 0;
        float home = homeRadius(), targetX = originX + vx * threshold, targetY = originY + vy * threshold;
        float perpendicularX = -vy, perpendicularY = vx;
        float startX = originX + vx * (home + dp(3));
        float startY = originY + vy * (home + dp(3));
        float endX = originX + vx * (threshold - dp(4));
        float endY = originY + vy * (threshold - dp(4));
        float narrow = dp(2.5f), wide = dp(13);
        path.reset();
        path.moveTo(startX - perpendicularX * narrow, startY - perpendicularY * narrow);
        path.lineTo(endX - perpendicularX * wide, endY - perpendicularY * wide);
        path.quadTo(endX + vx * dp(10), endY + vy * dp(10), endX + perpendicularX * wide, endY + perpendicularY * wide);
        path.lineTo(startX + perpendicularX * narrow, startY + perpendicularY * narrow);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(startX, startY, endX, endY,
                new int[]{alpha(accent, 0), alpha(accent, selected ? 38 : 12), alpha(accent, fired ? 130 : selected ? 92 : 42)},
                new float[]{0, .58f, 1}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);

        float radius = dp(TARGET_RADIUS_DP);
        float pulse = fired ? pulseSince(activatedAt, 320) : 0;
        paint.setShader(new RadialGradient(targetX, targetY, dp(TARGET_OUTSET_DP),
                new int[]{alpha(accent, fired ? 72 : selected ? 38 : 16), alpha(accent, 0)},
                new float[]{0, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(targetX, targetY, dp(TARGET_OUTSET_DP), paint);
        paint.setShader(new LinearGradient(targetX - radius, targetY - radius, targetX + radius, targetY + radius,
                alpha(background, fired ? 160 : 180), alpha(background, 105), Shader.TileMode.CLAMP));
        canvas.drawCircle(targetX, targetY, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(fired ? 2 : 1.15f));
        paint.setShader(new LinearGradient(targetX - radius, targetY - radius, targetX + radius, targetY + radius,
                alpha(accent, selected ? 255 : 210), alpha(accent, selected ? 180 : 90), Shader.TileMode.CLAMP));
        canvas.drawCircle(targetX, targetY, radius, paint);
        paint.setShader(null);
        paint.setStrokeWidth(dp(.65f));
        paint.setColor(alpha(ink, fired ? 100 : 36));
        canvas.drawCircle(targetX, targetY, radius - dp(2.5f), paint);
        if (progress > 0 && !fired) {
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(accent);
            bounds.set(targetX - radius, targetY - radius, targetX + radius, targetY + radius);
            canvas.drawArc(bounds, -90, 360 * progress, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
        if (pulse > 0) {
            paint.setStrokeWidth(dp(1.4f));
            paint.setColor(alpha(ink, Math.round(190 * pulse)));
            canvas.drawCircle(targetX, targetY, radius * (1 - .25f * pulse), paint);
            postInvalidateOnAnimation();
        }
        drawIcon(canvas, direction, targetX, targetY, fired ? ink : accent);
    }

    private void drawHome(Canvas canvas) {
        paint.setAlpha(255);
        float radius = homeRadius();
        float reveal = Math.min(1, (float) Math.hypot(dx, dy) / (radius * 1.7f));
        reveal = reveal * reveal * (3 - 2 * reveal);
        float pulse = pulseSince(returnedAt, 420);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(originX, originY, radius + dp(15),
                new int[]{alpha(accent, Math.round(15 * reveal)), alpha(accent, Math.round(48 * pulse)), alpha(accent, 0)},
                new float[]{0, .65f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(originX, originY, radius + dp(15), paint);
        paint.setShader(null);
        // Leave the centered microphone artwork unobscured; reveal home as the mic moves away.
        paint.setColor(alpha(background, Math.round(100 * reveal)));
        canvas.drawCircle(originX, originY, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1 + .25f * reveal));
        paint.setColor(alpha(accent, Math.round(32 + 153 * reveal)));
        canvas.drawCircle(originX, originY, radius, paint);
        paint.setStrokeWidth(dp(1));
        paint.setColor(alpha(accent, Math.round(65 * reveal)));
        canvas.drawCircle(originX, originY, radius - dp(4), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(accent, Math.round(255 * reveal)));
        canvas.drawCircle(originX, originY, dp(2), paint);
        if (pulse > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(alpha(accent, Math.round(160 * pulse)));
            canvas.drawCircle(originX, originY, radius + dp(11) * (1 - pulse), paint);
            postInvalidateOnAnimation();
        }
    }

    private void drawIcon(Canvas canvas, RecordingGesture.Direction direction, float x, float y, int color) {
        int saved = canvas.save();
        canvas.translate(x, y);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.9f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        switch (direction) {
            case LEFT:
                path.reset();
                path.moveTo(-dp(10), -dp(6));
                path.lineTo(-dp(5), -dp(6));
                path.lineTo(-dp(3), -dp(10));
                path.lineTo(dp(3), -dp(10));
                path.lineTo(dp(5), -dp(6));
                path.lineTo(dp(10), -dp(6));
                path.lineTo(dp(10), dp(8));
                path.lineTo(-dp(10), dp(8));
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawCircle(0, dp(1), dp(4), paint);
                break;
            case RIGHT:
                bounds.set(-dp(10), -dp(7), dp(3), dp(7));
                canvas.drawRoundRect(bounds, dp(2), dp(2), paint);
                path.reset();
                path.moveTo(dp(3), -dp(3));
                path.lineTo(dp(10), -dp(7));
                path.lineTo(dp(10), dp(7));
                path.lineTo(dp(3), dp(3));
                canvas.drawPath(path, paint);
                break;
            case UP:
                bounds.set(-dp(5), -dp(11), dp(5), dp(2));
                canvas.drawArc(bounds, 180, 180, false, paint);
                canvas.drawLine(-dp(5), -dp(4.5f), -dp(5), -dp(1), paint);
                canvas.drawLine(dp(5), -dp(4.5f), dp(5), -dp(1), paint);
                bounds.set(-dp(9), -dp(1), dp(9), dp(10));
                canvas.drawRoundRect(bounds, dp(2), dp(2), paint);
                canvas.drawLine(0, dp(3), 0, dp(6), paint);
                break;
            case DOWN:
                bounds.set(-dp(8), -dp(11), dp(8), dp(11));
                canvas.drawRoundRect(bounds, dp(2), dp(2), paint);
                canvas.drawLine(-dp(4), -dp(5), dp(4), -dp(5), paint);
                canvas.drawLine(-dp(4), 0, dp(4), 0, paint);
                canvas.drawLine(-dp(4), dp(5), dp(1), dp(5), paint);
                break;
            case MARKER:
                path.reset();
                path.moveTo(-dp(7), -dp(11));
                path.lineTo(dp(7), -dp(11));
                path.lineTo(dp(7), dp(11));
                path.lineTo(0, dp(6));
                path.lineTo(-dp(7), dp(11));
                path.close();
                canvas.drawPath(path, paint);
                break;
            default: break;
        }
        canvas.restoreToCount(saved);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    private float homeRadius() { return homeRadius; }
    private boolean animationsEnabled() { return isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled(); }
    private float pulseSince(long startedAt, long durationMs) {
        if (!active || startedAt == 0 || !animationsEnabled()) return 0;
        long elapsed = SystemClock.uptimeMillis() - startedAt;
        return elapsed >= durationMs ? 0 : 1 - elapsed / (float) durationMs;
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static int alpha(int color, int opacity) { return (color & 0x00ffffff) | (opacity << 24); }
}
