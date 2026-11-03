package com.os4.musiccover.lyrics;

/**
 * A song's or a singer's name tidied for a search: the credits a storefront hangs on a title,
 * and QQ 音乐's bracketed second names.
 *
 * Which song a session is playing - the players that sing into TITLE and spell the song into
 * ARTIST - is TrackIdentity's.
 */
public final class TrackName {

    private TrackName() {
    }

    /**
     * A title without the credits a storefront hangs on its end - "(feat. ...)", "(with ...)",
     * "[feat. ...]" - which no catalogue spells the same way and which drowned the name in the
     * search: "セカイツナガレ (feat. Liko(CV:Minori Suzuki) & Roy(CV:Yuka Terasaki))" found nothing
     * (2026-10-06). Cut at the first of them, nested brackets and all; a title that is nothing
     * but one is left alone.
     */
    static String undecorated(String title) {
        String low = title.toLowerCase();
        int cut = -1;
        for (String mark : new String[]{"(feat", "(ft.", "(with ", "[feat", "[with ", "（feat"}) {
            int at = low.indexOf(mark);
            if (at > 0 && (cut < 0 || at < cut)) cut = at;
        }
        if (cut <= 0) return title;
        String head = title.substring(0, cut).trim();
        return head.isEmpty() ? title : head;
    }

    /**
     * QQ 音乐's names with their other name in brackets after them: a Japanese title with its
     * Chinese one ("生きていたんだよな (她曾活过啊)"), a singer with the name they go by at home
     * ("爱缪 (あいみょん)", "Daoko (ダヲコ)/米津玄師 (よねづ けんし)"). Searched as published, no
     * catalogue found the song - QQ's own included - and the scoring, which drops brackets, kept
     * "爱缪" and threw away the あいみょん that NetEase credits (2026-10-07).
     *
     * {name, alias} when the text ends in one bracketed part with a name before it, else null.
     */
    static String[] aliased(String s) {
        if (s == null) return null;
        s = s.trim();
        int n = s.length();
        if (n < 4) return null;
        char close = s.charAt(n - 1);
        char open = close == ')' ? '(' : close == (char) 0xFF09 ? (char) 0xFF08 : 0;
        if (open == 0) return null;
        int at = s.lastIndexOf(open);
        if (at <= 0) return null;
        String name = s.substring(0, at).trim();
        String alias = s.substring(at + 1, n - 1).trim();
        if (name.isEmpty() || alias.isEmpty() || alias.indexOf(close) >= 0) return null;
        return new String[]{name, alias};
    }

    /**
     * A title's bracketed translation taken off, for a search: only a Chinese one after a name
     * written in something else. "(Live)", "(粤语版)" after a Chinese title and the like are
     * which recording it is and stay.
     */
    public static String untranslated(String title) {
        String[] a = aliased(title);
        if (a == null || !allHan(a[1]) || allHan(a[0])) return title;
        return a[0];
    }

    /** A singer's bracketed alias taken off, for a search: the name QQ 音乐 itself lists them by. */
    public static String unaliased(String artist) {
        String[] a = aliased(artist);
        return a == null ? artist : a[0];
    }

    private static boolean allHan(String s) {
        boolean any = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return false;
                any = true;
            }
        }
        return any;
    }
}
