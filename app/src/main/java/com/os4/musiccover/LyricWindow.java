package com.os4.musiccover;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

/**
 * The window the lock screen's lyrics are drawn in.
 *
 * It used to be the lock screen's own window: the view was added to `keyguard_foreground_layer`,
 * beside the clock and the media card. That is what the HDR highlight cannot live with - the
 * colour mode is a whole window's, and the card is in that window, so with the highlight on the
 * card's material is drawn as HDR content and brightens (user, 2026-09-29: "会让媒体通知等控件也变
 * 高光"). Measured before the move: with the mode on, SurfaceFlinger's HDR event log goes
 * `numHdrLayers(1), size(1200x2608), desiredRatio(5.00)` for every held note - the whole window,
 * the whole screen's worth of it - and dropping the headroom request does not help, SurfaceFlinger
 * picks its own ratio (5.00) when none is asked for. So the lyrics get a window of their own and
 * only it goes HDR; the lock screen's window stays SDR, and the card with it.
 *
 * It is a full-screen, untouchable panel sub-window of the lock screen's own window, so it is
 * composited above the lock screen's content and goes where the lock screen goes. The window
 * manager comes from the *application* context (the shade backdrop's lesson: one from a context
 * inside the shade window parents the window to it in ways it does not expect), while the parent
 * is named by the token of a view inside it. Touches fall through to the lock screen exactly as
 * they did when the view was a child of the keyguard, so the two-finger tap and the swipes are
 * untouched (Main.onTwoFingerTap hooks the keyguard's dispatch, not this view).
 *
 * Only with the HDR switch on (LockLyrics.attach); with it off the view is a child of the
 * foreground layer as it always was, because the window is paid for in everything a child gets
 * from the keyguard for free:
 *
 * - z-order, which cannot be given back: a sub-window is above ALL of its parent's content, so
 *   the PIN pad and the shade pulled over the lock screen, which used to cover the lyrics, are
 *   under them now. Under the pad the page fades with the card instead of staying and blurring
 *   (LyricView.swipeFade); the shade over the lock screen fades it through the clock
 *   container's alpha, as before, but what is left of it during the pull is on top;
 * - the keyguard's transforms: the card's container zoom is applied here in full instead of
 *   "the part my ancestors lacked", and the doze zoom (0.95 on keyguard_root_view) is applied
 *   here too (LyricView.followSwipeZoom);
 * - the keyguard's alpha and visibility, read off the layer's ancestors every frame
 *   ({@link #hostAlpha()});
 * - the keyguard's frames: the view's pre-draw is added to the keyguard's tree as well, so it is
 *   stepped on every frame the lock screen draws, as it was as a child ({@link #setHost});
 * - the tree lookups that used to start at this view's own root (the card's container, the
 *   AOD's dim layer) start at {@link #hostRoot()} instead;
 * - window-level state the lock screen's window carries (its surface' fade, its visibility) does
 *   reach this one, a child of it, but not before the OEM hides it at the end of an unlock, so
 *   the lyric page is also gated on the keyguard itself (LockLyrics.wantsWindow).
 */
final class LyricWindow {
    private static final String TAG = "MC lyricwin";

    /**
     * How far above SDR white the window may go: exactly what the text asks for
     * (`LyricView.HDR_GAIN` is 3), and no more. The request is the *display's* ceiling as well,
     * and SurfaceFlinger picks 5.00 on this panel when nothing is asked for.
     */
    private static final float HDR_HEADROOM = 3f;

    private static LyricView sView;
    private static WindowManager sWm;
    private static WindowManager.LayoutParams sLp;
    /** The layer the lyrics used to be added to - all that is left of it is a way into the tree. */
    private static View sHost;
    /** The observer the view's pre-draw was added to, so it can be taken off the same one. */
    private static ViewTreeObserver sHostVto;
    private static boolean sHdr;
    private static int sLayerId;

    private LyricWindow() {
    }

    /**
     * The keyguard layer the lyrics used to live in, kept only as a door into the keyguard's tree:
     * the card's container, the AOD's dim layer and the keyguard's own zoom all live under it.
     * Null while there is no keyguard - every reader has to survive that, and does.
     */
    static View host() {
        View h = sHost;
        if (sView != null && (h == null || !h.isAttachedToWindow())) {
            // The keyguard was rebuilt under the window (a theme or clock-style change inflates a
            // new tree into the same lock screen window): the window is fine, but a layer held
            // from the old tree would answer every lookup with the old tree's detached views -
            // the swipe's fade and zoom and the doze's dim would all stop - until cover mode
            // ended. The clock container is re-found by Main on a rebuild, so ask through it.
            View a = Main.sContainer;
            View l = a == null ? null : foregroundLayer(a);
            if (l != null && l != h && l.isAttachedToWindow()) {
                setHost(l);
                h = l;
            }
        }
        return h;
    }

    /** Whether this view is the one up in the window, rather than one in the keyguard's layer. */
    static boolean owns(View v) {
        return v != null && v == sView;
    }

    /**
     * What the view used to inherit from the keyguard as a child of its foreground layer, and no
     * longer does as a window: the layer's alpha and transition alpha all the way up, and whether
     * it is shown at all. The OEM fades the lock screen through its ancestors - the exit animation
     * on an unlock, among others - and a window that ignored them stood at full strength over a
     * keyguard that had already gone. Swipe fade and doze dim are read separately, from the
     * card's container and keyguard_info_layer, exactly as they were when this was a child.
     */
    static float hostAlpha() {
        View h = host();
        if (h == null) return 1f;
        if (!h.isShown()) return 0f;
        float a = 1f;
        for (Object p = h; p instanceof View; p = ((View) p).getParent()) {
            View v = (View) p;
            a *= v.getAlpha() * v.getTransitionAlpha();
        }
        return Math.max(0f, Math.min(1f, a));
    }

    /**
     * Moves the view's pre-draw onto a (new) host. As a child the view was stepped on every frame
     * the keyguard drew - that is how it followed the clock and the card through their animations
     * without asking for frames of its own; as a window its own pre-draw only runs when it draws
     * itself. Listening to the keyguard's tree as well gives that back.
     */
    private static void setHost(View layer) {
        ViewTreeObserver old = sHostVto;
        LyricView v = sView;
        if (old != null && v != null && old.isAlive()) old.removeOnPreDrawListener(v.preDrawListener());
        sHost = layer;
        sHostVto = null;
        if (layer == null || v == null) return;
        ViewTreeObserver vto = layer.getViewTreeObserver();
        vto.addOnPreDrawListener(v.preDrawListener());
        sHostVto = vto;
    }

    /** The keyguard's own root view, for the lookups the view used to do from its own root. */
    static View hostRoot() {
        View h = host();
        return h == null ? null : h.getRootView();
    }

    /**
     * Brings the lyrics up, or returns the view already up. `anchor` is the clock container: the
     * keyguard's tree is found through it, and nothing else of it is used.
     */
    static LyricView add(View anchor) {
        if (sView != null) {
            host();
            return sView;
        }
        View layer = foregroundLayer(anchor);
        if (layer == null) {
            Xp.log(TAG + "keyguard_foreground_layer not found");
            return null;
        }
        if (anchor.getWindowToken() == null) {
            Xp.log(TAG + "the lock screen's window has no token yet");
            return null;
        }
        try {
            // The application context, as the shade backdrop does: a WindowManager from a context
            // inside the shade window parents the new window to it.
            Context ctx = layer.getContext().getApplicationContext();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                Xp.log(TAG + "no WindowManager");
                return null;
            }
            LyricView v = new LyricView(ctx);
            // A sub-window OF THE LOCK SCREEN'S, not a window of our own floating above the world:
            // the lock screen's window draws its wallpaper itself and its layer covers everything
            // below it, so the first build of this - an application overlay, the shade backdrop's
            // recipe - was invisible on the lock screen and showed over the desktop instead (user,
            // 2026-09-29). As a panel sub-window it is composited above its parent's content, and
            // goes wherever the lock screen goes.
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.setTitle("MusicCoverLyrics");
            // A sub-window is admitted against its parent's window token, and a view inside the
            // lock screen carries it: without this the add is a BadTokenException.
            lp.token = anchor.getWindowToken();
            try {
                WindowManager.LayoutParams.class.getMethod("setTrustedOverlay").invoke(lp);
            } catch (Throwable t) {
                Xp.log(TAG + "setTrustedOverlay unavailable: " + t);
            }
            // Coming up in the middle of a held note: the window is in HDR from its first frame
            // rather than a frame later. The glow needs the canvas' extended range, not a mode of
            // its own, and one frame of it is one frame of a white where there should be a
            // brighter one.
            sHdr = LockLyrics.hdrWanted();
            colourMode(lp, sHdr);
            wm.addView(v, lp);
            sView = v;
            sWm = wm;
            sLp = lp;
            setHost(layer);
            Xp.log(TAG + "window added, hw=" + v.isHardwareAccelerated() + " hdr=" + sHdr);
            return v;
        } catch (Throwable t) {
            setHost(null);
            sView = null;
            sWm = null;
            sLp = null;
            sHdr = false;
            Xp.log(TAG + "window could not be added: " + Log.getStackTraceString(t));
            return null;
        }
    }

    /** Takes the lyrics down: the view, and the window it is the whole of. */
    static void remove() {
        setHost(null);
        LyricView v = sView;
        WindowManager wm = sWm;
        sView = null;
        sWm = null;
        sLp = null;
        sHdr = false;
        if (v == null) return;
        try {
            // Not removeViewImmediate: this is called from the view's own frame callback, and an
            // immediate removal tears the tree down under the traversal that is running.
            if (wm != null) wm.removeView(v);
            Xp.log(TAG + "window removed");
        } catch (Throwable t) {
            Xp.log(TAG + "window remove failed: " + t);
        }
    }

    /**
     * The window's colour mode, which is the whole reason this window exists: on, and the canvas
     * has room above SDR white for the glow; off, and this is an ordinary SDR window - which is
     * what the lock screen's window is either way, so the media card's material never sees it.
     */
    static void setHdr(boolean on) {
        if (sView == null || sLp == null || sHdr == on) return;
        sHdr = on;
        colourMode(sLp, on);
        try {
            sWm.updateViewLayout(sView, sLp);
            Xp.log(TAG + "HDR " + (on ? "on" : "off"));
        } catch (Throwable t) {
            Xp.log(TAG + "HDR update failed: " + t);
        }
    }

    /**
     * HDR asks for headroom and gets a window whose canvas can exceed white; SDR asks for none
     * (1.0 - "extended range brightness is not being used") and puts the default back.
     */
    private static void colourMode(WindowManager.LayoutParams lp, boolean on) {
        lp.setColorMode(on
                ? android.content.pm.ActivityInfo.COLOR_MODE_HDR
                : android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT);
        lp.setDesiredHdrHeadroom(on ? HDR_HEADROOM : 1f);
    }

    /** The keyguard's foreground layer, which is where this view was added before the move. */
    private static View foregroundLayer(View anchor) {
        try {
            int id = sLayerId;
            if (id == 0) {
                id = anchor.getResources().getIdentifier(
                        "keyguard_foreground_layer", "id", "com.android.systemui");
                sLayerId = id;
            }
            View root = anchor.getRootView();
            View layer = id == 0 || root == null ? null : root.findViewById(id);
            return layer instanceof android.view.ViewGroup ? layer : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** For the probe: what this window is, in one line. */
    static String describe() {
        LyricView v = sView;
        if (v == null) return "win=down";
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return "win=up hdr=" + (sHdr ? 1 : 0) + " hw=" + (v.isHardwareAccelerated() ? 1 : 0)
                + " " + v.getWidth() + "x" + v.getHeight() + "@" + loc[0] + "," + loc[1]
                + " scale=" + v.getScaleY() + " pivot=" + (int) v.getPivotX() + "," + (int) v.getPivotY()
                + " cm=" + (sLp == null ? "?" : sLp.getColorMode())
                + " host=" + (sHost == null ? "none" : sHost.isAttachedToWindow() ? "ok" : "stale")
                + " hostA=" + hostAlpha() + " a=" + v.getAlpha();
    }
}
