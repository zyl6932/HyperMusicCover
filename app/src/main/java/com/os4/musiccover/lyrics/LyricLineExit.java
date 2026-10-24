package com.os4.musiccover.lyrics;

import java.util.List;

/** The last drawn pose of a genuinely finished lyric group, independent of the next row's scroll. */
final class LyricLineExit {
    static final long DURATION_MS = 300L;
    static final float END_SCALE = 0.85f;
    // Line-timed lyrics wake at most every 500ms. Returning from sleep must not replay old exits.
    private static final int MAX_END_LAG_MS = 600;

    static final class Row {
        float x, y, scale, alpha, emphasis, lit, blur;
        boolean captured;
        long startedAt = -1L;
        boolean finished;

        boolean departing() { return startedAt >= 0L; }
        float amount(long now) {
            float t = Math.max(0f, Math.min(1f, (now - startedAt) / (float) DURATION_MS));
            return t * t * (3f - 2f * t);
        }
        float alpha(long now) { return departing() ? 1f - amount(now) : 1f; }
        float scale(long now) { return departing() ? 1f - (1f - END_SCALE) * amount(now) : 1f; }
    }

    private Row[] rows = new Row[0];
    private int previousPosition = -1;
    private boolean previouslyPlaying;
    private boolean seeking;

    void reset(int count) {
        rows = new Row[Math.max(0, count)];
        for (int i = 0; i < rows.length; i++) rows[i] = new Row();
        previousPosition = -1;
        previouslyPlaying = false;
        seeking = false;
    }

    Row row(int index) { return index >= 0 && index < rows.length ? rows[index] : null; }

    /** Keep the captured fade once; a moving clip can only make the departing row dimmer. */
    static float edgeAlpha(float captured, float top, float bottom,
                           float clipTop, float clipBottom, float fade) {
        if (fade <= 0f || clipBottom <= clipTop) return 0f;
        float edge = Math.min((top - clipTop) / fade, (clipBottom - bottom) / fade);
        return Math.min(captured, Math.max(0f, Math.min(1f, edge)));
    }

    void capture(int index, float x, float y, float scale, float alpha,
                 float emphasis, float lit, float blur) {
        Row row = row(index);
        if (row == null || row.departing()) return;
        row.x = x; row.y = y; row.scale = scale; row.alpha = alpha;
        row.emphasis = emphasis; row.lit = lit; row.blur = blur;
        row.captured = true;
    }

    /** Called before focus/layout rebasing, which must never be mistaken for a singer ending. */
    boolean step(List<LyricLine> lines, int position, long now, boolean playing,
                 boolean seek, boolean animate) {
        if (rows.length != lines.size()) reset(lines.size());
        boolean changed = false;
        if (seek && !seeking || position < previousPosition) {
            for (Row row : rows) changed |= row.departing();
            reset(lines.size());
        }
        for (int i = 0; i < rows.length; i++) {
            Row row = rows[i];
            if (row.departing()) {
                if (!row.finished) {
                    row.finished = !animate || now - row.startedAt >= DURATION_MS;
                    changed = true;
                }
                continue;
            }
            LyricLine line = lines.get(i);
            int start = LyricFocus.startOf(line), end = LyricFocus.endOf(line);
            if ((!playing && !previouslyPlaying) || !animate || seek || !row.captured || end <= start
                    || previousPosition < start || previousPosition >= end || position < end
                    || (long) position - end > MAX_END_LAG_MS) continue;
            row.startedAt = now;
            changed = true;
        }
        previousPosition = position;
        previouslyPlaying = playing;
        seeking = seek;
        return changed;
    }

    boolean moving() {
        for (Row row : rows) if (row.departing() && !row.finished) return true;
        return false;
    }

    /** A new layout cannot reuse pixel poses; preserve completed exits without replaying them. */
    void finishForLayout() {
        for (Row row : rows) {
            if (row.departing()) row.finished = true;
            row.captured = false;
        }
    }
}
