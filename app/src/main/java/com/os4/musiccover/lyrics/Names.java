package com.os4.musiccover.lyrics;

import java.util.Locale;

/**
 * The one meaning of "these two names are the same song (or singer)".
 *
 * The lyricInfo gate, the Lyricon bridge, the reading of ARTIST and the catalogue scoring each
 * used to carry a comparison of their own - raw containment in one, bracket-stripped equality in
 * another - so a name one of them took was refused by the next (#127: the gate refused the
 * payload the scoring would have passed). They all ask here now.
 */
final class Names {

    private Names() {
    }

    private static final java.util.regex.Pattern BRACKETS =
            java.util.regex.Pattern.compile("\\(.*?\\)|\\[.*?]|\\{.*?\\}");
    private static final java.util.regex.Pattern SPACE = java.util.regex.Pattern.compile("\\s+");

    /** Brackets out, lower case, simplified script, no whitespace at all. */
    static String clean(String input) {
        if (input == null) return "";
        String s = BRACKETS.matcher(input).replaceAll("").trim().toLowerCase(Locale.ROOT);
        return SPACE.matcher(fold(s)).replaceAll("");
    }

    /**
     * NcmLyrics.folded, or the name as it is where that class cannot load - the JVM unit tests,
     * whose android.jar stubs throw from its static initialiser.
     */
    static String fold(String s) {
        try {
            return NcmLyrics.folded(s);
        } catch (Throwable t) {
            return s;
        }
    }

    /**
     * Equal or either containing the other, once cleaned - either side may carry decorations the
     * other does not ("剑心 (电视剧《古剑奇谭》片头曲)" against a payload's "剑心").
     *
     * A name that is nothing but brackets cleans to nothing, and every name contains nothing: those
     * are compared as written instead.
     */
    static boolean same(String a, String b) {
        return same(a, b, false);
    }

    /**
     * As same(), or only outright equality when `exact`: a half of ARTIST cut at a bare hyphen
     * ("A" and "Lin" out of A-Lin) is inside a great many names.
     */
    static boolean same(String a, String b, boolean exact) {
        if (a == null || b == null) return false;
        String x = clean(a);
        String y = clean(b);
        if (x.isEmpty() || y.isEmpty()) {
            x = a.trim().toLowerCase(Locale.ROOT);
            y = b.trim().toLowerCase(Locale.ROOT);
            if (x.isEmpty() || y.isEmpty()) return false;
        }
        return exact ? x.equals(y) : x.equals(y) || x.contains(y) || y.contains(x);
    }
}
