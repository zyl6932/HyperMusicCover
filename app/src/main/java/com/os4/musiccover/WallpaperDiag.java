package com.os4.musiccover;

import android.graphics.Bitmap;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Modifier;

/**
 * The wallpaper process's reports: the self test, the timing breakdown of the last cover, the
 * engine's shape as found on this build, the keep-dim state, and the class/bitmap tracers used to
 * find a port's hooks. Nothing here changes what is drawn: the tracers' hooks proceed and only
 * log what went through.
 *
 * Moved out of WallpaperProbe as it was (2026-11-04): every member whole with its comments,
 * references across the line prefixed with WallpaperProbe. or WallpaperDiag., private dropped
 * where the other side needs it. Nothing else changed.
 */
final class WallpaperDiag {

    private WallpaperDiag() {
    }

    /** Coarse identity. Same idea as the module's artPrint, and for the same reason. */
    static int print8(Bitmap b) {
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

    /**
     * The last track change, segment by segment.
     *
     * Segments are printed only where both ends were actually stamped, so the JPEG path - which
     * has no source read and no composition on this side - reports what it did rather than a row
     * of zeroes claiming it was instant.
     */
    static String timingReport() {
        if (WallpaperProbe.sTmRecv == 0L) {
            return "=== cover timing ===\nno push seen yet."
                    + " Change a track with the lock screen up, then ask again.";
        }
        StringBuilder sb = new StringBuilder("=== cover timing === (" + WallpaperProbe.sTmKind + " path)");
        // Absolute, so a press stamped outside this process (`cut -d" " -f1 /proc/uptime` in the
        // same shell command as the key event) can be subtracted from it: what is left is the
        // player's own share, which is the part none of the segments below can see.
        sb.append("\n  the module saw the new track at (uptime): ").append(WallpaperProbe.sTmT0).append("ms");
        // The head start: the press, heard in SystemUI, against the player getting round to
        // saying so. This is the room a prefetch would have to work in.
        if (WallpaperProbe.sTmSkip > 0L && WallpaperProbe.sTmT0 > WallpaperProbe.sTmSkip) {
            sb.append("\n  the PLAYER took: ").append(WallpaperProbe.sTmT0 - WallpaperProbe.sTmSkip)
                    .append("ms to report the ")
                    .append(WallpaperProbe.sTmSkipDir < 0 ? "previous" : "next")
                    .append(" track (we knew the moment it was asked for)");
        }
        seg(sb, "waiting for the artwork", WallpaperProbe.sTmT0, WallpaperProbe.sTmArt,
                WallpaperProbe.sTmTries > 0 ? WallpaperProbe.sTmTries + (WallpaperProbe.sTmTries == 1 ? " try" : " tries") : null);
        seg(sb, "  of which the wallpaper check", WallpaperProbe.sTmCheckMs);
        seg(sb, "preparing and writing it", WallpaperProbe.sTmArt, WallpaperProbe.sTmSent, null);
        seg(sb, "broadcast in flight", WallpaperProbe.sTmSent, WallpaperProbe.sTmRecv, null);
        seg(sb, "reading the source", WallpaperProbe.sTmRecv, WallpaperProbe.sTmRead, null);
        seg(sb, "composing", WallpaperProbe.sTmRead, WallpaperProbe.sTmComposed, null);
        seg(sb, "frosting for the lyrics", WallpaperProbe.sTmComposed, WallpaperProbe.sTmFrosted, null);
        seg(sb, "on to the main thread", WallpaperProbe.sTmFrosted > 0 ? WallpaperProbe.sTmFrosted : WallpaperProbe.sTmComposed, WallpaperProbe.sTmMain, null);
        seg(sb, "uploading the texture", WallpaperProbe.sTmMain, WallpaperProbe.sTmUploaded, null);
        long end = WallpaperProbe.sTmUploaded > 0L ? WallpaperProbe.sTmUploaded : WallpaperProbe.sTmMain;
        if (WallpaperProbe.sTmT0 > 0L && end > WallpaperProbe.sTmT0) {
            sb.append("\n  ---\n  this press to cover on screen: ").append(end - WallpaperProbe.sTmT0)
                    .append("ms");
        }
        // What someone pressing next repeatedly actually watches: the presses in between were
        // thrown away, and the screen held the old cover for all of it.
        if (WallpaperProbe.sTmBurst > 0L && end > WallpaperProbe.sTmBurst && WallpaperProbe.sTmSkips > 0) {
            sb.append("\n  FIRST press of this burst to cover on screen: ")
                    .append(end - WallpaperProbe.sTmBurst).append("ms (")
                    .append(WallpaperProbe.sTmSkips).append(" more press")
                    .append(WallpaperProbe.sTmSkips == 1 ? "" : "es").append(" swallowed on the way)");
        }
        // The two halves of the lyrics' blur. They disagreeing is the bug where a track changed
        // at the moment the lyrics arrived and the cover stayed sharp for the rest of the song:
        // `want` is what SystemUI last asked for, `on` is what the cover is actually drawn with.
        sb.append("\nlyric blur: ").append(WallpaperProbe.sLyricBlur ? "on" : "off")
                .append(", asked for: ").append(WallpaperProbe.sLyricBlurWant ? "on" : "off")
                .append(" (decided at ").append(WallpaperProbe.sBlurSeq).append("ms, ")
                .append(WallpaperProbe.sMsgSeq).append(" messages since)")
                .append(WallpaperProbe.sLyricBlur == WallpaperProbe.sLyricBlurWant ? "" : "   <- OUT OF STEP");
        sb.append("\ncrossfade after that: ").append(WallpaperProbe.sTrackFadeMs)
                .append("ms on a track change, ").append(WallpaperProbe.sFadeMs)
                .append("ms entering or leaving cover mode");
        return sb.toString();
    }

    private static void seg(StringBuilder sb, String name, long from, long to, String note) {
        if (from <= 0L || to <= 0L || to < from) return;
        sb.append("\n  ").append(name).append(": ").append(to - from).append("ms");
        if (note != null) sb.append(" (").append(note).append(')');
    }

    private static void seg(StringBuilder sb, String name, long ms) {
        if (ms <= 0L) return;
        sb.append("\n  ").append(name).append(": ").append(ms).append("ms");
    }

    /** One engine instance: its class, its identity, and every int it carries. */
    static String describeEngine(Object eng) {
        StringBuilder sb = new StringBuilder(eng.getClass().getSimpleName())
                .append('@').append(Integer.toHexString(System.identityHashCode(eng)));
        try {
            for (Class<?> k = eng.getClass(); k != null && k != Object.class;
                 k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType() != int.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    sb.append(' ').append(k.getSimpleName()).append('.').append(f.getName())
                            .append('=').append(f.getInt(eng));
                }
            }
        } catch (Throwable t) {
            sb.append(" (ints unreadable: ").append(t).append(')');
        }
        return sb.toString();
    }

    /**
     * Everything a port to an unseen build needs, in one answer.
     *
     * Written for the case where the phone is someone else's: they run this once (or just send
     * the LSPosed log, which this also goes to) and it has to be enough to work out what moved,
     * without a second round trip. So it reports what was RESOLVED, what it was resolved to, and
     * the candidates for anything that was not - never just "failed".
     */
    static String selfTest() {
        StringBuilder sb = new StringBuilder("=== wallpaper self test ===");
        sb.append("\nwallpaper package: ").append(WallpaperEngines.wallpaperVersion());
        sb.append('\n').append(EngineNames.report());
        sb.append("\nkeyguard engines: ").append(WallpaperProbe.sKeyguardEngines.size());
        synchronized (WallpaperProbe.sKeyguardEngines) {
            for (int n = 0; n < WallpaperProbe.sKeyguardEngines.size(); n++) {
                Object e = WallpaperProbe.sKeyguardEngines.get(n);
                sb.append("\n  #").append(n + 1).append(' ').append(describeEngine(e))
                        .append(e == WallpaperProbe.sKeyguardEngine ? "   <- the one being pushed to" : "");
            }
        }
        sb.append("\ndesktop engine: ").append(WallpaperProbe.sDesktopEngine == null ? "none"
                : describeEngine(WallpaperProbe.sDesktopEngine));
        sb.append("\nvideo engine: ").append(VideoTakeover.sVideoEngine == null ? "none"
                : VideoTakeover.sVideoEngine.getClass().getSimpleName());
        sb.append("\nframe request: ").append(WallpaperProbe.sFrameReq < 0 ? "none has answered yet"
                : WallpaperEngines.FRAME_REQUESTS[WallpaperProbe.sFrameReq][0] + "() (candidate " + (WallpaperProbe.sFrameReq + 1) + " of "
                        + WallpaperEngines.FRAME_REQUESTS.length + ")");
        Object eng = WallpaperProbe.sKeyguardEngine;
        if (eng != null) {
            sb.append("\n  (Z)V methods here: ").append(oneBooleanMethods(eng));
        }
        // The three numbers that decide where the picture lands. On one screen they are always
        // the same; where they differ, they name the two screens that got crossed.
        sb.append("\nrender state: ").append(WallpaperProbe.sRenderState.isEmpty() ? "no upload yet" : WallpaperProbe.sRenderState);
        sb.append("\nsurface adopted: ").append(WallpaperProbe.sSurfaceW).append('x').append(WallpaperProbe.sSurfaceH);
        synchronized (WallpaperProbe.sTexSizes) {
            sb.append("\ntexture sizes held: ").append(WallpaperProbe.sTexSizes.size());
            for (java.util.Map.Entry<Object, android.graphics.Rect> e : WallpaperProbe.sTexSizes.entrySet()) {
                sb.append("\n  ").append(e.getKey().getClass().getSimpleName())
                        .append('@').append(Integer.toHexString(System.identityHashCode(e.getKey())))
                        .append(" -> ").append(e.getValue().width()).append('x')
                        .append(e.getValue().height())
                        .append(e.getKey() == WallpaperProbe.sKeyguardTexture ? "  <- current" : "");
            }
        }
        synchronized (WallpaperProbe.sScreenArts) {
            sb.append("\ncuts held: ").append(WallpaperProbe.sScreenArts.size());
        }
        sb.append("\nart: ").append(WallpaperProbe.describe(WallpaperProbe.sArt)).append("  orig: ").append(WallpaperProbe.describe(WallpaperProbe.sOrig));
        sb.append("\ntexture: ").append(WallpaperProbe.sKeyguardTexture == null ? "not captured" : "captured");
        sb.append("\ngpu fade: on=").append(WallpaperProbe.sGpuFadeOn).append(" hooked=").append(WallpaperProbe.sDrawHooked)
                .append(" broken=").append(WallpaperProbe.sGpuFadeBroken);
        // How the last transition actually ended. "TIMED OUT ... armed=false" is the shape of a
        // frame request that the OEM accepted and did nothing with.
        sb.append("\nlast fade: ").append(WallpaperProbe.sLastFade);
        return sb.toString();
    }

    /** For `op keepdimreport`: what was asked for and what the shader actually got. */
    static String keepDimReport() {
        synchronized (WallpaperProbe.KEEP_LOCK) {
            StringBuilder sb = new StringBuilder("dim=").append(WallpaperProbe.sKeepDim)
                    .append(" from=").append(WallpaperProbe.sKeepFrom).append(" to=").append(WallpaperProbe.sKeepTo)
                    .append(" at=").append(WallpaperProbe.sKeepAt).append(" dur=").append(WallpaperProbe.sKeepDurMs)
                    .append(" msgs=").append(WallpaperProbe.sKeepMsgs).append(" draws=").append(WallpaperProbe.sKeepDraws)
                    .append(" pumping=").append(WallpaperProbe.sKeepPumping).append(" log=");
            int keep = Math.min(WallpaperProbe.sKeepLogN, WallpaperProbe.KEEP_LOG);
            for (int i = 0; i < keep; i++) {
                int k = (WallpaperProbe.sKeepLogN - keep + i) % WallpaperProbe.KEEP_LOG;
                sb.append(WallpaperProbe.sKeepLogAt[k]).append(':').append(WallpaperProbe.sKeepLogV[k]).append(' ');
            }
            return sb.toString();
        }
    }

    static void reportNoFrameRequest(Object eng) {
        // Nothing to call, so say what this build DOES have: the methods taking one boolean are
        // the only candidates, and naming them is the whole of what re-deriving the name needs.
        Xp.log(WallpaperProbe.TAG + "reload: no frame request on " + eng.getClass().getSimpleName()
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

    static void dumpClass(String name, String grep) {
        if (name == null) { Xp.log(WallpaperProbe.TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = Xp.findClass(name, WallpaperProbe.sCl);
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + name + " NOT FOUND");
            return;
        }
        Xp.log(WallpaperProbe.TAG + "=== " + c.getName());
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
                    Xp.log(WallpaperProbe.TAG + "  " + sb);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(WallpaperProbe.TAG + "  " + line);
                }
            }
            Xp.log(WallpaperProbe.TAG + "  --- ^ " + k.getName());
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
    static void traceBitmaps(String name) {
        if (name == null) { Xp.log(WallpaperProbe.TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = Xp.findClass(name, WallpaperProbe.sCl);
        } catch (Throwable t) {
            Xp.log(WallpaperProbe.TAG + name + " NOT FOUND");
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
                Xp.hook("wall.probe.ctor_args." + Xp.slug(c.getSimpleName())
                        + "#" + Xp.slug(Xp.signature(ct)), ct, argLogger("<init>"));
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
                    Xp.hook("wall.probe.bitmap_args."
                            + Xp.slug(m.getDeclaringClass().getSimpleName())
                            + "." + Xp.slug(m.getName())
                            + "#" + Xp.slug(Xp.signature(m)),
                            m, argLogger(m.getName()));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        Xp.log(WallpaperProbe.TAG + "traced " + n + " bitmap-carrying methods on " + c.getName());
    }

    private static XposedInterface.Hooker argLogger(final String name) {
        return chain -> {
            Object result = chain.proceed();
            StringBuilder sb = new StringBuilder(WallpaperProbe.TAG)
                    .append(chain.getThisObject() == null ? "?"
                            : chain.getThisObject().getClass().getSimpleName())
                    .append('.').append(name).append('(');
            java.util.List<Object> args = chain.getArgs();
            for (int j = 0; j < args.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(WallpaperProbe.describe(args.get(j)));
            }
            sb.append(") -> ").append(WallpaperProbe.describe(result));
            Xp.log(sb.toString());
            return result;
        };
    }
}
