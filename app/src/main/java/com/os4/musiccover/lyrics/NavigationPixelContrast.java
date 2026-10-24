package com.os4.musiccover.lyrics;

/** Counts actual navigation pixels; neither Android's theme nor the map app's package is input. */
public final class NavigationPixelContrast {
    private NavigationPixelContrast() {}
    public static final int BLACK = 0xff000000, WHITE = 0xffffffff;

    public static final class Counts {
        public final int bright, dark, total;
        Counts(int bright, int dark, int total) {
            this.bright = bright;
            this.dark = dark;
            this.total = total;
        }
        public int opaque() { return bright + dark; }
        public boolean usable() { return opaque() >= 64 && opaque() >= total * 0.75f; }
        public int preferred() {
            return !usable() || bright == dark ? 0 : bright > dark ? BLACK : WHITE;
        }
        float share(int ink) {
            return opaque() == 0 ? 0f : (ink == BLACK ? bright : dark) / (float) opaque();
        }
    }

    public static Counts count(int[] pixels) {
        if (pixels == null || pixels.length == 0 || pixels.length > 8192)
            return new Counts(0, 0, 0);
        int bright = 0, dark = 0;
        for (int color : pixels) {
            if ((color >>> 24) < 224) continue;
            if (LyricContrastColors.fromBackground(color) == BLACK) bright++;
            else dark++;
        }
        return new Counts(bright, dark, pixels.length);
    }

    /** A real day/night switch needs two dominant samples; moving roads alone do not flip ink. */
    public static final class Stability {
        private int color, pending, confirmations;
        public int color() { return color; }
        public boolean needsConfirmation() { return pending != 0 && confirmations > 0; }
        public int accept(Counts counts) {
            int candidate = counts == null ? 0 : counts.preferred();
            if (candidate == 0) { pending = confirmations = 0; return color; }
            if (color == 0) { color = candidate; return color; }
            if (candidate == color || counts.share(candidate) < 0.60f) {
                pending = confirmations = 0;
                return color;
            }
            confirmations = pending == candidate ? confirmations + 1 : 1;
            pending = candidate;
            if (confirmations >= 2) {
                color = candidate;
                pending = confirmations = 0;
            }
            return color;
        }
        public void reset() { color = pending = confirmations = 0; }
    }
}
