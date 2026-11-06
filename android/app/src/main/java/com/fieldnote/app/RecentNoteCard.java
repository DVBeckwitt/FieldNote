package com.fieldnote.app;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

/** Native note taps/long presses, plus an upward drag to reveal the complete notebook. */
public final class RecentNoteCard extends LinearLayout {
    private final Runnable onShowAll;
    private final float swipeDistance;
    private final int touchSlop;
    private NotesDragListener dragListener, activeListener;
    private float downX, downY;
    private int pointerId;
    private boolean tracking, nativeCancelled, invalidSequence, handledLongPress;
    private boolean dragAttempted, dragAccepted, dragEnded;

    public RecentNoteCard(Context context, Runnable onShowAll) {
        super(context);
        this.onShowAll = onShowAll;
        swipeDistance = 48 * getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setClickable(true);
        setFocusable(true);
    }

    public void setDragListener(NotesDragListener listener) {
        if (dragListener == listener) return;
        if (dragAttempted && !dragEnded) invalidateSequence(null);
        dragListener = listener;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (dragAttempted && !dragEnded) invalidateSequence(event);
            tracking = true;
            nativeCancelled = false;
            invalidSequence = !isEnabled() || event.getPointerCount() != 1;
            handledLongPress = false;
            dragAttempted = dragAccepted = dragEnded = false;
            activeListener = null;
            pointerId = event.getPointerId(0);
            downX = event.getRawX();
            downY = event.getRawY();
            super.onTouchEvent(event);
            if (invalidSequence) cancelNativeTouch(event);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            if (tracking) invalidateSequence(event);
            return nativeCancelled;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            boolean owned = tracking || nativeCancelled;
            if (tracking) invalidateSequence(event);
            tracking = false;
            return owned;
        }
        if (!tracking) return nativeCancelled;
        if (!isEnabled() || event.getPointerCount() != 1 || event.findPointerIndex(pointerId) != 0)
            invalidateSequence(event);
        if (invalidSequence || handledLongPress) {
            cancelNativeTouch(event);
            if (action == MotionEvent.ACTION_UP) tracking = false;
            return true;
        }
        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            if (Math.hypot(dx, dy) > touchSlop) cancelNativeTouch(event);
            if (!dragAttempted && -dy > touchSlop && -dy >= Math.abs(dx) * 1.25f) startDrag(event);
            if (dragAccepted && !dragEnded && activeListener != null)
                activeListener.onDrag(Math.max(0, -dy));
            if (action == MotionEvent.ACTION_UP) {
                boolean commit = dragAccepted && !invalidSequence && !handledLongPress
                        && -dy >= swipeDistance && -dy >= Math.abs(dx) * 1.25f;
                tracking = false;
                if (dragAttempted) endDrag(commit);
                else if (!nativeCancelled) super.onTouchEvent(event);
            } else if (!nativeCancelled) super.onTouchEvent(event);
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void startDrag(MotionEvent event) {
        cancelNativeTouch(event);
        dragAttempted = true;
        dragEnded = false;
        activeListener = dragListener;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        boolean accepted = activeListener == null || activeListener.onDragStart();
        if (dragEnded) return; // Callback may synchronously disable or detach this view.
        dragAccepted = accepted && isEnabled() && !invalidSequence;
        if (!dragAccepted) endDrag(false);
    }

    private void endDrag(boolean commit) {
        if (!dragAttempted || dragEnded) return;
        dragEnded = true;
        dragAccepted = false;
        tracking = false;
        invalidSequence = true;
        NotesDragListener completed = activeListener;
        activeListener = null;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        if (completed != null) completed.onDragEnd(commit);
        else if (commit && onShowAll != null) onShowAll.run();
    }

    private void invalidateSequence(MotionEvent event) {
        invalidSequence = true;
        tracking = false;
        cancelNativeTouch(event);
        endDrag(false);
    }

    private void cancelNativeTouch(MotionEvent source) {
        if (nativeCancelled) return;
        nativeCancelled = true;
        cancelLongPress();
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = source == null ? MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0)
                : MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
        setPressed(false);
    }

    @Override public boolean performLongClick() {
        boolean handled = super.performLongClick();
        if (handled && tracking) {
            handledLongPress = true;
            endDrag(false);
        }
        return handled;
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
        tracking = false;
        super.onDetachedFromWindow();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.show_all_notes, "Show all notes"));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.id.show_all_notes && isEnabled()) {
            if (tracking) invalidateSequence(null);
            if (onShowAll != null) onShowAll.run();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
