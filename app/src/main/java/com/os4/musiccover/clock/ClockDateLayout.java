package com.os4.musiccover.clock;

/** Date space from a completed pre-draw pass, shared by the clock and lyrics next frame. */
public final class ClockDateLayout<T> {
    private boolean known;
    private T view;
    private float height;

    public T forFrame(T candidate) { return known ? view : candidate; }
    public float heightForFrame(float candidate) { return known ? height : candidate; }

    /** A held AOD can acquire/lose its date after the hidden desktop pose was captured. */
    public static float heldTimeTop(float top, float dateTop, float dateHeight, float gap) {
        boolean hadDate = !Float.isNaN(dateTop), hasDate = dateHeight > 0f;
        if (hadDate == hasDate) return top;
        return hasDate ? ClockPlacement.timeTop(top, dateHeight, gap) : dateTop;
    }

    public boolean commit(T drawn, float drawnHeight) {
        float h = drawn == null ? 0f : drawnHeight;
        boolean changed = !known || drawn != view || h != height;
        known = true;
        view = drawn;
        height = h;
        return changed;
    }

    public void reset() {
        known = false;
        view = null;
        height = 0f;
    }
}
