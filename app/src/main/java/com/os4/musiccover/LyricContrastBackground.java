package com.os4.musiccover;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import com.os4.musiccover.immersive.ImmersiveHost;
import com.os4.musiccover.lyrics.LockLyrics;
import com.os4.musiccover.lyrics.LyricContrastColors;

/** Runtime background observations; never changes the user's appearance preferences. */
public final class LyricContrastBackground {
    private LyricContrastBackground() {}

    public static final class Reading {
        public final String key, source;
        public final int color;
        Reading(String key, int color, String source) {
            this.key = key;
            this.color = color;
            this.source = source;
        }
    }

    private static final class CoverSample {
        final String identity;
        final int mode, sharp, frost;
        final boolean measuredFrost;
        CoverSample(String identity, int mode, int sharp, int frost, boolean measuredFrost) {
            this.identity = identity;
            this.mode = mode;
            this.sharp = sharp;
            this.frost = frost;
            this.measuredFrost = measuredFrost;
        }
    }

    private static volatile CoverSample sCover;
    private static volatile Reading sWallpaper = new Reading("unknown", 0, "unknown");
    private static volatile boolean sWallpaperReading;
    private static long sWallpaperReadAt;
    private static Reading sReading = new Reading("unknown", 0, "unknown");

    /** Main thread: a stable target background, not each intermediate crossfade frame. */
    static Reading current() {
        Context context = Main.sAppCtx;
        if (context == null) {
            NavigationSurfaceContrast.stop();
            return remember("unknown", 0, "unknown");
        }
        // Plain AOD is black; the held/full-screen AOD dims the scene. Never carry the lit
        // page's frozen black ink into either, and never interpret coverTint()==0 as black.
        if (!Main.screenOnCached()) {
            NavigationSurfaceContrast.stop();
            return remember("aod:" + ClockCollapse.aodHeld() + ":" + ClockCollapse.aodFullScreen(),
                    Color.WHITE, "aod-neutral");
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String navigation = ImmersiveHost.requestedNavigationKey();
        if (navigation != null) {
            Reading actual = NavigationSurfaceContrast.current(navigation);
            return remember(actual.key, actual.color, actual.source);
        }
        NavigationSurfaceContrast.stop();
        if (Main.coverModeOn() && !Main.keepsWallpaper()) {
            CoverSample sample = sCover;
            int mode = Main.sCoverCardStyle.mode;
            boolean frost = LockLyrics.blurWanted();
            if (sample == null || sample.mode != mode) {
                return remember("cover:pending:" + mode + ":" + frost, 0, "cover-pending");
            }
            int ink = frost ? sample.frost : sample.sharp;
            return remember("cover:" + sample.identity + ":" + frost, ink,
                    ink == 0 ? "cover-pending" : !frost ? "cover-region" : sample.measuredFrost
                            ? "cover-frost-region" : "cover-frost-estimate");
        }

        readWallpaperSoon(context);
        Reading wallpaper = sWallpaper;
        boolean dim = Main.lyricsDimWanted();
        int ink = 0;
        // This is the existing captured date view: no tree walk, OEM reflection, binder call
        // or assumption about the poorly named setBrightness boolean on a drawing frame.
        View date = ClockViews.sDateView;
        if (date instanceof TextView && date.isAttachedToWindow() && date.isShown()) {
            int actual = ((TextView) date).getCurrentTextColor();
            if (Color.alpha(actual) > 0) ink = LyricContrastColors.fromForeground(actual);
        }
        String source = "system-clock";
        if (ink == 0) {
            ink = wallpaper.color == 0 ? 0 : LyricContrastColors.fromBackground(
                    dim ? darken(wallpaper.color, 0.75f) : wallpaper.color);
            source = ink == 0 ? "unknown" : "wallpaper-palette";
        }
        return remember("wallpaper:" + wallpaper.key + ":" + night + ":dim=" + dim,
                ink, source);
    }

    private static Reading remember(String key, int color, String source) {
        Reading old = sReading;
        if (!key.equals(old.key) || color != old.color || !source.equals(old.source)) {
            sReading = new Reading(key, color, source);
        }
        return sReading;
    }

    /** Public wallpaper APIs on the existing worker, at most once per five seconds. */
    private static void readWallpaperSoon(Context context) {
        long now = SystemClock.uptimeMillis();
        if (sWallpaperReading || (sWallpaperReadAt != 0L && now - sWallpaperReadAt < 5000L)) return;
        sWallpaperReadAt = now;
        sWallpaperReading = true;
        Main.worker().post(() -> {
            try {
                WallpaperManager manager = WallpaperManager.getInstance(context);
                int which = WallpaperManager.FLAG_LOCK;
                int id = manager.getWallpaperId(which);
                if (id < 0) {
                    which = WallpaperManager.FLAG_SYSTEM;
                    id = manager.getWallpaperId(which);
                }
                WallpaperColors colors = manager.getWallpaperColors(which);
                int primary = colors == null ? 0 : colors.getPrimaryColor().toArgb();
                sWallpaper = new Reading(which + ":" + id, primary, "wallpaper-palette");
            } catch (Throwable ignored) {
                // Some ROMs deny palette access. A real clock color remains usable; no
                // guessed wallpaper or album color is advertised as a measured replacement.
            } finally {
                sWallpaperReading = false;
            }
        });
    }

    /** Publish the composition identity before its pixels are ready; one stable pending key. */
    static synchronized void beginCover(String identity, int mode) {
        CoverSample old = sCover;
        if (old == null || !identity.equals(old.identity) || mode != old.mode) {
            sCover = new CoverSample(identity, mode, 0, 0, false);
        }
    }

    /** Worker only. Samples an already composed picture, never allocates another bitmap. */
    static void measureCover(Bitmap sharp, Bitmap frost, String identity, int mode) {
        try {
            int region = average(sharp);
            if (region == 0) return;
            int frosted = frost == null ? frostEstimate(region) : average(frost);
            boolean measured = frost != null && frosted != 0;
            if (frosted == 0) frosted = frostEstimate(region);
            synchronized (LyricContrastBackground.class) {
                CoverSample target = sCover;
                if (target == null || !identity.equals(target.identity) || mode != target.mode) return;
                sCover = new CoverSample(identity, mode, LyricContrastColors.fromBackground(region),
                        LyricContrastColors.fromBackground(frosted), measured);
            }
        } catch (Throwable ignored) {
            // The sample is auxiliary. A recycled/unreadable image cannot fail a cover push.
        }
    }

    static synchronized void clearCover() { sCover = null; }

    /** The central/lower lyric area, excluding the top clock and bottom controls. */
    private static int average(Bitmap image) {
        if (image == null || image.isRecycled()) return 0;
        int w = image.getWidth(), h = image.getHeight();
        if (w <= 0 || h <= 0) return 0;
        long red = 0L, green = 0L, blue = 0L;
        int count = 0;
        for (int row = 0; row < 18; row++) {
            int y = Math.min(h - 1, (int) (h * (0.25f + 0.55f * (row + 0.5f) / 18f)));
            for (int col = 0; col < 16; col++) {
                int x = Math.min(w - 1, (int) (w * (0.08f + 0.84f * (col + 0.5f) / 16f)));
                int color = image.getPixel(x, y);
                if (Color.alpha(color) < 128) continue;
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                count++;
            }
        }
        return count == 0 ? 0 : Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
    }

    /** Same saturation/dark wash as CoverCompose.colouredFrost, without another blur job. */
    private static int frostEstimate(int color) {
        float r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        float grey = 0.213f * r + 0.715f * g + 0.072f * b;
        float wash = 1f - 0x61 / 255f;
        return Color.rgb(channel(channel(grey + (r - grey) * 1.3f) * wash),
                channel(channel(grey + (g - grey) * 1.3f) * wash),
                channel(channel(grey + (b - grey) * 1.3f) * wash));
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }

    private static int darken(int color, float gain) {
        return Color.rgb(channel(Color.red(color) * gain), channel(Color.green(color) * gain),
                channel(Color.blue(color) * gain));
    }
}
