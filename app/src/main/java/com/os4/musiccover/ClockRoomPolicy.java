package com.os4.musiccover;

/** Unknown stack geometry is NaN; a measured stack with no visible rows is unbounded. */
final class ClockRoomPolicy {
    private ClockRoomPolicy() {}

    static float contentTop(float stackY, float visibleTop) {
        return Float.isInfinite(visibleTop) ? Float.MAX_VALUE : stackY + visibleTop;
    }

    static float resolve(float requested, float contentTop, float unobstructedY) {
        if (!Float.isFinite(requested) || requested >= Float.MAX_VALUE / 2f) return requested;
        if (Float.isNaN(contentTop)) return requested;
        float available = contentTop == Float.MAX_VALUE ? unobstructedY : contentTop;
        return Float.isFinite(available) ? Math.max(requested, available) : requested;
    }
}
