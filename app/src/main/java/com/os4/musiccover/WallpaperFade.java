package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.os.SystemClock;

/**
 * The cross-fade between two lock pictures: the GPU fade drawn in the OEM's own GL pass, the CPU
 * fallback that blends into a buffer, the thread priority raised while either runs, and the
 * darkening that belongs to the picture in the texture (the OEM's own, read where it hands it
 * over, and applied on the GL thread every frame).
 *
 * Moved out of WallpaperProbe as it was (2026-11-04): every member whole with its comments,
 * references across the line prefixed with WallpaperProbe. or WallpaperFade., private dropped
 * where the other side needs it. Nothing else changed.
 */
final class WallpaperFade {

    private WallpaperFade() {
    }

    /** The darkening the lock screen's real wallpaper is drawn with: the desktop's, once known. */
    static boolean origDarken() {
        return WallpaperProbe.sHomeDarkenKnown ? WallpaperProbe.sHomeDarken : WallpaperProbe.sOemDarken;
    }
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
    private static final long FADE_STEP_MS = 16L;

    // ------------------------------------------------------------------ the GPU crossfade

    /**
     * The crossfade done on the GPU instead of by re-uploading the texture every frame.
     *
     * The CPU fade above is capped by what one frame of it costs, and that turned out not to be
     * the blend: measured on OS4.0.0.35 over a day of track changes, 9-17 frames per ~385ms fade
     * with the blend at 4-6ms and the rest spent waiting 17-89 times for the GL thread. Read off
     * the dex, every reloadTexture() is ImageEngineImpl.L(): U() re-runs onSurfaceCreated() -
     * glCreateProgram + glProgramBinary, glGenTextures, a full texImage2D, glGenerateMipmap, and
     * the frosting - and then, because the request was T(false), finishRendering() tears the EGL
     * context down so the next frame has to rebuild it first. The previous texture is never
     * deleted either; it only goes when that context does.
     *
     * So this uploads each end exactly once. The picture being faded TO goes through one normal
     * reload, which is the OEM's own upload, frosting included, and is the steady state the fade
     * lands on. The picture being faded FROM goes into a texture of ours on the first frame after.
     * Every frame of the fade is then just a redraw - T(true), with the surface-created flag left
     * alone - and after the OEM has drawn its texture, the draw hook below issues the OEM's own
     * draw() once more with our texture bound and constant-alpha blending. Same program, same
     * uniforms, so darken, dark mode, the wake zoom and the reveal apply to both pictures alike.
     *
     * Only for AnimImageGLProgram itself: the glass/gradient/blur programs override commonDraw()
     * with passes of their own, and redrawing their last one would be wrong. Those fall back to
     * the CPU fade, and so does anything this finds it cannot do mid-flight.
     */
    static final class GpuFade {
        /** The picture in our texture, drawn over the OEM's at the fade's alpha. */
        final Bitmap from;
        /** The picture in the OEM's texture, underneath. */
        final Bitmap to;
        /** The overlay's alpha at the start and at the end. A fresh fade goes 1 -> 0. */
        final float a0, a1;
        /**
         * What the screen shows when this lands. `to` for a fade that ends with the overlay gone;
         * for one that ends with the overlay at full strength, the picture the OEM texture has
         * to be swapped to before the overlay can be let go - see drawGpuFade().
         */
        final Bitmap dest;
        final long durMs;
        final Runnable done;
        /** The fade this one reversed, whose texture of `from` it takes over. GL thread clears it. */
        volatile GpuFade inherit;
        final long startedAt = SystemClock.uptimeMillis();
        /** Set on the GL thread once the upload of the far end has gone through. */
        volatile boolean armed;
        volatile boolean finished;
        volatile long t0;
        volatile int frames;
        volatile long uploadMs;
        volatile int contexts;
        /** The end-of-fade swap of the OEM texture to `dest`: asked for, and landed. */
        volatile boolean swapRequested;
        volatile boolean swapUploaded;
        /** GL thread only: the swap has been posted to the main thread. */
        boolean swapPosted;

        GpuFade(Bitmap from, Bitmap to, float a0, float a1, Bitmap dest, long durMs,
                Runnable done) {
            this.from = from;
            this.to = to;
            this.a0 = a0;
            this.a1 = a1;
            this.dest = dest;
            this.durMs = durMs;
            this.done = done;
        }

        /** The overlay's alpha at `now`, as the GL thread will draw it. */
        float alphaAt(long now) {
            long s = t0;
            if (!armed || s == 0L) return a0;
            float t = Math.min(1f, (now - s) / (float) durMs);
            // The CPU fade's curve, so switching between the two changes the cost, not the feel.
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            return a0 + (a1 - a0) * e;
        }

        /** The picture this fade started from. */
        Bitmap start() {
            return a1 >= 0.5f ? to : from;
        }
    }
    private static GpuFade sGlTexFor;
    private static android.opengl.EGLContext sGlTexCtx;
    private static final int[] sGlInts = new int[4];
    private static final float[] sGlColor = new float[4];
    /** A fade that has not seen its last frame this long after it should have is ended here. */
    private static final long GPU_FADE_GRACE_MS = 1500L;

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
    static void startFade(final Bitmap from, final Bitmap to, final Runnable done) {
        startFade(from, to, done, WallpaperProbe.sFadeMs);
    }

    static void startFade(final Bitmap from, final Bitmap to, final Runnable done,
                                  final long durMs) {
        final Context ctx = WallpaperProbe.sCtx;
        if (ctx == null || from == null || to == null || from.isRecycled() || to.isRecycled()
                || from.getWidth() != to.getWidth() || from.getHeight() != to.getHeight()) {
            Xp.log(WallpaperProbe.TAG + "no fade: " + WallpaperProbe.describe(from) + " -> " + WallpaperProbe.describe(to));
            if (done != null) done.run();
            WallpaperEngines.reloadTexture();
            return;
        }
        if (WallpaperProbe.sSharedLock && WallpaperEngines.lockEngine() == null) {
            // The desktop is on screen and the lock screen's picture is not on it: nothing to
            // fade. The cover is put on when the lock screen comes back - syncSharedLock().
            if (done != null) done.run();
            return;
        }
        if (gpuFadeUsable()) {
            startGpuFade(from, to, done, durMs);
            return;
        }
        if (fadeTooExpensive(from)) {
            if (done != null) done.run();
            WallpaperEngines.reloadTexture();
            return;
        }
        // Both ends agree with each other by the check above; whether they agree with the
        // TEXTURE is the thing that decides how this looks on screen, and it is the one number
        // that is not in either of them. Logged once per fade so a report of a bad transition
        // can be read off the log instead of guessed at.
        if (from.getWidth() != WallpaperProbe.sReportedW || from.getHeight() != WallpaperProbe.sReportedH) {
            Xp.log(WallpaperProbe.TAG + "fade " + WallpaperProbe.describe(from) + " -> " + WallpaperProbe.describe(to)
                    + " does NOT match the texture " + WallpaperProbe.sReportedW + "x" + WallpaperProbe.sReportedH);
        }
        final int fw = from.getWidth(), fh = from.getHeight();
        final int gen = ++sFadeGen;
        final long t0 = SystemClock.uptimeMillis();
        final long[] spent = {0L, 0L, 0L};  // blend ms, frames, frames waited out
        WallpaperProbe.sFrostSkipping = WallpaperProbe.sSkipFrost;
        WallpaperProbe.sFadeInFlight = false;
        final Runnable step = new Runnable() {
            @Override
            public void run() {
                // A newer fade has taken over - a track changed while this one was in the air.
                // It owns sFade and the buffer now, so this one simply stops.
                if (gen != sFadeGen) return;
                long now = SystemClock.uptimeMillis();
                // The GL thread has not finished with the buffer yet. Come back rather than
                // compose over the top of it - see sFadeInFlight.
                if (WallpaperProbe.sFadeInFlight && now - sFadeSentAt < FADE_ACK_MS) {
                    spent[2]++;
                    Gen.postDelayed(this, 2L);
                    return;
                }
                long el = now - t0;
                if (el >= durMs) {
                    WallpaperProbe.sFade = null;
                    WallpaperProbe.sFadeInFlight = false;
                    WallpaperProbe.sFrostSkipping = false;
                    if (done != null) done.run();
                    // The last upload is a normal one: the real bitmap, and the frosted copy
                    // regenerated from it.
                    WallpaperEngines.reloadTexture();
                    Xp.log(WallpaperProbe.TAG + "fade done in " + el + "ms over " + spent[1] + " frames, blend "
                            + (spent[1] == 0 ? 0 : spent[0] / spent[1]) + "ms/frame, waited "
                            + spent[2] + "x for the upload"
                            + (WallpaperProbe.sSkipFrost ? ", frosting skipped" : ""));
                    return;
                }
                float t = el / (float) durMs;
                // Ease OUT, not smoothstep. The clock it has to keep company with is a spring,
                // and a spring is all front-loaded: most of the movement is over in the first
                // third. A symmetric curve spends that third barely changing, which is exactly
                // when the eye is looking, and then finishes after the clock has stopped.
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                // A buffer nobody can be reading. With both taken - one published and not yet
                // picked up, the other still being uploaded - this frame is skipped rather than
                // composed over one of them, which is what tore the texture on slower phones.
                Bitmap dst = pickFadeBuf(fw, fh);
                if (dst == null) {
                    spent[2]++;
                    Gen.postDelayed(this, 2L);
                    return;
                }
                long b0 = SystemClock.uptimeMillis();
                blendInto(dst, from, to, e);
                spent[0] += SystemClock.uptimeMillis() - b0;
                spent[1]++;
                synchronized (WallpaperProbe.FADE_LOCK) {
                    WallpaperProbe.sFade = dst;
                }
                WallpaperProbe.sFadeInFlight = true;
                sFadeSentAt = SystemClock.uptimeMillis();
                // Kept alive between frames: T(false) finishes rendering after every frame,
                // which throws the EGL context away for the next one to rebuild.
                WallpaperEngines.reloadEngine(WallpaperEngines.lockEngine(), true);
                Gen.postDelayed(this, FADE_STEP_MS);
            }
        };
        Gen.post(step);
    }

    /**
     * Ends a fade in flight without running its completion. For a fade that has become invalid
     * rather than one that has finished - the buffer it was composing into no longer matches
     * the texture, so nothing it produces from here is worth uploading.
     */
    static void cancelFade() {
        sFadeGen++;
        WallpaperProbe.sFade = null;
        WallpaperProbe.sFadeInFlight = false;
        WallpaperProbe.sFrostSkipping = false;
    }

    /**
     * A fade buffer of this size that the GL thread cannot be reading, or null if both are busy.
     *
     * The choice is made under FADE_LOCK, and the lambda takes sFade and marks it as uploading
     * under the same lock, so a buffer picked here is neither published (the lambda cannot pick
     * it up while it is being composed) nor mid-upload. Main thread only.
     */
    private static Bitmap pickFadeBuf(int w, int h) {
        synchronized (WallpaperProbe.FADE_LOCK) {
            if (usableFadeBuf(WallpaperProbe.sFadeBuf, w, h)) return WallpaperProbe.sFadeBuf;
            if (usableFadeBuf(WallpaperProbe.sFadeBuf2, w, h)) return WallpaperProbe.sFadeBuf2;
            // Allocate into a slot that is free - never over a buffer that is still in use,
            // or the upload reading it would be reading a recycled or replaced bitmap.
            if (!fadeBufBusy(WallpaperProbe.sFadeBuf)) {
                WallpaperProbe.sFadeBuf = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                return WallpaperProbe.sFadeBuf;
            }
            if (!fadeBufBusy(WallpaperProbe.sFadeBuf2)) {
                WallpaperProbe.sFadeBuf2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                return WallpaperProbe.sFadeBuf2;
            }
            return null;
        }
    }

    private static boolean fadeBufBusy(Bitmap b) {
        return b != null && (b == WallpaperProbe.sFade || b == WallpaperProbe.sUploading);
    }

    private static boolean usableFadeBuf(Bitmap b, int w, int h) {
        return b != null && !b.isRecycled() && b.getWidth() == w && b.getHeight() == h
                && !fadeBufBusy(b);
    }

    /** Whether the GPU fade can be trusted with the keyguard as it is right now. */
    static boolean gpuFadeUsable() {
        if (!WallpaperProbe.sGpuFadeOn || WallpaperProbe.sGpuFadeBroken || !WallpaperProbe.sDrawHooked || WallpaperProbe.sPlainProgram == null) return false;
        // On a shared still the desktop renderer stands in for the keyguard's, and it is known
        // before its first upload as the lock screen's - which is the upload the fade starts.
        Object renderer = WallpaperProbe.sSharedLock ? WallpaperProbe.sDesktopRenderer : WallpaperProbe.sKeyguardRenderer;
        if (renderer == null || WallpaperEngines.lockEngine() == null) return false;
        try {
            Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"), "mProgram");
            if (prog != null && prog.getClass() == WallpaperProbe.sPlainProgram) return true;
            Xp.log(WallpaperProbe.TAG + "gpu fade not used: the keyguard program is "
                    + (prog == null ? "null" : prog.getClass().getSimpleName()));
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + "gpu fade not used: " + t);
        }
        return false;
    }
    /** What boostForFade raised, and from what. Main thread. */
    private static int sBoostedMainFrom = Integer.MIN_VALUE;
    private static int sBoostedGlTid;
    private static int sBoostedGlFrom;
    private static boolean sBoostLogged;

    /**
     * The fade's two threads at display priority while it runs, and back after. Main thread.
     *
     * With four cores up and all four full - SystemUI's frames, its GC, WeChat in the background -
     * the fade's main thread and GL thread sat runnable for 400-800ms of a 1.1s entry (trace
     * 2026-10-20): the cover came up late and stalled half way, though each of its frames is a
     * millisecond or two. Raised only as far as URGENT_DISPLAY, still below SystemUI's render
     * thread, and never lowered from where it already was.
     */
    private static void boostForFade(boolean on) {
        final int want = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY;
        try {
            if (on) {
                if (sBoostedMainFrom == Integer.MIN_VALUE) {
                    int now = android.os.Process.getThreadPriority(android.os.Process.myTid());
                    if (now > want) {
                        android.os.Process.setThreadPriority(want);
                        sBoostedMainFrom = now;
                    }
                }
                int gl = WallpaperProbe.sKeyguardGlTid;
                if (gl != 0 && sBoostedGlTid == 0) {
                    int now = android.os.Process.getThreadPriority(gl);
                    if (now > want) {
                        android.os.Process.setThreadPriority(gl, want);
                        sBoostedGlTid = gl;
                        sBoostedGlFrom = now;
                        if (!sBoostLogged) {
                            sBoostLogged = true;
                            Xp.log(WallpaperProbe.TAG + "fade threads raised to " + want + " (GL " + gl + " from "
                                    + now + ", main from " + sBoostedMainFrom + ")");
                        }
                    }
                }
                return;
            }
            if (sBoostedMainFrom != Integer.MIN_VALUE) {
                android.os.Process.setThreadPriority(sBoostedMainFrom);
                sBoostedMainFrom = Integer.MIN_VALUE;
            }
            if (sBoostedGlTid != 0) {
                int gl = sBoostedGlTid;
                sBoostedGlTid = 0;
                android.os.Process.setThreadPriority(gl, sBoostedGlFrom);
            }
        } catch (Throwable t) {
            // Not allowed, or the GL thread is gone: the fade runs as it always did.
            sBoostedMainFrom = Integer.MIN_VALUE;
            sBoostedGlTid = 0;
            Xp.d(WallpaperProbe.TAG + "fade priority: " + t);
        }
    }

    private static void startGpuFade(Bitmap from, Bitmap to, Runnable done, long durMs) {
        boostForFade(true);
        // A CPU fade still running owns sFade; stop it before the texture changes hands. A GPU
        // fade overtaken by this one simply stops too, without running its completion - the
        // same rule the CPU fade's generation check follows, and for the same reason: the one
        // that matters here is the exit's, which clears the art this new fade has just set.
        cancelFade();
        final GpuFade old = WallpaperProbe.sGpuFade;
        final GpuFade f;
        if (old != null && !old.finished) {
            float a = old.alphaAt(SystemClock.uptimeMillis());
            // Not once the old fade has started swapping its underneath picture: the OEM texture
            // may already be its far end, which this reversal would otherwise take for its start.
            // That fade is visually over by then, so a fresh fade from it is the right thing.
            if (!old.swapRequested && sameImage(to, old.start())) {
                // Going back to where the running fade came from - the big and small clock
                // toggled again before the last toggle had finished. Starting over from `from`
                // would jump the wallpaper to that fade's far end and fade back from there.
                // Both pictures are already on the GPU, so this runs the same pair backwards
                // from the alpha on screen right now, and uploads nothing to do it.
                float target = old.a1 >= 0.5f ? 0f : 1f;
                f = new GpuFade(old.from, old.to, a, target, to, durMs, done);
                f.inherit = old;
                f.armed = old.armed;
                f.t0 = old.armed ? SystemClock.uptimeMillis() : 0L;
                WallpaperProbe.sUploadOverride = old.to;
                WallpaperProbe.sGpuFade = f;
                Xp.log(WallpaperProbe.TAG + "gpu fade reversed at alpha " + Math.round(a * 100f) / 100f
                        + " -> " + target);
            } else {
                // A third picture - a skip while the last one was still fading. Only two
                // pictures can be on screen, so start from whichever of the two dominates it
                // now: the jump is at most half a fade instead of all of one.
                Bitmap near = a >= 0.5f ? old.from : old.to;
                if (near != null && !near.isRecycled() && near.getWidth() == to.getWidth()
                        && near.getHeight() == to.getHeight()) {
                    from = near;
                }
                f = startFreshGpuFade(from, to, done, durMs);
            }
        } else {
            f = startFreshGpuFade(from, to, done, durMs);
        }
        android.view.Choreographer.FrameCallback loop =
                new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (WallpaperProbe.sGpuFade != f) return;
                if (SystemClock.uptimeMillis() - f.startedAt > f.durMs + GPU_FADE_GRACE_MS) {
                    // The GL thread stopped drawing - the screen went off, most likely. End it
                    // with a real reload: what is uploaded is the far end only for a fade that
                    // lands on its underneath picture, not for one waiting on its swap.
                    WallpaperProbe.sLastFade = "TIMED OUT after " + f.frames + " frames (armed=" + f.armed
                            + ", swap=" + f.swapRequested + "/" + f.swapUploaded + ")";
                    Xp.log(WallpaperProbe.TAG + "gpu fade timed out after " + f.frames + " frames (armed="
                            + f.armed + ", swap=" + f.swapRequested + "/" + f.swapUploaded + ")");
                    finishGpuFade(f);
                    WallpaperEngines.reloadTexture();
                    return;
                }
                WallpaperEngines.requestFrame(true);
                Gen.frame(this);
            }
        };
        Gen.frame(loop);
    }

    /** A fade from `from` to `to` from the start: one reload uploads `to` underneath. */
    private static GpuFade startFreshGpuFade(Bitmap from, Bitmap to, Runnable done, long durMs) {
        GpuFade f = new GpuFade(from, to, 1f, 0f, to, durMs, done);
        WallpaperProbe.sUploadOverride = to;
        WallpaperProbe.sGpuFade = f;
        WallpaperEngines.reloadEngine(WallpaperEngines.lockEngine(), true);
        return f;
    }

    /**
     * Whether two pictures are the same picture. Identity first; otherwise the same size and the
     * same coarse print, because a cover re-composed for the same track is a new Bitmap with the
     * same pixels, and that is exactly the case a toggle back into cover mode produces.
     */
    private static boolean sameImage(Bitmap a, Bitmap b) {
        if (a == b) return true;
        if (a == null || b == null || a.isRecycled() || b.isRecycled()) return false;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        return WallpaperDiag.print8(a) == WallpaperDiag.print8(b);
    }

    /** Main thread. Lands a fade: its completion runs and the override goes. */
    private static void finishGpuFade(GpuFade f) {
        if (WallpaperProbe.sGpuFade != f) return;
        WallpaperProbe.sGpuFade = null;
        WallpaperProbe.sUploadOverride = null;
        boostForFade(false);
        if (f.done != null) f.done.run();
        // A cover that has just faded out leaves the desktop holding the lock screen's cut of
        // its own picture. Put back now, under the lock screen where it looks the same, rather
        // than as the user unlocks.
        if (WallpaperProbe.sSharedLock) WallpaperEngines.syncSharedLock();
        // A video held back by the fade is drawn from the next frame; ask for it rather than
        // wait on the player's next one, which a paused player would never send.
        if (WallpaperVideo.on()) WallpaperEngines.videoFrame();
    }

    /** Any thread. Ends a fade that cannot continue, with a plain reload so nothing is stranded. */
    static void abortGpuFade(final GpuFade f) {
        f.finished = true;
        Gen.post(new Runnable() {
            @Override
            public void run() {
                if (WallpaperProbe.sGpuFade != f) return;
                finishGpuFade(f);
                WallpaperEngines.reloadTexture();
            }
        });
    }

    /**
     * GL thread, before the OEM draws: the darkening that belongs to the picture in its texture.
     * Every frame rather than once, because the texture changes hands under reloads the OEM's
     * own updateMaskLayerStatus() does not follow.
     */
    static void applyDarken(Object renderer) {
        try {
            Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"), "mProgram");
            if (prog == null) return;
            int want = origDarken() && !WallpaperProbe.sTexShowsCover ? 1 : 0;
            if (((Integer) Xp.getObjectField(prog, "mDarken")).intValue() != want) {
                Xp.setObjectField(prog, "mDarken", want);
            }
        } catch (Throwable t) {
            WallpaperProbe.sDarkenBroken = true;
            Xp.log(WallpaperProbe.TAG + "cannot set the darkening, leaving it to the OEM: " + t);
        }
    }

    /**
     * One frame of the GPU fade, on the GL thread, after the OEM has drawn the far end.
     *
     * Also where our texture is let go of - when its fade has ended or been replaced, and only
     * in the context that made it. A texture of a context that has since been destroyed went
     * with that context and is not ours to delete any more.
     */
    static void drawGpuFade(Object renderer, final GpuFade f) {
        android.opengl.EGLContext ctx = android.opengl.EGL14.eglGetCurrentContext();
        // A reversed fade takes over the texture of the fade it reversed - same picture, so
        // there is nothing to upload again. The chain is walked because a toggle can reverse a
        // reversal before the GL thread has drawn the first one.
        if (f != null && WallpaperProbe.sGlTex != 0 && sGlTexFor != f && f.inherit != null) {
            int depth = 0;
            for (GpuFade p = f.inherit; p != null && depth < 16; p = p.inherit, depth++) {
                if (p == sGlTexFor && p.from == f.from) {
                    sGlTexFor = f;
                    break;
                }
            }
        }
        if (f != null) f.inherit = null;
        if (WallpaperProbe.sGlTex != 0 && (sGlTexFor != f || f.finished || !ctx.equals(sGlTexCtx))) {
            if (ctx.equals(sGlTexCtx)) {
                GLES20.glDeleteTextures(1, new int[]{WallpaperProbe.sGlTex}, 0);
            }
            WallpaperProbe.sGlTex = 0;
            sGlTexFor = null;
            sGlTexCtx = null;
        }
        if (f == null || f.finished) return;

        long now = SystemClock.uptimeMillis();
        // The clock starts with the first frame that shows the far end underneath, so a reload
        // that lands late shortens nothing: until then the near end is drawn at full strength
        // over whatever the texture still is, which is the near end anyway.
        if (f.armed && f.t0 == 0L) f.t0 = now;
        long el = f.t0 == 0L ? 0L : now - f.t0;
        boolean holding = false;
        if (el >= f.durMs && f.a1 >= 0.5f && !f.swapUploaded) {
            // Landing with the overlay at full strength: the screen already shows `dest`, but
            // from OUR texture. The OEM's has to become it before the overlay can go, or letting
            // go would cut back to the picture underneath. So one reload, and the overlay is
            // held at full strength until the upload hook says it has landed.
            holding = true;
            if (!f.swapPosted) {
                f.swapPosted = true;
                Gen.post(new Runnable() {
                    @Override
                    public void run() {
                        if (WallpaperProbe.sGpuFade != f) return;
                        WallpaperProbe.sUploadOverride = f.dest;
                        f.swapRequested = true;
                        WallpaperEngines.reloadEngine(WallpaperEngines.lockEngine(), true);
                    }
                });
            }
        }
        if (el >= f.durMs && !holding) {
            f.finished = true;
            if (WallpaperProbe.sGlTex != 0) {
                GLES20.glDeleteTextures(1, new int[]{WallpaperProbe.sGlTex}, 0);
                WallpaperProbe.sGlTex = 0;
                sGlTexFor = null;
                sGlTexCtx = null;
            }
            Gen.post(new Runnable() {
                @Override
                public void run() {
                    WallpaperProbe.sLastFade = "done in " + (SystemClock.uptimeMillis() - f.startedAt) + "ms, "
                            + f.frames + " frames, armed=" + f.armed;
                    Xp.log(WallpaperProbe.TAG + "gpu fade done in " + (SystemClock.uptimeMillis() - f.startedAt)
                            + "ms from the request, alpha " + Math.round(f.a0 * 100f) / 100f
                            + " -> " + f.a1 + ", " + f.frames + " frames over " + f.durMs
                            + "ms, first upload after " + (f.t0 - f.startedAt) + "ms, near end "
                            + "uploaded in " + f.uploadMs + "ms" + (f.contexts > 1
                            ? ", GL context rebuilt " + (f.contexts - 1) + "x mid-fade" : ""));
                    finishGpuFade(f);
                }
            });
            return;
        }

        Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"), "mProgram");
        if (prog == null || prog.getClass() != WallpaperProbe.sPlainProgram) {
            Xp.log(WallpaperProbe.TAG + "gpu fade dropped: the program became "
                    + (prog == null ? "null" : prog.getClass().getSimpleName()));
            abortGpuFade(f);
            return;
        }
        Object wp = Xp.getObjectField(prog, "mAnimImageGLWallpaper");
        int oemTex = ((Integer) Xp.getObjectField(wp, "mTextureId")).intValue();

        if (WallpaperProbe.sGlTex == 0) {
            if (f.from.isRecycled()) {
                abortGpuFade(f);
                return;
            }
            long u0 = SystemClock.uptimeMillis();
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, f.from, 0);
            WallpaperProbe.sGlTex = ids[0];
            sGlTexFor = f;
            sGlTexCtx = ctx;
            f.contexts++;
            f.uploadMs += SystemClock.uptimeMillis() - u0;
        }

        float alpha = f.alphaAt(now);

        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        int[] fn = sGlInts;
        int[] one = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, one, 0);
        fn[0] = one[0];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_RGB, one, 0);
        fn[1] = one[0];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_ALPHA, one, 0);
        fn[2] = one[0];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, one, 0);
        fn[3] = one[0];
        GLES20.glGetFloatv(GLES20.GL_BLEND_COLOR, sGlColor, 0);

        // draw() is a bare glDrawArrays, so the overlay inherits the uniforms commonDraw() set
        // for the picture underneath - uDarken included. When only one of the two is the
        // original, the overlay gets its own value, and it is put back after.
        boolean darkUnder = false, darkOver = false;
        int uDarken = -1;
        if (WallpaperProbe.sOemDarkenKnown && origDarken() && !WallpaperProbe.sDarkenBroken) {
            darkUnder = !WallpaperProbe.sTexShowsCover;
            darkOver = f.from == WallpaperProbe.sOrig;
            if (darkOver != darkUnder) {
                uDarken = ((Integer) Xp.getObjectField(wp, "uDarken")).intValue();
                GLES20.glUniform1i(uDarken, darkOver ? 1 : 0);
            }
        }

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA);
        GLES20.glBlendColor(0f, 0f, 0f, alpha);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        // A fresh fade (overlay 1 -> 0) over a motion cover fades the video's last frame out,
        // not the old still - see WallpaperVideo.fadeOverlay.
        int video = f.a0 >= 0.5f && f.a1 < 0.5f ? WallpaperVideo.fadeOverlay() : 0;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, video != 0 ? video : WallpaperProbe.sGlTex);
        Xp.callMethod(wp, "draw");

        if (uDarken != -1) GLES20.glUniform1i(uDarken, darkUnder ? 1 : 0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oemTex);
        GLES20.glBlendFuncSeparate(fn[0], fn[1], fn[2], fn[3]);
        GLES20.glBlendColor(sGlColor[0], sGlColor[1], sGlColor[2], sGlColor[3]);
        if (!blend) GLES20.glDisable(GLES20.GL_BLEND);
        f.frames++;
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
        long screen = (long) WallpaperProbe.sSurfaceW * WallpaperProbe.sSurfaceH;
        if (screen <= 0) return false;
        long texture = (long) from.getWidth() * from.getHeight();
        if (texture * 2 <= screen * 3) return false;           // <= 1.5x the screen
        Xp.log(WallpaperProbe.TAG + "no fade: " + WallpaperProbe.describe(from) + " is "
                + (Math.round(texture * 10.0 / screen) / 10.0) + "x the screen ("
                + WallpaperProbe.sSurfaceW + "x" + WallpaperProbe.sSurfaceH + ") - one upload of it is "
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
}
