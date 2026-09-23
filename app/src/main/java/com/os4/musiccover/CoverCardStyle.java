package com.os4.musiccover;

/**
 * Values shared by the settings page, preview and the SystemUI-drawn cover.
 *
 * Two settings, both shares of the room between the clock and the media card, so every step of
 * either slider moves the square. They were three in dp - size, margin and offset - and they
 * overlapped: past the room the size slider did nothing (420dp never fits), the margin mostly
 * acted as a second size cap, and the offset was clamped away whenever the square filled the
 * room. The margin is a fixed GAP_DP now.
 */
final class CoverCardStyle {
    static final int FULL = 0;
    static final int CARD = 1;
    /** Least room kept to the clock, the media card and the screen edges. */
    static final float GAP_DP = 16f;
    static final float MIN_FILL = 0.4f;
    static final float DEFAULT_FILL = 0.8f;
    static final float DEFAULT_POS = 0.5f;
    /** About the 20dp the corner had at a 300dp square before it was a setting. */
    static final float DEFAULT_CORNER = 0.12f;
    /** The top of the old dp size slider: a square set there was filling its room. */
    private static final float LEGACY_MAX_SIZE_DP = 420f;

    final int mode;
    /** The side as a share of the largest square that fits the room, MIN_FILL..1. */
    final float fill;
    /** Where the square sits in the height it leaves over: 0 top, 0.5 centre, 1 bottom. */
    final float pos;
    /**
     * How round the corners are, 0 square to 1 a circle: a share of the side rather than dp, so
     * the shape holds while the size follows the room.
     */
    final float corner;

    CoverCardStyle(int mode, float fill, float pos, float corner) {
        this.mode = mode == CARD ? CARD : FULL;
        this.fill = finite(fill, MIN_FILL, 1f, DEFAULT_FILL);
        this.pos = finite(pos, 0f, 1f, DEFAULT_POS);
        this.corner = finite(corner, 0f, 1f, DEFAULT_CORNER);
    }

    static CoverCardStyle defaults() {
        return new CoverCardStyle(FULL, DEFAULT_FILL, DEFAULT_POS, DEFAULT_CORNER);
    }

    CoverCardStyle with(String key, float value) {
        if ("fill".equals(key)) return new CoverCardStyle(mode, value, pos, corner);
        if ("pos".equals(key)) return new CoverCardStyle(mode, fill, value, corner);
        if ("corner".equals(key)) return new CoverCardStyle(mode, fill, pos, value);
        if ("mode".equals(key)) return new CoverCardStyle(Math.round(value), fill, pos, corner);
        return this;
    }

    /** The corner radius for a card whose shorter side is this. */
    float radius(float side) {
        return side * 0.5f * corner;
    }

    /**
     * The card takes the artwork's own shape - a Bilibili video's 16:10 cover was centre-cropped
     * to a square and lost its sides - within these bounds; anything longer is cropped to them.
     */
    static final float MAX_ASPECT = 2f;

    /** Width over height for artwork of this size, held to 1/MAX_ASPECT..MAX_ASPECT. */
    static float aspect(float w, float h) {
        if (!(w > 0f && h > 0f)) return 1f;
        return Math.max(1f / MAX_ASPECT, Math.min(MAX_ASPECT, w / h));
    }

    /**
     * An old dp size, for a state file or backup from before the shares. Only the slider's top
     * translates without knowing the room - it meant "as big as fits"; anything else is the default.
     */
    static float fillFromLegacySizeDp(float sizeDp) {
        return sizeDp >= LEGACY_MAX_SIZE_DP ? 1f : DEFAULT_FILL;
    }

    static float finite(float v, float lo, float hi, float fallback) {
        return Float.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : fallback;
    }

    /** The square reserves its full playing size even while the paused artwork scales inward. */
    Rect place(float width, float height, float density, float clockBottom,
               float mediaTop) {
        if (!(width > 0f && height > 0f && density > 0f)
                || !Float.isFinite(width) || !Float.isFinite(height)
                || !Float.isFinite(density)) return null;
        float gap = GAP_DP * density;
        float top = Math.max(0f, clockBottom > 0f && Float.isFinite(clockBottom)
                ? clockBottom : height * 0.18f) + gap;
        // A stale card rectangle from a previous lock session can put the art over the OEM
        // media card on wake. Wait for this session's measured boundary instead.
        if (!Float.isFinite(mediaTop) || mediaTop <= height / 3f
                || mediaTop > height) return null;
        float bottom = mediaTop - gap;
        float room = Math.min(width - 2f * gap, bottom - top);
        float least = 96f * density;
        if (room < least || !Float.isFinite(room)) return null;
        // A small share of a tight room is held at the least size rather than dropped.
        float side = Math.max(least, room * fill);
        float y = top + (bottom - top - side) * pos;
        return new Rect((width - side) * 0.5f, y, side);
    }

    static final class Rect {
        final float x, y, side;
        Rect(float x, float y, float side) {
            this.x = x;
            this.y = y;
            this.side = side;
        }
    }
}
