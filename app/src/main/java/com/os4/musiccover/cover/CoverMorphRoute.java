package com.os4.musiccover.cover;

/** Whether a manual page switch has a large artwork endpoint to morph through. */
public final class CoverMorphRoute {
    public static final int NORMAL = 0;
    public static final int COVER = 1;
    public static final int LYRICS = 2;

    private CoverMorphRoute() { }

    public static boolean shouldMorph(int from, int to) {
        return from != to && (from == COVER || to == COVER);
    }

    /** No entry clears the two-finger lyric dismissal: only the next two-finger tap does. */
    public static boolean lyricsAfterEntry(boolean enabled, boolean tapHidden, boolean demo) {
        return demo || enabled && !tapHidden;
    }

    public static boolean lyricsAfterToggle(boolean enabled, boolean tapHidden, boolean demo) {
        return demo || enabled && tapHidden;
    }
}
