package com.os4.musiccover;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.List;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Probe module: verifies that the HyperOS keyguard clock squeeze can be driven on
 * demand, independently of real notifications.
 *
 * Chain being verified (OS4.0.0.35, SDK 37):
 *   KeyguardClockContainer.notifStateChange(float y, boolean withAnim, NotificationTopChangeType)
 *     -> AnimationHelper.mClockAnima.notifStateChange(...)
 *     -> AllInOneClockAnimation: KeyguardClockNotifInteractor.setNotifY(y) -> ClockResult
 *                                updateClockComponentStyle(result, withAnim, type)
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "[MCProbe] ";
    private static final String ACTION = "com.os4.musiccover.PROBE";

    private static final String CLS_CONTAINER =
            "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String CLS_INTERACTOR =
            "com.android.keyguard.interactor.KeyguardClockNotifInteractor";
    private static final String CLS_TIME_VIEW = "com.miui.clock.allInOne.TimeView";
    private static final String CLS_TOP_CHANGE_TYPE =
            "com.miui.systemui.notification.data.repository.NotificationTopChangeType";
    private static final String CLS_MEDIA_CARD = "com.android.systemui.statusbar.notification"
            + ".mediacontrol.MiuiMediaNotificationControllerImpl";

    private static volatile View sContainer;
    private static volatile Class<?> sContainerCls;
    private static volatile Class<?> sTypeCls;
    private static boolean sReceiverRegistered;

    /**
     * While non-null, every notifStateChange() call - ours and the system's own
     * re-assertions from KeyguardClockNotifInteractor$collectNotificationYSource -
     * is coerced to this Y. This is how the cover mode takes ownership of the clock.
     */
    private static volatile Float sHoldY;
    /** Last Y the system asked for, so we can hand control back on release. */
    private static volatile float sLastSystemY = Float.NaN;
    /** Last Y we actually applied, i.e. where a ramp starts from. */
    private static volatile float sCurrentY = Float.NaN;
    private static ValueAnimator sRamp;
    private static Choreographer.FrameCallback sFrameCb;
    private static float sSpringX, sSpringV;
    /** Set while our own frame is inside setNotifY, so we don't read it back as the system's. */
    private static volatile boolean sSelfDriving;
    /** Per-frame setNotifY logging floods logcat at 8ms intervals; off unless asked for. */
    private static volatile boolean sVerbose;
    /**
     * Uniform scale applied to the glyph size the OEM computes, at the last mile. The OEM's
     * own animation keeps driving every frame; we only shrink what it asks for. NaN = off.
     */
    private static volatile float sSizeScale = Float.NaN;
    /**
     * Cover-mode collapse. The OEM already animates notifY frame by frame; deriving the extra
     * group scale from that same y means the OEM's spring drives the collapse too, and we
     * never write an animator for it. NaN = off.
     */
    private static volatile float sCollapseMin = Float.NaN;
    /** Below this notifY the OEM's variable font stops shrinking - the collapse takes over. */
    private static final float SQUEEZE_FLOOR = 740f;
    private static volatile float sAppliedK = Float.NaN;
    /**
     * Cover-mode clock tint as {r, g, b, a, mix} for the MiGlass shader. null = leave the
     * OEM's own colour alone. The OEM rewrites glassData whenever it re-renders, so this is
     * re-asserted per draw rather than set once - same ownership model as the notifY hold.
     */
    private static volatile float[] sTint;
    /**
     * Liquid-glass -> filled morph across the collapse. AllInOneBase.updateGlassValue(float)
     * writes glassData[36] and fades the glyph interior from fully transparent (the liquid
     * glass look, which degenerates into hairlines once the clock is small) to solid. NaN = off.
     */
    private static volatile float sGlassV0 = Float.NaN, sGlassV1 = Float.NaN;
    private static volatile float sAppliedGlassV = Float.NaN;

    /** The full-bleed album cover we add behind the clock. */
    private static volatile ImageView sCover;
    /** Survives keyguard rebuilds: re-attach whenever a fresh clock container shows up. */
    private static volatile boolean sCoverWanted;
    /**
     * While true, the wallpaper's subject cut-out stays hidden. HyperOS composites it in
     * SystemUI (KeyguardDepthInteractor -> deducted_image_view) on top of the hour clock, so
     * swapping the wallpaper texture alone leaves the OLD wallpaper's subject floating over the
     * album cover - the leftover the cover swap does not otherwise explain.
     */
    private static volatile boolean sDepthHidden;
    /** SystemUI restarts on its own (observed, with no crash recorded), which used to drop the
     *  hidden flag and bring the old wallpaper's subject back over the album cover. Cover mode
     *  is a property of the phone, not of this process, so it has to outlive the process. */
    private static Context sAppCtx;
    private static final String STATE_FILE = "mc_cover_state";

    /** The album cover is the wallpaper: depth cut-out hidden and the clock collapsed. */
    private static volatile boolean sCoverMode;
    /** 0.335 is what measured equal to OPPO's collapsed clock; the UI can move it. */
    private static final float DEFAULT_CLOCK_SCALE = 0.335f;
    private static final float DEFAULT_GLASS_END = 0.75f;
    private static volatile float sClockScale = DEFAULT_CLOCK_SCALE;
    private static volatile float sGlassEnd = DEFAULT_GLASS_END;
    /** The session we are mirroring, plus the callback that keeps the cover on the right track. */
    private static MediaController sWatched;
    private static MediaController.Callback sMediaCb;

    /**
     * Where the sharp cover sits vertically inside the wallpaper, 0 = flush with the top,
     * 0.5 = centred. Centred puts the most interesting part of the artwork exactly behind the
     * media card, which is why Apple biases it upwards.
     */
    private static final float DEFAULT_BIAS = 0.34f;
    private static volatile float sBias = DEFAULT_BIAS;

    /**
     * Cover mode follows the media card. Not a setting: with it off the module does nothing at
     * all, which is not a state worth offering - uninstalling is the way to turn the module off.
     * The adb "auto" op can still flip it for a debugging session; it comes back on at startup.
     */
    private static volatile boolean sAuto = true;
    /**
     * Whether the OEM media card is on the lockscreen. This, not playback state, is the switch:
     * pausing leaves the card up, so it leaves the cover up too, and dismissing the card is the
     * one thing that ends music mode.
     */
    private static volatile boolean sCardShowing;
    /** False until the card hook has fired once, so a restart does not act on an unknown state. */
    private static volatile boolean sCardKnown;
    /** The session the card itself is bound to - exact, where pickController() only guesses. */
    private static volatile android.media.session.MediaSession.Token sCardToken;
    /** Track identity straight off the card's MediaData. */
    private static volatile String sCardKey = "";
    /**
     * A track change arrives as a remove followed by an add, so the card is briefly gone. Wait
     * this long before believing it: long enough to absorb that pair, short enough that
     * dismissing the card by hand still feels immediate.
     */
    private static final long CARD_GONE_MS = 600L;
    /** What the pushed wallpaper currently shows, so a metadata storm pushes it only once. */
    private static volatile String sTrackKey = "";
    private static MediaSessionManager sMsm;
    private static MediaSessionManager.OnActiveSessionsChangedListener sSessionsCb;

    /**
     * Composing the wallpaper is a full-screen bitmap plus a multi-pass blur. That was fine as a
     * one-off adb command; on every track change it has to be off the main thread or it drops
     * frames in SystemUI.
     */
    private static Handler sWork;
    private static Handler sMain;

    /** The screen the wallpaper is composed for. Read off the keyguard, not assumed. */
    private static volatile int sScreenW = 1200, sScreenH = 2608;

    // Indices into TimeView.glassData, read off the class's own constants.
    private static final int GLASS_COLOR_R = 11, GLASS_COLOR_G = 12, GLASS_COLOR_B = 13;
    private static final int GLASS_COLOR_A = 14, GLASS_COLOR_MIX = 16;

    /**
     * The OEM's own miuix curves, read off AllInOneClockAnimation at runtime as
     * {dampingRatio, response}. miuix derives stiffness = (2*PI/response)^2 and
     * damping = 2*zeta*(2*PI/response) - confirmed against the dumped parameters[].
     */
    private static final float[] EASE_STATE_CHANGED = {0.88f, 0.38f}; // k=273.4  c=29.1
    private static final float[] EASE_DEFAULT       = {0.82f, 0.42f}; // k=223.8  c=24.5
    private static final float[] EASE_RUNNING       = {1.00f, 0.18f}; // k=1218.5 c=69.8

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if ("com.miui.miwallpaper".equals(lpp.packageName)) {
            WallpaperProbe.handle(lpp);
            return;
        }
        if (!"com.android.systemui".equals(lpp.packageName)) return;

        final ClassLoader cl = lpp.classLoader;
        XposedBridge.log(TAG + "loaded into SystemUI");

        try {
            sContainerCls = XposedHelpers.findClass(CLS_CONTAINER, cl);
            sTypeCls = XposedHelpers.findClass(CLS_TOP_CHANGE_TYPE, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "class lookup FAILED: " + t);
            return;
        }

        XposedBridge.hookAllMethods(sContainerCls, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sContainer = (View) param.thisObject;
                captureScreenSize(sContainer);
                XposedBridge.log(TAG + "clock container attached: " + sContainer);
                try {
                    registerReceiver(sContainer.getContext().getApplicationContext());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "registerReceiver failed: " + t);
                }
                // The keyguard is rebuilt on some transitions, taking our cover with it, so
                // re-attach rather than assume the view is still in the tree.
                if (sCoverWanted) {
                    sCover = null;
                    attachCover();
                }
                // Re-apply on keyguard rebuild. Measured: the system never re-shows the
                // cut-out on its own, so no per-call ownership hook is warranted - hooking
                // View.setVisibility process-wide cost every visibility change in SystemUI and
                // fired zero times. What actually lost the state was SystemUI restarting.
                if (sDepthHidden) setDepthHidden(true);
                // A rebuilt keyguard can be a different clock style, so the nudge measured
                // against the old one means nothing.
                sNudgeSample = Float.NaN;
                if (sCoverMode) reassertCoverClock();
            }
        });

        // The keyguard is rebuilt on every screen-off/on, so a captured instance goes
        // stale. Drop it on detach and always drive the currently attached one.
        XposedBridge.hookAllMethods(sContainerCls, "onDetachedFromWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sContainer == param.thisObject) {
                    sContainer = null;
                    XposedBridge.log(TAG + "clock container detached");
                }
                // Ownership must never outlive the keyguard that granted it. The keyguard
                // is torn down and rebuilt on every screen off/on, and a hold left standing
                // across that boundary rewrites the *new* clock's Y - which is what made the
                // clock size differ between awake/AOD and with/without a media card.
                abandonHold("keyguard torn down", false);
            }
        });

        // The system does re-show the cut-out - verified: it came back right after we restored
        // cover mode across a SystemUI restart. Re-hide after the OEM's own update instead of
        // hooking View.setVisibility process-wide, which would tax every view in SystemUI.
        try {
            Class<?> depth = XposedHelpers.findClass(
                    "com.android.keyguard.depth.KeyguardDepthInteractor", cl);
            XposedBridge.hookAllMethods(depth, "updateDeductedImageView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sDepthHidden) return;
                    try {
                        View d = (View) XposedHelpers.getObjectField(param.thisObject,
                                "deductedImageView");
                        if (d != null && d.getVisibility() == View.VISIBLE) {
                            d.setVisibility(View.INVISIBLE);
                            XposedBridge.log(TAG + "depth re-hidden after updateDeductedImageView");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "depth ownership hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "depth ownership hook failed: " + t);
        }

        // The media card is the switch. Every add, track change and dismissal of the
        // lockscreen card funnels through this one static - MediaData in means a card is on
        // screen (and which track), null means it has gone. Playback state never reaches it,
        // which is exactly what we want: pausing leaves the card up, so it leaves the cover up.
        try {
            Class<?> card = XposedHelpers.findClass(CLS_MEDIA_CARD, cl);
            XposedBridge.hookAllMethods(card, "access$setTopMediaData", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onCardChanged(param.args.length > 1 ? param.args[1] : null);
                }
            });
            XposedBridge.log(TAG + "media card hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "media card hook failed: " + t);
        }

        // Track the live container. Ownership is NOT enforced here: the system's own
        // re-assertions come in through KeyguardClockNotifInteractor.setNotifY directly
        // and never pass through this method, so coercing here only produced a race
        // between our value and the system's. The single chokepoint is setNotifY.
        XposedBridge.hookAllMethods(sContainerCls, "notifStateChange", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // Anyone calling this is by definition the live instance.
                sContainer = (View) param.thisObject;
            }
        });

        // The OEM squeezes the clock through these four setters on TimeView. Scaling
        // setSizeInternal's argument shrinks the whole clock without touching the animation:
        // the OEM still computes and drives every frame, we just rescale the last mile.
        try {
            Class<?> timeView = XposedHelpers.findClass(CLS_TIME_VIEW, cl);
            XC_MethodHook axisLog = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean scaled = false;
                    if ("setSizeInternal".equals(param.method.getName()) && !Float.isNaN(sSizeScale)) {
                        param.args[0] = ((Float) param.args[0]) * sSizeScale;
                        scaled = true;
                    }
                    if (sVerbose) {
                        XposedBridge.log(TAG + "TimeView." + param.method.getName()
                                + "(" + param.args[0] + ")" + (scaled ? " [scaled]" : "")
                                + " on " + viewIdOf((View) param.thisObject));
                    }
                }
            };
            for (String m : new String[]{"setSizeInternal", "setWidth", "setHeight", "setWeight"}) {
                XposedBridge.hookAllMethods(timeView, m, axisLog);
            }

            // Own the colour the same way we own notifY. Setting glassData once does not
            // survive - the OEM rewrites it on every re-render (verified: the clock reverted
            // to the glass look right after a spring animation) - so re-assert per draw.
            XposedBridge.hookAllMethods(timeView, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    float[] t = sTint;
                    if (t == null) return;
                    try {
                        float[] g = (float[]) XposedHelpers.getObjectField(param.thisObject, "glassData");
                        if (g == null || g.length <= GLASS_COLOR_MIX) return;
                        g[GLASS_COLOR_R] = t[0];
                        g[GLASS_COLOR_G] = t[1];
                        g[GLASS_COLOR_B] = t[2];
                        g[GLASS_COLOR_A] = t[3];
                        g[GLASS_COLOR_MIX] = t[4];
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "TimeView hook failed: " + t);
        }

        // The real chokepoint. Everything that squeezes the clock - the OEM's own
        // notification-Y flow, KeyguardClockContainer.notifStateChange, and our own
        // per-frame driving - lands here, so this is where ownership is enforced.
        try {
            Class<?> interactor = XposedHelpers.findClass(CLS_INTERACTOR, cl);
            XposedBridge.hookAllMethods(interactor, "setNotifY", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    float requested = (Float) param.args[0];
                    // Only the system's own emissions tell us where the clock really
                    // belongs; our own frames must not be mistaken for that.
                    if (!sSelfDriving) sLastSystemY = requested;
                    Float hold = sHoldY;
                    if (hold != null && requested != hold) param.args[0] = hold;
                    applyCollapse((Float) param.args[0]);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sVerbose) {
                        XposedBridge.log(TAG + "setNotifY(" + param.args[0] + ") -> " + param.getResult());
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "interactor hook failed: " + t);
        }
    }

    private static void saveState() {
        if (sAppCtx == null) return;
        try {
            java.io.FileOutputStream f =
                    new java.io.FileOutputStream(new java.io.File(sAppCtx.getFilesDir(), STATE_FILE));
            f.write(("cover=" + (sCoverMode ? 1 : 0)
                    + "\nbias=" + sBias
                    + "\nclock=" + sClockScale
                    + "\nglass=" + sGlassEnd + "\n").getBytes());
            f.close();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "saveState failed: " + t);
        }
    }

    private static void loadState() {
        if (sAppCtx == null) return;
        java.io.File f = new java.io.File(sAppCtx.getFilesDir(), STATE_FILE);
        if (!f.exists()) return;
        boolean cover = false;
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            String body = new String(buf, 0, Math.max(0, n)).trim();
            // The first version of this file was a bare "1"/"0"; keep reading those so an
            // upgrade does not silently drop a cover that is still on the phone.
            if (body.length() <= 1) {
                cover = "1".equals(body);
            } else {
                for (String line : body.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    if ("cover".equals(k)) cover = "1".equals(v);
                    // "auto" was a stored setting; following the card is unconditional now.
                    else if ("bias".equals(k)) sBias = Float.parseFloat(v);
                    else if ("clock".equals(k)) sClockScale = Float.parseFloat(v);
                    else if ("glass".equals(k)) sGlassEnd = Float.parseFloat(v);
                    // "offdelay" was the pause timer, before the card became the switch.
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "loadState failed: " + t);
            return;
        }
        XposedBridge.log(TAG + "state restored: cover=" + cover + " bias=" + sBias);
        if (cover) enterCoverMode(false);
        // Always follow the card, whatever the file said.
        setAuto(true);
    }

    private static synchronized void registerReceiver(Context ctx) {
        if (sReceiverRegistered) {
            return;
        }
        sReceiverRegistered = true;
        sAppCtx = ctx;

        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String op = i.getStringExtra("op");
                if (op == null) op = "drive";
                XposedBridge.log(TAG + "recv op=" + op + " extras=" + i.getExtras());
                try {
                    boolean anim = i.getBooleanExtra("anim", true);
                    String type = i.getStringExtra("type");
                    if (type == null) type = "STATE_CHANGED";

                    // No "ms" extra -> spring (what the OEM actually uses).
                    // With "ms" -> fixed-duration curve, for A/B comparison.
                    int ms = i.getIntExtra("ms", 0);
                    float[] e = easeByName(i.getStringExtra("ease"));
                    float zeta = i.getFloatExtra("zeta", e[0]);
                    float response = i.getFloatExtra("response", e[1]);

                    if ("info".equals(op)) {
                        dumpInfo();
                    } else if ("anim".equals(op)) {
                        dumpAnimConfigs();
                    } else if ("hold".equals(op)) {
                        float y = i.getFloatExtra("y", -1f);
                        XposedBridge.log(TAG + "hold -> y=" + y + " from " + currentY()
                                + (ms > 0 ? " over " + ms + "ms" : " spring zeta=" + zeta + " response=" + response));
                        if (!anim) { sHoldY = y; drive(y, false, type); }
                        else if (ms > 0) rampTo(y, ms, type);
                        else springTo(y, zeta, response, type, false);
                    } else if ("gscale".equals(op)) {
                        String gid = i.getStringExtra("id");
                        groupScale(gid == null ? "time_group" : gid, i.getFloatExtra("k", 1f),
                                i.getFloatExtra("px", -1f), i.getFloatExtra("py", -1f),
                                i.getFloatExtra("ty", 0f));
                    } else if ("cls".equals(op)) {
                        String name = i.getStringExtra("name");
                        if (name == null) dumpClockStyleInfo();
                        else dumpClass(name, i.getStringExtra("grep"));
                    } else if ("tint".equals(op)) {
                        if (i.getBooleanExtra("off", false)) {
                            sTint = null;
                            XposedBridge.log(TAG + "tint released");
                        } else {
                            int argb = i.getIntExtra("color", 0xFFFFFFFF);
                            sTint = new float[]{
                                    ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                                    (argb & 0xFF) / 255f, i.getFloatExtra("a", 1f),
                                    i.getFloatExtra("mix", 1f)};
                            XposedBridge.log(TAG + "tint rgb=" + sTint[0] + "," + sTint[1] + ","
                                    + sTint[2] + " a=" + sTint[3] + " mix=" + sTint[4]);
                        }
                        invalidateClocks();
                    } else if ("gdata".equals(op)) {
                        pokeGlassData(i.getIntExtra("idx", -1), i.getFloatExtra("v", 0f));
                    } else if ("depth".equals(op)) {
                        setDepthHidden(!i.getBooleanExtra("on", true));
                    } else if ("pushart".equals(op)) {
                        boolean on = i.getBooleanExtra("on", true);
                        if (i.hasExtra("bias")) sBias = clamp01(i.getFloatExtra("bias", sBias));
                        sTrackKey = on ? trackKey(pickController(c)) : "";
                        setCoverEnabled(on, anim, false);
                    } else if ("bias".equals(op)) {
                        setBias(i.getFloatExtra("v", DEFAULT_BIAS));
                    } else if ("clockscale".equals(op)) {
                        sClockScale = clamp01(i.getFloatExtra("v", DEFAULT_CLOCK_SCALE));
                        if (sClockScale < 0.05f) sClockScale = 0.05f;
                        saveState();
                        XposedBridge.log(TAG + "clock scale = " + sClockScale);
                        if (sCoverMode) { sCollapseMin = sClockScale; sAppliedK = Float.NaN;
                            reassertCoverClock(); }
                    } else if ("glassend".equals(op)) {
                        sGlassEnd = clamp01(i.getFloatExtra("v", DEFAULT_GLASS_END));
                        saveState();
                        XposedBridge.log(TAG + "glass end = " + sGlassEnd);
                        if (sCoverMode) { sGlassV1 = sGlassEnd; sAppliedGlassV = Float.NaN;
                            reassertCoverClock(); }
                    } else if ("auto".equals(op)) {
                        setAuto(i.getBooleanExtra("on", true));
                    } else if ("reload".equals(op)) {
                        requestWallpaperReload(c);
                    } else if ("lockwp".equals(op)) {
                        final Context cc = sAppCtx;
                        final boolean clear = i.getBooleanExtra("clear", false);
                        final boolean force = i.getBooleanExtra("force", false);
                        worker().post(new Runnable() {
                            @Override
                            public void run() {
                                if (clear) clearLockWallpaper(cc);
                                else ensureLockWallpaper(cc, force);
                            }
                        });
                    } else if ("cover".equals(op)) {
                        if (i.getBooleanExtra("on", true)) {
                            sCoverWanted = true;
                            attachCover();
                        } else {
                            sCoverWanted = false;
                            detachCover();
                        }
                    } else if ("glassmorph".equals(op)) {
                        if (i.getBooleanExtra("off", false)) {
                            sGlassV0 = sGlassV1 = Float.NaN;
                            sAppliedGlassV = Float.NaN;
                            callOnClockViews("updateGlassValue", "f", 0f, 0, false);
                            XposedBridge.log(TAG + "glass morph off");
                        } else {
                            sGlassV0 = i.getFloatExtra("v0", 0f);
                            sGlassV1 = i.getFloatExtra("v1", 1f);
                            XposedBridge.log(TAG + "glass morph " + sGlassV0 + " -> " + sGlassV1);
                        }
                    } else if ("collapse".equals(op)) {
                        float k = i.getFloatExtra("k", -1f);
                        sCollapseMin = k > 0 ? k : Float.NaN;
                        XposedBridge.log(TAG + "collapse min scale = " + sCollapseMin);
                        if (Float.isNaN(sCollapseMin)) groupScale("time_group", 1f, -1f, 0f, 0f);
                    } else if ("callclock".equals(op)) {
                        callOnClockViews(i.getStringExtra("m"), i.getStringExtra("kind"),
                                i.getFloatExtra("f", 0f), i.getIntExtra("n", 0),
                                i.getBooleanExtra("b", false));
                    } else if ("call".equals(op)) {
                        callOnTimeViews(i.getStringExtra("m"), i.getStringExtra("kind"),
                                i.getFloatExtra("f", 0f), i.getIntExtra("n", 0),
                                i.getBooleanExtra("b", false));
                    } else if ("glass".equals(op)) {
                        setGlass(i.getBooleanExtra("on", false));
                    } else if ("scale".equals(op)) {
                        float k = i.getFloatExtra("k", -1f);
                        sSizeScale = k > 0 ? k : Float.NaN;
                        XposedBridge.log(TAG + "size scale = " + sSizeScale);
                    } else if ("params".equals(op)) {
                        setClockParams(i.getFloatExtra("w", -1f), i.getFloatExtra("h", -1f),
                                i.getFloatExtra("weight", -1f), i.getIntExtra("size", -1),
                                i.getFloatExtra("sizei", -1f));
                    } else if ("api".equals(op)) {
                        dumpApi(i.getStringExtra("id"));
                    } else if ("views".equals(op)) {
                        dumpViewTree(i.getBooleanExtra("root", false));
                    } else if ("query".equals(op)) {
                        // Answered through the ordered broadcast's result extras: the app is a
                        // separate process and this is the only channel it already has. A reply
                        // arriving at all is also how the app knows the module is loaded.
                        android.os.Bundle out = new android.os.Bundle();
                        out.putBoolean("alive", true);
                        out.putBoolean("cover", sCoverMode);
                        out.putBoolean("auto", sAuto);
                        out.putFloat("bias", sBias);
                        out.putFloat("clock", sClockScale);
                        out.putFloat("glass", sGlassEnd);
                        out.putBoolean("card", sCardShowing);
                        // Checked live rather than reported from the cached flag: the user can
                        // change the wallpaper at any time and that is what breaks the feature.
                        out.putBoolean("lockwp", hasLockWallpaper(c));
                        out.putString("track", sCardKey);
                        MediaController mc = sWatched;
                        out.putString("player", mc == null ? "" : mc.getPackageName());
                        setResultExtras(out);
                    } else if ("bounds".equals(op)) {
                        dumpClockBounds();
                    } else if ("state".equals(op)) {
                        XposedBridge.log(TAG + "state: holdY=" + sHoldY + " currentY=" + sCurrentY
                                + " lastSystemY=" + sLastSystemY + " animating=" + (sFrameCb != null || sRamp != null)
                                + " container=" + (sContainer != null) + " verbose=" + sVerbose);
                        XposedBridge.log(TAG + "cover: on=" + sCoverMode + " auto=" + sAuto
                                + " bias=" + sBias + " screen=" + sScreenW + "x" + sScreenH
                                + " card=" + (sCardKnown ? (sCardShowing ? sCardKey : "gone") : "unknown")
                                + " following=" + (sWatched == null ? "none" : sWatched.getPackageName())
                                + " track=" + sTrackKey);
                    } else if ("verbose".equals(op)) {
                        sVerbose = i.getBooleanExtra("on", !sVerbose);
                        XposedBridge.log(TAG + "verbose=" + sVerbose);
                    } else if ("abandon".equals(op)) {
                        abandonHold("requested");
                    } else if ("release".equals(op)) {
                        float back = sLastSystemY;
                        if (Float.isNaN(back)) {
                            // Never fabricate a Y - a wrong one snaps the clock to a size the
                            // system never asked for. With nothing observed yet, just let go.
                            abandonHold("release with no observed system Y", true);
                            return;
                        }
                        XposedBridge.log(TAG + "release -> y=" + back
                                + (ms > 0 ? " over " + ms + "ms" : " spring zeta=" + zeta + " response=" + response));
                        if (!anim) { abandonHold("release"); drive(back, false, type); }
                        else if (ms > 0) rampTo(back, ms, type, true);
                        else springTo(back, zeta, response, type, true);
                    } else {
                        abandonHold("raw drive");
                        drive(i.getFloatExtra("y", -1f), anim, type);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        XposedBridge.log(TAG + "receiver registered for " + ACTION);
        loadState();

        // The keyguard container is NOT always torn down on screen off - verified on device,
        // onDetachedFromWindow never fired across a full off/on cycle - so detach alone is
        // not enough to guarantee we let go. Screen off and unlock both end any reason for
        // us to own the clock, and they are framework signals rather than OEM internals.
        BroadcastReceiver lifecycle = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    reassertCoverClock();
                    return;
                }
                // Cover mode deliberately survives the screen going off. Releasing and then
                // snapping back on wake is what made the AOD-to-lockscreen transition jump:
                // the clock rendered at its natural size for the first frames and was then
                // yanked to the collapsed one. Holding throughout means every emission the OEM
                // makes during that transition is already coerced, so there is nothing to jump.
                // (The screen-off release still applies otherwise - that is what stopped a
                // stale hold from leaking into the next keyguard.)
                if (Intent.ACTION_SCREEN_OFF.equals(a) && sCoverMode) {
                    stopMotion();
                    XposedBridge.log(TAG + "screen off, cover mode keeps the clock held");
                    return;
                }
                abandonHold(a, true);
            }
        };
        IntentFilter lf = new IntentFilter();
        lf.addAction(Intent.ACTION_SCREEN_OFF);
        lf.addAction(Intent.ACTION_SCREEN_ON);
        lf.addAction(Intent.ACTION_USER_PRESENT);
        ctx.registerReceiver(lifecycle, lf, Context.RECEIVER_NOT_EXPORTED);
        XposedBridge.log(TAG + "lifecycle receiver registered (screen off / user present)");
    }

    /** Where the clock is right now: our own last frame, else the system's last word. NaN if unknown. */
    private static float currentY() {
        if (!Float.isNaN(sCurrentY)) return sCurrentY;
        return sLastSystemY;
    }

    private static void stopMotion() {
        if (sRamp != null) { sRamp.cancel(); sRamp = null; }
        if (sFrameCb != null) { Choreographer.getInstance().removeFrameCallback(sFrameCb); sFrameCb = null; }
    }

    /**
     * Drops ownership unconditionally. Anything that can strand an in-flight animation -
     * the keyguard being rebuilt, the container going away, Choreographer frames stopping
     * because the display turned off - has to come through here, otherwise sHoldY stays
     * pinned at whatever half-way value the last frame wrote and silently rewrites the
     * clock forever after.
     */
    private static void abandonHold(String why) {
        abandonHold(why, false);
    }

    private static void abandonHold(String why, boolean restore) {
        boolean held = sHoldY != null || sFrameCb != null || sRamp != null
                || !Float.isNaN(sCollapseMin) || sTint != null;
        stopMotion();
        sHoldY = null;
        sCurrentY = Float.NaN;
        sSpringV = 0f;
        // Cover mode is one state: the squeeze, the collapse scale and the tint are taken and
        // given back together, so none of them can outlive the keyguard that granted them.
        if (!Float.isNaN(sCollapseMin)) {
            sCollapseMin = Float.NaN;
            sAppliedK = Float.NaN;
            groupScale("time_group", 1f, -1f, 0f, 0f);
            for (View root : clockRoots()) {
                View d = findClockView(root, "text_area");
                if (d != null) d.setTranslationY(0f);
            }
            sNudge = 0f;
            sNudgeSample = Float.NaN;
        }
        if (sTint != null) {
            sTint = null;
            invalidateClocks();
        }
        if (!Float.isNaN(sGlassV0)) {
            float back = sGlassV0;
            sGlassV0 = sGlassV1 = Float.NaN;
            sAppliedGlassV = Float.NaN;
            callOnClockViews("updateGlassValue", "f", back, 0, false);
        }
        if (!held) return;
        XposedBridge.log(TAG + "hold abandoned: " + why);
        // Dropping the coercion alone only stops us rewriting *future* calls; the clock
        // keeps whatever squeeze the last frame left behind until the system happens to
        // emit again, which it may never do if nothing below the clock changes. Snap it
        // back to the value the system last asked for.
        if (restore && sContainer != null && !Float.isNaN(sLastSystemY)) {
            drive(sLastSystemY, false, "STATE_CHANGED");
        }
    }

    /**
     * Integrates the same damped spring the OEM uses, and pushes Y every frame.
     * Carries velocity across retargets, so an interrupted animation stays continuous.
     */
    private static void springTo(final float target, final float zeta, final float response,
                                 final String typeName, final boolean releaseAtEnd) {
        final View v = sContainer;
        if (v == null) {
            XposedBridge.log(TAG + "no clock container captured yet");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                boolean retarget = sFrameCb != null;
                stopMotion();
                final float w0 = (float) (2 * Math.PI / response);
                final float k = w0 * w0;
                final float c = 2f * zeta * w0;
                if (!retarget) {
                    float from = currentY();
                    if (Float.isNaN(from)) {
                        XposedBridge.log(TAG + "no Y observed yet, snapping to " + target);
                        sHoldY = target;
                        applyY(target, typeName);
                        return;
                    }
                    sSpringX = from;
                    sSpringV = 0f;
                }
                final long t0 = android.os.SystemClock.uptimeMillis();
                final long[] last = {0L};
                sFrameCb = new Choreographer.FrameCallback() {
                    @Override
                    public void doFrame(long frameTimeNanos) {
                        if (last[0] == 0L) last[0] = frameTimeNanos;
                        float dt = (frameTimeNanos - last[0]) / 1e9f;
                        last[0] = frameTimeNanos;
                        if (dt <= 0f) dt = 1f / 120f;
                        if (dt > 0.05f) dt = 0.05f;
                        int steps = (int) Math.ceil(dt * 240f);
                        float h = dt / Math.max(1, steps);
                        for (int i = 0; i < Math.max(1, steps); i++) {
                            float a = -k * (sSpringX - target) - c * sSpringV;
                            sSpringV += a * h;
                            sSpringX += sSpringV * h;
                        }
                        if (sContainer == null) {
                            abandonHold("container vanished mid-spring");
                            return;
                        }
                        boolean done = Math.abs(sSpringX - target) < 0.5f && Math.abs(sSpringV) < 2f;
                        float y = done ? target : sSpringX;
                        sHoldY = y;
                        applyY(y, typeName);
                        if (!done) {
                            Choreographer.getInstance().postFrameCallback(this);
                            return;
                        }
                        sFrameCb = null;
                        sSpringV = 0f;
                        long ms = android.os.SystemClock.uptimeMillis() - t0;
                        if (releaseAtEnd) {
                            abandonHold("spring settled in " + ms + "ms, control handed back");
                        } else {
                            XposedBridge.log(TAG + "spring settled in " + ms + "ms, holding at " + target);
                        }
                    }
                };
                Choreographer.getInstance().postFrameCallback(sFrameCb);
            }
        });
    }

    private static float[] easeByName(String name) {
        if ("running".equals(name)) return EASE_RUNNING;
        if ("default".equals(name)) return EASE_DEFAULT;
        return EASE_STATE_CHANGED;
    }

    private static void rampTo(float target, long ms, String typeName) {
        rampTo(target, ms, typeName, false);
    }

    /**
     * The OEM animates the clock by animating Y itself and recomputing the glyph
     * metrics every frame (AllInOneClockAnimation$folmeClockListener.onUpdate ->
     * setNotifY). We do the same: ramp Y, snap the clock on each frame.
     */
    private static void rampTo(final float target, final long ms, final String typeName,
                               final boolean releaseAtEnd) {
        final View v = sContainer;
        if (v == null) {
            XposedBridge.log(TAG + "no clock container captured yet");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                stopMotion();
                final float from = currentY();
                if (Float.isNaN(from)) {
                    XposedBridge.log(TAG + "no Y observed yet, snapping to " + target);
                    sHoldY = target;
                    applyY(target, typeName);
                    return;
                }
                ValueAnimator a = ValueAnimator.ofFloat(from, target);
                a.setDuration(ms);
                // HyperOS-ish emphasised decelerate; swap for a spring later.
                a.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
                a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator an) {
                        float y = (Float) an.getAnimatedValue();
                        sHoldY = y;
                        applyY(y, typeName);
                    }
                });
                a.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator an) {
                        if (releaseAtEnd) {
                            abandonHold("ramp done, control handed back");
                        } else {
                            XposedBridge.log(TAG + "ramp done, holding at " + target);
                        }
                    }
                });
                sRamp = a;
                a.start();
            }
        });
    }

    /** Applies one frame. Must already be on the view's thread. */
    private static void applyY(float y, String typeName) {
        View v = sContainer;
        if (v == null) return;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object type = Enum.valueOf((Class<Enum>) sTypeCls, typeName);
            Method m = XposedHelpers.findMethodExact(sContainerCls,
                    "notifStateChange", float.class, boolean.class, sTypeCls);
            m.setAccessible(true);
            // NOTE the inversion: KeyguardClockContainer calls this arg "isDragOrFling",
            // AllInOneClockAnimation reads it as "withAnim" = !isDragOrFling.
            // true  -> drag/fling  -> snap  (what per-frame driving wants)
            // false -> state change -> Folme animates, font_weight included
            sSelfDriving = true;
            try {
                m.invoke(v, y, true, type);
            } finally {
                sSelfDriving = false;
            }
            sCurrentY = y;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "applyY failed: " + Log.getStackTraceString(t));
        }
    }

    private static void drive(final float y, final boolean anim, final String typeName) {
        final View v = sContainer;
        if (v == null) {
            XposedBridge.log(TAG + "no clock container captured yet");
            return;
        }
        v.post(new Runnable() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public void run() {
                try {
                    Object type = Enum.valueOf((Class<Enum>) sTypeCls, typeName);
                    Method m = XposedHelpers.findMethodExact(sContainerCls,
                            "notifStateChange", float.class, boolean.class, sTypeCls);
                    m.setAccessible(true);
                    sSelfDriving = true;
                    try {
                        m.invoke(v, y, anim, type);
                    } finally {
                        sSelfDriving = false;
                    }
                    sCurrentY = y;
                    XposedBridge.log(TAG + "notifStateChange(" + y + ", " + anim + ", " + typeName + ") OK");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "drive failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * The notifY channel clamps the variable font at 674/337/317. To find out whether the
     * clock can go smaller than that we need to see what the OEM actually applies the axes
     * to - textSize, fontVariationSettings, or a scale - so dump the live view tree.
     */
    /**
     * Talks to com.miui.clock.allInOne.TimeView directly, bypassing the ClockResult clamp
     * that pins the notifY path at 674/337/317. -1 leaves an axis alone.
     */
    private static void setClockParams(final float w, final float h, final float weight,
                                       final int size, final float sizeInternal) {
        final View v = sContainer;
        if (v == null) { XposedBridge.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier("hour_view", "id", "com.android.systemui");
                    View t = v.getRootView().findViewById(id);
                    if (t == null) { XposedBridge.log(TAG + "hour_view not found"); return; }
                    // setClockParams()'s argument order is not (width, height, weight) - feeding
                    // it the fields' own values rotated them - so drive the three axes through
                    // their individual setters instead, where the mapping is unambiguous.
                    if (size >= 0) XposedHelpers.callMethod(t, "setSize", size);
                    // setSize(int) only stores the value; textSizePx - the actual point size,
                    // and the knob that reaches well below the font axes' floor - comes from here.
                    if (sizeInternal >= 0) XposedHelpers.callMethod(t, "setSizeInternal", sizeInternal);
                    if (w >= 0) XposedHelpers.callMethod(t, "setWidth", w);
                    if (h >= 0) XposedHelpers.callMethod(t, "setHeight", h);
                    if (weight >= 0) XposedHelpers.callMethod(t, "setWeight", weight);
                    t.invalidate();
                    RectF b = (RectF) XposedHelpers.getObjectField(t, "mTextBounds");
                    XposedBridge.log(TAG + "axes width=" + XposedHelpers.getObjectField(t, "width")
                            + " height=" + XposedHelpers.getObjectField(t, "height")
                            + " weight=" + XposedHelpers.getObjectField(t, "weight")
                            + " size=" + XposedHelpers.getObjectField(t, "size")
                            + " textSizePx=" + XposedHelpers.getObjectField(t, "textSizePx")
                            + " -> glyphs " + Math.round(b.width()) + "x" + Math.round(b.height()));
                } catch (Throwable e) {
                    XposedBridge.log(TAG + "setClockParams failed: " + Log.getStackTraceString(e));
                }
            }
        });
    }

    /** Dumps the declared API of one view in the clock tree, to find the size knobs. */
    private static void dumpApi(String idName) {
        View v = sContainer;
        if (v == null) { XposedBridge.log(TAG + "no clock container"); return; }
        if (idName == null) idName = "hour_view";
        int id = v.getContext().getResources().getIdentifier(idName, "id", "com.android.systemui");
        View target = id == 0 ? null : v.getRootView().findViewById(id);
        // "clock" means the AllInOneHourClock/AllInOneMinuteClock view itself, which carries no id.
        if ("clock".equals(idName)) {
            View[] roots = clockRoots();
            target = roots.length == 0 ? null : ((android.view.ViewGroup) roots[0]).getChildAt(0);
        }
        if (target == null) { XposedBridge.log(TAG + idName + " not found"); return; }
        Class<?> c = target.getClass();
        XposedBridge.log(TAG + idName + " = " + c.getName());
        for (; c != null && c != View.class; c = c.getSuperclass()) {
            XposedBridge.log(TAG + "--- " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder("  ").append(m.getReturnType().getSimpleName())
                        .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int k = 0; k < ps.length; k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(ps[k].getSimpleName());
                }
                XposedBridge.log(TAG + sb.append(')'));
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val;
                try { val = f.get(target); } catch (Throwable t) { val = "?"; }
                if (val != null && val.getClass().isArray()) {
                    StringBuilder arr = new StringBuilder("[");
                    int len = java.lang.reflect.Array.getLength(val);
                    for (int j = 0; j < len; j++) {
                        if (j > 0) arr.append(", ");
                        arr.append(j).append(':').append(java.lang.reflect.Array.get(val, j));
                    }
                    val = arr.append(']').toString();
                }
                XposedBridge.log(TAG + "  ." + f.getName() + " = " + val);
            }
        }
    }

    private static void dumpViewTree(boolean fromRoot) {
        View v = sContainer;
        if (v == null) {
            XposedBridge.log(TAG + "no clock container");
            return;
        }
        dumpViewTree(fromRoot ? v.getRootView() : v, 0);
    }

    /**
     * Scales the digits as a group. TimeView.onDraw strokes a Path, so a parent scale is a
     * canvas matrix concat - the path rasterises at the final size, no quality loss - and
     * position scales with it, unlike setSizeInternal which shrinks the glyphs but leaves
     * them at draw origins computed for the big clock.
     */
    /**
     * The lockscreen editor writes its colour choice into clockInfo.clockEffect (see the
     * constant_template_editor_info setting). Dump ClockStyleInfo's constants so we can name
     * the modes instead of guessing at integers.
     */
    private static void dumpClockStyleInfo() {
        try {
            Class<?> tv = XposedHelpers.findClass(CLS_TIME_VIEW, sContainerCls.getClassLoader());
            Class<?> info = tv.getDeclaredMethod("getClockStyleInfo").getReturnType();
            XposedBridge.log(TAG + "ClockStyleInfo = " + info.getName());
            for (Class<?> c = info; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    try {
                        XposedBridge.log(TAG + "  " + c.getSimpleName() + "." + f.getName()
                                + " = " + f.get(null));
                    } catch (Throwable ignored) {
                    }
                }
                StringBuilder ms = new StringBuilder();
                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if (n.toLowerCase().contains("effect") || n.toLowerCase().contains("blend")
                            || n.toLowerCase().contains("color")) ms.append(n).append(' ');
                }
                if (ms.length() > 0) XposedBridge.log(TAG + "  " + c.getSimpleName() + " methods: " + ms);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dumpClockStyleInfo failed: " + Log.getStackTraceString(t));
        }
    }

    /** Dumps a class by name so we can find OEM entry points without pulling the dex apart. */
    private static void dumpClass(String name, String grep) {
        ClassLoader cl = sContainerCls == null ? null : sContainerCls.getClassLoader();
        Class<?> c;
        try {
            c = XposedHelpers.findClass(name, cl);
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
                String line = sb.toString();
                if (g == null || line.toLowerCase().contains(g)) {
                    XposedBridge.log(TAG + "  " + line);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    XposedBridge.log(TAG + "  " + line);
                }
            }
        }
    }

    /**
     * Where the clock actually sits on screen. Every lock screen clock style lays its glyphs out
     * somewhere different, so anything that positions the collapsed clock has to come from these
     * numbers rather than from a constant measured against one style.
     */
    private static void dumpClockBounds() {
        for (View root : clockRoots()) {
            android.content.res.Resources r = root.getResources();
            for (String id : new String[]{"clock_animation_container", "time_group", "text_area",
                    "hour_view", "minute_view"}) {
                int i = r.getIdentifier(id, "id", "com.android.systemui");
                View v = i == 0 ? null : root.findViewById(i);
                if (v == null) continue;
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                StringBuilder sb = new StringBuilder(viewIdOf(root)).append(' ').append(id)
                        .append(" screen=").append(loc[0]).append(',').append(loc[1])
                        .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                        .append(" ty=").append(v.getTranslationY())
                        .append(" sy=").append(v.getScaleY())
                        .append(" pivotY=").append(v.getPivotY())
                        .append(" vis=").append(v.getVisibility());
                try {
                    sb.append(" textBounds=")
                            .append(XposedHelpers.callMethod(v, "getTextBoundsWithPosition"))
                            .append(" drawY=").append(XposedHelpers.callMethod(v, "getDrawTextY"))
                            .append(" textTop=").append(XposedHelpers.callMethod(v, "getTextTop"));
                } catch (Throwable ignored) {
                }
                XposedBridge.log(TAG + sb);
            }
        }
    }

    private static void invalidateClocks() {
        View v = sContainer;
        if (v == null) return;
        for (View root : clockRoots()) root.invalidate();
    }

    /** Reads, and optionally pokes, the MiGlass shader parameter array on both clock trees. */
    private static void pokeGlassData(final int idx, final float value) {
        final View v = sContainer;
        if (v == null) { XposedBridge.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null || t.getVisibility() != View.VISIBLE) continue;
                            float[] g = (float[]) XposedHelpers.getObjectField(t, "glassData");
                            if (g == null) continue;
                            if (idx >= 0 && idx < g.length) g[idx] = value;
                            t.invalidate();
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < g.length; j++) {
                                if (j > 0) sb.append(", ");
                                sb.append(j).append(':').append(g[j]);
                            }
                            XposedBridge.log(TAG + viewIdOf(root) + "/" + id + " glassData=[" + sb + "]");
                        } catch (Throwable e) {
                            XposedBridge.log(TAG + "pokeGlassData " + id + ": " + e);
                        }
                    }
                }
            }
        });
    }

    /**
     * Maps the notifY the clock is being drawn at onto a group scale, so the collapse rides
     * the OEM's own per-frame driver instead of a second animator of ours. Called from the
     * setNotifY hook, i.e. once per frame, by whoever is driving.
     */
    private static void applyCollapse(float y) {
        float min = sCollapseMin;
        if (Float.isNaN(min) && Float.isNaN(sGlassV0)) return;
        float natural = sLastSystemY;
        if (Float.isNaN(natural) || natural <= SQUEEZE_FLOOR) return;
        float p = (natural - y) / (natural - SQUEEZE_FLOOR);
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        if (Float.isNaN(min)) { applyGlassMorph(p); return; }
        applyGlassMorph(p);
        float k = 1f - p * (1f - min);
        sAppliedK = k;
        placeCollapsedClock(k, y, p);
    }

    /**
     * The top of the drawn digits, in time_group's coordinates, pooled across both clock trees.
     *
     * Every lock screen clock style lays its glyphs out somewhere else - the single-line style
     * puts hour and minute side by side, the stacked one draws the hour at 650 and the minute at
     * 909 - so this has to be measured, not assumed. Both trees share the pooled value so the two
     * lines of a stacked clock keep their spacing when they shrink.
     */
    private static float glyphTop() {
        float top = Float.MAX_VALUE;
        for (View root : clockRoots()) {
            for (String id : new String[]{"hour_view", "minute_view"}) {
                View v = findClockView(root, id);
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                try {
                    RectF r = (RectF) XposedHelpers.callMethod(v, "getTextBoundsWithPosition");
                    if (r == null || r.height() <= 0f) continue;
                    top = Math.min(top, v.getTop() + r.top);
                } catch (Throwable ignored) {
                }
            }
        }
        return top == Float.MAX_VALUE ? Float.NaN : top;
    }

    private static View findClockView(View root, String id) {
        int i = root.getResources().getIdentifier(id, "id", "com.android.systemui");
        return i == 0 ? null : root.findViewById(i);
    }

    /** The date line. It lives in the foreground tree; the background tree's copy is GONE. */
    private static View visibleDate() {
        for (View root : clockRoots()) {
            View v = findClockView(root, "text_area");
            if (v != null && v.getVisibility() == View.VISIBLE && v.getHeight() > 0) return v;
        }
        return null;
    }

    /** Air between the date and the collapsed clock. */
    private static final float CLOCK_GAP_DP = 10f;
    /** Air between the status bar and the date, when the group has to be pushed clear of it. */
    private static final float STATUS_BAR_GAP_DP = 8f;

    /** The nudge in force. Held across frames on purpose - see updateStatusBarNudge(). */
    private static volatile float sNudge;
    /** Previous raw reading, to tell a settled one from a stale one. */
    private static float sNudgeSample = Float.NaN;

    /**
     * How far the whole group has to come back down to clear the status bar, in px.
     *
     * The OEM translates the clock group up as it squeezes, and how far depends on the style: at
     * the squeeze floor the stacked clock puts the date at y=67, right under the status bar
     * icons, while the single-line style lands clear on its own and must not move.
     *
     * The subtlety is WHEN this can be measured. We run from the setNotifY hook, and the OEM
     * applies the frame's translation only after setNotifY returns - so a reading taken here is
     * always one frame behind, and on the first frames after waking from AOD it still describes
     * the AOD layout. Acting on that reading is exactly what threw the clock to the top of the
     * screen before it slid back down.
     *
     * So the nudge is not recomputed from whatever the last frame happened to look like. It is
     * only adopted once two consecutive readings agree, which means the OEM has stopped moving
     * and the geometry being measured is the settled one. Until then the value already in force
     * keeps being used, which is the right answer anyway: it was measured on the same clock in
     * the same state before the screen went off.
     */
    private static void updateStatusBarNudge(View date, float p) {
        // Only the fully collapsed state is worth measuring; anything else is mid-animation.
        if (p < 0.995f) return;
        int[] loc = new int[2];
        date.getLocationOnScreen(loc);
        float uncorrected = loc[1] - date.getTranslationY();
        android.content.res.Resources r = date.getResources();
        float statusBar = 0f;
        int id = r.getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) statusBar = r.getDimensionPixelSize(id);
        float measured = Math.max(0f,
                statusBar + STATUS_BAR_GAP_DP * r.getDisplayMetrics().density - uncorrected);
        if (!Float.isNaN(sNudgeSample) && Math.abs(measured - sNudgeSample) < 1f) {
            sNudge = measured;
        }
        sNudgeSample = measured;
    }

    /**
     * Puts the collapsed clock directly under the date.
     *
     * Anchoring to the date is the whole trick. Every earlier attempt anchored to something
     * absolute - a constant pivot, then the glyphs' own top - and every clock style broke it a
     * different way: one sat too low, another ended up above the date. The date and the clock are
     * siblings inside clock_animation_container, so the OEM's squeeze translation moves both and
     * cancels out of this calculation; what is left is a layout relationship that holds for any
     * style.
     *
     * It is also why waking from AOD no longer flashes. The position is computed from the layout
     * in a single pass, so the first frame is already right - the previous version measured the
     * result on screen and corrected it over the following frames, which is exactly what the jump
     * to the top and the slide back down was.
     */
    private static void placeCollapsedClock(float k, float y, float p) {
        View date = visibleDate();
        float glyph = glyphTop();
        // Nothing measurable yet - mid-teardown, or before the first layout. Leaving what is
        // already applied alone is right: resetting would itself be a visible jump.
        if (date == null || Float.isNaN(glyph)) return;

        // Both containers are laid out identically and sit at the same screen position, so the
        // date's offset inside its own parent is usable against either tree's time_group.
        updateStatusBarNudge(date, p);
        float nudge = sNudge;
        date.setTranslationY(nudge);
        float dateBottom = date.getTop() + date.getHeight() + nudge;
        for (View root : clockRoots()) {
            View g = findClockView(root, "time_group");
            if (g == null) continue;
            float gap = CLOCK_GAP_DP * g.getResources().getDisplayMetrics().density;
            // Not g.getWidth()/2: this runs from the setNotifY hook, which can fire before the
            // group is laid out, and a width of 0 puts the pivot on the left edge - the clock
            // then collapses into the corner instead of staying centred under the date.
            g.setPivotX(g.getResources().getDisplayMetrics().widthPixels / 2f);
            // Pivoting on the glyph top means the scaled block still starts at `glyph`, so the
            // translation needed to land it under the date does not depend on k.
            g.setPivotY(glyph);
            g.setScaleX(k);
            g.setScaleY(k);
            g.setTranslationY(dateBottom + gap - (g.getTop() + glyph));
        }
        if (sVerbose) {
            XposedBridge.log(TAG + "collapse y=" + y + " p=" + p + " k=" + k
                    + " glyphTop=" + glyph + " dateBottom=" + dateBottom + " nudge=" + nudge);
        }
    }

    /**
     * Pulls the album art off the active media session. SystemUI holds MEDIA_CONTENT_CONTROL so
     * getActiveSessions works here, and the session bitmap is full resolution - unlike the
     * media card thumbnail we fall back to.
     */
    /**
     * Several apps can hold an active session at once - here Apple Music and Salt Player both
     * did - and getActiveSessions order is not the order SystemUI's media card uses, so taking
     * the first one with art showed a cover for a track that was not the one on screen. Rank
     * the way the card does: whatever is actually playing, then whoever reported progress last.
     */
    private static MediaController pickController(Context ctx) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> cs = msm.getActiveSessions(null);
            MediaController best = null;
            long bestScore = Long.MIN_VALUE;
            for (MediaController c : cs) {
                PlaybackState ps = c.getPlaybackState();
                if (ps == null) continue;
                boolean playing = ps.getState() == PlaybackState.STATE_PLAYING;
                long score = ps.getLastPositionUpdateTime();
                if (playing) score += (1L << 50);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best != null) {
                XposedBridge.log(TAG + "media session -> " + best.getPackageName()
                        + " (of " + cs.size() + ")");
            }
            return best;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getActiveSessions failed: " + t);
            return null;
        }
    }

    private static Bitmap albumArt(Context ctx) {
        return albumArt(ctx, true);
    }

    /**
     * allowCard gates the media card thumbnail. It is the only source when the player publishes
     * nothing but an artwork URI, but it lags a track change by a moment - long enough to hand
     * back the PREVIOUS album - so callers that can afford to wait ask for the session only.
     */
    private static Bitmap albumArt(Context ctx, boolean allowCard) {
        MediaController c = pickController(ctx);
        if (c != null) {
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (b == null && md.getDescription() != null) {
                    b = md.getDescription().getIconBitmap();
                }
                if (b != null) {
                    XposedBridge.log(TAG + "album art from " + c.getPackageName()
                            + " " + b.getWidth() + "x" + b.getHeight() + " \""
                            + md.getString(MediaMetadata.METADATA_KEY_TITLE) + "\"");
                    return b;
                }
                XposedBridge.log(TAG + c.getPackageName() + " carries no art bitmap (uri="
                        + md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) + ")");
            }
        }
        if (!allowCard) return null;
        return cardThumbnail();
    }

    /**
     * The media card's own thumbnail, read on the main thread - the composing now runs on a
     * worker, and the view tree belongs to the UI thread.
     */
    private static Bitmap cardThumbnail() {
        final View v = sContainer;
        if (v == null) return null;
        final Bitmap[] out = new Bitmap[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier("album_art_image", "id", "com.android.systemui");
                    View art = id == 0 ? null : v.getRootView().findViewById(id);
                    if (art instanceof ImageView) {
                        Drawable d = ((ImageView) art).getDrawable();
                        if (d instanceof BitmapDrawable) out[0] = ((BitmapDrawable) d).getBitmap();
                    }
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main().post(r);
            try {
                done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        if (out[0] != null) {
            XposedBridge.log(TAG + "album art from media card thumbnail "
                    + out[0].getWidth() + "x" + out[0].getHeight());
        }
        return out[0];
    }

    /**
     * Hands the album art to the wallpaper process. It cannot read the media session itself, and
     * a full-screen bitmap is far past the binder limit, so send it as a JPEG the size of the
     * screen - already composed here, where we know the screen metrics.
     *
     * Composing costs a full-screen bitmap and a multi-pass blur, so callers go through
     * pushArtAsync() rather than calling this on the main thread.
     */
    private static void pushArtToWallpaper(Context ctx, boolean on) {
        pushArtToWallpaper(ctx, on, on ? albumArt(ctx) : null);
    }

    private static void pushArtToWallpaper(Context ctx, boolean on, Bitmap art) {
        long t0 = android.os.SystemClock.uptimeMillis();
        Intent out = wallpaperIntent("art");
        if (!on) {
            out.putExtra("off", true);
            ctx.sendBroadcast(out);
            sTrackKey = "";
            XposedBridge.log(TAG + "pushart off");
            return;
        }
        if (art == null) { XposedBridge.log(TAG + "pushart: no album art"); return; }
        int w = sScreenW, h = sScreenH;
        Bitmap full = composeWallpaper(art, w, h, sBias);
        long tc = android.os.SystemClock.uptimeMillis();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int q = 85;
        byte[] jpg;
        do {
            bos.reset();
            full.compress(Bitmap.CompressFormat.JPEG, q, bos);
            jpg = bos.toByteArray();
            q -= 15;
        } while (jpg.length > 700 * 1024 && q > 25);
        full.recycle();
        out.putExtra("jpg", jpg);
        ctx.sendBroadcast(out);
        XposedBridge.log(TAG + "pushart " + w + "x" + h + " bias=" + sBias
                + " as " + jpg.length + "B jpeg, draw " + (tc - t0) + "ms encode "
                + (android.os.SystemClock.uptimeMillis() - tc) + "ms");
    }

    /**
     * FLAG_RECEIVER_FOREGROUND is the whole reason a track change feels immediate: without it
     * this broadcast sits in the background queue and took a measured ~500ms to reach the
     * wallpaper process, which is more than composing and uploading the picture put together.
     */
    private static Intent wallpaperIntent(String op) {
        Intent out = new Intent("com.os4.musiccover.WPROBE");
        out.setPackage("com.miui.miwallpaper");
        out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        out.putExtra("op", op);
        out.putExtra("reload", true);
        return out;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * The right bias can only be found by looking at the phone - the sharp band has to clear the
     * media card without stranding the artwork under the clock - so it is a live knob, and a
     * change re-composes what is already on screen instead of waiting for the next track.
     */
    private static void setBias(float v) {
        sBias = clamp01(v);
        saveState();
        XposedBridge.log(TAG + "cover bias = " + sBias);
        if (sCoverMode) pushArtAsync(true, false);
    }

    /** Asks the wallpaper process to re-upload the art it already has on disk. */
    private static void requestWallpaperReload(Context ctx) {
        ctx.sendBroadcast(wallpaperIntent("reload"));
        XposedBridge.log(TAG + "wallpaper reload requested");
    }

    /**
     * The wallpaper is composed on a worker thread, where reading the live view is not safe, so
     * the size is taken once from the keyguard's own resources. DisplayMetrics rather than
     * getWidth(): this runs at attach time, before the container has been laid out.
     */
    private static void captureScreenSize(View v) {
        try {
            android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                sScreenW = dm.widthPixels;
                sScreenH = dm.heightPixels;
            }
        } catch (Throwable ignored) {
        }
    }

    /** The one worker the composing runs on, so two track changes cannot compose at once. */
    private static synchronized Handler worker() {
        if (sWork == null) {
            // Default priority put the composing on a little core and made it swing between
            // 48ms and 196ms for the same picture. This work is on the critical path of a track
            // change, so ask for display priority rather than background.
            HandlerThread t = new HandlerThread("mc-art", android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            sWork = new Handler(t.getLooper());
        }
        return sWork;
    }

    private static Handler main() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        return sMain;
    }

    /**
     * Composes and pushes off the main thread.
     *
     * Getting the RIGHT art is the fiddly part of a track change, not getting art at all.
     * Players fill the session bitmap in asynchronously, so at the instant the metadata arrives
     * there is often nothing there - and the media card thumbnail we fall back to is still the
     * PREVIOUS track (verified on device: the wallpaper came up as the last album while the card
     * already read the new one). So attempts are spaced out, the session is preferred over the
     * card until the last one, and artwork identical to what is already on the wallpaper is read
     * as "not updated yet" rather than accepted.
     */
    private static final int ART_TRIES = 14;
    /**
     * Checking costs a metadata read, so poll rather than wait in lumps: the player usually
     * fills the session bitmap in within a few hundred ms, and 700ms steps meant a track change
     * that missed by 50ms still cost the full 700. Fourteen tries covers the same ~1.6s window.
     */
    private static final long ART_RETRY_MS = 120L;
    /** What the wallpaper currently shows, coarsely, so a stale source can be recognised. */
    private static volatile int sArtPrint;
    /**
     * Which push is the current one. The retries span a couple of seconds, so the card can be
     * dismissed - or the track changed again - while they are still running; without this, a
     * retry that finally found artwork would put the cover back after cover mode had ended.
     */
    private static volatile int sPushGen;

    private static void pushArtAsync(final boolean on, final boolean fresh) {
        final Context ctx = sAppCtx;
        if (ctx == null) return;
        final int gen = ++sPushGen;
        if (!on) {
            sArtPrint = 0;
            worker().post(new Runnable() {
                @Override
                public void run() { pushArtToWallpaper(ctx, false, null); }
            });
            return;
        }
        // Checked before every push rather than once per process. The user can send the
        // wallpaper back to "follow home" at any moment - from Settings, a theme, the wallpaper
        // carousel - and that silently removes the keyguard renderer this whole thing hangs off.
        // A cached "already fine" would mean the module never noticed and the user had to go
        // press a repair button, which is exactly what should not be necessary.
        worker().post(new Runnable() {
            @Override
            public void run() { ensureLockWallpaper(ctx); }
        });
        // Not a track change (a bias tweak, a manual pushart): take whatever is there now.
        tryPushArt(ctx, fresh ? 0 : ART_TRIES - 1, fresh, gen);
    }

    private static void tryPushArt(final Context ctx, final int attempt, final boolean fresh,
                                   final int gen) {
        worker().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != sPushGen) {
                    XposedBridge.log(TAG + "art push superseded, dropping it");
                    return;
                }
                boolean last = attempt >= ART_TRIES - 1;
                Bitmap art = albumArt(ctx, last);
                int print = art == null ? 0 : artPrint(art);
                boolean stale = fresh && art != null && sArtPrint != 0 && print == sArtPrint;
                if ((art == null || stale) && !last) {
                    XposedBridge.log(TAG + "art " + (art == null ? "not ready" : "still the old one")
                            + ", retrying (" + (attempt + 2) + "/" + ART_TRIES + ")");
                    tryPushArt(ctx, attempt + 1, fresh, gen);
                    return;
                }
                if (stale) {
                    // The next track off the same album really does have the same cover.
                    XposedBridge.log(TAG + "same artwork as the last track, wallpaper left alone");
                    return;
                }
                sArtPrint = print;
                pushArtToWallpaper(ctx, true, art);
            }
        }, attempt == 0 ? 0L : ART_RETRY_MS);
    }

    /**
     * The whole cover mechanism rests on the lock screen having a wallpaper of its OWN. MIUI
     * only builds a KeyguardImageEngineImpl - and with it the keyguard renderer whose texture we
     * replace - when the lock screen wallpaper is separate from the home one. Share one between
     * them and the wallpaper process creates a single engine with isLockScreen=false, so there
     * is nothing to hook and the cover silently does nothing at all.
     *
     * This is not hypothetical: the lock wallpaper got reset to "same as home" (dumpsys wallpaper
     * showed an empty "Lock wallpaper state" and mWhich=3), and the module went on reporting
     * cover mode on, art pushed, reload requested - with nowhere to draw it.
     *
     * Copying the current home wallpaper into the lock slot satisfies the requirement without
     * changing what the user sees: the lock screen keeps the same picture, it just owns a copy.
     */
    private static boolean ensureLockWallpaper(Context ctx) {
        return ensureLockWallpaper(ctx, false);
    }

    private static boolean ensureLockWallpaper(Context ctx, boolean force) {
        android.app.WallpaperManager wm = (android.app.WallpaperManager)
                ctx.getSystemService(Context.WALLPAPER_SERVICE);
        if (wm == null) return false;
        if (!force) {
            try {
                android.os.ParcelFileDescriptor lock =
                        wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
                if (lock != null) {
                    try {
                        lock.close();
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "cannot read the lock wallpaper slot: " + t);
                return false;
            }
            XposedBridge.log(TAG + "lock screen has no wallpaper of its own - the keyguard "
                    + "wallpaper engine cannot exist, so the cover has nowhere to go. Giving it a "
                    + "copy of the home wallpaper.");
        }
        try {
            Bitmap home = homeWallpaper(wm);
            if (home == null) {
                XposedBridge.log(TAG + "no home wallpaper bitmap to copy");
                return false;
            }
            // At the screen's own size, deliberately. Whatever goes in this slot becomes the
            // texture the keyguard uploads, and every track change then has to rescale the
            // composed cover to it - on both sides. The home wallpaper here is 1579x3432, which
            // cost ~100ms a track for nothing: the picture is only ever shown on this screen.
            Bitmap fitted = home.getWidth() == sScreenW && home.getHeight() == sScreenH
                    ? home : centerCrop(home, sScreenW, sScreenH);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            if (fitted != home) fitted.recycle();
            home.recycle();
            XposedBridge.log(TAG + "lock wallpaper set at " + sScreenW + "x" + sScreenH
                    + "; the keyguard engine will be rebuilt");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not set a lock wallpaper: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /** The home wallpaper, decoded no larger than it needs to be for this screen. */
    private static Bitmap homeWallpaper(android.app.WallpaperManager wm) {
        try {
            android.os.ParcelFileDescriptor sys =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
            if (sys != null) {
                java.io.InputStream in =
                        new android.os.ParcelFileDescriptor.AutoCloseInputStream(sys);
                try {
                    android.graphics.BitmapFactory.Options o =
                            new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    return android.graphics.BitmapFactory.decodeStream(in, null, o);
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "reading the home wallpaper file failed: " + t);
        }
        // A live or default home wallpaper has no file; render whatever is showing instead.
        try {
            Drawable d = wm.getDrawable();
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Whether the lock screen has a wallpaper entry of its own. Note this is about the ENTRY,
     * not the picture: a copy of the home wallpaper counts, which is exactly what
     * ensureLockWallpaper() installs.
     */
    private static boolean hasLockWallpaper(Context ctx) {
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            android.os.ParcelFileDescriptor f =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (f == null) return false;
            try {
                f.close();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Puts the lock screen back to following the home wallpaper. Undoes ensureLockWallpaper. */
    private static void clearLockWallpaper(Context ctx) {
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            wm.clear(android.app.WallpaperManager.FLAG_LOCK);
            XposedBridge.log(TAG + "lock wallpaper cleared, back to following the home one");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "clearLockWallpaper failed: " + t);
        }
    }

    /** Coarse identity of an artwork: enough to tell one cover from another, cheap to take. */
    private static int artPrint(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
            int[] px = new int[64];
            s.getPixels(px, 0, 8, 0, 0, 8, 8);
            s.recycle();
            return java.util.Arrays.hashCode(px);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * One switch for the whole look. The clock collapse is not a separate thing the user turns
     * on - if the album cover is the wallpaper, the clock is small.
     */
    private static void enterCoverMode(boolean animate) {
        sCoverMode = true;
        setDepthHidden(true);
        sCollapseMin = sClockScale;
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        sAppliedK = Float.NaN;
        sAppliedGlassV = Float.NaN;
        if (animate) {
            springTo(SQUEEZE_FLOOR, EASE_STATE_CHANGED[0], EASE_STATE_CHANGED[1],
                    "STATE_CHANGED", false);
        } else {
            sHoldY = SQUEEZE_FLOOR;
            drive(SQUEEZE_FLOOR, false, "STATE_CHANGED");
        }
        saveState();
    }

    private static void exitCoverMode() {
        sCoverMode = false;
        abandonHold("cover mode off", true);
        setDepthHidden(false);
        saveState();
    }

    /**
     * Puts the collapse back if anything dropped it. Cheap and idempotent: when the hold already
     * survived (the normal path now), this only re-states values that are already correct, so
     * waking from AOD shows no transition at all.
     */
    private static void reassertCoverClock() {
        if (!sCoverMode) return;
        sCollapseMin = sClockScale;
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        Float held = sHoldY;
        if (held != null && Math.abs(held - SQUEEZE_FLOOR) < 1f) return;
        sAppliedK = Float.NaN;
        sAppliedGlassV = Float.NaN;
        sHoldY = SQUEEZE_FLOOR;
        drive(SQUEEZE_FLOOR, false, "STATE_CHANGED");
        XposedBridge.log(TAG + "cover clock re-collapsed after wake");
    }

    private static void setDepthHidden(final boolean hide) {
        sDepthHidden = hide;
        final View v = sContainer;
        if (v == null) { XposedBridge.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                View d = findDeductedImageView();
                if (d == null) { XposedBridge.log(TAG + "deducted_image_view not found"); return; }
                d.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
                XposedBridge.log(TAG + "deducted_image_view " + (hide ? "hidden" : "shown"));
                saveState();
            }
        });
    }

    private static View findDeductedImageView() {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getContext().getResources()
                .getIdentifier("deducted_image_view", "id", "com.android.systemui");
        return id == 0 ? null : v.getRootView().findViewById(id);
    }

    /**
     * Lays the cover out the way the Apple reference does: the artwork sharp at full width and
     * its own aspect, with a heavily blurred copy filling the screen above and below it.
     * Center-cropping a square cover into a 1200x2608 screen instead zooms ~5x and throws most
     * of the artwork away - on the Lover cover it cut the face in half.
     *
     * bias places the sharp band in the leftover vertical space: 0 flush with the top, 0.5
     * centred, 1 flush with the bottom. Centred is what the media card covers, so the default
     * sits above that.
     */
    private static Bitmap composeWallpaper(Bitmap src, int w, int h, float bias) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG);

        float coverH = src.getHeight() * (w / (float) src.getWidth());
        if (bias < 0f) bias = 0f;
        if (bias > 1f) bias = 1f;
        float top = (h - coverH) * bias;

        // Blurring a centre-crop gave a muddy wash whose colours did not meet the sharp cover at
        // the seam. Extend the artwork by MIRRORING it above and below instead: the rows either
        // side of a seam are then the same row of the artwork, so the join is continuous by
        // construction, and the blur keeps local colour instead of averaging the whole image.
        int bw = Math.max(1, w / 4), bh = Math.max(1, h / 4);
        float k = bw / (float) w;
        float cH = coverH * k, tp = top * k;
        Bitmap bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(bg);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, tp, bw, tp + cH), p);
        bc.save();
        bc.translate(0, tp);
        bc.scale(1f, -1f);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, 0, bw, cH), p);
        bc.restore();
        bc.save();
        bc.translate(0, tp + cH);
        bc.scale(1f, -1f);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, -cH, bw, 0), p);
        bc.restore();

        Bitmap blurred = blur(bg, 48, 4, 3);
        cv.drawBitmap(blurred, null, new android.graphics.RectF(0, 0, w, h), p);
        cv.drawColor(0x14000000);
        float feather = Math.min(240f, coverH / 4f);
        int layer = cv.saveLayer(0, top, w, top + coverH, null);
        cv.drawBitmap(src, null, new android.graphics.RectF(0, top, w, top + coverH), p);
        android.graphics.Paint mask = new android.graphics.Paint();
        mask.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
        mask.setShader(new android.graphics.LinearGradient(0, top, 0, top + feather,
                0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, top, w, top + feather, mask);
        mask.setShader(new android.graphics.LinearGradient(0, top + coverH - feather, 0,
                top + coverH, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, top + coverH - feather, w, top + coverH, mask);
        cv.restoreToCount(layer);
        return out;
    }

    /**
     * Downscaling hard and letting one bilinear upscale smear it back is not a blur - it leaves
     * the tell-tale blocky diamonds of interpolating a tiny image. Halve step by step (each
     * halving is a box average), run a real separable box blur at the small size where it costs
     * almost nothing, then double back up, so nothing is ever interpolated across a big jump.
     */
    private static Bitmap blur(Bitmap src, int smallW, int radius, int passes) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 > smallW) {
            Bitmap next = Bitmap.createScaledBitmap(cur,
                    cur.getWidth() / 2, Math.max(1, cur.getHeight() / 2), true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        int sh = Math.max(1, cur.getHeight() * smallW / cur.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(cur, smallW, sh, true);
        if (cur != src) cur.recycle();

        int n = smallW * sh;
        int[] a = new int[n], b = new int[n];
        small.getPixels(a, 0, smallW, 0, 0, smallW, sh);
        for (int i = 0; i < passes; i++) {
            boxH(a, b, smallW, sh, radius);
            boxV(b, a, smallW, sh, radius);
        }
        small.setPixels(a, 0, smallW, 0, 0, smallW, sh);

        // Climb back up in doublings; the caller's final draw stretches the last step.
        Bitmap up = small;
        while (up.getWidth() * 2 <= src.getWidth()) {
            Bitmap next = Bitmap.createScaledBitmap(up, up.getWidth() * 2, up.getHeight() * 2, true);
            if (up != small) up.recycle();
            up = next;
        }
        if (up != small) small.recycle();
        return up;
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[base + Math.min(w - 1, Math.max(0, i))];
                sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
            }
            for (int x = 0; x < w; x++) {
                dst[base + x] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int o = src[base + Math.min(w - 1, Math.max(0, x - r))];
                int in = src[base + Math.min(w - 1, Math.max(0, x + r + 1))];
                sr += ((in >> 16) & 0xff) - ((o >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((o >> 8) & 0xff);
                sb += (in & 0xff) - (o & 0xff);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[Math.min(h - 1, Math.max(0, i)) * w + x];
                sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int o = src[Math.min(h - 1, Math.max(0, y - r)) * w + x];
                int in = src[Math.min(h - 1, Math.max(0, y + r + 1)) * w + x];
                sr += ((in >> 16) & 0xff) - ((o >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((o >> 8) & 0xff);
                sb += (in & 0xff) - (o & 0xff);
            }
        }
    }

    /** Fills w x h from the source without distorting it, the way CENTER_CROP would. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        android.graphics.RectF dst = new android.graphics.RectF(
                (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        cv.drawBitmap(src, null, dst, new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Adds the cover into keyguard_background_layer, which sits behind the whole clock stack. */
    private static void attachCover() {
        final View v = sContainer;
        if (v == null) {
            XposedBridge.log(TAG + "no clock container");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = v.getContext();
                    int id = ctx.getResources().getIdentifier(
                            "keyguard_background_layer", "id", "com.android.systemui");
                    View layer = id == 0 ? null : v.getRootView().findViewById(id);
                    if (!(layer instanceof ViewGroup)) {
                        XposedBridge.log(TAG + "keyguard_background_layer not found");
                        return;
                    }
                    Bitmap art = albumArt(ctx);
                    if (art == null) {
                        XposedBridge.log(TAG + "no album art available");
                        return;
                    }
                    ImageView iv = sCover;
                    if (iv == null || iv.getParent() != layer) {
                        detachCover();
                        iv = new ImageView(ctx);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        ((ViewGroup) layer).addView(iv, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        sCover = iv;
                    }
                    iv.setImageBitmap(art);
                    rebindSession();
                    XposedBridge.log(TAG + "cover attached, layer "
                            + layer.getWidth() + "x" + layer.getHeight());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "attachCover failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * Turns the automatic mode on or off. On, cover mode is a consequence of what is playing;
     * off, it stays exactly where a pushart broadcast left it.
     */
    private static synchronized void setAuto(boolean on) {
        sAuto = on;
        XposedBridge.log(TAG + "auto mode " + (on ? "on" : "off"));
        saveState();
        if (!on) {
            main().removeCallbacks(sCardGone);
            return;
        }
        startSessionWatch();
        applyCardState();
    }

    /**
     * The card appeared, changed track, or went away. A track change arrives as a removal
     * followed by an add, so "gone" is only believed after CARD_GONE_MS - otherwise every skip
     * would tear the wallpaper down and put it straight back.
     */
    private static void onCardChanged(Object mediaData) {
        sCardKnown = true;
        boolean showing = mediaData != null;
        if (showing) {
            try {
                sCardToken = (android.media.session.MediaSession.Token)
                        XposedHelpers.getObjectField(mediaData, "token");
            } catch (Throwable t) {
                sCardToken = null;
            }
            sCardKey = cardKey(mediaData);
        } else {
            sCardToken = null;
            sCardKey = "";
        }
        XposedBridge.log(TAG + "media card " + (showing ? "-> " + sCardKey : "gone"));
        main().removeCallbacks(sCardGone);
        if (!sAuto) {
            sCardShowing = showing;
            return;
        }
        if (showing) {
            sCardShowing = true;
            applyCardState();
        } else {
            main().postDelayed(sCardGone, CARD_GONE_MS);
        }
    }

    private static final Runnable sCardGone = new Runnable() {
        @Override
        public void run() {
            sCardShowing = false;
            applyCardState();
        }
    };

    /** Cover mode is on exactly when the card is up. Idempotent, so it is safe to re-run. */
    private static void applyCardState() {
        // Nothing observed yet: a SystemUI restart must not tear down a cover that is on the
        // phone just because the card hook has not fired for the first time.
        if (!sAuto || !sCardKnown) return;
        if (!sCardShowing) {
            sTrackKey = "";
            if (sCoverMode) {
                XposedBridge.log(TAG + "media card dismissed, leaving cover mode");
                setCoverEnabled(false, true);
            }
            return;
        }
        rebindSession();
    }

    /** Track identity as the card itself sees it. */
    private static String cardKey(Object mediaData) {
        try {
            return XposedHelpers.getObjectField(mediaData, "packageName")
                    + "|" + XposedHelpers.getObjectField(mediaData, "song")
                    + "|" + XposedHelpers.getObjectField(mediaData, "artist");
        } catch (Throwable t) {
            return String.valueOf(mediaData);
        }
    }

    private static synchronized void startSessionWatch() {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        try {
            if (sMsm == null) {
                sMsm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (sSessionsCb == null) {
                // Which app owns the session changes without any one controller telling us -
                // starting a second player, or the first one going away - so the list itself has
                // to be watched, not just the controller we happen to be holding.
                sSessionsCb = new MediaSessionManager.OnActiveSessionsChangedListener() {
                    @Override
                    public void onActiveSessionsChanged(List<MediaController> controllers) {
                        rebindSession();
                    }
                };
                sMsm.addOnActiveSessionsChangedListener(sSessionsCb, null, main());
                XposedBridge.log(TAG + "active-session listener registered");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "session listener failed: " + Log.getStackTraceString(t));
        }
        rebindSession();
    }

    /**
     * Re-points the callback at the session the card is showing. The card hands us its own
     * MediaSession token, so this is exact - pickController()'s ranking is only the fallback for
     * when we are driven by something other than the card.
     */
    private static synchronized void rebindSession() {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        MediaController c = controllerFromCard(ctx);
        if (c == null) c = pickController(ctx);
        boolean same = c != null && sWatched != null
                && c.getSessionToken().equals(sWatched.getSessionToken());
        if (!same) {
            if (sWatched != null && sMediaCb != null) {
                try {
                    sWatched.unregisterCallback(sMediaCb);
                } catch (Throwable ignored) {
                }
            }
            sWatched = c;
            sMediaCb = null;
            if (c != null) {
                sMediaCb = new MediaController.Callback() {
                    @Override
                    public void onMetadataChanged(MediaMetadata md) {
                        if (sCoverWanted) attachCover();
                        onMediaUpdate();
                    }

                    @Override
                    public void onSessionDestroyed() {
                        rebindSession();
                    }
                };
                try {
                    c.registerCallback(sMediaCb, main());
                    XposedBridge.log(TAG + "following " + c.getPackageName());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "registerCallback failed: " + t);
                }
            } else {
                XposedBridge.log(TAG + "no active media session");
            }
        }
        onMediaUpdate();
    }

    /** Identity of what is on screen, so a metadata storm pushes the same artwork only once. */
    private static String trackKey(MediaController c) {
        if (c == null) return "";
        MediaMetadata md = c.getMetadata();
        if (md == null) return c.getPackageName();
        return c.getPackageName() + "|" + md.getString(MediaMetadata.METADATA_KEY_TITLE)
                + "|" + md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                + "|" + md.getString(MediaMetadata.METADATA_KEY_ALBUM);
    }

    /** The controller the card is bound to, or null if the card has not named one. */
    private static MediaController controllerFromCard(Context ctx) {
        android.media.session.MediaSession.Token t = sCardToken;
        if (t == null) return null;
        try {
            return new MediaController(ctx, t);
        } catch (Throwable e) {
            XposedBridge.log(TAG + "card token unusable: " + e);
            return null;
        }
    }

    /**
     * One place decides what the card means, because the same decision is reached from three
     * directions: the card hook, a metadata change on the session, and the session list changing
     * underneath us. Playback state is deliberately not consulted - a paused track is still a
     * card on the lockscreen, and still music mode.
     */
    private static void onMediaUpdate() {
        if (!sAuto || !sCardShowing) return;
        String key = sCardKey.isEmpty() ? trackKey(sWatched) : sCardKey;
        if (sCoverMode && key.equals(sTrackKey)) return;
        sTrackKey = key;
        XposedBridge.log(TAG + "card track: " + key);
        if (sCoverMode) pushArtAsync(true, true);
        else setCoverEnabled(true, true);
    }

    /**
     * Cover mode is the wallpaper and the clock together, so both switches move as one. The
     * wallpaper is composed on the worker, the clock is taken on the main thread.
     */
    private static void setCoverEnabled(final boolean on, final boolean animate) {
        setCoverEnabled(on, animate, true);
    }

    /**
     * fresh says whether this is a track change. On one, artwork identical to what is already on
     * the wallpaper means the source has not caught up yet and is worth waiting for; on a manual
     * push it just means the same song, and waiting 1.7s to conclude that is pure delay.
     */
    private static void setCoverEnabled(final boolean on, final boolean animate,
                                        final boolean fresh) {
        pushArtAsync(on, on && fresh);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (on) enterCoverMode(animate && screenOn());
                else exitCoverMode();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main().post(r);
    }

    /**
     * Springing the clock needs Choreographer frames, and those stop while the display is off.
     * An animation started then never settles, and leaves the hold pinned half way.
     */
    private static boolean screenOn() {
        try {
            PowerManager pm = (PowerManager) sAppCtx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void detachCover() {
        // The session watch belongs to auto mode, not to the (legacy) in-SystemUI ImageView, so
        // dropping that view must not stop the module following the music.
        if (!sAuto && sWatched != null && sMediaCb != null) {
            try {
                sWatched.unregisterCallback(sMediaCb);
            } catch (Throwable ignored) {
            }
            sWatched = null;
            sMediaCb = null;
        }
        final ImageView iv = sCover;
        sCover = null;
        if (iv == null) return;
        iv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup p = (ViewGroup) iv.getParent();
                    if (p != null) p.removeView(iv);
                    iv.setImageDrawable(null);
                    XposedBridge.log(TAG + "cover detached");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "detachCover failed: " + t);
                }
            }
        });
    }

    /** Rides the same progress as the scale, so one OEM spring drives size and look together. */
    private static void applyGlassMorph(float p) {
        if (Float.isNaN(sGlassV0)) return;
        float g = sGlassV0 + p * (sGlassV1 - sGlassV0);
        if (!Float.isNaN(sAppliedGlassV) && Math.abs(g - sAppliedGlassV) < 0.004f) return;
        sAppliedGlassV = g;
        for (View root : clockRoots()) {
            try {
                View c = ((android.view.ViewGroup) root).getChildAt(0);
                if (c == null) continue;
                XposedHelpers.callMethod(c, "updateGlassValue", g);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Calls a method on the AllInOneHourClock / AllInOneMinuteClock views of both trees. */
    private static void callOnClockViews(final String method, final String kind, final float f,
                                         final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { XposedBridge.log(TAG + "callclock: need m="); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                Object arg = "b".equals(kind) ? (Object) b : "i".equals(kind) ? (Object) n : (Object) f;
                for (View root : clockRoots()) {
                    try {
                        View c = ((android.view.ViewGroup) root).getChildAt(0);
                        if (c == null) continue;
                        XposedHelpers.callMethod(c, method, arg);
                        c.invalidate();
                        XposedBridge.log(TAG + viewIdOf(root) + " " + c.getClass().getSimpleName()
                                + "." + method + "(" + arg + ") ok");
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + "callclock " + method + ": " + e);
                    }
                }
            }
        });
    }

    /** Calls one TimeView setter on every clock view in both subtrees, for fast iteration. */
    private static void callOnTimeViews(final String method, final String kind, final float f,
                                        final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { XposedBridge.log(TAG + "call: need m="); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                Object arg = "b".equals(kind) ? (Object) b : "i".equals(kind) ? (Object) n : (Object) f;
                int hits = 0;
                for (View root : clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null) continue;
                            XposedHelpers.callMethod(t, method, arg);
                            t.invalidate();
                            hits++;
                        } catch (Throwable e) {
                            XposedBridge.log(TAG + "call " + method + " on " + id + ": " + e);
                        }
                    }
                }
                XposedBridge.log(TAG + "call " + method + "(" + arg + ") applied to " + hits + " views");
            }
        });
    }

    /** The MiGlass shader samples the wallpaper and does not follow a child View's scale. */
    private static void setGlass(final boolean on) {
        final View v = sContainer;
        if (v == null) { XposedBridge.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null) continue;
                            XposedHelpers.callMethod(t, "setMiGlassEffectEnable", on);
                            t.invalidate();
                        } catch (Throwable e) {
                            XposedBridge.log(TAG + "setGlass " + id + " failed: " + e);
                        }
                    }
                }
                XposedBridge.log(TAG + "glass=" + on);
            }
        });
    }

    private static void groupScale(final String idName, final float k, final float px,
                                   final float py, final float ty) {
        final View v = sContainer;
        if (v == null) { XposedBridge.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier(idName, "id", "com.android.systemui");
                    int n = 0;
                    for (View root : clockRoots()) {
                        View g = id == 0 ? null : root.findViewById(id);
                        if (g == null) continue;
                        g.setPivotX(px >= 0 ? px : g.getWidth() / 2f);
                        g.setPivotY(py >= 0 ? py : 0f);
                        g.setScaleX(k);
                        g.setScaleY(k);
                        g.setTranslationY(ty);
                        n++;
                        XposedBridge.log(TAG + idName + " in " + viewIdOf(root) + " scale=" + k
                                + " pivot=" + g.getPivotX() + "," + g.getPivotY() + " ty=" + ty
                                + " size=" + g.getWidth() + "x" + g.getHeight());
                    }
                    if (n == 0) XposedBridge.log(TAG + idName + " not found in either clock root");
                } catch (Throwable e) {
                    XposedBridge.log(TAG + "groupScale failed: " + Log.getStackTraceString(e));
                }
            }
        });
    }

    /**
     * HyperOS renders the clock as a sandwich: the hour lives under
     * keyguard_background_layer (KeyguardClockContainer -> AllInOneHourClock) and the minute
     * under keyguard_foreground_layer (AllInOneMinuteClock), so the wallpaper subject can sit
     * between them. Both subtrees reuse the SAME ids - time_group, hour_view, clock_animation_
     * container - so a findViewById from the root only ever reaches the hour. Anything that
     * changes the clock's geometry has to be applied to both.
     */
    private static View[] clockRoots() {
        View v = sContainer;
        if (v == null) return new View[0];
        View root = v.getRootView();
        java.util.List<View> out = new java.util.ArrayList<>();
        for (String id : new String[]{"miui_keyguard_clock_container",
                "miui_keyguard_foreground_clock_container"}) {
            int rid = root.getResources().getIdentifier(id, "id", "com.android.systemui");
            View c = rid == 0 ? null : root.findViewById(rid);
            if (c != null) out.add(c);
        }
        return out.toArray(new View[0]);
    }

    private static String viewIdOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return String.valueOf(v.getId());
        }
    }

    private static void dumpViewTree(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        try {
            String name = v.getResources().getResourceEntryName(v.getId());
            sb.append(" #").append(name);
        } catch (Throwable ignored) {
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(' ').append(loc[0]).append(',').append(loc[1])
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility());
        if (v.getScaleX() != 1f || v.getScaleY() != 1f) {
            sb.append(" scale=").append(v.getScaleX()).append('/').append(v.getScaleY());
        }
        if (v.getTranslationY() != 0f) sb.append(" ty=").append(v.getTranslationY());
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            sb.append(" textSize=").append(t.getTextSize())
              .append(" fontVar=").append(t.getFontVariationSettings())
              .append(" text=\"").append(t.getText()).append('"');
        }
        XposedBridge.log(TAG + sb);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1);
        }
    }

    private static void dumpInfo() {
        View v = sContainer;
        if (v == null) {
            XposedBridge.log(TAG + "no clock container");
            return;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        XposedBridge.log(TAG + "container onScreen=[" + loc[0] + "," + loc[1] + "] size="
                + v.getWidth() + "x" + v.getHeight());
        try {
            XposedBridge.log(TAG + "getClockBottom()=" + XposedHelpers.callMethod(v, "getClockBottom"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getClockBottom failed: " + t);
        }
        try {
            XposedBridge.log(TAG + "getNotificationClockTop()="
                    + XposedHelpers.callMethod(v, "getNotificationClockTop"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getNotificationClockTop failed: " + t);
        }
        logViewById(v, "mi_media_controls");
        logViewById(v, "album_art_image");
    }

    /**
     * Reads the OEM's own miuix AnimConfig objects off the live clock animation, so we can
     * copy the real curve instead of guessing a duration.
     * KeyguardClockContainer.mAnimationHelper.mClockAnima -> AllInOneClockAnimation
     */
    private static void dumpAnimConfigs() {
        View v = sContainer;
        if (v == null) {
            XposedBridge.log(TAG + "no clock container");
            return;
        }
        try {
            Object helper = XposedHelpers.getObjectField(v, "mAnimationHelper");
            Object anim = XposedHelpers.getObjectField(helper, "mClockAnima");
            XposedBridge.log(TAG + "clock animation impl = " + anim.getClass().getName());
            for (String f : new String[]{"stateChangedAnimConfig", "notifsChangedAnimConfig",
                    "defaultAnimConfig", "animRunningConfig"}) {
                try {
                    Object cfg = XposedHelpers.getObjectField(anim, f);
                    XposedBridge.log(TAG + f + " = " + describe(cfg));
                } catch (Throwable t) {
                    XposedBridge.log(TAG + f + " unavailable: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dumpAnimConfigs failed: " + Log.getStackTraceString(t));
        }
    }

    /** Generic reflective field dump, one level deep, for opaque OEM value objects. */
    private static String describe(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName()).append('{');
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                Object val = f.get(o);
                if (val != null && val.getClass().isArray()) {
                    StringBuilder arr = new StringBuilder("[");
                    int n = java.lang.reflect.Array.getLength(val);
                    for (int k = 0; k < n; k++) {
                        if (k > 0) arr.append(',');
                        arr.append(java.lang.reflect.Array.get(val, k));
                    }
                    val = arr.append(']').toString();
                } else if (val != null && val.getClass().getName().startsWith("miuix.")) {
                    val = val.getClass().getSimpleName() + describeShallow(val);
                }
                sb.append(f.getName()).append('=').append(val).append(' ');
            } catch (Throwable ignored) {
            }
        }
        return sb.append('}').toString();
    }

    /** Walks the whole class hierarchy - miuix ease styles keep their params in a super. */
    private static String describeShallow(Object o) {
        StringBuilder sb = new StringBuilder("{");
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            sb.append('<').append(c.getSimpleName()).append("> ");
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(o);
                    if (val != null && val.getClass().isArray()) {
                        StringBuilder arr = new StringBuilder("[");
                        int n = java.lang.reflect.Array.getLength(val);
                        for (int k = 0; k < n; k++) {
                            if (k > 0) arr.append(',');
                            arr.append(java.lang.reflect.Array.get(val, k));
                        }
                        val = arr.append(']').toString();
                    }
                    sb.append(f.getName()).append('=').append(val).append(' ');
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.append('}').toString();
    }

    private static void logViewById(View anchor, String idName) {
        try {
            Context c = anchor.getContext();
            int id = c.getResources().getIdentifier(idName, "id", "com.android.systemui");
            if (id == 0) {
                XposedBridge.log(TAG + idName + ": id not found");
                return;
            }
            View found = anchor.getRootView().findViewById(id);
            if (found == null) {
                XposedBridge.log(TAG + idName + ": view not present");
                return;
            }
            int[] loc = new int[2];
            found.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + found.getWidth(), loc[1] + found.getHeight());
            XposedBridge.log(TAG + idName + ": " + r + " vis=" + found.getVisibility());
        } catch (Throwable t) {
            XposedBridge.log(TAG + idName + " lookup failed: " + t);
        }
    }
}
