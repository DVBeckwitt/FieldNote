package com.fieldnote.app;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/** Page navigation is recognized only for gestures beginning outside capture/edit controls. */
public final class SwipeTabsLayout extends FrameLayout {
    public interface Listener { void onPageSwipe(int direction); }
    private final float threshold;
    private final int slop;
    private final Listener listener;
    private final List<View> excluded = new ArrayList<>();
    private final Rect bounds = new Rect();
    private float startX, startY;
    private boolean eligible, swiped;

    public SwipeTabsLayout(Context context, float threshold, Listener listener) {
        super(context);
        this.threshold = threshold;
        this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void exclude(View view) { excluded.add(view); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = event.getRawX();
            startY = event.getRawY();
            swiped = false;
            eligible = true;
            for (View view : excluded) {
                if (view.isShown() && view.getGlobalVisibleRect(bounds)
                        && bounds.contains((int) startX, (int) startY)) eligible = false;
            }
        } else if (action == MotionEvent.ACTION_MOVE && eligible && !swiped) {
            float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) eligible = false;
            else if (Math.abs(dx) >= threshold && Math.abs(dx) >= Math.abs(dy) * 1.25f) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                swiped = true;
                listener.onPageSwipe(dx < 0 ? 1 : -1);
            }
        }
        if (swiped) return true;
        // Blank space must retain the sequence so a page swipe can begin there.
        boolean handled = super.dispatchTouchEvent(event);
        return handled || eligible;
    }
}
