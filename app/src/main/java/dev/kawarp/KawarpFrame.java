package dev.kawarp;

import android.graphics.BitmapShader;

/** One immutable animation sample shared by the lock-screen wash and its card backdrops. */
public final class KawarpFrame {
    final BitmapShader previous;
    final BitmapShader current;
    final float time, blend, warp, saturation, dither, scale, brightness, contrast;

    KawarpFrame(BitmapShader previous, BitmapShader current, float time, float blend,
                float warp, float saturation, float dither, float scale,
                float brightness, float contrast) {
        this.previous = previous;
        this.current = current;
        this.time = time;
        this.blend = blend;
        this.warp = warp;
        this.saturation = saturation;
        this.dither = dither;
        this.scale = scale;
        this.brightness = brightness;
        this.contrast = contrast;
    }
}
