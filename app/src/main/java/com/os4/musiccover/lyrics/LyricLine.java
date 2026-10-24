package com.os4.musiccover.lyrics;
/**
 * One lyric line, as the renderer draws it. Times are milliseconds from the start of the track.
 *
 * Public only because the parser is Kotlin, and Kotlin refuses to let a public function return a
 * package-private Java type.
 */
public final class LyricLine {
    final String text;
    /** Null when the file has none for this line. */
    final String translation;
    /**
     * How the line is said in Latin letters - romaji, jyutping, a Korean romanisation - or null.
     * Kept apart from the translation because it has a switch of its own (LockLyrics.sRoma);
     * drawn over it when both are on (under()).
     */
    final String roma;
    final int start;
    /** Not final: a background vocal hung under this line can outlast it, and extends it. */
    int end;
    /** The second singer's line in a duet: drawn against the other edge. */
    final boolean opposite;

    /**
     * Word timing, or null for a line-timed file. syllable k covers the characters
     * [charEnd[k-1], charEnd[k]) of text and is sung from sylStart[k] to sylEnd[k].
     */
    final int[] sylStart;
    final int[] sylEnd;
    final int[] charEnd;

    /**
     * The background vocal sung over this line, if the file has one - drawn smaller underneath,
     * with its own word timing. Null for most lines.
     */
    LyricLine bg;
    private LyricLine[] backing = new LyricLine[0];

    LyricLine[] backgrounds() {
        if (bg != null && (backing.length == 0 || backing[0] != bg)) backing = new LyricLine[]{bg};
        return backing;
    }

    void addBackground(LyricLine line) {
        LyricLine[] previous = backgrounds();
        backing = java.util.Arrays.copyOf(previous, previous.length + 1);
        backing[previous.length] = line;
        bg = backing[0];
        end = Math.max(end, line.end);
    }

    void copyBackgrounds(LyricLine other) {
        bg = other.bg;
        backing = other.backgrounds().clone();
    }

    public LyricLine(String text, String translation, int start, int end, boolean opposite,
                     int[] sylStart, int[] sylEnd, int[] charEnd) {
        this(text, translation, null, start, end, opposite, sylStart, sylEnd, charEnd);
    }

    public LyricLine(String text, String translation, String roma, int start, int end,
                     boolean opposite, int[] sylStart, int[] sylEnd, int[] charEnd) {
        this.text = text == null ? "" : text;
        this.translation = translation == null || translation.trim().isEmpty()
                ? null : translation.trim();
        this.roma = roma == null || roma.trim().isEmpty() ? null : roma.trim();
        this.opposite = opposite;
        boolean words = sylStart != null && sylStart.length > 0
                && sylEnd != null && sylEnd.length == sylStart.length
                && charEnd != null && charEnd.length == sylStart.length;
        // Validate the renderer contract once, before any Canvas/StaticLayout work. A bad
        // word map falls back to its intact line text; other lines and backing vocals survive.
        if (words) {
            int previousChar = 0;
            int previousStart = -1;
            for (int k = 0; k < sylStart.length; k++) {
                if (sylStart[k] < previousStart || sylStart[k] < 0 || sylEnd[k] < sylStart[k]
                        || charEnd[k] < previousChar || charEnd[k] > this.text.length()) {
                    words = false;
                    break;
                }
                previousStart = sylStart[k];
                previousChar = charEnd[k];
            }
        }
        // Valid word timing may begin before the enclosing line's declared boundary.
        this.start = words ? Math.min(Math.max(0, start), sylStart[0]) : Math.max(0, start);
        this.end = Math.max(end, this.start);
        this.sylStart = words ? sylStart.clone() : null;
        this.sylEnd = words ? sylEnd.clone() : null;
        this.charEnd = words ? charEnd.clone() : null;
        // A word-timed line is not over while its last word is still being sung, whatever end
        // the file reported. Most word-timed files give a line no end of its own - enhanced LRC
        // is a timestamp per word and nothing else - and lyrics-core reads the line's end off
        // its last syllable, which for the last word is that word's own start; the parser then
        // gives that word the room the file was leaving it. The renderer moves the stack on at
        // this end and stops drawing the word path at it, so an end ahead of the last syllable
        // cuts its fill off mid-sweep: on a line whose next one starts within a second, the
        // last word gets no sweep at all and the line leaves just as it begins.
        if (words) for (int wordEnd : sylEnd) this.end = Math.max(this.end, wordEnd);
    }

    /**
     * How far through the text the singing is at ms, in characters, fractional inside the
     * syllable being sung. 0 before the line, text.length() after it; a line-timed file is
     * all-or-nothing.
     */
    float sungChars(int ms) {
        int n = text.length();
        if (sylStart == null) return ms >= start ? n : 0f;
        if (ms <= sylStart[0]) return 0f;
        int count = sylStart.length;
        for (int k = 0; k < count; k++) {
            int from = k == 0 ? 0 : charEnd[k - 1];
            if (ms < sylStart[k]) return from;
            if (ms < sylEnd[k]) {
                float f = (ms - sylStart[k]) / (float) Math.max(1, sylEnd[k] - sylStart[k]);
                return from + f * (charEnd[k] - from);
            }
        }
        return n;
    }

    boolean hasWords() {
        return sylStart != null;
    }

    /**
     * The text drawn under the line for the switches in mode (LockLyrics.below()): the
     * romanisation over the translation, either alone, or null for nothing.
     */
    String under(int mode) {
        String r = (mode & LockLyrics.BELOW_ROMA) != 0 ? roma : null;
        String t = (mode & LockLyrics.BELOW_TRANS) != 0 ? translation : null;
        if (r == null) return t;
        return t == null ? r : r + "\n" + t;
    }
}
