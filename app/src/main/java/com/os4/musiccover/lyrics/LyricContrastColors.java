package com.os4.musiccover.lyrics;

/** Pure color decisions shared by background sampling and the render-time override. */
public final class LyricContrastColors {
    private LyricContrastColors() {}
    private static final int BLACK = 0xff000000, WHITE = 0xffffffff;

    /** Choose whichever opaque ink has greater contrast against the sampled sRGB backdrop. */
    public static int fromBackground(int argb) {
        if ((argb >>> 24) == 0) return 0;
        double luma = 0.2126 * linear((argb >>> 16) & 255)
                + 0.7152 * linear((argb >>> 8) & 255) + 0.0722 * linear(argb & 255);
        return (luma + 0.05) / 0.05 >= 1.05 / (luma + 0.05) ? BLACK : WHITE;
    }

    /** The OEM already selected a foreground: preserve its light/dark direction, not its hue. */
    public static int fromForeground(int argb) {
        if ((argb >>> 24) == 0) return 0;
        double light = 0.2126 * ((argb >>> 16) & 255)
                + 0.7152 * ((argb >>> 8) & 255) + 0.0722 * (argb & 255);
        return light >= 128.0 ? WHITE : BLACK;
    }

    private static double linear(int component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
