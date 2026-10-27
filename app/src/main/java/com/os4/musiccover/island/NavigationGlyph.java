package com.os4.musiccover.island;

/** Conservative pixel classification: never recolor a photo, opaque tile or multicolor logo. */
final class NavigationGlyph {
    private NavigationGlyph() { }

    static boolean monochrome(int[] pixels) {
        if (pixels == null || pixels.length == 0) return false;
        int visible = 0, clear = 0;
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            if (alpha < 32) { clear++; continue; }
            if (alpha < 128) continue;
            visible++;
            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 16) return false;
        }
        return visible >= 4 && clear >= pixels.length / 6;
    }

    static int[] recolor(int[] pixels, int ink) {
        int[] result = pixels.clone();
        for (int i = 0; i < result.length; i++) result[i] = (result[i] & 0xff000000) | (ink & 0xffffff);
        return result;
    }
}
