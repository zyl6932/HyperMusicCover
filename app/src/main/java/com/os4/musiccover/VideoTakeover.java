package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import android.util.Log;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Modifier;

/**
 * The OEM's video lock wallpaper, taken over for the cover: the hooks on its engines and players,
 * pinning the path they play, handing the surface back, the crash marker that stops a takeover
 * that broke the wallpaper once, and putting the playback position back after a reload.
 *
 * Moved out of WallpaperProbe as it was (2026-11-04): every member whole with its comments,
 * references across the line prefixed with WallpaperProbe. or VideoTakeover., private dropped
 * where the other side needs it, and videoWorker's static synchronized kept on
 * WallpaperProbe.class explicitly so it still shares the one lock with register and the frosted
 * cache. Nothing else changed.
 */
final class VideoTakeover {

    private VideoTakeover() {
    }

    // ------------------------------------------------------------------ the live lock wallpaper

    /**
     * A video lock wallpaper, and how the cover gets into its WINDOW.
     *
     * The SystemUI half of the cover is a view over the keyguard; this half is what the clock's
     * liquid glass and the cards' blur sample, and on a video wallpaper that is the wallpaper
     * window FastPlayer plays into. The cover reaches it the way the user's own video does: it is
     * encoded into a short MP4 (CoverVideoEncoder) and the lock engine's path getter answers with
     * that file while the cover is up, and the engine is asked to reload. On the way out the
     * getter answers with the wallpaper's own path again, the engine reloads, and the player is
     * put back where it would have been had it kept playing behind the cover.
     *
     * Two engine shapes, both read off MiWallpaper 7.0.7 (OS4.0.0.40) and both handled here:
     *
     *   plain (wallpaper_effect_type_2 = 0)  KeyguardVideoEngineImpl. onWallpaperUpdate builds a
     *       new VideoPlayer (FastPlayerImpl) and hands it the path getter's answer.
     *   depth (wallpaper_effect_type_2 = 10) KeyguardVideoDepthEngineImpl. The player lives on the
     *       process-wide VideoDepthManager, which caches the path in its one String field. On
     *       this build onWallpaperUpdate re-reads the getter into that field; on OS4.0.0.35 it
     *       did not (see pinVideoPath), so the field is written as well.
     *
     * Everything below reaches the OEM's objects by TYPE - the FastPlayer, the VideoPlayer, the
     * VideoDepthManager, its String field - never by R8 name: the names move between builds and
     * the types do not. FastPlayer's own methods (seekto, pause, getCurrentPosition, setLoop)
     * are a library's public API and keep their names.
     *
     * Only the LOCK engine is ever touched. The desktop has video engines of the same classes in
     * the same process, and every hook here checks that the object it fired on belongs to the
     * captured lock engine before it changes anything.
     */
    private static volatile Object sVideoDepth;
    /** The live lock wallpaper's engine: KeyguardVideoEngineImpl or KeyguardVideoDepthEngineImpl. */
    static volatile Object sVideoEngine;
    /**
     * The desktop's video engine. It owns the only wallpaper window when the lock screen and the
     * desktop are set to the same video: MIUI then builds no lock engine at all, and the lock
     * screen's glass and cards sample the desktop's window. See targetEngine().
     */
    private static volatile Object sDesktopVideoEngine;
    /** The engine our cover video is on right now; null when it is on none. */
    static volatile Object sActiveEngine;
    /**
     * Shared video only: the cover was taken off the desktop's window for the desktop, and is
     * owed back to it the next time the lock screen shows.
     */
    private static volatile boolean sSharedSuspended;
    /** Whether the lock screen is up, as the engines are told by show/hideKeyguardWallpaper. */
    private static volatile boolean sKeyguardUp = true;
    /**
     * The two base classes. A subclass constructor runs its super's, so hooking these catches
     * every video engine; the instance's class name is what tells lock from desktop.
     */
    private static final String[] CLS_VIDEO_ENGINE_BASES = {
            "com.miui.miwallpaper.wallpaperservice.impl.VideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.VideoEngineImpl",
    };
    /**
     * The lock subclasses, where the path getter is implemented - it is abstract on the bases,
     * and libxposed refuses to hook an abstract method by throwing.
     */
    private static final String[] CLS_VIDEO_ENGINE_LOCK = {
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardVideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardVideoEngineImpl",
    };
    /** Their desktop siblings, for the shared-video case only. */
    private static final String[] CLS_VIDEO_ENGINE_DESKTOP = {
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopVideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopVideoEngineImpl",
    };
    private static final String CLS_FASTPLAYER = "com.miui.fastplayer.FastPlayer";
    private static final String CLS_VIDEO_PLAYER = "com.miui.miwallpaper.container.video.VideoPlayer";
    private static final String CLS_FASTPLAYER_IMPL =
            "com.miui.miwallpaper.container.video.FastPlayerImpl";
    private static final String CLS_DEPTH_MANAGER =
            "com.miui.miwallpaper.container.videodepth.VideoDepthManager";

    /** Whether the lock engine is currently playing our cover video. */
    static volatile boolean sCoverVideoActive;
    private static volatile String sCoverVideoPath;
    private static final String COVER_VIDEO_FILE = "mc_cover.mp4";
    private static final String COVER_VIDEO_BLUR_FILE = "mc_cover_blur.mp4";
    private static java.util.concurrent.ExecutorService sVideoWorker;

    /** The cover video's encoder, built on first use so retirement owns it (Gen.retireOnMain). */
    private static java.util.concurrent.ExecutorService videoWorker() {
        synchronized (WallpaperProbe.class) {
            if (sVideoWorker == null) sVideoWorker = Gen.executor("mc-wall-video");
            return sVideoWorker;
        }
    }
    /**
     * Bumped by every takeover request, in either direction. An encode that finishes after the
     * cover was taken down - or after a newer track superseded it - must not switch the window to
     * a cover nobody asked for any more; the worker compares against this before it does.
     */
    private static final java.util.concurrent.atomic.AtomicInteger sTakeoverGen =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The lock wallpaper's own video file, as the engine's path getter last answered it with the
     * cover off. Where the restore reads the duration from, and where the multi-frame cover video
     * takes its first frame from.
     */
    private static volatile String sLockVideoPath;
    /** Each engine's own video path, as its getter last answered with the cover off. */
    private static final java.util.Map<Object, String> sOwnPaths =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** What the engine last asked its player's loop to be, before any forcing; null unknown. */
    private static volatile Boolean sLockLoops;
    /** Where the user's video was when the cover took the window, and when that was. */
    private static volatile long sSavedVideoPosMs = -1L;
    private static volatile long sSavedVideoAtMs;
    /** Whether the depth manager's path field currently holds our cover path. */
    static volatile boolean sPathPinned;
    /** The depth manager's path field value from before we pinned it. */
    private static volatile String sPinnedOver;

    /**
     * What the lock wallpaper is now, as SystemUI reads it before every push. null until a push
     * has said.
     */
    static volatile Boolean sLockIsVideo;

    /**
     * Whether this push is for a live lock wallpaper.
     *
     * SystemUI's answer decides when there is one: the engine is captured in a constructor that
     * runs once, and this process outlives any number of wallpaper changes, so an engine left
     * over from a video that has since been replaced by a still picture must not send a still
     * wallpaper's cover down the video path. The reverse - a live wallpaper this module has no
     * engine for (a linkage or super wallpaper) - still goes down the video path, which tells
     * SystemUI at once that no reload is coming, so its view is not held for one.
     */
    static boolean videoPath() {
        Boolean live = sLockIsVideo;
        return live != null ? live : sVideoEngine != null;
    }

    /** Told, not guessed: SystemUI carries the answer on every broadcast. */
    static void noteLockWallpaper(boolean video) {
        Boolean was = sLockIsVideo;
        sLockIsVideo = video;
        if (was != null && was == video) return;
        Xp.log(WallpaperProbe.TAG + "the lock wallpaper is " + (video ? "a live one" : "a still picture")
                + (was == null ? "" : ", it was not"));
        if (!video && sCoverVideoActive) videoWindowTakeover(true);
    }

    /**
     * Whether the cover video itself crossfades into the cover. Off, and told rather than decided
     * here: see Main.sVideoFade. With it off this process builds a one-frame cover video.
     */
    static volatile boolean sVideoFade;

    /**
     * The other end of the cover video's crossfade, caught by the art path before sArt moves.
     * Null is normal and means there is nothing to walk from.
     */
    static volatile Bitmap sFadeFrom;

    // ------------------------------------------------------------------ reaching the player

    /** Per class, the first field (walking up the hierarchy) whose declared type is `type`. */
    private static final java.util.Map<String, java.lang.reflect.Field> sTypedFields =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Object fieldOfType(Object o, String type) {
        if (o == null) return null;
        String key = o.getClass().getName() + "->" + type;
        java.lang.reflect.Field f = sTypedFields.get(key);
        if (f == null) {
            outer:
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field d : c.getDeclaredFields()) {
                    if (Modifier.isStatic(d.getModifiers())) continue;
                    if (d.getType().getName().equals(type)) {
                        d.setAccessible(true);
                        f = d;
                        break outer;
                    }
                }
            }
            if (f == null) return null;
            sTypedFields.put(key, f);
        }
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isDesktopEngine(Object eng) {
        return eng != null && eng.getClass().getName().contains("Desktop");
    }

    private static boolean isDepthEngine(Object eng) {
        return eng != null && eng.getClass().getName().contains("Depth");
    }

    /**
     * The VideoDepthManager behind a depth engine: its own field by type, and failing that the
     * class's static singleton (also by type) - there is one manager per process.
     */
    private static Object videoDepthManager(Object eng) {
        if (!isDepthEngine(eng)) return null;
        Object mgr = fieldOfType(eng, CLS_DEPTH_MANAGER);
        if (mgr == null) {
            try {
                Class<?> vdm = Xp.findClass(CLS_DEPTH_MANAGER, WallpaperProbe.sCl);
                for (java.lang.reflect.Field f : vdm.getDeclaredFields()) {
                    if (f.getType() == vdm && Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        mgr = f.get(null);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (mgr != null) sVideoDepth = mgr;
        return mgr;
    }

    /** The plain engine's VideoPlayer (a FastPlayerImpl, or a MediaPlayerImpl), or null. */
    private static Object lockVideoPlayer(Object eng) {
        if (eng == null || isDepthEngine(eng)) return null;
        return fieldOfType(eng, CLS_VIDEO_PLAYER);
    }

    /**
     * What the lock wallpaper is playing through right now: a native FastPlayer (the depth
     * manager's, or a FastPlayerImpl's), or an android.media.MediaPlayer - the plain engine
     * builds a MediaPlayerImpl instead of a FastPlayerImpl when the effect type is 0 and
     * full-screen AOD is off. Null when there is nothing to reach.
     */
    private static Object lockPlayer(Object eng) {
        if (eng == null) return null;
        Object mgr = videoDepthManager(eng);
        if (mgr != null) return fieldOfType(mgr, CLS_FASTPLAYER);
        Object vp = lockVideoPlayer(eng);
        Object fp = fieldOfType(vp, CLS_FASTPLAYER);
        return fp != null ? fp : fieldOfType(vp, "android.media.MediaPlayer");
    }

    private static boolean isMediaPlayer(Object p) {
        return p instanceof android.media.MediaPlayer;
    }

    private static long playerPositionMs(Object p) {
        if (p == null) return -1L;
        try {
            if (isMediaPlayer(p)) return ((android.media.MediaPlayer) p).getCurrentPosition();
            return ((Number) Xp.callMethod(p, "getCurrentPosition")).longValue();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** FastPlayer's "completed" mode: a play-once video parked on its last frame. */
    private static final int PLAYER_MODE_COMPLETED = 4;

    /**
     * Whether a player has finished its source and is holding the last frame - which is where a
     * one-frame cover video sits a moment after it opens, with its position never leaving zero.
     */
    private static boolean playerCompleted(Object eng, Object p) {
        if (p == null) return false;
        try {
            if (!isMediaPlayer(p)) {
                return ((Number) Xp.callMethod(p, "getPlayerMode")).intValue() == PLAYER_MODE_COMPLETED;
            }
            // MediaPlayerImpl keeps its state in an enum whose constant NAMES survive R8.
            Object vp = lockVideoPlayer(eng);
            if (vp == null) return false;
            for (Class<?> c = vp.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!f.getType().isEnum() || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object st = f.get(vp);
                    if (st != null && st.toString().contains("COMPLETE")) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void playerSeek(Object p, long ms) {
        if (p == null) return;
        try {
            if (isMediaPlayer(p)) {
                ((android.media.MediaPlayer) p).seekTo(ms, android.media.MediaPlayer.SEEK_CLOSEST);
            } else {
                Xp.callMethod(p, "seekto", 0.0f, ms, 0);
            }
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "seek failed: " + t);
        }
    }

    private static void playerPause(Object p) {
        if (p == null) return;
        try {
            if (isMediaPlayer(p)) ((android.media.MediaPlayer) p).pause();
            else Xp.callMethod(p, "pause");
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "pause failed: " + t);
        }
    }

    private static String describePlayer(Object eng, Object p) {
        if (p == null) return "none";
        return (isMediaPlayer(p) ? "MediaPlayer" : "FastPlayer") + " pos "
                + playerPositionMs(p) + "ms" + (playerCompleted(eng, p) ? " completed" : "");
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The EngineService calls every video engine receives in the course of being attached and
     * used - the lock screen showing, hiding, being swiped, a wallpaper update. Their names are
     * the interface's and survive R8.
     */
    private static final java.util.Set<String> ENGINE_LIFECYCLE = new java.util.HashSet<>(
            java.util.Arrays.asList("onWallpaperUpdate", "showKeyguardWallpaper",
                    "hideKeyguardWallpaper", "updateKeyguardWallpaperRatio",
                    "updateVideoWallpaperFrame", "controlVideoDepth", "setLockscreenVideoSurface"));

    static void hookVideoEngines() {
        // Captured from what the engine is CALLED with, not from its constructor. The
        // constructor hook never fired on MiWallpaper 7.0.7: the app is compiled ahead of time
        // and ART inlines a constructor that small into its caller, so a hook on it is never
        // reached - measured, a video lock wallpaper set with the module loaded and no
        // "captured" line at all. The attach path (the one method taking the UniversalEngine,
        // and the ones taking the SurfaceHolder) and the lifecycle calls are large, interface
        // dispatched and not inlined; any of them names the engine the moment it is in use.
        java.util.List<String> classes = new java.util.ArrayList<>();
        java.util.Collections.addAll(classes, CLS_VIDEO_ENGINE_BASES);
        java.util.Collections.addAll(classes, CLS_VIDEO_ENGINE_LOCK);
        java.util.Collections.addAll(classes, CLS_VIDEO_ENGINE_DESKTOP);
        for (String cn : classes) {
            try {
                Class<?> vd = Xp.findClass(cn, WallpaperProbe.sCl);
                int n = 0;
                for (Method m : vd.getDeclaredMethods()) {
                    int mod = m.getModifiers();
                    if (Modifier.isAbstract(mod) || Modifier.isStatic(mod) || Modifier.isNative(mod)) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    boolean attach = ps.length == 1
                            && (ps[0] == android.view.SurfaceHolder.class
                            || ps[0].getName().endsWith("UniversalWallpaper$UniversalEngine"));
                    if (!attach && !ENGINE_LIFECYCLE.contains(m.getName())) continue;
                    final boolean keyguardCall = m.getParameterCount() == 2
                            && (m.getName().equals("showKeyguardWallpaper")
                            || m.getName().equals("hideKeyguardWallpaper"));
                    final boolean showing = m.getName().equals("showKeyguardWallpaper");
                    Xp.hook("wall.video.engine_lifecycle", m, chain -> {
                        Object self = chain.getThisObject();
                        noteVideoEngine(self);
                        // Every engine is told when the lock screen shows and hides - the
                        // controller dispatches it to all of them - so this also fires once per
                        // engine; onKeyguardSeen() acts on the change only.
                        if (keyguardCall) onKeyguardSeen(showing);
                        return chain.proceed();
                    });
                    n++;
                }
                Xp.hookAllConstructors("wall.video.engine_ctor", vd, chain -> {
                    Object result = chain.proceed();
                    noteVideoEngine(chain.getThisObject());
                    return result;
                });
                Xp.log(WallpaperProbe.TAG + "video engine hooked on " + cn.substring(cn.lastIndexOf('.') + 1)
                        + " (" + n + " calls)");
            } catch (Throwable t) {
                Xp.w(WallpaperProbe.TAG + "video engine hook failed on " + cn + ": " + t);
            }
        }
        // And from the framework's side: every wallpaper Engine is told its visibility by
        // WallpaperService over binder, which nothing can inline, and the UniversalEngine holds
        // the EngineService it delegates to in a field of that type.
        try {
            Class<?> ue = Xp.findClass(
                    "com.miui.miwallpaper.wallpaperservice.UniversalWallpaper$UniversalEngine", WallpaperProbe.sCl);
            Xp.hookAll("wall.video.visibility", ue, "onVisibilityChanged", chain -> {
                Object r = chain.proceed();
                noteVideoEngine(fieldOfType(chain.getThisObject(),
                        "com.miui.miwallpaper.wallpaperservice.service.EngineService"));
                return r;
            });
            Xp.log(WallpaperProbe.TAG + "UniversalEngine.onVisibilityChanged hooked");
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "UniversalEngine.onVisibilityChanged hook failed: " + t);
        }
        java.util.List<String> getters = new java.util.ArrayList<>();
        java.util.Collections.addAll(getters, CLS_VIDEO_ENGINE_LOCK);
        java.util.Collections.addAll(getters, CLS_VIDEO_ENGINE_DESKTOP);
        for (String cn : getters) {
            try {
                hookVideoPathGetter(Xp.findClass(cn, WallpaperProbe.sCl));
            } catch (Throwable t) {
                Xp.w(WallpaperProbe.TAG + "video path getter hook failed on " + cn + ": " + t);
            }
        }
        hookPlayerDataSource();
        hookPlayerLoop();
    }

    /**
     * A video engine has been seen in use: a Keyguard one is the lock wallpaper's, a Desktop one
     * the desktop's. Whichever of them turns out to be the one the lock screen shows (see
     * targetEngine()) gets a waiting cover straight away - this process restarted, or the
     * wallpaper was changed, with the cover up - rather than at the next track.
     */
    private static void noteVideoEngine(Object self) {
        if (self == null || self == sVideoEngine || self == sDesktopVideoEngine) return;
        String n = self.getClass().getName();
        // UniversalEngine holds its EngineService whatever kind it is, so onVisibilityChanged
        // reaches here with the still-image engines too. Only a video engine is one: a still
        // lock wallpaper runs KeyguardImageEngineImpl, and taking it for a video engine made a
        // freshly started process answer videoPath() with true until the first push said
        // otherwise - which refused its hot reload as "the lock wallpaper is a live video".
        if (!n.contains("Video")) return;
        if (n.contains("Keyguard")) {
            sVideoEngine = self;
        } else if (n.contains("Desktop")) {
            sDesktopVideoEngine = self;
        } else {
            return;
        }
        Xp.log(WallpaperProbe.TAG + "video engine captured: " + n);
        Gen.post(() -> {
            if (sActiveEngine == null && targetEngine() == self && videoPath() && WallpaperProbe.sArt != null) {
                Xp.log(WallpaperProbe.TAG + "a cover is waiting, putting it on " + self.getClass().getSimpleName());
                videoWindowTakeover(false);
            }
        });
    }

    /**
     * Which engine's window the lock screen shows: the lock engine's, unless the lock screen and
     * the desktop are set to the same video, in which case MIUI builds no lock engine and it is
     * the desktop's. Null when the one it would be has not been seen yet.
     */
    private static Object targetEngine() {
        return sharedVideo() ? sDesktopVideoEngine : sVideoEngine;
    }

    /** The controller's path method, String (int which, boolean preview) - found by shape. */
    private static volatile Method sPathOf;

    private static String controllerPath(Object eng, int which) {
        try {
            Object ctrl = fieldOfType(eng, "com.miui.miwallpaper.manager.WallpaperServiceController");
            if (ctrl == null) return null;
            Method m = sPathOf;
            if (m == null) {
                for (Method c : ctrl.getClass().getDeclaredMethods()) {
                    Class<?>[] ps = c.getParameterTypes();
                    if (c.getReturnType() == String.class && ps.length == 2
                            && ps[0] == int.class && ps[1] == boolean.class) {
                        if (m != null) return null;
                        m = c;
                    }
                }
                if (m == null) return null;
                m.setAccessible(true);
                sPathOf = m;
            }
            return (String) m.invoke(ctrl, which, false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile String sSharedKey;
    private static volatile boolean sSharedAnswer;

    /**
     * Whether the lock screen and the desktop play the same video. MIUI copies the video to a lock
     * file and a home file, so it is the two files' CONTENT that is compared - length, and 64KB
     * from each end - asked of the controller the engines themselves ask. Cached on the paths,
     * lengths and times, so a changed wallpaper is noticed.
     */
    private static boolean sharedVideo() {
        Object desk = sDesktopVideoEngine;
        if (desk == null || !Boolean.TRUE.equals(sLockIsVideo)) return false;
        String lock = controllerPath(desk, 2), home = controllerPath(desk, 1);
        if (lock == null || home == null) return false;
        File a = new File(lock), b = new File(home);
        String key = lock + a.length() + a.lastModified() + home + b.length() + b.lastModified();
        if (key.equals(sSharedKey)) return sSharedAnswer;
        boolean same = a.isFile() && b.isFile() && a.length() == b.length()
                && (lock.equals(home) || sameEnds(a, b));
        sSharedKey = key;
        sSharedAnswer = same;
        Xp.log(WallpaperProbe.TAG + "lock and desktop video are " + (same ? "the SAME" : "different")
                + " (" + lock + ", " + home + ")");
        return same;
    }

    private static boolean sameEnds(File a, File b) {
        try (java.io.RandomAccessFile ra = new java.io.RandomAccessFile(a, "r");
             java.io.RandomAccessFile rb = new java.io.RandomAccessFile(b, "r")) {
            int n = (int) Math.min(65536L, ra.length());
            byte[] x = new byte[n], y = new byte[n];
            for (long at : new long[]{0L, Math.max(0L, ra.length() - n)}) {
                ra.seek(at);
                rb.seek(at);
                ra.readFully(x);
                rb.readFully(y);
                if (!java.util.Arrays.equals(x, y)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the lock screen is up, for the shared-video case. The engines' own show/hide calls
     * only arrive with the screen ON, so a cover pushed as the phone went to sleep - the usual
     * way into cover mode - found the lock screen "not up" and waited for the wake, and the
     * glass and cards changed a second after it. The keyguard being locked is the same answer
     * without the wait, and it is adopted: the hide that comes at the unlock is then a change
     * onKeyguardSeen() acts on, which is what takes the cover off the desktop again.
     */
    static boolean lockScreenUp() {
        if (sKeyguardUp) return true;
        try {
            android.app.KeyguardManager km = WallpaperProbe.sCtx == null ? null
                    : WallpaperProbe.sCtx.getSystemService(android.app.KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) {
                sKeyguardUp = true;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * The lock screen showed or hid. Only the shared-video case acts on it: there the cover sits
     * in the DESKTOP's window, so it is taken off as the lock screen goes - the desktop comes back
     * playing its own video - and put back as the lock screen returns.
     */
    private static void onKeyguardSeen(boolean showing) {
        if (sKeyguardUp == showing) return;
        sKeyguardUp = showing;
        Gen.post(() -> {
            if (!showing) {
                Object active = sActiveEngine;
                if (sCoverVideoActive && active != null && active == sDesktopVideoEngine) {
                    Xp.log(WallpaperProbe.TAG + "lock screen gone: the desktop gets its own video back");
                    sSharedSuspended = true;
                    giveBack(sTakeoverGen.incrementAndGet(), false);
                }
            } else if (sSharedSuspended) {
                sSharedSuspended = false;
                if (videoPath() && WallpaperProbe.sArt != null && sActiveEngine == null) {
                    Xp.log(WallpaperProbe.TAG + "lock screen back: the cover goes back on the desktop's window");
                    videoWindowTakeover(false);
                }
            }
        });
    }

    /**
     * The lock engine's path getter - the one the engine asks whenever it (re)builds its player.
     * Found by shape: the only non-static, no-argument String method the lock subclass declares
     * (N() on this build). While the cover is up it answers with the cover video; otherwise its
     * real answer is remembered as the wallpaper's own path.
     */
    private static void hookVideoPathGetter(Class<?> cls) {
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || Modifier.isStatic(mod)) {
                continue;
            }
            if (m.getParameterCount() != 0 || m.getReturnType() != String.class
                    || "toString".equals(m.getName())) {
                continue;
            }
            Xp.hook("wall.video.data_source_get", m, chain -> {
                Object self = chain.getThisObject();
                String cover = sCoverVideoPath;
                if (self != null && self == sActiveEngine && sCoverVideoActive && cover != null) {
                    return cover;
                }
                Object r = chain.proceed();
                if (self != null && r instanceof String && !((String) r).isEmpty()
                        && !r.equals(cover)) {
                    sOwnPaths.put(self, (String) r);
                }
                return r;
            });
            n++;
            Xp.log(WallpaperProbe.TAG + "video path getter hooked: " + cls.getSimpleName() + "." + m.getName());
        }
        if (n == 0) Xp.log(WallpaperProbe.TAG + "no path getter found on " + cls.getSimpleName());
    }

    /**
     * The plain player's data-source setter, as a backstop behind the getter: whatever re-opens
     * the lock player on the wallpaper's own file while the cover is up is pointed at the cover
     * instead. The setter is whichever void(String) method the VideoPlayer interface declares
     * (n() on this build), and the redirect only ever applies to the lock engine's own player.
     */
    private static void hookPlayerDataSource() {
        try {
            Class<?> vp = Xp.findClass(CLS_VIDEO_PLAYER, WallpaperProbe.sCl);
            String setter = null;
            for (Method m : vp.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class && m.getReturnType() == void.class) {
                    if (setter != null) {
                        Xp.log(WallpaperProbe.TAG + "VideoPlayer declares two void(String) methods - not guessing");
                        return;
                    }
                    setter = m.getName();
                }
            }
            if (setter == null) {
                Xp.log(WallpaperProbe.TAG + "VideoPlayer has no data-source setter");
                return;
            }
            for (Class<?> impl : new Class<?>[]{
                    Xp.findClass(CLS_FASTPLAYER_IMPL, WallpaperProbe.sCl),
                    Xp.findClass("com.miui.miwallpaper.container.video.MediaPlayerImpl", WallpaperProbe.sCl)}) {
                Method m = impl.getDeclaredMethod(setter, String.class);
                Xp.hook("wall.video.data_source_set", m, chain -> {
                    Object self = chain.getThisObject();
                    String cover = sCoverVideoPath;
                    if (!sCoverVideoActive || cover == null
                            || self != lockVideoPlayer(sActiveEngine)) {
                        return chain.proceed();
                    }
                    Object[] args = chain.getArgs().toArray();
                    if (cover.equals(args[0])) return chain.proceed();
                    Xp.log(WallpaperProbe.TAG + "lock player data source redirected to the cover (was "
                            + WallpaperProbe.describe(args[0]) + ")");
                    args[0] = cover;
                    return chain.proceed(args);
                });
                Xp.log(WallpaperProbe.TAG + "lock player data source hooked: " + impl.getSimpleName() + "." + setter);
            }
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "lock player data source hook failed: " + t);
        }
    }

    /**
     * FastPlayer.setLoop, for the lock player only. A looping one-frame cover video is a decoder
     * restarting every 33ms for as long as the cover is up, so while the cover plays it is told
     * not to loop, and it parks on its only frame. The engine's own answer is remembered when the
     * cover is off: it is what says whether the user's video loops or plays once, which the
     * restore needs.
     */
    private static void hookPlayerLoop() {
        hookPlayerStart();
        try {
            Xp.hookAll("wall.video.fast_loop", Xp.findClass(CLS_FASTPLAYER, WallpaperProbe.sCl), "setLoop", LOOP_HOOKER);
            Xp.log(WallpaperProbe.TAG + "FastPlayer.setLoop hooked (lock player only)");
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "FastPlayer.setLoop hook failed: " + t);
        }
        try {
            Xp.hookAll("wall.video.media_looping", android.media.MediaPlayer.class, "setLooping", LOOP_HOOKER);
            Xp.log(WallpaperProbe.TAG + "MediaPlayer.setLooping hooked (lock player only)");
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "MediaPlayer.setLooping hook failed: " + t);
        }
    }

    private static final XposedInterface.Hooker LOOP_HOOKER = chain -> {
        Object self = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        if (args.length == 0 || !(args[0] instanceof Boolean)) return chain.proceed();
        Object active = sActiveEngine;
        if (sCoverVideoActive && active != null && self == lockPlayer(active)) {
            args[0] = Boolean.FALSE;
            return chain.proceed(args);
        }
        Object target = targetEngine();
        if (target != null && self == lockPlayer(target)) sLockLoops = (Boolean) args[0];
        return chain.proceed();
    };

    // ------------------------------------------------------------------ the depth path field

    /**
     * The one String field on the VideoDepthManager: the path its player is opened with. Found by
     * TYPE; refuses rather than guesses if a build ever has two.
     */
    private static java.lang.reflect.Field videoPathField(Object mgr) {
        java.lang.reflect.Field found = null;
        for (java.lang.reflect.Field f : mgr.getClass().getDeclaredFields()) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
            if (found != null) {
                Xp.log(WallpaperProbe.TAG + "vgl: " + mgr.getClass().getSimpleName() + " carries more than one "
                        + "String field (" + found.getName() + ", " + f.getName()
                        + ") - not guessing which is the path");
                return null;
            }
            found = f;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    private static String readPathField(Object mgr) {
        java.lang.reflect.Field f = mgr == null ? null : videoPathField(mgr);
        if (f == null) return null;
        try {
            return (String) f.get(mgr);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writePathField(Object mgr, String path) {
        java.lang.reflect.Field f = videoPathField(mgr);
        if (f == null) return;
        try {
            f.set(mgr, path);
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + "vgl: cannot write the depth path field: " + t);
        }
    }

    /**
     * Writes the cover path into the depth manager's cached path. On OS4.0.0.35 the reload did
     * NOT re-read the path getter - it reopened the player on this field, which only the engine's
     * initial setup writes - so hooking the getter alone left the wallpaper's own video playing.
     * On this build the reload re-reads the getter and this is redundant; it stays for the
     * builds where it is not.
     */
    private static void pinVideoPath(Object eng) {
        Object mgr = videoDepthManager(eng);
        String cover = sCoverVideoPath;
        if (mgr == null || cover == null) return;
        if (!sPathPinned) {
            String was = readPathField(mgr);
            if (was != null && !was.equals(cover)) sPinnedOver = was;
            if (sLockVideoPath == null && was != null && !was.equals(cover)) sLockVideoPath = was;
        }
        writePathField(mgr, cover);
        sPathPinned = true;
    }

    /** Puts the wallpaper's own path back into the depth manager before the reload out. */
    private static void unpinVideoPath(Object eng) {
        if (!sPathPinned) return;
        sPathPinned = false;
        Object mgr = videoDepthManager(eng);
        String original = sLockVideoPath != null ? sLockVideoPath : sPinnedOver;
        sPinnedOver = null;
        if (mgr != null && original != null) writePathField(mgr, original);
    }

    // ------------------------------------------------------------------ taking the window

    /**
     * Puts the cover into the lock wallpaper's window (on = false), or gives the window back to
     * the wallpaper's own video (on = true). Every call ends in exactly one "videoreload" to
     * SystemUI - sent once the window really shows the result, or at once when nothing is going
     * to change - because SystemUI holds its own half of the transition until it hears it.
     */
    static boolean videoWindowTakeover(boolean on) {
        final int gen = sTakeoverGen.incrementAndGet();
        if (on) {
            sFadeFrom = null;
            // Cover mode is over, so nothing is owed to the desktop at the next lock either.
            sSharedSuspended = false;
            if (!sCoverVideoActive) {
                // Taken down before the cover ever reached the window (an encode still running,
                // which the bumped generation now drops): the user's video never stopped, so the
                // position saved for it is stale by the next entry.
                sSavedVideoPosMs = -1L;
                WallpaperProbe.tellSystemUi("videoreload", "no cover video was active");
                return true;
            }
            giveBack(gen, true);
            return true;
        }

        final Object eng = targetEngine();
        if (eng == null) {
            Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: no video engine for the lock screen yet");
            WallpaperProbe.tellSystemUi("videoreload", "no video engine to reload");
            return false;
        }
        if (takeoverBroken(eng)) {
            Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: " + eng.getClass().getSimpleName() + " crashed on a"
                    + " cover video before, its window is left to its own video");
            WallpaperProbe.tellSystemUi("videoreload", "this engine crashed on a cover video before");
            return false;
        }
        if (isDesktopEngine(eng) && !lockScreenUp()) {
            // Shared video, and the desktop is what is on screen: the cover would be on the
            // desktop. Owed to the next lock screen instead - see onKeyguardSeen().
            sSharedSuspended = true;
            Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: the desktop is showing, the cover waits for the lock");
            WallpaperProbe.tellSystemUi("videoreload", "the desktop is showing, the cover waits for the lock");
            return true;
        }
        if (sCoverVideoActive && sActiveEngine != null && sActiveEngine != eng) {
            // The lock screen moved to another engine under the cover (the wallpaper was
            // changed between shared and separate): hand the old one its video first.
            giveBack(gen, false);
        }

        final Bitmap art = WallpaperProbe.sArt;
        final Context ctx = WallpaperProbe.sCtx;
        if (art == null || ctx == null) {
            Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: nothing to build a cover video from (art="
                    + WallpaperProbe.describe(art) + ")");
            WallpaperProbe.tellSystemUi("videoreload", "no art to build a cover video from");
            return false;
        }
        if (!sCoverVideoActive && sSavedVideoPosMs < 0) {
            // Where the user's video is, read before anything replaces it. The restore resumes
            // from here plus however long the cover was up.
            sSavedVideoPosMs = Math.max(0L, playerPositionMs(lockPlayer(eng)));
            sLockVideoPath = sOwnPaths.get(eng);
            sSavedVideoAtMs = SystemClock.uptimeMillis();
            Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: the wallpaper's video was at "
                    + sSavedVideoPosMs + "ms");
        }
        final boolean lyricBlur = WallpaperProbe.sLyricBlur;
        final long checksum = WallpaperProbe.sCurrentArtChecksum != 0
                ? (lyricBlur ? WallpaperProbe.sCurrentArtChecksum ^ 0x55aa55aa55aa55aaL : WallpaperProbe.sCurrentArtChecksum)
                : 0;
        // Caught here rather than in the worker: the next art push would move them.
        final Bitmap fadeFrom = sFadeFrom;
        final long fadeMs = WallpaperProbe.sFadeMs;
        final String lockPath = sLockVideoPath;
        final long savedPos = sSavedVideoPosMs;

        videoWorker().submit(() -> {
            try {
                File videoFile = new File(ctx.getFilesDir(),
                        lyricBlur ? COVER_VIDEO_BLUR_FILE : COVER_VIDEO_FILE);
                int w = WallpaperProbe.sReportedW > 0 ? WallpaperProbe.sReportedW : ctx.getResources().getDisplayMetrics().widthPixels;
                int h = WallpaperProbe.sReportedH > 0 ? WallpaperProbe.sReportedH : ctx.getResources().getDisplayMetrics().heightPixels;
                int ew = (w / 2) * 2, eh = (h / 2) * 2;
                // A cover already encoded for this very art is only a reload. Asked before the
                // crop and the frosting, which are what a cache hit used to spend 170ms on before
                // the encoder said it had the file all along. Only a one-frame cover: a
                // crossfade's file is keyed on its other end too, which needs the pictures.
                boolean fadeWanted = sVideoFade;
                if (!fadeWanted && CoverVideoEncoder.isCached(videoFile, checksum, isDepthEngine(eng))) {
                    Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: " + videoFile.getName() + " is already this cover");
                    putCoverVideo(eng, gen, videoFile.getAbsolutePath(), lyricBlur);
                    return;
                }
                // centerCrop always allocates, so this end is ours to give back.
                Bitmap to = WallpaperProbe.centerCrop(art, ew, eh);
                if (lyricBlur) {
                    Bitmap blurred = CoverCompose.frosted(to);
                    if (blurred != to) {
                        to.recycle();
                        to = blurred;
                    }
                }
                Bitmap from = null;
                boolean ownFrom = false;
                try {
                    if (sVideoFade) {
                        if (fadeFrom != null && !fadeFrom.isRecycled()) {
                            from = WallpaperProbe.centerCrop(fadeFrom, ew, eh);
                        } else {
                            from = wallpaperFrame(lockPath, savedPos, ew, eh);
                        }
                        ownFrom = from != null;
                    }
                    boolean ok = CoverVideoEncoder.encodeToMp4(from, to, videoFile, checksum,
                            from == null ? 0L : fadeMs, isDepthEngine(eng));
                    if (gen != sTakeoverGen.get()) {
                        // Taken down, or superseded by a newer track, while this encoded. The
                        // newer request has said - or will say - its own word to SystemUI.
                        Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: superseded while encoding, dropped");
                        return;
                    }
                    if (!ok || !videoFile.exists() || videoFile.length() == 0) {
                        Xp.w(WallpaperProbe.TAG + "videoWindowTakeover: cover video encoding failed");
                        WallpaperProbe.tellSystemUi("videoreload", "the cover video could not be encoded");
                        return;
                    }
                    putCoverVideo(eng, gen, videoFile.getAbsolutePath(), lyricBlur);
                } finally {
                    to.recycle();
                    if (ownFrom && from != null) from.recycle();
                }
            } catch (Throwable t) {
                Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: worker error: " + Log.getStackTraceString(t));
                if (gen == sTakeoverGen.get()) {
                    WallpaperProbe.tellSystemUi("videoreload", "the cover video worker failed");
                }
            }
        });
        return true;
    }

    /** The encoded cover goes into the engine's window. From the video worker. */
    private static void putCoverVideo(final Object eng, final int gen, final String path,
                                      final boolean lyricBlur) {
        Gen.post(() -> {
            if (gen != sTakeoverGen.get()) return;
            if (isDesktopEngine(eng) && !lockScreenUp()) {
                // Unlocked while this encoded, on a shared video.
                sSharedSuspended = true;
                WallpaperProbe.tellSystemUi("videoreload", "the desktop is showing, the cover waits");
                return;
            }
            sCoverVideoPath = path;
            sCoverVideoActive = true;
            sActiveEngine = eng;
            pinVideoPath(eng);
            markTakeover(eng);
            Xp.log(WallpaperProbe.TAG + "videoWindowTakeover: cover video (" + (lyricBlur ? "frosted" : "sharp")
                    + ", " + new File(path).length() + "B) into "
                    + eng.getClass().getSimpleName());
            // Ahead of the reload, not after it: see CoverPush.noteVideoReloading().
            WallpaperProbe.tellSystemUi("videoreloading", "the cover is going onto the window");
            reloadThen(eng, gen, fp -> {
                WallpaperProbe.tellSystemUi("videoreload", "the cover is on the window");
                // Alive a few seconds past the first frame: this engine plays a cover.
                Gen.postDelayed(VideoTakeover::clearTakeoverMark, TAKEOVER_SURVIVE_MS);
            });
        });
    }

    /**
     * Takes the cover off the engine it is on and gives that engine's window back to its own
     * video, resumed where it would have been. `tell` is whether SystemUI is waiting on this -
     * it is when cover mode ends; it is not when a shared video's cover is only being taken off
     * the desktop for the length of an unlock.
     */
    private static void giveBack(final int gen, final boolean tell) {
        final Object eng = sActiveEngine;
        sCoverVideoActive = false;
        sCoverVideoPath = null;
        sActiveEngine = null;
        if (eng == null) {
            if (tell) WallpaperProbe.tellSystemUi("videoreload", "no cover video was active");
            return;
        }
        unpinVideoPath(eng);
        Xp.log(WallpaperProbe.TAG + "giving " + eng.getClass().getSimpleName() + " its own video back");
        final long savedPos = sSavedVideoPosMs;
        final long savedAt = sSavedVideoAtMs;
        final String path = sLockVideoPath;
        sSavedVideoPosMs = -1L;
        sPreSeeked = -1L;
        if (savedPos >= 0) {
            sPreSeekEng = eng;
            sPreSeekPath = path;
            sPreSeekAt = savedAt;
            sPreSeekUntil = SystemClock.uptimeMillis() + FIRST_FRAME_TIMEOUT_MS;
            sPreSeekPos = savedPos;
        }
        reloadThen(eng, gen, fp -> restorePosition(eng, gen, path, savedPos, savedAt, tell));
    }

    // ------------------------------------------------------------------ the crash guard

    /**
     * Written just before a reload that puts a cover video on an engine, removed once the process
     * has lived TAKEOVER_SURVIVE_MS past its first frame. Found at the next start, it means the
     * process died with a cover video in the player - and since this process restores its cover
     * on start and puts it straight back, that is a crash loop: measured on OS4.0.0.40, where the
     * depth engine's native decoder crashed on every reload, the process restarted every two
     * seconds until the system stopped binding the wallpaper at all. The engine class is then
     * recorded against this module build and never given a cover video again by it.
     */
    private static final String TAKEOVER_MARK = "mc_takeover_pending";
    private static final String TAKEOVER_BROKEN = "mc_takeover_broken";
    private static final long TAKEOVER_SURVIVE_MS = 3000L;
    /** "engine class|module build" entries that crashed; see checkTakeoverCrash(). */
    private static final java.util.Set<String> sBrokenTakeovers =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** This module build, so a fixed build gets to try again. */
    private static String moduleStamp() {
        try {
            android.content.pm.ApplicationInfo ai = Xp.api().getModuleApplicationInfo();
            return String.valueOf(new File(ai.sourceDir).lastModified());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String takeoverKey(Object eng) {
        return eng.getClass().getName() + "|" + moduleStamp();
    }

    static void checkTakeoverCrash(Context ctx) {
        try {
            File broken = new File(ctx.getFilesDir(), TAKEOVER_BROKEN);
            File mark = new File(ctx.getFilesDir(), TAKEOVER_MARK);
            if (mark.exists()) {
                String key = new String(WallpaperProbe.readBytes(mark.getAbsolutePath()), "UTF-8").trim();
                mark.delete();
                java.io.FileOutputStream out = new java.io.FileOutputStream(broken, true);
                out.write((key + "\n").getBytes("UTF-8"));
                out.close();
                Xp.log(WallpaperProbe.TAG + "the last process died putting a cover video on " + key
                        + " - that engine is not given one again by this build");
            }
            if (broken.exists()) {
                for (String line : new String(WallpaperProbe.readBytes(broken.getAbsolutePath()), "UTF-8").split("\n")) {
                    if (!line.trim().isEmpty()) sBrokenTakeovers.add(line.trim());
                }
            }
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "crash guard check failed: " + t);
        }
    }

    private static boolean takeoverBroken(Object eng) {
        return sBrokenTakeovers.contains(takeoverKey(eng));
    }

    private static void markTakeover(Object eng) {
        Context c = WallpaperProbe.sCtx;
        if (c == null) return;
        try {
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new File(c.getFilesDir(), TAKEOVER_MARK));
            out.write(takeoverKey(eng).getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "crash guard mark failed: " + t);
        }
    }

    private static void clearTakeoverMark() {
        Context c = WallpaperProbe.sCtx;
        if (c != null) new File(c.getFilesDir(), TAKEOVER_MARK).delete();
    }

    /** What to do with the rebuilt player once its first frame is on the window. */
    private interface AfterReload {
        void run(Object fastPlayer);
    }

    /** How long the first-frame wait may run before carrying on anyway. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 1500L;
    private static final long FIRST_FRAME_POLL_MS = 16L;

    /**
     * Asks the lock engine to rebuild its player - which re-reads the path getter - and runs
     * `then` once the rebuilt player has put a frame on the window: its position leaves zero, or
     * it reports the completed mode a one-frame video parks in. The rebuild replaces the player
     * object, so the player is looked up afresh on every poll. A newer takeover abandons the wait.
     */
    private static void reloadThen(final Object eng, final int gen, final AfterReload then) {
        Gen.post(() -> {
            if (gen != sTakeoverGen.get()) return;
            final Object before = lockPlayer(eng);
            try {
                Xp.callMethod(eng, "onWallpaperUpdate", "video", isDesktopEngine(eng) ? 1 : 2);
            } catch (Throwable t) {
                Xp.w(WallpaperProbe.TAG + "reload failed: " + t);
                WallpaperProbe.tellSystemUi("videoreload", "the reload failed");
                return;
            }
            final long startedAt = SystemClock.uptimeMillis();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                if (gen != sTakeoverGen.get()) return;
                Object fp = lockPlayer(eng);
                long pos = playerPositionMs(fp);
                long waited = SystemClock.uptimeMillis() - startedAt;
                // A FastPlayer is replaced by the rebuild, so the old one - still reporting where
                // the previous source was - must not count. A MediaPlayer is reset in place
                // instead, which already puts its position back to zero.
                boolean fresh = fp != null && (isMediaPlayer(fp) || fp != before);
                if (fresh && (pos > 0 || playerCompleted(eng, fp))) {
                    Xp.log(WallpaperProbe.TAG + "first frame after " + waited + "ms ("
                            + describePlayer(eng, fp) + ")");
                    then.run(fp);
                    return;
                }
                if (waited >= FIRST_FRAME_TIMEOUT_MS) {
                    Xp.log(WallpaperProbe.TAG + "first frame wait timed out (player "
                            + (fp == null ? "none" : fresh ? "rebuilt" : "NOT rebuilt") + ", "
                            + describePlayer(eng, fp) + ")");
                    then.run(fp);
                    return;
                }
                Gen.postDelayed(poll[0], FIRST_FRAME_POLL_MS);
            };
            Gen.postDelayed(poll[0], FIRST_FRAME_POLL_MS);
            Xp.log(WallpaperProbe.TAG + "reload asked of " + eng.getClass().getSimpleName());
        });
    }

    /** Within this distance of the end, a play-once wallpaper counts as finished. */
    private static final long END_ZONE_MS = 150L;
    /** Where a finished wallpaper parks: inside its final frame. */
    private static final long PARK_FROM_END_MS = 40L;
    /** How far below the target a read-back may be before a seek counts as lost. */
    private static final long SEEK_TOLERANCE_MS = 400L;
    private static final long SEEK_VERIFY_MS = 250L;

    /**
     * Puts the rebuilt player back where the user's video would have been had it kept playing
     * behind the cover - the rebuild itself always starts from zero - and only then tells
     * SystemUI, whose cover view is still up over the window until it hears it. A looping video
     * wraps; a play-once video that would have finished parks on its last frame instead of
     * replaying its ending.
     */
    private static void restorePosition(final Object eng, final int gen, final String path,
                                        final long savedPos, final long savedAt,
                                        final boolean tell) {
        long dur = videoDurationMs(path);
        long[] t = restoreTarget(path, savedPos, savedAt);
        long target = t[0];
        boolean park = t[1] != 0L;
        Xp.log(WallpaperProbe.TAG + "restore: saved " + savedPos + "ms, target " + target + "ms of " + dur
                + "ms, loops=" + sLockLoops + (park ? ", parking on the last frame" : ""));
        if (target <= 100L && !park) {
            if (tell) WallpaperProbe.tellSystemUi("videoreload", "the wallpaper's video is back");
            return;
        }
        // Normally already done: the seek went in before the rebuilt player's start (see
        // hookPlayerStart), so its first frame is the target and there is nothing to land. Only
        // a read-back that says otherwise falls through to seeking the running player.
        long pre = sPreSeeked;
        sPreSeeked = -1L;
        if (pre > 0L) {
            long at = playerPositionMs(lockPlayer(eng));
            if (park ? at >= pre - SEEK_TOLERANCE_MS - 500L : at >= target - SEEK_TOLERANCE_MS) {
                Xp.log(WallpaperProbe.TAG + "restore: seeked to " + pre + "ms before the first frame, at " + at + "ms");
                if (tell) WallpaperProbe.tellSystemUi("videoreload", "the wallpaper's video is back at " + at + "ms");
                return;
            }
            Xp.log(WallpaperProbe.TAG + "restore: the seek before start did not hold (at " + at + "ms)");
        }
        final long want = target;
        final boolean parkIt = park;
        // SystemUI is told now, with the first seek, not after it has been read back: the
        // cover view over the window starts its fade-out on this word, and its first frames are
        // still nearly opaque while the seek lands. Waiting for the read-back held the cover up
        // a further quarter to three quarters of a second on every exit.
        final boolean[] told = {!tell};
        final int[] tries = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (gen != sTakeoverGen.get()) return;
            playerSeek(lockPlayer(eng), want);
            if (!told[0]) {
                told[0] = true;
                WallpaperProbe.tellSystemUi("videoreload", "the wallpaper's video is back, seeking to " + want + "ms");
            }
            Gen.postDelayed(() -> {
                if (gen != sTakeoverGen.get()) return;
                Object now = lockPlayer(eng);
                long at = playerPositionMs(now);
                // A park is not read back: a finished play-once player reports where its last
                // decoded frame sits, not the seek target (5200ms against 5960ms, measured), so
                // the read-back never agreed and three more seeks held the SystemUI cover up for
                // three quarters of a second longer on every exit.
                if (!parkIt && at < want - SEEK_TOLERANCE_MS && ++tries[0] < 3) {
                    Gen.post(step[0]);
                    return;
                }
                if (parkIt) playerPause(now);
                Xp.log(WallpaperProbe.TAG + "restore: at " + at + "ms after " + (tries[0] + 1) + " seek(s)");
                Xp.log(WallpaperProbe.TAG + "restore: settled at " + at + "ms");
            }, SEEK_VERIFY_MS);
        };
        Gen.post(step[0]);
    }

    /**
     * Where the user's video should resume: {target ms, 1 when it parks on its last frame}. A
     * looping video wraps; a play-once video that would have finished parks instead of
     * replaying its ending.
     */
    private static long[] restoreTarget(String path, long savedPos, long savedAt) {
        long dur = videoDurationMs(path);
        long target = savedPos < 0 ? 0L : savedPos + (SystemClock.uptimeMillis() - savedAt);
        long park = 0L;
        if (dur > 0) {
            Boolean loops = sLockLoops;
            if (loops != null && loops) {
                target %= dur;
            } else if (target >= dur - END_ZONE_MS) {
                target = Math.max(1L, dur - PARK_FROM_END_MS);
                park = 1L;
            }
        }
        return new long[] {target, park};
    }

    /**
     * A restore owed to the rebuilt player's start. The rebuild always starts from zero, and
     * a seek after the fact shows frame 0 for the quarter second it takes to land - on the
     * wallpaper window, which is what the lock screen's cards and notifications blur. So the
     * first frame after an exit was the video's opening frame in every card (a green flash on
     * the sheep video), with the right frame only arriving after the cover had gone. Seeking
     * before start is what the OEM's own startInternal does (seekTo 0 when COMPLETED), so the
     * first frame the window gets is already the right one.
     */
    private static volatile Object sPreSeekEng;
    private static volatile String sPreSeekPath;
    private static volatile long sPreSeekPos = -1L;
    private static volatile long sPreSeekAt;
    private static volatile long sPreSeekUntil;
    /** Where the seek before start went, -1 when none did. */
    private static volatile long sPreSeeked = -1L;

    private static void hookPlayerStart() {
        try {
            Xp.hookAll("wall.video.fast_start", Xp.findClass(CLS_FASTPLAYER, WallpaperProbe.sCl), "start", chain -> {
                long pos = sPreSeekPos;
                if (pos >= 0 && SystemClock.uptimeMillis() < sPreSeekUntil) {
                    Object eng = sPreSeekEng;
                    Object self = chain.getThisObject();
                    if (eng != null && lockPlayer(eng) == self) {
                        sPreSeekPos = -1L;
                        long target = restoreTarget(sPreSeekPath, pos, sPreSeekAt)[0];
                        if (target > 100L) {
                            try {
                                Xp.callMethod(self, "seekto", 0.0f, target, 0);
                                sPreSeeked = target;
                            } catch (Throwable t) {
                                Xp.w(WallpaperProbe.TAG + "seek before start failed: " + t);
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            Xp.log(WallpaperProbe.TAG + "FastPlayer.start hooked (restore seek before start)");
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "FastPlayer.start hook failed: " + t);
        }
        // The plain shape's player, the same story: its reload is a reset MediaPlayer, and the
        // quarter second from its frame 0 to the seek was frame 0 in every card.
        try {
            Xp.hookAll("wall.video.media_start", android.media.MediaPlayer.class, "start", chain -> {
                long pos = sPreSeekPos;
                if (pos >= 0 && SystemClock.uptimeMillis() < sPreSeekUntil) {
                    Object eng = sPreSeekEng;
                    Object self = chain.getThisObject();
                    if (eng != null && lockPlayer(eng) == self) {
                        sPreSeekPos = -1L;
                        long target = restoreTarget(sPreSeekPath, pos, sPreSeekAt)[0];
                        if (target > 100L) {
                            try {
                                ((android.media.MediaPlayer) self).seekTo(target,
                                        android.media.MediaPlayer.SEEK_CLOSEST);
                                sPreSeeked = target;
                            } catch (Throwable t) {
                                Xp.w(WallpaperProbe.TAG + "seek before start failed: " + t);
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            Xp.log(WallpaperProbe.TAG + "MediaPlayer.start hooked (restore seek before start)");
        } catch (Throwable t) {
            Xp.w(WallpaperProbe.TAG + "MediaPlayer.start hook failed: " + t);
        }
    }

    /** Duration of a video file, cached per path. 0 when unknown. */
    private static volatile String sDurationOf;
    private static volatile long sDurationMs;

    private static long videoDurationMs(String path) {
        if (path == null) return 0L;
        if (path.equals(sDurationOf)) return sDurationMs;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = d == null ? 0L : Long.parseLong(d);
            sDurationOf = path;
            sDurationMs = ms;
            return ms;
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + "duration of " + path + " unreadable: " + t);
            return 0L;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * A frame of the wallpaper's own video, for the multi-frame cover video's way in (only with
     * Main.sVideoFade on). Scaled by the decoder: a 4K frame is 35MB unscaled. Null is not a
     * failure - it is the one-frame cover.
     */
    private static Bitmap wallpaperFrame(String path, long atMs, int w, int h) {
        if (path == null) return null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            Bitmap frame = atMs > 0
                    ? mmr.getScaledFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                    : mmr.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
            if (frame == null) return null;
            Bitmap out = WallpaperProbe.centerCrop(frame, w, h);
            if (out != frame) frame.recycle();
            return out;
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + "wallpaperFrame: cannot decode " + path + " (" + t + ")");
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** The video half of `WPROBE op state`, as the broadcast's result. */
    static String describeVideo() {
        Object eng = sActiveEngine != null ? sActiveEngine : targetEngine();
        Object fp = lockPlayer(eng);
        Object mgr = videoDepthManager(eng);
        StringBuilder sb = new StringBuilder();
        sb.append("engine=").append(eng == null ? "none" : eng.getClass().getSimpleName())
          .append(" lockEngine=").append(sVideoEngine == null ? "none" : "seen")
          .append(" desktopEngine=").append(sDesktopVideoEngine == null ? "none" : "seen")
          .append(" shared=").append(sharedVideo())
          .append(" keyguardUp=").append(sKeyguardUp)
          .append(" suspended=").append(sSharedSuspended)
          .append(" lockIsVideo=").append(sLockIsVideo)
          .append(" coverActive=").append(sCoverVideoActive)
          .append(" coverPath=").append(sCoverVideoPath)
          .append(" lockPath=").append(sLockVideoPath)
          .append(" loops=").append(sLockLoops)
          .append(" saved=").append(sSavedVideoPosMs)
          .append(" player=").append(describePlayer(eng, fp))
          .append(" depthPath=").append(mgr == null ? "-" : readPathField(mgr))
          .append(" pinned=").append(sPathPinned)
          .append(" broken=").append(sBrokenTakeovers)
          .append(" gen=").append(sTakeoverGen.get());
        return sb.toString();
    }
}
