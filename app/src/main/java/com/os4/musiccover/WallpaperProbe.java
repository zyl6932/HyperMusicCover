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

import java.io.File;
import java.io.FileOutputStream;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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

    public static void handle(XC_LoadPackage.LoadPackageParam lpp) {
        sCl = lpp.classLoader;
        XposedBridge.log(TAG + "loaded into " + PKG + " (proc " + lpp.processName + ")");

        // Must be installed at load time: getBitmap() runs when the GL surface is created and
        // the texture is then cached, so a hook added later never sees it.
        try {
            Class<?> base = XposedHelpers.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer", sCl);
            // The one place the wallpaper bitmap reaches the GL upload:
            //   onSurfaceCreated() -> mTexture.use(c) -> lambda$onSurfaceCreated$0(Bitmap)
            // Hooking here rather than on WallpaperTexture.getWallpaperBitmap() because
            // thisObject is the renderer, so we can tell the keyguard one from the desktop one
            // and leave the home wallpaper alone.
            XposedBridge.hookAllMethods(base, "lambda$onSurfaceCreated$0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean keyguard = param.thisObject.getClass().getName().contains("Keyguard");
                    if (!keyguard || !(param.args[0] instanceof Bitmap)) return;
                    Bitmap orig = (Bitmap) param.args[0];
                    int w = orig.getWidth(), h = orig.getHeight();
                    if (w != sReportedW || h != sReportedH) {
                        sReportedW = w;
                        sReportedH = h;
                        XposedBridge.log(TAG + "keyguard texture is " + w + "x" + h);
                    }
                    Bitmap art = sArt;
                    if (art == null) return;
                    // Match the original exactly: updateDimensions/updateMatrix derive the GL
                    // matrix from these, so a different size lands the wallpaper askew.
                    Bitmap fitted;
                    if (art.getWidth() == w && art.getHeight() == h) {
                        fitted = art;                       // composed at exactly this size
                    } else if (sFitted != null && sFittedOf == art
                            && sFitted.getWidth() == w && sFitted.getHeight() == h) {
                        fitted = sFitted;                   // already fitted for this texture
                    } else {
                        fitted = centerCrop(art, w, h);
                        sFitted = fitted;
                        sFittedOf = art;
                    }
                    param.args[0] = fitted;
                    XposedBridge.log(TAG + "wallpaper texture REPLACED " + describe(orig)
                            + " -> " + describe(fitted)
                            + (fitted == art ? " (no rescale)" : ""));
                }
            });
            XposedBridge.log(TAG + "upload path hooked on ImageWallpaperRenderer");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getBitmap hook failed: " + t);
        }

        // Measured: of the ~380ms a track change took, 210ms was the OEM's own getBitmap()
        // decoding the real lock wallpaper off disk inside onSurfaceCreated - a bitmap thrown
        // away one call later when we substitute ours. Handing back the art we are going to
        // substitute anyway removes that decode outright. The upload path is still the lambda
        // below; this only short-circuits the source, and only once the texture size is known,
        // so a cold start still learns the real dimensions first.
        try {
            Class<?> kg = XposedHelpers.findClass(
                    "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer",
                    sCl);
            XposedBridge.hookAllMethods(kg, "getBitmap", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Bitmap fitted = fittedArt();
                    if (fitted != null) param.setResult(fitted);
                }
            });
            XposedBridge.log(TAG + "keyguard getBitmap short-circuit installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getBitmap short-circuit failed: " + t);
        }

        // Runtime refresh. ImageEngineImpl.U() ("preRender", on the GL thread) re-runs
        //     mRenderer.onSurfaceCreated(); mRenderer.onSurfaceChanged(w, h)
        // whenever its pending-surface flag is set, and onSurfaceCreated is the path that ends in
        // mTexture.use(...) -> the lambda we already replace the bitmap in. So a new track only
        // has to set that flag and ask for a frame; the OEM does the upload itself, including the
        // frosted copy the notification cards blur against.
        try {
            Class<?> eng = XposedHelpers.findClass(CLS_KEYGUARD_ENGINE, sCl);
            XposedBridge.hookAllConstructors(eng, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sKeyguardEngine = param.thisObject;
                    XposedBridge.log(TAG + "keyguard engine captured: " + param.thisObject);
                }
            });
            XposedBridge.log(TAG + "keyguard engine hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "keyguard engine hook failed: " + t);
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

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    register((Application) param.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "register failed: " + t);
                }
            }
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
            XposedBridge.log(TAG + "saveArt failed: " + t);
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
            XposedBridge.log(TAG + "art restored from disk " + describe(b));
        }
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
                XposedBridge.log(TAG + "recv op=" + op
                        + (carried == null ? " " + i.getExtras() : " jpg=" + carried.length + "B"));
                try {
                    if ("cls".equals(op)) {
                        dumpClass(i.getStringExtra("name"), i.getStringExtra("grep"));
                    } else if ("bmp".equals(op)) {
                        traceBitmaps(i.getStringExtra("name"));
                    } else if ("art".equals(op)) {
                        if (i.getBooleanExtra("off", false)) {
                            sArt = null;
                            new File(c.getFilesDir(), ART_FILE).delete();
                            XposedBridge.log(TAG + "art cleared");
                        } else {
                            byte[] jpg = i.getByteArrayExtra("jpg");
                            String file = i.getStringExtra("file");
                            Bitmap b = null;
                            if (jpg != null) b = decodeToTextureSize(jpg);
                            else if (file != null) b = BitmapFactory.decodeFile(file);
                            if (b == null) {
                                XposedBridge.log(TAG + "art decode failed (jpg="
                                        + (jpg == null ? "null" : jpg.length + "B")
                                        + " file=" + file + ")");
                            } else {
                                sArt = b;
                                sFitted = null;
                                sFittedOf = null;
                                fittedArt();   // scale here, not on the GL thread
                                XposedBridge.log(TAG + "art set " + describe(b));
                                // Show it first, write it to disk afterwards: the file only
                                // matters for the next cold start of this process, and a 100KB
                                // write in front of the upload is pure added latency.
                                if (i.getBooleanExtra("reload", false)) reloadTexture();
                                if (jpg != null) saveArtLater(c, jpg);
                                return;
                            }
                        }
                        if (i.getBooleanExtra("reload", false)) reloadTexture();
                    } else if ("reload".equals(op)) {
                        reloadTexture();
                    } else if ("state".equals(op)) {
                        XposedBridge.log(TAG + "art=" + describe(sArt)
                                + " engine=" + sKeyguardEngine);
                    } else {
                        XposedBridge.log(TAG + "ops: cls --es name <fqcn> [--es grep x]"
                                + " | bmp --es name <fqcn>");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        XposedBridge.log(TAG + "receiver registered for " + ACTION);
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
            XposedBridge.log(TAG + "reload: no keyguard engine (is the lockscreen wallpaper "
                    + "still the same image as the desktop one?)");
            return;
        }
        try {
            try {
                XposedHelpers.callMethod(eng, "u");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "reload: u() failed: " + t);
            }
            XposedHelpers.setBooleanField(eng, "b", true);
            XposedHelpers.callMethod(eng, "T", false);
            XposedBridge.log(TAG + "reload requested on " + eng.getClass().getSimpleName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "reload failed: " + Log.getStackTraceString(t));
        }
    }

    private static void dumpClass(String name, String grep) {
        if (name == null) { XposedBridge.log(TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = XposedHelpers.findClass(name, sCl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + name + " NOT FOUND");
            return;
        }
        XposedBridge.log(TAG + "=== " + c.getName());
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
                    XposedBridge.log(TAG + "  " + sb);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    XposedBridge.log(TAG + "  " + line);
                }
            }
            XposedBridge.log(TAG + "  --- ^ " + k.getName());
        }
    }

    /**
     * Hooks every method of a class that carries a Bitmap in or out and logs it, which is how
     * we find where the wallpaper texture actually enters the renderer.
     */
    private static void traceBitmaps(String name) {
        if (name == null) { XposedBridge.log(TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = XposedHelpers.findClass(name, sCl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + name + " NOT FOUND");
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
                XposedBridge.hookMethod(ct, argLogger("<init>"));
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
                    XposedBridge.hookMethod(m, argLogger(m.getName()));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        XposedBridge.log(TAG + "traced " + n + " bitmap-carrying methods on " + c.getName());
    }

    private static XC_MethodHook argLogger(final String name) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                StringBuilder sb = new StringBuilder(TAG)
                        .append(param.thisObject == null ? "?"
                                : param.thisObject.getClass().getSimpleName())
                        .append('.').append(name).append('(');
                for (int j = 0; j < param.args.length; j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(describe(param.args[j]));
                }
                sb.append(") -> ").append(describe(param.getResult()));
                XposedBridge.log(sb.toString());
            }
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
