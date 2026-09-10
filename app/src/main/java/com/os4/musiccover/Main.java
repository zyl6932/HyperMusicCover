package com.os4.musiccover;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
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
import android.text.Layout;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import android.util.Log;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;


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
public class Main extends XposedModule {

    /**
     * The framework instantiates this once per process it injects us into and attaches itself
     * before any callback runs. Everything below is static - the module is one piece of state
     * per process - so the interface is handed to {@link Xp} rather than kept as a field.
     */
    public Main() {
    }


    private static final String TAG = "[MCProbe] ";
    private static final String ACTION = "com.os4.musiccover.PROBE";

    private static final String CLS_CONTAINER =
            "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String CLS_INTERACTOR =
            "com.android.keyguard.interactor.KeyguardClockNotifInteractor";
    private static final String CLS_TIME_VIEW = "com.miui.clock.allInOne.TimeView";
    /** The date line above the clock - the only thing on the lock screen that draws it. */
    private static final String CLS_TEXT_AREA = "com.miui.clock.classic.ClassicTextAreaView";
    private static final String CLS_MEDIA_CARD = "com.android.systemui.statusbar.notification"
            + ".mediacontrol.MiuiMediaNotificationControllerImpl";
    private static final String CLS_KG_WALLPAPER_MANAGER =
            "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager";
    /**
     * The fingerprint ring is drawn twice over, so hiding one of these leaves the other on
     * screen: the frame animation plays the pulsing circle from a list of drawables, and the
     * icon view holds the static print underneath it.
     */
    private static final String CLS_FOD_ANIM =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation";
    private static final String CLS_FOD_ICON =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView";
    /**
     * Where the lock screen's notification stack is allowed to end.
     *
     * SystemUI computes that bound in KeyguardPanelViewController.nsslLockYPosition, a StateFlow
     * combined out of seven other flows. When the sensor is under the display AND the user has
     * fingerprint unlock on AND a print is enrolled, the stack stops just above the fingerprint
     * icon; otherwise it runs down to the indication area. Two of those seven values are that
     * setting and that enrolment, and forcing the pair is how the branch gets chosen.
     *
     * The class doing the combining is a Kotlin lambda that R8 names with a per-build ordinal,
     * so what follows is a shape to search for, never a name to look up. See findAvoidCombine.
     */
    private static final String CLS_KG_PANEL =
            "com.android.keyguard.panel.KeyguardPanelViewController";
    private static final String AVOID_PREFIX = CLS_KG_PANEL
            + "$nsslLockYPosition_delegate$lambda";
    private static final String AVOID_SUFFIX = "$$inlined$combine$1$3";

    /** SystemUI's own keyguard wallpaper manager, for the wallpaper type. See wallpaperKind(). */
    private static volatile Object sKgWallpaperMgr;
    /**
     * Whether the lock wallpaper is a live one, which decides where the cover is drawn: the
     * wallpaper process's GL texture for a still, a view in our own keyguard layer for a live
     * one. See showVideoCover(). Re-read before every push, because the user can change the
     * wallpaper between two songs.
     */
    private static volatile boolean sVideoWallpaper;
    /** The live wallpaper's cut-out subject, hidden alongside deducted_image_view. */
    private static volatile View sVideoFg;
    /** The live wallpaper itself, in the background layer, under our cover. */
    private static volatile View sVideoBg;
    /** The bitmap currently on sCover, ours to recycle when it is replaced. */
    private static volatile Bitmap sCoverBitmap;

    private static volatile View sContainer;
    private static volatile Class<?> sContainerCls;
    /**
     * The enum KeyguardClockContainer.notifStateChange takes as its third argument, read off
     * that method's own signature rather than looked up by name.
     *
     * com.miui.systemui.notification.data.repository.NotificationTopChangeType is only where
     * it lives on the OS4 build this was written against; a HyperOS 3 device reported no such
     * class, and looking it up by name there took the whole module down with it - one enum the
     * clock squeeze needs cost the cover, the media card and the depth hook as well. The
     * signature is what actually has to match, and it carries the class, so ask it.
     */
    private static volatile Class<?> sTypeCls;
    /** notifStateChange(float, boolean, NotificationTopChangeType), or null if it is gone. */
    private static volatile Method sNotifStateChange;
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
     * How light the strip of the cover that the collapsed clock and its date are drawn on is,
     * 0..1. NaN = nothing measured, and every colour the OEM sets is left exactly as it is.
     *
     * Read off the COMPOSED cover - mirrored, blurred, bias-placed - rather than off the album
     * art, because that is the picture the glyphs are actually over: at the default bias the
     * sharp band starts below the clock, so what is behind it is the blur, and on a light cover
     * that blur is light.
     */
    private static volatile float sCoverLuma = Float.NaN;
    /** From a debug op, so both halves of the range can be tried without swapping tracks. */
    private static volatile float sCoverLumaOverride = Float.NaN;
    /** Below this the cover counts as dark, i.e. the glyphs have to go light. */
    private static final float COVER_DARK_BELOW = 0.5f;
    /**
     * Where the glyph's lightness is put once the cover has decided the direction. Hue and
     * saturation are what make the clock look like it belongs; these are the ends that make it
     * readable, chosen far enough apart that a mid-grey cover still resolves to a real contrast.
     */
    private static final float GLYPH_DARK_V = 0.16f, GLYPH_LIGHT_V = 0.95f;
    /**
     * The strip of the cover that gets sampled for that reading, in dp from the top of the
     * screen. Generous on purpose: the date is pinned to DATE_TOP_DP and the collapsed clock
     * hangs under it, and how tall that whole block is depends on the clock style - 56dp clears
     * the status bar and 172dp is past the glyphs on every style measured so far, the point
     * being that a band that overshoots by a few rows still describes what the eye sees there.
     */
    private static final float BAND_TOP_DP = 56f, BAND_BOT_DP = 172f;
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
    /**
     * Hiding the cut-out once is not ownership. The OEM re-shows it from more than one place -
     * updateDeductedImageView is only the one we caught first, and the Folme alpha animators on
     * the same view (setDepthTransitionAlpha / deductedTranslateAlphaFolmeAnimator) reach it
     * without passing through any method we hook. Rather than chase each path, assert the state
     * every frame the keyguard draws, the same way notifY and glassData are asserted: a
     * pre-draw listener on the view itself costs one visibility check per keyguard frame and is
     * blind to whichever route put it back.
     */
    private static ViewTreeObserver.OnPreDrawListener sDepthGuard;
    private static View sDepthGuarded;
    /** How many times the system took the cut-out back, so the log says so without shouting. */
    private static int sDepthTakebacks;
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

    /**
     * The media card, restyled. This is the first thing the module changes about the card
     * itself, and it follows the same ownership rule as the cut-out: the card is rebound on
     * every track change and re-laid out constantly, so the look is asserted every frame from a
     * guard rather than set once.
     *
     * Both are off by default - the card as the OEM draws it is not wrong, only busy - and both
     * apply in cover mode only, which is when the artwork is already the wallpaper and the
     * thumbnail is showing it a second time.
     */
    private static volatile boolean sMcHideArt;
    private static volatile boolean sMcCenterText;
    /**
     * Whether tapping the card's title line toggles playback.
     *
     * It exists because of where the real button is: on this card it sits over the fingerprint
     * sensor, so a thumb aiming at pause is as likely to unlock the phone instead. The title is
     * the one part of the card that is both large and nowhere near the sensor.
     *
     * Applied on the lock screen only, like the two switches above it, and for a sharper reason
     * than consistency: the shade shows the SAME card view, and in the shade the real button
     * works and the title is the OEM's. Held the same way too - asserted from the card guard
     * rather than set once, because the guard is already what tells us the card is still ours.
     */
    private static volatile boolean sMcTitleTap;
    /**
     * Hide the lock screen fingerprint ring. Unlike the two card switches above, this one is not
     * tied to cover mode: an icon that appeared and vanished as the music started and stopped
     * would read as a glitch rather than as a setting.
     */
    private static volatile boolean sHideFp;
    /**
     * Whether the notification stack keeps clear of the fingerprint icon: 0 leaves it to the
     * system, 1 never avoids it, 2 always does. Not a boolean, because "off" here would mean two
     * different things - stop reserving the space, or reserve it even with no print enrolled.
     */
    private static volatile int sFpAvoid;
    /**
     * Every fingerprint icon view built since SystemUI started, weakly held. The alpha is set at
     * construction, but the switch can move afterwards, and a hidden icon has to be able to come
     * back without the user restarting SystemUI.
     */
    private static final java.util.Map<View, Boolean> sFodIcons =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());
    /** resId -> is this one of the ring's frames, so the name lookup happens once per drawable. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> sFodRing =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Where the card lands on the lock screen, in screen pixels. The app draws a preview of the
     * lock screen and cannot measure this itself - the card belongs to SystemUI - so it is
     * sampled here, while the keyguard is actually up, and kept for the app to ask about later.
     */
    private static volatile int sCardL, sCardT, sCardW, sCardH;
    /** The reading waiting to be confirmed by holding still. See sampleCardRect(). */
    private static int sCardSampleL, sCardSampleT, sCardSampleW, sCardSampleH;
    private static long sCardSampleAt;
    private static ViewTreeObserver.OnPreDrawListener sCardGuard;
    private static View sCardGuarded, sCardArt, sCardTitle, sCardArtist;
    /** The title carrying our play/pause listener, and what it was clickable-wise before. */
    private static View sCardTitleTapped;
    private static boolean sCardTitleClickable;
    /** Set only around a capture. See shootCard(). */
    private static volatile boolean sCardForced;
    /**
     * How far the card is into the cover look: 0 is the card as the OEM draws it, 1 is the
     * artwork hidden and the text centred.
     *
     * Deliberately NOT an animator of its own. applyCollapse() already derives a 0..1 progress
     * from the notifY of the frame it is on, and both the clock's collapse scale and the glass
     * morph are read off it; hanging the card off the same number means the OEM's spring drives
     * the thumbnail, the clock and the glass together, and there is nothing left to keep in sync.
     */
    private static volatile float sCardP;
    /** How small the thumbnail gets at the far end of the fade. Apple's shrinks as it goes. */
    private static final float CARD_ART_MIN_SCALE = 0.82f;
    /**
     * The artwork's scale as the OEM has it, and the last one we wrote over it.
     *
     * Sampling one frame of this and writing it back mirrored people's thumbnails. `album_art`
     * carries a scaleX the OEM owns and animates - -1 has been read off it and so has 1 - so
     * no single frame tells you which is its resting value, and the frame we happened to catch
     * when the card was resolved became the value handed back on the way out. When that frame
     * was not the resting one the thumbnail stayed flipped left-for-right: reported as "expand
     * to full screen, tap back to the card, and the thumbnail is reversed".
     *
     * Keeping both numbers is what removes the guess. See scaleArt().
     */
    private static float sArtBaseX = 1f, sArtBaseY = 1f;
    private static float sArtWroteX = Float.NaN, sArtWroteY = Float.NaN;

    /**
     * A single tap on the cover puts the wallpaper back, the way tapping Apple's full-bleed
     * artwork does. Tapping again brings the cover back.
     */
    private static volatile boolean sTapToggle = true;
    /**
     * Whether the wallpaper crossfades between the album cover and the lock wallpaper instead of
     * swapping in one frame. The fade itself runs in the wallpaper process, which is the only
     * place that holds both pictures; all this decides is whether to ask for it.
     */
    private static volatile boolean sFadeWp = true;
    /**
     * Set when the user tapped their way OUT of cover mode while the card was still up.
     *
     * Without it the very next metadata event puts the cover straight back: the card is the
     * switch, and the card has not gone anywhere. Cleared when the card actually goes away, so
     * the next thing the user plays starts from the cover again rather than inheriting a
     * decision that was made about a different song.
     */
    private static volatile boolean sTapSuppressed;
    private static GestureDetector sTapDetector;
    /** Set for the length of one gesture that started on the card's artwork and is ours. */
    private static boolean sArtSwallow;
    private static long sArtDownAt;
    /** Longer than a tap is a press, a drag or a scroll, and none of those mean expand. */
    private static final long ART_TAP_MS = 500L;
    /** Px of forgiveness around the thumbnail. The card slides; fingers are not pixels. */
    private static final int ART_TAP_SLOP = 24;

    /**
     * The OEM's own miuix curves, read off AllInOneClockAnimation at runtime as
     * {dampingRatio, response}. miuix derives stiffness = (2*PI/response)^2 and
     * damping = 2*zeta*(2*PI/response) - confirmed against the dumped parameters[].
     */
    private static final float[] EASE_STATE_CHANGED = {0.88f, 0.38f}; // k=273.4  c=29.1
    private static final float[] EASE_DEFAULT       = {0.82f, 0.42f}; // k=223.8  c=24.5
    private static final float[] EASE_RUNNING       = {1.00f, 0.18f}; // k=1218.5 c=69.8

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.attach(this);
    }

    /**
     * The modern API's equivalent of handleLoadPackage: called once the default class loader
     * exists, before the app's own components are built. The scope list narrows what we are
     * injected into, but the framework still reports every package loaded in those processes,
     * so the package name is checked here rather than trusted.
     */
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        Xp.attach(this);
        String pkg = param.getPackageName();
        if ("com.miui.miwallpaper".equals(pkg)) {
            WallpaperProbe.handle(param);
            return;
        }
        if (!"com.android.systemui".equals(pkg)) return;

        final ClassLoader cl = param.getDefaultClassLoader();
        Xp.log(TAG + "loaded into SystemUI");

        try {
            sContainerCls = Xp.findClass(CLS_CONTAINER, cl);
        } catch (Throwable t) {
            Xp.log(TAG + "KeyguardClockContainer not found, nothing to hook: " + t);
            return;
        }
        // Not fatal, and it used to be. A HyperOS 3 report had no class under the name the
        // enum has here, the lookup threw, and the whole of SystemUI went unhooked - no cover,
        // no media card, no depth - over one enum only the clock squeeze needs. Every hook
        // below now installs on its own terms, and each failure says which feature it cost.
        sNotifStateChange = findNotifStateChange(sContainerCls);
        if (sNotifStateChange != null) {
            sTypeCls = sNotifStateChange.getParameterTypes()[2];
        } else {
            Xp.log(TAG + "no notifStateChange(float, boolean, enum) on " + CLS_CONTAINER
                    + " - the clock squeeze cannot be driven on this build");
        }

        try {
            Xp.hookAll(sContainerCls, "onAttachedToWindow", chain -> {
                Object result = chain.proceed();
                sContainer = (View) chain.getThisObject();
                captureScreenSize(sContainer);
                Xp.log(TAG + "clock container attached: " + sContainer);
                try {
                    registerReceiver(sContainer.getContext().getApplicationContext());
                } catch (Throwable t) {
                    Xp.log(TAG + "registerReceiver failed: " + t);
                }
                // The keyguard is rebuilt on some transitions, taking our cover with it, so
                // re-attach rather than assume the view is still in the tree.
                if (sCoverWanted) {
                    sCover = null;
                    attachCover();
                }
                // The video path's cover is a view, so a keyguard rebuild takes it - and the
                // wallpaper it was covering is hidden, so losing it without putting it back
                // leaves the lock screen blank. Re-compose rather than re-show the old view:
                // the track may have moved on while the screen was off.
                if (sCoverMode && sVideoWallpaper) {
                    sCover = null;
                    pushArtAsync(true, false);
                }
                // Re-apply on keyguard rebuild. Measured: the system never re-shows the
                // cut-out on its own, so no per-call ownership hook is warranted - hooking
                // View.setVisibility process-wide cost every visibility change in SystemUI and
                // fired zero times. What actually lost the state was SystemUI restarting.
                if (sDepthHidden) setDepthHidden(true);
                else if (sVideoWpOwed) setDepthHidden(false);
                // Same story for the card: the guard and the artwork's tap listener both live on
                // the view, so a rebuild takes them.
                if (sCoverMode || wantsArtTap()) applyMediaCard();
                // A rebuilt keyguard can be a different clock style, so the nudge measured
                // against the old one means nothing - and neither does the clock view we resolved.
                sNudgeSample = Float.NaN;
                sClockTargets.clear();
                if (sCoverMode) reassertCoverClock(true);
                return result;
            });
        } catch (Throwable t) {
            // Everything downstream hangs off this one: it is where the container is captured
            // and where the broadcast receiver is registered, so without it there is not even
            // an adb channel to ask what went wrong. Say so plainly rather than throwing out
            // of onPackageLoaded, which loses the message.
            Xp.log(TAG + "onAttachedToWindow hook FAILED, the module is inert in SystemUI: " + t);
        }

        // The keyguard is rebuilt on every screen-off/on, so a captured instance goes
        // stale. Drop it on detach and always drive the currently attached one.
        try {
            Xp.hookAll(sContainerCls, "onDetachedFromWindow", chain -> {
                Object result = chain.proceed();
                if (sContainer == chain.getThisObject()) {
                    sContainer = null;
                    Xp.log(TAG + "clock container detached");
                }
                // Ownership must never outlive the keyguard that granted it. The keyguard
                // is torn down and rebuilt on every screen off/on, and a hold left standing
                // across that boundary rewrites the *new* clock's Y - which is what made the
                // clock size differ between awake/AOD and with/without a media card.
                abandonHold("keyguard torn down", false);
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "onDetachedFromWindow hook failed: " + t);
        }

        // The system does re-show the cut-out - verified: it came back right after we restored
        // cover mode across a SystemUI restart. Re-hide after the OEM's own update instead of
        // hooking View.setVisibility process-wide, which would tax every view in SystemUI.
        try {
            Class<?> depth = Xp.findClass(
                    "com.android.keyguard.depth.KeyguardDepthInteractor", cl);
            Xp.hookAll(depth, "updateDeductedImageView", chain -> {
                Object result = chain.proceed();
                if (sDepthHidden) {
                    try {
                        View d = (View) Xp.getObjectField(chain.getThisObject(),
                                "deductedImageView");
                        if (d != null && d.getVisibility() == View.VISIBLE) {
                            d.setVisibility(View.INVISIBLE);
                            Xp.log(TAG + "depth re-hidden after updateDeductedImageView");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return result;
            });
            Xp.log(TAG + "depth ownership hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "depth ownership hook failed: " + t);
        }

        // The fingerprint ring, when the user has asked for it to go. Two hooks, because the
        // ring is two things: MiuiGxzwFrameAnimation plays the pulsing circle out of a list of
        // drawables, and MiuiGxzwIconView holds the static print underneath. Hiding either one
        // alone leaves the other on screen.
        //
        // Both install whatever the setting says and read the flag per call, so the switch takes
        // effect on the next draw rather than on the next SystemUI restart. And both are on
        // their own terms: a build that renamed one still gets the other.
        try {
            Class<?> anim = Xp.findClass(CLS_FOD_ANIM, cl);
            Xp.hookAll(anim, "draw", chain -> {
                Object[] args = chain.getArgs().toArray();
                // draw(int resId) is the frame. Any other overload is not ours to touch, which
                // the argument check below says without having to name the signature.
                if (sHideFp && args.length == 1 && args[0] instanceof Integer
                        && isFodRing((Integer) args[0])) {
                    // Substituting the drawable rather than skipping the draw: the animation
                    // keeps its own timing and its own lifecycle, it just paints nothing. A
                    // skipped draw would leave whatever the OEM expects to be on that surface.
                    args[0] = android.R.color.transparent;
                }
                return chain.proceed(args);
            });
            Xp.log(TAG + "fingerprint animation hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint animation hook failed, the ring will still pulse: " + t);
        }

        try {
            Class<?> iconCls = Xp.findClass(CLS_FOD_ICON, cl);
            Xp.hookAllConstructors(iconCls, chain -> {
                Object result = chain.proceed();
                try {
                    View v = (View) chain.getThisObject();
                    sFodIcons.put(v, Boolean.TRUE);
                    v.setAlpha(sHideFp ? 0f : 1f);
                } catch (Throwable ignored) {
                    // A view we cannot dim is a visible print, not a broken keyguard.
                }
                return result;
            });
            Xp.log(TAG + "fingerprint icon hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint icon hook failed, the static print will stay: " + t);
        }

        // Whether the notifications keep clear of that icon. Installed whatever the setting is,
        // and the mode is read per call, but unlike everything else here a change does not show
        // up immediately: this value is not one of the seven flows, so the bound is only
        // recomputed when one of those moves.
        try {
            // Fail on the panel rather than on the lambda: "no KeyguardPanelViewController" is a
            // different build, "no combine lambda" is a different R8 run, and the log should say
            // which one happened.
            Xp.findClass(CLS_KG_PANEL, cl);
            long t0 = android.os.SystemClock.uptimeMillis();
            Class<?> combine = findAvoidCombine(cl);
            java.lang.reflect.Method invoke = combine == null ? null : invoke3(combine);
            if (invoke == null) {
                Xp.log(TAG + "no nsslLockYPosition combine lambda on this build"
                        + " - fingerprint avoidance cannot be overridden");
            } else {
                Xp.hook(invoke, chain -> {
                    if (sFpAvoid != 0) {
                        try {
                            java.util.List<Object> a = chain.getArgs();
                            Object second = a.size() > 1 ? a.get(1) : null;
                            if (second instanceof Object[]) {
                                Object[] vals = (Object[]) second;
                                // Exactly seven, or the indices below mean something else. A
                                // build that combines a different number of flows gets left
                                // alone rather than having two unknown values overwritten.
                                if (vals.length == 7) {
                                    Boolean forced = sFpAvoid == 2;
                                    // 5 is "fingerprint unlock is on", 6 is "a print is
                                    // enrolled". Written in place: the array is the one the
                                    // original will read, so proceed() needs no new arguments.
                                    vals[5] = forced;
                                    vals[6] = forced;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                });
                Xp.log(TAG + "fingerprint avoidance hooked on " + combine.getName()
                        + " in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
            }
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint avoidance hook failed: " + t);
        }

        // MIUI's own answer to "what kind of wallpaper is this", which beats every heuristic
        // below it: SystemUI keeps this class unobfuscated and it holds a live
        // MiuiWallpaperManager, whose getMiuiWallpaperType(which) is the API MIUI itself asks.
        // Captured from the constructor the same way the wallpaper engine is - it is a Dagger
        // singleton built during SystemUI startup, so the hook has to be in before that.
        try {
            Class<?> wp = Xp.findClass(CLS_KG_WALLPAPER_MANAGER, cl);
            Xp.hookAllConstructors(wp, chain -> {
                Object result = chain.proceed();
                sKgWallpaperMgr = chain.getThisObject();
                return result;
            });
            Xp.log(TAG + "keyguard wallpaper manager hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "keyguard wallpaper manager hook failed, falling back to "
                    + "MIUI's files for the wallpaper type: " + t);
        }

        // The media card is the switch. Every add, track change and dismissal of the
        // lockscreen card funnels through this one static - MediaData in means a card is on
        // screen (and which track), null means it has gone. Playback state never reaches it,
        // which is exactly what we want: pausing leaves the card up, so it leaves the cover up.
        try {
            Class<?> card = Xp.findClass(CLS_MEDIA_CARD, cl);
            Xp.hookAll(card, "access$setTopMediaData", chain -> {
                Object result = chain.proceed();
                java.util.List<Object> args = chain.getArgs();
                onCardChanged(args.size() > 1 ? args.get(1) : null);
                return result;
            });
            Xp.log(TAG + "media card hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "media card hook failed: " + t);
        }

        // A tap on the cover puts the wallpaper back.
        //
        // Hooked on the shade window's dispatch rather than given a view of our own: anything
        // clickable added over the keyguard swallows the ACTION_DOWN in its region, and
        // swipe-to-unlock is a drag that begins with exactly that ACTION_DOWN. Here the events
        // are only observed - the detector's answer is thrown away and proceed() runs
        // unconditionally - so every gesture still reaches the OEM as it did before.
        try {
            Class<?> shade = Xp.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowView", cl);
            Xp.hookAll(shade, "dispatchTouchEvent", chain -> {
                MotionEvent ev = null;
                try {
                    java.util.List<Object> a = chain.getArgs();
                    if (!a.isEmpty() && a.get(0) instanceof MotionEvent) {
                        ev = (MotionEvent) a.get(0);
                    }
                } catch (Throwable ignored) {
                }
                if (ev != null) {
                    // The one case this hook does more than watch. Returning true without
                    // proceeding takes the gesture out of the dispatch entirely, which is the
                    // only way the artwork can mean something other than "open the player".
                    try {
                        if (swallowArtTap(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    feedTap(ev);
                }
                return chain.proceed();
            });
            Xp.log(TAG + "lock screen tap hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "lock screen tap hook failed: " + t);
        }

        // Track the live container. Ownership is NOT enforced here: the system's own
        // re-assertions come in through KeyguardClockNotifInteractor.setNotifY directly
        // and never pass through this method, so coercing here only produced a race
        // between our value and the system's. The single chokepoint is setNotifY.
        try {
            Xp.hookAll(sContainerCls, "notifStateChange", chain -> {
                // Anyone calling this is by definition the live instance.
                sContainer = (View) chain.getThisObject();
                return chain.proceed();
            });
        } catch (Throwable t) {
            Xp.log(TAG + "notifStateChange hook failed: " + t);
        }

        // The OEM squeezes the clock through these four setters on TimeView. Scaling
        // setSizeInternal's argument shrinks the whole clock without touching the animation:
        // the OEM still computes and drives every frame, we just rescale the last mile.
        try {
            Class<?> timeView = Xp.findClass(CLS_TIME_VIEW, cl);
            XposedInterface.Hooker axisLog = chain -> {
                String name = chain.getExecutable().getName();
                Object[] args = chain.getArgs().toArray();
                boolean scaled = false;
                if ("setSizeInternal".equals(name) && !Float.isNaN(sSizeScale)) {
                    args[0] = ((Float) args[0]) * sSizeScale;
                    scaled = true;
                }
                if (sVerbose) {
                    Xp.log(TAG + "TimeView." + name
                            + "(" + args[0] + ")" + (scaled ? " [scaled]" : "")
                            + " on " + viewIdOf((View) chain.getThisObject()));
                }
                return chain.proceed(args);
            };
            for (String m : new String[]{"setSizeInternal", "setWidth", "setHeight", "setWeight"}) {
                Xp.hookAll(timeView, m, axisLog);
            }

            // Own the colour the way we own the y, but at the far end of the pipeline.
            //
            // Writing glassData and stopping there does nothing: the shader's parameters are
            // pushed by MiuiBlurUtils.setMiGlass/setGlassEffectMethod when a colour is SET, not
            // read out of the array when the view draws. The per-draw write that used to be here
            // was dead for exactly that reason - the OEM's own write of the same array is what
            // ever made a tint show up.
            //
            // So the substitution goes where the OEM's value enters: whichever way SystemUI
            // arrived at a colour - the wallpaper palette, a colour animation, or our own
            // updateGlassValue frame - it reaches a glyph through one of these setters, and
            // none of them has to know anything about the palette.
            Xp.hookAll(timeView, "setGlassColor", chain -> {
                Object[] args = chain.getArgs().toArray();
                args[0] = legible((View) chain.getThisObject(), (Integer) args[0]);
                return chain.proceed(args);
            });
            Xp.hookAll(timeView, "setTextColor", chain -> {
                Object[] args = chain.getArgs().toArray();
                args[0] = legible((View) chain.getThisObject(), (Integer) args[0]);
                return chain.proceed(args);
            });
            // The material's own brightness, which the OEM takes from the same palette and which
            // has to agree with the glyphs: the pale frosted kind goes with dark text, the dark
            // kind with light text, and passing one against the other is a washed-out clock.
            //
            // The argument is the OEM's own flag, and the one it is given here is the value the
            // palette would have handed it for a background as light as the cover - read off
            // AllInOneBase.setClockPalette, which forwards its textDark to this call. Inferred
            // rather than seen: it is the only part of this that a device test has to confirm,
            // and if the small clock comes out washed rather than crisp, this is the line.
            Xp.hookAll(timeView, "setBrightness", chain -> {
                float luma = coverLuma();
                if (Float.isNaN(luma)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = luma >= COVER_DARK_BELOW;
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.log(TAG + "TimeView hook failed: " + t);
        }

        // The date line above the clock. Same palette, same cover behind it, and its own class:
        // the glass never touches it and it takes a plain colour, so it needs a hook of its own
        // rather than riding the TimeView one. Its setter is the only way in - setTextColor
        // forwards to setDateTextColor, which is where the AOD and blur-blend variants are
        // chosen, so hooking the outer one covers every path.
        try {
            Class<?> textArea = Xp.findClass(CLS_TEXT_AREA, cl);
            Xp.hookAll(textArea, "setTextColor", chain -> {
                Object[] args = chain.getArgs().toArray();
                args[0] = legible((View) chain.getThisObject(), (Integer) args[0]);
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.log(TAG + "date colour hook failed: " + t);
        }

        // The real chokepoint. Everything that squeezes the clock - the OEM's own
        // notification-Y flow, KeyguardClockContainer.notifStateChange, and our own
        // per-frame driving - lands here, so this is where ownership is enforced.
        try {
            Class<?> interactor = Xp.findClass(CLS_INTERACTOR, cl);
            Xp.hookAll(interactor, "setNotifY", chain -> {
                Object[] args = chain.getArgs().toArray();
                float requested = (Float) args[0];
                // Only the system's own emissions tell us where the clock really
                // belongs; our own frames must not be mistaken for that.
                if (!sSelfDriving) sLastSystemY = requested;
                Float hold = sHoldY;
                if (hold != null && requested != hold) args[0] = hold;
                applyCollapse((Float) args[0]);
                // The coerced Y has to reach the original, which is what proceed(args) is for:
                // this is the one hook whose whole purpose is rewriting an argument.
                Object result = chain.proceed(args);
                if (sVerbose) {
                    Xp.log(TAG + "setNotifY(" + args[0] + ") -> " + result);
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "interactor hook failed: " + t);
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
                    + "\nglass=" + sGlassEnd
                    + "\nmcart=" + (sMcHideArt ? 1 : 0)
                    + "\nmctext=" + (sMcCenterText ? 1 : 0)
                    + "\nmctap=" + (sMcTitleTap ? 1 : 0)
                    + "\ntap=" + (sTapToggle ? 1 : 0)
                    + "\nfadewp=" + (sFadeWp ? 1 : 0)
                    + "\nhidefp=" + (sHideFp ? 1 : 0)
                    + "\nfpavoid=" + sFpAvoid
                    // Not a setting - a measurement. Kept so the app's preview is to scale from
                    // the first frame after a SystemUI restart, instead of only once the phone
                    // has been locked again.
                    + "\ncardrect=" + sCardL + "," + sCardT + "," + sCardW + "," + sCardH
                    + "\n").getBytes());
            f.close();
        } catch (Throwable t) {
            Xp.log(TAG + "saveState failed: " + t);
        }
    }

    private static final Runnable sSaveState = new Runnable() {
        @Override
        public void run() { saveState(); }
    };

    /**
     * For values that change as fast as the screen draws. The card rectangle is sampled from a
     * pre-draw listener while the card is animating in, and writing the file on every frame of
     * that would be a file write per frame for a number that settles in a few hundred ms.
     */
    private static void saveStateSoon() {
        main().removeCallbacks(sSaveState);
        main().postDelayed(sSaveState, 500L);
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
                    else if ("mcart".equals(k)) sMcHideArt = "1".equals(v);
                    else if ("mctext".equals(k)) sMcCenterText = "1".equals(v);
                    else if ("mctap".equals(k)) sMcTitleTap = "1".equals(v);
                    else if ("tap".equals(k)) sTapToggle = "1".equals(v);
                    else if ("fadewp".equals(k)) sFadeWp = "1".equals(v);
                    else if ("hidefp".equals(k)) sHideFp = "1".equals(v);
                    else if ("fpavoid".equals(k)) sFpAvoid = Integer.parseInt(v);
                    else if ("cardrect".equals(k)) {
                        String[] r = v.split(",");
                        // Same sanity check the sampler applies, because a file written before
                        // it existed can hold a reading taken from the shade.
                        if (r.length == 4 && Integer.parseInt(r[1]) >= sScreenH / 3) {
                            sCardL = Integer.parseInt(r[0]); sCardT = Integer.parseInt(r[1]);
                            sCardW = Integer.parseInt(r[2]); sCardH = Integer.parseInt(r[3]);
                        }
                    }
                    // "offdelay" was the pause timer, before the card became the switch.
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "loadState failed: " + t);
            return;
        }
        Xp.log(TAG + "state restored: cover=" + cover + " bias=" + sBias);
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
                Xp.log(TAG + "recv op=" + op + " extras=" + i.getExtras());
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
                        Xp.log(TAG + "hold -> y=" + y + " from " + currentY()
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
                    } else if ("cluma".equals(op)) {
                        // The cover reading, by hand. Both halves of its range on one track,
                        // instead of waiting for the right artwork to come round.
                        if (i.getBooleanExtra("off", false)) {
                            sCoverLumaOverride = Float.NaN;
                            Xp.log(TAG + "cover luma back to measured " + sCoverLuma);
                        } else {
                            sCoverLumaOverride = i.getFloatExtra("v", 0.8f);
                            Xp.log(TAG + "cover luma forced to " + sCoverLumaOverride
                                    + " (measured " + sCoverLuma + ")");
                        }
                        recolorClock();
                    } else if ("gdata".equals(op)) {
                        pokeGlassData(i.getIntExtra("idx", -1), i.getFloatExtra("v", 0f));
                    } else if ("depth".equals(op)) {
                        setDepthHidden(!i.getBooleanExtra("on", true));
                    } else if ("pushart".equals(op)) {
                        boolean on = i.getBooleanExtra("on", true);
                        if (i.hasExtra("bias")) sBias = clamp01(i.getFloatExtra("bias", sBias));
                        sTrackKey = on ? trackKey(pickController(c)) : "";
                        setCoverEnabled(on, anim, false);
                    } else if ("needart".equals(op)) {
                        // The wallpaper process came up with nothing to draw - see
                        // WallpaperProbe.askForArt(). It only sends this while its own art is
                        // null, and stops the moment one arrives, so this cannot loop.
                        String why = i.getStringExtra("why");
                        if (!sCoverMode) {
                            Xp.log(TAG + "needart (" + why + "), ignored: cover is off");
                        } else {
                            Xp.log(TAG + "needart (" + why + "), resending the art");
                            // Not a track change: take whatever the session has now, without the
                            // same-artwork check that is what swallowed the later pushes.
                            pushArtAsync(true, false);
                        }
                    } else if ("mediacard".equals(op)) {
                        if (i.hasExtra("hideart")) sMcHideArt = i.getBooleanExtra("hideart", false);
                        if (i.hasExtra("centertext")) {
                            sMcCenterText = i.getBooleanExtra("centertext", false);
                        }
                        if (i.hasExtra("titletap")) {
                            sMcTitleTap = i.getBooleanExtra("titletap", false);
                        }
                        saveState();
                        Xp.log(TAG + "media card hideArt=" + sMcHideArt
                                + " centerText=" + sMcCenterText
                                + " titleTap=" + sMcTitleTap);
                        applyMediaCard();
                    } else if ("hidefp".equals(op)) {
                        sHideFp = i.getBooleanExtra("on", !sHideFp);
                        saveState();
                        Xp.log(TAG + "hide fingerprint " + (sHideFp ? "on" : "off"));
                        applyHideFp();
                    } else if ("fpavoid".equals(op)) {
                        sFpAvoid = i.getIntExtra("mode", 0);
                        saveState();
                        // Nothing to re-apply: the bound is recomputed when one of the seven
                        // flows changes and this flag is not one of them, so it lands on the
                        // next recompute - in practice the next time the screen goes off.
                        Xp.log(TAG + "fingerprint avoid mode=" + sFpAvoid
                                + " (applies on the next recompute)");
                    } else if ("fadewp".equals(op)) {
                        sFadeWp = i.getBooleanExtra("on", !sFadeWp);
                        saveState();
                        Xp.log(TAG + "wallpaper crossfade " + (sFadeWp ? "on" : "off"));
                    } else if ("tap".equals(op)) {
                        sTapToggle = i.getBooleanExtra("on", !sTapToggle);
                        saveState();
                        Xp.log(TAG + "tap-to-toggle " + (sTapToggle ? "on" : "off"));
                    } else if ("clockshot".equals(op)) {
                        // For telling the two candidate causes apart by hand: with this off the
                        // module never draws the live clock, and the preview falls back to its
                        // outline. If the lock screen still breaks up, the capture is innocent.
                        sClockShot = i.getBooleanExtra("on", true);
                        Xp.log(TAG + "clock capture " + (sClockShot ? "on" : "off"));
                    } else if ("preview".equals(op)) {
                        setResultExtras(previewBundle(c, i));
                    } else if ("bias".equals(op)) {
                        setBias(i.getFloatExtra("v", DEFAULT_BIAS));
                    } else if ("clockscale".equals(op)) {
                        sClockScale = clamp01(i.getFloatExtra("v", DEFAULT_CLOCK_SCALE));
                        if (sClockScale < 0.05f) sClockScale = 0.05f;
                        saveState();
                        Xp.log(TAG + "clock scale = " + sClockScale);
                        if (sCoverMode) { sCollapseMin = sClockScale; sAppliedK = Float.NaN;
                            reassertCoverClock(); }
                    } else if ("glassend".equals(op)) {
                        sGlassEnd = clamp01(i.getFloatExtra("v", DEFAULT_GLASS_END));
                        saveState();
                        Xp.log(TAG + "glass end = " + sGlassEnd);
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
                            Xp.log(TAG + "glass morph off");
                        } else {
                            sGlassV0 = i.getFloatExtra("v0", 0f);
                            sGlassV1 = i.getFloatExtra("v1", 1f);
                            Xp.log(TAG + "glass morph " + sGlassV0 + " -> " + sGlassV1);
                        }
                    } else if ("collapse".equals(op)) {
                        float k = i.getFloatExtra("k", -1f);
                        sCollapseMin = k > 0 ? k : Float.NaN;
                        Xp.log(TAG + "collapse min scale = " + sCollapseMin);
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
                        Xp.log(TAG + "size scale = " + sSizeScale);
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
                        out.putBoolean("mcart", sMcHideArt);
                        out.putBoolean("mctext", sMcCenterText);
                        out.putBoolean("mctap", sMcTitleTap);
                        out.putBoolean("tap", sTapToggle);
                        out.putBoolean("fadewp", sFadeWp);
                        out.putBoolean("hidefp", sHideFp);
                        out.putInt("fpavoid", sFpAvoid);
                        // Everything the app's preview needs to be to scale. It draws a lock
                        // screen it cannot see, and every one of these is device-specific, so
                        // they are measured here rather than written down twice.
                        out.putInt("sw", sScreenW);
                        out.putInt("sh", sScreenH);
                        out.putInt("cardl", sCardL);
                        out.putInt("cardt", sCardT);
                        out.putInt("cardw", sCardW);
                        out.putInt("cardh", sCardH);
                        float[] cg = clockGeometry();
                        if (cg != null) {
                            out.putFloat("clockw", cg[0]);
                            out.putFloat("clockh", cg[1]);
                            out.putFloat("clocky", cg[2]);
                            out.putFloat("clockpad", CLOCK_PAD);
                            out.putFloat("clockx", cg[3]);
                            out.putFloat("clockpivotx", cg[4]);
                        }
                        setResultExtras(out);
                    } else if ("diag".equals(op)) {
                        String report = clockReport();
                        android.os.Bundle rb = new android.os.Bundle();
                        rb.putString("report", report);
                        setResultExtras(rb);
                        // Logged as well as answered: an adb run of this op should not have to
                        // go through the app to see it.
                        for (String line : report.split("\n")) Xp.log(TAG + line);
                    } else if ("bounds".equals(op)) {
                        dumpClockBounds();
                    } else if ("state".equals(op)) {
                        Xp.log(TAG + "state: holdY=" + sHoldY + " currentY=" + sCurrentY
                                + " lastSystemY=" + sLastSystemY + " animating=" + (sFrameCb != null || sRamp != null)
                                + " container=" + (sContainer != null) + " verbose=" + sVerbose);
                        Xp.log(TAG + "cover: on=" + sCoverMode + " auto=" + sAuto
                                + " bias=" + sBias + " screen=" + sScreenW + "x" + sScreenH
                                + " card=" + (sCardKnown ? (sCardShowing ? sCardKey : "gone") : "unknown")
                                + " following=" + (sWatched == null ? "none" : sWatched.getPackageName())
                                + " track=" + sTrackKey);
                        Xp.log(TAG + "card rect: " + sCardL + "," + sCardT + " "
                                + sCardW + "x" + sCardH + " hideArt=" + sMcHideArt
                                + " centerText=" + sMcCenterText
                                + " titleTap=" + sMcTitleTap
                                + " title=" + (sCardTitleTapped != null) + " cardP=" + sCardP);
                        Xp.log(TAG + "tap: toggle=" + sTapToggle
                                + " suppressed=" + sTapSuppressed
                                + " bouncer=" + bouncerUp()
                                + " wallpaperFade=" + sFadeWp);
                    } else if ("verbose".equals(op)) {
                        sVerbose = i.getBooleanExtra("on", !sVerbose);
                        Xp.log(TAG + "verbose=" + sVerbose);
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
                        Xp.log(TAG + "release -> y=" + back
                                + (ms > 0 ? " over " + ms + "ms" : " spring zeta=" + zeta + " response=" + response));
                        if (!anim) { abandonHold("release"); drive(back, false, type); }
                        else if (ms > 0) rampTo(back, ms, type, true);
                        else springTo(back, zeta, response, type, true);
                    } else {
                        abandonHold("raw drive");
                        drive(i.getFloatExtra("y", -1f), anim, type);
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        Xp.log(TAG + "receiver registered for " + ACTION);
        loadState();
        // The icon views can already exist by now - the file is read when the keyguard attaches,
        // which is not necessarily before the fingerprint view is built.
        applyHideFp();

        // Which kind of wallpaper is on the lock screen decides where the cover is drawn, and
        // cover mode can be restored from disk before any push has had a chance to work it
        // out. Off the main thread: it reads MIUI's files.
        final Context appCtx = ctx;
        worker().post(new Runnable() {
            @Override
            public void run() {
                String k = wallpaperKind(appCtx, "lock");
                sVideoWallpaper = k != null && !KIND_IMAGE.equals(k);
                if (sVideoWallpaper && sDepthHidden) main().post(new Runnable() {
                    @Override
                    public void run() { setDepthHidden(true); }
                });
            }
        });

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
                    // Waking re-runs the OEM's depth pipeline, and if the keyguard was rebuilt
                    // while the screen was off the guard went away with the old view.
                    if (sDepthHidden) setDepthHidden(true);
                    // A hand-back of the live wallpaper that had to wait for a lock screen.
                    else if (sVideoWpOwed) setDepthHidden(false);
                    if (sCoverMode) applyMediaCard();
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
                    Xp.log(TAG + "screen off, cover mode keeps the clock held");
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
        Xp.log(TAG + "lifecycle receiver registered (screen off / user present)");
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
                || !Float.isNaN(sCollapseMin) || !Float.isNaN(sCoverLuma);
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
        // The cover's colouring goes back with the rest of cover mode, through the same call
        // that put it there: with nothing measured, the setter hooks hand the OEM's own colours
        // straight through. Without this the date would stay dark over the wallpaper the cover
        // was hiding - the one way this could leave the lock screen worse than it found it.
        if (!Float.isNaN(sCoverLuma)) {
            sCoverLuma = Float.NaN;
            recolorClock();
        }
        if (!Float.isNaN(sGlassV0)) {
            float back = sGlassV0;
            sGlassV0 = sGlassV1 = Float.NaN;
            sAppliedGlassV = Float.NaN;
            callOnClockViews("updateGlassValue", "f", back, 0, false);
        }
        // The card is part of the same state as the clock: cover mode takes the thumbnail
        // and the text along with the squeeze, and gives all three back together. Releasing it
        // here rather than in exitCoverMode() is what lets the animated exit keep the guard
        // installed for the whole flight - the spring walks sCardP down to 0, and this is the
        // frame after it lands.
        // Not while our own animator owns the progress: it is walking the card back frame by
        // frame and will hand it over itself when it lands.
        if (!sCoverMode && sCardP != 0f && sCardAnim == null) {
            sCardP = 0f;
            applyMediaCard();
        }
        if (!held) return;
        Xp.log(TAG + "hold abandoned: " + why);
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
            Xp.log(TAG + "no clock container captured yet");
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
                        Xp.log(TAG + "no Y observed yet, snapping to " + target);
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
                            Xp.log(TAG + "spring settled in " + ms + "ms, holding at " + target);
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
            Xp.log(TAG + "no clock container captured yet");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                stopMotion();
                final float from = currentY();
                if (Float.isNaN(from)) {
                    Xp.log(TAG + "no Y observed yet, snapping to " + target);
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
                            Xp.log(TAG + "ramp done, holding at " + target);
                        }
                    }
                });
                sRamp = a;
                a.start();
            }
        });
    }

    /**
     * The clock squeeze's one entry point, resolved by shape.
     *
     * Matching on (float, boolean, enum) rather than on the enum's fully qualified name: the
     * name moved between HyperOS versions and the shape did not, and the only thing this
     * module ever does with the enum is hand a constant of it straight back.
     */
    private static Method findNotifStateChange(Class<?> container) {
        for (Method m : container.getDeclaredMethods()) {
            if (!"notifStateChange".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == float.class && p[1] == boolean.class && p[2].isEnum()) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** The named constant of that enum, or its first one if the names have moved too. */
    private static Object topChangeType(String name) {
        Class<?> c = sTypeCls;
        if (c == null) return null;
        Object[] all = c.getEnumConstants();
        if (all == null || all.length == 0) return null;
        for (Object o : all) {
            if (((Enum<?>) o).name().equals(name)) return o;
        }
        Xp.log(TAG + "no " + name + " on " + c.getName() + ", falling back to "
                + ((Enum<?>) all[0]).name());
        return all[0];
    }

    /** Applies one frame. Must already be on the view's thread. */
    private static void applyY(float y, String typeName) {
        View v = sContainer;
        Method m = sNotifStateChange;
        if (v == null || m == null) return;
        try {
            Object type = topChangeType(typeName);
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
            // The card rides our own frames as well as the OEM's chokepoint, because on some
            // clock styles there IS no chokepoint: notifStateChange never reaches
            // KeyguardClockNotifInteractor.setNotifY on the classic style, so applyCollapse()
            // is never called and the card would sit at whatever progress it started the
            // animation with. Measured on device with the classic style selected: setNotifY
            // fired zero times across a full enter and exit, and the two card settings simply
            // stopped working. Writing it here costs a comparison when the hook got there first.
            float p = coverProgress(y);
            if (!Float.isNaN(p)) setCardProgress(p);
        } catch (Throwable t) {
            Xp.log(TAG + "applyY failed: " + Log.getStackTraceString(t));
        }
    }

    private static void drive(final float y, final boolean anim, final String typeName) {
        final View v = sContainer;
        final Method m = sNotifStateChange;
        if (v == null) {
            Xp.log(TAG + "no clock container captured yet");
            return;
        }
        if (m == null) {
            Xp.log(TAG + "no notifStateChange on this build, cannot drive the clock");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object type = topChangeType(typeName);
                    sSelfDriving = true;
                    try {
                        m.invoke(v, y, anim, type);
                    } finally {
                        sSelfDriving = false;
                    }
                    sCurrentY = y;
                    Xp.log(TAG + "notifStateChange(" + y + ", " + anim + ", " + typeName + ") OK");
                } catch (Throwable t) {
                    Xp.log(TAG + "drive failed: " + Log.getStackTraceString(t));
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
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier("hour_view", "id", "com.android.systemui");
                    View t = v.getRootView().findViewById(id);
                    if (t == null) { Xp.log(TAG + "hour_view not found"); return; }
                    // setClockParams()'s argument order is not (width, height, weight) - feeding
                    // it the fields' own values rotated them - so drive the three axes through
                    // their individual setters instead, where the mapping is unambiguous.
                    if (size >= 0) Xp.callMethod(t, "setSize", size);
                    // setSize(int) only stores the value; textSizePx - the actual point size,
                    // and the knob that reaches well below the font axes' floor - comes from here.
                    if (sizeInternal >= 0) Xp.callMethod(t, "setSizeInternal", sizeInternal);
                    if (w >= 0) Xp.callMethod(t, "setWidth", w);
                    if (h >= 0) Xp.callMethod(t, "setHeight", h);
                    if (weight >= 0) Xp.callMethod(t, "setWeight", weight);
                    t.invalidate();
                    RectF b = (RectF) Xp.getObjectField(t, "mTextBounds");
                    Xp.log(TAG + "axes width=" + Xp.getObjectField(t, "width")
                            + " height=" + Xp.getObjectField(t, "height")
                            + " weight=" + Xp.getObjectField(t, "weight")
                            + " size=" + Xp.getObjectField(t, "size")
                            + " textSizePx=" + Xp.getObjectField(t, "textSizePx")
                            + " -> glyphs " + Math.round(b.width()) + "x" + Math.round(b.height()));
                } catch (Throwable e) {
                    Xp.log(TAG + "setClockParams failed: " + Log.getStackTraceString(e));
                }
            }
        });
    }

    /** Dumps the declared API of one view in the clock tree, to find the size knobs. */
    private static void dumpApi(String idName) {
        View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        if (idName == null) idName = "hour_view";
        int id = v.getContext().getResources().getIdentifier(idName, "id", "com.android.systemui");
        View target = id == 0 ? null : v.getRootView().findViewById(id);
        // "clock" means the AllInOneHourClock/AllInOneMinuteClock view itself, which carries no id.
        if ("clock".equals(idName)) {
            View[] roots = clockRoots();
            target = roots.length == 0 ? null : ((android.view.ViewGroup) roots[0]).getChildAt(0);
        }
        if (target == null) { Xp.log(TAG + idName + " not found"); return; }
        Class<?> c = target.getClass();
        Xp.log(TAG + idName + " = " + c.getName());
        for (; c != null && c != View.class; c = c.getSuperclass()) {
            Xp.log(TAG + "--- " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder("  ").append(m.getReturnType().getSimpleName())
                        .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int k = 0; k < ps.length; k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(ps[k].getSimpleName());
                }
                Xp.log(TAG + sb.append(')'));
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
                Xp.log(TAG + "  ." + f.getName() + " = " + val);
            }
        }
    }

    private static void dumpViewTree(boolean fromRoot) {
        View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container");
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
            Class<?> tv = Xp.findClass(CLS_TIME_VIEW, sContainerCls.getClassLoader());
            Class<?> info = tv.getDeclaredMethod("getClockStyleInfo").getReturnType();
            Xp.log(TAG + "ClockStyleInfo = " + info.getName());
            for (Class<?> c = info; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    try {
                        Xp.log(TAG + "  " + c.getSimpleName() + "." + f.getName()
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
                if (ms.length() > 0) Xp.log(TAG + "  " + c.getSimpleName() + " methods: " + ms);
            }
        } catch (Throwable t) {
            Xp.log(TAG + "dumpClockStyleInfo failed: " + Log.getStackTraceString(t));
        }
    }

    /** Dumps a class by name so we can find OEM entry points without pulling the dex apart. */
    private static void dumpClass(String name, String grep) {
        ClassLoader cl = sContainerCls == null ? null : sContainerCls.getClassLoader();
        Class<?> c;
        try {
            c = Xp.findClass(name, cl);
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
                String line = sb.toString();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + line);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + line);
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
                            .append(Xp.callMethod(v, "getTextBoundsWithPosition"))
                            .append(" drawY=").append(Xp.callMethod(v, "getDrawTextY"))
                            .append(" textTop=").append(Xp.callMethod(v, "getTextTop"));
                } catch (Throwable ignored) {
                }
                Xp.log(TAG + sb);
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
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
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
                            float[] g = (float[]) Xp.getObjectField(t, "glassData");
                            if (g == null) continue;
                            if (idx >= 0 && idx < g.length) g[idx] = value;
                            t.invalidate();
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < g.length; j++) {
                                if (j > 0) sb.append(", ");
                                sb.append(j).append(':').append(g[j]);
                            }
                            Xp.log(TAG + viewIdOf(root) + "/" + id + " glassData=[" + sb + "]");
                        } catch (Throwable e) {
                            Xp.log(TAG + "pokeGlassData " + id + ": " + e);
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
        float p = coverProgress(y);
        if (Float.isNaN(p)) return;
        // Same progress, three consumers: the collapse scale below, the glass morph, and
        // the card. One OEM spring drives all of them and none of them owns a clock of its own.
        setCardProgress(p);
        if (Float.isNaN(min)) { applyGlassMorph(p); return; }
        applyGlassMorph(p);
        float k = 1f - p * (1f - min);
        sAppliedK = k;
        placeCollapsedClock(k, y, p);
    }

    /**
     * How far into the cover-mode look a given notifY is, 0..1, or NaN while there is no
     * natural Y to measure it against.
     */
    private static float coverProgress(float y) {
        float natural = sLastSystemY;
        if (Float.isNaN(natural) || natural <= SQUEEZE_FLOOR) return Float.NaN;
        float p = (natural - y) / (natural - SQUEEZE_FLOOR);
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    /**
     * The view a clock style wants scaled, in one tree, or null if this tree draws no time.
     *
     * Two shapes exist on this device and they have nothing in common:
     *
     * - **all_in_one** (the OEM's variable-font clock): the digits are TimeViews inside a
     *   `time_group`, one tree drawing the hour and the other the minute.
     * - **classic**: one `time_view`, a `com.miui.clock.MiuiTextGlassView`, which is an ordinary
     *   TextView holding "14:26" - no group, no TimeViews, and only in the background tree.
     *
     * Everything the collapse does works on whichever of the two is there, so this is the only
     * place that has to know the difference. (A style that has neither takes the clock takeover
     * out of play entirely rather than throwing: the date, and the wallpaper, still work.)
     */
    private static View clockTarget(View root) {
        // Always tried first, and never cached: one resource lookup and a findViewById, and
        // answering from a cache here is how a style change would go unnoticed.
        View g = findClockView(root, "time_group");
        if (usable(g)) return g;
        View cached = sClockTargets.get(root);
        // The root standing in for itself means "this tree draws no clock" - some styles put the
        // whole clock in one tree and only the date in the other, and without remembering that,
        // every frame of the squeeze would walk a whole layer to fail to find one.
        if (cached == root) return null;
        if (usable(cached) && cached.getParent() != null) return cached;
        View found = resolveTimeTarget(root);
        sClockTargets.put(root, found == null ? root : found);
        return found;
    }

    /**
     * Finds whatever draws the time, without knowing what the style calls it.
     *
     * Looking it up by id does not scale: `time_group` and `hour_view` belong to all_in_one,
     * `time_view` to the classic style, and the user has more styles than that. What every style
     * does have in common is a view that draws a clock face, and there are only two kinds:
     *
     * - one of com.miui.clock's own TimeViews, recognisable because it can report the bounds of
     *   the glyphs it strokes (`getTextBoundsWithPosition`);
     * - an ordinary TextView holding something that reads as a time and nothing else.
     *
     * When several turn up - a style that draws the hour and the minute as separate views - the
     * one to scale is their common parent, so they shrink together. Unless that parent also
     * holds the date, which would drag the date along with them; then the first one is scaled on
     * its own, which is wrong-ish but visibly wrong rather than silently absent.
     */
    private static View resolveTimeTarget(View root) {
        java.util.List<View> hits = new java.util.ArrayList<>();
        collectTimeViews(root, hits);
        if (hits.isEmpty()) return null;
        if (hits.size() == 1) return hits.get(0);
        View top = hits.get(0);
        for (int guard = 0; guard < 12 && top != null; guard++) {
            if (containsAll(top, hits)) break;
            top = top.getParent() instanceof View ? (View) top.getParent() : null;
        }
        if (top == null || !containsAll(top, hits)) return hits.get(0);
        View date = visibleDate();
        return date != null && isDescendant(top, date) ? hits.get(0) : top;
    }

    private static void collectTimeViews(View v, java.util.List<View> out) {
        if (!usable(v)) return;
        if (isTimeView(v)) {
            out.add(v);
            return;
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collectTimeViews(g.getChildAt(i), out);
    }

    private static boolean isTimeView(View v) {
        try {
            v.getClass().getMethod("getTextBoundsWithPosition");
            return true;
        } catch (Throwable ignored) {
        }
        return v instanceof TextView && looksLikeTime(((TextView) v).getText());
    }

    /**
     * "14:51", "14", "9:05" - and not "9月9日周三 七月廿八", "9/9", "32°" or a signature.
     * Digits and colons only, and short: the date lines on these styles are none of that.
     */
    private static boolean looksLikeTime(CharSequence cs) {
        if (cs == null) return false;
        String t = cs.toString().trim();
        if (t.isEmpty() || t.length() > 5) return false;
        boolean digit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') { digit = true; continue; }
            if (c == ':' || c == '\uFF1A') continue;
            return false;
        }
        return digit;
    }

    private static boolean containsAll(View ancestor, java.util.List<View> views) {
        for (View v : views) {
            if (!isDescendant(ancestor, v)) return false;
        }
        return true;
    }

    private static boolean isDescendant(View ancestor, View v) {
        for (int guard = 0; guard < 32 && v != null; guard++) {
            if (v == ancestor) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    /**
     * Resolved clock views, per tree. This is asked for on every frame of the squeeze, and
     * resolving it means a resource lookup and possibly a walk of the whole layer.
     *
     * Dropped when the keyguard is rebuilt, which is also when the clock style can have changed
     * underneath us - see the onAttachedToWindow hook.
     */
    private static final java.util.WeakHashMap<View, View> sClockTargets =
            new java.util.WeakHashMap<>();
    /** The date view the cache above was filled against. See placeCollapsedClock(). */
    private static View sDateView;
    private static boolean sClockComplained;

    /**
     * Says, once per style, why a clock style could not be taken over.
     *
     * This runs on every frame of the squeeze, so it cannot log freely - but a style it cannot
     * read is exactly the thing that needs reporting, and asking the user to reproduce it with a
     * dump is worse than having the answer already in the log. Reset whenever a placement
     * succeeds, so the next broken style speaks up again.
     */
    private static void reportUnknownClock(View date, RectF pooled) {
        if (sClockComplained) return;
        sClockComplained = true;
        StringBuilder sb = new StringBuilder("clock style not understood: date=")
                .append(date == null ? "MISSING" : "ok").append(" glyphs=")
                .append(pooled == null ? "MISSING" : pooled.toString());
        for (View root : clockRoots()) {
            sb.append("\n  tree ").append(idOf(root)).append(':');
            describeClockTree(root, sb, 0);
        }
        Xp.log(TAG + sb);
    }

    /** Anything on the tree that carries text or draws glyphs, which is all this needs to see. */
    private static void describeClockTree(View v, StringBuilder sb, int depth) {
        if (depth > 8 || v == null) return;
        boolean text = v instanceof TextView && ((TextView) v).getText() != null
                && ((TextView) v).getText().length() > 0;
        boolean glyphs = isTimeView(v);
        if (text || glyphs) {
            sb.append("\n    ").append(v.getClass().getSimpleName()).append(" #").append(idOf(v))
              .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
              .append(" vis=").append(v.getVisibility());
            if (text) sb.append(" \"").append(((TextView) v).getText()).append('"');
            if (glyphs) sb.append(" [timeview]");
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) describeClockTree(g.getChildAt(i), sb, depth + 1);
    }

    private static String idOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "no-id";
        }
    }

    private static boolean usable(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.getWidth() > 0;
    }

    private static View findVisibleByName(View v, String name) {
        if (usable(v)) {
            try {
                if (v.getId() != View.NO_ID
                        && name.equals(v.getResources().getResourceEntryName(v.getId()))) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!(v instanceof ViewGroup) || v.getVisibility() != View.VISIBLE) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findVisibleByName(g.getChildAt(i), name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * The box the digits actually ink, in the target view's own coordinates.
     *
     * Every clock style lays its glyphs out somewhere else - the single-line style puts hour and
     * minute side by side, the stacked one draws the hour at 650 and the minute at 909, the
     * classic one is a single left-aligned line - so this is measured, never assumed.
     */
    private static RectF inkBox(View root, View target) {
        if (target == null) return null;
        RectF box = null;
        java.util.List<View> times = new java.util.ArrayList<>();
        collectTimeViews(target, times);
        for (View v : times) {
            RectF r = null;
            try {
                r = (RectF) Xp.callMethod(v, "getTextBoundsWithPosition");
            } catch (Throwable ignored) {
            }
            if (r == null || r.height() <= 0f) {
                r = v instanceof TextView ? textInkBox((TextView) v) : null;
                if (r == null) continue;
            } else {
                r = new RectF(r);
            }
            // Offsets accumulated up to the target, not just one level: a style is free to wrap
            // its digits in as many layouts as it likes between the two.
            for (View p = v; p != null && p != target; ) {
                r.offset(p.getLeft(), p.getTop());
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
            if (box == null) box = r; else box.union(r);
        }
        return box;
    }

    /**
     * The same measurement for a plain TextView, which is what the classic style's clock is.
     *
     * Paint.getTextBounds gives the ink relative to the baseline origin, so it has to be put
     * back together with the layout's baseline and the view's padding. Not getLineTop/Bottom:
     * those are the line box, ascent and descent included, and anchoring the collapse to a line
     * box instead of to the ink is exactly the mistake section 13 is about.
     */
    private static RectF textInkBox(TextView t) {
        Layout lay = t.getLayout();
        CharSequence cs = t.getText();
        if (lay == null || lay.getLineCount() < 1 || cs == null || cs.length() == 0) return null;
        String txt = cs.toString();
        Rect ink = new Rect();
        t.getPaint().getTextBounds(txt, 0, txt.length(), ink);
        if (ink.width() <= 0 || ink.height() <= 0) return null;
        float x = t.getPaddingLeft() + lay.getLineLeft(0) + ink.left;
        float base = t.getPaddingTop() + lay.getLineBaseline(0);
        return new RectF(x, base + ink.top, x + ink.width(), base + ink.bottom);
    }

    /**
     * The union of the drawn digits across both trees, in the target's coordinates.
     *
     * The top anchors the placement, the middle decides the pivot, and the size is what the
     * preview draws at - all three off this one pooled measurement.
     */
    private static RectF glyphBox() {
        RectF box = null;
        for (View root : clockRoots()) {
            View target = clockTarget(root);
            RectF r = inkBox(root, target);
            // A tree that HAS a clock in it but shows no ink yet is a clock that has not been
            // through a layout pass, not a tree without a clock - that one has no usable target
            // at all. Reported as "no box" rather than unioned away, because half a box is what
            // makes clockPivotX() take the left-aligned branch: all_in_one draws the hour in one
            // tree and the minute in the other, so the half that happens to be measured first
            // has a centre nowhere near the screen's, and the clock collapses towards its own
            // left edge. That is the crooked small clock a SystemUI restart used to leave behind.
            if (r == null && usable(target)) return null;
            if (r == null) continue;
            if (box == null) box = r; else box.union(r);
        }
        return box;
    }

    /**
     * {width, height, screen y of the top} for the collapsed clock at scale 1, for the app's
     * preview. Multiplying the first two by the clock scale gives exactly what
     * placeCollapsedClock() puts on screen: it pivots on the glyph top, so the top edge does
     * not move with the scale and this y holds for every scale.
     *
     * Derived the same way placement is - the date at its fixed target, the clock a gap below
     * it - so the preview follows the rule instead of a second set of numbers that can drift.
     */
    /**
     * {w, h, y, x, pivotX} for the collapsed clock at scale 1, in screen pixels.
     *
     * The box is the padded one, because that is what the captures cover; y and pivotX are the
     * two anchors the phone actually scales about - the glyph top and clockPivotX() - so the app
     * can reproduce the same transform: `left = pivotX + (x - pivotX) * scale`. Centred styles
     * come out centred and left-aligned ones stay left, without the app knowing which is which.
     */
    private static float[] clockGeometry() {
        View date = visibleDate();
        RectF box = paddedGlyphBox();
        if (date == null || box == null) return null;
        View g = null;
        for (View root : clockRoots()) {
            View t = clockTarget(root);
            if (t != null) { g = t; break; }
        }
        if (g == null) return null;
        float d = date.getResources().getDisplayMetrics().density;
        return new float[]{box.width(), box.height(),
                DATE_TOP_DP * d + date.getHeight() + CLOCK_GAP_DP * d,
                g.getLeft() + box.left, g.getLeft() + clockPivotX(g, glyphBox())};
    }

    private static RectF paddedGlyphBox() {
        RectF box = glyphBox();
        if (box == null) return null;
        box.inset(-CLOCK_PAD, -CLOCK_PAD);
        return box;
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
    /**
     * Where cover mode puts the date, in dp from the top of the screen.
     *
     * A fixed target rather than a "don't collide with the status bar" rule, because the OEM's
     * squeeze leaves each clock style's date at a different height and they should all agree.
     * The value is where the single-line style already lands - measured 240px for the glyphs on
     * this 1200x2608 480dpi screen, i.e. 228px for the view that draws them - while the stacked
     * style was arriving 61px higher.
     *
     * Deliberately not derived from status_bar_height: that resource reads 182px here, which is
     * the whole cutout band rather than anything the eye lines up against.
     */
    private static final float DATE_TOP_DP = 76f;

    /** The offset in force. Held across frames on purpose - see updateDateOffset(). */
    private static volatile float sNudge;
    /** Previous raw reading, to tell a settled one from a stale one. */
    private static float sNudgeSample = Float.NaN;

    /**
     * How far the whole group has to move so the date lands on the target, in px.
     *
     * The OEM translates the clock group up as it squeezes, and how far depends on the style, so
     * left alone the date ends up at a different height for each one - the glyphs land at 240px
     * on the single-line style and 179px on the stacked one. This pulls them onto the same line.
     *
     * The subtlety is WHEN it can be measured. We run from the setNotifY hook, and the OEM
     * applies the frame's translation only after setNotifY returns - so a reading taken here is
     * always one frame behind, and on the first frames after waking from AOD it still describes
     * the AOD layout. Acting on that reading is exactly what threw the clock to the top of the
     * screen before it slid back down.
     *
     * So this is not recomputed from whatever the last frame happened to look like. It is only
     * adopted once two consecutive readings agree, which means the OEM has stopped moving and the
     * geometry being measured is the settled one. Until then the value already in force keeps
     * being used, which is the right answer anyway: it was measured on the same clock in the same
     * state before the screen went off.
     */
    private static void updateDateOffset(View date, float p) {
        // Only the fully collapsed state is worth measuring; anything else is mid-animation.
        if (p < 0.999f) return;
        int[] loc = new int[2];
        date.getLocationOnScreen(loc);
        float uncorrected = loc[1] - date.getTranslationY();
        float target = DATE_TOP_DP * date.getResources().getDisplayMetrics().density;
        float measured = target - uncorrected;
        boolean adopt = !Float.isNaN(sNudgeSample) && Math.abs(measured - sNudgeSample) < 1f;
        if (adopt) sNudge = measured;
        sNudgeSample = measured;
        if (sVerbose) {
            Xp.log(TAG + "nudge: onScreen=" + loc[1] + " ty=" + r1(date.getTranslationY())
                    + " measured=" + r1(measured) + (adopt ? " ADOPTED" : " held"));
        }
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
    private static boolean placeCollapsedClock(float k, float y, float p) {
        View date = visibleDate();
        // A different date view means the clock views were re-inflated - a style change - and
        // anything remembered about the old ones describes a clock that is no longer there.
        // Cheaper and more reliable than watching for the style setting itself, and it catches
        // the case where the container was reused rather than re-attached.
        if (date != sDateView) {
            sDateView = date;
            sClockTargets.clear();
            sNudgeSample = Float.NaN;
            sAppliedK = Float.NaN;
            sAppliedGlassV = Float.NaN;
        }
        // One measurement per frame, pooled across the trees: the top anchors the placement and
        // the middle decides the pivot, and both have to describe the WHOLE clock.
        RectF pooled = glyphBox();
        // Nothing measurable yet - mid-teardown, or before the first layout. Leaving what is
        // already applied alone is right: resetting would itself be a visible jump.
        if (date == null || pooled == null) {
            reportUnknownClock(date, pooled);
            return false;
        }
        sClockComplained = false;
        float glyph = pooled.top;

        // Both containers are laid out identically and sit at the same screen position, so the
        // date's offset inside its own parent is usable against either tree's time_group.
        updateDateOffset(date, p);
        // Faded in with the squeeze, so entering cover mode stays one continuous motion instead
        // of stepping the date sideways at the start. At p=1 - which is where AOD wakes up - the
        // whole offset is already in force on the first frame.
        float nudge = sNudge * p;
        date.setTranslationY(nudge);
        float dateBottom = date.getTop() + date.getHeight() + nudge;
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            float gap = CLOCK_GAP_DP * g.getResources().getDisplayMetrics().density;
            g.setPivotX(clockPivotX(g, pooled));
            // Pivoting on the glyph top means the scaled block still starts at `glyph`, so the
            // translation needed to land it under the date does not depend on k.
            g.setPivotY(glyph);
            g.setScaleX(k);
            g.setScaleY(k);
            // Faded in with p, exactly like the date's nudge above, and for the same reason:
            // at p=0 the group has to be back at ITS OWN layout position, because that is what
            // abandonHold() hands back to (translationY 0, scale 1) the moment the exit spring
            // lands. The absolute anchor this used to be does not converge there - measured on
            // this screen it left the clock 12px high at p=0 - so the last frame of the exit
            // and the first frame after it differed by those 12px, and the big clock dropped
            // in one step a quarter of a second after it had visibly stopped moving. The same
            // step ran the other way on the first frame of the entry.
            g.setTranslationY((dateBottom + gap - (g.getTop() + glyph)) * p);
        }
        recordCollapse(y, p, k, glyph, dateBottom, nudge, date);
        if (sVerbose) {
            Xp.log(TAG + "collapse y=" + y + " p=" + p + " k=" + k
                    + " glyphTop=" + glyph + " dateBottom=" + dateBottom + " nudge=" + nudge);
        }
        return true;
    }

    // ------------------------------------------------------- transition trace

    /**
     * Records what every moving part of the lock screen is doing, frame by frame, for the
     * length of one transition.
     *
     * For an artifact that lasts a few frames and is not in the wallpaper: the wallpaper
     * process proved its own geometry never moves during a swap, so whatever flashes on screen
     * is a VIEW in this process, and no log written after the fact can say which one. This
     * samples the candidates on the pre-draw of every frame and prints only what CHANGED, so a
     * part that sits still costs one string compare and says nothing, and the part that jumps
     * to the corner of the screen for three frames is the only thing in the output.
     *
     * Armed for a fixed window rather than left running: this is per-frame reflection over a
     * handful of views, which is fine for the 900ms of a transition and not fine forever.
     */
    private static final String[] TRACE_VIEWS = {
            "media_player", "album_art", "album_art_image", "deducted_image_view",
            "keyguard_background_layer", "keyguard_foreground_layer",
            "notification_container_parent", "mi_media_controls",
    };
    private static final long TRACE_MS = 900L;

    private static ViewTreeObserver.OnPreDrawListener sTrace;
    private static View sTraceOn;
    private static long sTraceT0;
    private static final java.util.HashMap<String, String> sTraceLast = new java.util.HashMap<>();
    private static final StringBuilder sTraceOut = new StringBuilder();

    private static void armTransitionTrace(final String why) {
        // Off unless someone is watching: this is per-frame reflection over a handful of views
        // plus a walk of the whole window, which is the right price for 900ms of a bug hunt and
        // the wrong one for every transition forever. `--es op verbose --ez on true` arms it.
        if (!sVerbose) return;
        final View root = sContainer;
        if (root == null || sTrace != null) return;
        sTraceT0 = android.os.SystemClock.uptimeMillis();
        sTraceLast.clear();
        sTraceOut.setLength(0);
        sTraceOut.append("transition trace (").append(why).append("):");
        sTraceOn = root;
        sTrace = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                long dt = android.os.SystemClock.uptimeMillis() - sTraceT0;
                for (String name : TRACE_VIEWS) {
                    View v = findSysuiView(name);
                    String now = v == null ? "gone" : traceLine(v);
                    String was = sTraceLast.put(name, now);
                    if (now.equals(was)) continue;
                    sTraceOut.append("\n  +").append(dt).append("ms ").append(name)
                             .append(' ').append(now);
                }
                sweepForStrays(dt);
                if (dt >= TRACE_MS) {
                    releaseTransitionTrace();
                    Xp.log(TAG + sTraceOut);
                }
                return true;
            }
        };
        root.getViewTreeObserver().addOnPreDrawListener(sTrace);
    }

    /**
     * Anything small being drawn high on the screen that is NOT one of the views above.
     *
     * The named list is a set of guesses, and a guess cannot find an artifact nobody has
     * identified yet - the report is "a small copy of the cover, up in the corner", and the
     * whole point of this pass is to name the view that is. So every frame also walks the
     * keyguard window for a visible view that is under half the screen wide and sitting in the
     * top third, which is what "small, up there" means in numbers. Bounded by the same 900ms
     * window as the rest of the trace.
     */
    private static void sweepForStrays(long dt) {
        View root = sContainer;
        if (root == null || sScreenW <= 0) return;
        sweep(root.getRootView(), dt, 0);
    }

    private static void sweep(View v, long dt, int depth) {
        if (depth > 14 || v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sweep(g.getChildAt(i), dt, depth + 1);
        }
        int w = v.getWidth(), h = v.getHeight();
        // Small, but cover-sized: below an eighth of the screen is the status bar's icons,
        // which jitter by a pixel every frame and would bury the one line that matters.
        if (w < sScreenW / 8 || h < sScreenW / 8 || w > sScreenW / 2) return;
        if (!(v instanceof ImageView) && !(v instanceof android.view.TextureView)) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (loc[1] > sScreenH / 3) return;
        String name = idOf(v);
        String key = "stray:" + name + '@' + System.identityHashCode(v);
        String now = v.getClass().getSimpleName() + ' ' + traceLine(v);
        String was = sTraceLast.put(key, now);
        if (now.equals(was)) return;
        sTraceOut.append("\n  +").append(dt).append("ms STRAY #").append(name)
                 .append(' ').append(now);
    }

    private static String traceLine(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        StringBuilder sb = new StringBuilder();
        sb.append(loc[0]).append(',').append(loc[1])
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility())
          .append(" a=").append(r2(v.getAlpha()))
          .append(" s=").append(r2(v.getScaleX())).append('/').append(r2(v.getScaleY()))
          .append(" t=").append(r1(v.getTranslationX())).append(',')
          .append(r1(v.getTranslationY()))
          .append(" shown=").append(v.isShown());
        return sb.toString();
    }

    private static void releaseTransitionTrace() {
        View on = sTraceOn;
        ViewTreeObserver.OnPreDrawListener t = sTrace;
        sTraceOn = null;
        sTrace = null;
        if (on == null || t == null) return;
        try {
            on.getViewTreeObserver().removeOnPreDrawListener(t);
        } catch (Throwable ignored) {
        }
    }
    // ------------------------------------------------------------------ diagnostics

    /**
     * A rolling record of what the collapse actually did, for a phone that is not here.
     *
     * The clock lands in the wrong place on some devices and clock styles and not on the one
     * this was written on, and a screenshot cannot say why: every number that decides the
     * placement - the date's settled position, the pooled glyph box, which view was picked as
     * the thing to scale - is measured on the device at the moment it happens. So the module
     * keeps the last few frames' worth and the app can ask for them (op=diag), which turns a
     * report into numbers without the reporter needing adb.
     *
     * Deduplicated on everything but the timestamp: at rest the OEM emits the same y over and
     * over, and a ring full of one identical frame says nothing. What is worth seeing is the
     * ramp into the collapse and the settled value it ended on.
     */
    private static final int DIAG_FRAMES = 20;
    private static final String[] sDiagRing = new String[DIAG_FRAMES];
    private static int sDiagAt;
    private static String sDiagLast = "";
    /** The most recent fully collapsed frame, which the entry ring is too old to hold. */
    private static volatile String sDiagSettled;

    private static void recordCollapse(float y, float p, float k, float glyph, float dateBottom,
                                       float nudge, View date) {
        int[] loc = new int[2];
        date.getLocationOnScreen(loc);
        StringBuilder sb = new StringBuilder();
        // p to three places on purpose: updateDateOffset() only measures at p >= 0.999, so a
        // report where the nudge never moved has to show whether that threshold was reached.
        sb.append("y=").append(r1(y)).append(" p=").append(r3(p)).append(" k=").append(r2(k))
          .append(" glyphTop=").append(r1(glyph)).append(" dateBottom=").append(r1(dateBottom))
          .append(" nudge=").append(r1(nudge)).append(" sNudge=").append(r1(sNudge))
          .append(" sample=").append(r1(sNudgeSample))
          .append(" dateOnScreen=").append(loc[1])
          .append(" dateTop=").append(date.getTop()).append(" dateH=").append(date.getHeight())
          .append(" dateTy=").append(r1(date.getTranslationY()));
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            int[] gl = new int[2];
            g.getLocationOnScreen(gl);
            sb.append(" | ").append(idOf(g)).append(" top=").append(g.getTop())
              .append(" ty=").append(r1(g.getTranslationY()))
              .append(" onScreen=").append(gl[1]);
        }
        String line = sb.toString();
        if (p >= 0.999f) sDiagSettled = line;
        if (line.equals(sDiagLast)) return;
        sDiagLast = line;
        // The ring is the ENTRY into the collapse and is not overwritten afterwards. Leaving it
        // rolling looked right until you follow what a reporter actually does: see the clock in
        // the wrong place, unlock, then open the app - and unlocking runs the exit animation,
        // whose twenty-odd frames would evict every frame that decided the placement. The entry
        // is where it is decided; the settled line above carries the current answer.
        if (sDiagAt >= DIAG_FRAMES) return;
        sDiagRing[sDiagAt] = line;
        sDiagAt++;
    }

    private static void resetCollapseRecord() {
        sDiagAt = 0;
        sDiagLast = "";
        java.util.Arrays.fill(sDiagRing, null);
    }

    private static String r1(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 10f) / 10f);
    }

    private static String r2(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 100f) / 100f);
    }

    private static String r3(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 1000f) / 1000f);
    }

    /**
     * Everything needed to explain where this phone put the clock, as one block of text.
     *
     * Written to be pasted into a bug report by someone who cannot run adb, so it says what it
     * measured AND what it intended: a report where `target` and `dateOnScreen` agree is a
     * report about something other than the placement.
     */
    private static String clockReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("HyperMusicCover clock report\n");
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append(" (").append(android.os.Build.DEVICE)
          .append(")\nbuild: ").append(android.os.Build.DISPLAY)
          .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
          .append(" / SDK ").append(android.os.Build.VERSION.SDK_INT)
          .append("\nos: ").append(sysProp("ro.mi.os.version.name"))
          .append(' ').append(sysProp("ro.mi.os.version.incremental"))
          .append("\n");
        View v = sContainer;
        if (v == null) {
            sb.append("no clock container - the module is loaded but the keyguard has not been "
                    + "built yet, or the hook did not take.\n");
            return sb.toString();
        }
        android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
        sb.append("screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
          .append(" density=").append(dm.density).append(" dpi=").append(dm.densityDpi)
          .append(" moduleScreen=").append(sScreenW).append('x').append(sScreenH)
          .append(" statusBar=").append(sysDimen(v, "status_bar_height"))
          .append("\n");
        sb.append("state: cover=").append(sCoverMode).append(" auto=").append(sAuto)
          .append(" card=").append(sCardKnown ? (sCardShowing ? "showing" : "gone") : "unknown")
          .append(" keyguard=").append(onKeyguardNow())
          .append(" videoWallpaper=").append(sVideoWallpaper)
          .append("\nhold: y=").append(sHoldY).append(" current=").append(r1(sCurrentY))
          .append(" lastSystem=").append(r1(sLastSystemY))
          .append(" floor=").append(SQUEEZE_FLOOR)
          .append(" collapseMin=").append(r2(sCollapseMin))
          .append(" clockScale=").append(sClockScale)
          .append("\nnudge: inForce=").append(r1(sNudge))
          .append(" lastSample=").append(r1(sNudgeSample))
          .append(" dateTopTarget=").append(r1(DATE_TOP_DP * dm.density))
          .append("dp*").append(dm.density).append(" gap=").append(CLOCK_GAP_DP).append("dp\n");

        View date = visibleDate();
        if (date == null) {
            sb.append("date: NOT FOUND - nothing to anchor the clock to\n");
        } else {
            int[] loc = new int[2];
            date.getLocationOnScreen(loc);
            sb.append("date: #").append(idOf(date)).append(' ')
              .append(date.getClass().getSimpleName())
              .append(" onScreen=").append(loc[0]).append(',').append(loc[1])
              .append(" top=").append(date.getTop()).append(" h=").append(date.getHeight())
              .append(" ty=").append(r1(date.getTranslationY()))
              .append(" text=\"").append(date instanceof TextView
                    ? ((TextView) date).getText() : "?").append("\"\n");
        }
        RectF pooled = glyphBox();
        sb.append("glyphs: ").append(pooled == null ? "NOT MEASURABLE" : pooled.toString())
          .append("\n");
        float[] cg = clockGeometry();
        sb.append("geometry: ").append(cg == null ? "null"
                : "w=" + r1(cg[0]) + " h=" + r1(cg[1]) + " y=" + r1(cg[2])
                  + " x=" + r1(cg[3]) + " pivotX=" + r1(cg[4])).append("\n");

        for (View root : clockRoots()) {
            sb.append("tree ").append(idOf(root)).append(":\n");
            View g = clockTarget(root);
            if (g == null) {
                sb.append("  target: NONE (this tree draws no clock)\n");
            } else {
                int[] gl = new int[2];
                g.getLocationOnScreen(gl);
                boolean byId = g == findClockView(root, "time_group");
                sb.append("  target: #").append(idOf(g)).append(' ')
                  .append(g.getClass().getSimpleName())
                  .append(byId ? " (time_group)" : " (RESOLVED by search)")
                  .append(" left=").append(g.getLeft()).append(" top=").append(g.getTop())
                  .append(' ').append(g.getWidth()).append('x').append(g.getHeight())
                  .append(" onScreen=").append(gl[0]).append(',').append(gl[1])
                  .append(" scale=").append(r2(g.getScaleX())).append('/').append(r2(g.getScaleY()))
                  .append(" pivot=").append(r1(g.getPivotX())).append(',').append(r1(g.getPivotY()))
                  .append(" ty=").append(r1(g.getTranslationY()))
                  .append("\n  ink: ").append(inkBox(root, g)).append('\n');
                // The one thing a screenshot cannot show and the placement depends on entirely:
                // whether the view being scaled also contains the date, which would drag the
                // date along with the clock and make the anchor chase itself.
                if (date != null) {
                    sb.append("  target holds the date: ").append(isAncestor(g, date)).append('\n');
                }
            }
            describeClockTree(root, sb, 1);
            sb.append('\n');
        }

        sb.append("settled: ").append(sDiagSettled == null ? "never" : sDiagSettled)
          .append('\n');
        sb.append("first ").append(sDiagAt).append(" frames of the collapse")
          .append(sDiagAt == 0 ? " (none - cover mode has not run)" : "").append(":\n");
        for (int n = 0; n < sDiagAt; n++) {
            sb.append("  ").append(sDiagRing[n]).append('\n');
        }
        return sb.toString();
    }

    private static boolean isAncestor(View ancestor, View child) {
        for (android.view.ViewParent p = child.getParent(); p instanceof View;
             p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static String sysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object s = sp.getMethod("get", String.class).invoke(null, key);
            String out = s == null ? "" : s.toString();
            return out.isEmpty() ? "?" : out;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String sysDimen(View v, String name) {
        try {
            int id = v.getResources().getIdentifier(name, "dimen", "android");
            return id == 0 ? "?" : String.valueOf(v.getResources().getDimensionPixelSize(id));
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Where the collapse pivots horizontally, in the target's own coordinates.
     *
     * A clock that is centred on the screen has to stay centred as it shrinks, and one that is
     * aligned to the left - as the classic style's is, under a left-aligned date - has to stay
     * left. So the pivot follows what the style already does rather than being a constant:
     * centred styles pivot on the middle of the screen, the rest on their own left edge.
     *
     * **The box has to be the POOLED one**, across both trees, like the top edge. all_in_one
     * draws half the clock in each tree - the hour in the background one, the minute in the
     * foreground one - so each tree on its own looks like an off-centre clock, and giving each
     * its own pivot made the two halves shrink towards their own left edges and pull apart. On
     * screen: "14" and "43" with a gap down the middle.
     *
     * Not g.getWidth()/2 for the centred case: this runs from the setNotifY hook, which can fire
     * before the group is laid out, and a width of 0 would put the pivot on the left edge - the
     * clock then collapses into the corner instead of staying centred under the date.
     */
    private static float clockPivotX(View g, RectF pooled) {
        float screenW = g.getResources().getDisplayMetrics().widthPixels;
        if (pooled == null) return screenW / 2f - g.getLeft();
        float inkCentre = g.getLeft() + pooled.centerX();
        boolean centred = Math.abs(inkCentre - screenW / 2f) < screenW * 0.05f;
        return centred ? screenW / 2f - g.getLeft() : pooled.left;
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
                Xp.log(TAG + "media session -> " + best.getPackageName()
                        + " (of " + cs.size() + ")");
            }
            return best;
        } catch (Throwable t) {
            Xp.log(TAG + "getActiveSessions failed: " + t);
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
                    Xp.log(TAG + "album art from " + c.getPackageName()
                            + " " + b.getWidth() + "x" + b.getHeight() + " \""
                            + md.getString(MediaMetadata.METADATA_KEY_TITLE) + "\"");
                    return b;
                }
                Xp.log(TAG + c.getPackageName() + " carries no art bitmap (uri="
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
            Xp.log(TAG + "album art from media card thumbnail "
                    + out[0].getWidth() + "x" + out[0].getHeight());
        }
        return out[0];
    }

    /**
     * Everything the app's preview needs that only this process can see.
     *
     * The app draws a lock screen it has no access to: it cannot read the media session (that
     * would need notification access), and the media card and the shortcut buttons are
     * SystemUI's own views. It used to draw its own diagram of the card out of rectangles, which
     * is exactly as convincing as it sounds. So the real views are captured here instead - one
     * software draw each - and the app paints the results into its preview at the coordinates
     * they occupy on the phone.
     *
     * Sizes: the artwork is a 256px JPEG, the card is scaled to CARD_SHOT_W and the shortcuts to
     * SHORTCUT_SHOT_W, all together well inside the ordered broadcast's binder budget. The
     * shortcuts are only sent when asked for - they never change - and the artwork is skipped
     * when the caller says it already has this track's.
     */
    private static android.os.Bundle previewBundle(Context c, Intent i) {
        android.os.Bundle out = new android.os.Bundle();
        // One line per request, in debug builds, saying where every piece of the picture came
        // from or why it is missing. The preview has six independent sources and a user reports
        // it as one thing ("the preview is blank"), so without this every report costs a round
        // trip to find out WHICH source failed.
        StringBuilder diag = DIAG ? new StringBuilder() : null;
        out.putBoolean("alive", true);
        out.putString("track", sCardKey);
        if (diag != null) {
            diag.append("preview: cover=").append(sCoverMode)
                .append(" auto=").append(sAuto)
                .append(" cardKnown=").append(sCardKnown)
                .append(" cardShowing=").append(sCardShowing)
                .append(" keyguard=").append(keyguardShowing())
                .append(" screenOn=").append(screenOn())
                .append(" container=").append(sContainer != null)
                .append(" screen=").append(sScreenW).append('x').append(sScreenH)
                .append(" track=").append(sCardKey.isEmpty() ? "none" : sCardKey);
        }

        if (!sCardKey.isEmpty() && sCardKey.equals(i.getStringExtra("have"))) {
            out.putBoolean("same", true);
            if (diag != null) diag.append("\n  art: same track, not resent");
        } else {
            byte[] thumb = artThumbnail(c, 256);
            if (thumb != null) out.putByteArray("jpg", thumb);
            if (diag != null) {
                diag.append("\n  art: ").append(thumb == null ? "NULL" : thumb.length + "B");
                if (thumb == null) {
                    MediaController mc = pickController(c);
                    diag.append(" (session=")
                        .append(mc == null ? "none" : mc.getPackageName())
                        .append(" metadata=")
                        .append(mc == null || mc.getMetadata() == null ? "none" : "yes")
                        .append(" cardThumb=").append(cardThumbnail() == null ? "none" : "yes")
                        .append(')');
                }
            }
        }

        View cardView = cardShotRoot(findSysuiView("mi_media_controls"));
        View mediaBg = cardView == null ? null : findByName(cardView, "media_bg");
        // 24dp on this device - notification_item_bg_radius, which media_bg's own outline
        // agrees with. The app rounds its stand-in frosting to the same number.
        if (mediaBg != null) out.putFloat("cardradius", outlineRadius(mediaBg));
        putArtSlot(out, cardView);
        boolean dump = i.getBooleanExtra("dump", false);
        // The artwork at full size, for lifting a sample cover out of a running phone.
        if (dump) {
            Bitmap raw = albumArt(c);
            if (raw != null) {
                try {
                    java.io.FileOutputStream f = new java.io.FileOutputStream(
                            new java.io.File(sAppCtx.getFilesDir(), "mc_shot_art.jpg"));
                    raw.compress(Bitmap.CompressFormat.JPEG, 95, f);
                    f.close();
                    Xp.log(TAG + "shot art " + raw.getWidth() + "x" + raw.getHeight());
                } catch (Throwable t) {
                    Xp.log(TAG + "art dump failed: " + t);
                }
            }
        }
        byte[] card = shootCard(cardView);
        if (dump) dumpShot("card", card);
        if (card != null) {
            out.putByteArray("card", card);
            // The SAMPLED rectangle, not a live reading: on an unlocked phone this same view is
            // the one at the top of the shade, six hundred pixels higher up.
            out.putIntArray("cardrect", new int[]{sCardL, sCardT, sCardW, sCardH});
        }
        if (diag != null) {
            diag.append("\n  card: view=");
            if (cardView == null) {
                diag.append("NOT FOUND (no media card in the keyguard tree)");
            } else {
                diag.append(cardView.getClass().getSimpleName())
                    .append(' ').append(cardView.getWidth()).append('x')
                    .append(cardView.getHeight())
                    .append(" vis=").append(cardView.getVisibility());
            }
            diag.append(" shot=").append(card == null ? "NULL" : card.length + "B")
                .append(" rect=").append(sCardL).append(',').append(sCardT).append(' ')
                .append(sCardW).append('x').append(sCardH)
                .append(" artslot=").append(out.containsKey("artfrac") ? "yes" : "no")
                .append(" hideArt=").append(sMcHideArt)
                .append(" centerText=").append(sMcCenterText);
        }

        putDate(out, dump);
        // Sent with the pictures, not only in answer to a query. The query happens once, when
        // the page is opened; the clock's geometry changes whenever the user changes the lock
        // screen clock style, and a stale zero here is the app holding a picture of a clock it
        // then refuses to draw for want of somewhere to put it.
        float[] cg = clockGeometry();
        if (cg != null) {
            out.putFloat("clockw", cg[0]);
            out.putFloat("clockh", cg[1]);
            out.putFloat("clocky", cg[2]);
            out.putFloat("clockx", cg[3]);
            out.putFloat("clockpivotx", cg[4]);
            out.putFloat("clockpad", CLOCK_PAD);
        }
        if (diag != null) {
            View date = visibleDate();
            diag.append("\n  date: view=").append(date == null ? "NOT FOUND"
                    : date.getClass().getSimpleName() + " " + date.getWidth() + "x"
                      + date.getHeight() + " vis=" + date.getVisibility())
                .append(" shot=").append(out.containsKey("date") ? "yes" : "NULL");
            diag.append("\n  clock: geom=").append(cg == null ? "NULL (no date or no glyph box)"
                    : "w" + cg[0] + " h" + cg[1] + " y" + cg[2] + " x" + cg[3] + " pivot" + cg[4]);
            View[] roots = clockRoots();
            diag.append(" roots=").append(roots.length);
            for (int t = 0; t < roots.length; t++) {
                View g = clockTarget(roots[t]);
                diag.append(" target").append(t).append('=')
                    .append(g == null ? "none" : g.getClass().getSimpleName() + "#" + idOf(g));
            }
        }

        // Both clock trees: the hour is drawn in the background layer and the minute in the
        // foreground one, and each carries half the time.
        for (int t = 0; t < 2; t++) {
            byte[] png = shootClock(t);
            if (dump) dumpShot("clock" + t, png);
            if (png != null) out.putByteArray("clock" + t, png);
            if (diag != null) {
                diag.append(" shot").append(t).append('=')
                    .append(png == null ? "NULL" : png.length + "B");
            }
        }
        if (diag != null && !sClockShot) diag.append(" [clock capture switched OFF]");

        if (i.getBooleanExtra("shortcuts", false)) {
            putShortcut(out, "sl", "shortcut_view_left", dump);
            putShortcut(out, "sr", "shortcut_view_right", dump);
            if (diag != null) {
                diag.append("\n  shortcuts: asked, left=")
                    .append(out.containsKey("sl") ? "yes" : "NOT FOUND")
                    .append(" right=").append(out.containsKey("sr") ? "yes" : "NOT FOUND");
            }
        } else if (diag != null) {
            diag.append("\n  shortcuts: not asked for (the app already has them)");
        }

        if (diag != null) Xp.log(TAG + diag);
        return out;
    }

    /**
     * Where the card draws the album art, and how rounded it is.
     *
     * The artwork is the one part of the card the app has to draw itself. A software canvas does
     * not apply a view's outline clip - only a hardware one does - so the captured card came
     * back with a hard-cornered square where the OEM draws a rounded one. Everything else about
     * it survives the capture, so only the artwork is cut out of the picture and painted back.
     *
     * The radius is read off the view's own OutlineProvider rather than a resource: it is the
     * number actually being clipped with, and it costs one call.
     */
    private static void putArtSlot(android.os.Bundle out, View card) {
        if (card == null || card.getWidth() <= 0 || card.getHeight() <= 0) return;
        // Resolved off the card rather than read from the guard's cache: the guard is only
        // installed when one of the two card settings is on, and the app asks for this picture
        // whether or not anything has been switched on.
        View box = findByName(card, "album_art");
        View img = box == null ? null : findByName(box, "album_art_image");
        if (img == null) return;
        // Hidden by the user's own setting - nothing to draw, and saying so is how the app knows.
        if (sMcHideArt || box.getVisibility() != View.VISIBLE) return;
        if (img.getWidth() <= 0 || img.getHeight() <= 0) return;
        int[] a = new int[2], b = new int[2];
        img.getLocationOnScreen(a);
        card.getLocationOnScreen(b);
        // FRACTIONS of the card, not pixels. The app pastes the capture into the rectangle the
        // card occupies on the LOCK screen, but the card it captured may have been laid out at
        // some other width - pull the shade down over the app and it is the same view, sized for
        // the shade - and pixels measured there land in the wrong place once the capture has
        // been stretched to the lock screen's width. Fractions survive the stretch.
        float w = card.getWidth(), h = card.getHeight();
        out.putFloatArray("artfrac", new float[]{(a[0] - b[0]) / w, (a[1] - b[1]) / h,
                img.getWidth() / w, img.getHeight() / h});
        // Measured on this device: the ImageView is not clipped at all, its CONTAINER is -
        // clipToOutline with a 30px radius - so the radius has to be looked for on both.
        float r = img.getClipToOutline() ? outlineRadius(img) : 0f;
        if (r <= 0f && box.getClipToOutline()) r = outlineRadius(box);
        out.putFloat("artradius", r / w);
    }

    private static float outlineRadius(View v) {
        try {
            android.graphics.Outline o = new android.graphics.Outline();
            v.getOutlineProvider().getOutline(v, o);
            float r = o.getRadius();
            if (r >= 0f) return r;
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    private static final int DATE_SHOT_W = 360;

    /**
     * The date line above the clock, captured like everything else rather than re-typed.
     *
     * It has to be the real view: the format, the language, the font and the colour all follow
     * the lock screen clock style, and a date drawn here would be a second thing to keep in step
     * with a theme the user can change at any moment. Because the app re-asks every few seconds,
     * a style change simply arrives with the next picture.
     *
     * The y is COMPUTED, not read. Cover mode pins the date to DATE_TOP_DP (placeCollapsedClock
     * anchors everything else to it), and the app asks for this with the phone unlocked, where
     * the live position is whatever the hidden keyguard happens to be holding. The x comes off
     * the view because it does not depend on the squeeze.
     */
    private static void putDate(android.os.Bundle out, boolean dump) {
        View date = visibleDate();
        if (date == null || date.getWidth() <= 0 || date.getHeight() <= 0) return;
        byte[] png = shoot(date, DATE_SHOT_W);
        if (dump) dumpShot("date", png);
        if (png == null) return;
        int[] loc = new int[2];
        date.getLocationOnScreen(loc);
        float density = date.getResources().getDisplayMetrics().density;
        out.putByteArray("date", png);
        out.putIntArray("daterect", new int[]{loc[0], Math.round(DATE_TOP_DP * density),
                date.getWidth(), date.getHeight()});
    }

    private static final int CLOCK_SHOT_W = 480;
    /**
     * Slack around the glyph box before cropping. getTextBoundsWithPosition() reports the text's
     * own bounds, but the digits are STROKED - in glass mode they are nothing but stroke - so
     * half a stroke width falls outside it and the crop shaved the last digit.
     */
    private static final float CLOCK_PAD = 10f;

    private static volatile boolean sClockShot = true;

    /**
     * The clock's digits, cropped to the glyphs, drawn WITHOUT the collapse scale - the app
     * applies that itself, so dragging the size slider does not need a new picture.
     *
     * These are the OEM's own variable-font paths, which is why they can be captured at all:
     * TimeView.onDraw strokes a Path, and a Path draws into a software canvas like anything
     * else. The refracting glass is a RuntimeShader and does not, so what comes back is the
     * digits as they are filled at the moment of the capture.
     */
    private static byte[] shootClock(int which) {
        if (!sClockShot) return null;
        View[] roots = clockRoots();
        if (which >= roots.length) return null;
        View g = clockTarget(roots[which]);
        RectF box = paddedGlyphBox();
        if (g == null || box == null || box.width() <= 0 || box.height() <= 0) return null;
        try {
            float k = Math.min(1f, CLOCK_SHOT_W / box.width());
            int w = Math.max(1, Math.round(box.width() * k));
            int h = Math.max(1, Math.round(box.height() * k));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            cv.translate(-box.left, -box.top);
            g.draw(cv);
            redrawAfterCapture(g);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Xp.log(TAG + "clock capture failed: " + t);
            return null;
        }
    }

    /**
     * Whether the card is currently laid out the way the lock screen lays it out.
     *
     * The app stretches this picture into the rectangle the card occupies on the LOCK screen, so
     * a picture of the card in any other layout is wrong by construction - and the shade
     * re-lays-out the very same view for its own panel. Taken there and stretched into the lock
     * screen's rectangle, the card's content ended up crammed into the top-left corner.
     *
     * The test is the sampled size rather than "is the shade open", because it is the size that
     * the stretch actually depends on: whatever the shade, an AOD or a future panel does to the
     * card, a picture is only usable if it was taken at the size it will be drawn at. When
     * nothing has been sampled yet there is no invariant to protect and the picture is taken.
     */
    private static boolean cardLaidOutForKeyguard(View card) {
        if (sCardW <= 0 || sCardH <= 0) return true;
        boolean ok = Math.abs(card.getWidth() - sCardW) <= 2
                && Math.abs(card.getHeight() - sCardH) <= 2;
        if (!ok && !sCardShapeComplained) {
            sCardShapeComplained = true;
            Xp.log(TAG + "card is " + card.getWidth() + "x" + card.getHeight()
                    + ", lock screen says " + sCardW + "x" + sCardH + " - not photographing it");
        }
        if (ok) sCardShapeComplained = false;
        return ok;
    }

    private static boolean sCardShapeComplained;

    /**
     * What to photograph for "the media card".
     *
     * Not mi_media_controls, which is only the card's contents: the bright rim along the card's
     * edge is a FOREGROUND drawable on its parent, MiuiMediaHeaderView, and a foreground is
     * drawn after the children - so starting the capture at the child left the rim out
     * altogether. The parent has the same bounds, so nothing else about the picture changes.
     *
     * Guarded on the bounds matching rather than on the class name, because everything measured
     * and reported around the card - the sampled rectangle, the artwork's slot - is relative to
     * mi_media_controls, and only an identically placed parent keeps those true.
     */
    private static View cardShotRoot(View card) {
        if (card == null) return null;
        if (!(card.getParent() instanceof View)) return card;
        View p = (View) card.getParent();
        boolean sameBox = p.getWidth() == card.getWidth() && p.getHeight() == card.getHeight()
                && card.getLeft() == 0 && card.getTop() == 0;
        return sameBox ? p : card;
    }

    /** Torch and camera. Their layout is the same locked or unlocked, so this reads it live. */
    private static void putShortcut(android.os.Bundle out, String key, String id, boolean dump) {
        View v = findSysuiView(id);
        if (v == null) {
            Xp.log(TAG + id + " not found");
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
        byte[] png = shoot(v, SHORTCUT_SHOT_W);
        if (dump) dumpShot(key, png);
        if (png == null) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        out.putByteArray(key, png);
        out.putIntArray(key + "rect", new int[]{loc[0], loc[1], v.getWidth(), v.getHeight()});
    }

    /** Debug builds narrate the preview pipeline; release builds only do so on `op=verbose`. */
    private static final boolean DIAG = BuildConfig.DEBUG;

    private static final int CARD_SHOT_W = 420;
    private static final int SHORTCUT_SHOT_W = 160;

    /**
     * `--es op preview --ez dump true` drops the captures next to the state file, for looking at
     * when the preview shows the wrong thing. A capture can fail quietly - a view that renders
     * only on a hardware canvas comes out blank rather than throwing - and the size of the PNG
     * is not enough to tell that apart from a picture that is simply mostly transparent.
     */
    private static void dumpShot(String name, byte[] png) {
        if (sAppCtx == null) return;
        try {
            java.io.File f = new java.io.File(sAppCtx.getFilesDir(), "mc_shot_" + name + ".png");
            if (png == null) {
                Xp.log(TAG + "shot " + name + " = null");
                return;
            }
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(png);
            o.close();
            Xp.log(TAG + "shot " + name + " " + png.length + "B -> " + f);
        } catch (Throwable t) {
            Xp.log(TAG + "shot dump failed: " + t);
        }
    }

    /**
     * The media card as a picture.
     *
     * The two card settings only apply on the keyguard (see assertMediaCard), but the app asks
     * for this with the phone unlocked and in its own foreground, and what it needs to show is
     * what the LOCK screen will look like. So they are forced on for the duration of the draw
     * and put straight back. Nothing can be drawn to the screen in between - this is the main
     * thread, and a traversal cannot interleave with a broadcast receiver - so the real card
     * never flickers.
     */
    private static byte[] shootCard(View card) {
        if (card == null) return null;
        if (!cardLaidOutForKeyguard(card)) return null;
        View box = findByName(card, "album_art");
        View img = box == null ? null : findByName(box, "album_art_image");
        // View.VISIBLE rather than a -1 sentinel for "no view": the null check below already
        // covers that case, and lint tracks the @Visibility typedef through this assignment -
        // a sentinel outside the set is a WrongConstant error at the restore.
        int wasVisible = img == null ? View.VISIBLE : img.getVisibility();
        sCardForced = true;
        try {
            assertMediaCard(card);
            // Cut the artwork out of the picture; the app paints it back rounded (putArtSlot).
            // The badge over its corner is a sibling, so it still lands on top where it belongs.
            if (img != null) img.setVisibility(View.INVISIBLE);
            return shoot(card, CARD_SHOT_W);
        } catch (Throwable t) {
            Xp.log(TAG + "card capture failed: " + t);
            return null;
        } finally {
            if (img != null) img.setVisibility(wasVisible);
            sCardForced = false;
            assertMediaCard(card);
        }
    }

    /**
     * One view, drawn into a bitmap at most maxW wide, as a PNG.
     *
     * PNG rather than JPEG because these have to keep their alpha: the card is a rounded
     * translucent panel and the shortcuts are circles, and squaring either off would put a hard
     * rectangle on top of the wallpaper in the preview.
     */
    private static byte[] shoot(View v, int maxW) {
        return shoot(v, maxW, null);
    }

    /**
     * extraBg is a child whose BACKGROUND has to be drawn by hand, for views MIUI's blur path
     * skips on a software canvas.
     */
    private static byte[] shoot(View v, int maxW, View extraBg) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            float k = Math.min(1f, maxW / (float) w);
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(w * k)),
                    Math.max(1, Math.round(h * k)), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            drawBackgroundOf(extraBg, cv);
            v.draw(cv);
            redrawAfterCapture(v);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Xp.log(TAG + "view capture failed: " + t);
            return null;
        }
    }

    /**
     * Puts a captured view back in line for a real redraw.
     *
     * Drawing a live view into an offscreen canvas is not free of consequences: View.draw()
     * leaves the view marked as drawn, and the framework is then entitled to skip it on the next
     * real frame, which shows up as stale pixels - half a clock, digits that did not follow the
     * minute over. Suspected cause of "the lock screen font is sometimes incomplete", reported
     * after the clock started being captured every few seconds.
     *
     * invalidate() does not reach children, and it is a child that draws the glyphs, so this
     * walks. Cheap: a handful of views, once per capture, and an invalidate on an unchanged view
     * costs one more draw of something that was going to be drawn anyway.
     */
    private static void redrawAfterCapture(View v) {
        if (v == null) return;
        v.invalidate();
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) redrawAfterCapture(g.getChildAt(i));
    }

    /** Draws one child's background at its place in the parent, without going through the view. */
    private static void drawBackgroundOf(View v, android.graphics.Canvas cv) {
        if (v == null) return;
        Drawable d = v.getBackground();
        if (d == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        Rect old = new Rect(d.getBounds());
        try {
            cv.save();
            cv.translate(v.getLeft(), v.getTop());
            d.setBounds(0, 0, v.getWidth(), v.getHeight());
            d.draw(cv);
        } catch (Throwable ignored) {
        } finally {
            d.setBounds(old);
            cv.restore();
        }
    }

    /** Keyed on the artwork itself, so the app can re-ask as often as it likes for free. */
    private static volatile int sThumbPrint;
    private static volatile byte[] sThumbJpg;

    /**
     * The current artwork, small, as a JPEG. Runs on the receiver's thread - the main one - so
     * it stays deliberately cheap: one downscale and one encode of a picture a few hundred
     * pixels wide, and nothing at all when the artwork has not changed since the last ask.
     */
    private static byte[] artThumbnail(Context ctx, int max) {
        Bitmap b = albumArt(ctx);
        if (b == null) return null;
        int print = artPrint(b);
        byte[] cached = sThumbJpg;
        if (cached != null && print != 0 && print == sThumbPrint) return cached;
        try {
            int w = b.getWidth(), h = b.getHeight();
            float k = Math.min(1f, max / (float) Math.max(w, h));
            Bitmap small = k < 1f ? Bitmap.createScaledBitmap(b,
                    Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true) : b;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            if (small != b) small.recycle();
            byte[] jpg = bos.toByteArray();
            sThumbPrint = print;
            sThumbJpg = jpg;
            return jpg;
        } catch (Throwable t) {
            Xp.log(TAG + "art thumbnail failed: " + t);
            return null;
        }
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
        // A live lock wallpaper needs BOTH halves, and returning here after the first was why
        // the card and the clock glass stayed on the video: this view covers the background,
        // but those two sample the wallpaper WINDOW, and the only thing that paints it is the
        // broadcast below. Falling through is what makes the wallpaper process paint it.
        //
        // It costs one extra compose per track change on this path - showVideoCover() composes
        // for the view and the JPEG below composes again. Worth folding into one later; the
        // correctness of having both matters more than the ~100ms.
        if (sVideoWallpaper) showVideoCover(ctx, on, art);
        long t0 = android.os.SystemClock.uptimeMillis();
        Intent out = wallpaperIntent("art");
        // Only a request. The wallpaper process falls back to the one-frame swap whenever it
        // does not hold both ends of the fade - after its own restart, most of all. Never with
        // the display off: the frames would be composed and uploaded into a screen nobody is
        // looking at, and the clock is not springing either.
        out.putExtra("fade", sFadeWp && screenOn());
        if (!on) {
            out.putExtra("off", true);
            ctx.sendBroadcast(out);
            sTrackKey = "";
            // Nothing behind the clock any more, so nothing to judge the colour against. The
            // repaint that hands the OEM's own colours back happens with the rest of cover mode.
            sCoverLuma = Float.NaN;
            Xp.log(TAG + "pushart off");
            return;
        }
        if (art == null) { Xp.log(TAG + "pushart: no album art"); return; }
        int w = sScreenW, h = sScreenH;
        Bitmap full = composeWallpaper(art, w, h, sBias);
        measureCover(full);
        if (sCoverMode) recolorClock();
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
        Xp.log(TAG + "pushart " + w + "x" + h + " bias=" + sBias
                + " as " + jpg.length + "B jpeg, draw " + (tc - t0) + "ms encode "
                + (android.os.SystemClock.uptimeMillis() - tc) + "ms");
    }

    /**
     * The cover, for a lock screen whose wallpaper is a video (or any other live one).
     *
     * That case looked unreachable - MIUI builds a KeyguardVideoDepthEngineImpl instead of the
     * image engine, so the GL texture this module replaces never exists - but the video does
     * not live in the wallpaper process either. Traced on device: the engine decodes into two
     * SurfaceTextures that SYSTEMUI supplies, drawn by two full-screen TextureViews that
     * com.miui.keyguard.VideoDepthSurfaceHolder puts in keyguard_background_layer (behind the
     * clock) and keyguard_foreground_layer (the cut-out subject, in front of it).
     *
     * So on this path the wallpaper is already inside our own view tree, and the cover is just
     * a view above the background one. The rule that sent this module into the wallpaper
     * process in the first place - that the clock's liquid glass and the card blur sample the
     * wallpaper WINDOW, so an in-SystemUI cover is never picked up by them - does not hold
     * here, because MIUI is not using that window either. Confirmed on device: the glass
     * refracts a video wallpaper normally.
     *
     * Cheaper than the image path, too: no JPEG round trip and no cross-process broadcast, so
     * the composed bitmap goes straight onto the view.
     */
    private static void showVideoCover(Context ctx, boolean on, Bitmap art) {
        if (!on) {
            detachCover();
            setDepthHidden(false);
            Xp.log(TAG + "video cover off");
            return;
        }
        if (art == null) {
            Xp.log(TAG + "video cover: no album art");
            return;
        }
        long t0 = android.os.SystemClock.uptimeMillis();
        // Composed here, on the worker, exactly as for the image path - same mirror-extend,
        // blur and bias, so the two paths produce the same picture.
        final Bitmap full = composeWallpaper(art, sScreenW, sScreenH, sBias);
        measureCover(full);
        if (sCoverMode) recolorClock();
        final long draw = android.os.SystemClock.uptimeMillis() - t0;
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup layer = coverLayer();
                    if (layer == null) {
                        Xp.log(TAG + "keyguard_background_layer not found for the video cover");
                        full.recycle();
                        return;
                    }
                    ImageView iv = sCover;
                    if (iv == null || iv.getParent() != layer) {
                        detachCover();
                        iv = new ImageView(ctx);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        // Added last, so it draws over the video's TextureView. The layer is
                        // ordered, and a TextureView draws in the view hierarchy like any other
                        // view - unlike a SurfaceView, which would punch through whatever we
                        // put above it.
                        layer.addView(iv, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        sCover = iv;
                    }
                    iv.setImageBitmap(full);
                    Bitmap old = sCoverBitmap;
                    sCoverBitmap = full;
                    if (old != null && old != full) old.recycle();
                    // The video's own cut-out subject is a second TextureView in the FOREGROUND
                    // layer, i.e. in front of the clock. Left alone it floats over the cover
                    // exactly the way deducted_image_view did on the image path.
                    setDepthHidden(true);
                    guardVideoCover(iv);
                    Xp.log(TAG + "video cover shown " + sScreenW + "x" + sScreenH
                            + " bias=" + sBias + ", draw " + draw + "ms");
                } catch (Throwable t) {
                    Xp.log(TAG + "video cover failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * Keeps the live-wallpaper cover to the lock screen, and only the lock screen.
     *
     * keyguard_background_layer is not the keyguard's alone: MIUI draws it as the backdrop of
     * the Control Center too, the same way the media card view is shared between the lock
     * screen and the shade. So a full-screen ImageView parked in it turns up behind the
     * Control Center - reported from the device as the cover appearing there while music
     * played, and sharp rather than blurred, which is what says it is a VIEW being drawn and
     * not something sampling the wallpaper.
     *
     * The wallpaper's own TextureViews go the other way: they are hidden because our cover is
     * over them, and that is only true on the lock screen, so off it they are left exactly as
     * MIUI has them - see setVideoSurfacesHidden().
     *
     * A pre-draw listener rather than a one-off, for the reason the depth guard has one: this
     * has to be true on every frame something else draws that layer, and nothing tells us when
     * that is. Every write is conditional and visibility-only, so a frame that needs no change
     * costs a comparison.
     */
    private static void guardVideoCover(final View cover) {
        if (sCoverGuarded == cover && sCoverGuard != null) return;
        releaseCoverGuard();
        sCoverGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                boolean onKeyguard = sCoverMode && onKeyguardNow();
                int want = onKeyguard ? View.VISIBLE : View.INVISIBLE;
                if (cover.getVisibility() != want) cover.setVisibility(want);
                // Our own view is the only thing to write off the lock screen. The wallpaper's
                // TextureViews belong to MIUI there, and handing them back inside a layer the
                // shade is drawing is what put a stray frame of the wallpaper into the first
                // pull-down - setVideoSurfacesHidden() has the measurement.
                if (!onKeyguard) return true;
                View bg = sVideoBg, fg = sVideoFg;
                if (bg != null && bg.getVisibility() != View.INVISIBLE) {
                    bg.setVisibility(View.INVISIBLE);
                }
                if (fg != null && fg.getVisibility() != View.INVISIBLE) {
                    fg.setVisibility(View.INVISIBLE);
                }
                return true;
            }
        };
        cover.getViewTreeObserver().addOnPreDrawListener(sCoverGuard);
        sCoverGuarded = cover;
        Xp.log(TAG + "video cover guard installed");
    }

    /**
     * The live wallpaper's own TextureViews: hidden while the cover is over them, given back
     * when it goes.
     *
     * Giving them back only counts while the lock screen is actually in front.
     * keyguard_background_layer is not the keyguard's alone - the shade draws it as its own
     * backdrop over the desktop, which is how the cover could turn up behind the Control
     * Centre - so writing VISIBLE there off the lock screen leaves a surface on screen that
     * nobody is feeding. Measured on the device: exactly one bad frame, because MIUI sets it
     * straight back the moment that layer is drawn, which is why only the FIRST pull-down
     * after unlocking showed a stray wallpaper frame and the second was clean.
     *
     * So a hand-back we cannot make now is remembered instead, and paid the next time the
     * keyguard is in front (screen on, or a keyguard rebuild). That debt is the safety net the
     * old unconditional restore was: what must never happen is the wallpaper hidden with no
     * cover over it, which is a black lock screen.
     */
    private static void setVideoSurfacesHidden(boolean hide, View bg, View fg) {
        if (!hide && !onKeyguardNow()) {
            if (sVideoWallpaper && !sVideoWpOwed) {
                sVideoWpOwed = true;
                Xp.log(TAG + "off the lock screen: leaving the live wallpaper as MIUI left it,"
                        + " hand-back deferred");
            }
            return;
        }
        int vis = hide ? View.INVISIBLE : View.VISIBLE;
        if (bg != null) bg.setVisibility(vis);
        if (fg != null) fg.setVisibility(vis);
        sVideoWpOwed = false;
    }

    /** A hand-back of the live wallpaper's surfaces that is waiting for a lock screen. */
    private static volatile boolean sVideoWpOwed;

    private static void releaseCoverGuard() {
        View c = sCoverGuarded;
        ViewTreeObserver.OnPreDrawListener g = sCoverGuard;
        sCoverGuarded = null;
        sCoverGuard = null;
        if (c == null || g == null) return;
        try {
            c.getViewTreeObserver().removeOnPreDrawListener(g);
        } catch (Throwable ignored) {
        }
    }

    private static View sCoverGuarded;
    private static ViewTreeObserver.OnPreDrawListener sCoverGuard;

    /** keyguard_background_layer, which sits behind the whole clock stack. */
    private static ViewGroup coverLayer() {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(
                "keyguard_background_layer", "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        return layer instanceof ViewGroup ? (ViewGroup) layer : null;
    }

    /**
     * The TextureView a live wallpaper is drawn into, in one of the keyguard's layers. It
     * carries no id - it is added in code by VideoDepthSurfaceHolder - so it is found by type,
     * and there is only ever one per layer.
     */
    private static View videoSurfaceView(String layerId) {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(layerId, "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        if (!(layer instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) layer;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i) instanceof android.view.TextureView) return g.getChildAt(i);
        }
        return null;
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
        // Which kind of lock wallpaper this push is for. The wallpaper process cannot work it
        // out for itself - it only knows which engines it has BUILT, and those are built once
        // per process - and this side re-reads it before every push anyway. See videoPath().
        out.putExtra("video", sVideoWallpaper);
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
        Xp.log(TAG + "cover bias = " + sBias);
        if (sCoverMode) pushArtAsync(true, false);
    }

    /** Asks the wallpaper process to re-upload the art it already has on disk. */
    private static void requestWallpaperReload(Context ctx) {
        ctx.sendBroadcast(wallpaperIntent("reload"));
        Xp.log(TAG + "wallpaper reload requested");
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
                    Xp.log(TAG + "art push superseded, dropping it");
                    return;
                }
                boolean last = attempt >= ART_TRIES - 1;
                Bitmap art = albumArt(ctx, last);
                int print = art == null ? 0 : artPrint(art);
                boolean stale = fresh && art != null && sArtPrint != 0 && print == sArtPrint;
                if ((art == null || stale) && !last) {
                    Xp.log(TAG + "art " + (art == null ? "not ready" : "still the old one")
                            + ", retrying (" + (attempt + 2) + "/" + ART_TRIES + ")");
                    tryPushArt(ctx, attempt + 1, fresh, gen);
                    return;
                }
                if (stale) {
                    // The next track off the same album really does have the same cover.
                    Xp.log(TAG + "same artwork as the last track, wallpaper left alone");
                    return;
                }
                // Only when something is really going out. A push with no art leaves the
                // wallpaper showing what it already showed, and recording 0 here would claim it
                // was empty and disarm the stale-art check on the next track change.
                if (art != null) sArtPrint = print;
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

    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static boolean ensureLockWallpaper(Context ctx, boolean force) {
        android.app.WallpaperManager wm = (android.app.WallpaperManager)
                ctx.getSystemService(Context.WALLPAPER_SERVICE);
        if (wm == null) return false;
        // Before anything else, and before `force` too - forcing is the same setBitmap and
        // does the same damage. See wallpaperKind(): a live wallpaper on either slot cannot
        // survive being replaced with a still, and this is the only place that could do it.
        String lockKind = wallpaperKind(ctx, "lock");
        boolean live = lockKind != null && !KIND_IMAGE.equals(lockKind);
        sVideoWallpaper = live;
        if (live) {
            // Nothing to repair, and nothing that may be written: setBitmap(FLAG_LOCK) would
            // unbind the live wallpaper for good. The cover still works - it just goes into
            // our own keyguard layer instead of the wallpaper process. See showVideoCover().
            Xp.log(TAG + "the lock screen has a " + lockKind + " wallpaper of its own; leaving "
                    + "the wallpaper alone and drawing the cover in the keyguard layer");
            return false;
        }
        if (lockKind == null) {
            // Nothing of its own on the lock slot, so it is showing the home wallpaper - and
            // if that one is live, the lock screen IS that live wallpaper. Copying "the home
            // wallpaper" would get a still frame of it at best and the stock picture at worst,
            // and either way the animation is gone.
            String homeKind = wallpaperKind(ctx, "home");
            if (homeKind != null && !KIND_IMAGE.equals(homeKind)) {
                Xp.log(TAG + "the lock screen is following a " + homeKind + " home wallpaper. "
                        + "Leaving it alone rather than replacing it with a still of itself.");
                return false;
            }
        }
        if (!force) {
            try {
                android.os.ParcelFileDescriptor lock =
                        wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
                if (lock != null) {
                    try {
                        lock.close();
                    } catch (Throwable ignored) {
                    }
                    // The slot being populated is all the COVER needs. What the transition out
                    // of it needs as well is for the picture in there to be the size of the
                    // screen - see fitLockWallpaperToScreen().
                    fitLockWallpaperToScreen(wm);
                    return true;
                }
            } catch (Throwable t) {
                Xp.log(TAG + "cannot read the lock wallpaper slot: " + t);
                return false;
            }
            Xp.log(TAG + "lock screen has no wallpaper of its own - the keyguard "
                    + "wallpaper engine cannot exist, so the cover has nowhere to go. Giving it a "
                    + "copy of the home wallpaper.");
        }
        try {
            Bitmap home = homeWallpaper(wm);
            if (home == null) {
                Xp.log(TAG + "no home wallpaper bitmap to copy");
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
            Xp.log(TAG + "lock wallpaper set at " + sScreenW + "x" + sScreenH
                    + "; the keyguard engine will be rebuilt");
            return true;
        } catch (Throwable t) {
            Xp.log(TAG + "could not set a lock wallpaper: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /**
     * The size of the last lock wallpaper this tried to re-fit, packed w<<32|h.
     *
     * Not a plain "done" flag: the user can change the wallpaper at any point and the next one
     * deserves its own attempt. What this stops is fighting the same picture over and over -
     * ensureLockWallpaper() runs before every art push, so a slot that cannot be rewritten would
     * otherwise be rewritten once per track change, forever.
     */
    private static volatile long sLockWpTried;

    /**
     * Puts an over-sized lock wallpaper back to the size of the screen, so leaving cover mode
     * can crossfade instead of cutting.
     *
     * Every frame of that crossfade re-uploads the WHOLE keyguard texture, and the texture is
     * the lock wallpaper at ITS OWN pixel size, not the screen's. Measured in the wallpaper
     * process, same phone, same 240ms fade, the only difference being the wallpaper's own
     * dimensions:
     *
     *   1200x2608 (= the screen)  12.5MB/frame  fine
     *   1579x3432 (1.7x)          21MB/frame    the GL thread falls behind far enough that the
     *                                           compositor picks up a half-built frame - the
     *                                           cover drawn small on black, in a corner
     *
     * So WallpaperProbe.fadeTooExpensive() refuses to fade above 1.5x the screen and swaps in
     * one frame instead. That is the right call at that point, but from the outside it reads as
     * "the transition only works if your wallpaper happens to be the right resolution", which is
     * not something anyone should have to know. The extra pixels buy nothing either way: the
     * picture is only ever drawn on this screen, and every draw throws them away. Re-fitting is
     * the same centre-crop ensureLockWallpaper() already applies to the copy it writes itself,
     * so both paths leave the slot in the same shape.
     *
     * Two things it will not do:
     *
     * - Touch a wallpaper whose aspect ratio is not the screen's. A picture that much wider is
     *   being used for something - the parallax a scrolling wallpaper pans through - and
     *   cropping it would change what the user SEES rather than only its resolution. A hard cut
     *   is the smaller harm.
     * - Take a second run at the same picture. See sLockWpTried.
     *
     * Runs on the art worker, off the main thread, and only from the branch where the lock slot
     * already holds a still of its own - live wallpapers returned above, long before this.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them.
    @SuppressLint("MissingPermission")
    private static void fitLockWallpaperToScreen(android.app.WallpaperManager wm) {
        int sw = sScreenW, sh = sScreenH;
        if (sw <= 0 || sh <= 0) return;
        int w = 0, h = 0;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options b =
                        new android.graphics.BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(in, null, b);
                w = b.outWidth;
                h = b.outHeight;
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "cannot measure the lock wallpaper: " + t);
            return;
        }
        if (w <= 0 || h <= 0) return;
        // The same gate the fade uses, and deliberately the same number: anything this leaves
        // alone is something WallpaperProbe will still agree to fade.
        if ((long) w * h * 2 <= (long) sw * sh * 3) return;
        float times = (float) (w * (double) h / (sw * (double) sh));
        // Aspect, to 1%. 1579x3432 and 1200x2608 are one picture at two resolutions; a panorama
        // is not, and centre-cropping one is a change nobody asked for.
        if (Math.abs((long) w * sh - (long) h * sw) * 100L > (long) h * sw) {
            Xp.log(TAG + "lock wallpaper is " + w + "x" + h + ", " + r2(times)
                    + "x the screen but not its shape - leaving it alone;"
                    + " the cover will swap in one frame rather than fade");
            return;
        }
        long size = ((long) w << 32) | (h & 0xffffffffL);
        if (sLockWpTried == size) return;
        sLockWpTried = size;
        Bitmap src = null, fitted = null;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options o =
                        new android.graphics.BitmapFactory.Options();
                // Decoded straight down where a power of two allows it: the full 21MB only ever
                // existed to be thrown away, and this is a phone.
                o.inSampleSize = sampleSizeFor(w, h, sw, sh);
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                src = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (src == null) {
                Xp.log(TAG + "lock wallpaper could not be decoded for re-fitting");
                return;
            }
            fitted = src.getWidth() == sw && src.getHeight() == sh ? src : fitKeepingColour(src, sw, sh);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(TAG + "lock wallpaper was " + w + "x" + h + " (" + r2(times)
                    + "x the screen) - re-fitted to " + sw + "x" + sh
                    + " so leaving cover mode can crossfade instead of cutting");
        } catch (Throwable t) {
            Xp.log(TAG + "could not re-fit the lock wallpaper: " + Log.getStackTraceString(t));
        } finally {
            if (fitted != null && fitted != src) fitted.recycle();
            if (src != null) src.recycle();
        }
    }

    /**
     * centerCrop() with the source's colour space carried through.
     *
     * The shared one draws into `Bitmap.createBitmap(w, h, ARGB_8888)`, and that bitmap is
     * sRGB. Everywhere else in this module that is exactly right - what goes in is album art
     * that was composed here. Here it is the user's own wallpaper, which on this phone can be
     * a Display P3 photo, and drawing P3 into an sRGB bitmap gamut-clips it: saturated reds and
     * greens come back duller, permanently, in a file the user did not ask to have rewritten.
     * The OEM cares too - its upload path calls BitmapUtils.checkColorSpace() on every bitmap.
     *
     * Everything else about the crop is deliberately identical to centerCrop(), because
     * identical is the point: read off the dex, the OEM maps this texture to the screen with
     * AnimImageGLProgram.changeMvpMatrixCrop(), which is setIdentityM() plus a single scale on
     * whichever axis overflows - a centred scale-to-cover with no translation and no crop hint.
     * So the pixels this bakes into the file are the pixels that were already on screen.
     */
    private static Bitmap fitKeepingColour(Bitmap src, int w, int h) {
        Bitmap out = null;
        try {
            android.graphics.ColorSpace cs = src.getColorSpace();
            if (cs != null && !cs.equals(android.graphics.ColorSpace.get(
                    android.graphics.ColorSpace.Named.SRGB))) {
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, src.hasAlpha(), cs);
                Xp.log(TAG + "keeping the wallpaper's colour space: " + cs.getName());
            }
        } catch (Throwable t) {
            // A colour space the framework will not hand back to createBitmap. sRGB it is -
            // the same thing that happened before this method existed.
            Xp.log(TAG + "cannot carry the wallpaper's colour space over: " + t);
        }
        if (out == null) return centerCrop(src, w, h);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        cv.drawBitmap(src, null, new android.graphics.RectF(
                        (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** The largest power of two that still leaves the picture covering the screen. */
    private static int sampleSizeFor(int w, int h, int sw, int sh) {
        int s = 1;
        while (w / (s * 2) >= sw && h / (s * 2) >= sh) s *= 2;
        return s;
    }

    /** The one kind of wallpaper this module can work with: a still picture. */
    private static final String KIND_IMAGE = "image";

    /**
     * What kind of wallpaper is on a slot - "image", "video", "sensor", "super_wallpaper", or
     * null when nothing here can say.
     *
     * This exists because getWallpaperFile(FLAG_LOCK) cannot tell two very different states
     * apart. It returns null when the lock screen follows the home wallpaper, and it returns
     * null when the lock screen has a LIVE wallpaper of its own, because a live wallpaper has
     * no bitmap file anywhere to hand back. ensureLockWallpaper read that null as the first
     * case and wrote a still copy of the home wallpaper into the lock slot - which is a
     * setBitmap(..., FLAG_LOCK), which unbinds the live wallpaper permanently. A user reported
     * exactly that: set a video lock wallpaper, play one track, and from then on the lock
     * screen is a still of the home wallpaper. Nothing here recorded what it replaced, so
     * leaving cover mode could not put it back either.
     *
     * Three sources, most authoritative first, all three read off the device:
     *
     * 1. MIUI's own record at /data/system/theme_magic/users/<u>/wallpaper/data/{lock,home}.xml.
     *    The root element is the answer - `wallpaper_data which="2" type="image"` - and the
     *    rest of the (38KB) tag is colour palettes. The files are 0777, so this needs no root,
     *    let alone the system uid SystemUI happens to have. Seen carrying last_type="video" on
     *    the home slot here, which is where the vocabulary is confirmed from.
     * 2. Settings.Secure `flag_lock_wallpaper_type` - lock only, and there is no home
     *    equivalent: `desktop_wallpaper_type` is unset in all three namespaces.
     * 3. Settings.Secure `constant_template_editor_info`, the lock screen editor's JSON, whose
     *    homeInfo/lockscreenInfo both carry a `resourceType`. The only source that covers home.
     *
     * Deliberately NOT WallpaperManager.getWallpaperInfo(FLAG_LOCK): on MIUI every wallpaper,
     * still or video, is drawn by the same com.miui.miwallpaper ImageWallpaper component, so
     * it answers "live" for all of them and distinguishes nothing.
     *
     * Unknown reads back as null, and null keeps the old behaviour. Being wrong in the shy
     * direction costs the cover on one phone; being wrong the other way costs someone their
     * wallpaper.
     */
    private static String wallpaperKind(Context ctx, String slot) {
        boolean lock = "lock".equals(slot);
        String kind = kindFromMiui(lock);
        String from = "MiuiWallpaperManager";
        if (kind == null) {
            kind = kindFromThemeMagic(slot);
            from = "MIUI's " + slot + ".xml";
        }
        if (kind == null && lock) {
            kind = kindFromSettings(ctx, "flag_lock_wallpaper_type");
            from = "flag_lock_wallpaper_type";
        }
        if (kind == null) {
            kind = kindFromEditorInfo(ctx, lock ? "lockscreenInfo" : "homeInfo");
            from = "constant_template_editor_info";
        }
        // On every track change, so only when the answer moves. Which it does: this is
        // checked before each push precisely because the user can change the wallpaper at
        // any moment, and the interesting moment is the one where it just became a video.
        String last = "lock".equals(slot) ? sLockKindLogged : sHomeKindLogged;
        if (!java.util.Objects.equals(last, kind)) {
            Xp.log(TAG + slot + " wallpaper is " + (kind == null ? "unknown" : kind)
                    + (kind == null ? " (neither MIUI's record nor Settings said)"
                                    : " per " + from));
            if ("lock".equals(slot)) sLockKindLogged = kind;
            else sHomeKindLogged = kind;
        }
        return kind;
    }

    private static String sLockKindLogged = "";
    private static String sHomeKindLogged = "";

    /**
     * MIUI's own answer, and the one to prefer: `MiuiWallpaperManager.getMiuiWallpaperType(which)`
     * is what MIUI asks itself, it returns the same vocabulary the files use ("image", "video",
     * "sensor", "super_wallpaper"), and both it and the SystemUI class holding the instance keep
     * their real names through R8 - so unlike everything below it, this should not need a new
     * heuristic per HyperOS version.
     *
     * `which` is 1 for home and 2 for lock, read off MiuiWallpaperManager's own constants rather
     * than assumed - though they do match WallpaperManager's FLAG_SYSTEM/FLAG_LOCK, and the
     * `which=` attribute in MIUI's XML agrees.
     */
    private static String kindFromMiui(boolean lock) {
        Object mgr = sKgWallpaperMgr;
        if (mgr == null) return null;
        try {
            Object wm = Xp.getObjectField(mgr, "mMiuiWallpaperManager");
            if (wm == null) return null;
            int which = miuiWhich(wm.getClass(), lock);
            Object type = Xp.callMethod(wm, "getMiuiWallpaperType", which);
            String s = type == null ? null : type.toString();
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            Xp.log(TAG + "MiuiWallpaperManager could not say what the "
                    + (lock ? "lock" : "home") + " wallpaper is: " + t);
            return null;
        }
    }

    private static int miuiWhich(Class<?> wmCls, boolean lock) {
        String name = lock ? "MI_WALLPAPER_WHICH_LOCK" : "MI_WALLPAPER_WHICH_HOME";
        try {
            java.lang.reflect.Field f = wmCls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return lock ? 2 : 1;
        }
    }

    private static String kindFromThemeMagic(String slot) {
        java.io.InputStream in = null;
        try {
            // uid / 100000 is the user id - UserHandle.PER_USER_RANGE, and UserHandle's own
            // accessors for it are all @hide.
            java.io.File f = new java.io.File("/data/system/theme_magic/users/"
                    + (android.os.Process.myUid() / 100000)
                    + "/wallpaper/data/" + slot + ".xml");
            if (!f.canRead()) return null;
            in = new java.io.FileInputStream(f);
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(in, null);
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                    e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                String type = p.getAttributeValue(null, "type");
                if (type != null && !type.isEmpty()) return type;
            }
        } catch (Throwable t) {
            Xp.log(TAG + "cannot read MIUI's " + slot + " wallpaper record: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String kindFromSettings(Context ctx, String key) {
        android.content.ContentResolver cr = ctx.getContentResolver();
        String[] got = new String[3];
        try {
            got[0] = android.provider.Settings.Secure.getString(cr, key);
            got[1] = android.provider.Settings.System.getString(cr, key);
            got[2] = android.provider.Settings.Global.getString(cr, key);
        } catch (Throwable ignored) {
        }
        for (String v : got) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * The lock screen editor's own record, which is the only one of these that covers the home
     * slot: there is no `desktop_wallpaper_type` key - checked on device, all three namespaces.
     *
     * `constant_template_editor_info` is a single Settings.Secure JSON blob written by
     * com.miui.aod, with the same shape under `homeInfo` and `lockscreenInfo`. Its
     * `resourceType` uses the same vocabulary as the XML's `type` - "image", "video".
     */
    private static String kindFromEditorInfo(Context ctx, String section) {
        try {
            String raw = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "constant_template_editor_info");
            if (raw == null || raw.isEmpty()) return null;
            org.json.JSONObject info = new org.json.JSONObject(raw)
                    .optJSONObject(section);
            if (info == null) return null;
            org.json.JSONObject wp = info.optJSONObject("wallpaperInfo");
            if (wp == null) return null;
            String type = wp.optString("resourceType", "");
            return type.isEmpty() ? null : type;
        } catch (Throwable t) {
            Xp.log(TAG + "cannot read " + section + " from the editor info: " + t);
            return null;
        }
    }

    /** The home wallpaper, decoded no larger than it needs to be for this screen. */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
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
            Xp.log(TAG + "reading the home wallpaper file failed: " + t);
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
     * Whether the cover has somewhere to go.
     *
     * Two conditions, and they are not the same one. The lock screen needs a wallpaper entry
     * of its own - about the ENTRY, not the picture: a copy of the home wallpaper counts, and
     * is exactly what ensureLockWallpaper() installs. And that wallpaper has to be a still
     * image, because a video or sensor one is drawn by an engine this module has nothing
     * hooked in, and ensureLockWallpaper will not (must not) convert it to a still.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static boolean hasLockWallpaper(Context ctx) {
        try {
            String kind = wallpaperKind(ctx, "lock");
            if (kind != null && !KIND_IMAGE.equals(kind)) return false;
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
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static void clearLockWallpaper(Context ctx) {
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            wm.clear(android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(TAG + "lock wallpaper cleared, back to following the home one");
        } catch (Throwable t) {
            Xp.log(TAG + "clearLockWallpaper failed: " + t);
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
        resetCollapseRecord();
        armTransitionTrace("entering cover mode");
        // Whatever the user decided about the last song does not carry into this one.
        sTapSuppressed = false;
        setDepthHidden(true);
        // Start the card where the OEM has it when animating, so the thumbnail fades out across
        // the spring's frames instead of blinking away before the clock has begun to move.
        // Without an animation there is nothing to fade, and the settled look goes on directly.
        sCardP = animate ? 0f : 1f;
        applyMediaCard();
        sCollapseMin = sClockScale;
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        sAppliedK = Float.NaN;
        sAppliedGlassV = Float.NaN;
        if (animate) {
            // RUNNING here as well as on the way out. The OEM's STATE_CHANGED curve settles in
            // ~600ms over this travel, measured, against ~371ms for RUNNING - so with only the
            // exit changed the toggle was lopsided, and the wallpaper crossfade could not be in
            // step with both. Apple's is symmetric and so is this now.
            springTo(SQUEEZE_FLOOR, EASE_RUNNING[0], EASE_RUNNING[1], "STATE_CHANGED", false);
            // The spring above cannot carry the card on a style that emits no notifY, and the
            // card is not the clock's business anyway. See oemDrivesCard().
            if (!oemDrivesCard()) animateCardTo(1f, null);
        } else {
            sHoldY = SQUEEZE_FLOOR;
            drive(SQUEEZE_FLOOR, false, "STATE_CHANGED");
        }
        // The reading may predate this cover - the card can come up on art that was pushed
        // before the user ever locked the phone - and the OEM will not re-colour on its own.
        recolorClock();
        saveState();
    }

    /**
     * Leaves cover mode, optionally on the way out rather than all at once.
     *
     * Animated, this is one spring and nothing else: it drives notifY back to where the system
     * last asked for it, and the collapse scale, the glass morph and the card's thumbnail are
     * all derived from that same y in applyCollapse(), so they move together for free. That is
     * what Apple's is - everything at once, over about 300ms - and it is also why there is no
     * duration constant here: the OEM's own STATE_CHANGED curve settles in about 445ms.
     *
     * The unanimated path is the old behaviour and still the right one when the display is off
     * (Choreographer frames stop, so a spring started there never settles) or when the clock was
     * never taken over in the first place.
     */
    private static void exitCoverMode(boolean animate) {
        sCoverMode = false;
        // The cover is on its way out, so the reading that coloured the clock describes the
        // wallpaper coming back even less than it described the old one. Dropped here rather
        // than at the settle: the clock is at its smallest now, so the colour going back to the
        // OEM's is at its least visible, and the animated exit keeps the cover's colour off the
        // frames in between.
        if (!Float.isNaN(sCoverLuma)) {
            sCoverLuma = Float.NaN;
            recolorClock();
        }
        armTransitionTrace("leaving cover mode");
        float natural = sLastSystemY;
        boolean canSpring = animate && screenOn() && sContainer != null
                && !Float.isNaN(sCollapseMin)
                && !Float.isNaN(natural) && natural > SQUEEZE_FLOOR;
        if (canSpring) {
            // The cut-out belongs to the wallpaper, and the wallpaper is already on its way
            // back by the time this runs, so it is restored now rather than at the settle.
            setDepthHidden(false);
            // No applyMediaCard() here on purpose: it would drop the guard, and the guard is
            // what draws every frame of the thumbnail coming back. abandonHold() releases it
            // when the spring lands.
            //
            // RUNNING, not the STATE_CHANGED curve the entry uses. Measured on device: the
            // OEM's STATE_CHANGED spring takes 610-636ms to settle over this 681px travel,
            // and because the card fade and the glass morph are both linear in y, the last
            // tenth of that is a thumbnail still fading in 300ms after the wallpaper has
            // landed. RUNNING is critically damped at response 0.18s, which settles in about
            // 300ms - Apple's number, and the wallpaper crossfade's 320ms.
            springTo(natural, EASE_RUNNING[0], EASE_RUNNING[1], "STATE_CHANGED", true);
            saveState();
            return;
        }
        // Same stand-in on the way out, and it has to start BEFORE abandonHold(): that is
        // what hands the card back, and handing it back is the thing being animated. It leaves
        // the card alone while this animator owns it, and the animator finishes the job.
        boolean fadeCard = animate && screenOn() && sCardP > 0f && sCardGuarded != null;
        if (fadeCard) {
            animateCardTo(0f, new Runnable() {
                @Override
                public void run() {
                    sCardP = 0f;
                    applyMediaCard();
                }
            });
        }
        abandonHold("cover mode off", true);
        setDepthHidden(false);
        if (!fadeCard) {
            // Hands the card back before the guard goes, or the last frame it drew stays.
            sCardP = 0f;
            applyMediaCard();
        }
        saveState();
    }

    /**
     * Puts the collapse back if anything dropped it. Cheap and idempotent: when the hold already
     * survived (the normal path now), this only re-states values that are already correct, so
     * waking from AOD shows no transition at all.
     */
    private static void reassertCoverClock() {
        reassertCoverClock(false);
    }

    /**
     * force is for a clock that has been REBUILT under us - a keyguard re-attach, which is also
     * what changing the lock screen clock style looks like from here.
     *
     * The hold surviving is not enough then. The hold is a number we coerce on the way through
     * setNotifY; the collapse is a scale and a translation living on the clock views themselves,
     * and a rebuilt clock is a fresh set of views with neither. Taking the early exit below left
     * the new clock at full size with the y still pinned - the symptom being a clock that simply
     * stops collapsing the moment the user picks a different style.
     */
    private static void reassertCoverClock(boolean force) {
        if (!sCoverMode) return;
        // Cover mode is being restored, not animated into - a wake, or a keyguard rebuilt under
        // us - so the card's progress is its settled value rather than a frame of something.
        // Without this it keeps whatever it was left with, which after a SystemUI restart is the
        // 0 that comes back from the state file: cover mode on, thumbnail still showing.
        setCardProgress(1f);
        sCollapseMin = sClockScale;
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        Float held = sHoldY;
        if (!force && held != null && Math.abs(held - SQUEEZE_FLOOR) < 1f) return;
        sAppliedK = Float.NaN;
        sAppliedGlassV = Float.NaN;
        sHoldY = SQUEEZE_FLOOR;
        drive(SQUEEZE_FLOOR, false, "STATE_CHANGED");
        settleCollapsedClock(0);
        Xp.log(TAG + "cover clock re-collapsed after wake");
    }

    /**
     * Frames the re-assert gets: six once the nudge has been adopted, and up to forty while
     * either the clock is unmeasurable or the offset is still moving, at 80ms apart.
     *
     * Forty is not a guess. Measured on the restart path: the clock is unmeasurable for the first
     * ten passes, and the date's own layout then slides from 230 to 266 over the next seven -
     * the OEM is still animating its squeeze towards the y we are holding it at, and the date
     * moves with it. Two readings 80ms apart differ by ten pixels the whole way down, so the
     * loop adopts nothing until that stops, which takes about three seconds. A shorter tail is
     * what left the first lock screen at the un-nudged 270: the readings were all real, and the
     * last of them just needed a partner.
     */
    private static final int CLOCK_SETTLE_TRIES = 6;
    private static final int CLOCK_SETTLE_MAX = 40;
    private static final long CLOCK_SETTLE_MS = 80L;

    /**
     * Places the collapsed clock again over the next few frames, then stops.
     *
     * The placement normally rides the OEM's own frames - an entry animates for 600ms and every
     * one of those frames runs it - but the re-assert after a SystemUI restart has no animation
     * to ride. The clock container attaches, we drive notifY once, and the OEM stops emitting
     * frames the moment it has nothing left to move. If that single frame lands before the
     * keyguard's clock has been measured (and it does: the container attaches at 0,0-0,0, and
     * `clock style not understood: date=MISSING glyphs=MISSING` is what that one frame says),
     * then placeCollapsedClock() bails and NOTHING runs it again - the clock sat uncollapsed, or
     * at a pivot taken from half a glyph box, until the user locked the screen and got a real
     * collapse to converge on. That is the "first time after a restart is always crooked" bug.
     *
     * Six frames rather than a condition: the interesting question is not whether this pass
     * worked but whether the LAYOUT has settled, and a pass that succeeded on a half-measured
     * clock is exactly the crooked case. By the last one the keyguard has been through measure,
     * layout and the OEM's own squeeze, and every pass writes the same numbers a normal frame
     * of the collapse would, so the extra ones cost nothing but are idempotent.
     */
    private static void settleCollapsedClock(final int attempt) {
        if (!sCoverMode || Float.isNaN(sCollapseMin)) return;
        Float y = sHoldY;
        if (y == null) return;
        // Overlapping chains are harmless - every pass writes the values the last one did - and
        // the alternative is bookkeeping for a method that runs a handful of times per restart.
        if (attempt > CLOCK_SETTLE_MAX) return;
        float p = coverProgress(y);
        if (Float.isNaN(p)) {
            // No natural y to measure against yet, and on a fresh SystemUI there may never be
            // one: coverProgress() needs a frame the OEM emitted on its own, and after a restart
            // the only frames going are ours. Held at the squeeze floor the collapse is complete
            // by definition - p is 1 whatever the natural y turns out to be - so this is not a
            // guess, it is the definition the hold was taken under. (This was the second reason
            // the first lock screen stayed wrong: the retry loop ran, and every pass through it
            // returned here.)
            if (y > SQUEEZE_FLOOR) return;
            p = 1f;
        }
        boolean placed = placeCollapsedClock(1f - p * (1f - sCollapseMin), y, p);
        // Two reasons to keep going, and both are the bug rather than a nicety.
        //
        // The clock may not be measurable yet - the keyguard is still laying out - and a pass
        // that never ran is the whole complaint; one that runs late costs a view write that
        // writes what is already there.
        //
        // And the date's offset is a feedback loop that refuses to adopt a reading until two
        // passes agree (its gain is 0.9, so it needs two or three readings to converge). With
        // the OEM's own squeeze still animating behind us the first readings are -3, -39, -42:
        // every one of them a real measurement, none of them agreeing with the last, and the
        // loop left holding 0. sNudge == sNudgeSample is what "it has adopted something" looks
        // like from outside - updateDateOffset assigns both from the same reading when it
        // commits - so that is the condition the tail is for.
        boolean adopted = !Float.isNaN(sNudgeSample) && sNudge == sNudgeSample;
        if (sVerbose) {
            Xp.log(TAG + "settle " + attempt + ": placed=" + placed + " adopted=" + adopted
                    + " nudge=" + r1(sNudge) + " sample=" + r1(sNudgeSample));
        }
        if (placed && adopted && attempt >= CLOCK_SETTLE_TRIES) return;
        if (attempt >= CLOCK_SETTLE_MAX) return;
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                settleCollapsedClock(attempt + 1);
            }
        }, CLOCK_SETTLE_MS);
    }

    /**
     * The combine behind nsslLockYPosition, whatever R8 called it this time.
     *
     * The name carries an ordinal that is assigned per build, so it cannot be written down: a
     * number measured on one HyperOS is simply a wrong guess on the next. The class loader's own
     * dex tables are asked instead, and the ordinal probe below only exists for the case where
     * those private fields have moved.
     */
    private static Class<?> findAvoidCombine(ClassLoader cl) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Field pf = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList");
            pf.setAccessible(true);
            Object pathList = pf.get(cl);
            java.lang.reflect.Field ef = pathList.getClass().getDeclaredField("dexElements");
            ef.setAccessible(true);
            for (Object el : (Object[]) ef.get(pathList)) {
                try {
                    java.lang.reflect.Field df = el.getClass().getDeclaredField("dexFile");
                    df.setAccessible(true);
                    Object dex = df.get(el);
                    if (dex == null) continue;
                    java.util.Enumeration<?> en = (java.util.Enumeration<?>)
                            dex.getClass().getMethod("entries").invoke(dex);
                    while (en.hasMoreElements()) {
                        Object o = en.nextElement();
                        if (!(o instanceof String)) continue;
                        // Normalised first: entries() has been seen returning both the internal
                        // form and the binary one, and matching only one of them would fail
                        // silently on the other.
                        String n = ((String) o).replace('/', '.');
                        if (n.startsWith(AVOID_PREFIX) && n.endsWith(AVOID_SUFFIX)) names.add(n);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "dex walk for the avoid combine failed: " + t);
        }
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (invoke3(c) != null) return c;
            } catch (Throwable ignored) {
            }
        }
        for (int i = 1; i <= 400; i++) {
            try {
                Class<?> c = Class.forName(AVOID_PREFIX + "$" + i + AVOID_SUFFIX, false, cl);
                if (invoke3(c) != null) return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Kotlin's Function3 bridge: invoke(FlowCollector, Object[], Continuation). */
    private static java.lang.reflect.Method invoke3(Class<?> c) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if ("invoke".equals(m.getName()) && m.getParameterTypes().length == 3) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * Is this drawable one of the fingerprint ring's frames?
     *
     * Asked by resource name rather than against a list of ids: ids are assigned per build, so a
     * list would have to be re-measured on every phone, while the names the OEM gives these
     * frames have been stable. The answer is cached because the question is asked inside a draw.
     */
    private static boolean isFodRing(int resId) {
        Boolean known = sFodRing.get(resId);
        if (known != null) return known;
        Context c = sAppCtx;
        // No context yet is not an answer worth remembering: it means the keyguard has not
        // attached, and the next draw will resolve properly.
        if (c == null) return false;
        boolean ring = false;
        try {
            String name = c.getResources().getResourceEntryName(resId);
            ring = name != null
                    && (name.startsWith("finger_circle") || name.contains("fingerprint_circle"));
        } catch (Throwable ignored) {
            // Not a resource in SystemUI's table, so not one of the OEM's ring frames. Cached
            // as such - the same id will come back on the next frame.
        }
        sFodRing.put(resId, ring);
        return ring;
    }

    /**
     * Applies the current setting to every icon view still alive. Runs on the main thread: the
     * receiver has no handler of its own, so it is already there.
     */
    private static void applyHideFp() {
        float alpha = sHideFp ? 0f : 1f;
        java.util.List<View> views;
        synchronized (sFodIcons) {
            views = new java.util.ArrayList<>(sFodIcons.keySet());
        }
        for (View v : views) {
            try {
                v.setAlpha(alpha);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void setDepthHidden(final boolean hide) {
        setDepthHidden(hide, 0);
    }

    /**
     * attempt exists because this runs on a SystemUI that has just started: cover mode is
     * restored the moment the clock container attaches, and the foreground layer that holds the
     * cut-out is not necessarily inflated yet. The old code logged "not found" once and gave up,
     * which left the old wallpaper's subject sitting on top of the album cover until something
     * else happened to call it again.
     */
    private static void setDepthHidden(final boolean hide, final int attempt) {
        sDepthHidden = hide;
        final View v = sContainer;
        if (v == null) {
            retryDepth(hide, attempt, "no clock container");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                View d = findDeductedImageView();
                if (d == null) {
                    retryDepth(hide, attempt, "deducted_image_view not found");
                    return;
                }
                d.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
                // On a live wallpaper the cut-out subject is a TextureView in the same layer
                // rather than this ImageView, and it is in front of the clock just the same.
                // Resolved every time rather than cached: it is added and removed by MIUI as
                // the wallpaper type changes.
                View fg = videoSurfaceView("keyguard_foreground_layer");
                sVideoFg = fg;
                // And the video itself, in the background layer. The cover is drawn over it
                // either way, but leaving it playing under an opaque view decodes a video
                // nobody can see - and if anything of it is still reaching the screen, this
                // is what says so.
                View bg = sVideoWallpaper ? videoSurfaceView("keyguard_background_layer") : null;
                sVideoBg = bg;
                setVideoSurfacesHidden(hide, bg, fg);
                if (hide) guardDepth(d); else releaseDepthGuard();
                Xp.log(TAG + "deducted_image_view " + (hide ? "hidden" : "shown")
                        + (fg == null ? "" : " (+ the live wallpaper's cut-out)"));
                saveState();
                // VideoDepthSurfaceHolder adds its TextureViews after SystemUI has started, so
                // on a live wallpaper the first pass through here can be too early - measured:
                // deducted_image_view was already there and the TextureView was not. Same
                // retry the cut-out itself gets, for the same reason.
                // Only while the keyguard is actually up. Off the lock screen the TextureViews
                // are legitimately absent, and retrying then just burns 12 passes and 12 log
                // lines every time anything asks for a re-hide.
                if (hide && sVideoWallpaper && fg == null && onKeyguardNow()) {
                    retryDepth(true, attempt, "the live wallpaper's cut-out is not in the tree yet");
                }
            }
        });
    }

    private static final int DEPTH_RETRIES = 12;
    private static final long DEPTH_RETRY_MS = 250L;

    private static void retryDepth(final boolean hide, final int attempt, String why) {
        if (attempt >= DEPTH_RETRIES) {
            Xp.log(TAG + why + ", giving up after " + attempt + " tries");
            return;
        }
        if (attempt == 0) Xp.log(TAG + why + ", retrying");
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                // Only still wanted if nothing has changed its mind in the meantime.
                if (sDepthHidden == hide) setDepthHidden(hide, attempt + 1);
            }
        }, DEPTH_RETRY_MS);
    }

    /**
     * Keeps the cut-out hidden for as long as cover mode wants it hidden. Installed on the view
     * itself, so a keyguard rebuild takes it away with the view and the next hide installs a
     * fresh one.
     */
    private static void guardDepth(final View d) {
        if (sDepthGuarded == d && sDepthGuard != null) return;
        releaseDepthGuard();
        sDepthGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (sDepthHidden && d.getVisibility() == View.VISIBLE) {
                    // VISIBLE -> INVISIBLE only invalidates, it does not request a layout, so
                    // this cannot start a traversal loop.
                    d.setVisibility(View.INVISIBLE);
                    if (++sDepthTakebacks <= 5 || sDepthTakebacks % 100 == 0) {
                        Xp.log(TAG + "system re-showed the cut-out, re-hidden ("
                                + sDepthTakebacks + ")");
                    }
                }
                // The live wallpaper's own TextureViews are NOT asserted here. guardVideoCover()
                // owns them, because whether they should be hidden depends on the cover being
                // on screen, which is a lock-screen question this guard cannot answer - and two
                // guards writing one property just take turns undoing each other.
                return true;
            }
        };
        d.getViewTreeObserver().addOnPreDrawListener(sDepthGuard);
        sDepthGuarded = d;
        Xp.log(TAG + "depth guard installed");
    }

    private static void releaseDepthGuard() {
        View d = sDepthGuarded;
        ViewTreeObserver.OnPreDrawListener g = sDepthGuard;
        sDepthGuarded = null;
        sDepthGuard = null;
        if (d == null || g == null) return;
        try {
            d.getViewTreeObserver().removeOnPreDrawListener(g);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- media card

    private static final int CARD_RETRIES = 12;
    private static final long CARD_RETRY_MS = 250L;
    /** How long the card has to hold still before its position is believed. */
    private static final long CARD_SETTLE_MS = 400L;

    private static View findSysuiView(String id) {
        View v = sContainer;
        if (v == null) return null;
        View root = v.getRootView();
        int i = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
        View hit = i == 0 ? null : root.findViewById(i);
        return hit != null ? hit : findByName(root, id);
    }

    /**
     * Finds a view by its resource entry name, walking the tree instead of resolving an id.
     *
     * getIdentifier() only searches the one package it is given, and not everything on the
     * keyguard comes from com.android.systemui's own resources: the two shortcut buttons at the
     * bottom came back "not found" by id while a dump of the very same tree listed them by name.
     * The name is read off each view's own Resources, which is exactly why the dump can see them.
     */
    private static View findByName(View v, String name) {
        try {
            if (v.getId() != View.NO_ID
                    && name.equals(v.getResources().getResourceEntryName(v.getId()))) {
                return v;
            }
        } catch (Throwable ignored) {
        }
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findByName(g.getChildAt(i), name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * Finds the card, applies the current look and leaves a guard behind to keep it applied.
     * Called whenever the card or cover mode changes; re-running it is free.
     *
     * The retries are the same story as the cut-out's: on a SystemUI that has just started,
     * cover mode is restored the moment the clock container attaches, which can be before the
     * notification stack has inflated anything.
     */
    private static void applyMediaCard() {
        applyMediaCard(0);
    }

    private static void applyMediaCard(final int attempt) {
        main().post(new Runnable() {
            @Override
            public void run() {
                View card = findSysuiView("mi_media_controls");
                if (card == null) {
                    // Only worth waiting for while something still wants something from the
                    // card: the restyle on the way in, or the artwork tap that is the way back.
                    if (attempt < CARD_RETRIES
                            && ((sCoverMode && (sMcHideArt || sMcCenterText || sMcTitleTap))
                                || wantsArtTap())) {
                        main().postDelayed(new Runnable() {
                            @Override
                            public void run() { applyMediaCard(attempt + 1); }
                        }, CARD_RETRY_MS);
                    }
                    return;
                }
                View art = card.findViewById(card.getResources()
                        .getIdentifier("album_art", "id", "com.android.systemui"));
                if (art != null && art != sCardArt) {
                    // A card view we have not written to. Forgetting what we wrote to the last
                    // one makes scaleArt() read this one's scale as the OEM's, which it is -
                    // otherwise a new thumbnail that happened to arrive carrying the number we
                    // left on the old view would be scaled against the wrong baseline.
                    sArtWroteX = sArtWroteY = Float.NaN;
                }
                sCardArt = art;
                sCardTitle = card.findViewById(card.getResources()
                        .getIdentifier("header_title", "id", "com.android.systemui"));
                sCardArtist = card.findViewById(card.getResources()
                        .getIdentifier("header_artist", "id", "com.android.systemui"));
                assertMediaCard(card);
                if (sCoverMode && (sMcHideArt || sMcCenterText || sMcTitleTap)) guardCard(card);
                else releaseCardGuard();
            }
        });
    }

    /**
     * One pass of "the card should look like this". Runs from the guard, so every write is
     * conditional on the value actually being wrong - setVisibility and setTranslationX only
     * invalidate, but setGravity requests a layout, and doing that unconditionally from a
     * pre-draw listener is a traversal loop.
     */
    private static void assertMediaCard(View card) {
        // The lock screen and the shade share one media card view, so "is the keyguard up" has
        // to be part of the condition rather than just cover mode: without it, pulling the shade
        // down on an unlocked phone would show the same restyled card, and these two settings
        // are about the lock screen. The clock container is the test - only the keyguard shows
        // it - and because this runs every frame, the card restores itself on unlock by itself.
        View c = sContainer;
        boolean onKeyguard = sCardForced || (keyguardShowing() && c != null && c.isShown());
        // A capture for the app's preview wants the settled look, not whichever frame an
        // animation happens to be on. Everywhere else the fade IS the state.
        float p = !onKeyguard ? 0f
                : sCardForced ? (sCoverMode ? 1f : 0f)
                : sCardP;
        float hideP = sMcHideArt ? p : 0f;
        float centreP = sMcCenterText ? p : 0f;
        View art = sCardArt;
        if (art != null) {
            // INVISIBLE at the far end, not GONE: the constraints around it are the card's
            // whole layout, and collapsing the artwork would drag the text sideways on its own
            // terms rather than ours. It also leaves the thumbnail's bitmap in place, which is
            // still the module's last-resort source for the cover itself.
            //
            // Anywhere short of the end it is VISIBLE with an alpha and a scale, and that is
            // the fade. Both only invalidate, so writing them from a pre-draw listener carries
            // the same guarantee the translationX below does, and the two resting states are
            // bit-for-bit the two the card had before there was an animation at all.
            if (hideP >= 1f) {
                if (art.getVisibility() != View.INVISIBLE) art.setVisibility(View.INVISIBLE);
                // Out of sight, but put back exactly as the OEM had it: the shade shows this
                // same view, and a thumbnail left at 0.82 of its size there would be ours.
                scaleArt(art, 1f);
                if (art.getAlpha() != 1f) art.setAlpha(1f);
            } else {
                if (art.getVisibility() != View.VISIBLE) art.setVisibility(View.VISIBLE);
                float a = 1f - hideP;
                if (art.getAlpha() != a) art.setAlpha(a);
                scaleArt(art, 1f - hideP * (1f - CARD_ART_MIN_SCALE));
            }
        }
        centreCardText(card, (TextView) sCardTitle, centreP);
        centreCardText(card, (TextView) sCardArtist, centreP);
        applyTitleTap((TextView) sCardTitle, sMcTitleTap && sCoverMode && onKeyguard);
        if (onKeyguard && !sCardForced) sampleCardRect(card);
    }

    /**
     * Makes the card's title a play/pause button, or hands the title back.
     *
     * Asserted from the guard rather than set once when the card was found, because the same
     * view is also the shade's: the lock screen's title is ours and the shade's is the OEM's,
     * and the guard is the only thing here that knows which of the two is on screen. Written
     * only when the state actually changes - the guard runs every frame and this puts a
     * listener on, which is not something to redo sixty times a second.
     *
     * Java has no way to read a view's existing OnClickListener back off it, so what the title
     * had is remembered as the two things that CAN be read - whether it was clickable, and
     * whether it had a listener at all - and the latter is logged, because a title that already
     * does something of its own would be silently swallowed here.
     */
    private static void applyTitleTap(TextView title, boolean on) {
        View mine = sCardTitleTapped;
        boolean want = on && title != null;
        if (mine == title && want) return;
        if (mine == null && !want) return;
        if (mine != null) {
            sCardTitleTapped = null;
            try {
                mine.setOnClickListener(null);
                mine.setClickable(sCardTitleClickable);
            } catch (Throwable ignored) {
            }
        }
        if (!want) return;
        sCardTitleClickable = title.isClickable();
        if (title.hasOnClickListeners()) {
            Xp.log(TAG + "card title already had a click listener - it will not be restored");
        }
        sCardTitleTapped = title;
        title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayback();
            }
        });
    }

    /**
     * Play or pause whatever the card is showing.
     *
     * Through the session the card is bound to rather than the media-key broadcast the OEM's
     * own button ends up sending: same result when nothing else is going on, but it cannot
     * drift onto a different player, which a key event can when two of them are open.
     * The state is read rather than toggled blind because the transport controls have play()
     * and pause() and no playPause() of their own.
     */
    private static void togglePlayback() {
        MediaController c = sWatched;
        if (c == null) {
            rebindSession();
            c = sWatched;
        }
        if (c == null) {
            Xp.log(TAG + "title tap: no session to play");
            return;
        }
        try {
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            MediaController.TransportControls t = c.getTransportControls();
            if (t == null) {
                Xp.log(TAG + "title tap: no transport controls");
                return;
            }
            if (playing) t.pause();
            else t.play();
            Xp.log(TAG + "title tap -> " + (playing ? "pause" : "play")
                    + " on " + c.getPackageName());
        } catch (Throwable t) {
            Xp.log(TAG + "title tap failed: " + t);
        }
    }

    /**
     * Centres one line of the card's text on the card.
     *
     * translationX only - it moves the view without touching the constraints, so the OEM can
     * re-run its own layout without fighting us and letting go is a single write back to zero.
     *
     * What is moved is the GLYPH RUN, not the view. The two obvious approaches both fail here:
     * the text views are constrained to the space beside the artwork, so centring the view in
     * the card leaves the text sitting at the left edge of a box that is wider than the words;
     * and setGravity(CENTER_HORIZONTAL) had no effect on this card at all (reported from the
     * device: text still hard left). Reading the drawn line straight off the Layout sidesteps
     * both - it already accounts for whatever gravity, padding and ellipsis are in force, so
     * the sum below is "where the ink starts now" against "where it should start".
     */
    private static void centreCardText(View card, TextView t, float p) {
        if (t == null) return;
        if (p <= 0f) {
            if (t.getTranslationX() != 0f) t.setTranslationX(0f);
            return;
        }
        Layout lay = t.getLayout();
        if (lay == null || lay.getLineCount() < 1 || card.getWidth() <= 0) return;
        float ink = lay.getLineWidth(0);
        if (ink <= 0f) return;
        float have = t.getLeft() + t.getPaddingLeft() + lay.getLineLeft(0);
        // Scaled by the progress, so the line slides between where the OEM put it and the
        // centre instead of jumping between the two.
        float dx = ((card.getWidth() - ink) / 2f - have) * p;
        if (t.getTranslationX() != dx) t.setTranslationX(dx);
    }

    /**
     * Records where the card is, for the app's preview.
     *
     * Only called with the keyguard actually showing: the same card is what the shade puts at
     * the top of the notification list, at a completely different height, and a preview drawn
     * from that reading would put the card where it never appears on the lock screen.
     *
     * A reading is only believed once it has held still for CARD_SETTLE_MS, which is the same
     * rule the date offset uses and for the same reason: the card slides in when it appears and
     * slides away again at unlock, so those frames are readings of a card in flight. Measured
     * without any settling rule: 62,1676 - the card twenty pixels off centre, caught on its way
     * out. Two identical frames were not enough either - a card can be laid out at its starting
     * position and sit there for a few frames before the animation begins, which is how a
     * reading 244px above the real one got through.
     */
    private static void sampleCardRect(View card) {
        // Not while dozing. AOD shows the keyguard and holds still for as long as it is up, so
        // it sails past the settle rule below - and it lays the card out somewhere else, which
        // is how the preview ended up drawing it too high. isInteractive() is false in doze.
        if (!card.isShown() || !screenOn()) return;
        int w = card.getWidth(), h = card.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        // On the lock screen the card lives in the notification area, below a clock pinned near
        // the top; it is never up by the status bar. Anything that high is the shade's copy of
        // the same view - the panel can be pulled down over the lock screen too, where the
        // keyguard test above still passes.
        if (loc[1] < sScreenH / 3) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (loc[0] != sCardSampleL || loc[1] != sCardSampleT
                || w != sCardSampleW || h != sCardSampleH) {
            sCardSampleL = loc[0];
            sCardSampleT = loc[1];
            sCardSampleW = w;
            sCardSampleH = h;
            sCardSampleAt = now;
            return;
        }
        if (now - sCardSampleAt < CARD_SETTLE_MS) return;
        if (loc[0] == sCardL && loc[1] == sCardT && w == sCardW && h == sCardH) return;
        sCardL = loc[0];
        sCardT = loc[1];
        sCardW = w;
        sCardH = h;
        Xp.log(TAG + "media card at " + sCardL + "," + sCardT + " " + sCardW + "x" + sCardH);
        saveStateSoon();
    }

    /**
     * One frame of the card fade, written by whatever computed the progress.
     *
     * Storing the number would not be enough. The guard is a pre-draw listener, and a card that
     * nothing has invalidated does not draw - a progress that only lived in a field would sit
     * there until the OEM next happened to touch the card. Writing through here invalidates it,
     * and the guard goes on doing what it always did: catching the frames the OEM starts.
     */
    /**
     * Whether the OEM's own squeeze will carry the card's progress on this clock style.
     *
     * The card fade was deliberately given no animator of its own: it rides the same 0..1 that
     * applyCollapse() derives from the clock's notifY, so one OEM spring moves the collapse, the
     * glass and the card together. That holds only where there IS a notifY to ride.
     *
     * On the classic clock style there is not. notifStateChange never reaches
     * KeyguardClockNotifInteractor.setNotifY there, so sLastSystemY is never learned and stays
     * NaN from process start - measured with verbose on: zero setNotifY calls across a full
     * enter and exit. No natural Y means no progress, which took the two card settings down
     * with the clock's collapse even though they have nothing to do with the clock.
     */
    private static boolean oemDrivesCard() {
        float natural = sLastSystemY;
        return !Float.isNaN(natural) && natural > SQUEEZE_FLOOR;
    }

    /** Matches the exit spring's settle and the wallpaper crossfade, so they land together. */
    private static final long CARD_FADE_MS = 320L;
    private static ValueAnimator sCardAnim;

    /**
     * Walks the card's progress on our own frames, for the styles where the OEM has none.
     *
     * Only ever a stand-in: where oemDrivesCard() is true this is not started at all and the
     * behaviour is exactly what it was. Writing through setCardProgress() means the guard and
     * the invalidation work the same way for both drivers.
     */
    private static void animateCardTo(final float target, final Runnable onEnd) {
        stopCardAnim();
        final float from = sCardP;
        if (from == target) {
            if (onEnd != null) onEnd.run();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(from, target);
        a.setDuration(CARD_FADE_MS);
        a.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                setCardProgress((Float) v.getAnimatedValue());
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator v) {
                if (sCardAnim == v) sCardAnim = null;
                if (onEnd != null) onEnd.run();
            }
        });
        sCardAnim = a;
        a.start();
    }

    private static void stopCardAnim() {
        ValueAnimator a = sCardAnim;
        sCardAnim = null;
        if (a != null) a.cancel();
    }

    private static void setCardProgress(float p) {
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        if (sCardP == p) return;
        sCardP = p;
        View card = sCardGuarded;
        if (card != null) assertMediaCard(card);
    }

    /**
     * Whether the card's artwork is currently a toggle for cover mode.
     *
     * Both directions: the thumbnail is what put the cover up, so it is also the natural thing
     * to tap to put the wallpaper back. With the restyle's hide-artwork option on, the way back
     * falls out on its own - the thumbnail is INVISIBLE in cover mode by the time anyone could
     * tap it, and the rectangle test asks isShown(). Off the lock screen the card belongs to the
     * shade, where the OEM's own click is the right one.
     */
    private static boolean wantsArtTap() {
        return sTapToggle && sCardShowing;
    }

    /**
     * Makes the card's artwork toggle cover mode instead of opening the player.
     *
     * This has to happen at the window's dispatch, not on album_art itself. A touch listener on
     * the child was the obvious answer and does not work: on device it never fired once, so
     * something above the card claims the gesture before album_art is ever offered it. The
     * shade window's dispatchTouchEvent is upstream of all of that - it is where our own lock
     * screen taps already arrive - so consuming there is the one place that is certain to win.
     *
     * Consuming means returning true WITHOUT proceeding, for the whole gesture: taking only the
     * DOWN would leave the OEM tracking a stream whose beginning it never saw. Which is also
     * why the rect is re-read from the live view on every DOWN rather than from the sampled
     * card rectangle - the card sits somewhere else with the clock collapsed than without it,
     * and swallowing touches over empty wallpaper would be a bug the user could not explain.
     *
     * The cost is that the thumbnail's 158x158 stops being draggable for as long as it is the
     * thing the tap would act on. That is the trade, and it is why the UP has to have stayed
     * inside and been brief: a drag that began on the artwork does nothing rather than toggling
     * on release.
     */
    private static boolean swallowArtTap(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean onCard = wantsArtTap() && screenOn() && keyguardShowing() && onKeyguardNow()
                    && !bouncerUp();
            sArtSwallow = onCard && artRectContains(ev.getRawX(), ev.getRawY());
            if (sArtSwallow) {
                sArtDownAt = android.os.SystemClock.uptimeMillis();
            } else if (onCard && sVerbose) {
                // For telling "the rectangle is wrong" from "the state is wrong" without
                // guessing, which is how the mirror above was found.
                Xp.log(TAG + "artwork tap missed at " + ev.getRawX() + "," + ev.getRawY()
                        + " (art " + artRectDesc() + ")");
            }
            return sArtSwallow;
        }
        if (!sArtSwallow) return false;
        if (action == MotionEvent.ACTION_UP) {
            sArtSwallow = false;
            long held = android.os.SystemClock.uptimeMillis() - sArtDownAt;
            if (held < ART_TAP_MS && artRectContains(ev.getRawX(), ev.getRawY())) {
                // The same rectangle is both the way in and the way out, and which one it is
                // is read at UP rather than at DOWN: a cover that came up under the finger
                // during the gesture (the OEM can re-lay the card out at any point) would
                // otherwise send the tap the wrong way.
                if (sCoverMode) exitFromTap("artwork tapped");
                else enterFromTap("artwork tapped");
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            sArtSwallow = false;
        }
        return true;
    }

    /** The artwork's rectangle as this code sees it, for the log line above. */
    private static String artRectDesc() {
        View a = sCardArt;
        if (a == null) return "null";
        android.view.ViewParent p = a.getParent();
        if (!(p instanceof View)) return "unparented";
        int[] loc = new int[2];
        ((View) p).getLocationOnScreen(loc);
        return (loc[0] + a.getLeft()) + "," + (loc[1] + a.getTop())
                + " " + a.getWidth() + "x" + a.getHeight() + " shown=" + a.isShown();
    }

    /**
     * Shrinks the card's artwork to `factor` of whatever scale the OEM is giving it.
     *
     * Anything on the view that we did not put there is the OEM's, and remembering what we
     * last wrote is what tells the two apart. So the baseline is re-read on the first frame,
     * on a new card view, and on any frame the OEM's own animation has written over ours -
     * it follows the OEM live rather than latching one frame of it - and factor 1 writes that
     * baseline straight back. Sign included: see sArtBaseX for what a sampled scaleX cost.
     */
    private static void scaleArt(View art, float factor) {
        float x = art.getScaleX(), y = art.getScaleY();
        // Only adopt a reading that could be a RESTING value. The OEM animates this view, and
        // a frame caught mid-animation is not a baseline: catching a 0 made the thumbnail
        // vanish for good, because from then on every frame wrote 0 * factor and the value it
        // read back was its own 0. Magnitude has to be ~1 - and only the magnitude, because
        // the sign is the OEM's mirror and taking that as "not resting" is what flipped the
        // thumbnail before. Anything else leaves the last good baseline in place.
        if ((x != sArtWroteX || y != sArtWroteY) && resting(x) && resting(y)) {
            sArtBaseX = x;
            sArtBaseY = y;
        }
        float nx = sArtBaseX * factor, ny = sArtBaseY * factor;
        if (x != nx) art.setScaleX(nx);
        if (y != ny) art.setScaleY(ny);
        sArtWroteX = nx;
        sArtWroteY = ny;
    }

    /** A scale that could be the OEM's resting one: unit magnitude, either sign. */
    private static boolean resting(float v) {
        return Math.abs(Math.abs(v) - 1f) < 0.02f;
    }

    /**
     * Where the card's thumbnail is on screen right now.
     *
     * NOT getLocationOnScreen() on the artwork. That maps (0,0) through the view's own matrix
     * before adding mLeft, and this view has a matrix: measured, album_art reports an origin
     * exactly its own width (158px) to the right of its layout slot, with translationX 0 and
     * scaleX 1 - a horizontal mirror, the OEM's own. The reported rectangle therefore lands on
     * the title, which is what "tapping the title expands it, not the thumbnail" was.
     *
     * A mirror does not move what the user sees, so the layout slot IS the visible rectangle.
     * Taking it from the card - which carries no transform of its own - plus the child's
     * getLeft()/getTop() sidesteps the artwork's matrix while still picking up every ancestor's.
     */
    private static boolean artRectContains(float x, float y) {
        View a = sCardArt;
        if (a == null || !a.isShown() || a.getWidth() <= 0 || a.getHeight() <= 0) return false;
        android.view.ViewParent p = a.getParent();
        if (!(p instanceof View)) return false;
        int[] loc = new int[2];
        ((View) p).getLocationOnScreen(loc);
        float left = loc[0] + a.getLeft(), top = loc[1] + a.getTop();
        // A little slop, because the card is often still sliding when the finger lands. Small
        // enough to stay clear of the title, which starts just to the right of it.
        return x >= left - ART_TAP_SLOP && x < left + a.getWidth() + ART_TAP_SLOP
                && y >= top - ART_TAP_SLOP && y < top + a.getHeight() + ART_TAP_SLOP;
    }

    /**
     * The keyguard is actually in front. The clock container is the test - only the keyguard
     * shows it - and it is what keeps the shade's copy of this same card view out of all this.
     */
    private static boolean onKeyguardNow() {
        View c = sContainer;
        return c != null && c.isShown();
    }

    /**
     * Whether the bouncer - the PIN or pattern pad - is over the lock screen.
     *
     * The clock container is NOT this test, however well it works for the shade: measured with
     * the pad up, the clock stays shown, the card stays up and the keyguard stays locked, so
     * every guard the cover tap had was still true and a tap aimed at a digit toggled the cover
     * instead. The keyguard being locked does not mean the lock screen is what is on top.
     *
     * The pad hangs off its own child of the shade window, and MIUI keeps that child INVISIBLE
     * - not GONE - until the bouncer is summoned, so isShown() is exactly "the pad is up".
     * Falling back to false when the view cannot be found keeps the tap working on a build that
     * renamed it, rather than silently switching the whole feature off.
     */
    private static boolean bouncerUp() {
        View b = findSysuiView("keyguard_bouncer_container");
        return b != null && b.isShown();
    }

    /**
     * Back to the plain wallpaper, with the card left standing where it is.
     *
     * sTapSuppressed is what holds it that way. A card being up is exactly what the module reads
     * as "the cover belongs here", so without it the next metadata event would put the cover
     * straight back. It is the card actually going away that clears it - not a track change -
     * so the next song starts in cover mode as it always did.
     */
    private static void exitFromTap(String why) {
        sTapSuppressed = true;
        Xp.log(TAG + why + ": leaving cover mode");
        setCoverEnabled(false, true);
    }

    /**
     * Back into cover mode, through the normal path rather than by re-pushing whatever was last
     * composed: the track may well have moved on while the cover was off.
     */
    private static void enterFromTap(String why) {
        sTapSuppressed = false;
        sTrackKey = "";
        Xp.log(TAG + why + ": expanding into cover mode");
        onMediaUpdate();
    }

    private static void guardCard(final View card) {
        if (sCardGuarded == card && sCardGuard != null) return;
        releaseCardGuard();
        sCardGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                assertMediaCard(card);
                return true;
            }
        };
        card.getViewTreeObserver().addOnPreDrawListener(sCardGuard);
        sCardGuarded = card;
        Xp.log(TAG + "media card guard installed");
    }

    private static void releaseCardGuard() {
        View d = sCardGuarded;
        ViewTreeObserver.OnPreDrawListener g = sCardGuard;
        sCardGuarded = null;
        sCardGuard = null;
        // The guard is what gives the title back, so it has to happen here: with the guard gone
        // nothing would ever run the assert that would have done it, and the shade would keep a
        // title that pauses the music.
        applyTitleTap(null, false);
        if (d == null || g == null) return;
        try {
            d.getViewTreeObserver().removeOnPreDrawListener(g);
        } catch (Throwable ignored) {
        }
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
        // The sharp band, feathered in its own pixels and then drawn in one go. See feathered().
        Bitmap band = feathered(src, w, Math.round(coverH),
                Math.min(240, Math.round(coverH / 4f)));
        cv.drawBitmap(band, null, new android.graphics.RectF(0, top, w, top + coverH), p);
        band.recycle();
        return out;
    }

    /**
     * The artwork at full width and its own aspect, with the top and bottom `feather` rows faded
     * to transparent - in the pixels, not by masking a layer.
     *
     * Masking is what this replaces, and it put a one-pixel line along the band's top edge. The
     * band was drawn into a saveLayer whose bounds begin at a fractional `top`, and the mask
     * rect over it was snapped to whole pixels by a different rule than the layer was, so the
     * band's first row could fall outside the mask and land on the blurred background at full
     * strength - a line that follows the artwork's own brightness, bright where the row below is
     * bright and dark where it is dark, which is exactly what it looked like on the phone.
     *
     * Here the ramp is part of the picture, so that first row has nothing to show whatever the
     * rounding does. The mask rects are on a bitmap whose edges are 0 and bandH - both whole
     * pixels - so there is no fractional bound left for anything to disagree about.
     */
    private static Bitmap feathered(Bitmap src, int w, int bandH, int feather) {
        Bitmap band = Bitmap.createBitmap(w, bandH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(band);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, 0, w, bandH),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        if (feather > 0 && feather * 2 <= bandH) {
            android.graphics.Paint mask = new android.graphics.Paint();
            mask.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_IN));
            mask.setShader(new android.graphics.LinearGradient(0, 0, 0, feather,
                    0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP));
            bc.drawRect(0, 0, w, feather, mask);
            mask.setShader(new android.graphics.LinearGradient(0, bandH - feather, 0, bandH,
                    0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            bc.drawRect(0, bandH - feather, w, bandH, mask);
        }
        return band;
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
            Xp.log(TAG + "no clock container");
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
                        Xp.log(TAG + "keyguard_background_layer not found");
                        return;
                    }
                    Bitmap art = albumArt(ctx);
                    if (art == null) {
                        Xp.log(TAG + "no album art available");
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
                    Xp.log(TAG + "cover attached, layer "
                            + layer.getWidth() + "x" + layer.getHeight());
                } catch (Throwable t) {
                    Xp.log(TAG + "attachCover failed: " + Log.getStackTraceString(t));
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
        Xp.log(TAG + "auto mode " + (on ? "on" : "off"));
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
                        Xp.getObjectField(mediaData, "token");
            } catch (Throwable t) {
                sCardToken = null;
            }
            sCardKey = cardKey(mediaData);
        } else {
            sCardToken = null;
            sCardKey = "";
        }
        Xp.log(TAG + "media card " + (showing ? "-> " + sCardKey : "gone"));
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
            // The card going away is what clears a tap-dismissed cover: that decision was about
            // this session, and the next thing the user plays starts from the cover again.
            sTapSuppressed = false;
            if (sCoverMode) {
                Xp.log(TAG + "media card dismissed, leaving cover mode");
                setCoverEnabled(false, true);
            }
            return;
        }
        // The card is rebuilt around a track change, so this is where a fresh one is found -
        // and with the cover tapped away it is still where the tap listener is put back.
        if (sCoverMode || wantsArtTap()) applyMediaCard();
        rebindSession();
    }

    /** Track identity as the card itself sees it. */
    private static String cardKey(Object mediaData) {
        try {
            return Xp.getObjectField(mediaData, "packageName")
                    + "|" + Xp.getObjectField(mediaData, "song")
                    + "|" + Xp.getObjectField(mediaData, "artist");
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
                Xp.log(TAG + "active-session listener registered");
            }
        } catch (Throwable t) {
            Xp.log(TAG + "session listener failed: " + Log.getStackTraceString(t));
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
                    Xp.log(TAG + "following " + c.getPackageName());
                } catch (Throwable t) {
                    Xp.log(TAG + "registerCallback failed: " + t);
                }
            } else {
                Xp.log(TAG + "no active media session");
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
            Xp.log(TAG + "card token unusable: " + e);
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
        // The user tapped the cover away and the card is still up. The one case where "there is
        // a card" must not mean "put the cover back".
        if (sTapSuppressed) return;
        String key = sCardKey.isEmpty() ? trackKey(sWatched) : sCardKey;
        if (sCoverMode && key.equals(sTrackKey)) return;
        sTrackKey = key;
        Xp.log(TAG + "card track: " + key);
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
                else exitCoverMode(animate && screenOn());
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main().post(r);
    }

    /**
     * Springing the clock needs Choreographer frames, and those stop while the display is off.
     * An animation started then never settles, and leaves the hold pinned half way.
     */
    /**
     * Whether the lock screen is actually up.
     *
     * The clock container being shown is NOT this test, which is what the card rectangle used to
     * be sampled on: the keyguard's views can be laid out and VISIBLE with the phone unlocked
     * and something else in front of them - measured, vis=0 with the app in the foreground - so
     * pulling the shade down handed us the card at its position in the panel, up in the corner,
     * and the preview then drew the lock screen's card up there too.
     */
    private static boolean keyguardShowing() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    sAppCtx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Feeds the lock screen's touch stream to a detector without taking part in it.
     *
     * GestureDetector needs the whole DOWN..UP sequence, which is why this hangs off the shade
     * window's dispatchTouchEvent rather than any one child: it is the single point every event
     * passes through before the OEM decides what to do with it. The detector's answer is
     * discarded - consuming a DOWN here would take swipe-to-unlock with it.
     */
    private static void feedTap(MotionEvent ev) {
        if (!sTapToggle || ev == null) return;
        if (sTapDetector == null) {
            Context c = sAppCtx;
            if (c == null) return;
            // Constructed from inside the dispatch, so the looper it picks up is the one the
            // lock screen draws on, which is where the callback has to land.
            sTapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    onLockTap(e.getRawY());
                    return false;
                }
            });
        }
        try {
            sTapDetector.onTouchEvent(ev);
        } catch (Throwable ignored) {
        }
    }

    /**
     * A tap on the cover toggles cover mode.
     *
     * "On the cover" is everything between the status bar and the top of the media card. The
     * card and everything below it belongs to the OEM - the transport buttons, the notification
     * list, the two shortcuts in the corners - and a tap down there still means what it always
     * meant. The card rectangle is the one sampled for the app's preview, and the fallback is a
     * fraction of the screen rather than a pixel count because it stands in for a measurement
     * this device has simply not taken yet.
     */
    private static void onLockTap(float y) {
        if (!sTapToggle) return;
        if (!screenOn() || !keyguardShowing()) return;
        // Only the keyguard shows the clock container, so this is also what rules out the shade
        // being pulled down over an unlocked phone - the same test the card restyle uses.
        View c = sContainer;
        if (c == null || !c.isShown()) return;
        // The pad is up, so the taps on it are its own.
        if (bouncerUp()) return;
        // Nothing to toggle without music: the card is the switch, and this only chooses
        // whether the cover follows it.
        if (!sCardKnown || !sCardShowing) return;
        float top = sScreenH * 0.08f;
        // Measured live where possible. The sampled rectangle is a settled reading taken for
        // the app's preview and it lags: seen at 1360 while the card was actually at 1700,
        // which as a boundary would be 340px of cover that answers no tap at all.
        float bottom;
        View card = sCardGuarded;
        if (card != null && card.isShown() && card.getHeight() > 0) {
            int[] loc = new int[2];
            card.getLocationOnScreen(loc);
            bottom = loc[1];
        } else {
            bottom = sCardT > sScreenH / 3 ? sCardT : sScreenH * 0.62f;
        }
        if (y < top || y > bottom) return;
        if (sCoverMode) {
            exitFromTap("tap at y=" + y);
        } else if (sTapSuppressed) {
            enterFromTap("tap at y=" + y);
        }
    }

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
                    releaseCoverGuard();
                    ViewGroup p = (ViewGroup) iv.getParent();
                    if (p != null) p.removeView(iv);
                    iv.setImageDrawable(null);
                    // Only the composed one is ours - albumArt() hands back a bitmap the media
                    // session owns, and recycling that would take the card's thumbnail with it.
                    Bitmap b = sCoverBitmap;
                    sCoverBitmap = null;
                    if (b != null) b.recycle();
                    // The live wallpaper was only hidden because this view was covering it.
                    // Whatever removed the view - an exit, a keyguard rebuild, a failure part
                    // way through - the wallpaper has to come back with it, or the lock screen
                    // is left blank. Off the lock screen that hand-back is deferred rather than
                    // dropped; setVideoSurfacesHidden() says why.
                    View bg = sVideoBg, fg = sVideoFg;
                    sVideoBg = null;
                    sVideoFg = null;
                    setVideoSurfacesHidden(false, bg, fg);
                    Xp.log(TAG + "cover detached"
                            + (bg == null && fg == null ? ""
                               : sVideoWpOwed ? ", live wallpaper owed back"
                               : ", live wallpaper restored"));
                } catch (Throwable t) {
                    Xp.log(TAG + "detachCover failed: " + t);
                }
            }
        });
    }

    /**
     * The luminance every colour decision is made against, or NaN when there is nothing to make
     * one against - no cover measured, cover mode off - and the OEM's colouring is therefore
     * left exactly as it is.
     *
     * The screen check is the AOD one. Cover mode outlives the display going off, and the
     * glyphs are the same TimeViews in the always-on view: forcing the clock dark for a light
     * cover on a black AOD face is the one way this could make things worse than it found them.
     * `isInteractive` is false in AOD, so the OEM's own colouring stands there.
     */
    private static float coverLuma() {
        if (!sCoverMode || !screenOn()) return Float.NaN;
        float forced = sCoverLumaOverride;
        return Float.isNaN(forced) ? sCoverLuma : forced;
    }

    /**
     * The OEM's colour with only its lightness moved, or that same colour back when there is no
     * cover to judge it against.
     *
     * This is the whole fix for the light cover. The system's automatic clock colouring is
     * computed from the wallpaper's palette, and the cover replaces the wallpaper behind
     * SystemUI's back, so the palette arriving here describes a picture the clock is no longer
     * drawn on: a dark wallpaper gives the OEM light glyphs, the cover underneath them is light
     * too, and the small clock all but disappears - Taylor Swift's *Lover* is the case that
     * started this. Hue and saturation are what make the clock look like it belongs, so they
     * survive untouched; lightness is what decides whether it can be read at all, so that comes
     * from the cover.
     */
    private static int legible(View v, int argb) {
        float luma = coverLuma();
        if (Float.isNaN(luma)) return argb;
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(argb, hsv);
        hsv[2] = luma < COVER_DARK_BELOW ? GLYPH_LIGHT_V : GLYPH_DARK_V;
        int out = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(argb), hsv);
        if (sVerbose) {
            Xp.log(TAG + "colour " + Integer.toHexString(argb) + " -> "
                    + Integer.toHexString(out) + " over luma " + r2(luma) + " " + viewIdOf(v));
        }
        return out;
    }

    /**
     * Averages the strip of the composed cover the clock and its date are drawn on.
     *
     * Sampled from the composed bitmap rather than from the album art, because the two are not
     * the same picture where it matters: the sharp band starts below the clock at the default
     * bias, so what is actually behind the glyphs is the mirrored blur - and on a light cover
     * that blur is light. Costs 24 rows of getPixels on the worker that is already encoding the
     * same bitmap.
     */
    private static void measureCover(Bitmap full) {
        try {
            float d = sAppCtx.getResources().getDisplayMetrics().density;
            int top = Math.max(0, Math.round(BAND_TOP_DP * d));
            int bottom = Math.min(full.getHeight(), Math.round(BAND_BOT_DP * d));
            if (bottom - top < 8) { sCoverLuma = Float.NaN; return; }
            int stride = Math.max(1, (bottom - top) / 24);
            int[] row = new int[full.getWidth()];
            double sum = 0;
            int n = 0;
            for (int y = top; y < bottom; y += stride) {
                full.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int x = 0; x < row.length; x += 8) {
                    sum += android.graphics.Color.luminance(row[x]);
                    n++;
                }
            }
            if (n == 0) { sCoverLuma = Float.NaN; return; }
            sCoverLuma = (float) (sum / n);
            Xp.log(TAG + "cover luma " + r2(sCoverLuma) + " over " + n
                    + "px, band " + top + ".." + bottom);
        } catch (Throwable t) {
            sCoverLuma = Float.NaN;
            Xp.log(TAG + "cover luma failed: " + t);
        }
    }

    /**
     * Asks the OEM for its clock colours again, so a cover that has just changed is judged
     * against the new one.
     *
     * Nothing else will. The colour pass runs when the wallpaper's palette changes, and swapping
     * the cover is not one - it happens behind the wallpaper's back. Without this a track change
     * would keep the previous cover's decision, which on the way from a light cover to a dark
     * one means a dark clock left dark on a dark picture: worse than the problem this exists to
     * fix. The values handed over are the OEM's own, off the style it is already holding; the
     * setter hooks are what make them come out legible.
     */
    private static void recolorClock() {
        final View v = sContainer;
        if (v == null) return;
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    try {
                        if (!(root instanceof ViewGroup)) continue;
                        View c = ((ViewGroup) root).getChildAt(0);
                        if (c == null) continue;
                        if (glassGlyphs(root)) {
                            // The glass is driven from our own morph, and re-running one frame of
                            // it is what makes it re-colour - the same call applyGlassMorph()
                            // makes on every frame of a collapse, so nothing new is being asked
                            // of it. updateClockColor, which is the other path, is deliberately
                            // NOT called here: the OEM only reaches for it on a solid style, and
                            // it writes the glyphs' paint colour as well as their glass data.
                            if (!Float.isNaN(sAppliedGlassV)) {
                                Xp.callMethod(c, "updateGlassValue", sAppliedGlassV);
                            }
                        } else {
                            Object info = Xp.getObjectField(c, "mClockStyleInfo");
                            Xp.callMethod(c, "updateClockColor",
                                    Xp.callMethod(info, "getPrimaryColor"),
                                    Xp.callMethod(info, "getSecondaryColor"));
                        }
                    } catch (Throwable t) {
                        Xp.log(TAG + "recolor failed: " + t);
                    }
                }
                // The date is on neither of those paths - the glass never touches it, and the
                // palette is the only thing that colours it. Handing it back its own current
                // colour is enough: the setter hook is what turns that into the legible one.
                View date = visibleDate();
                if (date instanceof TextView) {
                    try {
                        Xp.callMethod(date, "setTextColor", ((TextView) date).getCurrentTextColor());
                    } catch (Throwable t) {
                        Xp.log(TAG + "date recolour failed: " + t);
                    }
                }
            }
        });
    }

    /** Whether these glyphs are drawn through the MiGlass shader, read off the views themselves. */
    private static boolean glassGlyphs(View root) {
        View v = sContainer;
        if (v == null) return false;
        for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
            int rid = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
            View t = rid == 0 ? null : root.findViewById(rid);
            if (t == null) continue;
            try {
                return Boolean.TRUE.equals(Xp.getObjectField(t, "isMiGlassEffectEnable"));
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
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
                Xp.callMethod(c, "updateGlassValue", g);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Calls a method on the AllInOneHourClock / AllInOneMinuteClock views of both trees. */
    private static void callOnClockViews(final String method, final String kind, final float f,
                                         final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { Xp.log(TAG + "callclock: need m="); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                Object arg = "b".equals(kind) ? (Object) b : "i".equals(kind) ? (Object) n : (Object) f;
                for (View root : clockRoots()) {
                    try {
                        View c = ((android.view.ViewGroup) root).getChildAt(0);
                        if (c == null) continue;
                        Xp.callMethod(c, method, arg);
                        c.invalidate();
                        Xp.log(TAG + viewIdOf(root) + " " + c.getClass().getSimpleName()
                                + "." + method + "(" + arg + ") ok");
                    } catch (Throwable e) {
                        Xp.log(TAG + "callclock " + method + ": " + e);
                    }
                }
            }
        });
    }

    /** Calls one TimeView setter on every clock view in both subtrees, for fast iteration. */
    private static void callOnTimeViews(final String method, final String kind, final float f,
                                        final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { Xp.log(TAG + "call: need m="); return; }
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
                            Xp.callMethod(t, method, arg);
                            t.invalidate();
                            hits++;
                        } catch (Throwable e) {
                            Xp.log(TAG + "call " + method + " on " + id + ": " + e);
                        }
                    }
                }
                Xp.log(TAG + "call " + method + "(" + arg + ") applied to " + hits + " views");
            }
        });
    }

    /** The MiGlass shader samples the wallpaper and does not follow a child View's scale. */
    private static void setGlass(final boolean on) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
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
                            Xp.callMethod(t, "setMiGlassEffectEnable", on);
                            t.invalidate();
                        } catch (Throwable e) {
                            Xp.log(TAG + "setGlass " + id + " failed: " + e);
                        }
                    }
                }
                Xp.log(TAG + "glass=" + on);
            }
        });
    }

    private static void groupScale(final String idName, final float k, final float px,
                                   final float py, final float ty) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
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
                        Xp.log(TAG + idName + " in " + viewIdOf(root) + " scale=" + k
                                + " pivot=" + g.getPivotX() + "," + g.getPivotY() + " ty=" + ty
                                + " size=" + g.getWidth() + "x" + g.getHeight());
                    }
                    if (n == 0) Xp.log(TAG + idName + " not found in either clock root");
                } catch (Throwable e) {
                    Xp.log(TAG + "groupScale failed: " + Log.getStackTraceString(e));
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
        // translationX was missing here and cost an afternoon: album_art reported an x that
        // overlapped header_title, which is impossible from layout alone. getLocationInWindow
        // maps (0,0) through the view's OWN matrix before adding mLeft, so a translation or a
        // mirror moves the reported origin without moving the view's slot.
        // The layout slot, which is what the user actually touches. It differs from the
        // reported origin above whenever the view carries a transform of its own.
        sb.append(" L=").append(v.getLeft()).append(',').append(v.getTop());
        if (v.getRotationX() != 0f) sb.append(" rotX=").append(v.getRotationX());
        if (v.getRotationY() != 0f) sb.append(" rotY=").append(v.getRotationY());
        if (v.getRotation() != 0f) sb.append(" rot=").append(v.getRotation());
        if (v.getTranslationX() != 0f) sb.append(" tx=").append(v.getTranslationX());
        if (v.getTranslationY() != 0f) sb.append(" ty=").append(v.getTranslationY());
        if (v.getPivotX() != v.getWidth() / 2f) sb.append(" pivotX=").append(v.getPivotX());
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            sb.append(" textSize=").append(t.getTextSize())
              .append(" fontVar=").append(t.getFontVariationSettings())
              .append(" text=\"").append(t.getText()).append('"');
        }
        Xp.log(TAG + sb);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1);
        }
    }

    private static void dumpInfo() {
        View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container");
            return;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Xp.log(TAG + "container onScreen=[" + loc[0] + "," + loc[1] + "] size="
                + v.getWidth() + "x" + v.getHeight());
        try {
            Xp.log(TAG + "getClockBottom()=" + Xp.callMethod(v, "getClockBottom"));
        } catch (Throwable t) {
            Xp.log(TAG + "getClockBottom failed: " + t);
        }
        try {
            Xp.log(TAG + "getNotificationClockTop()="
                    + Xp.callMethod(v, "getNotificationClockTop"));
        } catch (Throwable t) {
            Xp.log(TAG + "getNotificationClockTop failed: " + t);
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
            Xp.log(TAG + "no clock container");
            return;
        }
        try {
            Object helper = Xp.getObjectField(v, "mAnimationHelper");
            Object anim = Xp.getObjectField(helper, "mClockAnima");
            Xp.log(TAG + "clock animation impl = " + anim.getClass().getName());
            for (String f : new String[]{"stateChangedAnimConfig", "notifsChangedAnimConfig",
                    "defaultAnimConfig", "animRunningConfig"}) {
                try {
                    Object cfg = Xp.getObjectField(anim, f);
                    Xp.log(TAG + f + " = " + describe(cfg));
                } catch (Throwable t) {
                    Xp.log(TAG + f + " unavailable: " + t);
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "dumpAnimConfigs failed: " + Log.getStackTraceString(t));
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
                Xp.log(TAG + idName + ": id not found");
                return;
            }
            View found = anchor.getRootView().findViewById(id);
            if (found == null) {
                Xp.log(TAG + idName + ": view not present");
                return;
            }
            int[] loc = new int[2];
            found.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + found.getWidth(), loc[1] + found.getHeight());
            Xp.log(TAG + idName + ": " + r + " vis=" + found.getVisibility());
        } catch (Throwable t) {
            Xp.log(TAG + idName + " lookup failed: " + t);
        }
    }
}
