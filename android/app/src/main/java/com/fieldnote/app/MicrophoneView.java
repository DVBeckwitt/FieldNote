package com.fieldnote.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.Button;

/** Shared microphone artwork and accessibility actions; the host owns capture and gestures. */
public class MicrophoneView extends View {
    public interface ActionListener {
        void onPhoto();
        void onVideo();
        void onText();
        void onMarker();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawingRect = new RectF();
    private final boolean dark;
    private final int recordingColor;
    private final ActionListener actions;
    private final Shader idleGradient;
    private final Shader recordingGradient = new LinearGradient(-.65f, -1f, .7f, 1f,
            new int[]{0xffc99bff, 0xff9455e8, 0xff6227b8}, null, Shader.TileMode.CLAMP);
    private final Shader haloGradient;
    private final Shader recordingHaloGradient = new RadialGradient(0, 0, 1,
            new int[]{Color.TRANSPARENT, 0xffbb88ff, Color.TRANSPARENT},
            new float[]{.70f, .86f, 1f}, Shader.TileMode.CLAMP);
    public boolean recording, locked;
    private long pressStartedAt;

    public MicrophoneView(Context context, boolean dark, int accent,
                          int recordingColor, ActionListener actions) {
        super(context);
        this.dark = dark;
        this.recordingColor = recordingColor;
        this.actions = actions;
        idleGradient = new LinearGradient(-.65f, -1f, .7f, 1f,
                dark ? new int[]{0xff3865a5, 0xff204e87, 0xff122f56}
                        : new int[]{0xff639aeb, 0xff3679d5, 0xff2358a4}, null, Shader.TileMode.CLAMP);
        haloGradient = new RadialGradient(0, 0, 1,
                new int[]{Color.TRANSPARENT, accent, Color.TRANSPARENT}, new float[]{.73f, .88f, 1f}, Shader.TileMode.CLAMP);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void press(boolean pressed) {
        if (pressed && !isPressed()) pressStartedAt = SystemClock.uptimeMillis();
        animate().cancel();
        if (pressed) { setTranslationX(0); setTranslationY(0); }
        setPressed(pressed);
        if (ValueAnimator.areAnimatorsEnabled()) {
            animate().scaleX(pressed ? .84f : 1f).scaleY(pressed ? .84f : 1f)
                    .setInterpolator(new PathInterpolator(.2f, 0f, .1f, 1f))
                    .setDuration(pressed ? 110 : 220).setUpdateListener(value -> invalidate()).start();
        } else { setScaleX(pressed ? .84f : 1f); setScaleY(pressed ? .84f : 1f); }
        invalidate();
    }

    public void follow(float dx, float dy) {
        if (!isPressed()) return;
        setTranslationX(dx);
        setTranslationY(dy);
        invalidate();
    }

    public void home() {
        setPressed(false);
        animate().cancel();
        if (ValueAnimator.areAnimatorsEnabled()) {
            animate().translationX(0).translationY(0).scaleX(1).scaleY(1)
                    .setInterpolator(new PathInterpolator(.2f, 0f, .1f, 1f))
                    .setDuration(230).setUpdateListener(value -> invalidate()).start();
        } else { setTranslationX(0); setTranslationY(0); setScaleX(1); setScaleY(1); }
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        animate().setUpdateListener(null).cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float distance = (float) Math.hypot(getTranslationX(), getTranslationY());
        float compact = Math.max(0f, Math.min(1f, (distance - dp(20)) / dp(80)));
        compact = compact * compact * (3f - 2f * compact);
        // Only the artwork morphs. The host retains the fixed gesture origin and distances.
        float compactRadius = Math.max(dp(24), Math.min(dp(44),
                hostWidth() / 2f - dp(RecordingGesture.ACTIVATION_DISTANCE_DP) - dp(4)));
        float restRadius = Math.min(cx, cy) - dp(16);
        float radius = restRadius + (compactRadius - restRadius) * compact;
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        long now = SystemClock.uptimeMillis();
        float pulse = animate && (recording || isPressed())
                ? .5f + .5f * (float) Math.sin((recording ? now : now - pressStartedAt) * .0035) : .5f;
        if (isPressed()) {
            long elapsed = now - pressStartedAt;
            // The visual tremor never changes the host's touch coordinates or guide origin.
            if (animate) {
                float density = getResources().getDisplayMetrics().density;
                cx += (float) Math.sin(elapsed * .045) * density * .65f;
                cy += (float) Math.sin(elapsed * .053) * density * .45f;
            }
        }
        // Locked or shortcut-started narration radiates too, without needing a held finger.
        if (animate && (recording || isPressed())) postInvalidateOnAnimation();
        paint.setStyle(Paint.Style.FILL);
        canvas.save();
        canvas.translate(cx, cy);
        float haloRadius = radius + dp(recording ? 12 + 4 * pulse : isPressed() ? 15 : 11);
        canvas.scale(haloRadius, haloRadius);
        paint.setShader(recording ? recordingHaloGradient : haloGradient);
        paint.setAlpha(recording ? Math.round(100 + 50 * pulse)
                : isPressed() ? Math.round(52 + 28 * pulse) : dark ? 23 : 14);
        canvas.drawCircle(0, 0, 1, paint);
        canvas.restore();
        paint.setAlpha(255);
        paint.setShader(null);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(radius, radius);
        paint.setShader(recording ? recordingGradient : idleGradient);
        canvas.drawCircle(0, 0, 1, paint);
        canvas.restore();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(recording ? recordingColor : dark ? 0x785e92d9 : 0x805c8dcd);
        paint.setStrokeWidth(dp(locked && recording ? 5 : 1));
        canvas.drawCircle(cx, cy, radius + dp(3), paint);
        paint.setColor(locked && recording ? 0xffe4ccff : 0x24ffffff);
        paint.setStrokeWidth(dp(1));
        canvas.drawCircle(cx, cy, radius - dp(2), paint);
        paint.setColor(locked && recording ? 0xfff4eaff : Color.WHITE);
        canvas.save();
        // A floating host can use a smaller resting circle than the full notebook.
        // Keep the existing scale whenever it fits, and leave room for the icon's stroke.
        float iconScale = Math.min(2.2f - compact, Math.max(.1f, (radius - dp(4)) / dp(24)));
        canvas.scale(iconScale, iconScale, cx, cy);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(locked && recording ? 2.8f : 2.2f));
        drawingRect.set(cx - dp(6), cy - dp(17), cx + dp(6), cy + dp(5));
        canvas.drawRoundRect(drawingRect, dp(7), dp(7), paint);
        drawingRect.set(cx - dp(12), cy - dp(10), cx + dp(12), cy + dp(12));
        canvas.drawArc(drawingRect, 0, 180, false, paint);
        canvas.drawLine(cx, cy + dp(12), cx, cy + dp(21), paint);
        canvas.drawLine(cx - dp(7), cy + dp(21), cx + dp(7), cy + dp(21), paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private float hostWidth() {
        if (getParent() instanceof View) {
            View host = (View) getParent();
            int width = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
            if (width > 0) return width;
        }
        return getResources().getDisplayMetrics().widthPixels - dp(40);
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.mic_photo_action, "Take photo"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.mic_video_action, "Record video"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.mic_text_action, "Write text note"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.mic_marker_action, "Add audio marker"));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (actions != null) {
            if (action == R.id.mic_photo_action) { actions.onPhoto(); return true; }
            if (action == R.id.mic_video_action) { actions.onVideo(); return true; }
            if (action == R.id.mic_text_action) { actions.onText(); return true; }
            if (action == R.id.mic_marker_action) { actions.onMarker(); return true; }
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
}
