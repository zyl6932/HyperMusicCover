package com.os4.musiccover.lyrics;

import org.json.JSONObject;

/** Persisted drawing preferences; defaults reproduce upstream colors and opacity. */
public final class LyricAppearance {
    public static final int OFFICIAL = 0, COVER = 1, CUSTOM = 2;
    /**
     * The translation's own row joined the other two on 2026-11-15. Its official colour is the
     * pale cover tint it was always drawn in, at the 0.62 it always had, so a JSON written
     * before the row existed reads back looking exactly as it did.
     */
    public static final float DEFAULT_TRANS_OPACITY = 0.62f;
    public static final LyricAppearance DEFAULT =
            new LyricAppearance(0, 0, -1, -1, 1f, 0.72f, 0, -1, DEFAULT_TRANS_OPACITY);
    public final int mainMode, bgMode, transMode, mainColor, bgColor, transColor;
    public final float mainOpacity, bgOpacity, transOpacity;

    public LyricAppearance(int mainMode, int bgMode, int mainColor, int bgColor,
                           float mainOpacity, float bgOpacity) {
        this(mainMode, bgMode, mainColor, bgColor, mainOpacity, bgOpacity,
                OFFICIAL, -1, DEFAULT_TRANS_OPACITY);
    }

    public LyricAppearance(int mainMode, int bgMode, int mainColor, int bgColor,
                           float mainOpacity, float bgOpacity,
                           int transMode, int transColor, float transOpacity) {
        this.mainMode = mode(mainMode);
        this.bgMode = mode(bgMode);
        this.transMode = mode(transMode);
        this.mainColor = mainColor | 0xff000000;
        this.bgColor = bgColor | 0xff000000;
        this.transColor = transColor | 0xff000000;
        this.mainOpacity = opacity(mainOpacity, 1f);
        this.bgOpacity = opacity(bgOpacity, 0.72f);
        this.transOpacity = opacity(transOpacity, DEFAULT_TRANS_OPACITY);
    }

    private static int mode(int value) { return value >= OFFICIAL && value <= CUSTOM ? value : OFFICIAL; }
    private static float opacity(float value, float fallback) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : fallback;
    }

    public static LyricAppearance parse(String text) {
        if (text == null || text.isEmpty()) return DEFAULT;
        try {
            JSONObject json = new JSONObject(text);
            return new LyricAppearance(json.optInt("mainMode", 0), json.optInt("bgMode", 0),
                    json.optInt("mainColor", -1), json.optInt("bgColor", -1),
                    (float) json.optDouble("mainOpacity", 1d),
                    (float) json.optDouble("bgOpacity", 0.72d),
                    json.optInt("transMode", OFFICIAL), json.optInt("transColor", -1),
                    (float) json.optDouble("transOpacity", DEFAULT_TRANS_OPACITY));
        } catch (Exception ignored) { return DEFAULT; }
    }

    public String toJson() {
        try {
            return new JSONObject().put("mainMode", mainMode).put("bgMode", bgMode)
                    .put("transMode", transMode)
                    .put("mainColor", mainColor).put("bgColor", bgColor).put("transColor", transColor)
                    .put("mainOpacity", (double) mainOpacity).put("bgOpacity", (double) bgOpacity)
                    .put("transOpacity", (double) transOpacity).toString();
        } catch (Exception ignored) { return "{}"; }
    }
}
