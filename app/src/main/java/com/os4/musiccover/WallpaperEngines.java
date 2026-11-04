package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface;

/**
 * The OEM's wallpaper engines as this module drives them: reloading the lock (and desktop)
 * texture in place, whether the lock screen and the desktop share one picture, the engine
 * instances and their reload, the draw hook that swaps in the video frame, the keep-dim pump, and
 * asking an engine for one more frame.
 *
 * Moved out of WallpaperProbe as it was (2026-11-04): every member whole with its comments,
 * references across the line prefixed with WallpaperProbe. or WallpaperEngines., private dropped
 * where the other side needs it. Nothing else changed.
 */
final class WallpaperEngines {

    private WallpaperEngines() {
    }

    /** The pump's own frame request, on the main thread. */
    private static final long KEEP_PUMP_MS = 24L;
    /** The program `uKeepDim` was last resolved on, and its location (-1: not in this one). */
    private static int sKeepDimProgram;
    private static int sKeepDimLoc = -1;
    private static final int[] sCurProgram = new int[1];

    /**
     * The ways to ask the engine to run its preRender step, and the arguments it takes there.
     *
     * Ordered newest-name-first. The names are R8-obfuscated (OS4.0.0.35: u, b, T) and a build
     * that renamed one used to take the whole reload down with it - the log below is what that
     * looked like, and what it cost is in Handoff 27.
     */
    static final Object[][] FRAME_REQUESTS = {
            {"T", Boolean.FALSE},
            // OS4.0.0.17's name for the same method, read off its bytecode rather than inferred
            // from the log: there ImageEngineImpl.M(Z) and U(Z) stand where 0.35 has L(Z) and
            // T(Z). M and L are instruction-for-instruction identical, and U and T both open on
            // `iget-boolean h:Z; if-eqz; iget-object t:HashMap; s()` - the post-a-frame path.
            // What settles the pairing is the rest of that set: changeScrollWithScreen and g
            // kept their names across both builds, and those are what align the two lists.
            {"U", Boolean.FALSE},
            // 8.0.8-flip-q18's name, off its bytecode: ImageEngineImpl.Z(Z) opens on
            // `iget-boolean s:Z; if-eqz -> return; iget-object k:Handler; Handler.post(Runnable)`,
            // which is the same post-a-frame shape as T/U above with the fields renamed (that
            // build's guard is s where 7.0.7's is h). The sibling V(Z) is 563 code units of
            // surface work and is NOT this - it is the one to avoid calling by accident.
            {"Z", Boolean.FALSE},
            // The OEM's own showKeyguardWallpaper(ZI)/hideKeyguardWallpaper(ZI) take a boolean
            // and an int, so a build that widened the frame request the same way is worth one
            // more try. 0 is the no-animation value everywhere these appear.
            {"T", Boolean.FALSE, Integer.valueOf(0)},
    };

    /**
     * Re-runs the DESKTOP renderer's own surface-created path, the same way reloadTexture() does
     * for the keyguard: the engine's pending-surface flag plus its own frame request, so the OEM
     * does the upload rather than us touching GL.
     */
    static void reloadDesktopTexture() {
        Object eng = WallpaperProbe.sDesktopEngine;
        if (eng == null) {
            Xp.log(WallpaperProbe.TAG + "reload: no desktop engine captured - the desktop wallpaper cannot be "
                    + "swapped, so the cards' glass will keep showing the wallpaper");
            return;
        }
        reloadEngine(eng);
    }

    static void reloadTexture() {
        Object eng = lockEngine();
        if (eng == null) {
            if (WallpaperProbe.sSharedLock) {
                // Not a failure: the desktop is on screen, and the cover waits for the lock
                // screen - see syncSharedLock().
                Xp.log(WallpaperProbe.TAG + "reload: the lock screen shares the desktop and is not up, the cover"
                        + " waits for it");
                return;
            }
            Xp.log(WallpaperProbe.TAG + "reload: no keyguard engine (is the lockscreen wallpaper "
                    + "still the same image as the desktop one?)");
            return;
        }
        reloadEngine(eng);
    }

    /**
     * The engine whose texture the lock screen shows: the keyguard's, or with a shared still the
     * desktop's while the lock screen is up. Null when the lock screen shows nothing of ours to
     * reload - shared, and the desktop is what is on screen.
     */
    static Object lockEngine() {
        if (!WallpaperProbe.sSharedLock) return WallpaperProbe.sKeyguardEngine;
        return sharedKeyguardShown() ? WallpaperProbe.sDesktopEngine : null;
    }

    private static boolean sharedKeyguardShown() {
        Boolean shown = WallpaperProbe.sDesktopKgShown;
        return shown != null ? shown : VideoTakeover.lockScreenUp();
    }

    /** Whether the desktop's next upload is the lock screen's. See sSharedLock. */
    static boolean desktopOnLockWanted() {
        return WallpaperProbe.sSharedLock && (WallpaperProbe.sArt != null || WallpaperProbe.sFade != null) && sharedKeyguardShown();
    }

    /**
     * GL thread, from the upload hook: whether this desktop upload goes through the keyguard
     * branch. When it does not, everything the keyguard branch left on the desktop's texture is
     * taken back, so the home screen gets its own picture at its own geometry - the texture
     * dimension hook would otherwise keep answering with the screen-sized cut.
     */
    static boolean desktopUpload(Object renderer, Bitmap orig) {
        WallpaperProbe.sDesktopRenderer = renderer;
        boolean lock = desktopOnLockWanted();
        if (lock != WallpaperProbe.sDesktopOnLock) {
            WallpaperProbe.sDesktopOnLock = lock;
            Xp.log(WallpaperProbe.TAG + (lock ? "the desktop renderer draws the lock screen's cover"
                    : "the desktop renderer is back to its own picture"));
        }
        boolean gaveOurs = WallpaperProbe.sDesktopGaveOurs;
        WallpaperProbe.sDesktopGaveOurs = false;
        if (lock) return true;
        if (gaveOurs) {
            // Unlocked between the source and the upload: what is about to go up is a cover, on
            // the home screen. Up it goes - this frame cannot be stopped - and straight after it
            // the desktop's own picture.
            Xp.log(WallpaperProbe.TAG + "the desktop was handed a cover as the lock screen went; reloading it");
            final Object eng = WallpaperProbe.sDesktopEngine;
            Gen.post(() -> {
                if (!desktopOnLockWanted()) reloadEngine(eng);
            });
        }
        try {
            Object t = Xp.getObjectField(renderer, "mTexture");
            if (t != null) {
                WallpaperProbe.sTexSizes.remove(t);
                if (WallpaperProbe.sKeyguardTexture == t) WallpaperProbe.sKeyguardTexture = null;
            }
        } catch (Throwable ignored) {
        }
        if (WallpaperProbe.sKeyguardRenderer == renderer) WallpaperProbe.sKeyguardRenderer = null;
        // What a cover fades in from. The keyguard branch learns it from the upload that carries
        // no cover, which on a shared still never comes, so it is taken from the desktop's own
        // uploads instead, cut to the screen exactly as the keyguard branch would cut it.
        if (WallpaperProbe.sSharedLock && !WallpaperProbe.sShadeOn && WallpaperProbe.sArt == null && !gaveOurs) {
            WallpaperProbe.adoptSurfaceOf(renderer);
            Bitmap screen = WallpaperProbe.screenSized(orig);
            WallpaperProbe.rememberOriginal(screen != null ? screen : orig);
        }
        return false;
    }

    /** Told by SystemUI on every message. See sSharedLock. */
    static void noteSharedLock(boolean shared) {
        if (shared == WallpaperProbe.sSharedLock) return;
        WallpaperProbe.sSharedLock = shared;
        Xp.log(WallpaperProbe.TAG + (shared ? "the lock screen shares the desktop's still: the cover goes on the"
                + " desktop engine while locked" : "the lock screen has a wallpaper of its own again"));
        // Whatever was remembered belonged to the other arrangement's lock wallpaper.
        WallpaperProbe.sOrig = null;
        Gen.post(() -> {
            // The picture the first cover fades in from is learnt from a desktop upload with no
            // cover on it. One now, of the picture already on screen, so that first fade is not
            // a cut.
            if (shared && WallpaperProbe.sArt == null && WallpaperProbe.sDesktopEngine != null) reloadEngine(WallpaperProbe.sDesktopEngine);
            syncSharedLock();
        });
    }

    /**
     * The desktop engine was told the lock screen showed or hid. Main thread or binder - posted.
     */
    static void onDesktopKeyguard(boolean showing) {
        WallpaperProbe.sDesktopKgShown = showing;
        Gen.post(WallpaperEngines::syncSharedLock);
    }

    /**
     * Puts the desktop's texture in step with the lock screen: the cover on it while the lock
     * screen is up, the desktop's own picture the moment it goes. One reload, and only when the
     * two disagree, so a phone without a cover never has its desktop touched. Main thread.
     */
    static void syncSharedLock() {
        boolean want = desktopOnLockWanted();
        if (want == WallpaperProbe.sDesktopOnLock) return;
        Object eng = WallpaperProbe.sDesktopEngine;
        if (eng == null) return;
        // A fade in the air is left to finish on its own: its frames find no engine to reload
        // once the lock screen is gone, and its end is what clears the art on the way out.
        Xp.log(WallpaperProbe.TAG + (want ? "the cover goes on the shared desktop wallpaper"
                : "the shared desktop wallpaper gets its own picture back"));
        reloadEngine(eng);
    }

    /**
     * Records an engine instance and says what distinguishes it from the others.
     *
     * The int fields are dumped because on the multi-display build they are what tells the
     * instances apart: MultiDisplayEngineService.b is `which` - the value
     * MiuiWallpaperManager.isLockWhich reads to separate the lock engine from the desktop one -
     * and c is what it passes to WallpaperServiceController alongside it. Neither is named in a
     * way that can be relied on, so they are reported rather than interpreted.
     */
    static void noteEngineInstance(Object eng) {
        if (eng == null) return;
        if (!WallpaperProbe.sKeyguardEngines.contains(eng)) WallpaperProbe.sKeyguardEngines.add(eng);
        Xp.log(WallpaperProbe.TAG + "keyguard engine #" + WallpaperProbe.sKeyguardEngines.size() + ": " + WallpaperDiag.describeEngine(eng));
    }

    /** The wallpaper app's own version, which is what a report has to be read against. */
    static String wallpaperVersion() {
        try {
            Context c = WallpaperProbe.sCtx;
            if (c == null) return "unknown (no context)";
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.packageName + " " + pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "unknown: " + t;
        }
    }

    static void reloadEngine(Object eng) {
        reloadEngine(eng, false);
    }

    /**
     * keepAlive asks for the frame with T(true). Read off OS4.0.0.35: the boolean is what
     * ImageEngineImpl.L() hands its post-render step, and false runs finishRendering() on the
     * spot - the EGL surface and context are destroyed after the frame - where true defers that
     * by a second. So a run of frames wants true: with false every one of them pays for a new
     * context, and U() treats a new context as a new surface and re-uploads everything.
     */
    static void reloadEngine(Object eng, boolean keepAlive) {
        if (eng == null) return;
        try {
            Xp.callMethod(eng, "u");
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "reload: u() failed: " + t);
        }
        // Resolved, never assumed. On the multi-display build this flag is called `w`, and the
        // name `b` that it has on 7.0.7 belongs there to an int holding `which` - so writing `b`
        // blind is not a miss, it is a write onto the engine's own lock-or-desktop identity.
        // EngineNames only hands back a name it has confirmed is a boolean.
        String flag = EngineNames.pendingFlag;
        if (flag == null) {
            Xp.log(WallpaperProbe.TAG + "reload: no pending-surface flag resolved; the frame will redraw what is "
                    + "uploaded instead of re-reading it. " + EngineNames.report());
        } else {
            try {
                Xp.setBooleanField(eng, flag, true);
            } catch (Throwable t) {
                Xp.w(WallpaperProbe.TAG + "reload: the pending-surface field '" + flag + "' failed: " + t);
            }
        }
        if (frameRequest(eng, keepAlive)) {
            // Not per frame: a fade asks for a reload every frame, and this log goes through
            // LSPosed's binder and file on each one.
            if (!keepAlive) {
                Xp.log(WallpaperProbe.TAG + "reload requested on " + eng.getClass().getSimpleName()
                        + " via " + FRAME_REQUESTS[WallpaperProbe.sFrameReq][0] + "()");
            }
            return;
        }
        WallpaperDiag.reportNoFrameRequest(eng);
    }

    /** WallpaperVideo still holds GL objects; one more frame on the keyguard lets them go. */
    static volatile boolean sVideoGlLive;

    /**
     * The keyguard's frame with the motion cover in it: the OEM's own draw, with the texture it
     * binds swapped for the one WallpaperVideo composed this frame. See WallpaperVideo.
     */
    static Object drawWithVideo(XposedInterface.Chain chain) throws Throwable {
        Object renderer = chain.getThisObject();
        Object wp = null;
        int oemTex = 0;
        try {
            Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"),
                    "mProgram");
            wp = Xp.getObjectField(prog, "mAnimImageGLWallpaper");
            oemTex = ((Integer) Xp.getObjectField(wp, "mTextureId")).intValue();
        } catch (Throwable t) {
            wp = null;
        }
        int tex = wp == null ? 0 : WallpaperVideo.beforeDraw(renderer,
                WallpaperProbe.sGpuFade != null || WallpaperProbe.sFade != null, WallpaperProbe.sReportedW, WallpaperProbe.sReportedH, oemTex);
        sVideoGlLive = WallpaperVideo.on() || WallpaperVideo.holdsGl();
        if (tex != 0) {
            try {
                Xp.setObjectField(wp, "mTextureId", tex);
            } catch (Throwable t) {
                Xp.w(WallpaperProbe.TAG + "video: cannot swap the texture: " + t);
                tex = 0;
            }
        }
        if (tex == 0) wp = null;
        try {
            return chain.proceed();
        } finally {
            // Only if it is still ours: a reload inside the frame sets a new one of the OEM's.
            if (wp != null && ((Integer) Xp.getObjectField(wp, "mTextureId")).intValue() == tex) {
                Xp.setObjectField(wp, "mTextureId", oemTex);
            }
        }
    }

    static Class<?> plainProgram() {
        return WallpaperProbe.sPlainProgram;
    }

    /** Any thread: a frame for the video, asked for on the main thread like the fade's. */
    static void videoFrame() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            requestFrame(true);
        } else {
            Gen.post(() -> requestFrame(true));
        }
    }

    /**
     * Puts the current dim on whatever program is about to draw. GL thread, inside the
     * wallpaper's own draw.
     *
     * The location is cached per program: the keyguard's program is rebuilt on some
     * transitions, and asking the driver for the location every frame would be a call per
     * frame for a value that only moves during a transition.
     */
    static void applyKeepDim() {
        synchronized (WallpaperProbe.KEEP_LOCK) {
            if (WallpaperProbe.sKeepAt != 0L) {
                float t = WallpaperProbe.sKeepDurMs <= 0 ? 1f
                        : Math.min(1f, (android.os.SystemClock.uptimeMillis() - WallpaperProbe.sKeepAt)
                        / (float) WallpaperProbe.sKeepDurMs);
                float e = t * t * (3f - 2f * t);
                WallpaperProbe.sKeepDim = WallpaperProbe.sKeepFrom + (WallpaperProbe.sKeepTo - WallpaperProbe.sKeepFrom) * e;
                if (t >= 1f) {
                    WallpaperProbe.sKeepDim = WallpaperProbe.sKeepTo;
                    WallpaperProbe.sKeepFrom = WallpaperProbe.sKeepTo;
                    WallpaperProbe.sKeepAt = 0L;
                }
            }
            noteKeepDrawLocked();
        }
        android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_CURRENT_PROGRAM,
                sCurProgram, 0);
        final int prog = sCurProgram[0];
        if (prog != sKeepDimProgram) {
            sKeepDimProgram = prog;
            sKeepDimLoc = prog == 0 ? -1
                    : android.opengl.GLES20.glGetUniformLocation(prog, "uKeepDim");
        }
        if (sKeepDimLoc >= 0) {
            android.opengl.GLES20.glUniform1f(sKeepDimLoc, WallpaperProbe.sKeepDim);
        }
    }

    /** The values the shader was really told, for `op keepdimreport`. Holds KEEP_LOCK. */
    private static void noteKeepDrawLocked() {
        int n = WallpaperProbe.sKeepLogN;
        float last = n == 0 ? Float.NaN : WallpaperProbe.sKeepLogV[(n - 1) % WallpaperProbe.KEEP_LOG];
        if (!Float.isNaN(last) && Math.abs(last - WallpaperProbe.sKeepDim) < 0.005f) return;
        WallpaperProbe.sKeepLogAt[n % WallpaperProbe.KEEP_LOG] = android.os.SystemClock.uptimeMillis();
        WallpaperProbe.sKeepLogV[n % WallpaperProbe.KEEP_LOG] = WallpaperProbe.sKeepDim;
        WallpaperProbe.sKeepLogN = n + 1;
        WallpaperProbe.sKeepDraws++;
    }

    /**
     * Asks for wallpaper frames while the dim is moving. A still wallpaper draws only when
     * asked, so without this the value would sit in a uniform nobody renders.
     */
    static final Runnable KEEP_PUMP = new Runnable() {
        @Override
        public void run() {
            boolean live;
            synchronized (WallpaperProbe.KEEP_LOCK) {
                live = WallpaperProbe.sKeepAt != 0L;
                // No frame came back for a while: the request is not reaching the renderer
                // on this build. Snap and stop rather than pump for ever.
                if (live && android.os.SystemClock.uptimeMillis() - WallpaperProbe.sKeepAt
                        > WallpaperProbe.sKeepDurMs + 250L) {
                    WallpaperProbe.sKeepDim = WallpaperProbe.sKeepTo;
                    WallpaperProbe.sKeepFrom = WallpaperProbe.sKeepTo;
                    WallpaperProbe.sKeepAt = 0L;
                    live = false;
                }
            }
            if (!live) {
                WallpaperProbe.sKeepPumping = false;
                return;
            }
            requestFrame(true);
            WallpaperProbe.KEEP_MAIN.postDelayed(this, KEEP_PUMP_MS);
        }
    };

    static void startKeepPump() {
        if (WallpaperProbe.sKeepPumping) return;
        WallpaperProbe.sKeepPumping = true;
        WallpaperProbe.KEEP_MAIN.removeCallbacks(KEEP_PUMP);
        WallpaperProbe.KEEP_MAIN.post(KEEP_PUMP);
    }

    /** The value the animation is at now, so a new target starts from what is on screen. */
    static float keepDimNowLocked() {
        if (WallpaperProbe.sKeepAt == 0L) return WallpaperProbe.sKeepDim;
        float t = WallpaperProbe.sKeepDurMs <= 0 ? 1f
                : Math.min(1f, (android.os.SystemClock.uptimeMillis() - WallpaperProbe.sKeepAt)
                / (float) WallpaperProbe.sKeepDurMs);
        float e = t * t * (3f - 2f * t);
        return WallpaperProbe.sKeepFrom + (WallpaperProbe.sKeepTo - WallpaperProbe.sKeepFrom) * e;
    }

    /** A redraw of what is uploaded, with no reload - the GPU fade's per-frame request. */
    static void requestFrame(boolean keepAlive) {
        Object eng = lockEngine();
        if (eng != null && !frameRequest(eng, keepAlive)) WallpaperDiag.reportNoFrameRequest(eng);
    }

    /**
     * Whether an entry's boolean is the one that DEFERS the post-render teardown, read off that
     * build's own bytecode and never guessed.
     *
     * Index-aligned with FRAME_REQUESTS. True only where the dex has been read: T and U hand
     * their argument to the post-render step, where false runs finishRendering() on the spot -
     * the EGL surface and context destroyed after the frame - and true defers it by a second.
     * Z's boolean is a different question entirely (whether preRender re-reads the texture), and
     * the T(Z,I) entry is a shape nobody has met yet, so neither of those is ever given ours.
     */
    private static final boolean[] FRAME_REQ_DEFERS_TEARDOWN = {true, true, false, false};

    /** Tries the known frame requests, the one that answered last time first. */
    private static boolean frameRequest(Object eng, boolean keepAlive) {
        int known = WallpaperProbe.sFrameReq;
        if (known >= 0 && callFrameRequest(eng, known, keepAlive)) return true;
        for (int i = 0; i < FRAME_REQUESTS.length; i++) {
            if (i == known) continue;
            if (callFrameRequest(eng, i, keepAlive)) {
                WallpaperProbe.sFrameReq = i;
                return true;
            }
        }
        return false;
    }

    private static boolean callFrameRequest(Object eng, int entry, boolean keepAlive) {
        Object[] req = FRAME_REQUESTS[entry];
        Object[] args = new Object[req.length - 1];
        System.arraycopy(req, 1, args, 0, args.length);
        // The argument is the OEM's, not ours, everywhere its meaning has not been read out of
        // that build's bytecode. It used to be overwritten with keepAlive unconditionally, which
        // sent `true` where FRAME_REQUESTS deliberately says FALSE, the no-animation value at
        // every one of these entry points.
        //
        // On 7.0.7 that went unnoticed: the frame request there is U(Z), which reaches preRender
        // either way. On 8.0.8-flip the frame request is Z(Z), which posts preRender - V(Z),
        // named by its own "#preRender" trace section - and THAT one branches on the argument.
        // With true it never re-read the texture, so a GPU fade armed on nothing: measured as
        // `gpu fade timed out after 226 frames (armed=false)`, about 1.9s of the cover sitting
        // in the wallpaper process without being drawn, which is what read on the phone as the
        // cover taking seconds to appear and needing a fold or a rotation to show up.
        //
        // Where the boolean IS the teardown flag, though, ours is the right value and sending
        // the OEM's costs the whole animation: a run of frames asked for with false destroys the
        // EGL context after every one of them, and the next frame is a new context, which the
        // engine treats as a new surface and re-uploads everything for. That is a fade at 9-17
        // frames where the same fade with true draws 44-46 - the difference between a crossfade
        // and a slideshow, and what made the frost going on for the lyrics stutter.
        if (keepAlive && entry < FRAME_REQ_DEFERS_TEARDOWN.length
                && FRAME_REQ_DEFERS_TEARDOWN[entry]
                && args.length > 0 && args[0] instanceof Boolean) {
            args[0] = Boolean.TRUE;
        }
        try {
            Xp.callMethod(eng, (String) req[0], args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
