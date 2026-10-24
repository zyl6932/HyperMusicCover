package com.os4.musiccover.lyrics;

/**
 * Interruptible transition between the saved colors and one navigation contrast color.
 * One affine RGB transform is shared by text and cached blur pictures; alpha is unchanged.
 */
final class LyricNavigationColor {
    static final long DURATION_MS = 300L;
    private boolean initialized, moving;
    private int target;
    private long started;
    private float scale = 1f, offset;
    private float fromScale = 1f, fromOffset;

    /** Hidden entry uses its final color before the first glyph becomes visible. */
    boolean update(int ink, boolean visible, long now) {
        if (ink != LyricContrastState.BLACK && ink != LyricContrastState.WHITE) ink = 0;
        float oldScale = scale, oldOffset = offset;
        boolean oldMoving = moving;
        if (!initialized || !visible) {
            initialized = true;
            target = ink;
            scale = ink == 0 ? 1f : 0f;
            offset = ink == LyricContrastState.WHITE ? 255f : 0f;
            moving = false;
        } else {
            advance(now);
            if (target != ink) {
                // A reversal starts at the color actually drawn at this instant, never at the
                // previous endpoint. No queued completion can later restore an obsolete target.
                target = ink;
                fromScale = scale;
                fromOffset = offset;
                started = now;
                moving = scale != targetScale() || offset != targetOffset();
            }
        }
        return oldScale != scale || oldOffset != offset || oldMoving != moving;
    }

    private void advance(long now) {
        if (!moving) return;
        float t = Math.max(0f, Math.min(1f, (now - started) / (float) DURATION_MS));
        float eased = t * t * (3f - 2f * t);
        scale = fromScale + (targetScale() - fromScale) * eased;
        offset = fromOffset + (targetOffset() - fromOffset) * eased;
        if (t == 1f) moving = false;
    }

    private float targetScale() { return target == 0 ? 1f : 0f; }
    private float targetOffset() { return target == LyricContrastState.WHITE ? 255f : 0f; }

    boolean moving() { return moving; }
    boolean identity() { return scale == 1f && offset == 0f; }

    int apply(int color) {
        int r = Math.round(((color >>> 16) & 255) * scale + offset);
        int g = Math.round(((color >>> 8) & 255) * scale + offset);
        int b = Math.round((color & 255) * scale + offset);
        return (color & 0xff000000) | (r << 16) | (g << 8) | b;
    }

    /** Android ColorMatrix layout, without depending on Android in the state machine. */
    void matrix(float[] values) {
        java.util.Arrays.fill(values, 0f);
        values[0] = values[6] = values[12] = scale;
        values[4] = values[9] = values[14] = offset;
        values[18] = 1f;
    }

    void clear() {
        initialized = moving = false;
        target = 0;
        scale = fromScale = 1f;
        offset = fromOffset = 0f;
    }
}
