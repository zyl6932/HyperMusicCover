package com.os4.musiccover.shade;
import com.os4.musiccover.Gen;
import com.os4.musiccover.ModuleState;
import com.os4.musiccover.Main;
import com.os4.musiccover.Xp;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Everything the module does to the notification shade, which is now one thing: the album cover
 * as a moving background behind it (see {@link ShadeBackdrop}).
 *
 * This class owns the hooks that feed that background, the two settings, and the rules for when
 * it is shown. Main only installs it, hands it the cover and persists its settings; the shade's
 * logic does not live there.
 *
 * The shade itself is left entirely to SystemUI - its blur, its zoom, its cards and its layout.
 * The background sits in a window below the shade, so the official glass samples it on its own.
 */
public final class ShadeLayer {

    private ShadeLayer() {
    }

    private static final String TAG = "[MCShade] ";

    private static final String CLS_WINDOW_ROOT =
            "com.android.systemui.shade.NotificationShadeWindowView";
    /**
     * The shade's per-frame progress is written through this property. The animator does not
     * declare a `setValue` of its own, so the base class is hooked and filtered by identity.
     */
    private static final String CLS_FLOAT_FLOW = "com.miui.systemui.util.FloatFlowProperty";
    private static final String CLS_EXPANSION_ANIMATOR =
            "com.android.systemui.shade.NotificationPanelExpansionAnimator";
    /**
     * The control centre lives in a plugin, but it reports its expansion to this host-side
     * delegate every frame, through PanelExpandController.Callback.onExpansionChanged(float).
     */
    private static final String CLS_CENTRE_EXPAND =
            "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate";

    private static final Handler UI = Gen.handler();

    /** The shade window root, a NotificationShadeWindowView. Its context adds the window. */
    private static volatile View sRoot;
    /** Resolved once; the per-frame comparison is identity, not a name string. */
    private static volatile Class<?> sExpansionCls;

    // ------------------------------------------------------------------ hooks

    /**
     * Both hooks, each failing on its own: a build that renames one loses the background and
     * nothing else in the module.
     */
    public static void install(ClassLoader cl) {
        try {
            final Class<?> root = Xp.findClass(CLS_WINDOW_ROOT, cl);
            Xp.hookAllConstructors("sysui.shade.root_ctor", root, chain -> {
                final Object result = chain.proceed();
                if (chain.getThisObject() instanceof View) sRoot = (View) chain.getThisObject();
                return result;
            });
            Xp.log(TAG + "shade window hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "shade window hook failed, the shade will have no cover: " + t);
        }

        // Matched by Class identity: a name comparison on every frame of every Folme property in
        // SystemUI is one rename away from receiving no frames at all, silently.
        try {
            sExpansionCls = Xp.findClass(CLS_EXPANSION_ANIMATOR, cl);
            final Class<?> flow = Xp.findClass(CLS_FLOAT_FLOW, cl);
            Xp.hookAll("sysui.shade.expansion", flow, "setValue", chain -> {
                final Object result = chain.proceed();
                if (chain.getThisObject().getClass() == sExpansionCls) {
                    final java.util.List<Object> a = chain.getArgs();
                    final Object v = a.size() > 1 ? a.get(1) : null;
                    if (v instanceof Float) onExpansion((Float) v);
                }
                return result;
            });
            Xp.log(TAG + "shade expansion hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "shade expansion hook failed, the cover will never show: " + t);
        }

        try {
            final Class<?> centre = Xp.findClass(CLS_CENTRE_EXPAND, cl);
            Xp.hookAll("sysui.shade.expansion_change", centre, "onExpansionChanged", chain -> {
                final Object result = chain.proceed();
                final java.util.List<Object> a = chain.getArgs();
                if (a.size() == 1 && a.get(0) instanceof Float) onCentreExpansion((Float) a.get(0));
                return result;
            });
            Xp.log(TAG + "control centre expansion hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "control centre expansion hook failed, the centre gets no cover: " + t);
        }
    }

    static View shadeRoot() {
        return sRoot;
    }

    /** How far either panel is open, 0 shut. For the hot-reload busy check. */
    public static float expansion() {
        return Math.max(sExpansion, sCentreExpansion);
    }

    /**
     * Retirement: both panels' progress forgotten and the backdrop's window, GL thread and mesh
     * released. The hooks are the ledger's and stay; sRoot is the OEM's own view.
     */
    public static void retire() {
        UI.removeCallbacks(DRIVE);
        forgetExpansion();
        ShadeBackdrop.retire();
    }

    /**
     * Both readings back to shut, without touching the backdrop.
     *
     * <p>The OEM force-closes the shade on unlock and on screen off without writing the last
     * value through the setter the expansion hook watches: a shade pulled down on the lock
     * screen left this at 1 for the rest of the process, and the hot-reload busy check then
     * refused every update with "the shade is open" over a panel that was not there (measured
     * 2026-10-25; `NotificationShade` was GONE and `cmd statusbar collapse` cleared it).
     */
    public static void forgetExpansion() {
        sExpansion = 0f;
        sCentreExpansion = 0f;
        sLastDriven = 0f;
        sLastShade = 0f;
        sLastCentre = 0f;
    }

    // ------------------------------------------------------------------ driving

    /** The notification panel's expansion, 0 shut and 1 wide open. */
    private static volatile float sExpansion;
    /** The control centre's expansion, the same scale. */
    private static volatile float sCentreExpansion;

    /** Every frame of the notification panel's expansion, on the main thread. */
    static void onExpansion(float f) {
        sExpansion = clamp01(f);
        drive();
    }

    /**
     * Every frame of the control centre's expansion. The plugin is expected to report it on the
     * main thread; if it ever does not, the drive is moved there, because it touches a view.
     */
    static void onCentreExpansion(float f) {
        sCentreExpansion = clamp01(f);
        if (Looper.myLooper() == Looper.getMainLooper()) drive();
        else UI.post(DRIVE);
    }

    private static final Runnable DRIVE = new Runnable() {
        @Override
        public void run() {
            drive();
        }
    };

    /**
     * The background follows whichever of the two panels is further open.
     *
     * Switching from the notification shade to the control centre runs one down while the other
     * comes up. Following only the shade faded the background out and back in, which read as a
     * flash; the larger of the two never drops far enough to show, and the same rule covers the
     * centre pulled down on its own.
     */
    private static void drive() {
        final boolean allowed = sEnabled && !Main.keyguardLocked();
        final float f = Math.max(sExpansion, sCentreExpansion);
        noteCut(f, allowed);
        ShadeBackdrop.drive(f, allowed);
    }

    /** The last progress drive() passed on, and which of the two panels it came from. */
    private static float sLastDriven;
    private static float sLastShade;
    private static float sLastCentre;

    /**
     * Logs a progress that falls from well open to shut in one step, with both panels' values
     * before and after. A normal close never trips it, so the line only appears for the case the
     * fade-out floor in ShadeBackdrop exists for, and says which panel's signal jumped.
     */
    private static void noteCut(float f, boolean allowed) {
        final float shown = allowed ? f : 0f;
        if (sLastDriven >= 0.2f && shown <= 0f) {
            Xp.log(TAG + "progress cut from " + sLastDriven + " (shade " + sLastShade
                    + ", centre " + sLastCentre + ") to shade " + sExpansion + ", centre "
                    + sCentreExpansion + (allowed ? "" : ", not allowed"));
        }
        sLastDriven = shown;
        sLastShade = sExpansion;
        sLastCentre = sCentreExpansion;
    }

    private static float clamp01(float f) {
        return f < 0f ? 0f : f > 1f ? 1f : f;
    }

    // ------------------------------------------------------------------ cover

    /**
     * The cover, from Main's worker. Built into the background and not kept.
     *
     * Not built while the shade features are off, which is how they ship: every push used to
     * build the mesh anyway, on the worker the next push queues behind (26 builds, 1.56s of it
     * in one 53s trace, 2026-10-20). Turning them on with a cover up asks for a push again - see
     * ProbeReceiver's shadecfg and {@link #missingArt}.
     */
    public static void setArt(Bitmap b) {
        if (b != null && !sEnabled) {
            if (ShadeBackdrop.hasArt()) ShadeBackdrop.setArt(null);
            return;
        }
        ShadeBackdrop.setArt(b);
    }

    /**
     * Whether the shade wants a cover it does not have. False while the features are off: the
     * missing background is then not a loss, and a keyguard rebuild must not push the cover again
     * for it.
     */
    public static boolean missingArt() {
        return sEnabled && !ShadeBackdrop.hasArt();
    }

    public static boolean hasArt() {
        return ShadeBackdrop.hasArt();
    }

    /** Whether the cover should outlive cover mode. Read by Main when cover mode ends. */
    public static boolean keepsArt() {
        return sEnabled && sMode == 1;
    }

    /** Drops a cover kept past cover mode once nothing wants it kept. */
    private static void dropIdleArt() {
        if (Main.coverModeOn() || keepsArt()) return;
        setArt(null);
    }

    // ------------------------------------------------------------------ settings

    /**
     * The master switch. Ships off; off hands the shade back to SystemUI completely.
     *
     * Only an install that has never written a state file reads this: a phone's own answer comes
     * back in through shade_enabled (ModuleState.loadState), and that is what wins over the default.
     */
    private static volatile boolean sEnabled = false;
    /**
     * What a pull-down does once cover mode has ended: 0 is SystemUI's own shade, 1 keeps the last
     * cover. Ships as 0, because keeping it means the background outlives the music.
     */
    private static volatile int sMode = 0;

    public static int mode() {
        return sMode;
    }

    /** Settings that no longer exist, still in state files written by older builds. */
    private static final java.util.Set<String> RETIRED = new java.util.HashSet<>(
            java.util.Arrays.asList("gate", "cardblur", "cardradius", "contentpush", "clockpush",
                    "carrierpush", "shadebias", "touch", "maxlag", "stiffness", "damping",
                    "curtainstiffness", "curtaindamping", "blursat", "deadzone", "alphaend",
                    "wprise", "wpblur", "sharpstart"));

    /**
     * One setting by name. Values are clamped here and not in the UI, because adb is a caller too.
     */
    public static void configure(String key, int v) {
        if (key == null || RETIRED.contains(key)) return;
        if ("enabled".equals(key)) {
            final boolean on = v != 0;
            if (on == sEnabled) return;
            sEnabled = on;
            if (!on) {
                dropIdleArt();
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        ShadeBackdrop.hide();
                    }
                });
            }
            Xp.log(TAG + "shade features " + (on ? "enabled" : "disabled"));
        } else if ("mode".equals(key)) {
            // Old builds had three modes; both of the "always" ones map to keeping the cover.
            sMode = v <= 0 ? 0 : 1;
            dropIdleArt();
            Xp.log(TAG + "shade mode = " + sMode);
        } else {
            Xp.log(TAG + "unknown shade setting '" + key + "'");
        }
    }

    /** Every setting, in the order the settings page shows them. */
    public static final String[] CFG_KEYS = {"enabled", "mode"};

    /** A setting's current value, in the units configure() reads. */
    public static int cfgInt(String key) {
        if ("enabled".equals(key)) return sEnabled ? 1 : 0;
        if ("mode".equals(key)) return sMode;
        return 0;
    }

    /** The settings, one line each, for the state file. */
    public static String dumpCfg() {
        final StringBuilder sb = new StringBuilder();
        for (String key : CFG_KEYS) {
            sb.append("\nshade_").append(key).append('=').append(cfgInt(key));
        }
        return sb.toString();
    }
}
