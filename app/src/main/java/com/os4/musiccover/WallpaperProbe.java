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
    /**
     * Give the keyguard a texture the size of the SCREEN instead of the size of the wallpaper
     * file. On by default; the `texfit` probe turns it off.
     *
     * MIUI builds the keyguard texture at whatever `WallpaperManager.peekBitmapDimensions()`
     * says, and `ImageGLWallpaper.setupTexture()` allocates it from the bitmap it is handed, so
     * a phone whose lock wallpaper is 2121x4712 uploads 38MB per swap - twice - and our art has
     * to be scaled UP to that size to keep the GL matrix (built from the same dimensions) honest.
     * It also puts the fade over its own threshold, so the swap is a cut. Measured on the phone
     * that reported it: 1.3s to re-fit the wallpaper, then 38MB twice on every swap.
     *
     * The fix the module shipped first rewrote the wallpaper FILE (fitLockWallpaperToScreen in
     * Main), which is what costs the user their depth cut-out: MIUI's subject segmentation is
     * tied to the wallpaper the picker set, and nothing re-analyses a file we wrote ourselves.
     *
     * This does it without touching the file. Both ends of the pair move together:
     *   - the bitmap the upload gets, fitted to the surface, in screenSized() below;
     *   - the rectangle updateMVPMatrix() builds the matrix from, in the hook further down.
     * With those agreeing, the texture is the screen's size and the picture is where it belongs.
     * On a phone whose lock wallpaper IS the screen's size both halves return early and nothing
     * happens at all, which is every phone until someone picks a big picture.
     *
     * sKeyguardTexture is the wallpaper process's own ImageWallpaperRenderer$WallpaperTexture for
     * the KEYGUARD renderer - the desktop wallpaper shares this code and is not ours to resize.
     */
    private static volatile boolean sTexFit = true;
    private static volatile Object sKeyguardTexture;
    private static Bitmap sScreenArt;
    private static Bitmap sScreenArtOf;
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

    /** Asked for the cover again, at most this often. */
    private static final long ASK_MIN_MS = 2000L;
    /**
     * How many times one dry spell may ask.
     *
     * The gap between this process starting and SystemUI's receiver being up is about two
     * seconds wide, and both of the asks below fire inside it: measured on device, process start
     * at 18:53:04.169 and the engine at 18:53:05.064, SystemUI not loaded until 18:53:06.065,
     * and neither request arrived. So the ask is retried from the renderer for a few seconds.
     *
     * Nothing is retried once art has arrived, which is what keeps a cover that is simply
     * switched off - where this process is empty by design and always will be - from asking
     * forever.
     */
    private static final int ASK_TRIES = 5;
    /**
     * When askForArt() last sent a request, and how many it has sent since art last arrived.
     * Written from the GL thread as well as the main one, so these are only ever coarse.
     */
    private static volatile long sAskedAt;
    private static volatile int sAsks;

    /**
     * Tells SystemUI that this process has no cover to draw, so it should send one again.
     *
     * The push is one-shot and SystemUI only pushes on a track change - and once it has pushed,
     * the same-artwork rule suppresses every later push for that song. So art composed while the
     * wallpaper process was not up is lost for the whole track: measured on device, nine pushes
     * between 16:23 and 16:24 with the receiver here only registering at 16:25:17, and not one of
     * them arrived - the lock screen stayed without a cover until the next track. Nothing on the
     * SystemUI side can notice, because it recorded the print as sent the moment it sent it.
     *
     * So the side that knows it is empty does the asking. Only ever called when there is nothing
     * here, which is also what stops it once the art arrives.
     */
    private static void askForArt(String why) {
        Context c = sCtx;
        if (c == null || sAsks >= ASK_TRIES) return;
        long now = SystemClock.uptimeMillis();
        if (now - sAskedAt < ASK_MIN_MS) return;
        sAskedAt = now;
        sAsks++;
        try {
            Intent out = new Intent("com.os4.musiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.putExtra("op", "needart");
            out.putExtra("why", why);
            c.sendBroadcast(out);
            Xp.log(TAG + "no art here, asked SystemUI for it (" + why + " "
                    + sAsks + "/" + ASK_TRIES + ")");
        } catch (Throwable t) {
            Xp.log(TAG + "askForArt failed: " + t);
        }
    }

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
     * lagging behind it. Hence the ease-out below.
     *
     * The length itself is matched to the clock's spring rather than picked, and the two moved
     * together when the spring was changed to the OEM's own curve (EASE_COVER): the clock now
     * covers 95% of its travel at 235ms, and a cubic ease-out of length T covers its own 95% at
     * 0.632*T - so 370ms here against the 240 that went with the old, faster spring. Both
     * numbers reach 95% within a millisecond of each other, which is the whole of the rule:
     * 240ms of fade against a 235ms clock would have left the wallpaper sitting still while the
     * clock was still visibly growing, and 430 (the first attempt) is the same fault the other
     * way round.
     *
     * The number is no longer decided here. The clock's response is a setting now, and this is
     * the fade that belongs to it - proportional, because the same 95%-against-95% rule holds
     * for every response once zeta is fixed. SystemUI sends it when it changes and again on
     * every cover entry, and that second push is what covers a restart of this process: the
     * field below is a static with nothing behind it, so the 370 it starts at is only ever
     * right until the first push arrives.
     *
     * The op is kept for the case where SystemUI is not the one being tested:
     *   --es op fadems --ei v 370
     */
    private static volatile long sFadeMs = 370L;
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
    /** Target bitmap to fast-forward setUpMixFrost on the very first frame of a fade. */
    private static volatile Bitmap sFrostTargetBitmap;

    /** Whether keyguard (lockscreen) is currently showing. Defaults to true. */
    private static volatile boolean sKeyguardShowing = true;
    /** Whether video cover is suspended because phone is unlocked into desktop. */
    private static volatile boolean sCoverSuspended = false;

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
                    if (sKeyguardTexture == null) {
                        // Kept so the dimension hook can tell the keyguard's texture from the
                        // desktop one's: same class, two instances, and only one of them is ours.
                        try {
                            sKeyguardTexture = Xp.getObjectField(chain.getThisObject(), "mTexture");
                        } catch (Throwable ignored) {
                        }
                    }
                    // The experiment's other half. Everything below - the fade size check, the
                    // art fit, sReportedW/H that fittedArt() scales by - then sees the screen's
                    // size rather than the wallpaper file's, which is the whole point.
                    Bitmap screen = screenSized(orig);
                    if (screen != null) {
                        orig = screen;
                        args[0] = screen;
                    }
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
                    if (art == null) {
                        rememberOriginal(orig);
                        // Drawing the keyguard with no cover to draw: the one moment worth
                        // asking, and the retry for the two asks above that fire before
                        // SystemUI's receiver exists. Rate-limited and capped in askForArt().
                        askForArt("renderer");
                    }
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

        // The experiment's other half. updateMVPMatrix(surfaceW, surfaceH, getTextureDimensions())
        // builds the GL matrix from this rectangle, so a screen-sized upload with a
        // wallpaper-sized source rect draws the picture whichever way the two disagree - a corner
        // of it in a corner of the screen, which is the failure our own art-fit comment describes.
        // Both ends move together or neither does.
        try {
            Class<?> tex = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer$WallpaperTexture", sCl);
            Xp.hookAll(tex, "getTextureDimensions", chain -> {
                Object self = chain.getThisObject();
                if (!sTexFit || self == null || self != sKeyguardTexture) return chain.proceed();
                int w = sSurfaceW, h = sSurfaceH;
                if (w <= 0 || h <= 0) return chain.proceed();
                return new android.graphics.Rect(0, 0, w, h);
            });
            Xp.log(TAG + "texture dimension hook installed");
        } catch (Throwable t) {
            Xp.log(TAG + "texture dimension hook failed: " + t);
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
                // From here the lock screen has an image engine, so this is the first moment a
                // cover has somewhere to go - and by now the receiver above is up, which is what
                // the pushes that went missing did not have.
                if (sArt == null) askForArt("keyguard engine");
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
                    sVideoEngine = self;
                    sVideoDepth = null;
                    Xp.log(TAG + "video engine captured: " + self.getClass().getName());
                    return result;
                });
                hookVideoPathGetter(vd);

                // Hook lock/unlock lifecycle to restore desktop wallpaper when unlocked and re-apply cover when locked
                for (Method m : vd.getDeclaredMethods()) {
                    if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) {
                        continue;
                    }
                    if ("hideKeyguardWallpaper".equals(m.getName()) && m.getParameterCount() == 0) {
                        Xp.hook(m, chain -> {
                            Object self = chain.getThisObject();
                            onKeyguardStateChanged(self, false, "hideKeyguardWallpaper");
                            return chain.proceed();
                        });
                        Xp.log(TAG + "hooked " + cn.substring(cn.lastIndexOf('.') + 1) + ".hideKeyguardWallpaper()");
                    } else if ("showKeyguardWallpaper".equals(m.getName()) && m.getParameterCount() == 2) {
                        Xp.hook(m, chain -> {
                            Object self = chain.getThisObject();
                            onKeyguardStateChanged(self, true, "showKeyguardWallpaper");
                            return chain.proceed();
                        });
                        Xp.log(TAG + "hooked " + cn.substring(cn.lastIndexOf('.') + 1) + ".showKeyguardWallpaper(Z, I)");
                    } else if ("showWallpaperUnlockAnim".equals(m.getName()) && m.getParameterCount() == 0) {
                        Xp.hook(m, chain -> {
                            Object self = chain.getThisObject();
                            onKeyguardStateChanged(self, false, "showWallpaperUnlockAnim");
                            return chain.proceed();
                        });
                        Xp.log(TAG + "hooked " + cn.substring(cn.lastIndexOf('.') + 1) + ".showWallpaperUnlockAnim()");
                    }
                }

                Xp.log(TAG + "video engine hooked on " + cn.substring(cn.lastIndexOf('.') + 1));
            } catch (Throwable t) {
                Xp.log(TAG + "video engine hook failed on " + cn + ": " + t);
            }
        }

        // Also capture the engine whenever UniversalEngine attaches or switches engines
        try {
            Class<?> ue = Xp.findClass("com.miui.miwallpaper.wallpaperservice.UniversalWallpaper$UniversalEngine", sCl);
            Xp.hookAll(ue, "onCreate", chain -> {
                Object r = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Object eng = Xp.getObjectField(self, "f");
                    if (eng != null && eng.getClass().getName().contains("Video")) {
                        sVideoEngine = eng;
                        sVideoDepth = null;
                        Xp.log(TAG + "video engine captured via UniversalEngine.onCreate: " + eng.getClass().getName());
                    }
                } catch (Throwable ignored) {}
                return r;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "hook UniversalEngine failed: " + t);
        }

        // Intercept WallpaperServiceController.m1159s(1/2, false) to return cover video when active
        try {
            Class<?> wsc = Xp.findClass("com.miui.miwallpaper.manager.WallpaperServiceController", sCl);
            for (Method m : wsc.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) {
                    continue;
                }
                if (m.getReturnType() == String.class && m.getParameterCount() == 2) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0] == int.class && p[1] == boolean.class) {
                        Xp.hook(m, chain -> {
                            Object[] args = chain.getArgs().toArray();
                            int which = (Integer) args[0];
                            boolean isPreview = (Boolean) args[1];
                            if ((which == 1 || which == 2) && !isPreview && sCoverVideoActive && sCoverVideoPath != null) {
                                Xp.log(TAG + "intercepted WallpaperServiceController." + m.getName()
                                        + "(" + which + ", false) -> " + sCoverVideoPath);
                                return sCoverVideoPath;
                            }
                            return chain.proceed();
                        });
                        Xp.log(TAG + "hooked WallpaperServiceController." + m.getName() + "(int, boolean)");
                    }
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "hook WallpaperServiceController failed: " + t);
        }

        // Prevent FastPlayer from high-frequency decoding the 1-frame cover video in an infinite loop
        try {
            Class<?> fp = Xp.findClass("com.miui.fastplayer.FastPlayer", sCl);
            Xp.hookAll(fp, "setLoop", chain -> {
                if (sCoverVideoActive) {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = Boolean.FALSE;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            Xp.log(TAG + "hooked FastPlayer.setLoop");
        } catch (Throwable t) {
            Xp.log(TAG + "hook FastPlayer.setLoop failed: " + t);
        }

        // The frosted copy the notification and media cards blur against is regenerated on
        // every texture upload. During a fade, fast-forward to the target cover's blur on the
        // very first frame so notification cards update instantly without delay ("慢半拍"),
        // while skipping intermediate frames (2..N) to preserve 120fps smoothness.
        try {
            Class<?> ap = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimatorProgram", sCl);
            Xp.hookAll(ap, "setUpMixFrost", chain -> {
                if (sFrostSkipping) {
                    Bitmap target = sFrostTargetBitmap;
                    if (target != null && !target.isRecycled()) {
                        sFrostTargetBitmap = null;
                        Object[] args = chain.getArgs().toArray();
                        args[0] = target;
                        Xp.log(TAG + "setUpMixFrost: fast-forwarded to target art blur on frame 1");
                        return chain.proceed(args);
                    }
                    return null;
                }
                return chain.proceed();
            });
            Xp.log(TAG + "frosting hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "frosting hook failed: " + t);
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
            sAsks = 0;
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
        sFrostTargetBitmap = to;
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
        sFrostTargetBitmap = null;
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
        // Restored from disk or not: either way the cover that belongs on screen is the one
        // SystemUI holds, and SystemUI has no way to learn ours is missing.
        if (sArt == null) askForArt("process start");
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
                    } else if ("texfit".equals(op)) {
                        // The one switch for the screen-sized keyguard texture, which is on unless
                        // this turns it off: if a phone ever draws the wallpaper into a corner,
                        // this is what to reach for. A reload is what makes the next upload
                        // re-read every one of these numbers, and reloadTexture() is also how the
                        // surface size gets known here (the first upload of a process runs before
                        // onSurfaceChanged has set it).
                        sTexFit = i.getBooleanExtra("on", !sTexFit);
                        Xp.log(TAG + "texture fit to screen " + (sTexFit ? "ON" : "off")
                                + " (surface " + sSurfaceW + "x" + sSurfaceH + ")");
                        reloadTexture();
                    } else if ("art".equals(op)) {
                        final Context cc = c;
                        boolean reload = i.getBooleanExtra("reload", false);
                        // A fade needs both ends of it in this process. Missing either one is
                        // not a failure, it is the old behaviour: swap the texture in one frame.
                        boolean fade = reload && i.getBooleanExtra("fade", false);
                        if (i.getBooleanExtra("off", false)) {
                            // A live wallpaper has no texture to fade back to - the way back
                            // is handing the surface to its player again.
                            sCoverSuspended = false;
                            if (videoPath()) {
                                sCurrentArtChecksum = 0;
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
                                        sCurrentArtChecksum = 0;
                                        sArt = null;
                                        sFitted = null;
                                        sFittedOf = null;
                                        new File(cc.getFilesDir(), ART_FILE).delete();
                                        Xp.log(TAG + "art cleared");
                                    }
                                });
                                return;
                            }
                            sCurrentArtChecksum = 0;
                            sArt = null;
                            new File(c.getFilesDir(), ART_FILE).delete();
                            Xp.log(TAG + "art cleared");
                        } else {
                            byte[] jpg = i.getByteArrayExtra("jpg");
                            String file = i.getStringExtra("file");
                            Bitmap b = null;
                            if (jpg != null) {
                                b = decodeToTextureSize(jpg);
                                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                                crc.update(jpg);
                                sCurrentArtChecksum = crc.getValue();
                            } else if (file != null) {
                                b = BitmapFactory.decodeFile(file);
                                sCurrentArtChecksum = 0;
                            }
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
                                sAsks = 0;
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
                                + " videoEngine=" + sVideoEngine
                                + " coverVideo=" + sCoverVideoActive
                                + " coverSuspended=" + sCoverSuspended
                                + " kgShowing=" + sKeyguardShowing
                                + " pinned=" + sPathPinned);
                        dumpVideoManager();
                    } else if ("keyguard_state".equals(op)) {
                        boolean showing = i.getBooleanExtra("showing", true);
                        Xp.log(TAG + "recv keyguard_state showing=" + showing);
                        onKeyguardStateChanged(sVideoEngine, showing, "broadcast");
                    } else if ("vpath".equals(op)) {
                        dumpVideoManager();
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
     * The ways to ask the engine to run its preRender step, and the arguments it takes there.
     *
     * Ordered newest-name-first. The names are R8-obfuscated (OS4.0.0.35: u, b, T) and a build
     * that renamed one used to take the whole reload down with it - the log below is what that
     * looked like, and what it cost is in Handoff 27.
     */
    private static final Object[][] FRAME_REQUESTS = {
            {"T", Boolean.FALSE},
            // OS4.0.0.17's name for the same method, read off its bytecode rather than inferred
            // from the log: there ImageEngineImpl.M(Z) and U(Z) stand where 0.35 has L(Z) and
            // T(Z). M and L are instruction-for-instruction identical, and U and T both open on
            // `iget-boolean h:Z; if-eqz; iget-object t:HashMap; s()` - the post-a-frame path.
            // What settles the pairing is the rest of that set: changeScrollWithScreen and g
            // kept their names across both builds, and those are what align the two lists.
            {"U", Boolean.FALSE},
            // The OEM's own showKeyguardWallpaper(ZI)/hideKeyguardWallpaper(ZI) take a boolean
            // and an int, so a build that widened the frame request the same way is worth one
            // more try. 0 is the no-animation value everywhere these appear.
            {"T", Boolean.FALSE, Integer.valueOf(0)},
    };

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
    private static void reloadTexture() {
        Object eng = sKeyguardEngine;
        if (eng == null) {
            Xp.log(TAG + "reload: no keyguard engine (is the lockscreen wallpaper "
                    + "still the same image as the desktop one?)");
            return;
        }
        try {
            Xp.callMethod(eng, "u");
        } catch (Throwable t) {
            Xp.log(TAG + "reload: u() failed: " + t);
        }
        try {
            Xp.setBooleanField(eng, "b", true);
        } catch (Throwable t) {
            Xp.log(TAG + "reload: the pending-surface field failed: " + t);
        }
        for (Object[] req : FRAME_REQUESTS) {
            String name = (String) req[0];
            Object[] args = new Object[req.length - 1];
            System.arraycopy(req, 1, args, 0, args.length);
            try {
                Xp.callMethod(eng, name, args);
                Xp.log(TAG + "reload requested on " + eng.getClass().getSimpleName()
                        + " via " + name + "()");
                return;
            } catch (Throwable ignored) {
            }
        }
        // Nothing to call, so say what this build DOES have: the methods taking one boolean are
        // the only candidates, and naming them is the whole of what re-deriving the name needs.
        Xp.log(TAG + "reload: no frame request on " + eng.getClass().getSimpleName()
                + " - the texture keeps what it holds until the OEM rebuilds the surface."
                + " Methods here that take one boolean: " + oneBooleanMethods(eng));
    }

    /** One-boolean methods declared on the engine and its supers, for re-deriving a name. */
    private static String oneBooleanMethods(Object eng) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Class<?> c = eng.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 1 || p[0] != boolean.class) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(c.getSimpleName()).append('.').append(m.getName()).append("(Z)");
                }
            }
        } catch (Throwable t) {
            return "could not be listed: " + t;
        }
        return sb.length() == 0 ? "none" : sb.toString();
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
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardVideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardVideoEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopVideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopVideoEngineImpl",
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
    /** Whether the video wallpaper is currently playing our 1-frame cover video. */
    private static volatile boolean sCoverVideoActive;
    private static volatile String sCoverVideoPath;
    private static volatile long sCurrentArtChecksum;
    /**
     * The wallpaper's own playback path, held while cover mode has the manager's field.
     *
     * Read out of the field before we overwrite it rather than asked of the engine, because the
     * engine's path getter is the thing we are shadowing - calling it while cover mode is on
     * would hand back our own file.
     */
    private static volatile String sOriginalVideoPath;
    /** Whether the manager's path field is currently ours rather than the wallpaper's. */
    private static volatile boolean sPathPinned;
    private static final String COVER_VIDEO_FILE = "mc_cover.mp4";
    private static final java.util.concurrent.ExecutorService sVideoWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * What the lock wallpaper is now, as SystemUI reads it before every push. null until a push
     * has said.
     */
    private static volatile Boolean sLockIsVideo;

    /**
     * Whether this push is for a live lock wallpaper.
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
        if (!video && sCoverVideoActive) videoWindowTakeover(true);
    }

    private static volatile long sSavedVideoPositionUs = 0;

    private static boolean isDepthEngine(Object eng) {
        if (eng == null) return false;
        return eng.getClass().getName().contains("Depth");
    }

    private static long getDepthVideoPositionUs(Object eng) {
        Object mgr = videoDepthManager(eng);
        if (mgr == null) return 0;
        try {
            Object fp = Xp.getObjectField(mgr, "j");
            if (fp != null) {
                long us = (Long) Xp.callMethod(fp, "getCurrentPositionUs");
                if (us > 0) return us;
            }
        } catch (Throwable ignored) {
        }
        try {
            long us = (Long) Xp.getObjectField(mgr, "w");
            if (us > 0) return us;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void restoreDepthVideoPosition(Object eng, long posUs) {
        if (posUs <= 0) return;
        long posMs = posUs / 1000;
        if (posMs >= 1500 || posMs <= 100) {
            Xp.log(TAG + "restoreDepthVideoPosition: posMs=" + posMs + " near end/start, replaying naturally");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Object mgr = videoDepthManager(eng);
                if (mgr != null) {
                    Xp.callMethod(mgr, "m1078h", posMs);
                    Xp.log(TAG + "restoreDepthVideoPosition: seeked to " + posMs + "ms via depth manager");
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object mgr = videoDepthManager(eng);
                if (mgr != null) {
                    Object fp = Xp.getObjectField(mgr, "j");
                    if (fp != null) {
                        Xp.callMethod(fp, "seekto", 0.0f, posMs, 0);
                        Xp.log(TAG + "restoreDepthVideoPosition: FastPlayer seekto " + posMs + "ms");
                    }
                }
            } catch (Throwable t) {
                Xp.log(TAG + "restoreDepthVideoPosition failed: " + t);
            }
        }, 350L);
    }

    private static long getVideoPositionUs(Object eng) {
        if (eng == null) return 0;
        if (isDepthEngine(eng)) {
            return getDepthVideoPositionUs(eng);
        }
        // Plain engine: KeyguardVideoEngineImpl -> f3121e (field "e", FastPlayerImpl) -> f2256t (field "t", FastPlayer)
        try {
            Object player = Xp.getObjectField(eng, "e");
            if (player != null) {
                Object fp = Xp.getObjectField(player, "t");
                if (fp != null) {
                    try {
                        long us = (Long) Xp.callMethod(fp, "getCurrentPositionUs");
                        if (us > 0) return us;
                    } catch (Throwable ignored) {
                    }
                    try {
                        long ms = (Long) Xp.callMethod(fp, "getCurrentPosition");
                        if (ms > 0) return ms * 1000L;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void restoreVideoPosition(Object eng, long posUs) {
        if (posUs <= 0 || eng == null) return;
        if (isDepthEngine(eng)) {
            restoreDepthVideoPosition(eng, posUs);
            return;
        }
        long posMs = posUs / 1000L;
        // If playback was already near the end (>=1500ms for a ~2000ms lock video) or just starting (<=100ms),
        // do not seek, let it naturally replay from 0 without jumping.
        if (posMs >= 1500 || posMs <= 100) {
            Xp.log(TAG + "restorePlainVideoPosition: posMs=" + posMs
                    + " is near end/start, replaying naturally from 0 without jump");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Object player = Xp.getObjectField(eng, "e");
                if (player != null) {
                    try {
                        Xp.callMethod(player, "mo1345i", posMs);
                        Xp.log(TAG + "restorePlainVideoPosition: seeked to " + posMs + "ms via player.mo1345i");
                        return;
                    } catch (Throwable ignored) {
                    }
                    try {
                        Xp.callMethod(player, "mo1047i", posMs);
                        Xp.log(TAG + "restorePlainVideoPosition: seeked to " + posMs + "ms via player.mo1047i");
                        return;
                    } catch (Throwable ignored) {
                    }
                    Object fp = Xp.getObjectField(player, "t");
                    if (fp != null) {
                        Xp.callMethod(fp, "seekto", 0.0f, posMs, 0);
                        Xp.log(TAG + "restorePlainVideoPosition: seeked to " + posMs + "ms via FastPlayer.seekto");
                        return;
                    }
                }
            } catch (Throwable t) {
                Xp.log(TAG + "restorePlainVideoPosition failed: " + t);
            }
        });
    }

    private static boolean isDesktopEngine(Object eng) {
        return eng != null && eng.getClass().getName().contains("Desktop");
    }

    private static synchronized void onKeyguardStateChanged(Object eng, boolean showing, String reason) {
        boolean was = sKeyguardShowing;
        sKeyguardShowing = showing;
        if (eng == null) eng = sVideoEngine;
        Xp.log(TAG + "onKeyguardStateChanged: showing=" + showing + " (was " + was + ") reason=" + reason
                + " coverActive=" + sCoverVideoActive + " coverSuspended=" + sCoverSuspended
                + " engine=" + (eng == null ? "null" : eng.getClass().getSimpleName()));

        if (eng == null || !isDesktopEngine(eng)) return;

        if (!showing) {
            // Unlocked into desktop: suspend cover video and restore desktop video wallpaper
            if (sCoverVideoActive) {
                sCoverSuspended = true;
                sCoverVideoActive = false;
                Xp.log(TAG + "onKeyguardStateChanged: suspending cover video on desktop, restoring desktop wallpaper");
                restoreVideoPath(eng);
                triggerVideoReload(eng);
                if (sSavedVideoPositionUs > 0) {
                    restoreVideoPosition(eng, sSavedVideoPositionUs);
                    sSavedVideoPositionUs = 0;
                }
            }
        } else {
            // Keyguard / lockscreen showing: if cover was suspended and art is still active, restore cover
            if (sCoverSuspended && sArt != null && sCoverVideoPath != null) {
                File vf = new File(sCoverVideoPath);
                if (vf.exists() && vf.length() > 0) {
                    sCoverSuspended = false;
                    sCoverVideoActive = true;
                    Xp.log(TAG + "onKeyguardStateChanged: re-activating cover video for lockscreen on "
                            + eng.getClass().getSimpleName());
                    pinVideoPath(eng);
                    triggerVideoReload(eng);
                }
            }
        }
    }

    /**
     * Controls dynamic video wallpaper cover mode.
     * @param on true to restore the original video wallpaper, false to activate the album cover video
     */
    private static boolean videoWindowTakeover(boolean on) {
        Object eng = sVideoEngine;
        if (eng == null) {
            Xp.log(TAG + "videoWindowTakeover: no video engine captured yet");
            return false;
        }

        if (on) {
            sCoverSuspended = false;
            if (!sCoverVideoActive) return true;
            sCoverVideoActive = false;
            sCoverVideoPath = null;
            Xp.log(TAG + "videoWindowTakeover: restoring original video wallpaper");
            restoreVideoPath(eng);
            triggerVideoReload(eng);
            if (sSavedVideoPositionUs > 0) {
                restoreVideoPosition(eng, sSavedVideoPositionUs);
                sSavedVideoPositionUs = 0;
            }
            return true;
        } else {
            final Bitmap art = sArt;
            if (art == null) {
                Xp.log(TAG + "videoWindowTakeover: sArt is null, cannot make cover video");
                return false;
            }
            final Context ctx = sCtx;
            if (ctx == null) {
                Xp.log(TAG + "videoWindowTakeover: sCtx is null");
                return false;
            }
            if (!sCoverVideoActive) {
                long posUs = getVideoPositionUs(eng);
                if (posUs > 0) sSavedVideoPositionUs = posUs;
                Xp.log(TAG + "videoWindowTakeover: saved playback position: "
                        + (sSavedVideoPositionUs / 1000) + "ms");
            }
            final long checksum = sCurrentArtChecksum;

            // If phone is currently unlocked on desktop, do NOT reload desktop video!
            final boolean onDesktop = !sKeyguardShowing && isDesktopEngine(eng);
            if (onDesktop) {
                sCoverSuspended = true;
                sCoverVideoActive = false;
                Xp.log(TAG + "videoWindowTakeover: phone currently unlocked on desktop, pre-encoding cover in background");
            }

            sVideoWorker.submit(() -> {
                try {
                    File videoFile = new File(ctx.getFilesDir(), COVER_VIDEO_FILE);
                    int w = sReportedW > 0 ? sReportedW : ctx.getResources().getDisplayMetrics().widthPixels;
                    int h = sReportedH > 0 ? sReportedH : ctx.getResources().getDisplayMetrics().heightPixels;
                    Bitmap fitted = centerCrop(art, (w / 2) * 2, (h / 2) * 2);

                    boolean ok = CoverVideoEncoder.encodeBitmapToMp4(fitted, videoFile, checksum);
                    if (ok && videoFile.exists() && videoFile.length() > 0) {
                        sCoverVideoPath = videoFile.getAbsolutePath();
                        if (onDesktop || (!sKeyguardShowing && isDesktopEngine(eng))) {
                            sCoverSuspended = true;
                            sCoverVideoActive = false;
                            Xp.log(TAG + "videoWindowTakeover: cover video pre-encoded (" + videoFile.length()
                                    + "B), waiting for lockscreen to activate");
                        } else {
                            sCoverVideoActive = true;
                            sCoverSuspended = false;
                            Xp.log(TAG + "videoWindowTakeover: cover video ready (" + videoFile.length()
                                    + "B), triggering reload on " + eng.getClass().getSimpleName());
                            // Pin BEFORE the reload: the reload is what rebuilds the player, and it
                            // reads the manager's cached path rather than our hooked getter.
                            pinVideoPath(eng);
                            triggerVideoReload(eng);
                        }
                    } else {
                        Xp.log(TAG + "videoWindowTakeover: cover video encoding failed");
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "videoWindowTakeover: worker error: " + t);
                }
            });
            return true;
        }
    }

    /**
     * The VideoDepthManager, reached through the engine that owns it.
     *
     * Not from its own constructor - that runs before the module is loaded - and not from its
     * static instance field either, which is left null on this build. The engine is
     * constructible after we are in, and holds the manager on field `p`: read straight off the
     * disassembly of VideoDepthEngineImpl.T(), which does
     * `iget-object v1, v4, VideoDepthEngineImpl;.p:L.../videodepth/VideoDepthManager;`.
     *
     * `p` is only the manager on the depth engine - on the plain one it is an unrelated
     * obfuscated field of the same name, and reading it as a manager was good for one confusing
     * "no field k1.f.j". The type is the thing that decides which shape this engine is, so
     * check it rather than trusting the field name.
     */
    private static Object videoDepthManager(Object eng) {
        if (eng == null) return null;
        try {
            Object mgr = Xp.getObjectField(eng, "p");
            if (mgr != null && mgr.getClass().getName().contains("VideoDepthManager")) {
                sVideoDepth = mgr;
                return mgr;
            }
        } catch (Throwable ignored) {
        }
        // Fallback: search declared fields across class hierarchy by type
        Class<?> c = eng.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType().getName().contains("VideoDepthManager")) {
                    f.setAccessible(true);
                    try {
                        Object mgr = f.get(eng);
                        if (mgr != null) {
                            sVideoDepth = mgr;
                            return mgr;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            c = c.getSuperclass();
        }
        // Fallback: static instance on VideoDepthManager
        try {
            Class<?> vdmCls = Xp.findClass("com.miui.miwallpaper.container.videodepth.VideoDepthManager", sCl);
            for (java.lang.reflect.Field f : vdmCls.getDeclaredFields()) {
                if (f.getType() == vdmCls && Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    Object mgr = f.get(null);
                    if (mgr != null) {
                        sVideoDepth = mgr;
                        return mgr;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * The one String field on the VideoDepthManager: the path its player is opened with.
     *
     * Found by TYPE, not by name. The name is R8's (`q` on OS4.0.0.35) and this module has been
     * broken by a rename before, but there is exactly one String field on the class - the
     * disassembly of VideoDepthEngineImpl.T() shows it written from the engine's own path
     * getter and read by the method that opens the player - so the type is the stronger
     * identity. Refuses rather than guesses if a build ever has two.
     */
    private static java.lang.reflect.Field videoPathField(Object mgr) {
        if (mgr == null) return null;
        java.lang.reflect.Field found = null;
        for (java.lang.reflect.Field f : mgr.getClass().getDeclaredFields()) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
            if ("q".equals(f.getName())) {
                f.setAccessible(true);
                return f;
            }
            if (found != null) {
                Xp.log(TAG + "vgl: " + mgr.getClass().getSimpleName() + " carries more than one "
                        + "String field (" + found.getName() + ", " + f.getName()
                        + ") - not guessing which is the path");
                return null;
            }
            found = f;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    /** What the manager would open its player with right now. */
    private static String videoPathFieldValue(Object mgr) {
        java.lang.reflect.Field f = videoPathField(mgr);
        if (f == null) return null;
        try {
            return (String) f.get(mgr);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Points the depth manager's cached playback path at `path`. Returns false if it could not.
     *
     * This is the fix for why the cover never appeared on this shape. The engine's own T() is
     * the only writer of that field and the only caller of the path getter this module hooks,
     * and onWallpaperUpdate - the reload we trigger - rebuilds the surfaces and the player
     * WITHOUT re-running T(). Measured on the device: `releaseAndInitFastPlayer` ->
     * `init fastplayer` -> `setDataSource path = /data/system/theme_magic/.../lock_wallpaper_video.mp4`,
     * i.e. the intercepted path was loaded once and then thrown away on the next re-init, and
     * the player kept the wallpaper's own video. Writing the field is what survives the reload.
     */
    private static boolean setVideoPath(Object mgr, String path) {
        java.lang.reflect.Field f = videoPathField(mgr);
        if (f == null) return false;
        try {
            f.set(mgr, path);
            Xp.log(TAG + "vgl: playback path field " + f.getName() + " := " + path);
            return true;
        } catch (Throwable t) {
            Xp.log(TAG + "vgl: cannot write the playback path field: " + t);
            return false;
        }
    }

    /**
     * The depth manager as it actually stands, read off the live object.
     *
     * Exists because the paper trail on this shape is not enough to debug it with: the OEM's
     * own `VideoDepthManager##setDataSource` line says which path the ENGINE handed over, and
     * the question after that is always whether the manager's cached field still holds it. This
     * is the one place both are visible at the same moment.
     */
    private static void dumpVideoManager() {
        Object eng = sVideoEngine;
        if (eng == null) {
            Xp.log(TAG + "vmanager: no video engine captured");
            return;
        }
        Object mgr = videoDepthManager(eng);
        if (mgr == null) {
            Xp.log(TAG + "vmanager: engine is " + eng.getClass().getSimpleName()
                    + " with no VideoDepthManager (plain shape)");
            return;
        }
        int surfaces = 0;
        StringBuilder sb = new StringBuilder();
        try {
            for (java.lang.reflect.Field f : mgr.getClass().getDeclaredFields()) {
                if (f.getType() != android.view.Surface.class || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(mgr);
                if (v != null) surfaces++;
                if (sb.length() > 0) sb.append(", ");
                sb.append(f.getName()).append(v == null ? "=null" : "=set");
            }
        } catch (Throwable t) {
            sb.append("unreadable: ").append(t);
        }
        Xp.log(TAG + "vmanager: " + mgr.getClass().getSimpleName()
                + " surfaces=" + surfaces + "/6 [" + sb + "]"
                + " cachedPath=" + describe(videoPathFieldValue(mgr))
                + " originalPath=" + describe(sOriginalVideoPath)
                + " pinned=" + sPathPinned);
    }

    /** Writes our cover file into the manager's cached path, remembering the wallpaper's own. */
    private static void pinVideoPath(Object eng) {
        Object mgr = videoDepthManager(eng);
        if (mgr == null) {
            // Not a failure on the plain shape, where the author's device works without this:
            // there the reload does re-read the hooked getter.
            Xp.log(TAG + "vgl: no VideoDepthManager - the path stays unpinned, so the cover "
                    + "survives only if the engine re-reads its getter on the reload");
            return;
        }
        String cur = videoPathFieldValue(mgr);
        if (cur != null && !cur.equals(sCoverVideoPath)) {
            sOriginalVideoPath = cur;
            Xp.log(TAG + "vgl: remembered wallpaper's own playback path: " + sOriginalVideoPath);
        }
        sPathPinned = setVideoPath(mgr, sCoverVideoPath);
    }

    /** Puts the wallpaper's own path back. The reload that follows re-opens the player on it. */
    private static void restoreVideoPath(Object eng) {
        Object mgr = videoDepthManager(eng);
        if (mgr == null) return;
        String original = sOriginalVideoPath;
        if (original == null || original.equals(sCoverVideoPath)) {
            try {
                Class<?> wsc = Xp.findClass("com.miui.miwallpaper.manager.WallpaperServiceController", sCl);
                Object ctrl = Xp.callStaticMethod(wsc, "m1417l");
                int which = eng.getClass().getName().contains("Desktop") ? 1 : 2;
                original = (String) Xp.callMethod(ctrl, "m1457s", which, false);
            } catch (Throwable ignored) {
            }
        }
        if (original != null && !original.equals(sCoverVideoPath)) {
            sOriginalVideoPath = original;
            setVideoPath(mgr, original);
            Xp.log(TAG + "vgl: restored video path: " + original);
        } else {
            Xp.log(TAG + "vgl: never learned the wallpaper's own path - the field still holds "
                    + describe(videoPathFieldValue(mgr)) + ", leaving it alone");
        }
        sPathPinned = false;
    }

    private static void triggerVideoReload(Object eng) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                int which = eng.getClass().getName().contains("Desktop") ? 1 : 2;
                Xp.callMethod(eng, "onWallpaperUpdate", "video", which);
                Xp.log(TAG + "triggerVideoReload: onWallpaperUpdate('video', " + which + ") called on "
                        + eng.getClass().getSimpleName());
            } catch (Throwable t) {
                Xp.log(TAG + "triggerVideoReload failed: " + t);
            }
        });
    }

    private static void hookVideoPathGetter(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            // The path getter is ABSTRACT on the base class and implemented on the concrete
            // subclasses, and libxposed refuses to hook an abstract method by throwing.
            if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) {
                continue;
            }
            if (m.getParameterCount() == 0 && m.getReturnType() == String.class
                    && !Modifier.isStatic(m.getModifiers())
                    && !m.getName().equals("toString")
                    && !m.getName().equals("getClass")) {
                Xp.hook(m, chain -> {
                    Object self = chain.getThisObject();
                    if (self != null && sCoverVideoActive && sCoverVideoPath != null) {
                        Xp.log(TAG + "video path intercepted (" + cls.getSimpleName()
                                + "." + m.getName() + "()) -> " + sCoverVideoPath);
                        return sCoverVideoPath;
                    }
                    return chain.proceed();
                });
                Xp.log(TAG + "hooked video path candidate: " + cls.getSimpleName() + "." + m.getName());
            }
        }
    }

    // hookSurfaceChanged() used to live here: a sweep over "any 3-arg method whose 2nd and
    // 3rd parameters are ints", writing the result into sReportedW/sReportedH. Two reasons it
    // is gone rather than fixed.
    //
    // It never ran. onSurfaceChanged is declared on the base class and overridden by nobody, so
    // the sweep over getDeclaredMethods() on the two Keyguard subclasses matched nothing, and
    // on the base classes it sat behind the abstract-method throw above. Measured on the device
    // across a full session: "video surface size reported" - 0 hits.
    //
    // And sReportedW/sReportedH are not its to write. They are the texture size the STILL
    // wallpaper path scales its art by (fittedArt(), the fade agreement check), re-derived by
    // the keyguard upload hook on every upload. A video surface size written into them is at
    // best a transient lie and at worst a wrong crop. If the video surface size is ever needed,
    // it goes in fields of its own.


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
    /**
     * Hooks every Bitmap-carrying method and constructor on a renderer, and logs each call with
     * its arguments and result. Reached only through the `bmp` probe op.
     *
     * It used to run at load time, for the whole list of renderers, to find out how the wallpaper
     * bitmap travels into the GL texture. That question is answered (the answer is
     * onSurfaceCreated -> mTexture.use -> the lambda we replace the bitmap in, which is where the
     * module has worked since), and the cost of leaving it on was a hook on every one of those
     * methods in every wallpaper process, plus a log line per call, whether or not anyone was
     * looking. It stays as a probe because the first step in porting this module to a build whose
     * R8 names have moved is reading them back off the phone, and this is the tool that does it.
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

    /**
     * The bitmap the GL upload should get, at the size the texture should be - or null when
     * there is nothing to change.
     *
     * Null for the three cases that must not be touched: the experiment is off, the surface size
     * is not known yet (the first upload of a process runs before onSurfaceChanged has set it),
     * or the bitmap is already that size. Cached per source, because the source is either the
     * module's own art or the OEM's one wallpaper and both repeat.
     */
    private static Bitmap screenSized(Bitmap src) {
        int w = sSurfaceW, h = sSurfaceH;
        if (!sTexFit || src == null || w <= 0 || h <= 0) return null;
        if (src.getWidth() == w && src.getHeight() == h) return null;
        if (sScreenArt != null && sScreenArtOf == src
                && sScreenArt.getWidth() == w && sScreenArt.getHeight() == h) {
            return sScreenArt;
        }
        Bitmap fitted;
        try {
            fitted = centerCrop(src, w, h);
        } catch (Throwable t) {
            Xp.log(TAG + "screen-size fit failed: " + t);
            return null;
        }
        Bitmap old = sScreenArt;
        if (old != null && old != src && old != sArt) old.recycle();
        sScreenArt = fitted;
        sScreenArtOf = src;
        Xp.log(TAG + "texture fitted to the screen: " + describe(src)
                + " -> " + describe(fitted));
        return fitted;
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
