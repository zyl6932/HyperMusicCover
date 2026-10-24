package com.os4.musiccover.lyrics;

/** One monochrome choice per visible background/request, without changing saved colors. */
final class LyricContrastState {
    static final int BLACK = 0xff000000, WHITE = 0xffffffff;
    private String background;
    private long request;
    private int ink;
    private boolean resolved;

    int update(boolean active, String key, long requestId, int candidate) {
        if (!active) {
            clear();
            return 0;
        }
        boolean changed = ink == 0 || request != requestId
                || (background == null ? key != null : !background.equals(key));
        if (changed) {
            // A map Surface can be rebuilt while the same lyric page is still visible.
            // Retain its current display ink provisionally instead of flashing white while
            // the replacement's first sample arrives. This is not a resolved sample for the
            // new background: its first real answer below can still replace that ink.
            boolean sameVisibleRequest = ink != 0 && request == requestId;
            background = key;
            request = requestId;
            if (!sameVisibleRequest) ink = WHITE;
            resolved = false;
        }
        // The cover worker may not have produced the new background yet. Only an actual
        // black/white answer resolves it; a later answer for this background can finish entry.
        // Once selected, repeated lyric ticks or dynamic frames cannot flip the text's color.
        if (!resolved && (candidate == BLACK || candidate == WHITE)) {
            ink = candidate;
            resolved = true;
        }
        return ink;
    }

    void clear() {
        background = null;
        request = 0L;
        ink = 0;
        resolved = false;
    }
}
