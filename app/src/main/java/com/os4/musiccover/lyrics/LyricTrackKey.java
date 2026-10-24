package com.os4.musiccover.lyrics;

/** Track identity for lyric loading, independent of whether automatic covers have run. */
final class LyricTrackKey {
    private LyricTrackKey() {}

    static String fromMetadata(String cardKey, String packageName, String title,
                               String artist, String album, long duration) {
        String fallback = cardKey == null ? "" : cardKey;
        if (packageName == null || packageName.isEmpty()) return fallback;
        // Preserve the existing stable identity for players which put current lyrics in TITLE.
        if (artist != null || album != null || duration > 0) {
            return packageName + "|" + artist + "|" + album + "|" + duration;
        }
        if (!fallback.isEmpty()) return fallback;
        // A manual entry can be the first request: there may be no cover's cached card key.
        // A session with a real title still supplies enough evidence to start the lookup.
        return title == null || title.trim().isEmpty() ? "" : packageName + "|" + title;
    }
}
