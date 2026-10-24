package com.os4.musiccover;

/** An expanded card must not dismiss the background of a still-requested capsule lyric page. */
final class MediaLyricEntryPolicy {
    enum Artwork { RESUME_LYRICS, EXIT_COVER, ENTER_COVER }
    static Artwork artwork(boolean capsuleLyricsRequested, boolean coverVisible) {
        return capsuleLyricsRequested ? Artwork.RESUME_LYRICS
                : coverVisible ? Artwork.EXIT_COVER : Artwork.ENTER_COVER;
    }
    static boolean restoreMusic(boolean coverEnabled, boolean navigationRequested) {
        return coverEnabled && !navigationRequested;
    }
}
