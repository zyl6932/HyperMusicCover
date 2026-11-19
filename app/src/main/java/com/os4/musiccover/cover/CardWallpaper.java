package com.os4.musiccover.cover;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.os4.musiccover.Xp;

/**
 * The user's own lock wallpaper, read as a picture the square card's background can be composed
 * from - the second answer to "what is behind the card".
 *
 * The first answer is the album cover, blurred, which is what every other style shows and what
 * the card did before the switch existed. This one is the wallpaper itself: read from the system,
 * handed to the wallpaper process as if it were the cover's source, and blurred over there by the
 * very same code that blurs the cover (CoverCompose.cardBackground, radius 48, 4 passes, the same
 * 8% wash). Nothing about the blur is decided here - only which picture it is applied to, so the
 * two backgrounds look equally soft by construction rather than by a number kept in step.
 *
 * Read on the push worker, never on the main thread: getDrawable decodes the wallpaper and can
 * take tens of milliseconds, and the clock is springing while this runs.
 *
 * Why not keep the wallpaper untouched and blur it in the shader (the other route): that needs
 * the wallpaper's GL program hooked and a multi-tap sampling pass written into it, on every
 * device that draws a keyguard texture, for a look that is static. Composing a blurred copy on
 * the CPU costs one picture per track and leaves the OEM's GL alone.
 */
public final class CardWallpaper {
    private CardWallpaper() {
    }

    /**
     * The source is handed over as raw pixels to the other process, so its size is a write and a
     * read, not a quality decision: the background is a heavy blur of it either way, and the
     * composition blurs at quarter size regardless. Measured covers are 512x512; this is a few
     * times bigger and still a couple of megabytes rather than the twelve a full-screen read
     * would be.
     */
    private static final int SRC_W = 540;

    /**
     * How long a read is kept. The wallpaper can be changed while a song plays - a theme, the
     * gallery's own set-as-wallpaper - and a picture kept for the life of the process would show
     * the old one until SystemUI restarted. A minute is short enough for that to be noticed and
     * long enough that every track change is not a fresh decode.
     */
    private static final long CACHE_MS = 60_000L;

    private static Bitmap sCache;
    private static int sCacheW = -1;
    private static long sCacheAt;

    /**
     * The lock wallpaper fitted to the screen, or null when the system will not hand one over -
     * a live wallpaper, a build that refuses the read, a wallpaper still being set. Null is not
     * a failure: the caller falls back to the blurred cover, which is what the card showed
     * before this switch existed.
     */
    public static synchronized Bitmap forScreen(Context ctx, int w, int h) {
        long now = android.os.SystemClock.uptimeMillis();
        if (sCache != null && !sCache.isRecycled() && sCacheW == w
                && now - sCacheAt < CACHE_MS) {
            return sCache;
        }
        Bitmap made = null;
        try {
            made = read(ctx, w, h);
        } catch (Throwable t) {
            // A wallpaper that cannot be read is one card background that falls back, not a
            // process to take down: this runs on the push worker.
            Xp.w("card wallpaper unreadable, the card keeps the blurred cover: " + t);
        }
        if (made == null) return null;
        sCache = made;
        sCacheW = w;
        sCacheAt = now;
        return made;
    }

    private static Bitmap read(Context ctx, int w, int h) {
        WallpaperManager wm = WallpaperManager.getInstance(ctx);
        Drawable d = null;
        try {
            d = wm.getDrawable(WallpaperManager.FLAG_LOCK);
        } catch (Throwable t) {
            Xp.w("card wallpaper: the lock slot was refused: " + t);
        }
        if (d == null) {
            try {
                // No separate lock wallpaper: the lock screen shows the desktop's, so that is
                // the picture the user is looking at behind the card.
                d = wm.getDrawable();
            } catch (Throwable t) {
                Xp.w("card wallpaper: no wallpaper drawable: " + t);
            }
        }
        if (d == null) return null;

        Bitmap whole = null;
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled() && b.getWidth() > 0 && b.getHeight() > 0) whole = b;
        }
        if (whole == null) {
            // A drawable with no bitmap behind it - a colour, a shape, an animated one. Drawing
            // it is the only way to get pixels, and the size is the source's, so it is fitted
            // below like any other.
            int iw = Math.max(1, d.getIntrinsicWidth());
            int ih = Math.max(1, d.getIntrinsicHeight());
            Bitmap made = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(made);
            d.setBounds(0, 0, iw, ih);
            d.draw(cv);
            whole = made;
        }
        return fit(whole, w, h);
    }

    /**
     * Centre-cropped to the screen's aspect and scaled to {@link #SRC_W}: the background fills
     * the screen, so nothing but the middle of the wallpaper is ever visible anyway.
     */
    private static Bitmap fit(Bitmap src, int w, int h) {
        if (w <= 0 || h <= 0) return src;
        float target = w / (float) h;
        int sw = src.getWidth(), sh = src.getHeight();
        int cw = sw, ch = Math.max(1, Math.round(sw / target));
        if (ch > sh) {
            ch = sh;
            cw = Math.max(1, Math.round(sh * target));
        }
        int x = (sw - cw) / 2, y = (sh - ch) / 2;
        int outW = Math.min(SRC_W, cw);
        int outH = Math.max(1, Math.round(outW / target));
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(src, new Rect(x, y, x + cw, y + ch),
                new RectF(0, 0, outW, outH), p);
        return out;
    }
}
