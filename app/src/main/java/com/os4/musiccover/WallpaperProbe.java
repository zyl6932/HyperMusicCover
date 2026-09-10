package com.os4.musiccover;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Modifier;


/**
 * Probe for com.miui.miwallpaper, the separate process that actually draws the lockscreen
 * wallpaper (window com.miui.miwallpaper.wallpaperservice.ImageWallpaper, rendered through
 * OpenGL). The clock's liquid-glass refraction and the notification/media card blur sample
 * that window, not SystemUI's view tree, so a cover added inside SystemUI can never be picked
 * up by them - the album art has to become the wallpaper here.
 *
 * Read-only for now: this process owns the wallpaper, and a bad hook leaves the phone with no
 * wallpaper at all, so discover the bitmap path before touching it.
 */
public class WallpaperProbe {

    private static final String TAG = "[MCWall] ";
    private static final String ACTION = "com.os4.musiccover.WPROBE";
    private static final String PKG = "com.miui.miwallpaper";

    private static ClassLoader sCl;
    private static boolean sRegistered;
    private static Context sCtx;
    /** The fitted copy of sArt, kept so the GL thread never rescales during a track change. */
    private static volatile Bitmap sFitted;
    private static volatile Bitmap sFittedOf;
    private static volatile int sReportedW, sReportedH;

    /**
     * While non-null, this replaces whatever the keyguard renderer would have uploaded as its
     * wallpaper texture. Because it becomes the real wallpaper surface, the clock glass and the
     * card blur sample it too - which is the whole point of coming into this process.
     */
    private static volatile Bitmap sArt;

    /**
     * The keyguard engine, captured so a new track can re-run the texture upload without the
     * process being killed. Its GL work all happens on one HandlerThread; nothing here touches
     * GL directly, it only asks the engine to run its own surface-created path again.
     */
    private static volatile Object sKeyguardEngine;

    private static final String CLS_KEYGUARD_ENGINE =
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardImageEngineImpl";

    // ------------------------------------------------------------------ the crossfade

    /**
     * The real lock wallpaper at texture size - the far end of the fade out of cover mode.
     *
     * Deliberately not persisted. It is only ever learnt by watching one go past in the upload
     * hook, so a copy read back from disk could be a wallpaper the user has since changed, and
     * fading to the wrong picture and then cutting to the right one is worse than not fading at
     * all. Missing it costs exactly one hard cut - the reload that performs it is the reload
     * that learns the original - so this heals itself on first use after a process restart.
     */
    private static volatile Bitmap sOrig;
    private static volatile int sOrigPrint;
    /** The frame the fade is on. Non-null only while one is running. */
    private static volatile Bitmap sFade;
    /** Reused across fades: a 12MB allocation per transition is itself a dropped frame. */
    private static Bitmap sFadeBuf;
    /**
     * True from the moment a blended frame is handed to the engine until the GL thread has
     * finished reading it.
     *
     * One buffer is reused for the whole fade, so composing the next frame into it while the
     * upload is still walking down it puts two different blend fractions in one texture, with a
     * hard horizontal seam between them. That is not theoretical - it is what the tearing looks
     * like on video: the top third of the wallpaper a step or two behind the rest, for several
     * frames in a row. So the fade waits for its frame to be consumed before composing another.
     */
    private static volatile boolean sFadeInFlight;
    private static volatile long sFadeSentAt;
    /**
     * How long to wait for that acknowledgement before composing anyway. A reload that never
     * reaches the upload path - coalesced, or the engine simply not asking for a frame - must
     * cost a dropped frame, not a fade that stops half way.
     */
    private static final long FADE_ACK_MS = 48L;
    private static final Paint sFadePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** Which fade is the current one, so a track change mid-flight cancels the old one. */
    private static volatile int sFadeGen;
    /**
     * How long the crossfade takes.
     *
     * Apple's whole exit is about 300ms, but matching that number is not the same as matching
     * the feel: the clock is a critically damped spring, so it has covered most of its distance
     * long before it settles, and a linear-ish wallpaper fade of the same total length reads as
     * lagging behind it. Hence both the shorter default and the ease-out below.
     *
     * Tunable, because it is a taste judgement made against a phone:
     *   --es op fadems --ei v 240
     */
    private static volatile long sFadeMs = 240L;
    private static final long FADE_STEP_MS = 16L;
    /**
     * Whether the OEM's frosted copy is regenerated on the fade's frames.
     *
     * On by default, on measurement rather than principle: it is worth 7 -> 9 frames across a
     * 240ms fade, because the round trip to the GL thread is what paces this and the blur is
     * part of it. What it costs is the notification and media cards blurring a wallpaper up to
     * 240ms stale, which nobody can see, and the upload that ends the fade puts it right.
     */
    private static volatile boolean sSkipFrost = true;
    /** Live for the duration of one fade, read by the frosting hook on the GL thread. */
    private static volatile boolean sFrostSkipping;

    public static void handle(XposedModuleInterface.PackageLoadedParam param) {
        sCl = param.getDefaultClassLoader();
        Xp.log(TAG + "loaded into " + PKG);

        // Must be installed at load time: getBitmap() runs when the GL surface is created and
        // the texture is then cached, so a hook added later never sees it.
        try {
            Class<?> base = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer", sCl);
            // The one place the wallpaper bitmap reaches the GL upload:
            //   onSurfaceCreated() -> mTexture.use(c) -> lambda$onSurfaceCreated$0(Bitmap)
            // Hooking here rather than on WallpaperTexture.getWallpaperBitmap() because
            // thisObject is the renderer, so we can tell the keyguard one from the desktop one
            // and leave the home wallpaper alone.
            Xp.hookAllLambdas(base, "lambda$onSurfaceCreated$0", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean keyguard = chain.getThisObject().getClass().getName().contains("Keyguard");
                if (keyguard && args.length > 0 && args[0] instanceof Bitmap) {
                    Bitmap orig = (Bitmap) args[0];
                    int w = orig.getWidth(), h = orig.getHeight();
                    if (w != sReportedW || h != sReportedH) {
                        sReportedW = w;
                        sReportedH = h;
                        Xp.log(TAG + "keyguard texture is " + w + "x" + h);
                    }
                    noteRenderState(chain.getThisObject(), w, h);
                    // A fade owns the texture outright while it runs: every frame of it is
                    // a blend this process composed, and neither the art nor the original is
                    // what should be uploaded until it lands.
                    Bitmap fade = sFade;
                    // Size-checked like the art below, and for the same reason: the GL matrix
                    // comes from the bitmap's dimensions, so a bitmap that is not exactly this
                    // texture lands the wallpaper askew - a corner of the picture in a corner
                    // of the screen. A fade composed for a texture that has since changed size
                    // (a new wallpaper, a surface rebuilt at another size) is not something to
                    // fit and show, it is stale: drop it and let the frame below draw the real
                    // art at the real size.
                    if (fade != null && (fade.getWidth() != w || fade.getHeight() != h)) {
                        Xp.log(TAG + "fade dropped: composed for " + describe(fade)
                                + " but the texture is " + w + "x" + h);
                        cancelFade();
                        fade = null;
                    }
                    if (fade != null) {
                        args[0] = fade;
                        // proceed() is the upload: once it returns, the buffer has been read
                        // and the next frame may be composed into it. This is the whole
                        // handshake, and it is why one buffer is enough.
                        Object r = chain.proceed(args);
                        sFadeInFlight = false;
                        return r;
                    }
                    Bitmap art = sArt;
                    // The one moment the real lock wallpaper passes through here. Once the art
                    // is set the getBitmap short-circuit below means the OEM never decodes it
                    // again, so this is the only chance to learn what to fade back to.
                    if (art == null) rememberOriginal(orig);
                    if (art != null) {
                        // Match the original exactly: updateDimensions/updateMatrix derive
                        // the GL matrix from these, so another size lands the wallpaper askew.
                        Bitmap fitted;
                        if (art.getWidth() == w && art.getHeight() == h) {
                            fitted = art;                   // composed at exactly this size
                        } else if (sFitted != null && sFittedOf == art
                                && sFitted.getWidth() == w && sFitted.getHeight() == h) {
                            fitted = sFitted;               // already fitted for this texture
                        } else {
                            fitted = centerCrop(art, w, h);
                            sFitted = fitted;
                            sFittedOf = art;
                        }
                        args[0] = fitted;
                        Xp.log(TAG + "wallpaper texture REPLACED " + describe(orig)
                                + " -> " + describe(fitted)
                                + (fitted == art ? " (no rescale)" : ""));
                    }
                }
                return chain.proceed(args);
            });
            Xp.log(TAG + "upload path hooked on ImageWallpaperRenderer");
        } catch (Throwable t) {
            // Said "getBitmap hook failed" until a HyperOS 3 report came in naming that and
            // the short-circuit below in the same breath. This is the one that matters: with
            // it gone the texture is never replaced, so the cover does nothing at all.
            Xp.log(TAG + "upload path hook FAILED - the cover cannot be drawn: " + t);
        }

        // Measured: of the ~380ms a track change took, 210ms was the OEM's own getBitmap()
        // decoding the real lock wallpaper off disk inside onSurfaceCreated - a bitmap thrown
        // away one call later when we substitute ours. Handing back the art we are going to
        // substitute anyway removes that decode outright. The upload path is still the lambda
        // below; this only short-circuits the source, and only once the texture size is known,
        // so a cold start still learns the real dimensions first.
        try {
            Class<?> kg = Xp.findClass(
                    "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer",
                    sCl);
            Xp.hookAll(kg, "getBitmap", chain -> {
                // Not size-checked here on purpose: getBitmap is the SOURCE, and what the
                // texture ends up being is decided by the lambda above, which does check.
                Bitmap fading = sFade;
                if (fading != null) return fading;
                Bitmap fitted = fittedArt();
                // Returning without proceeding IS the short-circuit: the OEM never decodes
                // the real lock wallpaper off disk, which is the 210ms this buys back.
                return fitted != null ? fitted : chain.proceed();
            });
            Xp.log(TAG + "keyguard getBitmap short-circuit installed");
        } catch (Throwable t) {
            Xp.log(TAG + "getBitmap short-circuit failed: " + t);
        }

        // Runtime refresh. ImageEngineImpl.U() ("preRender", on the GL thread) re-runs
        //     mRenderer.onSurfaceCreated(); mRenderer.onSurfaceChanged(w, h)
        // whenever its pending-surface flag is set, and onSurfaceCreated is the path that ends in
        // mTexture.use(...) -> the lambda we already replace the bitmap in. So a new track only
        // has to set that flag and ask for a frame; the OEM does the upload itself, including the
        // frosted copy the notification cards blur against.
        try {
            Class<?> eng = Xp.findClass(CLS_KEYGUARD_ENGINE, sCl);
            Xp.hookAllConstructors(eng, chain -> {
                Object result = chain.proceed();
                sKeyguardEngine = chain.getThisObject();
                Xp.log(TAG + "keyguard engine captured: " + sKeyguardEngine);
                return result;
            });
            Xp.log(TAG + "keyguard engine hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "keyguard engine hook failed: " + t);
        }

        // The video wallpaper's engine, which is what a live lock wallpaper gets instead of
        // KeyguardImageEngineImpl. Captured from the constructor for the same reason: it is
        // built while the process starts, so a hook added later never sees it. The manager
        // that owns the surfaces hangs off it - the manager's own constructor runs too early
        // to hook, and its static instance field is not populated.
        for (String cn : CLS_VIDEO_ENGINES) {
            try {
                Class<?> vd = Xp.findClass(cn, sCl);
                Xp.hookAllConstructors(vd, chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    // Desktop has video engines too, and its wallpaper is not ours to touch.
                    if (self.getClass().getName().contains("Keyguard")) {
                        sVideoEngine = self;
                        sVideoDepth = null;
                        Xp.log(TAG + "video engine captured: " + self.getClass().getName());
                    }
                    return result;
                });
                Xp.log(TAG + "video engine hooked on " + cn.substring(cn.lastIndexOf('.') + 1));
            } catch (Throwable t) {
                Xp.log(TAG + "video engine hook failed on " + cn + ": " + t);
            }
        }

        // The frosted copy the notification and media cards blur against is regenerated on
        // every texture upload. That is the right trade once per track change and the wrong one
        // twenty times in a row, so a fade can switch it off for its own frames; the upload that
        // ends the fade is a normal one and puts it right. Only ever engaged from startFade().
        try {
            Class<?> ap = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimatorProgram", sCl);
            Xp.hookAll(ap, "setUpMixFrost", chain -> {
                if (sFrostSkipping) return null;
                return chain.proceed();
            });
            Xp.log(TAG + "frosting hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "frosting hook failed: " + t);
        }

        // getBitmap() turned out never to be called - the texture does not travel that way - so
        // trace every Bitmap-carrying method and constructor on the renderers from load time.
        // The GL surface is created during process startup, so a hook added later misses it.
        for (String cn : new String[]{
                "com.miui.miwallpaper.opengl.ImageWallpaperRenderer",
                "com.miui.miwallpaper.opengl.AnimImageWallpaperRenderer",
                "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer"}) {
            traceBitmaps(cn);
        }

        Xp.hook(Xp.findMethodExact(Application.class, "onCreate"), chain -> {
            Object result = chain.proceed();
            try {
                register((Application) chain.getThisObject());
            } catch (Throwable t) {
                Xp.log(TAG + "register failed: " + t);
            }
            return result;
        });
    }

    private static final String ART_FILE = "mc_art.jpg";

    /**
     * Decodes the pushed JPEG straight to the size the texture wants. SystemUI composes at the
     * screen's size and the texture is the lock wallpaper's, but the two share an aspect ratio,
     * so this is a pure scale - and doing it inside the decoder replaces a full decode plus a
     * 21MB allocation and filtered draw with a single pass (measured: 140ms -> ~45ms).
     */
    private static Bitmap decodeToTextureSize(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        try {
            if (sReportedW > 0) {
                BitmapFactory.Options probe = new BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpg, 0, jpg.length, probe);
                if (probe.outWidth > 0 && probe.outWidth != sReportedW) {
                    o.inScaled = true;
                    o.inDensity = probe.outWidth;
                    o.inTargetDensity = sReportedW;
                }
            }
        } catch (Throwable ignored) {
        }
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }

    private static String sRenderState = "";
    private static boolean sRenderStateFailed;
    /** The screen, as MIUI's own renderer has it (mSurfaceSize). 0 until an upload has run. */
    private static volatile int sSurfaceW, sSurfaceH;

    /**
     * The three numbers that decide where the wallpaper lands on screen, read off MIUI's own
     * renderer at the moment of the upload:
     *
     * - mSurfaceSize, which is the glViewport onSurfaceChanged last set;
     * - the texture's dimensions, which is what AnimImageWallpaperRenderer.updateMVPMatrix()
     *   builds the MVP matrix from (updateMVPMatrix(surfaceW, surfaceH, textureDimensions));
     * - the bitmap actually being uploaded.
     *
     * A picture drawn small and cornered is one of these three disagreeing with the other two,
     * and none of them is visible from the SystemUI side or from a screenshot. Logged only when
     * the triple CHANGES, so a steady state costs one string compare per upload and says
     * nothing, and the frame that moves the picture is the one that prints.
     */
    private static void noteRenderState(Object renderer, int w, int h) {
        if (sRenderStateFailed) return;
        try {
            Object surface = Xp.getObjectField(renderer, "mSurfaceSize");
            Object texture = Xp.getObjectField(renderer, "mTexture");
            Object dims = texture == null ? null
                    : Xp.callMethod(texture, "getTextureDimensions");
            // Read before the early return below: this is the only place the SCREEN's own size
            // is knowable in this process, and startFade() needs it on every fade, not only on
            // the frames where something changed.
            if (surface instanceof android.graphics.Rect) {
                android.graphics.Rect r = (android.graphics.Rect) surface;
                if (r.width() > 0 && r.height() > 0) {
                    sSurfaceW = r.width();
                    sSurfaceH = r.height();
                }
            }
            String now = "viewport=" + surface + " mvpFrom=" + dims
                    + " upload=" + w + "x" + h;
            if (now.equals(sRenderState)) return;
            sRenderState = now;
            Xp.log(TAG + "render state " + now);
        } catch (Throwable t) {
            sRenderStateFailed = true;
            Xp.log(TAG + "cannot read the renderer's geometry: " + t);
        }
    }

    /**
     * sArt scaled to the texture the keyguard actually uploads, or null while that size is still
     * unknown. Cached, so a track change scales once rather than on every GL callback.
     */
    private static Bitmap fittedArt() {
        Bitmap art = sArt;
        if (art == null || sReportedW <= 0 || sReportedH <= 0) return null;
        if (art.getWidth() == sReportedW && art.getHeight() == sReportedH) return art;
        Bitmap cached = sFitted;
        if (cached != null && sFittedOf == art
                && cached.getWidth() == sReportedW && cached.getHeight() == sReportedH) {
            return cached;
        }
        Bitmap fitted = centerCrop(art, sReportedW, sReportedH);
        sFitted = fitted;
        sFittedOf = art;
        return fitted;
    }

    private static void saveArtLater(final Context ctx, final byte[] jpg) {
        new Thread(new Runnable() {
            @Override
            public void run() { saveArt(ctx, jpg); }
        }, "mc-art-save").start();
    }

    private static void saveArt(Context ctx, byte[] jpg) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), ART_FILE));
            fos.write(jpg);
            fos.close();
        } catch (Throwable t) {
            Xp.log(TAG + "saveArt failed: " + t);
        }
    }

    /**
     * The texture is only read when the GL surface is created, i.e. at process start, so the art
     * has to be on disk here before that happens - a bitmap pushed later would not be uploaded
     * until something recreated the surface.
     */
    private static void loadArt(Context ctx) {
        File f = new File(ctx.getFilesDir(), ART_FILE);
        if (!f.exists()) return;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) {
            sArt = b;
            Xp.log(TAG + "art restored from disk " + describe(b));
        }
    }

    /**
     * Crossfades the keyguard texture from one picture to another.
     *
     * Both ends are already at texture size and both live in this process, so a frame costs one
     * blend and one re-upload and nothing crosses a process boundary - which is the only reason
     * this is affordable at all.
     *
     * The OEM's own reveal animator was the obvious thing to drive and is the wrong shape. Its
     * shader says so itself: "Reveal is the animation value that goes from 1 (the image is
     * hidden) to 0 (the image is visible)", and the branch behind it is
     * blendSrcOver(vec4(0.,0.,0.,uReveal), ori) - one texture fading to black. It can dissolve a
     * picture; it cannot dissolve between two, which is what Apple's transition is.
     *
     * Time-based rather than step-based: a frame that overruns costs a frame, not a longer
     * transition. The clock is springing to its own curve over in SystemUI and cannot wait.
     */
    private static void startFade(final Bitmap from, final Bitmap to, final Runnable done) {
        final Context ctx = sCtx;
        if (ctx == null || from == null || to == null || from.isRecycled() || to.isRecycled()
                || from.getWidth() != to.getWidth() || from.getHeight() != to.getHeight()) {
            Xp.log(TAG + "no fade: " + describe(from) + " -> " + describe(to));
            if (done != null) done.run();
            reloadTexture();
            return;
        }
        if (fadeTooExpensive(from)) {
            if (done != null) done.run();
            reloadTexture();
            return;
        }
        // Both ends agree with each other by the check above; whether they agree with the
        // TEXTURE is the thing that decides how this looks on screen, and it is the one number
        // that is not in either of them. Logged once per fade so a report of a bad transition
        // can be read off the log instead of guessed at.
        if (from.getWidth() != sReportedW || from.getHeight() != sReportedH) {
            Xp.log(TAG + "fade " + describe(from) + " -> " + describe(to)
                    + " does NOT match the texture " + sReportedW + "x" + sReportedH);
        }
        Bitmap buf = sFadeBuf;
        if (buf == null || buf.isRecycled()
                || buf.getWidth() != from.getWidth() || buf.getHeight() != from.getHeight()) {
            buf = Bitmap.createBitmap(from.getWidth(), from.getHeight(), Bitmap.Config.ARGB_8888);
            sFadeBuf = buf;
        }
        final Bitmap dst = buf;
        final int gen = ++sFadeGen;
        final long t0 = SystemClock.uptimeMillis();
        final long[] spent = {0L, 0L, 0L};  // blend ms, frames, frames waited out
        sFrostSkipping = sSkipFrost;
        sFadeInFlight = false;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                // A newer fade has taken over - a track changed while this one was in the air.
                // It owns sFade and the buffer now, so this one simply stops.
                if (gen != sFadeGen) return;
                long now = SystemClock.uptimeMillis();
                // The GL thread has not finished with the buffer yet. Come back rather than
                // compose over the top of it - see sFadeInFlight.
                if (sFadeInFlight && now - sFadeSentAt < FADE_ACK_MS) {
                    spent[2]++;
                    h.postDelayed(this, 2L);
                    return;
                }
                long el = now - t0;
                if (el >= sFadeMs) {
                    sFade = null;
                    sFadeInFlight = false;
                    sFrostSkipping = false;
                    if (done != null) done.run();
                    // The last upload is a normal one: the real bitmap, and the frosted copy
                    // regenerated from it.
                    reloadTexture();
                    Xp.log(TAG + "fade done in " + el + "ms over " + spent[1] + " frames, blend "
                            + (spent[1] == 0 ? 0 : spent[0] / spent[1]) + "ms/frame, waited "
                            + spent[2] + "x for the upload"
                            + (sSkipFrost ? ", frosting skipped" : ""));
                    return;
                }
                float t = el / (float) sFadeMs;
                // Ease OUT, not smoothstep. The clock it has to keep company with is a spring,
                // and a spring is all front-loaded: most of the movement is over in the first
                // third. A symmetric curve spends that third barely changing, which is exactly
                // when the eye is looking, and then finishes after the clock has stopped.
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                long b0 = SystemClock.uptimeMillis();
                blendInto(dst, from, to, e);
                spent[0] += SystemClock.uptimeMillis() - b0;
                spent[1]++;
                sFade = dst;
                sFadeInFlight = true;
                sFadeSentAt = SystemClock.uptimeMillis();
                reloadTexture();
                h.postDelayed(this, FADE_STEP_MS);
            }
        });
    }

    /**
     * Ends a fade in flight without running its completion. For a fade that has become invalid
     * rather than one that has finished - the buffer it was composing into no longer matches
     * the texture, so nothing it produces from here is worth uploading.
     */
    private static void cancelFade() {
        sFadeGen++;
        sFade = null;
        sFadeInFlight = false;
        sFrostSkipping = false;
    }

    /**
     * Whether a crossfade at this texture size is affordable, measured rather than assumed.
     *
     * Every frame of the fade goes through reloadTexture(), and that is not a cheap poke: it
     * re-runs the OEM's onSurfaceCreated(), which clears the surface, rebuilds the GL program
     * and re-uploads the WHOLE texture. So the cost of a fade is the texture size times the
     * frame count, and it is paid on the GL thread while the clock is springing next to it.
     *
     * Measured on the device, same phone, same 240ms fade, the only difference being the lock
     * wallpaper's own dimensions:
     *
     *   1200x2608 (= the screen)  12.5MB/frame  8 frames  waited 16x for the upload   fine
     *   1579x3432                 21MB/frame    7 frames  waited 51x                  breaks
     *
     * Breaks how: the GL thread falls far enough behind that the compositor picks up a frame
     * from the middle of onSurfaceCreated() - after glClearColor, before updateMVPMatrix - and
     * that frame is the cover drawn small on black, in a corner. Reported from the device as
     * "a small album cover in the top right, then it switches over", in both directions.
     *
     * So the gate is the ratio to the screen, which is the number the frame budget actually
     * scales with. A lock wallpaper the size of the screen is what ensureLockWallpaper() writes
     * and what this was built for; 1.7x the screen is not, and a hard cut is better than a
     * transition that flashes. Unknown surface size fades, as before - never make the OEM's own
     * behaviour worse over a number we have not read yet.
     */
    private static boolean fadeTooExpensive(Bitmap from) {
        long screen = (long) sSurfaceW * sSurfaceH;
        if (screen <= 0) return false;
        long texture = (long) from.getWidth() * from.getHeight();
        if (texture * 2 <= screen * 3) return false;           // <= 1.5x the screen
        Xp.log(TAG + "no fade: " + describe(from) + " is "
                + (Math.round(texture * 10.0 / screen) / 10.0) + "x the screen ("
                + sSurfaceW + "x" + sSurfaceH + ") - one upload of it is "
                + (texture * 4 / (1024 * 1024)) + "MB and the fade needs one per frame."
                + " Swapping in one frame instead.");
        return true;
    }

    /** One frame of the crossfade. Both sources are already exactly dst's size. */
    private static void blendInto(Bitmap dst, Bitmap from, Bitmap to, float t) {
        Canvas cv = new Canvas(dst);
        cv.drawBitmap(from, 0f, 0f, null);
        sFadePaint.setAlpha(Math.round(255f * (t < 0f ? 0f : t > 1f ? 1f : t)));
        cv.drawBitmap(to, 0f, 0f, sFadePaint);
    }

    /**
     * Keeps a copy of the real lock wallpaper, which is the only thing the cover can fade back
     * to. The bitmap handed to the upload hook belongs to the OEM and is recycled behind us, so
     * this has to be a copy - and the fingerprint is what stops it being copied again on every
     * reload that happens while cover mode is off.
     */
    private static void rememberOriginal(Bitmap b) {
        if (b == null || b.isRecycled()) return;
        try {
            int print = print8(b);
            Bitmap have = sOrig;
            if (have != null && !have.isRecycled() && print == sOrigPrint
                    && have.getWidth() == b.getWidth() && have.getHeight() == b.getHeight()) {
                return;
            }
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) return;
            sOrig = copy;
            sOrigPrint = print;
            Xp.log(TAG + "lock wallpaper remembered " + describe(copy));
        } catch (Throwable t) {
            Xp.log(TAG + "could not remember the lock wallpaper: " + t);
        }
    }

    /** Coarse identity. Same idea as the module's artPrint, and for the same reason. */
    private static int print8(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 8 || h < 8) return 0;
        int v = w * 31 + h;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                v = v * 31 + b.getPixel(x * (w - 1) / 7, y * (h - 1) / 7);
            }
        }
        return v;
    }

    /** Fills w x h from the source without distorting it, the way CENTER_CROP would. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        cv.drawBitmap(src, null, new RectF((w - dw) / 2f, (h - dh) / 2f,
                (w + dw) / 2f, (h + dh) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static synchronized void register(Context ctx) {
        if (sRegistered) return;
        sRegistered = true;
        sCtx = ctx.getApplicationContext();
        loadArt(ctx);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String op = i.getStringExtra("op");
                byte[] carried = i.getByteArrayExtra("jpg");
                Xp.log(TAG + "recv op=" + op
                        + (carried == null ? " " + i.getExtras() : " jpg=" + carried.length + "B"));
                try {
                    if (i.hasExtra("video")) noteLockWallpaper(i.getBooleanExtra("video", false));
                    if ("cls".equals(op)) {
                        dumpClass(i.getStringExtra("name"), i.getStringExtra("grep"));
                    } else if ("bmp".equals(op)) {
                        traceBitmaps(i.getStringExtra("name"));
                    } else if ("art".equals(op)) {
                        final Context cc = c;
                        boolean reload = i.getBooleanExtra("reload", false);
                        // A fade needs both ends of it in this process. Missing either one is
                        // not a failure, it is the old behaviour: swap the texture in one frame.
                        boolean fade = reload && i.getBooleanExtra("fade", false);
                        if (i.getBooleanExtra("off", false)) {
                            // A live wallpaper has no texture to fade back to - the way back
                            // is handing the surface to its player again.
                            if (videoPath()) {
                                sArt = null;
                                sFitted = null;
                                sFittedOf = null;
                                new File(c.getFilesDir(), ART_FILE).delete();
                                videoWindowTakeover(true);
                                return;
                            }
                            Bitmap from = fittedArt();
                            Bitmap to = sOrig;
                            if (fade && from != null && to != null) {
                                startFade(from, to, new Runnable() {
                                    @Override
                                    public void run() {
                                        sArt = null;
                                        sFitted = null;
                                        sFittedOf = null;
                                        new File(cc.getFilesDir(), ART_FILE).delete();
                                        Xp.log(TAG + "art cleared");
                                    }
                                });
                                return;
                            }
                            sArt = null;
                            new File(c.getFilesDir(), ART_FILE).delete();
                            Xp.log(TAG + "art cleared");
                        } else {
                            byte[] jpg = i.getByteArrayExtra("jpg");
                            String file = i.getStringExtra("file");
                            Bitmap b = null;
                            if (jpg != null) b = decodeToTextureSize(jpg);
                            else if (file != null) b = BitmapFactory.decodeFile(file);
                            if (b == null) {
                                Xp.log(TAG + "art decode failed (jpg="
                                        + (jpg == null ? "null" : jpg.length + "B")
                                        + " file=" + file + ")");
                            } else {
                                // Read before sArt moves: on the way into cover mode this is
                                // the lock wallpaper, and on a track change it is the album
                                // that is on screen right now.
                                Bitmap from = fittedArt();
                                if (from == null) from = sOrig;
                                sArt = b;
                                sFitted = null;
                                sFittedOf = null;
                                Bitmap to = fittedArt();   // scale here, not on the GL thread
                                Xp.log(TAG + "art set " + describe(b));
                                // Show it first, write it to disk afterwards: the file only
                                // matters for the next cold start of this process, and a 100KB
                                // write in front of the upload is pure added latency.
                                if (videoPath()) videoWindowTakeover(false);
                                else if (fade && from != null && to != null) {
                                    startFade(from, to, null);
                                } else if (reload) reloadTexture();
                                if (jpg != null) saveArtLater(c, jpg);
                                return;
                            }
                        }
                        if (reload) reloadTexture();
                    } else if ("reload".equals(op)) {
                        reloadTexture();
                    } else if ("fadems".equals(op)) {
                        long v = i.getIntExtra("v", (int) sFadeMs);
                        sFadeMs = v < 60L ? 60L : (v > 1200L ? 1200L : v);
                        Xp.log(TAG + "crossfade is now " + sFadeMs + "ms");
                    } else if ("nofrost".equals(op)) {
                        sSkipFrost = i.getBooleanExtra("on", !sSkipFrost);
                        Xp.log(TAG + "frosting during a fade is "
                                + (sSkipFrost ? "skipped" : "kept"));
                    } else if ("state".equals(op)) {
                        Xp.log(TAG + "art=" + describe(sArt)
                                + " orig=" + describe(sOrig)
                                + " fading=" + (sFade != null)
                                + " nofrost=" + sSkipFrost
                                + " fadems=" + sFadeMs
                                + " engine=" + sKeyguardEngine
                                + " videoEngine=" + sVideoEngine);
                    } else if ("vgl".equals(op)) {
                        videoWindowTakeover(i.getBooleanExtra("on", true));
                    } else {
                        Xp.log(TAG + "ops: cls --es name <fqcn> [--es grep x]"
                                + " | bmp --es name <fqcn>");
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        Xp.log(TAG + "receiver registered for " + ACTION);
    }

    /**
     * Re-uploads the wallpaper texture in place. The engine's field b is the "surface needs
     * creating" flag its preRender step reads; u() is what the OEM calls to set it, and T(false)
     * posts a render onto the GL thread. Setting the field as well as calling u() is deliberate:
     * u() is obfuscated, and this is the one bit that decides whether the frame re-reads the
     * texture or just redraws the old one.
     */
    private static void reloadTexture() {
        Object eng = sKeyguardEngine;
        if (eng == null) {
            Xp.log(TAG + "reload: no keyguard engine (is the lockscreen wallpaper "
                    + "still the same image as the desktop one?)");
            return;
        }
        try {
            try {
                Xp.callMethod(eng, "u");
            } catch (Throwable t) {
                Xp.log(TAG + "reload: u() failed: " + t);
            }
            Xp.setBooleanField(eng, "b", true);
            Xp.callMethod(eng, "T", false);
            Xp.log(TAG + "reload requested on " + eng.getClass().getSimpleName());
        } catch (Throwable t) {
            Xp.log(TAG + "reload failed: " + Log.getStackTraceString(t));
        }
    }

    /**
     * The video wallpaper's manager, captured so the cover can reach the wallpaper WINDOW.
     *
     * A video lock wallpaper is drawn by FastPlayer into three surfaces, held on this object:
     * .c is the alpha one and .d the normal one - both handed over from SystemUI and shown
     * there as TextureViews - and **.e ("mLocalSurface") is this process's own wallpaper
     * window**. That last one is the one the clock's liquid glass and the media card's blur
     * sample, which is why covering the TextureViews in SystemUI changed the background and
     * left the glass and the card still showing the video.
     */
    private static volatile Object sVideoDepth;
    /** KeyguardVideoDepthEngineImpl, which owns the manager above on its field `p`. */
    private static volatile Object sVideoEngine;
    /**
     * The two base classes a live video lock wallpaper can be running on. Which one MIUI picks
     * is the wallpaper's effect type, and it changes underneath you: the same wallpaper on this
     * phone reported `lockEffectType = 10` (depth) at one point and `0` (plain) later, i.e. a
     * different engine class and a different internal shape. Both are hooked, and the base
     * class is hooked rather than the Keyguard subclass so one hook covers the family - a
     * subclass constructor runs its super's, so the hook still fires with the subclass instance.
     */
    private static final String[] CLS_VIDEO_ENGINES = {
            "com.miui.miwallpaper.wallpaperservice.impl.VideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.VideoEngineImpl",
    };

    /**
     * Paints the cover into the wallpaper window a video wallpaper is playing into, by taking
     * that one surface off FastPlayer and drawing on it directly.
     *
     * FastPlayer.changeOpenGLSurface(alpha, on, normal, on, local, on) is the OEM's own way of
     * turning an individual output on and off - it is what MIUI calls when the surfaces come
     * and go - so switching the local one off is asking the player to let go rather than
     * fighting it for the buffer. Only then can lockCanvas() have it: a Surface connected to
     * GL cannot also be locked for a software canvas.
     *
     * The video keeps playing into the other two the whole time, so nothing has to be resumed,
     * re-seeked or re-decoded on the way back - the surface is simply handed over again.
     */
    /** Whether the video's surface is currently ours rather than its player's. */
    private static volatile boolean sVideoTakenOver;

    /**
     * What the lock wallpaper is now, as SystemUI reads it before every push. null until a push
     * has said.
     */
    private static volatile Boolean sLockIsVideo;

    /**
     * Whether this push is for a live lock wallpaper.
     *
     * Having a video engine is not the same question, and answering with it alone was a bug:
     * the engine is captured in a constructor, the constructor runs once, and this process
     * outlives any number of wallpaper changes. Set a video lock wallpaper and then set a still
     * one back, and sVideoEngine is still there - so every push took the video path, which on
     * the depth shape does nothing at all (see takeoverDepth), and the still wallpaper's texture
     * was never replaced. Measured on the device: cover mode on, "art set" logged here, and
     * nothing on the lock screen. Both have to be true, and the engine alone never decides.
     */
    private static boolean videoPath() {
        Boolean live = sLockIsVideo;
        return sVideoEngine != null && (live == null || live);
    }

    /** Told, not guessed: SystemUI carries the answer on every broadcast. */
    private static void noteLockWallpaper(boolean video) {
        Boolean was = sLockIsVideo;
        sLockIsVideo = video;
        if (was != null && was == video) return;
        Xp.log(TAG + "the lock wallpaper is " + (video ? "a live one" : "a still picture")
                + (was == null ? "" : ", it was not"));
        // Release is paired with the take, always: a wallpaper that is no longer a video must
        // not be left with our canvas where its player's surface should be.
        if (!video && sVideoTakenOver) videoWindowTakeover(true);
    }

    private static boolean videoWindowTakeover(boolean on) {
        Object eng = sVideoEngine;
        if (eng == null) {
            Xp.log(TAG + "vgl: no video engine - is the lock wallpaper a video?");
            return false;
        }
        Object mgr = videoDepthManager();
        try {
            return mgr != null ? takeoverDepth(mgr, on) : takeoverPlain(eng, on);
        } catch (Throwable t) {
            Xp.log(TAG + "vgl failed: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /**
     * The depth shape: FastPlayer renders into three surfaces held by a VideoDepthManager, and
     * changeOpenGLSurface(alpha, on, normal, on, local, on) is the OEM's own way of switching
     * an individual output off. Asking the player to let go beats fighting it for the buffer -
     * a Surface connected to GL cannot also be locked for a software canvas.
     */
    private static boolean takeoverDepth(Object mgr, boolean on) throws Exception {
        // Deliberately does nothing. The depth shape cannot be taken over, and TRYING breaks
        // the video.
        //
        // Switching the local output off with changeOpenGLSurface(local=false) does not release
        // the buffer - FastPlayer's native GL context still holds the EGLSurface - so
        // lockCanvas throws IllegalArgumentException. Handing it a null local surface with
        // setSurface(alpha, normal, null, 12, null), the depth equivalent of d(null)/h(null) on
        // the plain shape, does not release it either. Both measured.
        //
        // The reverting version of this was worse than useless: setSurface is not a cheap
        // toggle, it re-initialises the player's outputs, and calling it to null and straight
        // back left the video frozen with no way home short of restarting the wallpaper
        // process. Reported from the device as "the video sticks and never becomes playable
        // again" - which is a broken lock screen, and strictly worse than this shape simply
        // not having the cover on its card and clock glass.
        //
        // So on this shape the cover is the keyguard-layer view alone: the background is right
        // and the media card blur and the clock glass keep showing the video. If this is ever
        // revisited, the thing to try is the Bitmap parameter setSurface already takes and MIUI
        // always passes null for - letting FastPlayer draw the picture would sidestep
        // lockCanvas entirely. Do not go back to disabling the output.
        if (!on && !sDepthWarned) {
            sDepthWarned = true;
            Xp.log(TAG + "vgl: this is the depth engine - the wallpaper window cannot be taken "
                    + "over on it, so the card blur and the clock glass keep the video. The "
                    + "cover is still drawn in the keyguard layer.");
        }
        return false;
    }

    /** So the explanation above is logged once per process, not once per track. */
    private static volatile boolean sDepthWarned;

    /**
     * The plain shape: a VideoPlayer on the engine's field `e` drawing into the engine's own
     * SurfaceHolder on `o` - which IS the wallpaper window. Stopping the player is what frees
     * the surface; start() puts the video back.
     */
    private static boolean takeoverPlain(Object eng, boolean on) throws Exception {
        Object player = Xp.getObjectField(eng, "e");
        Object holder = Xp.getObjectField(eng, "o");
        if (player == null || !(holder instanceof android.view.SurfaceHolder)) {
            Xp.log(TAG + "vgl: player=" + player + " holder=" + holder);
            return false;
        }
        android.view.SurfaceHolder h = (android.view.SurfaceHolder) holder;
        if (on) {
            if (!sVideoTakenOver) return true;
            sVideoTakenOver = false;
            // Give the surface BACK before starting. Taking it away was d(null)/h(null), and
            // start() on its own just plays into nowhere - the window keeps showing the last
            // frame we painted, i.e. the cover, for good. That is what "the video never comes
            // back" was.
            setPlayerHolder(player, h);
            Xp.callMethod(player, "start");
            Xp.log(TAG + "vgl(plain): surface handed back, player restarted");
        } else if (sVideoTakenOver) {
            // Already ours - a track change, not an entry. The player is stopped and the
            // surface is already detached, so this is one repaint and nothing else.
            paintCoverOnto(h.getSurface());
        } else {
            sVideoTakenOver = true;
            Xp.callMethod(player, "stop");
            // stop() ends the decode loop but leaves the surface connected to the decoder, and
            // a connected Surface cannot be locked for a software canvas - lockCanvas throws
            // IllegalArgumentException. Hand the player a null holder to make it let go.
            setPlayerHolder(player, null);
            Xp.log(TAG + "vgl(plain): player stopped and surface released");
            paintCoverOnto(h.getSurface());
        }
        return true;
    }

    /**
     * The VideoDepthManager, reached through the engine that owns it.
     *
     * Not from its own constructor - that runs before the module is loaded - and not from its
     * static instance field either, which is left null on this build. The engine is
     * constructible after we are in, and holds the manager on field `p`.
     */
    private static Object videoDepthManager() {
        Object mgr = sVideoDepth;
        if (mgr != null) return mgr;
        Object eng = sVideoEngine;
        if (eng == null) return null;
        try {
            mgr = Xp.getObjectField(eng, "p");
            // `p` is only the manager on the depth engine - on the plain one it is an unrelated
            // obfuscated field of the same name, and reading it as a manager was good for one
            // confusing "no field k1.f.j". The type is the thing that decides which shape this
            // engine is, so check it rather than trusting the field name.
            if (mgr != null && !mgr.getClass().getName().contains("VideoDepthManager")) {
                mgr = null;
            }
            if (mgr != null) {
                sVideoDepth = mgr;
                Xp.log(TAG + "video depth manager: " + mgr);
            }
            return mgr;
        } catch (Throwable t) {
            Xp.log(TAG + "vgl: cannot reach the VideoDepthManager: " + t);
            return null;
        }
    }

    /**
     * Sets - or clears, with null - the holder the VideoPlayer draws into.
     *
     * Both `d` and `h` take a SurfaceHolder and which one actually binds is an R8 name away
     * from being knowable, so both are called and whichever exists wins. Symmetric on purpose:
     * the release and the hand-back have to be the same pair, or the video never comes back.
     */
    private static void setPlayerHolder(Object player, android.view.SurfaceHolder holder) {
        for (String name : new String[]{"d", "h"}) {
            try {
                Method m = Xp.findMethodExact(player.getClass(), name,
                        android.view.SurfaceHolder.class);
                m.invoke(player, holder);
            } catch (Throwable t) {
                Xp.log(TAG + "vgl(plain): " + name + "(" + (holder == null ? "null" : "holder")
                        + ") -> " + t);
            }
        }
    }

    /** Draws the current art over a surface FastPlayer has just let go of. */
    private static boolean paintCoverOnto(android.view.Surface s) {
        Bitmap art = sArt;
        if (art == null || !s.isValid()) {
            Xp.log(TAG + "vgl: nothing to paint (art=" + describe(art)
                    + " valid=" + s.isValid() + ")");
            return false;
        }
        Canvas cv = null;
        boolean painted = false;
        try {
            cv = s.lockCanvas(null);
            RectF dst = new RectF(0, 0, cv.getWidth(), cv.getHeight());
            cv.drawBitmap(art, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
            Xp.log(TAG + "vgl: painted " + describe(art) + " onto the wallpaper window "
                    + cv.getWidth() + "x" + cv.getHeight());
            painted = true;
        } catch (Throwable t) {
            Xp.log(TAG + "vgl: lockCanvas failed: " + t);
        } finally {
            if (cv != null) {
                try {
                    s.unlockCanvasAndPost(cv);
                } catch (Throwable t) {
                    Xp.log(TAG + "vgl: unlockCanvasAndPost failed: " + t);
                }
            }
        }
        return painted;
    }

    private static void dumpClass(String name, String grep) {
        if (name == null) { Xp.log(TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = Xp.findClass(name, sCl);
        } catch (Throwable t) {
            Xp.log(TAG + name + " NOT FOUND");
            return;
        }
        Xp.log(TAG + "=== " + c.getName());
        String g = grep == null ? null : grep.toLowerCase();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder(m.getReturnType().getSimpleName())
                        .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int j = 0; j < ps.length; j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(ps[j].getSimpleName());
                }
                sb.append(')');
                if (g == null || sb.toString().toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + sb);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + line);
                }
            }
            Xp.log(TAG + "  --- ^ " + k.getName());
        }
    }

    /**
     * Hooks every method of a class that carries a Bitmap in or out and logs it, which is how
     * we find where the wallpaper texture actually enters the renderer.
     */
    private static void traceBitmaps(String name) {
        if (name == null) { Xp.log(TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = Xp.findClass(name, sCl);
        } catch (Throwable t) {
            Xp.log(TAG + name + " NOT FOUND");
            return;
        }
        int n = 0;
        for (java.lang.reflect.Constructor<?> ct : c.getDeclaredConstructors()) {
            boolean touches = false;
            for (Class<?> p : ct.getParameterTypes()) {
                if (p == Bitmap.class) touches = true;
            }
            if (!touches) continue;
            try {
                Xp.hook(ct, argLogger("<init>"));
                n++;
            } catch (Throwable ignored) {
            }
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (final Method m : k.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                boolean touches = m.getReturnType() == Bitmap.class;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p == Bitmap.class) touches = true;
                }
                if (!touches) continue;
                try {
                    Xp.hook(m, argLogger(m.getName()));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        Xp.log(TAG + "traced " + n + " bitmap-carrying methods on " + c.getName());
    }

    private static XposedInterface.Hooker argLogger(final String name) {
        return chain -> {
            Object result = chain.proceed();
            StringBuilder sb = new StringBuilder(TAG)
                    .append(chain.getThisObject() == null ? "?"
                            : chain.getThisObject().getClass().getSimpleName())
                    .append('.').append(name).append('(');
            java.util.List<Object> args = chain.getArgs();
            for (int j = 0; j < args.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(describe(args.get(j)));
            }
            sb.append(") -> ").append(describe(result));
            Xp.log(sb.toString());
            return result;
        };
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof Bitmap) {
            Bitmap b = (Bitmap) o;
            return "Bitmap[" + b.getWidth() + "x" + b.getHeight() + " " + b.getConfig() + "]";
        }
        String s = String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
