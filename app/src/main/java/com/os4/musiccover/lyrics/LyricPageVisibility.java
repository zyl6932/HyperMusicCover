package com.os4.musiccover.lyrics;

/** The lyric page's own progress; an island page does not unfold the media card. */
final class LyricPageVisibility {
    private LyricPageVisibility() {}

    static float progress(boolean capsulePage, boolean coverMode, float cardProgress) {
        // Body taps own a lyric overlay, even when a cover is retained behind navigation.
        // Its fade must not wait for an intentionally folded native card to unfold.
        if (capsulePage) return 1f;
        if (coverMode) return Math.max(0f, Math.min(1f, cardProgress));
        return 0f;
    }

    static boolean needsIndependentClock(boolean capsulePage, boolean coverMode,
                                         boolean wanted, boolean hasLyrics) {
        return capsulePage && !coverMode && wanted && hasLyrics;
    }
}
