package com.os4.musiccover;

import android.content.Context;

import java.io.File;

/**
 * The files the wallpaper process keeps the cover in, so it can put it back after its own
 * restart: the last JPEG SystemUI handed over, the last raw source it composed from, and the
 * marker saying that source was composed as a card background.
 *
 * Every path is named here and nowhere else. The three used to be spelled out, and deleted as a
 * set, separately in each branch of the "art cleared" path; a file added to the set now only has
 * to be added to {@link #clear}.
 */
final class CoverStore {

    private CoverStore() {
    }

    static final String ART_FILE = "mc_art.jpg";
    /** Where this process keeps the last source, to compose again after its own restart. */
    static final String SRC_FILE = "mc_src.raw";
    static final String CARD_MODE_FILE = "mc_card_mode";
    /** The default path of the video cover when SystemUI does not name one. */
    static final String VIDEO_FILE = "mc_video.mp4";

    static File art(Context c) {
        return new File(c.getFilesDir(), ART_FILE);
    }

    /** Written beside the JPEG, then renamed over it. */
    static File artTmp(Context c) {
        return new File(c.getFilesDir(), ART_FILE + ".tmp");
    }

    static File source(Context c) {
        return new File(c.getFilesDir(), SRC_FILE);
    }

    static File cardMode(Context c) {
        return new File(c.getFilesDir(), CARD_MODE_FILE);
    }

    static File video(Context c) {
        return new File(c.getFilesDir(), VIDEO_FILE);
    }

    /**
     * Forgets the saved cover, so a cold start comes back to the plain wallpaper. The cover
     * videos are not touched: coming back into cover mode on the same album reloads them from
     * the cache instead of encoding again.
     */
    static void clear(Context c) {
        art(c).delete();
        source(c).delete();
        cardMode(c).delete();
    }
}
