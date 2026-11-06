package com.fieldnote.app;

/** A notes-panel drag measured from finger-down in screen pixels. */
public interface NotesDragListener {
    boolean onDragStart();
    void onDrag(float distancePx);
    void onDragEnd(boolean commit);
}
