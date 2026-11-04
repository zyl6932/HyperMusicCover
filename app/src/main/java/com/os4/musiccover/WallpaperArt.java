package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;

/**
 * The cover's picture in this process: composing it from the raw source SystemUI hands over (and
 * keeping a composition around for a quick way back), the frosted copy the lyrics page blurs to,
 * whether the lyric blur is wanted, and the JPEG and source kept on disk for a cold start.
 *
 * Moved out of WallpaperProbe as it was (2026-11-04): every member whole with its comments,
 * references across the line prefixed with WallpaperProbe. or WallpaperArt., private dropped
 * where the other side needs it, and the static synchronized methods (the frosted cache and
 * composer) kept on WallpaperProbe.class explicitly so they still share the one lock with
 * register and videoWorker. Nothing else changed.
 */
final class WallpaperArt {

    private WallpaperArt() {
    }

    private static Bitmap sFrosted, sFrostedOf;
    private static Bitmap sFrosted2, sFrostedOf2;

    /**
     * How long the desktop stays swapped without being told again.
     *
     * The message that ends it is a broadcast, and a broadcast has no delivery guarantee. This is
     * the only thing standing between a lost one and the user's home screen showing an album
     * cover indefinitely, so it exists, and it is generous enough that an ordinary pull-down -
     * which is seconds long - is refreshed many times over.
     *
     * The cost of expiring early is small by construction: SystemUI draws the cover inside the
     * shade window as well, so the background still looks right; only the glass goes back to
     * sampling the real wallpaper.
     */
    private static final long SHADE_TTL_MS = 10000L;

    /** The whole of a file, or null. Used for the art SystemUI hands over by path. */
    static byte[] readBytes(String path) {
        try {
            java.io.File f = new java.io.File(path);
            byte[] buf = new byte[(int) f.length()];
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.FileInputStream(f));
            try {
                in.readFully(buf);
            } finally {
                in.close();
            }
            return buf;
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + "could not read " + path + ": " + t);
            return null;
        }
    }

    static Bitmap frostedIfWanted(Bitmap sharp) {
        return sharp == null || !WallpaperProbe.sLyricBlur ? sharp : frostedOf(sharp);
    }

    /**
     * The frosted copy of one fitted picture, made once. Called off the GL thread first.
     *
     * Two are kept, because a track change needs both ends of its crossfade at the same time -
     * the song going out and the one coming in. With one slot, the incoming picture evicted the
     * outgoing one and the fade paid for it a second time, which is the cost this is here to
     * avoid in the first place.
     */
    private static Bitmap frostedOf(Bitmap sharp) {
        synchronized (WallpaperProbe.class) {
            if (sFrosted != null && sFrostedOf == sharp && !sFrosted.isRecycled()) return sFrosted;
            if (sFrosted2 != null && sFrostedOf2 == sharp && !sFrosted2.isRecycled()) {
                // Answered from the older slot: make it the newer one, so the next picture evicts
                // whichever of the two has gone longest without being asked for.
                Bitmap f = sFrosted2, of = sFrostedOf2;
                sFrosted2 = sFrosted;
                sFrostedOf2 = sFrostedOf;
                sFrosted = f;
                sFrostedOf = of;
                return f;
            }
            long t0 = SystemClock.uptimeMillis();
            // The kept composition's own frosting when this is it: a stretch, where frosted() is a
            // full-screen blur (783ms measured on a busy phone, the lyrics switched on in the cover).
            Composed kept = WallpaperProbe.sComposed;
            Bitmap f = kept != null && kept.sharp == sharp
                    ? frostOf(kept, sharp.getWidth(), sharp.getHeight()) : CoverCompose.frosted(sharp);
            cacheFrosted(sharp, f);
            Xp.log(WallpaperProbe.TAG + "frosted " + WallpaperProbe.describe(sharp) + " in " + (SystemClock.uptimeMillis() - t0)
                    + "ms");
            return f;
        }
    }

    /** Puts a frosted copy in the newer slot, keyed on the sharp picture it stands in for. */
    private static void cacheFrosted(Bitmap sharp, Bitmap frosted) {
        synchronized (WallpaperProbe.class) {
            if (frosted == null) return;
            sFrosted2 = sFrosted;
            sFrostedOf2 = sFrostedOf;
            sFrosted = frosted;
            sFrostedOf = sharp;
        }
    }

    /**
     * Lets go of both frosted copies. Not recycled: a fade in the air holds its own reference to
     * whichever one it is drawing from, and dropping ours only means the next one is made again.
     * Called when the cover goes, where two screen-sized bitmaps are pure cost.
     */
    static void dropFrosted() {
        synchronized (WallpaperProbe.class) {
            sFrosted = null;
            sFrostedOf = null;
            sFrosted2 = null;
            sFrostedOf2 = null;
        }
    }

    static Bitmap sharpFittedArt() {
        Bitmap art = WallpaperProbe.sArt;
        if (art == null) return null;
        int targetW = WallpaperProbe.sReportedW;
        int targetH = WallpaperProbe.sReportedH;
        if (targetW <= 0 || targetH <= 0) {
            Context ctx = WallpaperProbe.sCtx;
            if (ctx != null) {
                targetW = ctx.getResources().getDisplayMetrics().widthPixels;
                targetH = ctx.getResources().getDisplayMetrics().heightPixels;
            }
        }
        if (targetW <= 0 || targetH <= 0) return null;
        if (art.getWidth() == targetW && art.getHeight() == targetH) return art;
        Bitmap cached = WallpaperProbe.sFitted;
        if (cached != null && WallpaperProbe.sFittedOf == art
                && cached.getWidth() == targetW && cached.getHeight() == targetH) {
            return cached;
        }
        Bitmap fitted = centerCrop(art, targetW, targetH);
        WallpaperProbe.sFitted = fitted;
        WallpaperProbe.sFittedOf = art;
        return fitted;
    }

    static void saveArtLater(final Context ctx, final byte[] jpg) {
        WallpaperProbe.sArtSaving.incrementAndGet();
        Gen.thread("mc-art-save", () -> {
            try {
                saveArt(ctx, jpg);
            } finally {
                WallpaperProbe.sArtSaving.decrementAndGet();
            }
        }, WallpaperProbe.sArtSaving::decrementAndGet);
    }

    private static void saveArt(Context ctx, byte[] jpg) {
        // Beside it, then renamed: killed half way, the process came back to a truncated JPEG.
        File tmp = CoverStore.artTmp(ctx);
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(jpg);
            }
            if (!tmp.renameTo(CoverStore.art(ctx))) {
                throw new java.io.IOException("rename failed");
            }
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "saveArt failed: " + t);
        }
    }

    /**
     * The texture is only read when the GL surface is created, i.e. at process start, so the art
     * has to be on disk here before that happens - a bitmap pushed later would not be uploaded
     * until something recreated the surface.
     */
    static void loadArt(Context ctx) {
        File f = CoverStore.art(ctx);
        // The source, when the last cover came that way: the JPEG is deleted as soon as a
        // composed-here cover is shown, so whichever of the two is newer is the one on screen.
        File src = CoverStore.source(ctx);
        WallpaperProbe.sCardMode = CoverStore.cardMode(ctx).exists();
        if (src.exists() && (!f.exists() || src.lastModified() >= f.lastModified())) {
            try {
                CoverCompose.Source s = CoverCompose.readSource(src);
                if (s != null) {
                    WallpaperProbe.sArt = WallpaperProbe.sCardMode ? CoverCompose.cardBackground(s.src, s.w, s.h)
                            : CoverCompose.composeWallpaper(s.src, s.w, s.h, s.bias);
                    WallpaperProbe.sAsks = 0;
                    Xp.log(WallpaperProbe.TAG + "art restored from the saved source " + WallpaperProbe.describe(WallpaperProbe.sArt));
                    return;
                }
            } catch (Throwable t) {
                Xp.log(WallpaperProbe.TAG + "the saved source could not be composed: " + t);
            }
        }
        if (!f.exists()) return;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) {
            WallpaperProbe.sArt = b;
            WallpaperProbe.sAsks = 0;
            Xp.log(WallpaperProbe.TAG + "art restored from disk " + WallpaperProbe.describe(b));
        }
    }

    /** Fills w x h from the source without distorting it, the way CENTER_CROP would. */
    static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        cv.drawBitmap(src, null, new RectF((w - dw) / 2f, (h - dh) / 2f,
                (w + dw) / 2f, (h + dh) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /**
     * Takes a blur answer from SystemUI, unless a later one has already been taken. Answers
     * whether this one was the later.
     *
     * The place in line rides with the answer because the two messages that can carry one are
     * built and sent from different threads over there: the cover push is composed and sent on
     * the worker while the answer itself is decided on the main thread. The one that arrives last
     * is therefore not the one that was decided last, and the cover is the slow half - it is read
     * first and sent after - so it is the one that arrives holding the stale answer. Taken at
     * face value that answer undoes the switch that came after it, and nothing re-sends, which is
     * a song playing out sharp under its lyrics.
     */
    static boolean takeBlurDecision(long seq, boolean on, String via) {
        if (seq != 0L && seq < WallpaperProbe.sBlurSeq) {
            Xp.log(WallpaperProbe.TAG + "the " + via + " carried an answer made before the one in hand ("
                    + seq + " against " + WallpaperProbe.sBlurSeq + "), leaving the cover "
                    + (WallpaperProbe.sLyricBlurWant ? "frosted" : "sharp"));
            return false;
        }
        if (seq > WallpaperProbe.sBlurSeq) WallpaperProbe.sBlurSeq = seq;
        if (WallpaperProbe.sLyricBlurWant != on) {
            WallpaperProbe.sLyricBlurWant = on;
            Xp.log(WallpaperProbe.TAG + "the " + via + " asks for a " + (on ? "frosted" : "sharp")
                    + " cover (decided at " + seq + ")");
        }
        return true;
    }

    /**
     * Frosts the cover for the lyrics, or clears it. The blur is made on the composer thread; the
     * fade waits for one already in the air - the cover arriving, a track changing - to land
     * first, because a new fade starts from the picture it is handed, not from what is on screen.
     */
    static boolean lyricBlurWanted() {
        return WallpaperProbe.sLyricBlurWant;
    }

    static void setLyricBlur(final boolean on) {
        WallpaperProbe.sLyricBlurWant = on;
        WallpaperVideo.setFrost(on);
        composer().post(new Runnable() {
            @Override
            public void run() {
                // Warming the still path's frosted copy. A live wallpaper's cover is frosted by
                // the video worker instead, and this was 110-150ms on the composer - the thread
                // the next cover's decode waits on - for a picture nothing reads.
                Bitmap sharp = VideoTakeover.videoPath() ? null : sharpFittedArt();
                if (on && sharp != null) frostedOf(sharp);
                final long t0 = SystemClock.uptimeMillis();
                final Runnable settle = new Runnable() {
                    @Override
                    public void run() {
                        // Overtaken while this one was waiting out a fade. The wait re-posts,
                        // so a newer switch reaches the main thread first and this one would
                        // otherwise land afterwards and undo it.
                        if (on != WallpaperProbe.sLyricBlurWant) return;
                        boolean video = VideoTakeover.videoPath();
                        if (on == WallpaperProbe.sLyricBlur) {
                            // Already frosted - carried in on the cover itself.
                            if (on && !video) WallpaperProbe.tellBlurShown("already frosted");
                            return;
                        }
                        if ((WallpaperProbe.sFade != null || WallpaperProbe.sGpuFade != null)
                                && SystemClock.uptimeMillis() - t0 < 1500L) {
                            Gen.postDelayed(this, 30L);
                            return;
                        }
                        // The lyrics wait for this before they come in, so that the two come in
                        // together (LockLyrics.blurSettled). Told as the fade starts, not as it
                        // ends: the lyrics have a fade of their own.
                        if (on && !video) {
                            WallpaperProbe.tellBlurShown(WallpaperProbe.sArt == null ? "no cover to frost" : "frosting");
                        }
                        // On the video path `from` is only the crossfade's other end, and
                        // without a crossfade it would be a frosting on the main thread for
                        // nothing.
                        Bitmap from = video && !VideoTakeover.sVideoFade ? null : WallpaperProbe.fittedArt();
                        WallpaperProbe.sLyricBlur = on;
                        Bitmap to = video ? null : WallpaperProbe.fittedArt();
                        Xp.log(WallpaperProbe.TAG + "lyric blur " + (on ? "on" : "off") + " (waited "
                                + (SystemClock.uptimeMillis() - t0) + "ms)");
                        if (WallpaperProbe.sArt == null) return;
                        if (video) {
                            VideoTakeover.sFadeFrom = from;
                            VideoTakeover.videoWindowTakeover(false);
                            return;
                        }
                        if (from != null && to != null) WallpaperFade.startFade(from, to, null);
                        else WallpaperEngines.reloadTexture();
                    }
                };
                Gen.post(settle);
            }
        });
    }

    private static Handler sComposer;

    private static Handler composer() {
        synchronized (WallpaperProbe.class) {
            if (sComposer == null) {
                android.os.HandlerThread t = Gen.handlerThread("mc-compose",
                        android.os.Process.THREAD_PRIORITY_DISPLAY);
                sComposer = new Handler(t.getLooper());
            }
            return sComposer;
        }
    }

    /**
     * Composes the cover here from the source SystemUI handed over, then applies it.
     *
     * Off the main thread, because the main thread is where the GPU fade's frames are asked
     * for, and a fast skip lands this while the previous track's fade is still running.
     */
    static void composeFromSource(final Context c, final String path,
                                          final boolean reload, final boolean fade,
                                          final boolean cardMode, final String preKey) {
        final int seq = ++WallpaperProbe.sSrcSeq;
        final long t0 = SystemClock.uptimeMillis();
        WallpaperProbe.sComposing.incrementAndGet();
        composer().post(new Runnable() {
            @Override
            public void run() {
                if (seq != WallpaperProbe.sSrcSeq) {
                    WallpaperProbe.sComposing.decrementAndGet();
                    return;
                }
                final Bitmap b;
                final long read, composed;
                final boolean kept;
                try {
                    CoverCompose.Source s = CoverCompose.readSource(new File(path));
                    if (s == null) {
                        Xp.log(WallpaperProbe.TAG + "source at " + path + " is not one this build reads");
                        WallpaperProbe.sComposing.decrementAndGet();
                        return;
                    }
                    read = SystemClock.uptimeMillis();
                    WallpaperProbe.sTmRead = read;
                    if (seq != WallpaperProbe.sSrcSeq) {
                        WallpaperProbe.sComposing.decrementAndGet();
                        return;
                    }
                    long key = CoverCompose.sourceKey(s, cardMode);
                    Composed hit = composedFor(key);
                    kept = hit != null;
                    Composed made = kept ? hit : compose(s, cardMode, key);
                    // SystemUI's name for it, which the next tap asks for (applyKept).
                    if (preKey != null) made.preKey = preKey;
                    made.source = s;
                    b = made.sharp;
                    composed = SystemClock.uptimeMillis();
                    WallpaperProbe.sTmComposed = composed;
                    // Under the lyrics the picture that actually goes up is the frosted copy, and
                    // making it is another full-screen blur. Left to applyArt() it would be made
                    // on the main thread, in front of the crossfade the track change is waiting
                    // on. Made here it is already in the cache by the time that asks - keyed on
                    // this very bitmap, so it only hits when the composition is texture-sized,
                    // which is the normal case (the texture is composed to the screen).
                    if (WallpaperProbe.sLyricBlurWant && b.getWidth() == WallpaperProbe.sReportedW && b.getHeight() == WallpaperProbe.sReportedH
                            && !frostedHas(b)) {
                        // Built from the source and the layout, not from `b` - see
                        // CoverCompose.frostedFor() - but cached under `b`, which is what every
                        // reader asks with once this becomes the art. The kept small copy makes
                        // it a stretch rather than a blur.
                        cacheFrosted(b, frostOf(made, s.w, s.h));
                    }
                    WallpaperProbe.sTmFrosted = SystemClock.uptimeMillis();
                    keepComposed(made, 0L);
                    saveSourceLater(c, s, cardMode);
                } catch (Throwable t) {
                    Xp.w(WallpaperProbe.TAG + "composing from the source failed: " + t);
                    WallpaperProbe.sComposing.decrementAndGet();
                    return;
                }
                // Still counted until the picture is up: the check section must not let the
                // process retire between the composition and the thread that applies it.
                Runnable apply = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (seq != WallpaperProbe.sSrcSeq) {
                                Xp.log(WallpaperProbe.TAG + "composed cover overtaken by a newer one, dropped");
                                return;
                            }
                            Xp.log(WallpaperProbe.TAG + "composed from the source: read " + (read - t0)
                                    + "ms, compose " + (composed - read) + "ms"
                                    + (kept ? " (kept)" : "") + ", to the main thread "
                                    + (SystemClock.uptimeMillis() - composed) + "ms");
                            WallpaperProbe.applyArt(b, reload, fade);
                            // The JPEG from the old path is now older than what is on screen.
                            CoverStore.art(c).delete();
                        } finally {
                            WallpaperProbe.sComposing.decrementAndGet();
                        }
                    }
                };
                // Refused while a dry run has the gate closed: nothing will apply it, and a
                // count left up here would refuse every hot reload after it.
                if (!Gen.post(apply)) WallpaperProbe.sComposing.decrementAndGet();
            }
        });
    }

    /**
     * One composition and the frosting for it before its stretch, under the key of the source
     * they came from (CoverCompose.sourceKey).
     */
    static final class Composed {
        final long key;
        final Bitmap sharp;
        final Bitmap frostSmall;
        /** frostSmall stretched to the texture, made the first time the lyrics want it. */
        volatile Bitmap frost;
        /** SystemUI's name for it when it came as a precompose: what the tap asks for. */
        volatile String preKey;
        /** Its source, for this process's copy on disk once the tap has put it up. */
        volatile CoverCompose.Source source;

        Composed(long key, Bitmap sharp, Bitmap frostSmall) {
            this.key = key;
            this.sharp = sharp;
            this.frostSmall = frostSmall;
        }
    }

    private static final long COMPOSED_KEEP_MS = 30_000L;
    private static final long PRECOMPOSED_KEEP_MS = 60_000L;

    /** [c]'s frosted cover at the texture's size, stretched once and kept with it. */
    private static Bitmap frostOf(Composed c, int w, int h) {
        Bitmap f = c.frost;
        if (f == null || f.isRecycled() || f.getWidth() != w || f.getHeight() != h) {
            f = CoverCompose.stretchFrosted(c.frostSmall, w, h);
            c.frost = f;
        }
        return f;
    }

    private static Composed composedFor(long key) {
        Composed c = WallpaperProbe.sComposed;
        return c != null && c.key == key && !c.sharp.isRecycled() ? c : null;
    }

    private static Composed compose(CoverCompose.Source s, boolean cardMode, long key) {
        Bitmap sharp = cardMode ? CoverCompose.cardBackground(s.src, s.w, s.h)
                : CoverCompose.composeWallpaper(s.src, s.w, s.h, s.bias);
        Bitmap frost = CoverCompose.frostedSmallFor(s.src, s.w, s.h, cardMode ? 0.5f : s.bias);
        return new Composed(key, sharp, frost);
    }

    /** Keeps [c]; with [dropAfterMs] above 0, lets it go then unless a cover is showing it. */
    private static void keepComposed(Composed c, long dropAfterMs) {
        WallpaperProbe.sComposed = c;
        composer().removeCallbacks(DROP_COMPOSED);
        if (dropAfterMs > 0L) composer().postDelayed(DROP_COMPOSED, dropAfterMs);
    }

    /** The cover has gone: what it showed is kept a while for the way back in. */
    static void composedLater() {
        composer().removeCallbacks(DROP_COMPOSED);
        composer().postDelayed(DROP_COMPOSED, COMPOSED_KEEP_MS);
    }

    private static final Runnable DROP_COMPOSED = new Runnable() {
        @Override
        public void run() {
            Composed c = WallpaperProbe.sComposed;
            // On screen again: the cover going off says when to start counting.
            if (c == null || WallpaperProbe.sArt == c.sharp) return;
            WallpaperProbe.sComposed = null;
            Xp.log(WallpaperProbe.TAG + "kept composition let go");
        }
    };

    /**
     * The tap's own message (CoverPush.sendKeptFirst): the kept composition SystemUI handed over
     * ahead of it, put up right here on the main thread - no source to read, no composer queue
     * to wait in. Nothing when it is not here or not this one; the push behind brings it then.
     */
    static void applyKept(String preKey, boolean reload, boolean fade) {
        Composed c = WallpaperProbe.sComposed;
        int w = WallpaperProbe.sReportedW, h = WallpaperProbe.sReportedH;
        if (c == null || preKey == null || !preKey.equals(c.preKey) || c.sharp.isRecycled()
                || c.sharp.getWidth() != w || c.sharp.getHeight() != h || VideoTakeover.videoPath()) {
            Xp.log(WallpaperProbe.TAG + "the tap's kept cover is not here (" + (c == null ? "nothing kept"
                    : preKey != null && preKey.equals(c.preKey) ? "size or path" : "another")
                    + "), waiting for the push");
            return;
        }
        // Kept for as long as it is on screen again (DROP_COMPOSED looks at sArt).
        composer().removeCallbacks(DROP_COMPOSED);
        if (WallpaperProbe.sLyricBlurWant && !frostedHas(c.sharp)) cacheFrosted(c.sharp, frostOf(c, w, h));
        WallpaperProbe.sKeptShown = preKey;
        Xp.log(WallpaperProbe.TAG + "cover put up from the tap, kept composition");
        WallpaperProbe.applyArt(c.sharp, reload, fade);
        final CoverCompose.Source src = c.source;
        if (src != null && WallpaperProbe.sCtx != null) saveSourceLater(WallpaperProbe.sCtx, src, WallpaperProbe.sCardMode);
    }

    private static boolean frostedHas(Bitmap sharp) {
        synchronized (WallpaperProbe.class) {
            return sFrosted != null && sFrostedOf == sharp && !sFrosted.isRecycled()
                    || sFrosted2 != null && sFrostedOf2 == sharp && !sFrosted2.isRecycled();
        }
    }

    /**
     * Composes a source SystemUI expects to be asked for soon - the lock screen is up with a
     * media card on it - and keeps it without showing it, so entering cover mode finds it done.
     * Never touches sSrcSeq: a real cover arriving meanwhile is not overtaken by this.
     */
    static void precompose(final String path, final boolean cardMode,
                                   final String preKey) {
        WallpaperProbe.sComposing.incrementAndGet();
        composer().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (WallpaperProbe.sArt != null) return;
                    long t0 = SystemClock.uptimeMillis();
                    CoverCompose.Source s = CoverCompose.readSource(new File(path));
                    if (s == null) return;
                    long key = CoverCompose.sourceKey(s, cardMode);
                    Composed have = composedFor(key);
                    Composed c = have != null ? have : compose(s, cardMode, key);
                    c.preKey = preKey;
                    c.source = s;
                    // Stretched now as well: the tap shows it from the main thread, and a
                    // full-screen draw there is a frame the fade would start late by.
                    if (s.w == WallpaperProbe.sReportedW && s.h == WallpaperProbe.sReportedH) frostOf(c, s.w, s.h);
                    keepComposed(c, PRECOMPOSED_KEEP_MS);
                    Xp.log(WallpaperProbe.TAG + "precomposed" + (have != null ? " (already kept)" : "") + " in "
                            + (SystemClock.uptimeMillis() - t0) + "ms");
                } catch (Throwable t) {
                    Xp.w(WallpaperProbe.TAG + "precompose failed: " + t);
                } finally {
                    WallpaperProbe.sComposing.decrementAndGet();
                }
            }
        });
    }

    /** This process's own copy of the source, for its next cold start. Low priority, later. */
    private static void saveSourceLater(final Context c, final CoverCompose.Source s,
                                        final boolean cardMode) {
        // Counted with the JPEG write: both are what a later cold start restores, and the
        // check section must not let this generation go before one of them has landed.
        WallpaperProbe.sArtSaving.incrementAndGet();
        Gen.thread("mc-src-save", () -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                CoverCompose.writeSource(CoverStore.source(c),
                        s.src, s.w, s.h, s.bias);
                File marker = CoverStore.cardMode(c);
                if (cardMode) marker.createNewFile();
                else marker.delete();
            } catch (Throwable t) {
                Xp.w(WallpaperProbe.TAG + "saving the source failed: " + t);
            } finally {
                WallpaperProbe.sArtSaving.decrementAndGet();
            }
        }, WallpaperProbe.sArtSaving::decrementAndGet);
    }

    /**
     * Re-uploads the wallpaper texture in place.
     *
     * The engine's field b is the "surface needs creating" flag its preRender step reads, u()
     * is what the OEM calls to arm it, and T(false) posts that preRender onto the GL thread -
     * which is the path that ends in the texture being re-read. Setting the field as well as
     * calling u() is deliberate: u() is obfuscated, and this is the one bit that decides whether
     * the frame re-reads the texture or just redraws the old one.
     *
     * Every step stands on its own now. On a 1080x2400 HyperOS build T does not exist at all:
     * the flag was still armed, the request threw, one "reload failed" went to the log, and the
     * texture kept the album art. Leaving cover mode then put the depth layer back but not the
     * wallpaper, so the lock screen read as the cover stuck behind the subject. See Handoff 27.
     */
    /**
     * The cover the desktop wallpaper should be showing, fitted to the texture it is about to be
     * uploaded in place of.
     *
     * Fitted to the ORIGINAL's dimensions rather than to the screen's, because that is the rule
     * the keyguard side learned the hard way: updateDimensions/updateMatrix derive the GL matrix
     * from the bitmap's size, so anything that is not exactly this texture lands the wallpaper
     * askew - a corner of the picture in a corner of the screen.
     *
     * Returns null for "leave it alone", which is every case except an open shade with art.
     */
    static Bitmap shadeCoverFor(Bitmap orig) {
        if (!WallpaperProbe.sShadeOn) return null;
        if (android.os.SystemClock.uptimeMillis() - WallpaperProbe.sShadeAt > SHADE_TTL_MS) {
            // Nothing has refreshed this, so the shade is not up any more and the message that
            // would have said so never arrived. Put the desktop back.
            WallpaperProbe.sShadeOn = false;
            Xp.log(WallpaperProbe.TAG + "shade art expired with no word - desktop wallpaper back to its own");
            return null;
        }
        Bitmap art = WallpaperProbe.sArt;
        if (art == null || art.isRecycled() || orig == null) return null;
        final int w = orig.getWidth(), h = orig.getHeight();
        if (w <= 0 || h <= 0) return null;
        if (art.getWidth() == w && art.getHeight() == h) return art;
        final String key = w + "x" + h;
        Bitmap fitted = WallpaperProbe.sShadeFitted;
        if (fitted != null && !fitted.isRecycled() && key.equals(WallpaperProbe.sShadeFitOf)) return fitted;
        try {
            fitted = Bitmap.createScaledBitmap(art, w, h, true);
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + "shade art could not be fitted to " + key + ": " + t);
            return null;
        }
        final Bitmap old = WallpaperProbe.sShadeFitted;
        WallpaperProbe.sShadeFitted = fitted;
        WallpaperProbe.sShadeFitOf = key;
        if (old != null && old != fitted && !old.isRecycled()) old.recycle();
        return fitted;
    }
}
