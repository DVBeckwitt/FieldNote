package com.fieldnote.app;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.ScrollView;

/** Native scrolling, with a downward panel drag only when the finger starts at the top. */
public final class NotesScrollView extends ScrollView {
    private final Runnable onDismiss;
    private final float dismissDistance;
    private final int touchSlop;
    private NotesDragListener dragListener, activeListener;
    private boolean dismissEnabled, tracking, eligible, pulling, invalidSequence, nativeCancelled;
    private boolean dragAttempted, dragAccepted, dragEnded;
    private int pointerId;
    private float downX, downY;

    public NotesScrollView(Context context, Runnable onDismiss) {
        super(context);
        this.onDismiss = onDismiss;
        dismissDistance = 64 * getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setDragListener(NotesDragListener listener) {
        if (dragListener == listener) return;
        if (dragAttempted && !dragEnded) invalidateSequence(null);
        dragListener = listener;
    }

    public void setDismissEnabled(boolean enabled) {
        dismissEnabled = enabled;
        // Enabling mid-scroll cannot change where that gesture began.
        if (!enabled) {
            eligible = false;
            if (dragAttempted && !dragEnded) invalidateSequence(null);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (dragAttempted && !dragEnded) invalidateSequence(event);
            tracking = true;
            pulling = nativeCancelled = false;
            dragAttempted = dragAccepted = dragEnded = false;
            activeListener = null;
            invalidSequence = !isEnabled() || event.getPointerCount() != 1;
            eligible = dismissEnabled && getScrollY() == 0 && !invalidSequence;
            pointerId = event.getPointerId(0);
            downX = event.getRawX();
            downY = event.getRawY();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            if (tracking) {
                invalidSequence = true;
                eligible = false;
                if (pulling) { invalidateSequence(event); return true; }
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            boolean owned = pulling || nativeCancelled;
            if (owned) invalidateSequence(event);
            finishTracking();
            if (owned) return true;
        } else if (tracking && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            if (!isEnabled() || event.getPointerCount() != 1 || event.findPointerIndex(pointerId) != 0) {
                invalidSequence = true;
                eligible = false;
                if (pulling) invalidateSequence(event);
            }
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            if (eligible && !invalidSequence && !pulling) {
                if (dy < -touchSlop || (Math.abs(dx) > touchSlop && Math.abs(dx) >= Math.abs(dy) * 1.25f)) {
                    eligible = false;
                } else if (dy > touchSlop && dy >= Math.abs(dx) * 1.25f) {
                    startDrag(event);
                }
            }
            if (pulling) {
                if (dragAccepted && !dragEnded && activeListener != null)
                    activeListener.onDrag(Math.max(0, dy));
                if (action == MotionEvent.ACTION_UP) {
                    boolean commit = dragAccepted && !invalidSequence && dismissEnabled && isEnabled()
                            && dy >= dismissDistance && dy >= Math.abs(dx) * 1.25f;
                    finishTracking();
                    endDrag(commit);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) finishTracking();
        }
        // The child/native stream already received CANCEL when this panel drag took over.
        if (nativeCancelled) return true;
        boolean handled = super.dispatchTouchEvent(event);
        return handled || (tracking && eligible);
    }

    private void startDrag(MotionEvent event) {
        cancelNativeTouch(event);
        pulling = true;
        dragAttempted = true;
        dragEnded = false;
        activeListener = dragListener;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        boolean accepted = activeListener == null || activeListener.onDragStart();
        if (dragEnded) return;
        dragAccepted = accepted && dismissEnabled && isEnabled() && !invalidSequence;
        if (!dragAccepted) endDrag(false);
    }

    private void endDrag(boolean commit) {
        if (!dragAttempted || dragEnded) return;
        dragEnded = true;
        dragAccepted = false;
        finishTracking();
        NotesDragListener completed = activeListener;
        activeListener = null;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        if (completed != null) completed.onDragEnd(commit);
        else if (commit && onDismiss != null) onDismiss.run();
    }

    private void invalidateSequence(MotionEvent event) {
        invalidSequence = true;
        eligible = false;
        cancelNativeTouch(event);
        endDrag(false);
        finishTracking();
    }

    private void cancelNativeTouch(MotionEvent source) {
        if (nativeCancelled) return;
        nativeCancelled = true;
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = source == null ? MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0)
                : MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        cancelLongPress();
    }

    private void finishTracking() {
        tracking = false;
        eligible = false;
        pulling = false;
        invalidSequence = true;
    }

    @Override public void setEnabled(boolean enabled) {
        if (!enabled && tracking) invalidateSequence(null);
        super.setEnabled(enabled);
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus && tracking) invalidateSequence(null);
    }

    @Override protected void onDetachedFromWindow() {
        if (tracking) invalidateSequence(null);
        finishTracking();
        super.onDetachedFromWindow();
    }
}
