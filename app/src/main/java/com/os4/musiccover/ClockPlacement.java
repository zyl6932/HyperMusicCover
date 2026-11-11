package com.os4.musiccover;

/** Window-relative cover layout. Optional clock decorations never supply the anchor. */
final class ClockPlacement {
    private ClockPlacement() {}

    static final float TOP_DP = 76f;
    static final float SAFE_GAP_DP = 8f;

    static float safeLine(float density, float safeTop) {
        return Math.max(0f, safeTop) + SAFE_GAP_DP * density;
    }

    static float blockTop(float density, float safeTop, float offsetDp) {
        float safe = safeLine(density, safeTop);
        return Math.max(safe, Math.max(TOP_DP * density, safe) + offsetDp * density);
    }

    static float timeTop(float blockTop, float headerHeight, float gap) {
        return blockTop + (headerHeight > 0f ? headerHeight + gap : 0f);
    }

    /** The card/material progress is valid even before a clock can be measured. */
    static float progress(float from, float to, float t, float lag) {
        float p = Math.max(0f, Math.min(1f, t));
        if (lag > 0f) {
            p = Math.max(0f, Math.min(1f, (p - lag) / (1f - lag)));
            p = p * p * (3f - 2f * p);
        }
        return from + (to - from) * p;
    }
}
