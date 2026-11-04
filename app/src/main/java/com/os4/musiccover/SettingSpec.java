package com.os4.musiccover;

/**
 * What every setting is, as both sides of the query see it: the key it is answered under, its
 * type, its default and its range. Pure constants, shared by the app and the module.
 *
 * Nothing here may name a module class. The app process loads this file too, and anything that
 * reaches Main drags in a class that extends XposedModule - which the app has no libxposed to
 * resolve. The module's half, with the values and what a change does, is {@link Settings}.
 *
 * The query key is one of three names a setting can have, and the only one here. The op an adb
 * command or the app sends and the key the state file keeps are the module's (Settings); they
 * stay as they were even where they differ from this one ("lyrics" is saved as "lyricmaster"),
 * because a renamed file key loses what an old file says and a renamed op breaks adb habits and
 * an app talking to a module of another version.
 */
public final class SettingSpec {

    public enum Kind { BOOL, FLOAT, INT, STRING }

    /** The key in the query's reply. */
    public final String key;
    public final Kind kind;
    public final boolean defBool;
    public final float defFloat;
    public final int defInt;
    public final String defString;
    /** The range a value is clamped to; NaN where nothing is clamped. */
    public final float min, max;

    private SettingSpec(String key, Kind kind, boolean b, float f, int i, String s,
            float min, float max) {
        this.key = key;
        this.kind = kind;
        this.defBool = b;
        this.defFloat = f;
        this.defInt = i;
        this.defString = s;
        this.min = min;
        this.max = max;
    }

    private static SettingSpec bool(String key, boolean def) {
        return new SettingSpec(key, Kind.BOOL, def, 0f, 0, null, Float.NaN, Float.NaN);
    }

    private static SettingSpec real(String key, float def, float min, float max) {
        return new SettingSpec(key, Kind.FLOAT, false, def, 0, null, min, max);
    }

    private static SettingSpec real(String key, float def) {
        return real(key, def, Float.NaN, Float.NaN);
    }

    private static SettingSpec integer(String key, int def, int min, int max) {
        return new SettingSpec(key, Kind.INT, false, 0f, def, null, min, max);
    }

    private static SettingSpec text(String key, String def) {
        return new SettingSpec(key, Kind.STRING, false, 0f, 0, def, Float.NaN, Float.NaN);
    }

    // ---------------------------------------------------------------- the cover

    /** 启用音乐锁屏封面, the master switch. */
    public static final SettingSpec COVER_FEATURE = bool("coverfeature", true);
    /** Follow the media card. Not stored: on at every start. */
    public static final SettingSpec AUTO = bool("auto", true);
    /** Where the sharp band of the composed cover sits, 0 top to 1 bottom. */
    public static final SettingSpec BIAS = real("bias", 0.34f, 0f, 1f);
    /** The cover's shape: 0 full screen, 1 the square card, 2 keep the wallpaper. */
    public static final SettingSpec COVER_STYLE = integer("coverstyle", 0, 0, 2);
    /** The square's share of the room and its place in it. Fixed; reported for the preview. */
    public static final SettingSpec COVER_CARD_FILL = real("covercardfill", 1f);
    public static final SettingSpec COVER_CARD_POS = real("covercardpos", 0.5f);

    // ---------------------------------------------------------------- the clock

    /** The collapsed clock's height, in dp. */
    public static final SettingSpec CLOCK_HEIGHT = real("clock", 36f, 20f, 256f);
    /**
     * The collapsed clock as a fraction of the style's full clock. The module answers what the
     * clock is at, measured, whether or not this was ever set.
     */
    public static final SettingSpec CLOCK_SIZE = real("clocksize", 0.09f, 0.05f, 1f);
    /** The date and clock moved together, in dp, down positive. For the session only. */
    public static final SettingSpec CLOCK_OFFSET = real("clockoff", 0f, -60f, 300f);
    /** The glass clock's end, 0 fully transparent glass. For the session only. */
    public static final SettingSpec GLASS_END = real("glass", 0f, 0f, 1f);
    /** The cover transition's spring response, in seconds. For the session only. */
    public static final SettingSpec SPRING = real("spring", 0.38f, 0.18f, 0.60f);
    /** The full-screen AOD keeps the cover's small clock. */
    public static final SettingSpec AOD_SMALL = bool("aodsmall", true);
    /** Draw the big clock's colon on the styles that drop it. */
    public static final SettingSpec COLON = bool("colon", false);

    // ---------------------------------------------------------------- the media card

    public static final SettingSpec MC_TITLE_TAP = bool("mctap", false);
    public static final SettingSpec MC_PAGE_BUTTON = bool("mcpage", false);
    /** Hide the card's thumbnail, and show it anyway on the lyric page. Both fixed on. */
    public static final SettingSpec MC_HIDE_ART = bool("mcart", true);
    public static final SettingSpec MC_ART_IN_LYRICS = bool("mclyricart", true);
    /** Light the card's progress bar (#49). */
    public static final SettingSpec SEEK_GLOW = bool("seekglow", true);

    // ---------------------------------------------------------------- the transition

    /** Double-tap the lock screen to swap cover and wallpaper. */
    public static final SettingSpec TAP_TOGGLE = bool("tap", true);
    public static final SettingSpec FADE_WALLPAPER = bool("fadewp", true);
    public static final SettingSpec VIDEO_FADE = bool("vcfade", false);
    /** 0 off, 1 hold for the wallpaper window, 2 stretch by the measured gap. */
    public static final SettingSpec FADE_MODE = integer("fsmode2", 1, 0, 2);
    /** Apple Music's motion artwork in place of the still cover, and fetching it on data. */
    public static final SettingSpec MOTION = bool("motion", false);
    public static final SettingSpec MOTION_ON_DATA = bool("motiondata", false);

    // ---------------------------------------------------------------- the fingerprint

    public static final SettingSpec HIDE_FP = bool("hidefp", false);
    /** 0 system default, 1 never avoid the fingerprint icon, 2 always avoid it. */
    public static final SettingSpec FP_AVOID = integer("fpavoid", 0, 0, 2);

    // ---------------------------------------------------------------- the lyrics

    public static final SettingSpec LYRICS = bool("lyrics", true);
    public static final SettingSpec LYRIC_KEEP_ON = bool("lyrickeep", false);
    public static final SettingSpec LYRIC_HDR = bool("lyrichdr", false);
    public static final SettingSpec LYRIC_TRANS = bool("lyrictrans", true);
    public static final SettingSpec LYRIC_ROMA = bool("lyricroma", false);
    public static final SettingSpec LYRIC_ONLINE = bool("lyriconline", true);
    /** 0 left, 1 centre, 2 right. */
    public static final SettingSpec LYRIC_ALIGN = integer("lyricalign", 0, 0, 2);
    /** The lead's and the backing vocal's size, in sp. */
    public static final SettingSpec LYRIC_SIZE = real("lyricsize", 25f);
    public static final SettingSpec LYRIC_BG_SIZE = real("lyricbgsize", 17f);
    /** The band's other four values. Fixed; reported for the preview. */
    public static final SettingSpec LYRIC_FILL = real("lyricfill", 1f);
    public static final SettingSpec LYRIC_POS = real("lyricpos", 0.5f);
    public static final SettingSpec LYRIC_SIDE = real("lyricside", 30f);
    public static final SettingSpec LYRIC_WEIGHT = integer("lyricweight", 500, 100, 900);
    /** Colours and opacity of the lead and the backing vocal, as LyricAppearance's JSON. */
    public static final SettingSpec LYRIC_APPEARANCE = text("lyricappearance", "");

    // ---------------------------------------------------------------- the islands

    /** 「高德公交地铁」. */
    public static final SettingSpec TRANSIT = bool("transit", true);
    /** 取餐码, and drawing its island with the pickup card rather than the focus template. */
    public static final SettingSpec PICKUP = bool("pickup", true);
    /**
     * Answered as "card" until 2026-11-04, the same key as whether the media card is showing,
     * which it overwrote: the app's cardShowing was reading this switch. Its own key now; the
     * state file still keeps it as "card".
     */
    public static final SettingSpec PICKUP_CARD = bool("pickupcard", true);
}
