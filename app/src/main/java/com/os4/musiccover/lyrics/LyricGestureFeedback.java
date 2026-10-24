package com.os4.musiccover.lyrics;

/** A bounded response to the finger's upward speed, applied once to the whole lyric page. */
public final class LyricGestureFeedback {
    public static final float MIN_SCALE = 0.86f;
    private static final float FULL_SPEED_DP = 2400f;
    private static final float QUIET_SPEED_DP = 60f;
    private static final float SHRINK_TAU = 0.075f;
    private static final float RETURN_TAU = 0.18f;
    private static final float EPSILON = 0.0005f;

    private float scale = 1f;
    private float target = 1f;
    private boolean dragging;

    /** The page arrives as one block; combining entry and drag must keep the same hard floor. */
    public static float presentationScale(float visibility, float gestureScale) {
        float shown = Float.isFinite(visibility) ? Math.max(0f, Math.min(1f, visibility)) : 1f;
        float gesture = Float.isFinite(gestureScale)
                ? Math.max(MIN_SCALE, Math.min(1f, gestureScale)) : 1f;
        return Math.max(MIN_SCALE, Math.min(1f, (0.94f + 0.06f * shown) * gesture));
    }

    /** Fraction of the page's downward entry offset, using its current reversible visibility. */
    public static float entryOffset(float visibility) {
        return Float.isFinite(visibility) ? 1f - Math.max(0f, Math.min(1f, visibility)) : 0f;
    }

    /** Pixel velocity uses Android's downward-positive axis; density makes the response portable. */
    public static float upwardSpeed(float velocityY, float density) {
        if (!Float.isFinite(velocityY) || !Float.isFinite(density) || density <= 0f) return 0f;
        return (float) Math.min(FULL_SPEED_DP, Math.max(0d, -(double) velocityY / density));
    }

    /** NaN means the finger has left/cancelled; autonomous card springs never count as a drag. */
    public boolean step(float upwardDpPerSecond, float dtSeconds) {
        dragging = Float.isFinite(upwardDpPerSecond) && upwardDpPerSecond >= 0f;
        float amount = dragging ? Math.max(0f, Math.min(1f,
                (upwardDpPerSecond - QUIET_SPEED_DP) / (FULL_SPEED_DP - QUIET_SPEED_DP))) : 0f;
        target = 1f - (1f - MIN_SCALE) * amount;
        if (!Float.isFinite(dtSeconds) || dtSeconds <= 0f) return false;
        float dt = Math.min(dtSeconds, 0.1f);
        float tau = target < scale ? SHRINK_TAU : RETURN_TAU;
        float next = target + (scale - target) * (float) Math.exp(-dt / tau);
        if (Math.abs(next - target) < EPSILON) next = target;
        next = Math.max(MIN_SCALE, Math.min(1f, next));
        boolean changed = next != scale;
        scale = next;
        return changed;
    }

    public float scale() { return scale; }

    // Keep sampling while the finger is active: a stationary finger ages out of the bridge,
    // even after this scale has reached its target and no new MOVE will wake the view.
    public boolean moving() { return dragging || scale != target; }

    public void reset() {
        scale = target = 1f;
        dragging = false;
    }
}
