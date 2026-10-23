package com.os4.musiccover.lyrics;

import java.util.Locale;

/** The directory's format choice is independent of the order of lyric sources. */
public final class LocalLyricFormat {
    public static final String TTML = "ttml";
    public static final String LRC = "lrc";

    private LocalLyricFormat() { }

    public static String normalize(String value) {
        return value != null && LRC.equals(value.trim().toLowerCase(Locale.ROOT)) ? LRC : TTML;
    }

    /** Only for importing old settings. A new key always wins, regardless of file key order. */
    public static String migrate(String selected, String legacyOrder) {
        if (selected != null) return normalize(selected);
        if (legacyOrder != null) {
            for (String item : legacyOrder.split(",")) {
                String value = item.trim().toLowerCase(Locale.ROOT);
                if (TTML.equals(value) || LRC.equals(value)) return value;
            }
        }
        return TTML;
    }

    public static String[] formats(String selected) {
        // Preserve the other previously supported formats as fixed, last-resort fallbacks.
        return LRC.equals(normalize(selected))
                ? new String[] {LRC, TTML, "yrc", "krc", "lys"}
                : new String[] {TTML, LRC, "yrc", "krc", "lys"};
    }
}
