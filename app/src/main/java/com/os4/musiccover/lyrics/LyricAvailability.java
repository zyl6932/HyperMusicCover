package com.os4.musiccover.lyrics;

/** Availability of this track, deliberately independent of the renderer's previous-track hold. */
public enum LyricAvailability {
    PENDING, AVAILABLE, UNAVAILABLE;

    public enum Feedback { PENDING, ENABLED, DISABLED, UNAVAILABLE }

    /** Confirmed absence is a question on either tap; cancelling a lookup is only a close. */
    public Feedback feedbackFor(boolean opening) {
        if (this == UNAVAILABLE) return Feedback.UNAVAILABLE;
        if (!opening) return Feedback.DISABLED;
        return this == AVAILABLE ? Feedback.ENABLED : Feedback.PENDING;
    }

    public static LyricAvailability resolve(String expectedKey, String currentKey,
                                            boolean currentLines, boolean cachedLines,
                                            boolean loading, boolean parked, String settledKey) {
        if (expectedKey == null || !expectedKey.equals(currentKey)) return PENDING;
        if (currentLines || cachedLines) return AVAILABLE;
        if (loading || parked) return PENDING;
        if (currentKey.isEmpty() || currentKey.equals(settledKey)) return UNAVAILABLE;
        return PENDING;
    }
}
