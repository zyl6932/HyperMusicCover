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
import android.view.animation.Interpolator;
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


    static final String TAG = "[MCProbe] ";
    private static final String ACTION = "com.os4.musiccover.PROBE";

    private static final String CLS_CONTAINER =
            "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String CLS_INTERACTOR =
            "com.android.keyguard.interactor.KeyguardClockNotifInteractor";
    private static final String CLS_TIME_VIEW = "com.miui.clock.allInOne.TimeView";
    /** The OEM's own per-style index of clock parts. See dumpClockViewTypes(). */
    private static final String CLS_CLOCK_VIEW_TYPE = "com.miui.clock.module.ClockViewType";
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
     * The view the frames are painted INTO, and the one that actually carries the print.
     *
     * Measured, not assumed: on this phone the icon view above is 206x206 with its alpha at zero
     * and the print is still on screen, while this one sits visible at full alpha in the
     * `gxzw_anim` window. The frame animation is how it is normally fed - which is why
     * substituting the frames hides the print once the animation has run - but the first
     * keyguard of a SystemUI start paints it without the animation ever being asked, and that
     * paint is the one nothing was intercepting.
     */
    private static final String CLS_FOD_ANIM_VIEW =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimationView";
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

    /**
     * The charging animation HyperOS plays over the whole screen when the phone is plugged in.
     *
     * Matched as a package rather than by one class: the animation is one of several views in
     * there (MiuiChargeAnimationView on this phone, TinyMiuiChargeAnimationView on a folding
     * one's cover screen), and whichever of them is on the keyguard means the same thing to a
     * tap. See chargeAnimUp.
     */
    private static final String CLS_CHARGE_PKG = "com.miui.charge.";

    /** SystemUI's own keyguard wallpaper manager, for the wallpaper type. See wallpaperKind(). */
    static volatile Object sKgWallpaperMgr;
    /**
     * Whether the lock wallpaper is a live one, which decides where the cover is drawn: the
     * wallpaper process's GL texture for a still, a view in our own keyguard layer for a live
     * one. See showVideoCover(). Re-read before every push, because the user can change the
     * wallpaper between two songs.
     */
    static volatile boolean sVideoWallpaper;
    /** The live wallpaper's cut-out subject, hidden alongside deducted_image_view. */
    static volatile View sVideoFg;
    /** The live wallpaper itself, in the background layer, under our cover. */
    static volatile View sVideoBg;
    /** The bitmap currently on sCover, ours to recycle when it is replaced. */
    static volatile Bitmap sCoverBitmap;
    /** The frosted copy on sCover during lyric display. */
    static volatile Bitmap sCoverBlurBitmap;
    static volatile boolean sVideoCoverBlurred;

    static volatile View sContainer;
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
    static volatile Float sHoldY;
    /** Last Y the system asked for, so we can hand control back on release. */
    static volatile float sLastSystemY = Float.NaN;
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
     * The colour of the strip of the cover that the collapsed clock and its date are drawn on, as
     * ARGB. 0 = nothing measured, and every colour the OEM sets is left exactly as it is.
     *
     * Read off the COMPOSED cover - mirrored, blurred, bias-placed - rather than off the album
     * art, because that is the picture the glyphs are actually over: at the default bias the
     * sharp band starts below the clock, so what is behind it is the blur.
     *
     * Only HUE and SATURATION are taken from it; the lightness stays the OEM's. That split is the
     * whole design. Hue and saturation are what make a clock look like it belongs on the picture;
     * lightness is the half that decides whether it can be read at all, and the system has
     * already worked that out from a palette. Overriding the lightness as well is what used to
     * make the glyphs flip dark over a light cover.
     */
    static volatile int sCoverTint;
    /** From a debug op, so a colour can be tried without hunting for the artwork that gives it. */
    private static volatile int sCoverTintOverride;
    /**
     * The grey the always-on clock is drawn in, held for the rest of the doze. NaN outside one.
     *
     * The colour that reaches the AOD's glyphs lives in `glassData[11..13]` and is pushed by the
     * OEM's palette pass - and that palette is computed from the wallpaper, which in cover mode is
     * our album art. So the doze clock turns gold a second or two after it comes up; measured on
     * the phone, and poking [11..13] on a dozing keyguard turns the whole clock red, which is how
     * the location was proved. The AOD is not drawn on the cover. The first triple the doze inks
     * with is taken down to its own lightness and then held, so the clock keeps the neutral the
     * OEM starts from and the palette arriving late cannot repaint it. See the setMiGlass guard.
     */
    private static volatile float sAodGrey = Float.NaN;
    /**
     * The strip of the cover that gets sampled for that reading, in dp from the top of the
     * screen. Generous on purpose: the date rests near the top and the collapsed clock
     * hangs under it, and how tall that whole block is depends on the clock style - 56dp clears
     * the status bar and 172dp is past the glyphs on every style measured so far, the point
     * being that a band that overshoots by a few rows still describes what the eye sees there.
     */
    private static final float BAND_TOP_DP = 56f, BAND_BOT_DP = 172f;

    /**
     * The same strip, measured off the placement instead of written down, in screen pixels.
     *
     * The two constants above describe the all_in_one layout - the date near the top with the
     * collapsed clock hung under it - and every other style lays its date and its time out
     * somewhere else entirely. Measured on depth_pets: the date and clock sit at 560-640px while
     * the constants sample 168-516, so the reading came from the sky ABOVE the clock. The cover
     * was bright there and dark behind the glyphs, the module called it a dark cover, picked its
     * light glyphs, and drew a white clock on a white cover - with `cover luma 0.43` in the log
     * as the only trace, and every number in it self-consistent.
     *
     * Written by placeCollapsedClock() from where the date and the clock actually ended up, and
     * used whenever it has been written. The constants stay as the answer for the frames before
     * the first placement, which is also the only case they were ever right for.
     */
    private static volatile float sBandTopPx = Float.NaN, sBandBotPx = Float.NaN;

    /** Air above the date and below the clock that the band includes. */
    private static final float BAND_PAD_DP = 8f;

    /**
     * Where the date and the clock are, right now, on the screen.
     *
     * Called from the pre-draw and nowhere else. The placement is the wrong place for it and
     * was the first place it was written: measured on depth_pets, the placement reported the
     * block at 229-493 while the pre-draw of the very same frame has it at 514-750 - 285px
     * apart, which is the container translation the OEM applies after the placement returns.
     * The band came out over the sky above the clock, the cover read bright there and dark
     * behind the glyphs, and the clock was drawn white on white. Same rule as everything else
     * in this file: read after the transforms, never before.
     */
    static void updateColorBand() {
        if (!ClockCollapse.active()) return;
        int[] p = LOC_BAND;
        float top = Float.NaN, bot = Float.NaN;
        View date = sDateView;
        if (usableDate(date)) {
            date.getLocationOnScreen(p);
            top = p[1];
            bot = p[1] + date.getHeight();
        }
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            g.getLocationOnScreen(p);
            float gTop = p[1];
            float gBot = gTop + g.getHeight() * g.getScaleY();
            top = Float.isNaN(top) ? gTop : Math.min(top, gTop);
            bot = Float.isNaN(bot) ? gBot : Math.max(bot, gBot);
        }
        if (!Float.isNaN(top) && !Float.isNaN(bot) && bot - top >= 8f) {
            sBandTopPx = top;
            sBandBotPx = bot;
        }
    }
    /**
     * Liquid-glass -> filled morph across the collapse. AllInOneBase.updateGlassValue(float)
     * writes glassData[36] and fades the glyph interior from fully transparent (the liquid
     * glass look, which degenerates into hairlines once the clock is small) to solid. NaN = off.
     */
    private static volatile float sGlassV0 = Float.NaN, sGlassV1 = Float.NaN;
    private static volatile float sAppliedGlassV = Float.NaN;
    /**
     * The cover's entry morph has reached the solid end. While it stands, the glass value is
     * held there against spurious collapse-progress frames - see applyGlassMorph. Cleared
     * wherever the glass state itself is.
     */
    private static volatile boolean sGlassSettled;
    /** Armed once the HyperLight (统一柔光玻璃) counter-hook is installed; see armMiGlassGuard(). */
    private static volatile boolean sMiGlassGuardArmed;
    /** How many times the counter-hook actually fired on a clock glyph, for the one-line proof. */
    private static volatile int sMiGlassGuardHits;

    /** ClockViewType constants by name, resolved once. See oemPart(). */
    private static volatile java.util.Map<String, Object> sViewTypes;

    /** The full-bleed album cover we add behind the clock. */
    static volatile ImageView sCover;
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
     * The OEM's cut-out ImageView (deducted_image_view), and the visibility the OEM itself last
     * gave it. On a depth VIDEO wallpaper it holds the last frame's subject for the AOD, and the
     * OEM keeps it INVISIBLE on the awake lock screen, where the video's own cut-out TextureView
     * does the job. Handing it back as VISIBLE - what this used to do - left that last frame's
     * subject pinned over the playing video: a sheep's fleece in the corner and pine branches
     * that belonged to another frame. What goes back is what the OEM last asked for.
     */
    private static volatile View sDeductedRef;
    /** PROBE: the OEM object that owns the cut-out, for vcprobe's reading of its switches. */
    private static volatile Object sDepthInteractor;

    private static volatile int sDeductedWanted = -1;
    private static volatile boolean sDeductedHeld;
    private static volatile boolean sOwnDepthWrite;

    private static void setDeductedVisibility(View d, int v) {
        sOwnDepthWrite = true;
        try {
            d.setVisibility(v);
        } finally {
            sOwnDepthWrite = false;
        }
    }
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
    static Context sAppCtx;

    /** SystemUI's own context, for the helpers that live outside this file. */
    static Context appContext() {
        return sAppCtx;
    }
    private static final String STATE_FILE = "mc_cover_state";

    /** The album cover is the wallpaper: depth cut-out hidden and the clock collapsed. */
    static volatile boolean sCoverMode;
    /**
     * How tall the collapsed clock's digits are, in dp.
     *
     * A HEIGHT, not the coefficient this used to be. A coefficient multiplies whatever the
     * style's glyphs happen to measure, and those differ by more than ten times over between
     * styles and by more again between screens - so the same number meant a different clock
     * everywhere except on the phone it was chosen on. A height is the same clock everywhere:
     * what the setting says is what the digits measure, and the scale k falls out of it.
     *
     * 36dp is 108px on this 480dpi screen - the OPPO clock the look was matched to.
     */
    private static final float DEFAULT_CLOCK_HEIGHT_DP = 36f;
    /** The bottom of the range. Below this a digit is a smudge. */
    private static final float CLOCK_HEIGHT_MIN_DP = 20f;
    /**
     * A sanity ceiling on a stored height, and the app's slider top when it has no clock to
     * measure.
     *
     * Not the top of the travel. That is the style's own glyph height, because the collapse
     * cannot grow a clock - kForBox() caps k at 1 - so the app stops its slider there and this
     * only has to stay above any glyph on any screen. 256dp is 768px on this 480dpi device,
     * against 448px measured for the tallest clock here.
     */
    private static final float CLOCK_HEIGHT_MAX_DP = 256f;
    /** The smallest scale ever written to a view, so no style can collapse itself to nothing. */
    static final float MIN_CLOCK_K = 0.05f;
    private static final float DEFAULT_GLASS_END = 0.75f;
    static volatile float sClockHeightDp = DEFAULT_CLOCK_HEIGHT_DP;
    /**
     * A clock size stored before the unit changed, waiting for a measured box to convert with.
     *
     * The old value was a coefficient and the new one is a dp height, and 0.335dp would be a
     * clock three pixels tall - so clamping alone cannot tell the two apart. A stored value
     * under CLOCK_HEIGHT_MIN_DP is one of the old ones, and the only thing that says what it
     * was worth is the box it used to multiply, so it waits for the first one measured.
     */
    private static volatile float sClockLegacyK = Float.NaN;
    /**
     * The collapsed clock's size as a fraction of the style's own full-size clock - the one the
     * lock screen shows with cover mode off, before any notification squeezes it. 1 = unchanged.
     *
     * NaN until the slider is first moved, and then sClockHeightDp still decides: the default
     * is the 36dp clock, and what fraction that is depends on the style, so it cannot be
     * written down as one number.
     */
    static volatile float sClockSize = Float.NaN;
    /** The smallest size the slider can ask for. */
    static final float CLOCK_SIZE_MIN = 0.05f;
    /**
     * How far the date and the clock are moved together, in dp, from where cover mode puts
     * them. Positive is down.
     */
    static volatile float sClockOffsetDp = 0f;
    static final float CLOCK_OFFSET_MIN_DP = -60f;
    static final float CLOCK_OFFSET_MAX_DP = 300f;
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
    static volatile float sBias = DEFAULT_BIAS;
    static volatile CoverCardStyle sCoverCardStyle = CoverCardStyle.defaults();
    private static volatile boolean sCoverCardPlaying;

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
    static volatile String sTrackKey = "";
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
    static volatile int sScreenW = 1200, sScreenH = 2608;

    /**
     * Scratch pairs for getLocationOnScreen, one per call site.
     *
     * Every one of these sits on the collapse's per-frame path, where the array was the whole of
     * what the call allocated and there are several hundred frames in a transition for the
     * collector to walk afterwards. One array per SITE rather than one shared: they are all read
     * and consumed within a few lines of being filled, none of them nests inside another, and a
     * shared one would be a bug waiting for the first caller that does nest.
     */
    private static final int[] LOC_PLACE = new int[2];
    private static final int[] LOC_PEND = new int[2];
    private static final int[] LOC_BAND = new int[2];
    private static final int[] LOC_STALE = new int[2];
    private static final int[] LOC_CARD = new int[2];

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
    /**
     * Whether the hidden thumbnail comes back while the lock screen lyrics are up.
     *
     * Only means anything with sMcHideArt on, which is what the app's layout says: it is the
     * exception to that setting, not a setting of its own.
     */
    private static volatile boolean sMcArtInLyrics;
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
     * Keep cover mode's small clock in the always-on display instead of handing it back to the
     * OEM's, which is what it does by default.
     *
     * About the FULL-SCREEN AOD only - the one that shows the whole lock screen, dimmed. That is
     * the mode the user asked for, and the one where the OEM itself is trying to show the lock
     * screen's clock (see ClockCollapse.sAodHeld). The plain linkage AOD and the classic plugin
     * AOD are left exactly as they were.
     *
     * One setting for both views of the lock screen: the lyrics are a layer over cover mode
     * rather than a mode of their own, so "cover" and "lyrics" have no separate AOD to disagree
     * about.
     */
    static volatile boolean sAodSmall;
    /**
     * Whether the wallpaper process is sizing the keyguard texture to the SCREEN rather than to
     * the wallpaper file, which is its default and is the same switch as WallpaperProbe.sTexFit
     * on the other side. Kept here for one reason: while that is on, the re-fit below - which
     * rewrites the user's lockscreen wallpaper to the screen's size - must not run. Rewriting it
     * is what costs the user their depth cut-out, and it is no longer needed to make the
     * crossfade affordable, because a screen-sized texture is what made it affordable.
     */
    static volatile boolean sTexFit = true;
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
     * The lyrics exception as a number the card can move on, rather than a yes/no read off the
     * frame it happens to land on. 1 is "the thumbnail the setting hides is back because the
     * lyrics are up", 0 is "the card as the OEM draws it".
     *
     * Two reported bugs, one cause (2026-09-17). Read every frame with no progress of its own,
     * the answer flipped mid-transition, and both flips are visible: leaving cover mode with the
     * lyrics up put the thumbnail back for the first frames of the exit and took it away again
     * for the rest of it - "从歌词界面到正常封面切换的时候缩略图会闪一下" - and the title it
     * carries jumped between left and centre instead of sliding, which is the same number.
     *
     * Not a second animator. It is the same spring the cover itself moves on - EASE_COVER at the
     * response the user set - stepped from the card's own per-frame pass (see stepLyricArt), so
     * the thumbnail and the title cross at the speed of the transition they are part of and there
     * is still exactly one curve on this lock screen.
     */
    private static volatile float sLyricArtP;
    private static float sLyricArtV;
    private static float sLyricArtTo = -1f;
    private static long sLyricArtAt;
    private static boolean sCardFramePosted;
    /**
     * Whether the lyrics are up, remembered so the exit of cover mode does not re-ask it. The
     * lyric view fades out with the card's own progress, so during the exit the lyrics are still
     * on screen while they fade, and asking then is what made the thumbnail blink.
     */
    private static boolean sLyricUp;
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
    static volatile boolean sFadeWp = true;
    /**
     * Whether the COVER VIDEO ITSELF crossfades into the cover, in the wallpaper window.
     *
     * Off, and it is off because of what it cost when it was on: measured on the device, the
     * cards' blurred background stopped switching to the cover at all - the window never showed
     * the new frame. A cover video with a fade in it is a file of a dozen frames, a frame of the
     * user's own wallpaper has to be decoded to build it, and both ends of it are converted to
     * YUV; any of those going wrong leaves the reload at the end of that chain never happening,
     * while the SystemUI view - which is put up by the other process entirely - goes on showing
     * the cover. A one-frame cover, which is what this was before the fade existed, cannot fail
     * that way.
     *
     * The lag it was meant to hide is handled where it actually lives: see sFadeMode. Kept as a
     * switch rather than deleted because the muxer now handles any frame count (checked off the
     * device: two samples in, byte-identical file out) and this is one adb line to try again.
     */
    static volatile boolean sVideoFade;
    /**
     * What the cover's own fade does about the wallpaper window's head start, which is the one
     * thing about a live cover that cannot be fixed by making either half faster.
     *
     * The two halves are drawn by different processes and they are not equally fast. This view
     * gets the composed bitmap immediately; the window under it only changes once the cover
     * video has been ENCODED, the player rebuilt and its first frame rendered. So a fade that
     * starts at the push is over before the window has moved at all - which is the whole of the
     * report that the background and the cards' blurred background arrive at visibly different
     * moments.
     *
     *   FADE_MODE_OFF     - the fade the module always had: our view dissolves in from the
     *                       push, over the wallpaper's own crossfade length, whatever the window
     *                       is doing. The eye's background and the cards' blurred background then
     *                       change at visibly different moments, which is the original report.
     *   FADE_MODE_HOLD    - waits for WallpaperProbe's signal - sent when the new source's
     *                       FIRST FRAME is on the window - and only then puts the view up.
     *                       No dissolve: the view appears over identical content, so the eye's
     *                       background and the cards' blurred background switch in the same
     *                       frames. The default; the entry dissolve is gone by request - any
     *                       fade that runs before the first frame shows the blur stale.
     *   FADE_MODE_STRETCH - dissolves from the view's first frame, over the last MEASURED gap
     *                       between the push and the first-frame signal. Kept for A/B.
     *
     * On the way OUT the same three apply, but holding is what makes sense there whatever the
     * mode: the view has to still be up when the window swaps back, or its cut shows.
     *
     * The measured gap is persisted, so a restarted SystemUI does not have to relearn it - and
     * with no measurement at all the stretch falls back to the crossfade length, which is mode
     * OFF for that one transition.
     */
    static final int FADE_MODE_OFF = 0, FADE_MODE_HOLD = 1, FADE_MODE_STRETCH = 2;
    static volatile int sFadeMode = FADE_MODE_HOLD;
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
     * How long a tap on the cover is held back before it means anything.
     *
     * GestureDetector reports a single tap the moment the finger lifts, and a fast double tap
     * is two gestures: the first one had already toggled the cover by the time the second
     * arrived, and MIUI's own double-tap-to-sleep is decided downstream of this hook
     * (KeyguardPanelViewInjector, over com.android.keyguard's DoubleTapHelper), so it cannot be
     * asked first. So the tap is armed instead and fired only once the double tap window has
     * passed. Reported as "double tap the lock screen and the wallpaper state has changed when
     * it comes back on".
     *
     * The framework's own timeout plus a margin. GestureDetector measures the first DOWN to the
     * second, MIUI measures the gap between the two taps, so the window it might still call a
     * double tap can end later than ours - the margin is that difference. Not a guess to be
     * trimmed without measuring first.
     */
    private static final long TAP_CONFIRM_MS =
            android.view.ViewConfiguration.getDoubleTapTimeout() + 60L;
    /** Armed by a tap on the cover, cancelled while it is still a candidate double tap. */
    private static Runnable sPendingTap;

    /**
     * Whether the gesture in flight began on the control centre.
     *
     * Recorded at ACTION_DOWN because every later test is too late: the centre is dismissed
     * during the gesture it is being dismissed by, so anything asked after that reads "no
     * centre" and hands the touch to the cover.
     */
    private static volatile boolean sGestureOnCentre;

    /**
     * Whether the gesture in flight began on the charging animation.
     *
     * Recorded at ACTION_DOWN for the same reason the centre is: the animation is dismissed by
     * the very gesture that dismisses it, so by the time a held-back tap fires there is nothing
     * left on screen to ask, and the tap would be read as one on the cover. See chargeAnimUp.
     */
    private static volatile boolean sGestureOnCharge;

    /**
     * The OEM's own miuix curves, read off AllInOneClockAnimation at runtime as
     * {dampingRatio, response}. miuix derives stiffness = (2*PI/response)^2 and
     * damping = 2*zeta*(2*PI/response) - confirmed against the dumped parameters[].
     */
    private static final float[] EASE_STATE_CHANGED = {0.88f, 0.38f}; // k=273.4  c=29.1

    /**
     * The curve the cover-mode transition runs on, and it is the OEM's own - the same numbers as
     * EASE_STATE_CHANGED above, which is the preset the system itself drives this transition
     * with. Named separately so the two can diverge if they ever have reason to, not because
     * they differ today.
     *
     * It replaced EASE_RUNNING (0.18) in both directions, and that is the whole of the change:
     * the same spring family, the same driver, same velocity carry-over across a retarget, one
     * pair of numbers. 0.18 covers its distance about twice as fast at every point - 50% of the
     * travel at 48ms against 95ms here, 95% at 136ms against 235 - which on a 120Hz screen puts
     * the peak of the motion at ~73px in a single frame, at t~29ms. That is also the moment the
     * OEM is recomputing the clock's variable font at its largest, so any dropped frame there
     * doubled a step that was already the fastest thing on the screen. Measured with the jerk
     * probe: 30/49/75px on the old curve, 25-41px on this one.
     *
     * A recording of the transition was also measured frame by frame (clock band, pixel change
     * summed into a speed profile, integrated into progress), and it agrees on the shape but not
     * on the number: a least-squares fit puts the response at 0.45-0.55s, consistently slower
     * than the preset. The preset is believed over the fit, because the two are different kinds
     * of evidence - a 0.38 that was read out of the OEM's own animation object, against a number
     * inferred from pixels that cannot separate the clock's growth from the cover shrinking and
     * the wallpaper swapping in the same frames, with a noise floor that clips exactly the slow
     * first frames a spring is slowest in. Both errors push the estimate long. The preset also
     * reproduces an independent measurement already in this file: the exit spring "settles in
     * 610-636ms" was read off the device, and 0.38 lands at 665ms.
     */
    static final float[] EASE_COVER         = {0.88f, 0.38f};

    /**
     * The slider's range for the response above, in seconds. Zeta is not on it: the whole of the
     * change this replaced was the one number, and the curve's shape is the OEM's.
     *
     * 0.18 is the bottom because it is the fastest this transition has ever shipped - it is what
     * EASE_RUNNING was - and the jerk probe measured 30/49/75px of clock movement in a single
     * frame on it, against 25-41 here. Faster than that is asking for dropped frames on the one
     * frame the OEM is also recomputing the variable font.
     */
    private static final float CLOCK_RESPONSE_MIN = 0.18f, CLOCK_RESPONSE_MAX = 0.60f;
    /** The wallpaper crossfade that belongs to EASE_COVER[1]. See fadeMsFor(). */
    private static final long EASE_COVER_FADE_MS = 370L;
    /** What the transition runs on. Starts at the OEM preset and moves with the slider. */
    static volatile float sClockResponse = EASE_COVER[1];

    /**
     * The fade that belongs to a spring response: the card's stand-in animator and the
     * wallpaper's crossfade, which have to reach the end with the clock.
     *
     * Proportional, and that is the rule rather than a convenience. The wallpaper's fade is a
     * cubic ease-out, which covers 95% of its length at 0.632*T, and the 370ms that ships with
     * 0.38 was solved from that against the spring's own 95% at 235ms - see the comment on
     * WallpaperProbe.sFadeMs. With zeta fixed, a linear spring's step response is a function of
     * w0*t alone, so its 95% time is exactly proportional to the response, and so is the T that
     * matches it. 0.18 gives 175ms, 0.60 gives 584.
     */
    static long fadeMsFor(float response) {
        return Math.round(EASE_COVER_FADE_MS * response / EASE_COVER[1]);
    }

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
        // The lock screen editor and the always-on display, which judge a wallpaper's depth with
        // their own copies of the same classes and hold the ceiling on saved lock screens.
        if ("com.miui.aod".equals(pkg)) {
            HyperTweaks.aod(param.getDefaultClassLoader());
            return;
        }
        // The control centre plugin. Usually loaded into SystemUI's own loader, in which case
        // this never fires and the attempt below is the one that lands.
        if ("miui.systemui.plugin".equals(pkg)) {
            HyperTweaks.plugin(param.getDefaultClassLoader());
            return;
        }
        // The one player we hook. Its lyric never reaches the session on its own, so it is
        // fetched and published from inside the app - see AppleLyrics. Nothing else about the
        // module runs in this process.
        if (AppleLyrics.PKG.equals(pkg)) {
            AppleLyrics.handle(param.getDefaultClassLoader());
            return;
        }
        if (!"com.android.systemui".equals(pkg)) return;

        final ClassLoader cl = param.getDefaultClassLoader();
        Xp.log(TAG + "loaded into SystemUI");

        // Before the clock container lookup below, which returns early when it fails: none of
        // these have anything to do with the clock, and a build that renamed the container must
        // not cost them too.
        HyperTweaks.systemUi(cl);
        // The mini player hangs off the shortcut row, not the clock container.
        MiniPlayerRuntime.install(cl);

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
                CoverMorphLayer.cancel();
                sContainer = (View) chain.getThisObject();
                captureScreenSize(sContainer);
                // The clock container attach is the first reliably-fired event after both
                // modules' onPackageLoaded, so arming the HyperLight counter-hook here - not
                // from setGlassColor, which this build never calls on the all_in_one clock -
                // guarantees it registers after HyperLight's own View.setMiGlass hook.
                armMiGlassGuard();
                Xp.log(TAG + "clock container attached: " + sContainer);
                try {
                    registerReceiver(sContainer.getContext().getApplicationContext());
                } catch (Throwable t) {
                    Xp.log(TAG + "registerReceiver failed: " + t);
                }
                // The lyric bridge, from the same place and for the same reason: this is where
                // the process first has a Context. It does nothing at all when no bridge is
                // installed, and a second call is a no-op, so it rides along with the keyguard
                // being rebuilt rather than needing a moment of its own.
                try {
                    LyriconSource.attach(sContainer.getContext().getApplicationContext());
                } catch (Throwable t) {
                    Xp.log(TAG + "Lyricon attach failed: " + t);
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
                // ... and the shade's background has to be rebuilt for the same reason even on
                // the image path, where nothing else would notice it had gone: it is static, so
                // a restart takes it and only this puts it back.
                if (sCoverMode && (sVideoWallpaper || !ShadeLayer.hasArt())) {
                    if (sVideoWallpaper) sCover = null;
                    CoverPush.pushArtAsync(true, false);
                }
                if (sCoverMode && sCoverCardStyle.mode == CoverCardStyle.CARD) {
                    CoverCardLayer.attach(CoverPush.coverLayer());
                    CoverCardLayer.style(sCoverCardStyle);
                    CoverCardLayer.playback(sCoverCardPlaying);
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
                sClockTargets.clear();
                forgetClockRoots();
                forgetGlyphBox();
                ClockCollapse.onAttached();
                // A rebuilt keyguard has a new foreground layer, and a restored cover mode came
                // on before there was any keyguard to put the lyrics in. Posted: adding a view
                // from inside the tree's own attach dispatch is not something to rely on.
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            LockLyrics.refresh();
                        } catch (Throwable t) {
                            Xp.log(TAG + "lyrics refresh failed: " + t);
                        }
                    }
                });
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
                    CoverMorphLayer.cancel();
                    ClockCollapse.onDetached();
                    sContainer = null;
                    Xp.log(TAG + "clock container detached");
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "onDetachedFromWindow hook failed: " + t);
        }

        // The lock screen lyrics' HDR highlight needs the shade window in HDR colour mode, and that
        // window's attributes are rebuilt from mLpChanged on every apply - so the mode is written
        // into mLpChanged right before each one, not set once and overwritten on the next.
        try {
            Class<?> nsw = Xp.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowControllerImpl", cl);
            Xp.hookAll(nsw, "applyWindowLayoutParams", chain -> {
                Object self = chain.getThisObject();
                try {
                    LockLyrics.noteShadeWindow(self);
                    LockLyrics.applyHdrTo((android.view.WindowManager.LayoutParams)
                            Xp.getObjectField(self, "mLpChanged"));
                } catch (Throwable t) {
                    Xp.log(TAG + "lyrics HDR not applied: " + t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Xp.log(TAG + "shade window hook failed, lyrics stay SDR: " + t);
        }

        // The keyguard service hears about sleep and wake before anything is laid out for either.
        //
        // Sleep: the start of the fade that leaves only the clock. The small clock is sprung to
        // the OEM's own (and then the AOD's) clock across it - see ClockCollapse.toAod().
        //
        // Wake: flagged on the binder thread itself, because what matters is the pre-draw of the
        // very first lock screen frame. By the time a post reaches the main thread the OEM may
        // already have drawn one frame of its full-size clock - which is the flash this prevents.
        try {
            Class<?> ks = Xp.findClass("com.android.systemui.keyguard.KeyguardService$2", cl);
            Xp.hookAll(ks, "onStartedGoingToSleep", chain -> {
                Object result = chain.proceed();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        CoverMorphLayer.cancel();
                        if (sCoverMode) ClockCollapse.toAod();
                    }
                });
                return result;
            });
            Xp.hookAll(ks, "onStartedWakingUp", chain -> {
                ClockCollapse.noteWaking();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        MotionTrace.start("wake");
                    }
                });
                Object result = chain.proceed();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        if (sCoverMode && ClockCollapse.phase() == ClockCollapse.Phase.AOD
                                && keyguardShowing()) {
                            ClockCollapse.enter(true, true, "kg-post");
                        }
                    }
                });
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "KeyguardService sleep/wake hooks failed, the clock cuts to and from "
                    + "the AOD: " + t);
        }

        // The press itself, ~0.8s before the player admits anything changed.
        //
        // Measured on this phone: from the key landing to the module being told a new track is
        // playing is 713-972ms, all of it inside the player. Everything the cover does after that
        // costs ~110ms. So the only way to make a track change feel immediate is to hear the
        // press rather than the consequence - and this is where the press is, in this process,
        // on the way out to the player.
        //
        // Hooked here rather than on the card's buttons on purpose. `action0..action4` are laid
        // out in whatever order the player's custom actions arrive in (Apple Music puts prev at
        // action1 and next at action3, with 喜爱 and 随机播放 either side), the ids are AOSP's
        // generic ones, and the content descriptions are localised. TransportControls is public
        // API, cannot be renamed, and says what was MEANT - and it catches every route to it, not
        // just the lock screen's own buttons.
        try {
            Class<?> tc = Xp.findClass("android.media.session.MediaController$TransportControls",
                    cl);
            Xp.hookAll(tc, "skipToNext", chain -> {
                CoverPush.noteSkip(1);
                return chain.proceed();
            });
            Xp.hookAll(tc, "skipToPrevious", chain -> {
                CoverPush.noteSkip(-1);
                return chain.proceed();
            });
            Xp.log(TAG + "transport controls hooked");
        } catch (Throwable t) {
            // Independently, like every other hook here: without it a track change simply waits
            // for the player, which is what it did before.
            Xp.log(TAG + "transport control hook failed, a skip is only noticed when the player "
                    + "reports it: " + t);
        }

        // The OEM's own hand-over between the lock screen and the AOD, both ways.
        //
        // Into the AOD it lands after the screen fade has gone to black and before the AOD is
        // visible (measured 33.43 fade end -> 33.62 here -> 33.66 AOD shown -> 33.74
        // ACTION_SCREEN_OFF), so the AOD gets the full clock with nothing seen. It has to run
        // BEFORE the call: the call reads the clock's translation and posts it to the AOD plugin.
        //
        // Out of it, it is the first thing of the wake (onStartedWakingUp +46ms) and comes before
        // the OEM starts growing its glyphs from the AOD size - so the collapse starts from the
        // clock the AOD was showing and absorbs the OEM's growth, instead of racing it.
        try {
            Xp.hookAll(sContainerCls, "doAnimationToAod", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean toAod = args.length > 0 && Boolean.TRUE.equals(args[0]);
                if (sCoverMode && toAod) {
                    // Ahead of the broadcast, which is ~110ms behind: the display is no longer
                    // interactive, and any colour set in between must not get the cover's tint.
                    sScreenOn = false;
                    CoverCardLayer.refresh();
                    sAodGrey = Float.NaN;
                    ClockCollapse.toAod();
                    recolorClock();
                }
                Object result = chain.proceed();
                if (sCoverMode && !toAod && ClockCollapse.leavingOrOff()) {
                    forgetSysReads();
                    // Only a wake onto the lock screen. The same call can come with the phone
                    // unlocked, and the clock there is the shade's.
                    if (keyguardShowing()) {
                        sScreenOn = true;
                        CoverCardLayer.refresh();
                        sAodGrey = Float.NaN;
                        ClockCollapse.enter(true, true, "doAnim");
                        recolorClock();
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "doAnimationToAod hook failed, AOD falls back to screen off: " + t);
        }

        // The system does re-show the cut-out - verified: it came back right after we restored
        // cover mode across a SystemUI restart. Re-hide after the OEM's own update instead of
        // hooking View.setVisibility process-wide, which would tax every view in SystemUI.
        try {
            Class<?> depth = Xp.findClass(
                    "com.android.keyguard.depth.KeyguardDepthInteractor", cl);
            Xp.hookAll(depth, "updateDeductedImageView", chain -> {
                sDepthInteractor = chain.getThisObject();
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

        // What the OEM itself wants of its cut-out view while we hold it hidden. See sDeductedRef.
        // ImageView declares its own setVisibility, so this sees ImageViews only, and it acts on
        // exactly one of them.
        try {
            Xp.hookAll(android.widget.ImageView.class, "setVisibility", chain -> {
                if (!sOwnDepthWrite && chain.getThisObject() == sDeductedRef) {
                    Object a = chain.getArgs().get(0);
                    if (a instanceof Integer) sDeductedWanted = (Integer) a;
                }
                return chain.proceed();
            });
            Xp.log(TAG + "cut-out visibility watched");
        } catch (Throwable t) {
            Xp.log(TAG + "cut-out visibility hook failed: " + t);
        }

        // The full-screen AOD's shrink, and the one view that must not take it.
        //
        // On the way into full-screen AOD KeyguardPanelViewController.doDeductedImageScaleAnim
        // scales a list of views to (wallpaperScale - 0.05) around (0.5w, 0.4h) - 0.95 at rest.
        // On a depth video wallpaper the list is fullAodAnimationViewsForVideoDepth, which the
        // cover's layer is not in; everywhere else it is animationViews, which holds the
        // keyguard's root layout and with it keyguard_background_layer, where the video cover
        // view sits. A still wallpaper's window shrinks along with it (the same method sets the
        // wallpaper's matrix) and the cover IS that window's texture there, so nothing shows. A
        // video wallpaper's window does not move - isWallpaperScaleEnable() is false for video by
        // the OEM's own rule - and the cover view pulled in from all four edges over it.
        //
        // The clock, the date and the cards are meant to shrink - the AOD poses are written in
        // that scaled space (keyguard-shared-zoom) - so the call is left alone and only the cover
        // view is counter-scaled, in its pre-draw guard, around the same point. This records the
        // scale that guard is to undo; any other scale on the way (the unlock, the shade) is left
        // to act on the cover as it always has.
        try {
            Class<?> kpvc = Xp.findClass("com.android.keyguard.panel.KeyguardPanelViewController", cl);
            Xp.hookAll(kpvc, "doDeductedImageScaleAnim", chain -> {
                Object[] a = chain.getArgs().toArray();
                if (a.length > 0 && a[0] instanceof Float) {
                    CoverPush.sAodShrink = (Float) a[0] - 0.05f;
                }
                return chain.proceed();
            });
            Xp.log(TAG + "AOD shrink hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "AOD shrink hook failed (the video cover may pull in from the edges"
                    + " into full-screen AOD): " + t);
        }

        // The AOD's wallpaper dim. doWallpaperBlackAnim animates "wallpaperBlack" and lands it
        // through ViewRootImpl.setWallpaperBlack(float) - a MIUI channel that reaches the
        // wallpaper window itself. The value, and whether the window stays dimmed, survive a
        // cover's rebuild normally - but our reload resets the wallpaper-side render state, and
        // with the cover up the dim was observed to vanish into AOD. Remember the value and the
        // root it was set on; the first-frame signal re-asserts both. See reassertAodDim().
        try {
            Class<?> vri = Xp.findClass("android.view.ViewRootImpl", cl);
            Xp.hookAll(vri, "setWallpaperBlack", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 1 && args[0] instanceof Float) {
                    float v = (Float) args[0];
                    if (v != sLastWallpaperBlack) {
                        sLastWallpaperBlack = v;
                        sBlackRoot = chain.getThisObject();
                        if (sVerbose) {
                            Xp.log(TAG + "wallpaperBlack=" + v + " on "
                                    + (sBlackRoot == null ? "null" : sBlackRoot.getClass()
                                    .getSimpleName()));
                        }
                    }
                }
                return chain.proceed();
            });
            Xp.log(TAG + "wallpaperBlack hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "wallpaperBlack hook failed (the cover may sit bright in AOD): " + t);
        }

        // The fingerprint ring, when the user has asked for it to go. Two hooks, because the
        // ring is two things: MiuiGxzwFrameAnimation plays the pulsing circle out of a list of
        // drawables, and MiuiGxzwIconView holds the static print underneath. Hiding either one
        // alone leaves the other on screen.
        //
        // The split is HyperTweak's, read off https://github.com/TakeKazeX/HyperTweak: which of
        // these draws what, and that hiding one of the two is not enough. What this phone needed
        // on top of that was measured here rather than taken from anyone - see CLS_FOD_ANIM_VIEW.
        //
        // Both install whatever the setting says and read the flag per call, so the switch takes
        // effect on the next draw rather than on the next SystemUI restart. And both are on
        // their own terms: a build that renamed one still gets the other.
        //
        // Per call is not by itself enough, because the first call can come before the module
        // has read its settings at all - see peekHideFp, which is what both of them start with.
        try {
            Class<?> anim = Xp.findClass(CLS_FOD_ANIM, cl);
            Xp.hookAll(anim, "draw", chain -> {
                Object[] args = chain.getArgs().toArray();
                // The print's own window is painted before the keyguard's clock container
                // attaches, so the setting has to be fetched here rather than waited for.
                peekHideFp();
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
                    // Built before the keyguard attaches on the same builds the ring is, and
                    // this is the one chance to dim it before it is ever seen.
                    peekHideFp();
                    sFodIcons.put(v, Boolean.TRUE);
                    v.setAlpha(sHideFp ? 0f : 1f);
                } catch (Throwable ignored) {
                    // A view we cannot dim is a visible print, not a broken keyguard.
                }
                return result;
            });
            // And the paint itself, where the build declares one. This view paints nothing on
            // the phone this was measured on - its alpha sits at zero with the print still on
            // screen - but on a build where it is the painter, a frame it never draws cannot be
            // brought back by anything that happens between frames, which the alpha above can.
            for (String name : new String[] {"onDraw", "draw"}) {
                try {
                    Xp.hookAll(iconCls, name, chain -> {
                        peekHideFp();
                        if (sHideFp) return null;
                        return chain.proceed();
                    });
                    break;
                } catch (Throwable ignored) {
                    // Not declared here; try the other name.
                }
            }
            Xp.log(TAG + "fingerprint icon hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint icon hook failed, the static print will stay: " + t);
        }

        // The view the print is actually painted on. Its own hook because it is its own class
        // and its own failure: the frame substitution above covers it only once the animation
        // has run, and the first keyguard after a SystemUI start paints without it.
        try {
            Class<?> animView = Xp.findClass(CLS_FOD_ANIM_VIEW, cl);
            int hooked = 0;
            for (String name : new String[] {"onDraw", "draw", "dispatchDraw"}) {
                try {
                    Xp.hookAll(animView, name, chain -> {
                        peekHideFp();
                        // Painting nothing, rather than dimming: the alpha on this view is the
                        // OEM's to animate, and a frame it never paints cannot be animated back.
                        if (sHideFp) return null;
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignored) {
                    // Not declared on this build; the others still stand.
                }
            }
            Xp.log(TAG + "fingerprint print view hooked, " + hooked + " of its paint methods");
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint print view hook failed: " + t);
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
                    // Whether this gesture began on the control centre, decided at the DOWN and
                    // nowhere else.
                    //
                    // onLockTap's own controlCentreUp() guard is evaluated a double-tap window
                    // later, and by then the centre can be gone: swiping up to dismiss it takes
                    // the centre out from under the check, so it read false and the tap went
                    // through as a tap on the cover. Measured symptom - dragging up at the clock
                    // and date left cover mode instead of closing the centre.
                    //
                    // At the DOWN the answer is unambiguous: a finger that lands while the centre
                    // is open is aimed at the centre, whatever the gesture turns out to be.
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        sGestureOnCentre = controlCenterUp();
                        if (sGestureOnCentre) cancelPendingTap("gesture began on the control centre");
                        // The charging animation, for the same reason and at the same point: a
                        // tap on it is what dismisses it, and asking later would be asking after
                        // it had gone. See chargeAnimUp.
                        sGestureOnCharge = chargeAnimUp();
                        if (sGestureOnCharge) cancelPendingTap("gesture began on the charging animation");
                    }
                    // The one case this hook does more than watch. Returning true without
                    // proceeding takes the gesture out of the dispatch entirely, which is the
                    // only way the artwork can mean something other than "open the player".
                    // A gesture that starts on the mini player is the mini player's alone: its
                    // swipe up would otherwise also be swipe-to-unlock.
                    try {
                        if (MiniPlayerRuntime.routeTouch(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    // A downward swipe on the media card folds it back into the mini player. Seen
                    // before the art tap, which it overrides; the moment it is recognised the
                    // rest of the tree gets a CANCEL, so the shade does not also start opening.
                    try {
                        int swipe = cardSwipe(ev);
                        if (swipe == SWIPE_FIRED) {
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            try {
                                chain.proceed(new Object[]{cancel});
                            } finally {
                                cancel.recycle();
                            }
                            return Boolean.TRUE;
                        }
                        if (swipe == SWIPE_HELD) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
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

        // The charging animation going up and coming down, recorded rather than guessed at.
        //
        // This is not what holds the tap back - chargeAnimUp reads the live tree and would go on
        // working with this hook missing entirely. It is the evidence for it: `op chargeanim`
        // prints one line per attach and detach with the tree as it was at that instant, which
        // is what tells "the walk finds the view" from "the walk is looking at the wrong root",
        // and what puts a number on how long a tap is held off for.
        try {
            Class<?> chargeAnim = Xp.findClass(
                    CLS_CHARGE_PKG + "container.MiuiChargeAnimationView", cl);
            // After the call, not before: the point of the snapshot is what the tree looks like
            // with the animation in it, and addChargeView is what puts it there.
            Xp.hookAll(chargeAnim, "addChargeView", chain -> {
                Object result = chain.proceed();
                noteChargeAnim(true, chain.getThisObject());
                return result;
            });
            Xp.hookAll(chargeAnim, "removeChargeView", chain -> {
                Object result = chain.proceed();
                noteChargeAnim(false, chain.getThisObject());
                return result;
            });
            Xp.log(TAG + "charging animation hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "charging animation hook failed: " + t);
        }

        // The notification shade's cover background. Its hooks and logic are ShadeLayer's own.
        ShadeLayer.install(cl);

        // The live container, and - on every clock style except the all_in_one family - the
        // only place the notification Y ever arrives.
        //
        // KeyguardClockContainer forwards this straight to
        // AnimationHelper.mClockAnima.notifStateChange, and each style overrides that with its
        // own animation. Only AllInOneClockAnimation goes on to
        // KeyguardClockNotifInteractor.setNotifY. Measured with the classic style selected: zero
        // setNotifY calls across a full enter and exit of cover mode, so applyCollapse() never
        // ran and the whole clock half of the module was dead there - not because anything was
        // broken, but because nobody was driving it.
        //
        // `time_group` is the test for which of the two this is, because it is all_in_one's own
        // id and all_in_one is the only family that reaches setNotifY. Where it is present this
        // hook does nothing but what it always did: the coercion stays inside setNotifY, where
        // the OEM's spring reads it back, and moving it out here was measured to change what
        // ClockBaseAnimation's own "has this y changed" early-out compares against - which
        // parked the OEM's font animation and rendered the clock at the wrong size.
        //
        // Where it is absent there is no setNotifY to do any of that, so the y is learned and
        // held at this level instead: learned because the exit hands back to it (sLastSystemY
        // stays NaN for the life of the process otherwise), and held because the system's own
        // re-assertions arrive here on these styles and would otherwise undo the squeeze.
        try {
            Xp.hookAll(sContainerCls, "notifStateChange", chain -> {
                // Anyone calling this is by definition the live instance.
                sContainer = (View) chain.getThisObject();
                if (findClockView(sContainer, "time_group") != null) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                float requested = (Float) args[0];
                // Not our own writes, which are not what the system asked for. The system's
                // re-assertions while we hold ARE: they carry where the notifications start, and
                // the small clock gives way to them off this number.
                if (!sSelfDriving) sLastSystemY = requested;
                Float hold = sHoldY;
                if (hold != null && requested != hold) args[0] = hold;
                return chain.proceed(args);
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
            // Its bounds are computed inside its own draw; see ClockCollapse.onGlyphDrawn().
            Xp.hookAll(timeView, "onDraw", chain -> {
                Object result = chain.proceed();
                ClockCollapse.onGlyphDrawn((View) chain.getThisObject());
                return result;
            });

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
            //
            // The lightness is not touched here, and setBrightness - which carries the material's
            // own dark/light flag and used to be hooked alongside these - deliberately is not.
            Xp.hookAll(timeView, "setGlassColor", chain -> {
                View self = (View) chain.getThisObject();
                if (!glassStyleFor(self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = legible(self, (Integer) args[0]);
                return chain.proceed(args);
            });
            Xp.hookAll(timeView, "setTextColor", chain -> {
                View self = (View) chain.getThisObject();
                if (!glassStyleFor(self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = legible(self, (Integer) args[0]);
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.log(TAG + "TimeView hook failed: " + t);
        }

        // The date line above the clock is deliberately left to the OEM. On the glass styles it
        // is blur-blend rendered: setDateTextColor keeps only the green and blue of what it is
        // given and forces red to 0xff, and those channels weight the blend layers against the
        // backdrop rather than name a colour. Swapping in the cover's hue moved those weights,
        // so the date went dark on a light cover and light on a dark one - and our recolour,
        // which fed getCurrentTextColor() back in, left it that way after cover mode ended.

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
                // The coerced Y has to reach the original, which is what proceed(args) is for:
                // this is the one hook whose whole purpose is rewriting an argument.
                // Nothing is placed from here: the OEM applies parts of this frame later (Folme),
                // so any reading taken here is a frame stale. ClockCollapse places from the
                // pre-draw, after every transform of the frame has been written.
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

    /**
     * Opens the app when its code is dialled - from in here, where it actually works.
     *
     * The app has a manifest receiver for the same broadcast and on this phone it is never
     * called. Measured rather than assumed: the receiver is registered and resolvable (pm
     * query-receivers finds it for both actions), an identically-declared receiver in another
     * installed app does get called for its own code, and ours is not called for any of three
     * code lengths. Whatever the dialler is filtering on, a third-party app's manifest receiver
     * does not get there.
     *
     * Registering it here sidesteps the whole question twice over. This is SystemUI: a receiver
     * registered at runtime is not subject to the implicit-broadcast rules that manifest
     * receivers are, and starting an activity from a system process is not subject to the
     * background-activity-start rules that made the app's own attempt fail silently even when
     * it was reached.
     *
     * Kept alongside the app's receiver rather than replacing it: the app's works on phones
     * whose dialler does deliver, and on those this one simply never fires.
     */
    private static void registerSecretCode(Context ctx) {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction("android.provider.Telephony.SECRET_CODE");
            f.addAction("android.telephony.action.SECRET_CODE");
            f.addDataScheme("android_secret_code");
            f.addDataAuthority(LauncherIcon.SECRET_CODE, null);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    Xp.log(TAG + "secret code dialled: " + i.getData());
                    try {
                        Intent open = new Intent(Intent.ACTION_MAIN);
                        open.setClassName(BuildConfig.APPLICATION_ID,
                                "com.os4.musiccover.MainActivity");
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        c.startActivity(open);
                    } catch (Throwable t) {
                        Xp.log(TAG + "could not open the app: " + t);
                    }
                }
            }, f, Context.RECEIVER_EXPORTED);
            Xp.log(TAG + "listening for the dialled code " + LauncherIcon.SECRET_CODE);
        } catch (Throwable t) {
            Xp.log(TAG + "secret code receiver failed: " + t);
        }
    }

    static void saveState() {
        if (sAppCtx == null) return;
        // Never while loadState is still walking the file. Some of the setters it applies values
        // through save as part of their own contract - setClockResponse does - and a save taken
        // mid-parse writes every key the parse has NOT reached yet at its DEFAULT. `spring` sits
        // ahead of mcart, mctext, mctap, lyrics, lyrickeep and lyrichdr in the file, so restoring
        // a good file quietly rewrote it with those six off. The run itself looked fine, because
        // the loop went on to fill memory in correctly from the copy it had already read; the
        // damage only showed at the NEXT SystemUI start, which is why it read as "installing the
        // app turns some switches off". loadState saves once at the end instead.
        if (sLoading) return;
        try {
            java.io.FileOutputStream f =
                    new java.io.FileOutputStream(new java.io.File(sAppCtx.getFilesDir(), STATE_FILE));
            f.write(("cover=" + (sCoverMode ? 1 : 0)
                    + "\nbias=" + sBias
                    + "\ncoverstyle=" + sCoverCardStyle.mode
                    + "\ncovercardfill=" + sCoverCardStyle.fill
                    + "\ncovercardpos=" + sCoverCardStyle.pos
                    + "\ncovercardcorner=" + sCoverCardStyle.corner
                    // A pending pre-dp value is written as itself: it cannot be converted until
                    // a confirmed box exists, and writing the default over it would lose the
                    // setting the user actually had.
                    + "\nclock=" + (Float.isNaN(sClockLegacyK) ? sClockHeightDp : sClockLegacyK)
                    + (Float.isNaN(sClockSize) ? "" : "\nclocksize=" + sClockSize)
                    + "\nclockoff=" + sClockOffsetDp
                    // A measurement, like cardrect: the full clock the size is a fraction of.
                    + (ClockCollapse.fullUnitState() == null ? ""
                            : "\nclockfull=" + ClockCollapse.fullUnitState())
                    + "\nglass=" + sGlassEnd
                    + "\nspring=" + sClockResponse
                    + "\nmcart=" + (sMcHideArt ? 1 : 0)
                    + "\nmclyricart=" + (sMcArtInLyrics ? 1 : 0)
                    + "\nmctap=" + (sMcTitleTap ? 1 : 0)
                    + "\ntap=" + (sTapToggle ? 1 : 0)
                    + ShadeLayer.dumpCfg()
                    + "\nfadewp=" + (sFadeWp ? 1 : 0)
                    + "\nvcfade=" + (sVideoFade ? 1 : 0)
                    + "\nfsmode2=" + sFadeMode
                    // A measurement rather than a setting, and kept for the same reason the
                    // geometry is: a fresh SystemUI should not have to relearn it to use it.
                    + "\ncovergap=" + CoverPush.sCoverFadeGapMs
                    + "\nhidefp=" + (sHideFp ? 1 : 0)
                    + "\naodsmall=" + (sAodSmall ? 1 : 0)
                    + "\ncolon=" + (HyperTweaks.sForceColon ? 1 : 0)
                    + "\nseekglow=" + (HyperTweaks.sBarGlow ? 1 : 0)
                    + "\nlyrics=" + (LockLyrics.sEnabled ? 1 : 0)
                    + "\nlyrickeep=" + (LockLyrics.sKeepOn ? 1 : 0)
                    + "\nlyrichidden=" + (LockLyrics.sTapHidden ? 1 : 0)
                    + "\nlyrichdr=" + (LockLyrics.sHdr ? 1 : 0)
                    // Written as 1 or 0 like the rest, but read back as the default when absent:
                    // the key did not exist before this setting did, and the lyrics are supposed
                    // to look the way they always have on a file that predates it.
                    + "\nlyrictrans=" + (LockLyrics.sTrans ? 1 : 0)
                    + "\nlyricfill=" + LockLyrics.sStyle.fill
                    + "\nlyricpos=" + LockLyrics.sStyle.pos
                    + "\nlyricside=" + LockLyrics.sStyle.sideDp
                    + "\nlyricsize=" + LockLyrics.sStyle.sizeSp
                    + "\nlyricweight=" + LockLyrics.sStyle.weight
                    // Not a setting - whether the last lookup got its lyric from the session.
                    // Kept across restarts so the settings page does not accuse a working
                    // provider module of doing nothing merely because nothing has played yet;
                    // updated in both directions so it stops claiming one is working once it
                    // is not.
                    + "\nsawlyric=" + (LockLyrics.sSawSessionLyric ? 1 : 0)
                    + "\nfpavoid=" + sFpAvoid
                    + "\nminicfg=" + android.util.Base64.encodeToString(
                            MiniPlayerRuntime.configJson(sAppCtx).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            android.util.Base64.NO_WRAP)
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

    /** Set while the file is being applied, to keep a setter from writing a half-read state back. */
    private static volatile boolean sLoading;

    private static void loadState() {
        if (sAppCtx == null) return;
        java.io.File f = new java.io.File(sAppCtx.getFilesDir(), STATE_FILE);
        if (!f.exists()) return;
        boolean cover = false;
        sLoading = true;
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
                    // Every key in its own try. The loop's outer catch RETURNS, so without this
                    // one unreadable value takes every setting BELOW it in the file down with it,
                    // silently - and the log would say "loadState failed" without ever naming the
                    // key. One bad number should cost its own setting and nothing else.
                    try {
                        if ("cover".equals(k)) cover = "1".equals(v);
                        // "auto" was a stored setting; following the card is unconditional now.
                        else if ("bias".equals(k)) sBias = Float.parseFloat(v);
                        else if ("coverstyle".equals(k)) sCoverCardStyle =
                                sCoverCardStyle.with("mode", Float.parseFloat(v));
                        else if ("covercardfill".equals(k)) sCoverCardStyle =
                                sCoverCardStyle.with("fill", Float.parseFloat(v));
                        else if ("covercardpos".equals(k)) sCoverCardStyle =
                                sCoverCardStyle.with("pos", Float.parseFloat(v));
                        else if ("covercardcorner".equals(k)) sCoverCardStyle =
                                sCoverCardStyle.with("corner", Float.parseFloat(v));
                        // The dp size from before the shares. The file is rewritten without it,
                        // so this runs once; the old margin and offset are dropped.
                        else if ("covercardsize".equals(k)) sCoverCardStyle =
                                sCoverCardStyle.with("fill", CoverCardStyle
                                        .fillFromLegacySizeDp(Float.parseFloat(v)));
                        else if ("clock".equals(k)) setClockHeightDp(Float.parseFloat(v));
                        else if ("clocksize".equals(k)) setClockSize(Float.parseFloat(v));
                        else if ("clockoff".equals(k)) setClockOffsetDp(Float.parseFloat(v));
                        else if ("clockfull".equals(k)) ClockCollapse.restoreFullUnit(v);
                        else if ("glass".equals(k)) sGlassEnd = Float.parseFloat(v);
                        // Through the setter, the way "clock" is: the clamp and the push to the
                        // wallpaper process are both part of reading the value back.
                        else if ("spring".equals(k)) setClockResponse(Float.parseFloat(v));
                        else if ("mcart".equals(k)) sMcHideArt = "1".equals(v);
                        else if ("mclyricart".equals(k)) sMcArtInLyrics = "1".equals(v);
                        else if ("mctap".equals(k)) sMcTitleTap = "1".equals(v);
                        else if ("tap".equals(k)) sTapToggle = "1".equals(v);
                        else if ("fadewp".equals(k)) sFadeWp = "1".equals(v);
                        else if ("vcfade".equals(k)) sVideoFade = "1".equals(v);
                        else if ("fsmode2".equals(k)) sFadeMode = Integer.parseInt(v);
                        else if ("covergap".equals(k)) CoverPush.sCoverFadeGapMs = Long.parseLong(v);
                        else if ("hidefp".equals(k)) sHideFp = "1".equals(v);
                        else if ("aodsmall".equals(k)) sAodSmall = "1".equals(v);
                        else if ("colon".equals(k)) HyperTweaks.sForceColon = "1".equals(v);
                        else if ("seekglow".equals(k)) HyperTweaks.sBarGlow = "1".equals(v);
                        else if ("lyrics".equals(k)) LockLyrics.sEnabled = "1".equals(v);
                        else if ("lyrickeep".equals(k)) LockLyrics.sKeepOn = "1".equals(v);
                        else if ("lyrichidden".equals(k)) LockLyrics.sTapHidden = "1".equals(v);
                        else if ("lyrichdr".equals(k)) LockLyrics.sHdr = "1".equals(v);
                        else if ("lyrictrans".equals(k)) LockLyrics.sTrans = "1".equals(v);
                        // The dp lyricoff and lyricgap from before the shares are dropped: what
                        // they meant depends on the room, which is not known here.
                        else if ("lyricfill".equals(k)) LockLyrics.sStyle =
                                LockLyrics.sStyle.with("fill", Float.parseFloat(v));
                        else if ("lyricpos".equals(k)) LockLyrics.sStyle =
                                LockLyrics.sStyle.with("pos", Float.parseFloat(v));
                        else if ("lyricside".equals(k)) LockLyrics.sStyle =
                                LockLyrics.sStyle.with("side", Float.parseFloat(v));
                        else if ("lyricsize".equals(k)) LockLyrics.sStyle =
                                LockLyrics.sStyle.with("size", Float.parseFloat(v));
                        else if ("lyricweight".equals(k)) LockLyrics.sStyle =
                                LockLyrics.sStyle.with("weight", Float.parseFloat(v));
                        else if ("sawlyric".equals(k)) {
                            LockLyrics.sSawSessionLyric = "1".equals(v);
                        }
                        else if ("fpavoid".equals(k)) sFpAvoid = Integer.parseInt(v);
                        else if ("minicfg".equals(k)) MiniPlayerRuntime.applyConfig(sAppCtx,
                                new String(android.util.Base64.decode(v, android.util.Base64.DEFAULT),
                                        java.nio.charset.StandardCharsets.UTF_8));
                        // The whole shade settings page, in one prefix - the keys and their
                        // meaning belong to ShadeLayer.configure.
                        else if (k.startsWith("shade_")) {
                            ShadeLayer.configure(k.substring(6), Integer.parseInt(v));
                        }
                        else if ("cardrect".equals(k)) {
                            String[] r = v.split(",");
                            // Same sanity check the sampler applies, because a file written
                            // before it existed can hold a reading taken from the shade.
                            if (r.length == 4 && Integer.parseInt(r[1]) >= sScreenH / 3) {
                                sCardL = Integer.parseInt(r[0]); sCardT = Integer.parseInt(r[1]);
                                sCardW = Integer.parseInt(r[2]); sCardH = Integer.parseInt(r[3]);
                            }
                        }
                    } catch (Throwable t) {
                        Xp.log(TAG + "setting unreadable, keeping the default: "
                                + k + "=" + v + " (" + t + ")");
                    }
                    // "offdelay" was the pause timer, before the card became the switch.
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "loadState failed: " + t);
            return;
        } finally {
            sLoading = false;
        }
        Xp.log(TAG + "state restored: cover=" + cover + " bias=" + sBias);
        if (cover) {
            enterCoverMode(false);
            // The composed cover the shade reveals does not survive a restart, and the wallpaper
            // process has no reason to ask for art again - it kept its own texture on disk and
            // believes it already has one. Without this the lock screen comes back correct and
            // the shade stays empty until the next track change. Non-fresh, so it is not
            // suppressed for being the same artwork as last time.
            CoverPush.pushArtAsync(true, false);
        }
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
                if (op == null) op = "info";
                Xp.log(TAG + "recv op=" + op + " extras=" + i.getExtras());
                try {
                    if ("info".equals(op)) {
                        dumpInfo();
                    } else if ("anim".equals(op)) {
                        dumpAnimConfigs();
                    } else if ("hold".equals(op) || "release".equals(op)) {
                        // The OEM's squeeze on its own, for measuring it. Only with cover mode's
                        // clock off: ClockCollapse owns the hold while it is on.
                        final String name = op;
                        final boolean hold = "hold".equals(op);
                        final float y = i.getFloatExtra("y", Float.NaN);
                        final View cv = sContainer;
                        if (ClockCollapse.active()) {
                            Xp.log(TAG + op + ": the cover clock owns the hold ("
                                    + ClockCollapse.describe() + ")");
                        } else if (cv != null) cv.post(new Runnable() {
                            @Override
                            public void run() {
                                sHoldY = hold && !Float.isNaN(y) ? y : null;
                                float to = sHoldY != null ? sHoldY : sLastSystemY;
                                if (!Float.isNaN(to)) applyY(to);
                                Xp.log(TAG + name + " -> y=" + to);
                            }
                        });
                    } else if ("gscale".equals(op)) {
                        String gid = i.getStringExtra("id");
                        groupScale(gid == null ? "time_group" : gid, i.getFloatExtra("k", 1f),
                                i.getFloatExtra("px", -1f), i.getFloatExtra("py", -1f),
                                i.getFloatExtra("ty", 0f));
                    } else if ("cls".equals(op)) {
                        String name = i.getStringExtra("name");
                        if (name == null) dumpClockStyleInfo();
                        else dumpClass(name, i.getStringExtra("grep"));
                    } else if ("ctint".equals(op)) {
                        // The cover's colour, by hand, so a tint can be tried without hunting for
                        // the artwork that would produce it.
                        if (i.getBooleanExtra("off", false)) {
                            sCoverTintOverride = 0;
                            Xp.log(TAG + "cover tint back to measured #"
                                    + Integer.toHexString(sCoverTint));
                        } else {
                            // A hex string rather than an int extra: `am --ei` parses with
                            // Integer.valueOf, which does not take `0x`, and "#86a6b1" is how a
                            // colour is written everywhere else in this file.
                            String v = i.getStringExtra("v");
                            try {
                                sCoverTintOverride = v == null ? 0xff8888ff
                                        : (int) Long.parseLong(v.replace("#", ""), 16);
                            } catch (Throwable t) {
                                Xp.log(TAG + "ctint: cannot read " + v);
                                return;
                            }
                            Xp.log(TAG + "cover tint forced to #"
                                    + Integer.toHexString(sCoverTintOverride));
                        }
                        recolorClock();
                    } else if ("gdata".equals(op)) {
                        pokeGlassData(i.getIntExtra("idx", -1), i.getFloatExtra("v", 0f));
                    } else if ("aodprobe".equals(op)) {
                        setResultData(aodProbe());
                    } else if ("entries".equals(op)) {
                        setResultData(ClockCollapse.entries());
                    } else if ("poses".equals(op)) {
                        setResultData(ClockCollapse.poses());
                    } else if ("fall".equals(op)) {
                        setResultData(ClockCollapse.fall());
                    } else if ("card".equals(op)) {
                        setResultData(cardHistory());
                    } else if ("depth".equals(op)) {
                        setDepthHidden(!i.getBooleanExtra("on", true));
                    } else if ("vcprobe".equals(op)) {
                        // What each layer of a live cover is showing right now, as average colours:
                        // MIUI's two video TextureViews (whatever the player last put in them,
                        // hidden or not) and our own cover view.
                        View dd = findDeductedImageView();
                        setResultData("bg=" + avgColour(sVideoBg) + " fg=" + avgColour(sVideoFg)
                                + " cover=" + avgColour(sCover) + " uncover=" + sUncoverProbe
                                + " deducted=" + (dd == null ? "absent" : dd.getClass().getName()
                                + " vis=" + dd.getVisibility() + " alpha=" + r2(dd.getAlpha())
                                + " tAlpha=" + r2(dd.getTransitionAlpha())
                                + " drawable=" + (dd instanceof ImageView
                                ? ((ImageView) dd).getDrawable() : "-"))
                                + " depthHidden=" + sDepthHidden + " oemWants=" + sDeductedWanted
                                + " held=" + sDeductedHeld
                                + " | fgNow " + layerState(CoverPush.videoSurfaceView("keyguard_foreground_layer"))
                                + " | bgNow " + layerState(CoverPush.videoSurfaceView("keyguard_background_layer"))
                                + " | deducted " + layerState(dd)
                                + " | oem " + depthFlags());
                    } else if ("layer".equals(op)) {
                        // One of MIUI's three wallpaper layers set to a visibility, once, so the
                        // screen says which one is drawing what. which = fg | bg | deducted.
                        String which = i.getStringExtra("which");
                        View lv = "deducted".equals(which) ? findDeductedImageView()
                                : "bg".equals(which) ? CoverPush.videoSurfaceView("keyguard_background_layer")
                                : CoverPush.videoSurfaceView("keyguard_foreground_layer");
                        if (lv != null) {
                            lv.setVisibility(i.getBooleanExtra("hide", true) ? View.INVISIBLE : View.VISIBLE);
                        }
                        setResultData(which + " " + layerState(lv));
                    } else if ("uncover".equals(op)) {
                        // Hides our cover view only, leaving MIUI's layers as they are, so a
                        // screenshot shows what is under it. See CoverPush.guardVideoCover.
                        sUncoverProbe = i.getBooleanExtra("on", !sUncoverProbe);
                        View cv = sCover;
                        if (cv != null) cv.invalidate();
                        setResultData("uncover=" + sUncoverProbe);
                    } else if ("pushart".equals(op)) {
                        boolean on = i.getBooleanExtra("on", true);
                        if (i.hasExtra("bias")) sBias = clamp01(i.getFloatExtra("bias", sBias));
                        sTrackKey = on ? trackKey(pickController(c)) : "";
                        setCoverEnabled(on, i.getBooleanExtra("anim", true), false);
                    } else if ("coverstyle".equals(op)) {
                        String key = i.getStringExtra("key");
                        float value = i.getFloatExtra("v", Float.NaN);
                        CoverCardStyle next = sCoverCardStyle.with(key, value);
                        boolean modeChanged = next.mode != sCoverCardStyle.mode;
                        sCoverCardStyle = next;
                        CoverCardLayer.style(next);
                        CoverCardLayer.refresh();
                        saveStateSoon();
                        if (sCoverMode && modeChanged) {
                            if (next.mode == CoverCardStyle.CARD) {
                                CoverCardLayer.attach(CoverPush.coverLayer());
                                CoverCardLayer.playback(sCoverCardPlaying);
                            }
                            CoverPush.pushArtAsync(true, false);
                        }
                    } else if ("mediabtn".equals(op)) {
                        setResultData(dumpClickables());
                    } else if ("queue".equals(op)) {
                        setResultData(dumpQueues());
                    } else if ("wphello".equals(op)) {
                        // The wallpaper process, on its start or when asked, saying what it can
                        // take. See sWpComposes.
                        boolean composes = i.getBooleanExtra("composes", false);
                        // A restarted wallpaper process has forgotten the lyrics' blur.
                        LockLyrics.resendBlur();
                        if (composes != sWpComposes) {
                            sWpComposes = composes;
                            Xp.log(TAG + "wallpaper process "
                                    + (composes ? "composes covers itself, sending the source"
                                    : "wants the composed JPEG"));
                        }
                    } else if ("needart".equals(op)) {
                        // The wallpaper process came up with nothing to draw - see
                        // WallpaperProbe.askForArt(). It asks once per process start, and there
                        // is a floor between two asks and a ceiling on how many, so this cannot
                        // loop. What it may well be holding is the previous track's cover off
                        // its own disk, which is a state it cannot tell from the right one.
                        String why = i.getStringExtra("why");
                        if (!sCoverMode) {
                            // NOT ignored, and that was the bug behind "the wallpaper will not
                            // change back". Measured: the wallpaper process says `no art here,
                            // asked SystemUI for it`, cover mode is off, and this branch dropped
                            // the request on the floor - so the process kept the last cover
                            // texture it had been given and the lock screen showed that album
                            // art for ever, across every track, with nothing left to replace it.
                            // Asking with cover mode off means exactly one thing: hand it back.
                            //
                            // Rate limited because the answer is another broadcast and a
                            // wallpaper with an empty lock slot has nothing to reload either -
                            // without this, an empty slot and an asking wallpaper would trade
                            // messages.
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - sNeedArtOffAt > 3000L) {
                                sNeedArtOffAt = now;
                                Xp.log(TAG + "needart (" + why + ") with cover off: "
                                        + "telling the wallpaper to drop it and reload");
                                CoverPush.pushArtAsync(false, false);
                            }
                        } else {
                            Xp.log(TAG + "needart (" + why + "), resending the art");
                            // Not a track change: take whatever the session has now, without the
                            // same-artwork check that is what swallowed the later pushes.
                            CoverPush.pushArtAsync(true, false);
                        }
                    } else if ("mediacard".equals(op)) {
                        if (i.hasExtra("hideart")) sMcHideArt = i.getBooleanExtra("hideart", false);
                        if (i.hasExtra("lyricart")) {
                            sMcArtInLyrics = i.getBooleanExtra("lyricart", false);
                        }
                        if (i.hasExtra("titletap")) {
                            sMcTitleTap = i.getBooleanExtra("titletap", false);
                        }
                        saveState();
                        Xp.log(TAG + "media card hideArt=" + sMcHideArt
                                + " artInLyrics=" + sMcArtInLyrics
                                + " titleTap=" + sMcTitleTap);
                        applyMediaCard();
                    } else if ("texfit".equals(op)) {
                        // The screen-sized keyguard texture, on by default (WallpaperProbe's half
                        // is the one that does the work). Flipping it here flips that half too,
                        // because the two have to agree: with it on, the re-fit that rewrites the
                        // user's lockscreen wallpaper is skipped; with it off, that re-fit is the
                        // only thing keeping the crossfade affordable.
                        sTexFit = i.getBooleanExtra("on", !sTexFit);
                        Intent wp = CoverPush.wallpaperIntent("texfit");
                        wp.putExtra("on", sTexFit);
                        c.sendBroadcast(wp);
                        Xp.log(TAG + "texture fit to screen " + (sTexFit ? "ON" : "off")
                                + " (wallpaper re-fit " + (sTexFit ? "skipped" : "enabled") + ")");
                    } else if ("lyrics".equals(op)) {
                        boolean on = i.getBooleanExtra("on", !LockLyrics.sEnabled);
                        LockLyrics.setEnabled(on, sTrackKey, sWatched);
                        saveState();
                    } else if ("lyrichdr".equals(op)) {
                        LockLyrics.sHdr = i.getBooleanExtra("on", !LockLyrics.sHdr);
                        Xp.log(TAG + "lyrics HDR highlight: " + LockLyrics.sHdr);
                        LockLyrics.refresh();
                        saveState();
                    } else if ("lyrictrans".equals(op)) {
                        LockLyrics.sTrans = i.getBooleanExtra("on", !LockLyrics.sTrans);
                        Xp.log(TAG + "lyrics translations: " + LockLyrics.sTrans);
                        // The view notices the switch itself and lays the lines out again around
                        // it; refresh only has to start the frames that let it.
                        LockLyrics.refresh();
                        saveState();
                    } else if ("lyricstyle".equals(op)) {
                        String key = i.getStringExtra("key");
                        if (LockLyrics.setStyle(key, i.getFloatExtra("v", Float.NaN))) {
                            saveStateSoon();
                        }
                    } else if ("lyricinfo".equals(op)) {
                        // The playing session's metadata, every string key, with lyricInfo written
                        // out whole - to see how a player marks who sings which line.
                        setResultData(LockLyrics.dumpMetadata(c, sWatched));
                    } else if ("lyrickeep".equals(op)) {
                        LockLyrics.sKeepOn = i.getBooleanExtra("on", !LockLyrics.sKeepOn);
                        Xp.log(TAG + "lyrics keep the screen on: " + LockLyrics.sKeepOn);
                        LockLyrics.refresh();
                        saveState();
                    } else if ("lyricdemo".equals(op)) {
                        // Plays a database file by id on its own clock, whatever is playing:
                        //   --es id 101126 [--ez am true]   or   --ez clear true
                        // Cover mode still has to be on - that is what puts the view up.
                        if (i.getBooleanExtra("clear", false)) {
                            LockLyrics.endDemo(sTrackKey, sWatched);
                        } else {
                            String id = i.getStringExtra("id");
                            LockLyrics.demo(id == null ? "101126" : id,
                                    i.getBooleanExtra("am", false));
                        }
                    } else if ("looper".equals(op)) {
                        // What is queued on the main thread, grouped by where it came from. An ANR
                        // (2026-09-16 04:10) spent its last seconds in removeCallbacksAndMessages,
                        // which is only slow over a very long queue - this says whose it is.
                        setResultData(looperCensus());
                    } else if ("stilloff".equals(op)) {
                        LockLyrics.sStillOff = i.getBooleanExtra("on", !LockLyrics.sStillOff);
                        setResultData("still redraws " + (LockLyrics.sStillOff ? "off" : "on"));
                    } else if ("frames".equals(op)) {
                        // Who is asking the main thread for frames. The keyguard window was
                        // found rendering at 60fps through the AOD's still mode (2026-09-24)
                        // with nothing of the lyrics moving.
                        setResultData(frameCensus());
                    } else if ("slowlog".equals(op)) {
                        // The framework's own slow-message log on the main looper: every message
                        // that takes longer than `ms` to run is logged under the tag Looper with
                        // its handler and callback. 0 turns it off. Only timing per message, so
                        // cheap enough to leave on while a stutter is reproduced.
                        int ms = i.getIntExtra("ms", 0);
                        try {
                            android.os.Looper.class.getMethod("setSlowLogThresholdMs",
                                    long.class, long.class).invoke(
                                    android.os.Looper.getMainLooper(), (long) ms, 0L);
                            setResultData("slowlog " + (ms > 0 ? ms + "ms" : "off"));
                        } catch (Throwable t) {
                            setResultData("slowlog failed: " + t);
                        }
                    } else if ("twotap".equals(op)) {
                        setResultData("twoFingerDowns=" + sTwoSeen + " fired=" + sTwoFired
                                + " maxPointersSeen=" + sTwoMaxPointers + " trail=" + sTwoTrail
                                + " lastTwoTrail=" + sTwoTrailLast + " cancelled=" + sTwoCancelled
                                + " last=" + sTwoWhy
                                + " lyrics=" + LockLyrics.sEnabled
                                + " tap=" + (LockLyrics.sTapHidden ? "hidden" : "shown"));
                    } else if ("wpart".equals(op)) {
                        // The wallpaper has begun showing the new cover; see CoverCardLayer.
                        CoverCardLayer.releaseHeld();
                    } else if ("bouncer".equals(op)) {
                        setResultData(sBouncerTrace.toString());
                    } else if ("cardstate".equals(op)) {
                        // The square card's playback scale, next to what the session says - for
                        // "the card stayed small", where the log is not there to read.
                        MediaController w = sWatched;
                        PlaybackState ps = w == null ? null : w.getPlaybackState();
                        setResultData("toggles=[" + toggleCost() + "] session="
                                + (ps == null ? "none" : ps.getState())
                                + " playing=" + sCoverCardPlaying + " "
                                + CoverCardLayer.describe());
                    } else if ("tail".equals(op)) {
                        // This process's own recent log lines, the only way to read Xp.log on a
                        // phone whose logd keeps nothing below error level. --es grep x filters,
                        // --ei n caps the count (default 40, kept small for the binder reply).
                        setResultData(Xp.tail(i.getStringExtra("grep"), i.getIntExtra("n", 40)));
                    } else if ("lyricstate".equals(op)) {
                        String st = LockLyrics.describe();
                        Xp.log(TAG + "lyrics: " + st);
                        // Also as the broadcast's result, which `am broadcast` prints: on a
                        // phone whose LSPosed log drops INFO lines this is the only way to read it.
                        setResultData(st);
                    } else if ("lyricraw".equals(op)) {
                        // The session's lyricInfo exactly as it was published, to a file.
                        // metadump truncates every value at 160 characters, which is enough to
                        // see that a payload is there and not enough to see what shape it is -
                        // and NetEase's is a shape this module does not read yet.
                        String raw = LyricSource.lyricInfoOf(sWatched);
                        java.io.File rf = new java.io.File(c.getFilesDir(), "mc_lyricraw.txt");
                        try {
                            java.io.FileOutputStream os = new java.io.FileOutputStream(rf);
                            os.write((raw == null ? "no lyricInfo on "
                                    + (sWatched == null ? "no session"
                                       : sWatched.getPackageName()) : raw).getBytes("UTF-8"));
                            os.close();
                            rf.setReadable(true, false);
                            setResultData((raw == null ? "none" : raw.length() + " chars")
                                    + " -> " + rf.getAbsolutePath());
                        } catch (Throwable t) {
                            setResultData("lyricraw failed: " + t);
                        }
                    } else if ("lyricon".equals(op)) {
                        setResultData(LyriconSource.describe());
                    } else if ("local".equals(op)) {
                        // On a worker: this one reads the media database and then the file, and
                        // a broadcast receiver runs on the main thread.
                        final MediaController lw = sWatched;
                        final java.io.File lf =
                                new java.io.File(c.getFilesDir(), "mc_local.txt");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String d = LocalLyrics.describe(sAppCtx, lw);
                                Xp.log(TAG + "local: " + d);
                                try {
                                    java.io.FileOutputStream os =
                                            new java.io.FileOutputStream(lf);
                                    os.write(d.getBytes("UTF-8"));
                                    os.close();
                                    lf.setReadable(true, false);
                                } catch (Throwable t) {
                                    Xp.log(TAG + "local write failed: " + t);
                                }
                            }
                        }, "MCLocalProbe").start();
                        setResultData("looking -> " + lf.getAbsolutePath());
                    } else if ("metadump".equals(op)) {
                        String d = LyricSource.dumpMetadata(sWatched);
                        Xp.log(TAG + "metadump: " + d);
                        setResultData(d);
                    } else if ("ncm".equals(op)) {
                        // What the by-name route makes of the playing session: the four fields
                        // it matches on, the terms it would search, every candidate with how far
                        // its duration sits from ours, and which one that proves. On a worker
                        // because it is two network round trips, so the answer cannot be this
                        // broadcast's result - it goes to a file, which is also the only form
                        // of it this phone can read back (the module's INFO log does not
                        // survive to logcat here).
                        final MediaController w = sWatched;
                        // The four fields can also be given by hand, which is how a report of
                        // "this song never got lyrics" gets reproduced here without the song:
                        //   --es title .. --es artist .. --es album .. --el dur 252236
                        final boolean byHand = i.hasExtra("title") || i.hasExtra("artist")
                                || i.hasExtra("album") || i.hasExtra("dur");
                        final String qTitle = i.getStringExtra("title");
                        final String qArtist = i.getStringExtra("artist");
                        final String qAlbum = i.getStringExtra("album");
                        final long qDur = i.getLongExtra("dur", 0L);
                        final java.io.File out = new java.io.File(c.getFilesDir(), "mc_ncm.txt");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                // All three catalogues, in one account. Which of them was asked
                                // and what each one said is the whole question behind "this
                                // song never got lyrics", and asking them one probe at a time
                                // means three runs against three different minutes.
                                NcmLyrics.Query q = byHand
                                        ? NcmLyrics.build(qTitle, qArtist, qAlbum, qDur)
                                        : NcmLyrics.queryOf(w);
                                String d = (byHand
                                        ? NcmLyrics.describe(qTitle, qArtist, qAlbum, qDur)
                                        : NcmLyrics.describe(w)) + WebLyrics.describe(q)
                                        + OnlineLyrics.probe(w == null ? null : w.getPackageName(), q);
                                Xp.log(TAG + "ncm: " + d);
                                try {
                                    java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                                    os.write(d.getBytes("UTF-8"));
                                    os.close();
                                    out.setReadable(true, false);
                                } catch (Throwable t) {
                                    Xp.log(TAG + "ncm write failed: " + t);
                                }
                            }
                        }, "MCNcmProbe").start();
                        setResultData("searching -> " + out.getAbsolutePath());
                    } else if ("tweaks".equals(op)) {
                        // Which of the HyperOS restrictions actually came off in this process.
                        // Over the probe rather than the log because the module's INFO lines are
                        // not readable on this device - LSPosed keeps error level only.
                        setResultData(HyperTweaks.describe());
                    } else if ("colon".equals(op)) {
                        HyperTweaks.sForceColon = i.getBooleanExtra("on",
                                !HyperTweaks.sForceColon);
                        saveState();
                        // The always-on display draws its own clock in its own process, which
                        // can read neither this flag nor the file it is saved in, so the switch
                        // is mirrored somewhere both can see.
                        HyperTweaks.publishColon(sAppCtx);
                        // Nothing to re-apply: the clock asks its bean whether to draw the colon
                        // on the next layout, which the keyguard does every time it comes up.
                        Xp.log(TAG + "force clock colon "
                                + (HyperTweaks.sForceColon ? "on" : "off"));
                    } else if ("seekglow".equals(op)) {
                        HyperTweaks.sBarGlow = i.getBooleanExtra("on", !HyperTweaks.sBarGlow);
                        saveState();
                        // The card on screen was built before this switch was read, so it is
                        // upgraded in place - its constructor is long past and the mode it read
                        // there is a final field. Turning the switch off cannot undo that on this
                        // card: it applies to the next one the OEM builds.
                        View bar = findLockScreenView("media_progress_bar");
                        String r = bar == null ? "no card up" : HyperTweaks.applyBarGlow(bar);
                        Xp.log(TAG + "media bar glow " + (HyperTweaks.sBarGlow ? "on" : "off")
                                + " - " + r);
                        setResultData((HyperTweaks.sBarGlow ? "on " : "off ") + r
                                + (HyperTweaks.sBarGlow ? "" : " (the card up keeps its glow)"));
                    } else if ("hidefp".equals(op)) {
                        sHideFp = i.getBooleanExtra("on", !sHideFp);
                        saveState();
                        Xp.log(TAG + "hide fingerprint " + (sHideFp ? "on" : "off"));
                        applyHideFp();
                    } else if ("aodclock".equals(op)) {
                        sAodSmall = i.getBooleanExtra("small", !sAodSmall);
                        saveState();
                        // The pose a held doze was drawn at is the small one, and the fall into an
                        // OEM doze aims at that remembered pose rather than at the live clock. Left
                        // standing, turning the setting off would still land on the small clock
                        // once. Dropped, it is the state a doze that never settled is in.
                        ClockCollapse.forgetAodPose();
                        Xp.log(TAG + "AOD keeps the small clock " + (sAodSmall ? "on" : "off")
                                + " (full-screen AOD now: " + fullAodOn() + ")");
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
                    } else if ("vcfade".equals(op)) {
                        // Off by default: see the note on sVideoFade. Setting it here re-pushes
                        // the art, because the cover video that is on the device right now was
                        // built with whichever value was in force when it was pushed.
                        sVideoFade = i.getBooleanExtra("on", !sVideoFade);
                        saveState();
                        Xp.log(TAG + "cover video crossfade " + (sVideoFade ? "on" : "off")
                                + (sCoverMode ? " - re-pushing the art" : ""));
                        if (sCoverMode) CoverPush.pushArtAsync(true, false);
                    } else if ("fadesync".equals(op)) {
                        // --ei mode 0|1|2 picks one; with no mode it cycles, so the three can be
                        // compared on the phone with one command instead of three builds.
                        int mode = i.getIntExtra("mode", -1);
                        sFadeMode = mode >= 0 && mode <= FADE_MODE_STRETCH
                                ? mode : (sFadeMode + 1) % (FADE_MODE_STRETCH + 1);
                        saveState();
                        Xp.log(TAG + "cover fade mode = " + CoverPush.fadeModeName()
                                + " (0 off, 1 hold for the wallpaper window, 2 stretch by the"
                                + " last measured gap " + CoverPush.sCoverFadeGapMs + "ms)");
                    } else if ("videoreloading".equals(op)) {
                        CoverPush.noteVideoReloading();
                    } else if ("videoreload".equals(op)) {
                        // From WallpaperProbe, sent from inside the call that rebuilds the video
                        // player - and also from the paths where no reload is coming at all, so
                        // that the cover is never held for a message that will not arrive. See
                        // noteVideoReload() and sFadeMode.
                        CoverPush.noteVideoReload();
                        // The window's content changes a few hundred ms AFTER this message, on
                        // the wallpaper side, with nothing in this process asking for a frame -
                        // and the notif cards' frosted backdrop re-samples what is behind them
                        // only when this window redraws. Left alone it keeps serving the last
                        // frame it sampled while the wallpaper under it has already swapped,
                        // which is the report that the blur arrives half a beat late. Pump
                        // redraws until the swap has certainly landed. See startBlurSync().
                        startBlurSync(i.getStringExtra("why"));
                        LockLyrics.onVideoReload();
                        // The rebuild also resets the wallpaper-side render state, and with it
                        // the AOD's wallpaper dim (set through setWallpaperBlack /
                        // setWallPaperAnimProcess - see sLastWallpaperBlack). Re-assert it once
                        // the first frame is up, or the cover sits in AOD at full brightness
                        // while the wallpaper next to it would have been dimmed.
                        if (sCoverMode) reassertAodDim();
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
                        setClockHeightDp(i.getFloatExtra("v", DEFAULT_CLOCK_HEIGHT_DP));
                        saveState();
                        // A settled clock has no frames left to carry a new size on; ask for one.
                        ClockCollapse.refresh();
                    } else if ("clocksize".equals(op)) {
                        setClockSize(i.getFloatExtra("v", 1f));
                        saveState();
                        ClockCollapse.refresh();
                    } else if ("clockoffset".equals(op)) {
                        setClockOffsetDp(i.getFloatExtra("v", 0f));
                        saveState();
                        ClockCollapse.refresh();
                    } else if ("glassend".equals(op)) {
                        sGlassEnd = clamp01(i.getFloatExtra("v", DEFAULT_GLASS_END));
                        saveState();
                        Xp.log(TAG + "glass end = " + sGlassEnd);
                        ClockCollapse.refresh();
                    } else if ("clockspring".equals(op)) {
                        // Nothing forced on screen: see setClockResponse(). The fades it pushes
                        // are the only thing outside this process.
                        setClockResponse(i.getFloatExtra("v", EASE_COVER[1]));
                    } else if ("auto".equals(op)) {
                        setAuto(i.getBooleanExtra("on", true));
                    } else if ("reload".equals(op)) {
                        CoverPush.requestWallpaperReload(c);
                    } else if ("lockwp".equals(op)) {
                        final Context cc = sAppCtx;
                        final boolean clear = i.getBooleanExtra("clear", false);
                        final boolean force = i.getBooleanExtra("force", false);
                        worker().post(new Runnable() {
                            @Override
                            public void run() {
                                if (clear) CoverPush.clearLockWallpaper(cc);
                                else CoverPush.ensureLockWallpaper(cc, force);
                            }
                        });
                    } else if ("cover".equals(op)) {
                        if (i.getBooleanExtra("on", true)) {
                            sCoverWanted = true;
                            attachCover();
                        } else {
                            // Through setCoverEnabled, exactly like a tap, and NOT detachCover()
                            // directly. detachCover() only takes our own keyguard view away; the
                            // cover itself is a texture in the wallpaper process, and handing
                            // that back is pushArtAsync(false) inside setCoverEnabled. Calling
                            // the short one leaves the album art on the lock screen for good -
                            // seen on device, after a measurement session that toggled cover mode
                            // with this probe and then could not get the wallpaper back.
                            sCoverWanted = false;
                            setCoverEnabled(false, false, false);
                        }

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
                    } else if ("oemtrans".equals(op)) {
                        dumpOemTranslation(i.getFloatExtra("y", 1200f));
                    } else if ("viewtypes".equals(op)) {
                        dumpClockViewTypes();
                    } else if ("notif".equals(op)) {
                        dumpNotifState();
                    } else if ("chargeanim".equals(op)) {
                        setResultData(chargeAnimReport());
                    } else if ("views".equals(op)) {
                        dumpViewTree(i.getBooleanExtra("root", false));
                    } else if ("shadecfg".equals(op)) {
                        // One op for the whole settings page. The key is validated and the value
                        // clamped in ShadeLayer.configure, so adb and the UI go through exactly
                        // the same door.
                        ShadeLayer.configure(i.getStringExtra("key"), i.getIntExtra("v", 0));
                        saveState();
                    } else if ("minicfg".equals(op)) {
                        MiniPlayerRuntime.applyConfig(c, i.getStringExtra("json"));
                        saveState();
                    } else if ("query".equals(op)) {
                        // Answered through the ordered broadcast's result extras: the app is a
                        // separate process and this is the only channel it already has. A reply
                        // arriving at all is also how the app knows the module is loaded.
                        android.os.Bundle out = new android.os.Bundle();
                        out.putBoolean("alive", true);
                        out.putBoolean("cover", sCoverMode);
                        out.putString("minicfg", MiniPlayerRuntime.configJson(c));
                        float[] shortcuts = MiniPlayerRuntime.shortcutGeometry();
                        if (shortcuts != null) out.putFloatArray("minishortcuts", shortcuts);
                        out.putBoolean("auto", sAuto);
                        out.putFloat("bias", sBias);
                        out.putInt("coverstyle", sCoverCardStyle.mode);
                        out.putFloat("covercardfill", sCoverCardStyle.fill);
                        out.putFloat("covercardpos", sCoverCardStyle.pos);
                        out.putFloat("covercardcorner", sCoverCardStyle.corner);
                        out.putFloat("clock", sClockHeightDp);
                        out.putFloat("clocksize", effectiveClockSize());
                        out.putFloat("clockoff", sClockOffsetDp);
                        out.putFloat("glass", sGlassEnd);
                        out.putFloat("spring", sClockResponse);
                        // The whole shade settings page, generated from the one key list so a new
                        // knob cannot reach the state file and miss the settings app.
                        for (String key : ShadeLayer.CFG_KEYS) {
                            out.putInt("shade_" + key, ShadeLayer.cfgInt(key));
                        }
                        out.putInt("shadeMode", ShadeLayer.mode());
                        out.putBoolean("card", sCardShowing);
                        // Checked live rather than reported from the cached flag: the user can
                        // change the wallpaper at any time and that is what breaks the feature.
                        out.putBoolean("lockwp", CoverPush.hasLockWallpaper(c));
                        out.putString("track", sCardKey);
                        MediaController mc = sWatched;
                        out.putString("player", mc == null ? "" : mc.getPackageName());
                        out.putBoolean("mcart", sMcHideArt);
                        out.putBoolean("mclyricart", sMcArtInLyrics);
                        out.putBoolean("mctap", sMcTitleTap);
                        out.putBoolean("tap", sTapToggle);
                        out.putBoolean("fadewp", sFadeWp);
                        out.putInt("fsmode2", sFadeMode);
                        out.putBoolean("vcfade", sVideoFade);
                        out.putBoolean("hidefp", sHideFp);
                        out.putBoolean("aodsmall", sAodSmall);
                        out.putBoolean("colon", HyperTweaks.sForceColon);
                        out.putBoolean("seekglow", HyperTweaks.sBarGlow);
                        out.putBoolean("lyrics", LockLyrics.sEnabled);
                        out.putBoolean("lyrickeep", LockLyrics.sKeepOn);
                        out.putBoolean("lyrichdr", LockLyrics.sHdr);
                        out.putBoolean("lyrictrans", LockLyrics.sTrans);
                        out.putFloat("lyricfill", LockLyrics.sStyle.fill);
                        out.putFloat("lyricpos", LockLyrics.sStyle.pos);
                        out.putFloat("lyricside", LockLyrics.sStyle.sideDp);
                        out.putFloat("lyricsize", LockLyrics.sStyle.sizeSp);
                        out.putInt("lyricweight", LockLyrics.sStyle.weight);
                        // Whether anything has actually written a lyric to a session, which is
                        // what tells a working provider module from a merely installed one.
                        out.putBoolean("sessionlyric", LockLyrics.sSawSessionLyric
                                || LyricSource.hasLyricInfo(sWatched));
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
                            out.putFloat("clockfull", clockFullRatio());
                        }
                        // Outside the block above: it is a property of the STYLE, not of the
                        // measurement, and the app needs it even on a frame where the clock
                        // could not be measured - that is exactly when the slider it disables
                        // would otherwise look like it was doing something.
                        out.putBoolean("clockglass", clockHasGlass());
                        setResultExtras(out);
                    } else if ("diag".equals(op)) {
                        String report = clockReport();
                        android.os.Bundle rb = new android.os.Bundle();
                        rb.putString("report", report);
                        setResultExtras(rb);
                        // Logged as well as answered: an adb run of this op should not have to
                        // go through the app to see it.
                        for (String line : report.split("\n")) Xp.log(TAG + line);
                    } else if ("vtree".equals(op)) {
                        dumpViewTree();
                    } else if ("bounds".equals(op)) {
                        dumpClockBounds();
                    } else if ("geom".equals(op)) {
                        dumpGeom();
                    } else if ("ink".equals(op)) {
                        dumpInk();
                    } else if ("mini".equals(op)) {
                        setResultData(MiniPlayerRuntime.describe());
                    } else if ("alphasweep".equals(op)) {
                        // --ei ms N starts a sweep; without it, reads the last one back.
                        int ms = i.getIntExtra("ms", 0);
                        if (ms > 0) MotionTrace.startAlphaSweep(ms);
                        setResultData(MotionTrace.sSweep);
                    } else if ("motiontrace".equals(op)) {
                        MotionTrace.arm(i.getIntExtra("n", 4));
                    } else if ("geomtrace".equals(op)) {
                        startGeomTrace(i.getIntExtra("ms", 4000));
                    } else if ("state".equals(op)) {
                        Xp.log(TAG + "state: holdY=" + sHoldY
                                + " lastSystemY=" + sLastSystemY + " clock " + ClockCollapse.describe()
                                + " container=" + (sContainer != null) + " verbose=" + sVerbose);
                        Xp.log(TAG + "cover: on=" + sCoverMode + " auto=" + sAuto
                                + " bias=" + sBias + " screen=" + sScreenW + "x" + sScreenH
                                + " card=" + (sCardKnown ? (sCardShowing ? sCardKey : "gone") : "unknown")
                                + " following=" + (sWatched == null ? "none" : sWatched.getPackageName())
                                + " track=" + sTrackKey);
                        Xp.log(TAG + "card rect: " + sCardL + "," + sCardT + " "
                                + sCardW + "x" + sCardH + " hideArt=" + sMcHideArt
                                + " titleTap=" + sMcTitleTap
                                + " title=" + (sCardTitleTapped != null) + " cardP=" + sCardP);
                        Xp.log(TAG + "tap: toggle=" + sTapToggle
                                + " suppressed=" + sTapSuppressed
                                + " bouncer=" + bouncerUp()
                                + " wallpaperFade=" + sFadeWp);
                    } else if ("verbose".equals(op)) {
                        sVerbose = i.getBooleanExtra("on", !sVerbose);
                        LockLyrics.verbose = sVerbose;
                        Xp.log(TAG + "verbose=" + sVerbose);
                    } else {
                        Xp.log(TAG + "unknown op " + op);
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        registerSecretCode(ctx);
        Xp.log(TAG + "receiver registered for " + ACTION);
        // (registerSecretCode is defined below; see the comment there for why the dialled code
        // is answered from in here rather than by the app's own manifest receiver.)
        // Asks the wallpaper process what it can take, now that there is a receiver for the
        // answer. A build that predates the question never answers, which is the answer.
        try {
            ctx.sendBroadcast(CoverPush.wallpaperIntent("hello"));
        } catch (Throwable t) {
            Xp.log(TAG + "hello to the wallpaper process failed: " + t);
        }
        loadState();
        // Again at startup, not only when the switch is touched: the flag the always-on display
        // reads is written by this process, and a phone that was rebooted with the switch on has
        // nothing in it otherwise.
        HyperTweaks.publishColon(ctx);
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
                String k = CoverPush.wallpaperKind(appCtx, "lock");
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
                // Cached for the per-frame paths, which cannot afford screenOn()'s binder call:
                // how the clock is inked is a per-frame decision. screenOn() itself stays for the
                // event-time questions, where it is authoritative.
                // Every one of these three changes the answer to one of the two cached readings,
                // so the timer below is not what anyone waits on at the moments that matter.
                forgetSysReads();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    sScreenOn = true;
                    sAodGrey = Float.NaN;
                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    sScreenOn = false;
                    sAodGrey = Float.NaN;
                    // A tap still waiting out its double tap window was aimed at a screen that
                    // is gone; whatever was going to cancel it cannot arrive now.
                    cancelPendingTap("screen off");
                }
                if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    CoverMorphLayer.cancel();
                    CoverCardLayer.hideNow();
                }
                else CoverCardLayer.refresh();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) CoverMorphLayer.cancel();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    // The wake normally entered already, from the doAnimationToAod hook, before
                    // the first lit frame. This is the fallback for a build without that method.
                    if (sCoverMode && ClockCollapse.leavingOrOff() && keyguardShowing()) {
                        ClockCollapse.enter(true, true, "screenOn");
                    }
                    // Waking re-runs the OEM's depth pipeline, and if the keyguard was rebuilt
                    // while the screen was off the guard went away with the old view.
                    if (sDepthHidden) setDepthHidden(true);
                    // A hand-back of the live wallpaper that had to wait for a lock screen.
                    else if (sVideoWpOwed) setDepthHidden(false);
                    if (sCoverMode) applyMediaCard();
                    return;
                }
                // Cover mode outlives the display going off and the phone being unlocked; its
                // grip on the clock does not. The AOD shows the full clock - the AOD's clock is
                // this same clock, SystemUI's keyguard in doze - and so does an unlocked phone.
                // Going off, the sleep hooks have normally started the walk into the AOD already
                // and this does nothing; it is the fallback for a build without them.
                if (Intent.ACTION_SCREEN_OFF.equals(a) && sCoverMode) ClockCollapse.toAod();
                else ClockCollapse.release(a);
            }
        };
        IntentFilter lf = new IntentFilter();
        lf.addAction(Intent.ACTION_SCREEN_OFF);
        lf.addAction(Intent.ACTION_SCREEN_ON);
        lf.addAction(Intent.ACTION_USER_PRESENT);
        ctx.registerReceiver(lifecycle, lf, Context.RECEIVER_NOT_EXPORTED);
        Xp.log(TAG + "lifecycle receiver registered (screen off / user present)");
    }

    /**
     * Whether the display is on, as of the last lifecycle broadcast.
     *
     * A cache of screenOn() for the paths that ask once per frame. Optimistic by default: a
     * missed broadcast would otherwise leave the cover's colour switched off for good, and being
     * wrong in that direction costs a screenshot's worth of wrongness rather than a clock nobody
     * can read.
     */
    private static volatile boolean sScreenOn = true;

    /** The cached reading, for LockLyrics' per-frame questions. */
    static boolean screenOnCached() {
        return sScreenOn;
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

    /**
     * The named constant of that enum, or its first one if the names have moved too.
     *
     * Remembered per name, because this is called once per frame of every transition that drives
     * the clock - applyY() asks for it before every notifStateChange - and the answer is the same
     * constant for the life of the process. Resolving it costs an array: getEnumConstants()
     * hands back a fresh clone on every call, and the scan then allocates an iterator. Caching it
     * also stops the "no such constant" line below from being logged sixty times a second on a
     * build whose names have moved, which is a cross-process write on the frames that can least
     * afford one; it is now said once, and then the fallback is simply used.
     */
    private static final java.util.HashMap<String, Object> sTopTypes = new java.util.HashMap<>();
    private static Class<?> sTopTypesFor;

    private static Object topChangeType(String name) {
        Class<?> c = sTypeCls;
        if (c == null) return null;
        if (sTopTypesFor == c) {
            Object hit = sTopTypes.get(name);
            if (hit != null) return hit;
        } else {
            sTopTypes.clear();
            sTopTypesFor = c;
        }
        Object[] all = c.getEnumConstants();
        if (all == null || all.length == 0) return null;
        for (Object o : all) {
            if (((Enum<?>) o).name().equals(name)) {
                sTopTypes.put(name, o);
                return o;
            }
        }
        Xp.log(TAG + "no " + name + " on " + c.getName() + ", falling back to "
                + ((Enum<?>) all[0]).name());
        sTopTypes.put(name, all[0]);
        return all[0];
    }

    /**
     * Puts the OEM's clock at one notifY, in this frame, with no animation of its own. Must
     * already be on the view's thread.
     */
    static void applyY(float y) {
        View v = sContainer;
        Method m = sNotifStateChange;
        if (v == null || m == null) return;
        try {
            Object type = topChangeType("STATE_CHANGED");
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
        } catch (Throwable t) {
            Xp.log(TAG + "applyY failed: " + Log.getStackTraceString(t));
        }
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
    /**
     * The layout-space relationship between the date and the clock, printed on demand.
     *
     * W3 rests on one claim that has never been measured on this device: that the date and the
     * clock target sit in ONE frame, so their `getTop()`s can be subtracted meaningfully. Two
     * earlier attempts assumed a frame relation in one direction or the other and were off by
     * ~700px. So this prints the frames instead of assuming them - each view's own `getTop()`
     * (parent-relative, i.e. layout space), the same view's rendered position, and the chain of
     * ancestors up towards the container, so "do these two numbers mean the same thing" is
     * answered by what is actually on the screen.
     *
     * Called by hand (`op geom`) after each step of a sweep, which is why it logs a block and
     * writes nothing.
     */
    private static void dumpGeom() {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "geom: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder sb = new StringBuilder("geom");
                    sb.append(" hold=").append(sHoldY).append(" natural=").append(r1(sLastSystemY))
                      .append(" clock ").append(ClockCollapse.describe())
                      .append(" box=").append(boxOf(glyphBox()))
                      .append(" live=").append(boxOf(measureLiveBox()))
                      .append(" cac=").append(cacTy())
                      .append(" oemDateMargin=").append(oemDateMargin());
                    Xp.log(TAG + sb.toString());
                    Xp.log(TAG + "  date " + geomOf(sDateView));
                    for (View root : clockRoots()) {
                        Xp.log(TAG + "  clock(" + viewIdOf(root) + ") "
                                + geomOf(clockTarget(root)));
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "geom failed: " + t);
                }
            }
        });
    }

    /**
     * The glyph box measured on THIS frame, with the hold and the cache both stood down.
     *
     * A probe and nothing else: every reading it takes is put back exactly as it was, because
     * sInkUnit and the cached box are what the next real placement is made from and a diagnostic
     * that moves them is measuring itself.
     */
    private static RectF measureLiveBox() {
        RectF keepBox = sGlyphCache;
        long keepAt = sGlyphAt;
        float keepUnit = sInkUnit;
        try {
            sProbeLive = true;
            return glyphBox();
        } catch (Throwable t) {
            return null;
        } finally {
            sProbeLive = false;
            sGlyphCache = keepBox;
            sGlyphAt = keepAt;
            sInkUnit = keepUnit;
        }
    }

    /**
     * Per-frame trace of the four numbers the date placement is made of, and nothing else.
     *
     * `op verbose` answers this too and costs the frame rate doing it - it goes through the
     * LSPosed bridge several times per frame and the lock screen visibly stutters, so a
     * transition measured under it is not the transition anyone sees. This is one line, built
     * from fields already in hand plus two getters, so the frame it describes is the real one.
     *
     * What it is for: the date's screen position is `container.ty + date.top + date.ty`. If the
     * jump is a compensation that arrives a frame late, then the STEP in `dscr` is larger than
     * the step in `cty` on exactly the frames where the OEM moves the container; if it is
     * something else, `dtop` moves instead. One or the other, and no way to tell them apart from
     * a settled reading.
     */
    private static volatile long sGeomTraceUntil;
    private static int sGeomTraceN;

    private static void startGeomTrace(int ms) {
        sGeomTraceUntil = android.os.SystemClock.uptimeMillis() + Math.max(500, ms);
        sGeomTraceN = 0;
        // Its OWN listener, deliberately. It used to ride on sClockGuard, and exitCoverMode()
        // removes that guard before it starts the exit spring - so the one transition this
        // exists to look at was the one transition it could not see, and "no frames were drawn"
        // was the instrument being unplugged rather than the screen standing still.
        View v = sContainer;
        if (v != null) {
            try {
                v.getViewTreeObserver().addOnPreDrawListener(sGeomTraceL);
                sGeomTraced = v;
            } catch (Throwable t) {
                Xp.log(TAG + "geomtrace not installed: " + t);
            }
        }
        Xp.log(TAG + "geomtrace for " + ms + "ms on " + (v == null ? "no container" : idOf(v)));
    }

    private static void stopGeomTrace() {
        View v = sGeomTraced;
        sGeomTraced = null;
        if (v == null) return;
        try {
            v.getViewTreeObserver().removeOnPreDrawListener(sGeomTraceL);
        } catch (Throwable ignored) {
        }
    }

    private static View sGeomTraced;
    private static final ViewTreeObserver.OnPreDrawListener sGeomTraceL =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            traceGeomFrame();
            return true;
        }
    };

    private static void traceGeomFrame() {
        if (sGeomTraceUntil == 0L) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now > sGeomTraceUntil) {
            sGeomTraceUntil = 0L;
            stopGeomTrace();
            Xp.log(TAG + "geomtrace done, " + sGeomTraceN + " frames");
            return;
        }
        sGeomTraceN++;
        View date = sDateView;
        View c = sContainer;
        StringBuilder sb = new StringBuilder("gf ");
        sb.append(ClockCollapse.phase());
        // The date's own parent is `clock_animation_container`, and THAT is the view the OEM
        // squeezes by translating - `sContainer` is `miui_keyguard_clock_container`, one level
        // further up, and its translation is always 0. Reading the wrong one of the two is how
        // a trace can show a container standing still while the date is provably being carried.
        View cac = date == null || !(date.getParent() instanceof View)
                ? null : (View) date.getParent();
        sb.append(" cac=").append(cac == null ? "?" : r1(cac.getTranslationY()));
        sb.append(" cty=").append(c == null ? "?" : r1(c.getTranslationY()));
        if (date == null) {
            sb.append(" date=MISSING");
        } else {
            int[] loc = new int[2];
            date.getLocationOnScreen(loc);
            sb.append(" dtop=").append(date.getTop())
              .append(" dty=").append(r1(date.getTranslationY()))
              .append(" dscr=").append(loc[1]);
        }
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            int[] loc = new int[2];
            g.getLocationOnScreen(loc);
            sb.append(" | ").append(idOf(g)).append(" gtop=").append(g.getTop())
              .append(" gty=").append(r1(g.getTranslationY()))
              .append(" gk=").append(r2(g.getScaleY()))
              .append(" gscr=").append(loc[1]);
        }
        {
            sb.append(" | t=").append(now).append(" box=").append(boxOf(measureLiveBox()));
            // Anything above the container that could be carrying a wake animation.
            View up = c;
            for (int k = 0; k < 4 && up != null; k++) {
                sb.append(" ^").append(viewIdOf(up)).append(" sy=").append(r2(up.getScaleY()))
                  .append(" ty=").append(r1(up.getTranslationY()))
                  .append(" a=").append(r2(up.getAlpha()));
                up = up.getParent() instanceof View ? (View) up.getParent() : null;
            }
        }
        Xp.log(TAG + sb.toString());
    }

    /** One view's layout position, its rendered position, and the frames it hangs in. */
    private static String geomOf(View v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(viewIdOf(v));
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(" top=").append(v.getTop())
          .append(" left=").append(v.getLeft())
          .append(" h=").append(v.getHeight())
          .append(" ty=").append(r1(v.getTranslationY()))
          .append(" sy=").append(r2(v.getScaleY()))
          .append(" pivotY=").append(r1(v.getPivotY()))
          .append(" screen=").append(loc[1]);
        // Up towards the container: a difference between two views only cancels if both ends
        // reach the same ancestor carrying the same accumulated translation.
        View p = v.getParent() instanceof View ? (View) v.getParent() : null;
        for (int i = 0; i < 5 && p != null; i++) {
            sb.append(" <- ").append(viewIdOf(p)).append(" top=").append(p.getTop())
              .append(" ty=").append(r1(p.getTranslationY()))
              .append(" sy=").append(r2(p.getScaleY()));
            if (p == sContainer) break;
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return sb.toString();
    }

    /**
     * Where the OEM currently has `clock_animation_container` - the view the squeeze translates,
     * and the one both the date and the clock hang inside. Read rather than assumed: it is 0 in
     * the layouts without a media card and -168 in the collapsed one, and code that takes it for
     * granted is how the date ends up 35px out of place when an exit is interrupted.
     */
    private static float containerTy() {
        View d = sDateView;
        if (d == null || !(d.getParent() instanceof View)) return 0f;
        return ((View) d.getParent()).getTranslationY();
    }

    /** The translation the OEM puts on `clock_animation_container`, which the date rides. */
    private static String cacTy() {
        View d = sDateView;
        if (d == null || !(d.getParent() instanceof View)) return "?";
        return r1(((View) d.getParent()).getTranslationY());
    }

    /**
     * Every measurement the ink box could be taken from, per style, printed rather than picked.
     *
     * `glyphBox()` picks one of several sources per style and the choice is why the same 55.7dp
     * setting gives k=0.09 on one style and k=1.0 on the next: the fallback is the target's own
     * bounds, which is the whole container wherever the target is a container. This walks the
     * target and prints what each view would answer, so the replacement is chosen from numbers.
     */
    private static void dumpInk() {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "ink: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    View t = clockTarget(root);
                    Xp.log(TAG + "ink tree=" + viewIdOf(root) + " target="
                            + (t == null ? "NONE" : viewIdOf(t)));
                    if (t == null) continue;
                    inkWalk(t, t, 0);
                }
            }
        });
    }

    private static void inkWalk(View target, View v, int depth) {
        if (v == null || depth > 6) return;
        StringBuilder sb = new StringBuilder("ink   ");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append(" #").append(viewIdOf(v))
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility());
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(" at=").append(loc[0]).append(',').append(loc[1]);
        // The three shapes an ink measurement could come from, asked in the same order
        // inkBox() asks them.
        try {
            Object b = Xp.callMethod(v, "getTextBoundsWithPosition");
            if (b != null) sb.append(" textBounds=").append(b);
        } catch (Throwable ignored) {
        }
        for (String m : new String[]{"getRectSize", "getRealWidth", "getRealHeight",
                "getLeftPosition", "getTopPosition"}) {
            try {
                Object r = Xp.callMethod(v, m);
                if (r != null) sb.append(' ').append(m).append('=').append(r);
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof android.widget.TextView) {
            android.text.Layout lay = ((android.widget.TextView) v).getLayout();
            if (lay != null && lay.getLineCount() > 0) {
                sb.append(" lineTop=").append(lay.getLineTop(0))
                  .append(" lineBottom=").append(lay.getLineBottom(lay.getLineCount() - 1))
                  .append(" lineWidth=").append(lay.getLineWidth(0));
            }
        }
        Xp.log(TAG + sb.toString());
        if (v instanceof ViewGroup && depth < 6) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) inkWalk(target, g.getChildAt(i), depth + 1);
        }
    }

    /** The pooled glyph box as `l,t,r,b`, or none. */
    private static String boxOf(RectF b) {
        if (b == null) return "none";
        return r1(b.left) + "," + r1(b.top) + "," + r1(b.right) + "," + r1(b.bottom);
    }

    /** The OEM's own date+margin height, or the field's value as it came, or n/a. */
    private static String oemDateMargin() {
        try {
            View v = sContainer;
            Object it = v == null ? null : Xp.getObjectField(v, "keyguardClockNotifInteractor");
            Object got = it == null ? null : Xp.getObjectField(it, "dateHeightWithMargin");
            if (got == null) return "n/a";
            return String.valueOf(got);
        } catch (Throwable t) {
            return "n/a";
        }
    }

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

    // ------------------------------------------------------------------ collapse guard

    /**
     * The collapsed clock is asserted once per frame the keyguard draws, the same way the depth
     * cut-out and the media card are.
     *
     * Everything the placement writes is derived from the layout, and it is only ever written
     * while something is driving frames. In cover mode at rest nothing is: the OEM has stopped
     * emitting notifY, so applyCollapse() is not called, and the clock keeps whatever the last
     * driven frame left on it. Anything that changes *then* is invisible to us and stays wrong.
     *
     * Measured, on the path that had no other trigger left (SystemUI restarted with the screen
     * off, then woken): the one placement this process ran happened while the clock container was
     * still at screen y = 0, the OEM then translated the whole container 728px up, and no further
     * frame ever came. The date ended up at screen y = -501 - off the top of the screen - with
     * the transform on the views perfectly self-consistent, so nothing that only checks whether
     * the placement ran can tell.
     *
     * The layout listener below catches a rebuild that changes a *layout*. This catches
     * everything else, transforms included, which no layout event ever reports. The check is
     * deliberately cheap - the already-cached date view and clock targets, no lookups of its own
     * - so measuring the glyphs only happens on the frames where the answer is no.
     */
    private static View sClockGuarded;















    private static String us(long ns) {
        return ns < 1000000L ? (ns / 1000) + "us" : r1(ns / 1000000f) + "ms";
    }






    /** The density of whatever screen this process is glued to. */
    static float density() {
        View v = sContainer;
        if (v != null) return v.getResources().getDisplayMetrics().density;
        return sAppCtx != null ? sAppCtx.getResources().getDisplayMetrics().density : 3f;
    }




    /**
     * The height the setting means, converting a pre-dp value the once there is a box to convert
     * it with. The coefficient multiplied this same box, so dividing it back out is what it was
     * worth - the clock keeps the size it had, instead of becoming the 0.335dp the old number
     * would read as.
     */
    private static float heightDpFor(RectF box) {
        float legacy = sClockLegacyK;
        if (Float.isNaN(legacy)) return sClockHeightDp;
        sClockLegacyK = Float.NaN;
        if (box == null || box.height() <= 0f) return sClockHeightDp;
        float dp = legacy * box.height() / density();
        sClockHeightDp = dp < CLOCK_HEIGHT_MIN_DP ? CLOCK_HEIGHT_MIN_DP
                : (dp > CLOCK_HEIGHT_MAX_DP ? CLOCK_HEIGHT_MAX_DP : dp);
        Xp.log(TAG + "clock scale " + r3(legacy) + " was a coefficient; " + r1(box.height())
                + "px of glyphs makes that " + r1(sClockHeightDp) + "dp");
        saveState();
        return sClockHeightDp;
    }



    /** When the last with-cover-off needart answer was sent. See the needart branch. */
    private static volatile long sNeedArtOffAt;



    /**
     * The collapsed clock's height, in dp, from the slider or from a stored value.
     *
     * A value under the slider's own range is a coefficient from before the unit changed: it is
     * set aside rather than clamped, because what it is worth is boxHeight * k and the box is
     * whatever style and layout the phone is on. See sClockLegacyK.
     */
    private static void setClockHeightDp(float v) {
        if (v < CLOCK_HEIGHT_MIN_DP) {
            sClockLegacyK = v;
            Xp.log(TAG + "clock value " + v + " predates the dp unit, converting when measured");
            return;
        }
        sClockLegacyK = Float.NaN;
        sClockHeightDp = v > CLOCK_HEIGHT_MAX_DP ? CLOCK_HEIGHT_MAX_DP : v;
        Xp.log(TAG + "clock height = " + sClockHeightDp + "dp");
    }

    /** The clock size as a fraction of the style's full clock. NaN = follow sClockHeightDp. */
    private static void setClockSize(float v) {
        if (Float.isNaN(v) || v <= 0f) {
            sClockSize = Float.NaN;
        } else {
            sClockSize = v < CLOCK_SIZE_MIN ? CLOCK_SIZE_MIN : (v > 1f ? 1f : v);
        }
        Xp.log(TAG + "clock size = " + sClockSize);
    }

    private static void setClockOffsetDp(float v) {
        if (Float.isNaN(v)) v = 0f;
        sClockOffsetDp = v < CLOCK_OFFSET_MIN_DP ? CLOCK_OFFSET_MIN_DP
                : (v > CLOCK_OFFSET_MAX_DP ? CLOCK_OFFSET_MAX_DP : v);
        Xp.log(TAG + "clock offset = " + sClockOffsetDp + "dp");
    }

    /**
     * The size the collapsed clock is at right now, as a fraction of the style's full clock -
     * the slider's value, or what the dp default comes to on this style. NaN with no clock.
     */
    /** How many times the glyphs drawn now must grow to be the style's full clock. */
    static float clockFullRatio() {
        RectF box = glyphBox();
        if (box == null || box.height() <= 0f) return 1f;
        return ClockCollapse.fullRatio(glyphUnit(box));
    }

    static float effectiveClockSize() {
        if (!Float.isNaN(sClockSize)) return sClockSize;
        RectF box = glyphBox();
        if (box == null || box.height() <= 0f) return Float.NaN;
        float unit = glyphUnit(box);
        float full = unit * ClockCollapse.fullRatio(unit);
        if (!(full > 0f)) return Float.NaN;
        float s = sClockHeightDp * density() / full;
        return s < CLOCK_SIZE_MIN ? CLOCK_SIZE_MIN : (s > 1f ? 1f : s);
    }

    /**
     * The cover transition's spring response, in seconds, from the slider or from a stored value.
     *
     * Nothing is re-applied here on purpose. The value is read at the moment a transition starts
     * and handed to the integrator with it, so a change belongs to the next one - and a spring
     * that is already flying keeps the parameters it began with, which is what stops a drag from
     * bending the curve under the clock that is being drawn.
     *
     * The fades are the exception and do have to be pushed: they live in another process. See
     * pushFadeMs().
     */
    private static void setClockResponse(float v) {
        sClockResponse = v < CLOCK_RESPONSE_MIN ? CLOCK_RESPONSE_MIN
                : (v > CLOCK_RESPONSE_MAX ? CLOCK_RESPONSE_MAX : v);
        Xp.log(TAG + "clock response = " + sClockResponse + "s, fades "
                + fadeMsFor(sClockResponse) + "ms");
        saveState();
        CoverPush.pushFadeMs(sAppCtx);
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
    static View clockTarget(View root) {
        // Always tried first and never answered from the cache: one resource lookup and a
        // findViewById, and short-circuiting it is how a style change would go unnoticed.
        View g = findClockView(root, "time_group");
        if (usable(g)) {
            // Recorded, not answered from: the lookup above is what decides, and it is still the
            // first thing tried on every call. But sClockTargets is the only record of what this
            // resolved to, and the frame-by-frame guard reads it - leaving the fast path out of
            // it made every tree look unresolved for as long as the style had a time_group, and
            // the guard re-placed the clock four times a second for ever.
            //
            // Written only when it says something new. The same view comes back on all but the
            // first frame of a transition, and this is a WeakHashMap keyed on the view - a put
            // per root per frame, each one re-hashing the key and walking the table, for a value
            // that was already there.
            if (sClockTargets.get(root) != g) sClockTargets.put(root, g);
            return g;
        }
        View cached = sClockTargets.get(root);
        if (cached != null && cached != root && usable(cached) && cached.getParent() != null) {
            return cached;
        }
        // The root standing in for itself means "this tree draws no clock" - some styles put the
        // whole clock in one tree and only the date in the other, and without remembering that,
        // every frame of the squeeze would walk a whole layer to fail to find one.
        //
        // But only for as long as the tree looks the way it did when the answer was taken. It is
        // taken at attach, when the clock view is still 0x0, and the only thing that re-asks is
        // a change of date view - which a style whose date this module cannot find never
        // produces. The doodle is that style, and it stayed unscaled for the life of the
        // process: a fresh launch of SystemUI answered "no clock here" once, for ever.
        if (cached == root) {
            Integer was = sNoClockAt.get(root);
            if (was != null && was == clockSize(root)) return null;
        }
        View found = resolveTimeTarget(root);
        sClockTargets.put(root, found == null ? root : found);
        if (found == null) sNoClockAt.put(root, clockSize(root));
        else sNoClockAt.remove(root);
        return found;
    }

    /**
     * The size of the clock view at the top of this layer, or -1 when there is not one yet.
     *
     * A number rather than the view itself: the view object outlives its own measurement, so
     * what has to be compared is how big it was, and 0x0 is the tell for "before the first
     * layout" that the negative answer above was taken under.
     */
    private static int clockSize(View root) {
        View clock = clockViewOf(root);
        return clock == null ? -1 : clock.getWidth() * 8191 + clock.getHeight();
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
        // The OEM's own answer first, before any guess at the tree.
        //
        // Every clock style implements IClockView.getIClockView(ClockViewType), and that is how
        // the OEM's own animation code finds the pieces it moves - so it is the one lookup that
        // keeps working for a style this module has never seen. The guess below does not: a
        // rhombus clock draws its digits as vector art, a doodle clock as bitmaps and the
        // oriental ones in Chinese numerals, so "a TextView holding digits and colons" names
        // none of them, and on those styles the clock simply did not shrink.
        for (String type : new String[]{"TIME_GROUP", "TIME_AREA", "FULL_TIME"}) {
            View v = oemPart(root, type);
            if (usable(v)) return v;
        }
        java.util.List<View> parts = new java.util.ArrayList<>();
        for (String type : new String[]{"FULL_HOUR", "FULL_MINUTE", "HOUR1", "HOUR2",
                "MIN1", "MIN2"}) {
            View v = oemPart(root, type);
            if (usable(v)) parts.add(v);
        }
        if (parts.size() == 1) return parts.get(0);
        if (parts.size() > 1) {
            View top = commonAncestor(parts);
            // A parent that also holds the date must not be scaled: the date is what the
            // placement anchors to, and dragging it along makes the anchor chase itself.
            View date = visibleDate();
            if (top != null && !(date != null && isDescendant(top, date))) return top;
        }

        java.util.List<View> hits = new java.util.ArrayList<>();
        collectTimeViews(root, hits);
        // An INVISIBLE container is the normal state of the background layer in cover mode -
        // the clock is being drawn by its notification variant - and requiring the walk to
        // start on a VISIBLE view is why it never started. The children are exactly as visible
        // as they ever are; the container's own visibility says nothing about whether a clock
        // is in there. This alone is what kept magazine, classic and the oriental styles out.
        if (hits.isEmpty() && root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) collectTimeViews(g.getChildAt(i), hits);
        }
        if (hits.isEmpty()) return clockContainerOrNull(root);
        if (hits.size() == 1) return hits.get(0);
        View top = commonAncestor(hits);
        if (top == null) return hits.get(0);
        View date = visibleDate();
        return date != null && isDescendant(top, date) ? hits.get(0) : top;
    }

    /**
     * The OEM's own clock container, as a last resort, or null.
     *
     * For a style whose clock is a single view - the doodle answers ALL_VIEW and CLOCK_CONTAINER
     * with the same graffiti artwork and names nothing else - this is the only answer there is,
     * and it is the right one: on that style the date and the time are drawn into the same
     * picture, so scaling it scales the clock and not something else.
     *
     * Never when the container holds a date this module found. On a style that splits the two,
     * scaling the container would drag the date along, and the date is what the placement hangs
     * the clock from.
     */
    private static View clockContainerOrNull(View root) {
        View cc = oemPart(root, "CLOCK_CONTAINER");
        if (!usable(cc)) return null;
        View date = visibleDate();
        return date != null && isDescendant(cc, date) ? null : cc;
    }

    /**
     * The view at the top of one clock layer, which is that style's own clock: every style's
     * root view implements MiuiClockController.IClockView and is what the OEM asks about itself.
     *
     * Taken from the tree rather than from MiuiClockController.mClockView because the foreground
     * layer has a controller of its own and only one of the two is reachable from the container
     * this module hooks.
     */
    private static View clockViewOf(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        return g.getChildCount() > 0 ? g.getChildAt(0) : null;
    }

    /**
     * One of com.miui.clock.module.ClockViewType's constants by name, or null on a build that
     * has no such constant. The enum is the OEM's, and a name that exists today is not promised
     * to exist on the next HyperOS - so a miss is a miss, not a failure.
     */
    private static Object viewType(String name) {
        java.util.Map<String, Object> m = sViewTypes;
        if (m == null) {
            m = new java.util.HashMap<>();
            try {
                Class<?> cls = Xp.findClass(CLS_CLOCK_VIEW_TYPE,
                        sContainerCls == null ? null : sContainerCls.getClassLoader());
                Object[] all = cls.getEnumConstants();
                if (all != null) {
                    for (Object o : all) m.put(((Enum<?>) o).name(), o);
                }
            } catch (Throwable t) {
                Xp.log(TAG + "no ClockViewType: " + t);
            }
            sViewTypes = m;
        }
        return m.get(name);
    }

    /**
     * What the OEM itself says draws one named part of the clock in one layer, or null.
     *
     * This is the lookup that does not have to be maintained: the OEM finds everything it
     * animates this way, so a clock style added in a future build answers it too.
     */
    private static View oemPart(View root, String type) {
        View clock = clockViewOf(root);
        if (clock == null) return null;
        Object t = viewType(type);
        if (t == null) return null;
        try {
            Object got = Xp.callMethod(clock, "getIClockView", t);
            return got instanceof View ? (View) got : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The nearest common ancestor of a set of views, or null if they share none within reach. */
    private static View commonAncestor(java.util.List<View> views) {
        if (views == null || views.isEmpty()) return null;
        View top = views.get(0);
        for (int guard = 0; guard < 12 && top != null; guard++) {
            if (containsAll(top, views)) return top;
            top = top.getParent() instanceof View ? (View) top.getParent() : null;
        }
        return null;
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
    /** The clock size a tree was answered "no clock here" at. See clockTarget(). */
    private static final java.util.WeakHashMap<View, Integer> sNoClockAt =
            new java.util.WeakHashMap<>();
    /** The date view the cache above was filled against. See placeCollapsedClock(). */
    static View sDateView;
    /**
     * When the last "clock style not understood" dump was written, and how often one is allowed.
     *
     * This was a boolean, set here and cleared by the next placement that SUCCEEDED - which is
     * not the same thing as a style changing, and is what turned a diagnostic into a frame-rate
     * bug. A clock that alternates between measurable and not - which is what a keyguard does
     * while it is rebuilding, and what this module's own entry does while the layout swaps
     * part-way through - cleared the flag on one frame and dumped the whole of both clock trees
     * on the next. Each dump walks up to eight levels of the tree twice, builds the string, and
     * writes it to LSPosed over a socket; measured on the device, one transition carried a
     * `place max=20.1ms` against a `place avg=1.0ms` and a 66ms hole between frames, with the
     * dump's own line timestamped inside that transition.
     *
     * A throttle instead of a flag, so the answer is still in the log for a style that really
     * cannot be read, and a flapping one costs one dump per interval rather than one per frame.
     */
    private static long sClockComplainedAt;
    private static final long COMPLAIN_MS = 10000L;

    /**
     * Says why a clock style could not be taken over, at most once every COMPLAIN_MS.
     *
     * This runs on every frame of the squeeze, so it cannot log freely - but a style it cannot
     * read is exactly the thing that needs reporting, and asking the user to reproduce it with a
     * dump is worse than having the answer already in the log.
     */
    private static void reportUnknownClock(View date, RectF pooled) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sClockComplainedAt < COMPLAIN_MS) return;
        sClockComplainedAt = now;
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

    static String idOf(View v) {
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
        RectF box = inkBoxOnce(root, target);
        if (box == null && sTimeViews.remove(target) != null) {
            // The remembered digit views drew nothing: the OEM swapped the clock's layout variant
            // under them. Collect again, once.
            box = inkBoxOnce(root, target);
        }
        return box;
    }

    private static RectF inkBoxOnce(View root, View target) {
        RectF box = null;
        java.util.List<View> times = timeViewsOf(target);
        // Nothing inside the target reads as a clock, which is the normal case for a style
        // whose digits are not text. The OEM's own parts still name them, so measure those.
        //
        // Not on all_in_one: its target is time_group, the OEM calls the time_group itself
        // FULL_HOUR, and measuring a container as if it were its own glyph is the whole block
        // rather than the ink in it. That style's digits answer to getTextBoundsWithPosition
        // and are found above, so the fallback has nothing to add there and a great deal to
        // spoil - the foreground tree draws no ink in the notification variant, and handing it
        // a full-size box is what turned a correct 578x448 ink box into 1200x1159.
        if (times.isEmpty() && findClockView(root, "time_group") != target) {
            // The individual digits if the OEM names them - FULL_HOUR and FULL_MINUTE are the
            // hour and minute CONTAINERS, which on a style that draws both rows around a gap
            // is most of the clock's height - and only then those two.
            for (String part : new String[]{"HOUR1", "HOUR2", "MIN1", "MIN2"}) {
                View v = oemPart(root, part);
                if (v != target && usable(v) && isDescendant(target, v)) times.add(v);
            }
            if (times.isEmpty()) {
                for (String part : new String[]{"FULL_HOUR", "FULL_MINUTE"}) {
                    View v = oemPart(root, part);
                    if (v != null && v != target && usable(v) && isDescendant(target, v)) {
                        times.add(v);
                    }
                }
            }
            // The OEM's own index is not the only way a style names its digits, and on the
            // ones that draw them as art it answers with a CONTAINER: rhombus's FULL_HOUR is
            // `hour_container`, 1200x1408 wrapped around 489px of digits. Measuring that is the
            // "whole block as the glyph" mistake from the other side - the collapse divides by
            // 1322 instead of 489, so the clock comes out at 43px on a screen that asked for
            // 167. These views do give every digit an id of its own; measured on the device:
            //
            //   rhombus      hour1/2, minute1/2 (MiuiClockNumberView, 489px)
            //   magazine_c   current_time_hour_style1 / _minute_style1 (MiuiTextGlassView, 473px)
            //   doodle       time_hour, time_minute (ImageView, 514px)
            //   depth_pets   time_hour, time_hour_right, time_minute, time_minute_right (400px)
            //   eastern_b    time_below_view_hour1/2, _minute1/2 (TextView, 224px)
            //
            // Read through the same measurement loop below, which already knows a TextView
            // reports its own ink and an ImageView's bounds ARE its ink.
            for (String id : DIGIT_IDS) {
                View v = findByEntryName(target, id);
                if (v != null && v != target && usable(v)) times.add(v);
            }
            // A style whose whole clock really is one view - the doodle, when nothing above
            // answered - has nothing left to measure but itself, and its own bounds are then
            // exactly the clock. Safe here and nowhere else: the guard above keeps this off
            // all_in_one, whose time_group is a container that would report the whole block.
            if (times.isEmpty() && usable(target)) return ownBox(target);
        }
        // Still nothing measurable. Left null rather than falling back to the target's own
        // bounds: a tree that has a clock but no ink in it yet is a clock that has not been
        // through a layout pass, and the box that would come out of it is the block, not the
        // glyphs - the same half-a-box mistake clockPivotX() already documents.
        if (times.isEmpty()) return null;
        boolean trace = sGeomTraceUntil != 0L;
        StringBuilder who = trace ? new StringBuilder("inkBox target=" + viewIdOf(target)
                + " collected=" + times.size() + " :") : null;
        for (View v : times) {
            RectF r = null;
            try {
                r = (RectF) Xp.callMethod(v, "getTextBoundsWithPosition");
            } catch (Throwable ignored) {
            }
            if (r == null || r.height() <= 0f) {
                r = v instanceof TextView ? textInkBox((TextView) v) : null;
                // Digits drawn as vector art or as a bitmap have no text to measure, and a view
                // whose whole content is the glyph has no padding to undo - its own bounds are
                // the ink. That is a rhombus or a doodle digit.
                //
                // NOT for one of the OEM's own TimeViews. A TimeView that cannot report its
                // bounds has simply not been drawn yet, and its bounds are the entire clock
                // container - measuring those is the "block instead of glyphs" mistake that
                // turned all_in_one's 578x448 ink box into 1200x1159, and with it the pivot the
                // whole collapse hangs from.
                if (r == null && !reportsGlyphs(v)) r = ownBox(v);
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
            // The tallest single view, not the union: this is the one number the collapse should
            // divide by. See sInkUnit.
            if (r != null && r.height() > 0f
                    && (Float.isNaN(sInkUnit) || r.height() > sInkUnit)) sInkUnit = r.height();
            if (trace) who.append(' ').append(viewIdOf(v)).append('=').append(r1(r.height()));
        }
        if (trace) {
            who.append(" -> ").append(box == null ? "null" : boxOf(box))
               .append(" unit=").append(r1(sInkUnit));
            Xp.log(TAG + who);
        }
        return box;
    }

    /**
     * The views that draw the time inside a clock target, remembered for a moment.
     *
     * Collecting them walks the subtree and asks every view's class for a method by reflection -
     * a thrown NoSuchMethodException per view that is not a digit. Fine for a probe, not for the
     * cover clock's pre-draw, which measures on every frame. The list is only a list of views to
     * ask for their bounds; the bounds themselves are always read live. Re-collected when any of
     * them stops being usable, and on every keyguard rebuild or date-view change forgetGlyphBox()
     * hears about. Not on a timer: re-collecting walks the tree with reflection, and a timer
     * firing mid-transition was a 4-8ms spike in the one frame it landed on.
     */
    private static final java.util.HashMap<View, java.util.List<View>> sTimeViews =
            new java.util.HashMap<>();

    private static java.util.List<View> timeViewsOf(View target) {
        java.util.List<View> hit = sTimeViews.get(target);
        if (hit != null) {
            boolean ok = true;
            for (View v : hit) {
                if (!usable(v)) { ok = false; break; }
            }
            if (ok) return hit;
        }
        java.util.List<View> times = new java.util.ArrayList<>();
        collectTimeViews(target, times);
        // Only a non-empty answer is remembered: the fallbacks after it depend on the OEM's own
        // index and on ids, and an empty one is the case where the layout has not arrived yet.
        if (!times.isEmpty()) sTimeViews.put(target, times);
        return new java.util.ArrayList<>(times);
    }

    /**
     * The same lookup by NAME, walked over the subtree and matched against each view's own
     * resource entry name.
     *
     * `findClockView()` resolves a name through one package's resource table
     * (`com.android.systemui`) and that is where this was first written. Measured on the device
     * with the `op ink` probe: `time_hour` answers from there, but `hour1` (rhombus) and
     * `current_time_hour_style1` (magazine_c) are ids of the clock library's own and resolve to
     * 0, so the pass did nothing at all on exactly the two styles it was written for - and did
     * it silently, which is how a fix can land and change nothing. Reading the name each view
     * already carries does not care which table the id came from.
     */
    private static View findByEntryName(View root, String name) {
        if (root == null) return null;
        try {
            if (root.getId() != View.NO_ID
                    && name.equals(root.getResources().getResourceEntryName(root.getId()))) {
                return root;
            }
        } catch (Throwable ignored) {
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findByEntryName(g.getChildAt(i), name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * The ids styles give the views that draw a time. Not a substitute for the OEM's own index -
     * that is asked first, and on every style whose digits are text it answers correctly - but
     * the only thing that answers at all on the styles that draw them as vector art or bitmaps,
     * where the OEM's index stops at the container.
     *
     * Every one of these was read off the device with the `op ink` probe rather than guessed;
     * a name that is not here costs nothing, and a wrong one would put a container back into
     * the measurement, so the list stays to what was actually seen.
     */
    private static final String[] DIGIT_IDS = {
            "time_hour", "time_minute", "time_hour_right", "time_minute_right",
            "hour1", "hour2", "minute1", "minute2",
            "current_time_hour_style1", "current_time_minute_style1",
            "time_below_view_hour1", "time_below_view_hour2",
            "time_below_view_minute1", "time_below_view_minute2",
            "tv_time", "tv_minute", "tv_hour",
    };

    /** Whether this view can report the bounds of what it draws - one of the OEM's TimeViews. */
    private static boolean reportsGlyphs(View v) {
        if (v == null) return false;
        try {
            v.getClass().getMethod("getTextBoundsWithPosition");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The bounds of a view whose whole content is the glyph it draws. */
    private static RectF ownBox(View v) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        return new RectF(0f, 0f, v.getWidth(), v.getHeight());
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
    /**
     * How long a measured glyph box is trusted for.
     *
     * For the preview and the probes, which ask several times in a row. The cover clock asks for a
     * fresh one every frame instead: the OEM's squeeze and its wake growth both change the box
     * frame by frame, and mapping a stale box is exactly the one-frame-late error.
     */
    private static RectF sGlyphCache;
    private static long sGlyphAt;
    private static final long GLYPH_TTL_MS = 120L;

    /** Drops the remembered box. Called from every place the layout is known to have changed. */
    private static void forgetGlyphBox() {
        sGlyphCache = null;
        sGlyphAt = 0L;
        sTimeViews.clear();
    }

    /**
     * How tall ONE ROW of the clock's digits is, as opposed to how tall the ink block is.
     *
     * The two are the same number on every style that draws its time on a single line - which is
     * most of them, and why this went unnoticed - and they are nothing like each other on the
     * styles that stack the hour over the minute. Measured on the device: rhombus collects four
     * MiuiClockNumberViews of 489px each and their UNION is 1322, because the hour row and the
     * minute row sit 833px apart. The setting says "the height of the digits when collapsed",
     * so dividing by 1322 asks for digits of 62px while every other style gets 167 - the clock
     * comes out a third of the size it should be, and looks broken next to the others.
     *
     * Published by inkBox() as it measures, and taken as the largest across the trees for the
     * same reason the box is unioned: all_in_one draws its hour in one tree and its minute in
     * the other, and the row is as tall as its tallest digit.
     */
    private static volatile float sInkUnit = Float.NaN;

    static float glyphUnit(RectF box) {
        float u = sInkUnit;
        return (Float.isNaN(u) || u <= 0f) ? box.height() : u;
    }

    /** Read-only probe switch: measure through the hold and the TTL cache, change nothing. */
    private static volatile boolean sProbeLive;

    static RectF glyphBox() {
        return glyphBox(false);
    }

    /**
     * @param fresh measure this frame, past the trust window - what the cover clock's pre-draw
     *              wants, since it maps whatever is about to be drawn
     */
    static RectF glyphBox(boolean fresh) {
        long now = android.os.SystemClock.uptimeMillis();
        RectF cached = sGlyphCache;
        if (!fresh && !sProbeLive && cached != null && now - sGlyphAt < GLYPH_TTL_MS) {
            return new RectF(cached);
        }
        RectF box = null;
        sInkUnit = Float.NaN;
        boolean treeDrewNothing = false;
        for (View root : clockRoots()) {
            View target = clockTarget(root);
            RectF r = inkBox(root, target);
            if (r == null) {
                if (usable(target)) treeDrewNothing = true;
                continue;
            }
            if (box == null) box = r; else box.union(r);
        }
        if (box == null) return null;
        if (!treeDrewNothing) { sGlyphCache = box; sGlyphAt = now; return new RectF(box); }
        // (the unit below is published either way - see glyphUnit())
        // A tree that HAS a clock in it but shows no ink is either a clock that has not been
        // through a layout pass, or a tree that is simply not drawing - and those two want
        // opposite answers. Half a box is what makes clockPivotX() take the left-aligned
        // branch: all_in_one draws the hour in one tree and the minute in the other, so the
        // half that happens to be measured first has a centre nowhere near the screen's and
        // the clock collapses towards its own left edge. That is the crooked small clock a
        // SystemUI restart used to leave behind.
        //
        // But answering "no box at all" whenever a tree is empty took a good measurement down
        // with it, and in cover mode that is the normal state: all_in_one draws the WHOLE clock
        // in the background tree (hour_view and minute_view both visible, the foreground copy
        // left at 0x0), while the foreground `#time_group` still answers the id lookup and is
        // still VISIBLE. Voiding on it meant placeCollapsedClock() never ran a single frame,
        // and the size slider did nothing at all - on the style that was supposed to be the
        // working one.
        //
        // So what is left standing is judged instead of thrown away: the union of everything
        // that DID measure is a whole clock exactly when it sits where the screen's centre is,
        // which is the same test clockPivotX() is about to make. Off-centre, it is half of one
        // and the other half is still coming.
        View any = null;
        for (View root : clockRoots()) {
            if (clockTarget(root) != null) { any = root; break; }
        }
        if (any == null) { sGlyphCache = box; sGlyphAt = now; return new RectF(box); }
        float screenW = any.getResources().getDisplayMetrics().widthPixels;
        if (Math.abs(box.centerX() - screenW / 2f) >= screenW * 0.05f) return null;
        sGlyphCache = box;
        sGlyphAt = now;
        return new RectF(box);
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
        // The date is not required: it is what the anchored placement hangs the clock from, and
        // a style with no date view is scaled where it stands. Its height still enters the y the
        // preview puts the clock at, and with no date there is none to add.
        View date = visibleDate();
        RectF box = paddedGlyphBox();
        if (box == null) return null;
        View g = null;
        for (View root : clockRoots()) {
            View t = clockTarget(root);
            if (t != null) { g = t; break; }
        }
        if (g == null) return null;
        float d = g.getResources().getDisplayMetrics().density;
        return new float[]{box.width(), box.height(),
                (date == null ? 0f : ClockCollapse.coverDateY(date) + date.getHeight()) + CLOCK_GAP_DP * d,
                g.getLeft() + box.left, g.getLeft() + clockPivotX(g, glyphBox())};
    }

    private static RectF paddedGlyphBox() {
        RectF box = glyphBox();
        if (box == null) return null;
        box.inset(-CLOCK_PAD, -CLOCK_PAD);
        return box;
    }

    private static View findClockView(View root, String id) {
        int i = resId(root.getResources(), id);
        return i == 0 ? null : root.findViewById(i);
    }

    /**
     * getIdentifier() walks the package's resource table by name on every call, and the collapse
     * asks for the same handful of ids several times a frame - both clock roots, the clock target
     * inside each, the date, `time_group` for the anchored test. That is measurable against the
     * OEM doing its own animation at the same time and coming out smooth: driving y makes the
     * OEM recompute the variable font every frame, and this used to be added on top of it.
     *
     * The answer cannot change while the process lives - a build either has the id or it does
     * not - so it is remembered, including the 0 a build without it answers.
     */
    private static final java.util.HashMap<String, Integer> sResIds = new java.util.HashMap<>();

    private static int resId(android.content.res.Resources r, String name) {
        Integer hit = sResIds.get(name);
        if (hit != null) return hit;
        int id = r.getIdentifier(name, "id", "com.android.systemui");
        sResIds.put(name, id);
        return id;
    }

    /** The ids the OEM gives a date line, the plain one first. */
    private static final String[] DATE_IDS = {
            "text_area", "current_date", "notification_date", "tv_data", "date_and_time",
    };

    /** What the OEM itself calls the date, for the styles whose id is not in the list above. */
    private static final String[] DATE_PARTS = {
            "TEXT_AREA", "NOTIFICATION_DATE", "FULL_DATE", "FULL_DATE_WEEK",
    };

    private static boolean usableDate(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.getHeight() > 0
                && v.isAttachedToWindow();
    }

    /**
     * The date line, on whatever style is loaded.
     *
     * This used to look for `text_area` and nothing else, which is all_in_one's id and
     * classic's. Every other style keeps its date under an id of its own - the rhombus style
     * has `current_date` and `notification_date`, the magazine ones `current_date`, the
     * oriental ones `tv_data` and `date_and_time` - so the lookup returned null, and
     * placeCollapsedClock() refuses to place anything at all without a date to anchor to.
     * That is why the collapse did nothing on those styles even before the driver question.
     *
     * Sticky, because placeCollapsedClock() compares this against the view it placed against
     * last and clears its caches when it changes: a date that alternated between two
     * candidates on consecutive frames would re-adopt the offset every frame.
     */
    static View visibleDate() {
        View last = sDateView;
        if (usableDate(last)) return last;
        for (String id : DATE_IDS) {
            for (View root : clockRoots()) {
                View v = findClockView(root, id);
                if (usableDate(v)) return v;
            }
        }
        for (String part : DATE_PARTS) {
            for (View root : clockRoots()) {
                View v = oemPart(root, part);
                if (usableDate(v)) return v;
            }
        }
        return null;
    }

    /**
     * What the OEM gives the signature bar under the clock - the container, then its editor.
     *
     * The container is the one the OEM itself moves (see AllInOneBase), so it is tried first; the
     * editor is only there for a build that renames the container and keeps the editor's id.
     */
    private static final String[] SIG_IDS = {
            "signature_text_container", "signature_text",
    };

    /**
     * The signature bar under the clock, at most one per clock tree.
     *
     * Cover mode moves the clock out from under it, and nothing in the OEM re-derives the bar's
     * position from anything we have touched, so it is left behind. Carrying it is the same
     * problem as the date's, and it is solved the same way: measure where the OEM put it, and
     * translate it back to that distance from wherever the clock now is. Eleven layouts in this
     * build carry the ids - all_in_one's three, classic's and its signature variants - and every
     * other style has neither, so it is left alone.
     *
     * Sticky, for the reason the date is: a bar that alternated between candidates on consecutive
     * frames would be re-measured every frame.
     */
    private static View[] sSigViews = new View[0];

    static View[] signatureViews() {
        View[] last = sSigViews;
        if (last.length > 0) {
            boolean ok = true;
            for (View v : last) if (!usableDate(v)) ok = false;
            if (ok) return last;
        }
        View[] roots = clockRoots();
        java.util.ArrayList<View> found = new java.util.ArrayList<>(roots.length);
        for (View root : roots) {
            View v = null;
            for (String id : SIG_IDS) {
                View c = findClockView(root, id);
                if (usableDate(c)) {
                    v = c;
                    break;
                }
            }
            if (v != null && !found.contains(v)) found.add(v);
        }
        sSigViews = found.toArray(new View[0]);
        return sSigViews;
    }

    /**
     * Whether the signature bar has anything in it.
     *
     * The container exists on every style carrying the ids whether or not a signature was ever
     * set, and an empty one is invisible - so carrying it would drag an empty box around and,
     * worse, would push the lock lyrics down to make room for nothing. The bar's own text is the
     * answer, and it is read off the already-resolved views every frame rather than folded into
     * the lookup above: the user edits it from the OEM's clock editor, so it can go from empty to
     * set while the lock screen is up, and the lookup is sticky by design.
     */
    static boolean signatureShows(View v) {
        if (v instanceof TextView) return ((TextView) v).length() > 0;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && signatureShows(c)) return true;
        }
        return false;
    }

    /**
     * Whether the clock on screen is the one the anchored placement was measured on.
     *
     * `time_group` is all_in_one's own id - the style the date target, the gap and the
     * one-continuous-motion entry were all measured against. Anything else lays its own date
     * out, and is left where its author put it.
     */
    static boolean anchoredStyle() {
        for (View root : clockRoots()) {
            if (usable(findClockView(root, "time_group"))) return true;
        }
        return false;
    }

    /** Air between the date and the collapsed clock. */
    static final float CLOCK_GAP_DP = 10f;








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



    static String r1(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 10f) / 10f);
    }

    static String r2(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 100f) / 100f);
    }

    static String r3(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 1000f) / 1000f);
    }

    /**
     * Everything needed to explain where this phone put the clock, as one block of text.
     *
     * Written to be pasted into a bug report by someone who cannot run adb, so it says what it
     * measured AND what it intended: a report where `target` and `dateOnScreen` agree is a
     * report about something other than the placement.
     */
    /**
     * PROBE: what ClockViewType resolves to on the style that is loaded right now.
     *
     * Every clock style answers the OEM's own "where is the time" question - each clock view
     * implements MiuiClockController.IClockView.getIClockView(ClockViewType), and the OEM's
     * animation code uses nothing else to find the pieces it moves. Guessing at the view tree by
     * class and text works for some styles and not at all for others (a rhombus clock draws its
     * digits as vector art, a doodle clock as bitmaps, an oriental one in Chinese numerals), so
     * the guess has to go - but only once it is known what the OEM's own answer actually is per
     * style. That is what this prints.
     */
    /**
     * The OEM's own account of how far the clock has squeezed, and of where its floor is.
     *
     * `KeyguardClockNotifInteractor` decides what the clock looks like for a given notifY by
     * picking one of a dozen named scenes - "Space Squeeze Result", "Scene 2.0 (Time not
     * squeezable)", "Scene 2.1.1.1 (Squeeze height only)", "Scene 2.1.1.2 (Squeeze to min &
     * move up)" - each of which is a formula over the fields below rather than a number. The y
     * the collapse has been holding at - 740 on this device - is not a constant anywhere in the
     * OEM: it is the y at which that choice lands on "not squeezable" any more, which is to say
     * the y at which `timeHeight` has reached `timeMinHeight`.
     *
     * If that holds, then none of it has to be learned: the module is already handed the same
     * ClockResult once per frame, and "how far into the collapse are we" is a question about
     * those two heights - a fraction of the OEM's own squeeze - rather than about pixels of y.
     * Which is what would make the collapse mean the same thing on every phone.
     */
    private static void dumpNotifState() {
        View v = sContainer;
        if (v == null) { Xp.log(TAG + "notif: no clock container"); return; }
        try {
            Object it = Xp.getObjectField(v, "keyguardClockNotifInteractor");
            if (it == null) {
                Xp.log(TAG + "notif: no keyguardClockNotifInteractor on " + v.getClass().getName());
                return;
            }
            StringBuilder sb = new StringBuilder("notif ");
            sb.append(it.getClass().getSimpleName()).append(": ");
            for (String f : new String[]{"timeMinHeight", "baseClockMinHeight", "baseClockMaxHeight",
                    "adaptTimeHeight", "adaptTimeWidth", "adaptWidthHeightRatio",
                    "timeSqueezeRatio", "maxSpace", "dateHeightWithMargin", "notifPre",
                    "clockNotificationMargin", "isParamsValid", "rawNotifY"}) {
                try {
                    sb.append(f).append('=').append(Xp.getObjectField(it, f)).append(' ');
                } catch (Throwable t) {
                    sb.append(f).append("=n/a ");
                }
            }
            try {
                sb.append("| clockResult=").append(Xp.getObjectField(it, "clockResult"));
            } catch (Throwable t) {
                sb.append("| clockResult=n/a");
            }
            Xp.log(TAG + sb.toString());
            Xp.log(TAG + "notif: container getClockBottom=" + Xp.callMethod(v, "getClockBottom")
                    + " getNotificationClockTop=" + Xp.callMethod(v, "getNotificationClockTop")
                    + " hold=" + sHoldY + " lastSystem=" + r1(sLastSystemY));
        } catch (Throwable t) {
            Xp.log(TAG + "notif probe failed: " + t);
        }
    }

    /** Every `ClockViewType` this style answers, and the view it resolves to, per clock tree. */
    private static void dumpClockViewTypes() {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "viewtypes: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                Class<?> cvt;
                Object[] all;
                try {
                    cvt = Xp.findClass(CLS_CLOCK_VIEW_TYPE, sContainerCls.getClassLoader());
                    all = cvt.getEnumConstants();
                } catch (Throwable t) {
                    Xp.log(TAG + "viewtypes: no ClockViewType (" + CLS_CLOCK_VIEW_TYPE + "): " + t);
                    return;
                }
                if (all == null) { Xp.log(TAG + "viewtypes: no constants"); return; }
                for (View root : clockRoots()) {
                    View clock = root instanceof ViewGroup ? ((ViewGroup) root).getChildAt(0) : null;
                    Xp.log(TAG + "viewtypes tree " + viewIdOf(root) + ": clock="
                            + (clock == null ? "none" : clock.getClass().getName()));
                    if (clock == null) continue;
                    for (Object ct : all) {
                        Object got;
                        try {
                            got = Xp.callMethod(clock, "getIClockView", ct);
                        } catch (Throwable t) {
                            Xp.log(TAG + "  " + ct + " -> threw " + t);
                            continue;
                        }
                        if (!(got instanceof View)) {
                            Xp.log(TAG + "  " + ct + " -> " + got);
                            continue;
                        }
                        View g = (View) got;
                        int[] loc = new int[2];
                        g.getLocationOnScreen(loc);
                        Xp.log(TAG + "  " + ct + " -> " + g.getClass().getSimpleName()
                                + " #" + viewIdOf(g) + " " + g.getWidth() + "x" + g.getHeight()
                                + " vis=" + g.getVisibility() + " at=" + loc[0] + "," + loc[1]);
                    }
                }
            }
        });
    }

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
          .append("\nvideo cover: ").append(CoverPush.describeVideoCover())
          .append("\nhold: y=").append(sHoldY)
          .append(" lastSystem=").append(r1(sLastSystemY))
          .append("\nclock: ").append(ClockCollapse.describe()).append('\n')
          .append(" clockH=").append(r1(sClockHeightDp)).append("dp")
          .append(" size=").append(Float.isNaN(sClockSize) ? "dp" : r3(sClockSize))
          .append(" offset=").append(r1(sClockOffsetDp)).append("dp")
          .append(" response=").append(r2(sClockResponse)).append("s")
          .append(" fades=").append(fadeMsFor(sClockResponse)).append("ms")
          // Whether the app's glass slider is live at all: the morph exists only on the styles
          // whose clock view has updateGlassValue, and this is what tells the app which those
          // are. The first thing to look at when the slider appears to do nothing.
          .append(" anchored=").append(anchoredStyle())
          .append(" glassStyle=").append(clockHasGlass())
          .append("\ndate: rest=").append(r1(ClockCollapse.coverDateY(visibleDate())))
          .append("px from the top; cover mode leaves the date there and hangs the clock under"
                  + " it")
          .append("\ngap=").append(CLOCK_GAP_DP).append("dp\n");

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
        View[] sigs = signatureViews();
        if (sigs.length == 0) {
            sb.append("signature: none on this style\n");
        } else {
            for (View s : sigs) {
                int[] sl = new int[2];
                s.getLocationOnScreen(sl);
                sb.append("signature: #").append(idOf(s)).append(' ')
                  .append(s.getClass().getSimpleName())
                  .append(" onScreen=").append(sl[0]).append(',').append(sl[1])
                  .append(" top=").append(s.getTop()).append(" h=").append(s.getHeight())
                  .append(" ty=").append(r1(s.getTranslationY()))
                  .append(" text=").append(signatureShows(s))
                  .append(" under=").append(r1(ClockCollapse.contentBottomOnScreen()))
                  .append("\n");
            }
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
            sb.append("  oem: ").append(oemPartsOf(root)).append('\n');
            describeClockTree(root, sb, 1);
            sb.append('\n');
        }

        return sb.toString();
    }

    /** What the OEM's own ClockViewType map says this layer holds. */
    private static String oemPartsOf(View root) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{"TIME_GROUP", "TIME_AREA", "FULL_TIME", "FULL_HOUR",
                "FULL_MINUTE", "HOUR1", "HOUR2", "MIN1", "MIN2", "CLOCK_CONTAINER"}) {
            View v = oemPart(root, part);
            if (!usable(v)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(part).append("=#").append(idOf(v)).append(' ')
              .append(v.getWidth()).append('x').append(v.getHeight());
        }
        return sb.length() == 0 ? "none" : sb.toString();
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
    static float clockPivotX(View g, RectF pooled) {
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

    static Bitmap albumArt(Context ctx) {
        return albumArt(ctx, true);
    }

    static Bitmap albumArt(Context ctx, boolean allowCard) {
        return albumArt(ctx, allowCard, null);
    }

    /**
     * allowCard gates the media card thumbnail. It is the only source when the player publishes
     * nothing but an artwork URI, but it lags a track change by a moment - long enough to hand
     * back the PREVIOUS album - so callers that can afford to wait ask for the session only.
     *
     * sessionBits, when passed, is filled in with what the session turned out to be: 1 it
     * carried a bitmap, 0 it carried none, -1 there was no session to ask. The caller needs the
     * difference between the last two, because one is a player still filling its bitmap in and
     * the other is a player that never publishes one - and this is the only place that sees it.
     */
    static Bitmap albumArt(Context ctx, boolean allowCard, int[] sessionBits) {
        if (sessionBits != null) sessionBits[0] = -1;
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
                    if (sessionBits != null) sessionBits[0] = 1;
                    Xp.log(TAG + "album art from " + c.getPackageName()
                            + " " + b.getWidth() + "x" + b.getHeight() + " \""
                            + md.getString(MediaMetadata.METADATA_KEY_TITLE) + "\"");
                    return b;
                }
                if (sessionBits != null) sessionBits[0] = 0;
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
    static Bitmap cardThumbnail() {
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

    /** Prefer the pixels visible in the card; some OEM drawables expose no BitmapDrawable. */
    static Bitmap coverMorphSource() {
        // The square's own art first: prepared off the main thread already, and the very pixels
        // the morph lands on. The session fallback below is a binder call carrying the bitmap,
        // made on the main thread at the moment the animation has to start.
        // Only while the cover is up: outside it, a track may have changed since the square last
        // had art, and it would fly the previous album.
        Bitmap own = sCoverMode ? CoverCardLayer.currentArt() : null;
        // A copy, not the square's own: a tap back in mid-flight pushes the art again, and the
        // square recycles its previous bitmap 180ms into that crossfade - the one the copy was
        // flying, which then drew nothing until the morph had landed (~450ms of no cover).
        // 512x512, about a millisecond to copy.
        if (own != null) {
            try {
                Bitmap mine = own.copy(own.getConfig(), false);
                if (mine != null) return mine;
            } catch (Throwable ignored) {
            }
        }
        Bitmap thumb = cardThumbnail();
        return thumb != null ? thumb : (sAppCtx == null ? null : albumArt(sAppCtx, false));
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
            byte[] thumb = CoverPush.artThumbnail(c, 256);
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
                .append(" hideArt=").append(sMcHideArt);
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
            out.putFloat("clockfull", clockFullRatio());
            // For a size the slider has never set: the dp default, in this style's terms.
            out.putFloat("clocksize", effectiveClockSize());
        }
        out.putBoolean("clockglass", clockHasGlass());
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
     * The y is COMPUTED, not read. Cover mode leaves the date at its rest position (the clock
     * hangs under it), and the app asks for this with the phone unlocked, where
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
        out.putByteArray("date", png);
        out.putIntArray("daterect", new int[]{loc[0], Math.round(ClockCollapse.coverDateY(date)),
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

    /** A hand-back of the live wallpaper's surfaces that is waiting for a lock screen. */
    static volatile boolean sVideoWpOwed;

    /** PROBE `uncover`: our video cover view hidden, so a screenshot shows what is under it. */
    static volatile boolean sUncoverProbe;

    /** A view's current picture as an average colour, for the vcprobe op. */
    private static String avgColour(View v) {
        if (v == null) return "absent";
        try {
            Bitmap b;
            if (v instanceof android.view.TextureView) {
                b = ((android.view.TextureView) v).getBitmap(60, 130);
            } else {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) return "unlaid";
                b = Bitmap.createBitmap(60, 130, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(b);
                cv.scale(60f / v.getWidth(), 130f / v.getHeight());
                v.draw(cv);
            }
            if (b == null) return "no bitmap";
            long r = 0, g = 0, bl = 0;
            int n = b.getWidth() * b.getHeight();
            int[] px = new int[n];
            b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
            for (int p : px) {
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                bl += p & 255;
            }
            b.recycle();
            return String.format(java.util.Locale.ROOT, "#%02x%02x%02x(%s)",
                    r / n, g / n, bl / n, v.getVisibility() == View.VISIBLE ? "shown" : "hidden");
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }

    /** The AOD wallpaper-dim value the system last set, and the root it was set on. */
    private static volatile float sLastWallpaperBlack = -1f;
    private static volatile Object sBlackRoot;

    /**
     * How long the blur-sync pump keeps the keyguard redrawing after a reload signal.
     *
     * Long enough to cover the slowest measured swap: the wallpaper process rebuilds its player
     * and the first frame lands somewhere between 60ms (a cover video off the encode cache) and
     * ~700ms (restoring the user's own 4K wallpaper video, re-seeked) after the reload. Past
     * that, a swap that arrives late simply does not get re-sampled by the cards' blur.
     */
    private static final long BLUR_SYNC_MS = 1400L;

    private static volatile long sBlurSyncUntil;
    private static volatile boolean sBlurSyncPumping;

    /**
     * Keeps the keyguard window redrawing, one frame at a time, until the wallpaper window's
     * swap has certainly landed.
     *
     * The cards' frosted backdrop is not a live view of the wallpaper: it re-samples what is
     * behind the cards only when this window draws a frame. Every cover transition ends with
     * this window going quiet BEFORE the wallpaper side has finished - the cover's view has
     * faded out and detached, or the fade-in is over, and the player's first frame is still
     * being decoded - so the last backdrop sample, taken while the view was still up, is what
     * stays on the cards while the wallpaper window underneath swaps. The eye sees the new
     * wallpaper; the cards keep the old blur. That is the whole of the "half a beat late"
     * report, and no View animation on our side can fix it, because the missing ingredient is
     * not motion - it is a frame for the blur to re-sample in.
     *
     * So the pump draws those frames on purpose. One invalidate a vsync, from the signal that
     * says the player was rebuilt until well past the slowest first frame. The cost is a
     * redraw a frame for about a second, which is the same cost the still path's crossfade
     * already pays; the alternative is the cards disagreeing with the wallpaper they sit on.
     */
    private static final android.view.Choreographer.FrameCallback sBlurSyncFrame =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    sBlurSyncPumping = false;
                    if (android.os.SystemClock.uptimeMillis() >= sBlurSyncUntil) return;
                    View root = sContainer == null ? null : sContainer.getRootView();
                    if (root != null) root.invalidate();
                    sBlurSyncPumping = true;
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            };

    private static void startBlurSync(String why) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean fresh = now >= sBlurSyncUntil;
        sBlurSyncUntil = now + BLUR_SYNC_MS;
        if (sBlurSyncPumping) return;
        sBlurSyncPumping = true;
        android.view.Choreographer.getInstance().postFrameCallback(sBlurSyncFrame);
        // One line per burst, not per frame: the pump runs for over a thousand frames.
        Xp.log(TAG + "blur sync pumping " + BLUR_SYNC_MS + "ms (" + why + ")"
                + (fresh ? "" : " (extended)"));
    }

    /**
     * Re-applies the AOD wallpaper dim after a cover rebuild. The rebuild recreates the
     * wallpaper-side render pipeline, and the dim the system had animated onto the wallpaper
     * window - through setWallpaperBlack and the WallPaperAnimProcess transaction - is state
     * that pipeline does not carry across. Left alone, the cover sits in AOD at full
     * brightness where the wallpaper would have been dimmed. Three re-asserts spread over
     * 600ms: the new GL program can land after the first of them.
     */
    private static void reassertAodDim() {
        final float black = sLastWallpaperBlack;
        final Object root = sBlackRoot;
        if (black < 0f) return;
        for (final long at : new long[]{50L, 250L, 600L}) {
            main().postDelayed(() -> {
                if (!sCoverMode) return;
                try {
                    if (root != null) {
                        Xp.callMethod(root, "setWallpaperBlack", black);
                    }
                    android.view.SurfaceControl.Transaction t =
                            new android.view.SurfaceControl.Transaction();
                    try {
                        Xp.callMethod(t, "enableWallPaperAnim", true);
                        Xp.callMethod(t, "setWallPaperAnimProcess", black);
                    } catch (Throwable ignored) {
                    }
                    t.apply();
                    Xp.log(TAG + "aod dim re-asserted " + black + " (+" + at + "ms)");
                } catch (Throwable t2) {
                    Xp.log(TAG + "aod dim re-assert failed: " + t2);
                }
            }, at);
        }
    }


    private static int sVtreeLines;

    /**
     * PROBE: the keyguard window's whole view tree, one line per view - class, id, visibility,
     * alpha, size - with TextureViews and SurfaceViews marked. This is the ground truth behind
     * "the cover is up but the screen does not show it": the state dump reads our view's own
     * flags, and they can all say VISIBLE while the layer the view sits in is not what the eye
     * is looking at.
     */
    private static void dumpViewTree() {
        try {
            View v = sContainer;
            if (v == null) {
                Xp.log(TAG + "vtree: no clock container");
                return;
            }
            sVtreeLines = 0;
            StringBuilder sb = new StringBuilder();
            walkTree(v.getRootView(), 0, sb);
            String s = sb.toString();
            int start = 0;
            while (start < s.length()) {
                int nl = s.indexOf('\n', start);
                if (nl < 0) nl = s.length();
                Xp.log(TAG + "vtree " + s.substring(start, nl));
                start = nl + 1;
            }
            Xp.log(TAG + "vtree done, " + sVtreeLines + " views");
        } catch (Throwable t) {
            Xp.log(TAG + "vtree failed: " + t);
        }
    }

    private static void walkTree(View v, int depth, StringBuilder sb) {
        if (sVtreeLines > 400) return;
        sVtreeLines++;
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cls = v.getClass().getSimpleName();
        if (cls.isEmpty()) cls = v.getClass().getName();
        sb.append(cls).append('#').append(resourceName(v))
          .append(" vis=").append(v.getVisibility())
          .append(" a=").append(r2(v.getAlpha()))
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" tl=").append((int) v.getX()).append(',').append((int) v.getY());
        if (v instanceof android.view.TextureView) sb.append(" <<TEXTUREVIEW>>");
        if (v instanceof android.view.SurfaceView) sb.append(" <<SURFACEVIEW>>");
        if (v == sCover) sb.append(" <<COVER>>");
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkTree(g.getChildAt(i), depth + 1, sb);
        }
    }

    private static String resourceName(View v) {
        try {
            int id = v.getId();
            if (id <= 0 || id == View.NO_ID) return "-";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * FLAG_RECEIVER_FOREGROUND is the whole reason a track change feels immediate: without it
     * this broadcast sits in the background queue and took a measured ~500ms to reach the
     * wallpaper process, which is more than composing and uploading the picture put together.
     */
    /** The main looper's queue, counted by callback or target and what, largest first. */
    private static String looperCensus() {
        final java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        final int[] total = {0};
        Looper.getMainLooper().dump(new android.util.Printer() {
            @Override
            public void println(String x) {
                int m = x.indexOf("Message ");
                if (m < 0 || x.indexOf('{', m) < 0) return;
                total[0]++;
                String key;
                int cb = x.indexOf("callback=");
                if (cb >= 0) {
                    int end = x.indexOf(' ', cb);
                    key = "cb " + x.substring(cb + 9, end < 0 ? x.length() : end);
                } else {
                    int tg = x.indexOf("target=");
                    int wt = x.indexOf("what=");
                    int end = tg < 0 ? -1 : x.indexOf(' ', tg);
                    key = (tg < 0 ? "?" : x.substring(tg + 7, end < 0 ? x.length() : end))
                            + (wt < 0 ? "" : " " + x.substring(wt, Math.min(x.length(),
                                    x.indexOf(' ', wt) < 0 ? x.length() : x.indexOf(' ', wt))));
                }
                Integer v = counts.get(key);
                counts.put(key, v == null ? 1 : v + 1);
            }
        }, "");
        java.util.ArrayList<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<>(counts.entrySet());
        java.util.Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("queued=" + total[0]);
        for (int k = 0; k < Math.min(12, list.size()); k++) {
            sb.append(" | ").append(list.get(k).getValue()).append(" x ").append(list.get(k).getKey());
        }
        return sb.toString();
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
        if (sCoverMode) CoverPush.pushArtAsync(true, false);
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
    static synchronized Handler worker() {
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

    static Handler main() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        return sMain;
    }

    /**
     * When the card last named a new track, how long the wallpaper check took, and how many
     * attempts the artwork needed.
     *
     * The whole point of a track change is how long it takes, and until now nobody could say
     * where the time went - the module's own log does not reach logcat, so a report of "the cover
     * is slow" had nothing behind it. These ride along with the push (see pushArtToWallpaper) so
     * the wallpaper process, which holds the other half of the timeline, can print all of it at
     * once. Both processes read the same uptimeMillis clock, so the two halves simply subtract.
     */
    static volatile long sCtTrack;
    static volatile long sCtArt;
    static volatile long sCtCheckMs;
    static volatile int sCtTries;

    /**
     * The first track change of a BURST, and how many were swallowed by it.
     *
     * Pressing next twice in a row throws the first push away (see sPushGen), so the timing of a
     * burst reported per-push is the timing of its LAST one - by which point the player has
     * settled and the artwork is there for the asking. That reads as 60ms while the screen sat on
     * the old cover for the better part of a second, which is what someone pressing next
     * repeatedly actually sees. Measured from here instead: the first press of the burst to the
     * cover that finally arrives.
     */
    static volatile long sCtBurst;
    static volatile int sCtSkips;

    /**
     * Which push is the current one. The retries span a couple of seconds, so the card can be
     * dismissed - or the track changed again - while they are still running; without this, a
     * retry that finally found artwork would put the cover back after cover mode had ended.
     */
    static volatile int sPushGen;

    /**
     * Whether the wallpaper process composes covers itself, as said by that process.
     *
     * Asked rather than assumed, because the two processes run the module's code from whenever
     * each last started. After an update that restarted SystemUI but not the wallpaper, the
     * wallpaper still runs the old build, which understands only the composed JPEG - a source
     * sent to it would leave the lock screen without a cover. So the source goes over only once
     * the process on the other end has said it knows what to do with one.
     */
    static volatile boolean sWpComposes;

    /** The one kind of wallpaper this module can work with: a still picture. */
    static final String KIND_IMAGE = "image";

    /**
     * One switch for the whole look. The clock collapse is not a separate thing the user turns
     * on - if the album cover is the wallpaper, the clock is small.
     */
    private static void enterCoverMode(boolean animate) {
        sCoverMode = true;
        MiniPlayerRuntime.refresh();
        CoverCardLayer.entering();
        armTransitionTrace("entering cover mode");
        // Whatever the user decided about the last song does not carry into this one.
        sTapSuppressed = false;
        // The two-finger tap's page is NOT reset here: it stands until the next two-finger tap,
        // across songs, lock screens and a card that comes and goes. See LockLyrics.sTapHidden.
        setDepthHidden(true);
        // Start the card where the OEM has it when animating, so the thumbnail fades out across
        // the clock's own frames instead of blinking away before the clock has begun to move.
        // Without an animation there is nothing to fade, and the settled look goes on directly.
        sCardP = animate ? 0f : 1f;
        if (sCoverCardStyle.mode == CoverCardStyle.CARD) {
            CoverCardLayer.attach(CoverPush.coverLayer());
            CoverCardLayer.style(sCoverCardStyle);
            CoverCardLayer.playback(sCoverCardPlaying);
        }
        // The lyrics exception is decided here, before anything has moved. With the lyrics up
        // the thumbnail the setting hides never leaves, so the morph has nothing to fade it back
        // from; sprung from wherever it was, it would dip out and return over the entry, which is
        // the same blink with the direction reversed. wantsAttached() rather than wantsShown()
        // because nothing is on screen yet - it answers "will the lyrics be up", which is the
        // question this state is about.
        sLyricUp = sMcArtInLyrics && LockLyrics.wantsAttached();
        sLyricArtP = sLyricArtTo = sLyricUp ? 1f : 0f;
        sLyricArtV = 0f;
        sLyricArtAt = 0L;
        applyMediaCard();
        // The response is the slider's, read when the transition starts and nowhere else, which
        // is what makes a change land on the next transition and never mid-flight.
        ClockCollapse.enter(animate, false, "cover");
        // The reading may predate this cover - the card can come up on art that was pushed
        // before the user ever locked the phone - and the OEM will not re-colour on its own.
        recolorClock();
        // The wallpaper process cannot remember this one across a restart, and this is the
        // first moment of a transition it matters for. See pushFadeMs().
        CoverPush.pushFadeMs(sAppCtx);
        LockLyrics.attach();
        CoverCardLayer.refresh();
        saveState();
    }

    /**
     * Leaves cover mode, optionally on the way out rather than all at once.
     *
     * Animated, this is ClockCollapse's spring, and the clock, the glass morph and the card's
     * thumbnail all ride its one progress - what Apple's is, everything at once. Unanimated -
     * the display is off, or nothing was taken over - it is handed back in one frame.
     */
    private static void exitCoverMode(boolean animate) {
        CoverCardLayer.leaving();
        sCoverMode = false;
        CoverCardLayer.refresh();
        // The cover is on its way out, so the reading that coloured the clock describes the
        // wallpaper coming back even less than it described the old one. Dropped at the start:
        // the clock is at its smallest now, so the colour going back to the OEM's is at its
        // least visible.
        if (sCoverTint != 0) {
            sCoverTint = 0;
            recolorClock();
        }
        armTransitionTrace("leaving cover mode");
        // The cut-out belongs to the wallpaper, and the wallpaper is already on its way back -
        // except on a video wallpaper with the cover view still up: that view is held over the
        // window until the window has the video again, and the cut-out comes back with it going
        // (CoverPush.dropVideoCover), not in front of it now.
        if (!(sVideoWallpaper && sCover != null)) setDepthHidden(false);
        ClockCollapse.exit(animate);
        MiniPlayerRuntime.refresh();
        // No applyMediaCard() while the exit is flying: it would drop the card's guard, and the
        // guard is what draws every frame of the thumbnail coming back. onClockReleased() hands
        // the card back when the clock lands.
        if (!ClockCollapse.active()) onClockReleased();
        // The lyrics fade out on their own and leave the keyguard once they have.
        LockLyrics.refresh();
        saveState();
    }

    /** ClockCollapse let go of the clock. Outside cover mode, the card goes back with it. */
    static void onClockReleased() {
        if (sCoverMode) return;
        sCardP = 0f;
        applyMediaCard();
        MiniPlayerRuntime.refresh();
    }

    /** The mini player stays off screen until the cover exit has settled. */
    static boolean coverSceneActive() {
        return sCoverMode || ClockCollapse.phase() != ClockCollapse.Phase.OFF;
    }

    static MediaController miniPlayerSession() {
        return sWatched;
    }

    /** The OEM header owns the media card's background and foreground rim. */
    static View miniPlayerMediaHeader() {
        View art = sCardArt;
        View current = art != null && art.isAttachedToWindow() ? art : null;
        while (current != null) {
            if (current.getClass().getName().contains("MiuiMediaHeaderView")) return current;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        View card = findLockScreenView("mi_media_controls");
        View root = cardShotRoot(card);
        return root != card && root != null
                && root.getClass().getName().contains("MiuiMediaHeaderView") ? root : null;
    }

    /** The card's artwork box, title and artist: where the mini player's own pieces land. */
    static View miniPairArt() { return sCardArt; }
    static View miniPairTitle() { return sCardTitle; }
    static View miniPairArtist() { return sCardArtist; }

    /** The card's background view, where its material is set. */
    static View miniPlayerMediaBg(View header) {
        return header == null ? null : findByName(header, "media_bg");
    }

    /** The corner the card itself draws: media_bg's outline, 24dp on this device. */
    static float miniPlayerCardRadius(View header) {
        View bg = header == null ? null : findByName(header, "media_bg");
        float r = bg == null ? 0f : outlineRadius(bg);
        return r > 0f ? r : 24f * density();
    }

    /** The same gate as the cover morph: a lock screen that is up, awake and not covered. */
    static boolean miniPlayerMorphAllowed() {
        return Looper.myLooper() == Looper.getMainLooper() && coverMorphEligible();
    }

    /**
     * The lock screen's own media presentation holds: the card is up, no cover scene, the
     * keyguard up. Awake or dozing, pad or no pad - those only stop the pill taking touches.
     */
    static boolean miniPlayerPresentable() {
        return sCardShowing && !coverSceneActive() && keyguardShowing();
    }

    private static long sMiniBouncerCheckedAt;
    private static boolean sMiniBouncerUp;

    static boolean miniPlayerCanShow() {
        return miniPlayerDisplayEligible() && !miniControlCenterUp();
    }

    static boolean miniPlayerControlCenterUp() { return miniControlCenterUp(); }

    private static long sMiniCentreCheckedAt;
    private static boolean sMiniCentreUp;

    /**
     * controlCenterUp() looks the container up by name across the whole shade window, and
     * falls back to reading every view's resource name when it is not there. The mini player
     * asked it twice on every frame the lock screen drew - through its drags, its morphs, a
     * track change - which is where their dropped frames went. The same 100-odd ms the pad's
     * answer is kept for.
     */
    private static boolean miniControlCenterUp() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sMiniCentreCheckedAt > 150L) {
            sMiniCentreCheckedAt = now;
            sMiniCentreUp = controlCenterUp();
        }
        return sMiniCentreUp;
    }

    /** A translucent control center keeps the selected mini card visible but blocks its taps. */
    static boolean miniPlayerDisplayEligible() {
        if (!sCardShowing || coverSceneActive() || !screenOnCached() ||
                !keyguardShowing() || !onKeyguardNow()) return false;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sMiniBouncerCheckedAt > 100L) {
            sMiniBouncerCheckedAt = now;
            sMiniBouncerUp = bouncerUp();
        }
        return !sMiniBouncerUp;
    }

    /** Enter through the same route as the OEM artwork, with MiniPlayerRuntime owning the bridge. */
    static void miniPlayerEnterCover() {
        if (!miniPlayerCanShow()) return;
        if (!sAuto) {
            CoverMorphLayer.cancel();
            setCoverEnabled(true, true, false);
        } else {
            enterFromTap("mini player tapped");
        }
    }

    /**
     * A finger turned a scene's own morph round: back into the cover or the lyrics while the
     * exit is still running, or out of them while the entry is. Not gated on the scene being
     * off, as a tap on the pill is - it is still settling, and that is the point.
     */
    static void miniPlayerTurnScene(boolean enter) {
        if (!keyguardShowing() || bouncerUp()) return;
        if (enter) {
            if (sCoverMode) return;
            if (!sAuto) setCoverEnabled(true, true, false);
            else enterFromTap("mini player pulled back up");
        } else {
            if (!sCoverMode) return;
            exitFromTap("mini player pulled back down");
        }
    }

    /** The wake reached the clock before the SCREEN_ON broadcast did. */
    static void noteAwake() {
        if (sScreenOn) return;
        sScreenOn = true;
        sAodGrey = Float.NaN;
        forgetSysReads();
        recolorClock();
    }

    /** The card's progress into the cover look, as last written. */
    static float cardProgress() {
        return sCardP;
    }

    static boolean coverCardVisible() {
        View c = sContainer;
        // The PIN pad is not a reason to hide it: taken away there, the square cut out as the pad
        // came up and popped back as it went. It stays and blurs under the pad instead, the way
        // the lyrics do - see CoverCardLayer.followBouncer.
        return (sCoverMode || ClockCollapse.phase() == ClockCollapse.Phase.EXIT)
                && keyguardShowing() && c != null && c.isShown()
                && (sScreenOn || coverCardInAod() || coverCardFallingAsleep());
    }

    /**
     * The clock growing into the doze's big clock. The screen is already off, and without this
     * the layer was taken GONE on the first frame of the fall, before the square could go under
     * the growing digits - CoverCardLayer ties it to them.
     */
    private static boolean coverCardFallingAsleep() {
        return sCoverMode && ClockCollapse.phase() == ClockCollapse.Phase.EXIT
                && !ClockCollapse.exiting() && !ClockCollapse.aodHeld();
    }

    /**
     * The square stays through a doze that keeps the cover's small clock, and only that one.
     * Under the OEM's big AOD clock there is no room between it and the media card, and the
     * square sat across the digits. It used to be a setting, off by default; with the big clock
     * taken care of there was nothing left for the setting to protect.
     */
    static boolean coverCardInAod() {
        return sCoverMode && !sScreenOn && ClockCollapse.phase() == ClockCollapse.Phase.AOD
                && ClockCollapse.aodHeld();
    }

    /** The full-screen AOD may show a faint static colour wash even with the square hidden. */
    static boolean coverCardBackdropInAod() {
        View c = sContainer;
        return sCoverMode && sCoverCardStyle.mode == CoverCardStyle.CARD && !sScreenOn
                && ClockCollapse.phase() == ClockCollapse.Phase.AOD
                && ClockCollapse.aodFullScreen() && keyguardShowing()
                && c != null && c.isShown();
    }

    static int screenWidth() {
        return sScreenW;
    }

    static float coverCardMediaTop() {
        if (coverCardInAod()) return sScreenH * 0.70f;
        return liveMediaTop();
    }

    /**
     * The media card's top on screen as it is drawn right now, the AOD's compact card included;
     * NaN when it is not up. coverCardMediaTop() answers a stand-in in the AOD instead - for the
     * callers that must not follow the card through the doze's own transition.
     */
    static float liveMediaTop() {
        View card = sCardGuarded != null ? sCardGuarded : LockLyrics.card();
        if (card != null && card.isShown() && card.getHeight() > 0) {
            int[] xy = new int[2];
            card.getLocationOnScreen(xy);
            if (xy[1] > sScreenH / 3 && xy[1] <= sScreenH) return xy[1];
        }
        // The OEM card may not have been laid out after wake. An old measurement belongs to
        // another lock session, so wait for this one's measured boundary.
        return Float.NaN;
    }

    /** One frame of the card fade, from ClockCollapse's progress. */
    static void setCardProgressFrom(float p) {
        setCardProgress(p);
    }

    /** Geometry is sampled from the OEM's live thumbnail, not its transformed screen origin. */
    static CoverMorphMotion.Box coverMorphThumbnail() {
        View art = sCardArt;
        if (art == null || !art.isAttachedToWindow() || art.getWidth() <= 0
                || art.getHeight() <= 0 || !(art.getParent() instanceof View)) return null;
        int[] xy = new int[2];
        ((View) art.getParent()).getLocationOnScreen(xy);
        return new CoverMorphMotion.Box(xy[0] + art.getLeft(), xy[1] + art.getTop(),
                art.getWidth(), art.getHeight());
    }

    /** Reads the clipping that actually rounds the OEM artwork instead of assuming one radius. */
    static float coverMorphThumbnailRadius() {
        View art = sCardArt;
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) {
            return 14f * density();
        }
        float radius = art.getClipToOutline() ? outlineRadius(art) : 0f;
        if (radius <= 0f && art.getParent() instanceof View) {
            View box = (View) art.getParent();
            if (box.getClipToOutline()) radius = outlineRadius(box);
        }
        if (radius > 0f) return radius;
        return Math.min(14f * density(), Math.min(art.getWidth(), art.getHeight()) * 0.20f);
    }

    static ViewGroup coverMorphRoot() {
        View root = sContainer == null ? null : sContainer.getRootView();
        return root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    static boolean coverMorphEligible() {
        return coverMorphStillEligible() && !bouncerUp() && !controlCenterUp();
    }

    /** Cheap per-frame guard; the expensive overlay lookups are only needed at gesture start. */
    static boolean coverMorphStillEligible() {
        View c = sContainer;
        return c != null && c.isAttachedToWindow() && c.isShown() && sScreenOn
                && keyguardShowing();
    }

    static boolean coverMorphCardMode() {
        return sCoverCardStyle.mode == CoverCardStyle.CARD;
    }

    /** A manual entry must keep its moving artwork until the asynchronous backdrop can take it. */
    static boolean coverMorphHandoffReady(boolean cardMode) {
        long artAt = sCtArt;
        if (artAt == 0L) return false;
        long afterArt = cardMode ? 120L : fadeMsFor(sClockResponse) + 140L;
        return android.os.SystemClock.uptimeMillis() - artAt >= afterArt;
    }

    /** The full-screen destination is the sharp band in CoverCompose.composeWallpaper(). */
    static CoverMorphMotion.Box coverMorphTarget(Bitmap art) {
        if (art == null || art.isRecycled()) return null;
        if (sCoverCardStyle.mode == CoverCardStyle.FULL) {
            if (sScreenW <= 0 || sScreenH <= 0 || art.getWidth() <= 0) return null;
            float height = art.getHeight() * (sScreenW / (float) art.getWidth());
            float top = (sScreenH - height) * Math.max(0f, Math.min(1f, sBias));
            return new CoverMorphMotion.Box(0f, top, sScreenW, height);
        }
        ViewGroup layer = CoverPush.coverLayer();
        if (layer == null || layer.getWidth() <= 0 || layer.getHeight() <= 0) return null;
        int[] xy = new int[2];
        layer.getLocationOnScreen(xy);
        float mediaTop = coverCardMediaTop();
        if (!Float.isFinite(mediaTop)) {
            View card = findSysuiView("mi_media_controls");
            if (card != null && card.isShown() && card.getHeight() > 0) {
                int[] cardXY = new int[2];
                card.getLocationOnScreen(cardXY);
                mediaTop = cardXY[1];
            }
        }
        CoverCardStyle.Rect r = sCoverCardStyle.place(layer.getWidth(), layer.getHeight(),
                layer.getResources().getDisplayMetrics().density,
                ClockCollapse.contentBottomFor(layer) - xy[1],
                ClockCollapse.unzoomY(layer, mediaTop) - xy[1]);
        return r == null ? null : CoverMorphMotion.cardBox(xy[0] + r.x,
                xy[1] + r.y, r.side, CoverCardLayer.renderedScale(layer),
                CoverCardStyle.aspect(art.getWidth(), art.getHeight()));
    }

    /** Matches CoverCardLayer's live rule; a full wallpaper ends at the square screen edge. */
    static float coverMorphTargetRadius(CoverMorphMotion.Box target) {
        if (target == null || sCoverCardStyle.mode != CoverCardStyle.CARD) return 0f;
        return sCoverCardStyle.radius(Math.min(target.w, target.h));
    }

    /** Keep the shared media card at its real state while the moving copy owns its pixels. */
    static void refreshMediaCardForMorph() {
        // Called on the morph's frames: the card already held, not a search of the whole tree.
        View card = sCardGuarded;
        if (card == null || !card.isAttachedToWindow()) card = findSysuiView("mi_media_controls");
        if (card != null) assertMediaCard(card);
    }

    /** Arms the liquid-glass -> filled morph across the collapse. See applyGlassMorph(). */
    static void armGlassMorph() {
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        sAppliedGlassV = Float.NaN;
        // Each entry morphs from the start again. Left set by the previous cover, the hold in
        // applyGlassMorph() jumped every later entry to the solid end on its first frame.
        sGlassSettled = false;
    }

    /** Puts the glass back where the collapse found it, and stops morphing it. */
    static void restoreGlass() {
        if (Float.isNaN(sGlassV0)) return;
        float back = sGlassV0;
        sGlassV0 = sGlassV1 = Float.NaN;
        sAppliedGlassV = Float.NaN;
        sGlassSettled = false;
        callOnClockViews("updateGlassValue", "f", back, 0, false);
    }

    /**
     * The date view the clock is placed against. A different one means the clock views were
     * re-inflated - a style change - and everything resolved about the old ones is stale.
     */
    static void noteDateView(View date) {
        if (date == sDateView) return;
        sDateView = date;
        sSigViews = new View[0];
        sClockTargets.clear();
        forgetClockRoots();
        forgetGlyphBox();
    }

    /** A pre-dp height setting is converted the first time there is a box to convert it with. */
    static void convertLegacyHeight(RectF box) {
        if (!Float.isNaN(sClockLegacyK)) heightDpFor(box);
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

    /** Set once the state file has been consulted for sHideFp, whether or not it had a value. */
    private static volatile boolean sHideFpPeeked;

    /**
     * Reads the fingerprint setting out of the state file before loadState() would.
     *
     * The print is not part of the keyguard's own tree. It lives in a window of its own -
     * `gxzw_touch`, 206x206, the module's report names it - which SystemUI paints as the
     * keyguard comes up, and that paint happens BEFORE KeyguardClockContainer attaches. Since
     * attaching is what gives the module a context, and the context is what lets it read the
     * state file, the first frame of the print was always decided with the setting still at its
     * default. The hook let it through, the window was never asked to draw again for as long as
     * the keyguard stayed up, and the print sat there for the whole session - which is what
     * "restarting SystemUI turns the hiding off until you lock the phone a second time" was.
     *
     * Only this one key, and only until the real load has happened: the rest of the file belongs
     * to loadState, which applies values through setters that want a built keyguard. A file
     * caught mid-write costs the key its default for this one read, the same exposure loadState
     * has always had.
     */
    private static void peekHideFp() {
        if (sHideFpPeeked || sAppCtx != null) return;
        sHideFpPeeked = true;
        try {
            // Reflection because ActivityThread is not in the SDK to compile against. This is
            // the process's own Application - there is no other context to be had this early,
            // and the hooks that call this run on SystemUI's main thread, where it is set.
            Context c = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (c == null) {
                // Nothing to read it with yet; the next draw tries again.
                sHideFpPeeked = false;
                return;
            }
            java.io.File f = new java.io.File(c.getFilesDir(), STATE_FILE);
            if (!f.exists()) return;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            for (String line : new String(buf, 0, Math.max(0, n)).split("\n")) {
                if (line.startsWith("hidefp=")) {
                    sHideFp = "1".equals(line.substring(7).trim());
                    Xp.log(TAG + "fingerprint setting read early: hide=" + sHideFp);
                    return;
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "reading the fingerprint setting early failed: " + t);
        }
    }

    /**
     * The root view of every window that belongs to the print, through WindowManagerGlobal.
     *
     * These windows are not in the keyguard's tree - `gxzw_touch` holds the print and
     * `gxzw_anim` the ring and the "try again" tip - so there is no way to them from a view we
     * were handed. Matched by window name, which is the OEM's and has been stable, and the
     * result is used for nothing but finding the icon views inside.
     */
    private static java.util.List<View> fodWindowRoots() {
        java.util.List<View> out = new java.util.ArrayList<>();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object g = wmg.getMethod("getInstance").invoke(null);
            String[] names = (String[]) wmg.getMethod("getViewRootNames").invoke(g);
            java.lang.reflect.Method getRoot = wmg.getMethod("getRootView", String.class);
            for (String name : names) {
                if (name == null || !name.toLowerCase(java.util.Locale.ROOT).contains("gxzw")) {
                    continue;
                }
                View v = (View) getRoot.invoke(g, name);
                if (v != null) out.add(v);
            }
        } catch (Throwable t) {
            Xp.log(TAG + "the fingerprint windows could not be read: " + t);
        }
        return out;
    }

    /**
     * Takes in any icon view the constructor hook never saw.
     *
     * On this phone it never sees any: the view on screen is a MiuiGxzwIconView by the name we
     * hook, yet no construction of it is ever intercepted, so the alpha the hook exists to set
     * was being applied to an empty list. Found by walking the print's own windows instead, and
     * put in the same map, so the setting's switch reaches them like any other.
     *
     * Only views that name themselves an icon: the ring and the "try again" tip share those
     * windows and neither is the print.
     */
    private static void adoptFodIcons() {
        for (View root : fodWindowRoots()) adoptFodIcons(root, 0);
    }

    private static void adoptFodIcons(View v, int depth) {
        if (v == null || depth > 6) return;
        // Both painters: the icon view and the one the frames land on. The tip view in the same
        // window is neither, and keeps its "try again" to itself.
        String name = v.getClass().getName();
        if (name.endsWith("IconView") || name.equals(CLS_FOD_ANIM_VIEW)) {
            sFodIcons.put(v, Boolean.TRUE);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) adoptFodIcons(g.getChildAt(i), depth + 1);
        }
    }

    /**
     * Applies the current setting to every icon view still alive. Runs on the main thread: the
     * receiver has no handler of its own, so it is already there.
     */
    private static void applyHideFp() {
        adoptFodIcons();
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

    static void setDepthHidden(final boolean hide) {
        setDepthHidden(hide, 0);
    }

    /**
     * attempt exists because this runs on a SystemUI that has just started: cover mode is
     * restored the moment the clock container attaches, and the foreground layer that holds the
     * cut-out is not necessarily inflated yet. The old code logged "not found" once and gave up,
     * which left the old wallpaper's subject sitting on top of the album cover until something
     * else happened to call it again.
     */
    static void setDepthHidden(final boolean hide, final int attempt) {
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
                sDeductedRef = d;
                if (hide) {
                    if (!sDeductedHeld) {
                        sDeductedWanted = d.getVisibility();
                        sDeductedHeld = true;
                    }
                    setDeductedVisibility(d, View.INVISIBLE);
                } else if (sDeductedHeld) {
                    sDeductedHeld = false;
                    setDeductedVisibility(d, sDeductedWanted >= 0 ? sDeductedWanted : View.VISIBLE);
                }
                // On a live wallpaper the cut-out subject is a TextureView in the same layer
                // rather than this ImageView, and it is in front of the clock just the same.
                // Resolved every time rather than cached: it is added and removed by MIUI as
                // the wallpaper type changes.
                View fg = CoverPush.videoSurfaceView("keyguard_foreground_layer");
                sVideoFg = fg;
                // And the video itself, in the background layer. The cover is drawn over it
                // either way, but leaving it playing under an opaque view decodes a video
                // nobody can see - and if anything of it is still reaching the screen, this
                // is what says so.
                View bg = sVideoWallpaper ? CoverPush.videoSurfaceView("keyguard_background_layer") : null;
                sVideoBg = bg;
                CoverPush.setVideoSurfacesHidden(hide, bg, fg);
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
                    setDeductedVisibility(d, View.INVISIBLE);
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

    static View findSysuiView(String id) {
        View v = sContainer;
        if (v == null) return null;
        View root = v.getRootView();
        int i = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
        View hit = i == 0 ? null : root.findViewById(i);
        return hit != null ? hit : findByName(root, id);
    }

    /** The screen height the card readings are checked against. */
    static int screenHeight() {
        return sScreenH;
    }

    /**
     * The lock screen's copy of a view the shade - or a third-party module - also puts up.
     *
     * `mi_media_controls` is declared by three layouts in SystemUI: the media card
     * (miui_media_session) and two media island variants, both rooted at
     * PlayerIslandConstraintLayout. findSysuiView() answers with whichever comes first in the
     * tree, and that is the card only while no island copy is inflated - so a module that turns
     * the island on can take the card away from everything that measures against it.
     *
     * The test for "this is the one the lock screen is showing" is the one sampleCardRect() and
     * onLockTap() already use: the card sits in the notification area, below a clock pinned near
     * the top, and is never up by the status bar. Anything higher is the shade's copy or an
     * island. Falls back to findSysuiView(), so a card that is simply not laid out yet behaves
     * exactly as it did.
     */
    static View findLockScreenView(String id) {
        View v = sContainer;
        if (v == null) return null;
        View root = v.getRootView();
        int i = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
        View hit = i == 0 ? null : findOnScreenById(root, i);
        return hit != null ? hit : findSysuiView(id);
    }

    /** The first view carrying this id that is really up on the lock screen, in tree order. */
    private static View findOnScreenById(View v, int id) {
        // Pruned on the way down: isShown() is false for everything under a hidden parent, so
        // descending into one can only ever return null.
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v.getId() == id && v.isShown() && v.isAttachedToWindow() && v.getHeight() > 0) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (loc[1] >= sScreenH / 3) return v;
        }
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findOnScreenById(g.getChildAt(i), id);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * The bottom edge of the card rectangle on record, in screen pixels, or NaN when what is on
     * record is not a reading.
     *
     * The bottom rather than the top because of what the lyrics do with it: with no card drawn,
     * the band takes the space the card would have occupied, and that space ends here. Measured
     * off the card's own rectangle, so it is the same block the OEM's content would have filled -
     * on this screen 1700..2257, which stops clear of the shortcut buttons at 2219.
     *
     * sampleCardRect() only ever writes a settled card in the lower two thirds, and the state file
     * re-applies the same test on load - so a zero, or a reading taken from the shade, is already
     * excluded on the way in. The test is repeated here because sScreenH is the default 2608 until
     * the container attaches, which is after loadState() has run.
     */
    static float sampledCardBottom() {
        if (sCardT <= sScreenH / 3 || sCardH <= 0) return Float.NaN;
        return sCardT + sCardH;
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
                            && ((sCoverMode && (sMcHideArt || sMcTitleTap))
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
                if (sCoverMode && (sMcHideArt || sMcTitleTap)) guardCard(card);
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
        // The exception: with the lyrics up, the thumbnail the setting hides comes back.
        //
        // Held across the exit of cover mode rather than asked there. The lyric view fades out on
        // the card's own progress, so on the way out the lyrics are still on screen while they
        // fade - but the phase has already left ON, and wantsShown() answers "is it on screen
        // right now", so it said no on the exit's first frame. hideP went to 1 with p still at 1:
        // the thumbnail vanished and the title snapped to the centre, and then the falling p
        // brought both back over the rest of the exit. That is the blink. The answer is held for
        // the exit and re-asked once the clock has landed, where it cannot be seen.
        boolean flying = ClockCollapse.phase() == ClockCollapse.Phase.EXIT;
        if (!flying) {
            // The screen going off is not one of the lyrics' comings and goings, which is why the
            // test is not wantsShown() alone. That answers "is the lyric view on screen now", and
            // it says no the moment the screen does off - so the always-on display, which keeps
            // drawing this card, lost the thumbnail every time the screen went dark. With the
            // screen off, or in the AOD a wake has not left yet, the question is the standing one
            // instead: are the lyrics what this lock screen is showing.
            sLyricUp = LockLyrics.wantsShown() || LockLyrics.heldForBlur()
                    || ((ClockCollapse.phase() == ClockCollapse.Phase.AOD || !screenOnCached())
                        && LockLyrics.wantsAttached());
        }
        float exc = stepLyricArt(sMcArtInLyrics && sLyricUp);
        // One number, on purpose. Centring is not a setting of its own any more - a title with no
        // thumbnail beside it belongs in the middle and a title that has one does not - so the
        // title follows the same value the thumbnail does, and both now ride the exception's
        // progress instead of stepping to it: the thumbnail comes back and the title slides
        // aside for it, at the speed of the transition they are part of.
        float hideP = sMcHideArt ? p * (1f - exc) : 0f;
        float centreP = hideP;
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
            if (CoverMorphLayer.active()) {
                // The moving copy owns these pixels until it reaches either endpoint. Keep the
                // OEM view in the layout so its slot and the title continue to move normally.
                // On the way back it fades in under the landing copy instead of being switched on
                // at the end: the copy hides its pixels, but not its shadow, which popped in.
                if (art.getVisibility() != View.VISIBLE) art.setVisibility(View.VISIBLE);
                float a = Math.min(1f - hideP, CoverMorphLayer.thumbAlpha());
                if (art.getAlpha() != a) art.setAlpha(a);
            }
        }
        centreCardText(card, (TextView) sCardTitle, centreP);
        centreCardText(card, (TextView) sCardArtist, centreP);
        applyTitleTap((TextView) sCardTitle, sMcTitleTap && sCoverMode && onKeyguard);
        if (onKeyguard && !sCardForced) sampleCardRect(card, p);
    }

    /**
     * One step of the lyrics exception, and what it is now.
     *
     * Stepped, not switched: the two answers are two looks of the card, and moving between them
     * is the same transition the cover itself moves on. Same spring constants as the clock's
     * (EASE_COVER, at the response the user set for the transition), same integration, so the
     * thumbnail and the title cross at the speed of the morph they belong to. Anything faster
     * reads as the cut this replaces; anything slower stops matching the clock beside it.
     *
     * The frames come from the card's own pass - the guard already runs every frame of a
     * transition, and the lyrics being up means the lyric view is redrawing on those same
     * frames. Only the first step after a change has to be asked for (kickCardFrame): that step
     * writes nothing new, so nothing would invalidate and the spring would sit at rest one frame
     * short of moving.
     *
     * Snapped rather than sprung on the way into cover mode - see enterCoverMode - because a
     * thumbnail fading out and back in over the entry would be the same blink, moved.
     */
    private static float stepLyricArt(boolean want) {
        float to = want ? 1f : 0f;
        // A capture is the app asking what the settled card looks like (see shootCard), and the
        // frame it lands on is not a thing to keep. Everywhere else the motion IS the state.
        if (sCardForced) {
            sLyricArtP = sLyricArtTo = to;
            sLyricArtV = 0f;
            sLyricArtAt = 0L;
            return sLyricArtP;
        }
        if (to != sLyricArtTo) {
            // Retargeted. The spring starts on the next frame: this one has nothing new to write.
            sLyricArtTo = to;
            sLyricArtAt = 0L;
            kickCardFrame();
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (sLyricArtAt == 0L) {
            sLyricArtAt = now;
        } else {
            // A frame that stalled must not be integrated whole, or the thumbnail jumps by
            // whatever the stall was, in one step, on the very transition this is smoothing.
            float dt = Math.min(0.05f, (now - sLyricArtAt) / 1000f);
            sLyricArtAt = now;
            if (dt > 0f) {
                final float zeta = EASE_COVER[0];
                final float w0 = (float) (2 * Math.PI / sClockResponse);
                final float k = w0 * w0, damp = 2f * zeta * w0;
                // Sub-stepped: a spring integrated at 60Hz with a response this short is not
                // stable, and the clock's own loop substeps for the same reason.
                int steps = Math.max(1, (int) Math.ceil(dt * 240f));
                float h = dt / steps;
                for (int i = 0; i < steps; i++) {
                    float a = -k * (sLyricArtP - to) - damp * sLyricArtV;
                    sLyricArtV += a * h;
                    sLyricArtP += sLyricArtV * h;
                }
                if (sLyricArtP < 0f) sLyricArtP = 0f;
                if (sLyricArtP > 1f) sLyricArtP = 1f;
                // Both ends are exact: hideP is p at 0 and 0 at 1, which are the two states the
                // card has always had, and an alpha left at 0.998 of one of them is not.
                if (Math.abs(sLyricArtP - to) < 0.002f && Math.abs(sLyricArtV) < 0.02f) {
                    sLyricArtP = to;
                    sLyricArtV = 0f;
                }
            }
        }
        return sLyricArtP;
    }

    /**
     * Asks for one frame of the card's pass.
     *
     * A retarget writes the same values it wrote a frame ago, so it invalidates nothing and the
     * frame that would move the spring never arrives. One posted frame is enough to start it:
     * from there every step writes a different alpha, scale and translationX, and those are what
     * carry the next frame.
     */
    private static void kickCardFrame() {
        final View card = sCardGuarded;
        if (card == null || sCardFramePosted) return;
        sCardFramePosted = true;
        card.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                sCardFramePosted = false;
                if (sCardGuarded == card) assertMediaCard(card);
            }
        });
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
    private static void sampleCardRect(View card, float p) {
        // Not mid-morph. The reading below is of a card that has HELD STILL for CARD_SETTLE_MS,
        // and while the cover is going up or coming down the card is moving on every frame - so
        // the settle test can never pass and the only thing a read here produces is the bill for
        // getLocationOnScreen(), which walks the parent chain and its transforms. Asked on every
        // frame of a transition it measured 100us and up, which was the whole of what the card's
        // share of a frame cost once the two binder calls were memoised.
        //
        // The ends are what it is for: p at 0 is the card as the OEM lays it out, p at 1 is the
        // restyled one, and both hold still. Skipping the middle cannot lose a reading - the
        // settle rule stamps its clock from the first read, and the first read after the morph
        // stops is the same rectangle the skipped ones would have been stamped with.
        if (p > 0f && p < 1f) return;
        // Not while dozing. AOD shows the keyguard and holds still for as long as it is up, so
        // it sails past the settle rule below - and it lays the card out somewhere else, which
        // is how the preview ended up drawing it too high. isInteractive() is false in doze.
        if (!card.isShown() || !screenOn()) return;
        int w = card.getWidth(), h = card.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] loc = LOC_CARD;
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
        CoverCardLayer.refresh();
        saveStateSoon();
    }





    private static void setCardProgress(float p) {
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        if (sCardP == p) return;
        sCardP = p;
        CoverCardLayer.refresh();
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
            boolean onCard = (wantsArtTap() || MiniPlayerRuntime.wantsNativeArtworkGesture())
                    && screenOn() && keyguardShowing() && onKeyguardNow()
                    && !bouncerUp() && !sGestureOnCentre && !sGestureOnCharge;
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
                else if (MiniPlayerRuntime.wantsNativeArtworkGesture()) miniPlayerEnterCover();
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
    private static final int SWIPE_NONE = 0, SWIPE_FIRED = 1, SWIPE_HELD = 2;
    private static boolean sCardSwipeArmed, sCardSwipeFired;
    private static float sCardSwipeX, sCardSwipeY;

    /**
     * The media card's half of the dynamic switch: a drag that starts on the card and goes
     * down, more down than sideways, past the touch slop. Horizontal drags (the progress bar)
     * and taps (the buttons) never qualify, and are left alone from the DOWN on.
     */
    private static int cardSwipe(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                sCardSwipeFired = false;
                sCardSwipeArmed = MiniPlayerRuntime.wantsNativeCardSwipe()
                        && !sGestureOnCentre && !sGestureOnCharge
                        && cardRectContains(ev.getRawX(), ev.getRawY());
                sCardSwipeX = ev.getRawX();
                sCardSwipeY = ev.getRawY();
                return SWIPE_NONE;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sCardSwipeFired) {
                    MiniPlayerRuntime.dragMove(ev);
                    return SWIPE_HELD;
                }
                if (!sCardSwipeArmed) return SWIPE_NONE;
                float dx = ev.getRawX() - sCardSwipeX, dy = ev.getRawY() - sCardSwipeY;
                float slop = android.view.ViewConfiguration.get(sAppCtx).getScaledTouchSlop();
                if (Math.abs(dx) > slop && Math.abs(dx) >= Math.abs(dy)) {
                    sCardSwipeArmed = false;
                    return SWIPE_NONE;
                }
                if (dy > slop && dy > Math.abs(dx) * 1.2f) {
                    sCardSwipeArmed = false;
                    sCardSwipeFired = true;
                    sArtSwallow = false;
                    if (sCoverMode) {
                        // Out of the cover or the lyrics, landing on the pill: the scene exit
                        // shrinks the card into it.
                        MiniPlayerRuntime.preferMini();
                        MiniPlayerRuntime.rememberScene();
                        exitFromTap("media card swiped down");
                    } else if (!MiniPlayerRuntime.beginDrag(true, ev)) {
                        // No geometry to pull: the switch still happens, on its own spring.
                        MiniPlayerRuntime.onNativeCardSwipeDown();
                    }
                    return SWIPE_FIRED;
                }
                return SWIPE_NONE;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean held = sCardSwipeFired;
                if (held) {
                    MiniPlayerRuntime.dragEnd(ev,
                            ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                }
                sCardSwipeArmed = sCardSwipeFired = false;
                return held ? SWIPE_HELD : SWIPE_NONE;
            }
            default:
                return sCardSwipeFired ? SWIPE_HELD : SWIPE_NONE;
        }
    }

    private static boolean cardRectContains(float x, float y) {
        View header = miniPlayerMediaHeader();
        if (header == null || !header.isShown() || header.getWidth() <= 0) return false;
        int[] loc = new int[2];
        header.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + header.getWidth()
                && y >= loc[1] && y < loc[1] + header.getHeight();
    }

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
    static boolean onKeyguardNow() {
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

    private static View sBouncerView;
    private static long sBouncerLookedAt;

    /**
     * bouncerUp() for a caller that asks on every frame: the view is kept rather than looked up
     * through the whole tree each time, and looked up again only once the keyguard is rebuilt -
     * and on a build without it, at most once a second, since the miss walks the whole tree.
     */
    static boolean bouncerShown() {
        View b = sBouncerView;
        if (b == null || !b.isAttachedToWindow()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sBouncerLookedAt < 1000L) return false;
            sBouncerLookedAt = now;
            b = findSysuiView("keyguard_bouncer_container");
            sBouncerView = b;
        }
        return b != null && b.isShown();
    }

    /**
     * How far up the PIN pad is, 0..1, for the blur the lyrics and the square put on under it.
     *
     * Not bouncerShown(): the container only goes INVISIBLE once the pad's exit has finished,
     * ~300ms after it started, so a blur keyed on it began clearing after the OEM's own blur had
     * already gone. Nor the container's own alpha or its direct child's - they only jump at the
     * two ends. What fades is the security view itself (KeyguardPatternView, the PIN or password
     * one), 1 to 0 over ~270ms from the first frame of the exit, while its header slides down
     * (measured 2026-09-23, `op bouncer` tracing the whole subtree). So the level is the product
     * of the alphas from the container down to it. A build where it cannot be found reads 1
     * until the container hides, which is what bouncerShown() gave.
     */
    static float bouncerLevel() {
        if (!bouncerShown()) {
            sSecurityView = null;
            sBouncerSince = 0L;
            noteBouncer(0f, 0f);
            return 0f;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (sBouncerSince == 0L) sBouncerSince = now;
        // The pad's view reads fully opaque for the first frame or two, before the OEM resets it
        // to 0 and fades it in (measured: shown, 1.0, 1.0, then 0 at +15ms and up over ~70ms).
        // Followed as fast as the blur now follows, that was a flash of blur at the start.
        if (now - sBouncerSince < BOUNCER_SETTLE_MS) {
            noteBouncer(0f, -1f);
            return 0f;
        }
        View b = sBouncerView;
        View sec = sSecurityView;
        if (sec == null || !sec.isAttachedToWindow() || sec.getVisibility() != View.VISIBLE
                || !isAncestor(b, sec)) {
            sec = findSecurityView(b, 0);
            sSecurityView = sec;
        }
        float level = b.getAlpha();
        if (sec != null) {
            for (View v = sec; v != null && v != b; ) {
                level *= v.getAlpha();
                Object p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
        }
        level = Math.max(0f, Math.min(1f, level));
        noteBouncer(level, b.getAlpha());
        return level;
    }

    /** The pad's own view under the container, kept for the length of one showing. */
    private static View sSecurityView;
    /** When the container was first seen shown this time, 0 while it is not. */
    private static long sBouncerSince;
    private static final long BOUNCER_SETTLE_MS = 40L;

    /**
     * The shown security view: KeyguardPatternView, KeyguardPINView, KeyguardPasswordView and
     * the SIM ones all name themselves Keyguard...View, and the frames around them do not end so.
     */
    private static View findSecurityView(View v, int depth) {
        if (v.getVisibility() != View.VISIBLE || depth > 7) return null;
        String n = v.getClass().getSimpleName();
        if (depth > 0 && n.startsWith("Keyguard") && n.endsWith("View")
                && !n.startsWith("KeyguardSecurity") && !n.contains("Message")) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findSecurityView(g.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The last few changes of what bouncerLevel() read, for `op bouncer`. */
    private static final StringBuilder sBouncerTrace = new StringBuilder();
    private static int sBouncerTraceLines;
    private static String sBouncerLast = "";

    private static void noteBouncer(float level, float own) {
        String now = String.format(java.util.Locale.ROOT, "lvl=%.2f own=%.2f sec=%s", level, own,
                sSecurityView == null ? "-" : sSecurityView.getClass().getSimpleName());
        if (now.equals(sBouncerLast)) return;
        sBouncerLast = now;
        if (sBouncerTraceLines >= 80) {
            int cut = sBouncerTrace.indexOf("\n");
            if (cut >= 0) sBouncerTrace.delete(0, cut + 1);
        } else {
            sBouncerTraceLines++;
        }
        sBouncerTrace.append(android.os.SystemClock.uptimeMillis()).append(' ').append(now)
                .append('\n');
    }

    /**
     * Whether the control centre is pulled down over the lock screen.
     *
     * The swipe-down centre does not hide the keyguard the way the full settings expansion does -
     * measured, the clock container and the card both stay shown - so every guard the cover tap
     * already has is still true, and a tap aimed at a quick toggle toggles the cover instead.
     * What does flip with the centre is its window view: GONE while it is shut, VISIBLE while it
     * is open, under a container and a content wrapper that are themselves always VISIBLE. That
     * window view has no id of its own, so it is reached as the first child of the content
     * wrapper rather than looked up directly.
     *
     * Falling back to false when the tree cannot be resolved keeps the tap working on a build
     * that renamed these views, rather than silently switching the whole feature off.
     */
    private static boolean controlCenterUp() {
        View cc = findSysuiView("control_center_container");
        if (!(cc instanceof ViewGroup)) return false;
        View content = findByName(cc, "content_container");
        if (!(content instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) content;
        return g.getChildCount() > 0 && g.getChildAt(0).isShown();
    }

    /**
     * Whether the charging animation is on the lock screen.
     *
     * HyperOS plays a full-screen animation when the phone is plugged in, and it does not take
     * the touch: it is a FrameLayout whose own click handling covers a fraction of its area, so
     * a DOWN anywhere else falls through to the shade window underneath and the lock screen's
     * own tap - ours - fires with the animation still on screen. Tapping to dismiss it then
     * toggles the cover as well.
     *
     * It is read off the live tree rather than tracked with a flag of our own. The animation view
     * is added straight to the keyguard's root view by MiuiChargeAnimationView.addChargeView and
     * taken out again by removeChargeView when it ends or the screen wakes, so the tree is the
     * one place that cannot go stale - a flag set by a hook would stay set for the rest of the
     * session if the build took the view out some other way, and every tap on the cover would be
     * dead. This way a build that renames the class answers "no" and leaves the tap exactly as
     * it was, which is the same bargain bouncerUp and controlCenterUp make.
     */
    private static boolean chargeAnimUp() {
        View v = sContainer;
        if (v == null) return false;
        View root = v.getRootView();
        return root != null && chargeAnimIn(root, 0);
    }

    /**
     * The walk above. Depth-bounded because the animation is added to the window root itself -
     * it is a child of the root, or at most a couple of levels down - and this runs on every
     * touch the lock screen sees.
     */
    private static boolean chargeAnimIn(View v, int depth) {
        // isShown rather than the class alone: it is what "and the user can see it" means, and
        // the point of the guard is what is on the screen, not what is attached to the window.
        if (v.getClass().getName().startsWith(CLS_CHARGE_PKG) && v.isShown()) return true;
        if (depth >= 3 || !(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (chargeAnimIn(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    // ---- the charging animation's own comings and goings, for `op chargeanim`

    /** The last few attach/detach events, oldest first. */
    private static final java.util.ArrayDeque<String> sChargeTrail = new java.util.ArrayDeque<>();
    /** When the animation last went up, or 0 when it is not up. */
    private static long sChargeUpAt;
    /** How long the last one stayed up, for the line that records it going down. */
    private static long sChargeUpFor;

    private static void noteChargeAnim(boolean up, Object self) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (up) {
                sChargeUpAt = now;
            } else if (sChargeUpAt != 0L) {
                sChargeUpFor = now - sChargeUpAt;
                sChargeUpAt = 0L;
            }
            View view = self instanceof View ? (View) self : null;
            View root = sContainer == null ? null : sContainer.getRootView();
            String line = (up ? "up" : "down") + " @" + now
                    + " walk=" + chargeAnimUp()
                    + " view=" + (view == null ? "?"
                            : view.getClass().getSimpleName() + (view.isShown() ? " shown" : " hidden"))
                    + " root=" + (root == null ? "no keyguard" : root.getClass().getSimpleName())
                    + " kids=[" + childNames(root) + "]"
                    + (up ? "" : " stayed=" + sChargeUpFor + "ms");
            synchronized (sChargeTrail) {
                sChargeTrail.addLast(line);
                while (sChargeTrail.size() > 8) sChargeTrail.removeFirst();
            }
        } catch (Throwable t) {
            Xp.log(TAG + "charge trail failed: " + t);
        }
    }

    /** The children of a view by class name, a "!" marking the ones that are not shown. */
    private static String childNames(View v) {
        if (!(v instanceof ViewGroup)) return "-";
        ViewGroup g = (ViewGroup) v;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (i > 0) sb.append(',');
            sb.append(c.getClass().getSimpleName());
            if (!c.isShown()) sb.append('!');
        }
        return sb.toString();
    }

    /**
     * What a tap on the cover is reading right now, and what the animation has been doing.
     *
     * Answered through the ordered broadcast rather than the log, which is the channel that
     * works on this build - see the note on the app's own dump.
     */
    private static String chargeAnimReport() {
        StringBuilder sb = new StringBuilder("chargeAnimUp=" + chargeAnimUp()
                + (sChargeUpAt == 0L ? "" : " (up since " + sChargeUpAt + "ms)"));
        synchronized (sChargeTrail) {
            for (String line : sChargeTrail) sb.append('\n').append(line);
        }
        View root = sContainer == null ? null : sContainer.getRootView();
        sb.append("\nroot=").append(root == null ? "no keyguard" : root.getClass().getName());
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                sb.append("\n  ").append(i).append(' ').append(c.getClass().getName())
                        .append(" vis=").append(c.getVisibility())
                        .append(" alpha=").append(c.getAlpha())
                        .append(c.isShown() ? " shown" : " hidden");
            }
        }
        return sb.toString();
    }

    /** The card's track when the running morph began; a different one is what cancels it. */
    private static String sMorphKey = "";

    private static void beginMorph(boolean toCover) {
        long t0 = System.nanoTime();
        if (!CoverMorphLayer.active()) sMorphKey = sCardKey;
        CoverMorphLayer.begin(toCover);
        sMorphBeginNs += System.nanoTime() - t0;
    }

    /**
     * The mini player's pill is already turning into the card (MiniCardMorph); this is only its
     * artwork. With the cover card at the far end it flies there. With lyrics it lands on the
     * card's own thumbnail, which the container morph already carries - nothing to fly.
     */
    private static void beginMiniMorph(boolean toCover, boolean coverEndpoint) {
        long t0 = System.nanoTime();
        if (!CoverMorphLayer.active()) sMorphKey = sCardKey;
        if (!coverEndpoint || !CoverMorphLayer.beginMiniScene(toCover)) CoverMorphLayer.cancel();
        sMorphBeginNs += System.nanoTime() - t0;
    }

    /**
     * For `op cardstate`: main-thread milliseconds of the last few tap toggles, the whole handler
     * and the morph's own start inside it, newest first - where a hitch at the start comes from.
     */
    private static final StringBuilder sToggleCost = new StringBuilder();
    private static long sMorphBeginNs;

    private static void noteToggleCost(String what, long ns) {
        sToggleCost.insert(0, what + "=" + (ns / 100000L) / 10f + "ms(morph "
                + (sMorphBeginNs / 100000L) / 10f + ") ");
        if (sToggleCost.length() > 400) sToggleCost.setLength(400);
        sMorphBeginNs = 0L;
    }

    static String toggleCost() {
        return sToggleCost.toString();
    }

    private static void exitFromTap(String why) {
        long t0 = System.nanoTime();
        exitFromTapNow(why);
        noteToggleCost("out", System.nanoTime() - t0);
    }

    private static void enterFromTap(String why) {
        long t0 = System.nanoTime();
        enterFromTapNow(why);
        noteToggleCost("in", System.nanoTime() - t0);
    }

    /**
     * Back to the plain wallpaper, with the card left standing where it is.
     *
     * sTapSuppressed is what holds it that way. A card being up is exactly what the module reads
     * as "the cover belongs here", so without it the next metadata event would put the cover
     * straight back. It lasts as long as the music does: not a track change, not the screen going
     * off, and not an unlock and a lock again - it is the last session going away that clears it,
     * and the next thing the user plays then starts from the cover as it always did.
     */
    private static void exitFromTapNow(String why) {
        int from = LockLyrics.wantsAttached()
                ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
        if (MiniPlayerRuntime.prepareSceneExit()) {
            beginMiniMorph(false, from == CoverMorphRoute.COVER);
        } else if (CoverMorphRoute.shouldMorph(from, CoverMorphRoute.NORMAL)) {
            beginMorph(false);
        } else {
            CoverMorphLayer.cancel();
        }
        sTapSuppressed = true;
        Xp.log(TAG + why + ": leaving cover mode");
        MotionTrace.start("toggle-out");
        setCoverEnabled(false, true);
    }

    /**
     * Back into cover mode, through the normal path rather than by re-pushing whatever was last
     * composed: the track may well have moved on while the cover was off.
     */
    private static void enterFromTapNow(String why) {
        // Guarded on the two questions onMediaUpdate asks before it does anything.
        int to = LockLyrics.willAttachOnEntry()
                ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
        if (sAuto && sCardShowing && MiniPlayerRuntime.prepareSceneEntry()) {
            beginMiniMorph(true, to == CoverMorphRoute.COVER);
        } else if (sAuto && sCardShowing
                && CoverMorphRoute.shouldMorph(CoverMorphRoute.NORMAL, to)) {
            beginMorph(true);
        } else {
            CoverMorphLayer.cancel();
        }
        sTapSuppressed = false;
        sTrackKey = "";
        Xp.log(TAG + why + ": expanding into cover mode");
        MotionTrace.start("toggle-in");
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
        // A frame asked for by kickCardFrame() may still be on its way, and it will find nothing
        // to assert into. Cleared here so the next cover, whose guard is a different card view,
        // is not told a frame is already posted when there is none.
        sCardFramePosted = false;
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

    /** The OEM depth interactor's own switches, read by reflection. */
    private static String depthFlags() {
        Object o = sDepthInteractor;
        if (o == null) return "interactor not seen yet";
        StringBuilder sb = new StringBuilder();
        for (String f : new String[] {"depthEffectEnableInner", "depthVideoEnable",
                "isActualDisplayDepth", "isDepthFileParsing"}) {
            try {
                java.lang.reflect.Field fl = o.getClass().getDeclaredField(f);
                fl.setAccessible(true);
                sb.append(f).append('=').append(fl.get(o)).append(' ');
            } catch (Throwable t) {
                sb.append(f).append("=? ");
            }
        }
        return sb.toString();
    }

    /** What decides whether a view reaches the screen: its own state and its parents'. */
    private static String layerState(View v) {
        if (v == null) return "absent";
        StringBuilder sb = new StringBuilder();
        sb.append("vis=").append(v.getVisibility()).append(" a=").append(r2(v.getAlpha()))
                .append(" ta=").append(r2(v.getTransitionAlpha()))
                .append(" shown=").append(v.isShown())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        if (v instanceof ImageView) {
            android.graphics.drawable.Drawable d = ((ImageView) v).getDrawable();
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                sb.append(" bmp=").append(b == null ? "null" : b.getWidth() + "x" + b.getHeight()
                        + "@" + Integer.toHexString(System.identityHashCode(b)));
            }
        }
        int depth = 0;
        for (android.view.ViewParent p = v.getParent(); p instanceof View && depth < 3; p = p.getParent(), depth++) {
            View pv = (View) p;
            sb.append(" <").append(idName(pv)).append(" vis=").append(pv.getVisibility())
                    .append(" a=").append(r2(pv.getAlpha()));
        }
        return sb.toString();
    }

    private static View findDeductedImageView() {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getContext().getResources()
                .getIdentifier("deducted_image_view", "id", "com.android.systemui");
        return id == 0 ? null : v.getRootView().findViewById(id);
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
     * The last few things that happened to the media card, as `op card` prints them.
     *
     * The card disappearing has three unrelated causes and the module can only see one of them
     * directly. The OEM dropping its media data arrives here, at onCardChanged(null), and is
     * recorded. A third party hiding the VIEW - HyperLight's music capsule rewrites
     * MiuiMediaHeaderView's VISIBLE into GONE, measured 2026-09-21 - never reaches any hook of
     * ours at all, so it leaves no event: what names it is the live snapshot `op card` takes
     * before printing this, where our own state still says the card is up while the view on
     * screen is GONE. A player dying shows up as the first with no track change before it.
     *
     * Nothing here hides or shows anything. Cover mode follows the card (see applyCardState);
     * the card follows the OEM.
     */
    private static final String[] sCardLog = new String[10];
    private static int sCardLogN;

    private static String visName(int v) {
        return v == View.VISIBLE ? "VISIBLE" : v == View.INVISIBLE ? "INVISIBLE" : "GONE";
    }

    /** One line about the card right now: our state, and the view as it stands. */
    private static String cardSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("auto=").append(sAuto).append(" cover=").append(sCoverMode)
          .append(" known=").append(sCardKnown).append(" showing=").append(sCardShowing)
          .append(" forced=").append(sCardForced)
          .append(" token=").append(sCardToken != null)
          .append(" key=").append(sCardKey == null || sCardKey.isEmpty() ? "-" : sCardKey);
        View c = sCardGuarded;
        if (c == null) {
            try {
                c = findSysuiView("mi_media_controls");
            } catch (Throwable ignored) {
            }
        }
        if (c == null) {
            sb.append(" view=none");
            return sb.toString();
        }
        sb.append(" view=").append(visName(c.getVisibility()))
          .append(" shown=").append(c.isShown())
          .append(" attached=").append(c.isAttachedToWindow())
          .append(" alpha=").append(r2(c.getAlpha()))
          .append(" h=").append(c.getHeight());
        // Whoever is actually keeping it off screen, named. isShown() is false as soon as any
        // ancestor is not VISIBLE, and which one it is decides whether this is the OEM's own
        // layout or somebody else's setVisibility.
        for (android.view.ViewParent vp = c.getParent(); vp instanceof View;
             vp = ((View) vp).getParent()) {
            View v = (View) vp;
            if (v.getVisibility() != View.VISIBLE) {
                sb.append(" hiddenBy=").append(idOf(v)).append('=')
                  .append(visName(v.getVisibility()));
                break;
            }
        }
        return sb.toString();
    }

    private static void noteCard(String what) {
        int n = ++sCardLogN;
        sCardLog[(n - 1) % sCardLog.length] = "#" + n
                + "@" + (android.os.SystemClock.uptimeMillis() / 100) / 10f + "s "
                + what + " " + cardSnapshot();
    }

    /** The card's live state, then what has happened to it. Read by `op card`. */
    static String cardHistory() {
        StringBuilder sb = new StringBuilder("card now: " + cardSnapshot());
        sb.append(" | events=").append(sCardLogN);
        int keep = Math.min(sCardLogN, sCardLog.length);
        for (int i = 0; i < keep; i++) {
            String line = sCardLog[(sCardLogN - keep + i) % sCardLog.length];
            if (line != null) sb.append(" | ").append(line);
        }
        return sb.toString();
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
        // Against the track the morph started on, not sTrackKey: a tap into cover mode clears
        // that, the card is rebound as the cover goes up, and every entry was cancelled a couple
        // of frames in - then begun again from the thumbnail by the entry's second call.
        if (!showing || (CoverMorphLayer.active() && !sameTrack(sCardKey, sMorphKey))) {
            CoverMorphLayer.cancel();
        }
        Xp.log(TAG + "media card " + (showing ? "-> " + sCardKey : "gone"));
        noteCard(showing ? "OEM says up" : "OEM says gone (waiting " + CARD_GONE_MS + "ms)");
        main().removeCallbacks(sCardGone);
        if (!sAuto) {
            sCardShowing = showing;
            MiniPlayerRuntime.refresh();
            return;
        }
        if (showing) {
            sCardShowing = true;
            MiniPlayerRuntime.refresh();
            applyCardState();
        } else {
            main().postDelayed(sCardGone, CARD_GONE_MS);
        }
    }

    private static final Runnable sCardGone = new Runnable() {
        @Override
        public void run() {
            sCardShowing = false;
            noteCard("gone stands, cover mode off");
            MiniPlayerRuntime.refresh();
            applyCardState();
        }
    };

    /** Cover mode is on exactly when the card is up. Idempotent, so it is safe to re-run. */
    private static void applyCardState() {
        // Nothing observed yet is not the same as nothing there: a SystemUI restart must not
        // tear down a cover that is on the phone just because the card hook has not fired for
        // the first time. That wait has to end somewhere though, and it did not - which left
        // the wallpaper on the last album for good. See releaseUnobservedCover().
        if (!sAuto) return;
        if (!sCardKnown) {
            releaseUnobservedCover(activeSessions());
            return;
        }
        if (!sCardShowing) {
            sTrackKey = "";
            // The card going away is what clears a tap-dismissed cover - but only when the music
            // went with it.
            //
            // Unlocking takes the card off the keyguard and the next lock puts it back, and that
            // round trip arrives here exactly as a dismissal does. Clearing on it unconditionally
            // is why a cover the user had just tapped away came back on its own: unlock, lock, and
            // the card returning read as a new session that had never been decided about. The
            // decision is meant to last as long as the music does.
            //
            // An EMPTY session list is the proof that the music itself has ended - a card cannot
            // exist without a session behind it - and it is the same evidence
            // releaseUnobservedCover() acts on. A list that cannot be read proves nothing either
            // way and leaves the decision standing; so does a list that still has something in it.
            if (sTapSuppressed) {
                List<MediaController> sessions = activeSessions();
                if (sessions != null && sessions.isEmpty()) {
                    sTapSuppressed = false;
                    Xp.log(TAG + "no session left: the tapped-away cover is forgotten");
                }
            }
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

    /**
     * Ends the wait a cover restored from disk is parked in when no card is ever observed.
     *
     * The saved cover comes back before any card can, and the guard above refuses to act while
     * `sCardKnown` is false. That is right for as long as a card is on its way - a restart
     * rebuilds the card and the hook fires then - but it is not a wait that could ever end if
     * nothing plays again in this process: the hook never fires, the guard has no second chance,
     * and the wallpaper keeps the last album until the user happens to start some music. Which
     * is what a SystemUI restart during playback, followed by the music stopping, left behind.
     *
     * A card cannot exist without a media session behind it, so an EMPTY list is proof the card
     * is gone. Neither of the other two answers is proof of anything and both leave the cover
     * alone: a list that could not be read, and a non-empty one - the ordinary wait.
     */
    private static void releaseUnobservedCover(List<MediaController> sessions) {
        if (sCardKnown || !sCoverMode || sessions == null || !sessions.isEmpty()) return;
        Xp.log(TAG + "cover restored with no media session at all - leaving cover mode");
        sTrackKey = "";
        sTapSuppressed = false;
        setCoverEnabled(false, true);
    }

    /** The sessions the system holds right now, or null when the question could not be asked. */
    private static List<MediaController> activeSessions() {
        Context ctx = sAppCtx;
        if (ctx == null) return null;
        try {
            MediaSessionManager msm = sMsm != null ? sMsm
                    : (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return msm == null ? null : msm.getActiveSessions(null);
        } catch (Throwable t) {
            Xp.log(TAG + "active session read failed: " + t);
            return null;
        }
    }

    /**
     * Every clickable view on the keyguard, with whatever names it into something recognisable.
     *
     * For finding the media card's transport buttons: the whole point of hooking them is to hear
     * the press ~0.8s before the player admits a track changed, and the only reliable way to name
     * them across HyperOS builds is to look at what is actually there. Content descriptions are
     * the most durable handle - they are what the accessibility layer reads out, so they survive
     * the resource-id renames that R8 and OEM reskins do not.
     */
    private static String dumpClickables() {
        try {
            View root = sContainer == null ? null : sContainer.getRootView();
            if (root == null) return "no keyguard view - is the lock screen up?";
            StringBuilder sb = new StringBuilder("=== clickable views on the keyguard ===");
            collectClickable(root, sb, new int[] {0});
            return sb.toString();
        } catch (Throwable t) {
            return "clickable dump failed: " + Log.getStackTraceString(t);
        }
    }

    private static void collectClickable(View v, StringBuilder sb, int[] n) {
        if (n[0] > 60) return;
        if (v.isClickable() || v.hasOnClickListeners()) {
            n[0]++;
            int[] xy = new int[2];
            try {
                v.getLocationOnScreen(xy);
            } catch (Throwable ignored) {
            }
            sb.append('\n').append(v.getClass().getName())
                    .append(" id=").append(idName(v))
                    .append(" desc=").append(v.getContentDescription())
                    .append(" at ").append(xy[0]).append(',').append(xy[1])
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                    .append(v.isShown() ? "" : " (hidden)");
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectClickable(g.getChildAt(i), sb, n);
        }
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "none";
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /**
     * What every active session publishes about its play queue.
     *
     * The question behind it: the player takes 0.7-1s to say a track changed, and the only way to
     * beat that is to know what is coming BEFORE it is asked for. A queue with artwork on its
     * items is what makes that possible; a queue without one, or no queue at all, means a player
     * this can never help. Measured per player rather than assumed - see `op queue`.
     */
    private static String dumpQueues() {
        try {
            return dumpQueuesInner();
        } catch (Throwable t) {
            return "queue dump failed: " + Log.getStackTraceString(t);
        }
    }

    private static String dumpQueuesInner() {
        List<MediaController> cs = activeSessions();
        if (cs == null) return "sessions could not be read";
        StringBuilder sb = new StringBuilder("=== play queues ===\nprefetch: "
                + Prefetch.describe());
        for (MediaController c : cs) {
            sb.append('\n').append(c.getPackageName()).append(": ");
            List<android.media.session.MediaSession.QueueItem> q;
            try {
                q = c.getQueue();
            } catch (Throwable t) {
                sb.append("getQueue threw ").append(t);
                continue;
            }
            if (q == null || q.isEmpty()) {
                sb.append("no queue");
                continue;
            }
            long active = -1L;
            try {
                android.media.session.PlaybackState ps = c.getPlaybackState();
                if (ps != null) active = ps.getActiveQueueItemId();
            } catch (Throwable ignored) {
            }
            sb.append(q.size()).append(" items, active id=").append(active);
            int at = -1;
            for (int n = 0; n < q.size(); n++) {
                if (q.get(n).getQueueId() == active) {
                    at = n;
                    break;
                }
            }
            sb.append(" (index ").append(at).append(')');
            // The current item and the one after it: what a prefetch would have to work from.
            for (int n = Math.max(0, at); n < Math.min(q.size(), Math.max(0, at) + 2); n++) {
                android.media.MediaDescription d = q.get(n).getDescription();
                sb.append("\n  [").append(n).append("] id=").append(q.get(n).getQueueId())
                        .append(' ');
                if (d == null) {
                    sb.append("no description");
                    continue;
                }
                sb.append('"').append(d.getTitle()).append('"');
                sb.append(" icon=");
                android.graphics.Bitmap ib = null;
                try {
                    ib = d.getIconBitmap();
                } catch (Throwable ignored) {
                }
                if (ib != null) sb.append(ib.getWidth()).append('x').append(ib.getHeight());
                else sb.append("none");
                sb.append(" iconUri=").append(d.getIconUri());
                sb.append(" mediaId=").append(d.getMediaId());
                // The three fields a by-name lyric search needs, and where the only one of them
                // that has no field of its own could be hiding. Printed rather than assumed:
                // whether the read-ahead can ask the by-name half at all is decided entirely by
                // whether the player fills these in, and that cannot be reasoned about.
                sb.append("\n      subtitle=").append(d.getSubtitle())
                        .append(" description=").append(d.getDescription());
                android.os.Bundle ex = null;
                try {
                    ex = d.getExtras();
                } catch (Throwable ignored) {
                }
                if (ex == null || ex.isEmpty()) {
                    sb.append(" extras=none");
                } else {
                    sb.append(" extras={");
                    for (String k : ex.keySet()) {
                        Object v = ex.get(k);
                        String s = v == null ? "null" : v.toString();
                        sb.append(k).append('=')
                                .append(s.length() > 60 ? s.substring(0, 60) + "..." : s)
                                .append(' ');
                    }
                    sb.append('}');
                }
            }
        }
        return sb.toString();
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
                        // The one moment the list is handed over for free, and the only evidence
                        // there is while the card hook has still not fired in this process. The
                        // last session ending is what the card going away looks like from here.
                        releaseUnobservedCover(controllers);
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
                        // Every track change re-reads the state rather than trusting that the
                        // last callback of a skip was the one that says where it ended up.
                        MediaController w = sWatched;
                        if (w != null) updateCoverCardPlayback(w.getPlaybackState());
                        if (sCoverWanted) attachCover();
                        onMediaUpdate();
                    }

                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        LockLyrics.onPlaybackState(state);
                        updateCoverCardPlayback(state);
                        MiniPlayerRuntime.refresh();
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
        updateCoverCardPlayback(c == null ? null : c.getPlaybackState());
        MiniPlayerRuntime.refresh();
        onMediaUpdate();
    }

    private static final Runnable sRecheckPlayback = new Runnable() {
        @Override
        public void run() {
            MediaController w = sWatched;
            updateCoverCardPlayback(w == null ? null : w.getPlaybackState());
        }
    };

    private static void updateCoverCardPlayback(PlaybackState state) {
        int s = state == null ? PlaybackState.STATE_NONE : state.getState();
        // A skip passes through SKIPPING/BUFFERING/CONNECTING on its way to the next track. The
        // card dips for it on purpose, but nothing promises the PLAYING that ends it reaches us -
        // a fast run of skips left the card small while the music played. So a state like that
        // asks the session again shortly, until it has settled somewhere.
        boolean passing = s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_CONNECTING
                || s == PlaybackState.STATE_SKIPPING_TO_NEXT
                || s == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || s == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
        main().removeCallbacks(sRecheckPlayback);
        if (passing) main().postDelayed(sRecheckPlayback, 400L);
        boolean playing = s == PlaybackState.STATE_PLAYING;
        if (sCoverCardPlaying == playing) return;
        sCoverCardPlaying = playing;
        CoverCardLayer.playback(playing);
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

    /**
     * Whether two track keys name the same track.
     *
     * The package and the title have to match outright: they are the two fields every shape of
     * the key agrees on, and they are what actually tells two tracks apart. (The key comes in two
     * shapes - the card's own is pkg|song|artist, the session fallback is pkg|title|artist|album -
     * and the album field is null in one of them and not in the other, so it cannot be compared
     * at all.)
     *
     * The artist is compared too, but only as far as one being the other with something appended.
     * That is the shape the movement takes: bilibili rewrites its own artist to
     * "...·后台听视频省流量" when the video leaves the foreground. Anything more than an appended
     * marker is left to mean a different track, which costs nothing here - a genuinely different
     * artist would have moved the title as well.
     *
     * A key too short to carry a title answers no, which leaves the plain comparison the callers
     * have already made in charge.
     */
    static boolean sameTrack(String a, String b) {
        String pa = keyField(a, 0), pb = keyField(b, 0);
        String ta = keyField(a, 1), tb = keyField(b, 1);
        if (pa.isEmpty() || !pa.equals(pb)) return false;
        if (ta.isEmpty() || !ta.equals(tb)) return false;
        String aa = keyField(a, 2), ab = keyField(b, 2);
        return aa.equals(ab) || aa.startsWith(ab) || ab.startsWith(aa);
    }

    /** The nth |-separated field of a track key, or "" when the key does not reach that far. */
    private static String keyField(String key, int n) {
        if (key == null) return "";
        int from = 0;
        for (int i = 0; i < n; i++) {
            from = key.indexOf('|', from);
            if (from < 0) return "";
            from++;
        }
        int end = key.indexOf('|', from);
        return end < 0 ? key.substring(from) : key.substring(from, end);
    }

    /** The session's track title, which is what a queue item can be matched against. */
    private static String titleOf(MediaController c) {
        if (c == null) return null;
        try {
            MediaMetadata md = c.getMetadata();
            return md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        } catch (Throwable t) {
            return null;
        }
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
        MiniPlayerRuntime.refresh();
        if (!sAuto || !sCardShowing) return;
        // The user tapped the cover away and the card is still up. The one case where "there is
        // a card" must not mean "put the cover back".
        if (sTapSuppressed) return;
        String key = sCardKey.isEmpty() ? trackKey(sWatched) : sCardKey;
        // One track, several keys. The string itself changes under a track that has not: the
        // card's own key is pkg|song|artist and the session fallback appends the album, so the
        // moment the card is torn down and rebuilt - which is what a wake from the AOD is - the
        // key loses its last field and comes back with it, twice in two frames. Bilibili rewrites
        // the artist on top of that, appending its background-audio marker when the video leaves
        // the foreground. Every one of those read as a track change: a fresh push, and the one
        // that answered it was the media card's small thumbnail, so the cover came up soft on the
        // second lock and every lock after it. Same track means same package and same title - the
        // two fields all three shapes agree on - and then there is nothing for the cover to do.
        if (sCoverMode && (key.equals(sTrackKey) || sameTrack(key, sTrackKey))) {
            // The lyric is not so sure. A provider module cannot write its lyric until the
            // player has told it what is playing, so the payload lands on a session this has
            // already settled as the same track - and this return was the only thing between it
            // and the re-read that would pick it up. LockLyrics.onTrack was reachable from here
            // and nowhere else, so with the cover on, a module that was a second late lost the
            // song to whatever the network had found in the meantime, for the whole song.
            //
            // Its same-track path costs a getPlaybackState and a metadata read, and does nothing
            // at all unless the session is carrying a payload that song has not been read
            // against - each payload once, three per track. Cheap enough to reach from a path
            // that a title-in-the-metadata player runs on every sung line.
            LockLyrics.onTrack(key, sWatched);
            return;
        }
        sTrackKey = key;
        long ctNow = android.os.SystemClock.uptimeMillis();
        // Still waiting on the artwork for the previous one means this press lands on top of it:
        // same burst, and the clock keeps running from where it started.
        // The time limit matters as much as the pending artwork: a push that never found any art
        // leaves sCtArt at 0 for good, and without a window the next track change an hour later
        // would still count itself as part of that burst.
        if (sCtTrack != 0L && sCtArt == 0L && ctNow - sCtTrack < 2500L) {
            sCtSkips++;
        } else {
            sCtBurst = ctNow;
            sCtSkips = 0;
        }
        sCtTrack = ctNow;
        sCtArt = 0L;
        sCtCheckMs = 0L;
        sCtTries = 0;
        Xp.log(TAG + "card track: " + key);
        // Started here rather than once the cover has settled, so the fetch overlaps the
        // transition instead of following it.
        LockLyrics.onTrack(key, sWatched);
        // What is coming after this one, for the next press. Always, not only when the cover is
        // on: the queue is what makes a press answerable at all, and reading it costs nothing
        // when it has not moved.
        Prefetch.onTrack(sWatched);
        // The player has caught up with a press this already acted on, and the cover on screen is
        // the right one. Pushing again would compose the same picture and fade it over itself.
        if (sCoverMode && Prefetch.wasPredicted(titleOf(sWatched))) {
            Xp.log(TAG + "cover already up from the press, no second push");
            return;
        }
        if (sCoverMode) CoverPush.pushArtAsync(true, true);
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
        CoverPush.pushArtAsync(on, on && fresh);
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
        refreshSysReads();
        return sSysLocked;
    }

    /**
     * Doors for {@link ShadeLayer}, which is a file of its own on purpose - the shade port is
     * large enough that folding it in here would make the "every hook installs on its own terms"
     * rule unauditable.
     *
     * Both the keyguard answers are exposed together rather than one being picked here, because
     * the two disagree and which one the shade should use is the question the port turns on:
     * {@link #onKeyguardNow} is the clock container being shown, and the comment above
     * {@link #keyguardShowing} records that it reads VISIBLE with the phone UNLOCKED and an app
     * in the foreground. The shade is an unlocked-only feature, so it must gate on the
     * KeyguardManager answer, not on the container.
     */
    static boolean keyguardLocked() {
        return keyguardShowing();
    }

    static boolean clockContainerShown() {
        return onKeyguardNow();
    }

    static boolean coverModeOn() {
        return sCoverMode;
    }

    /**
     * Tells the wallpaper process to put the cover on the DESKTOP wallpaper, or take it off.
     *
     * The notification shade's glass samples what is behind its window, and the cover the shade
     * layer draws is inside it - so nothing drawn there can ever be what the cards blur. The lock
     * screen's cards show the album art because the art IS the wallpaper; this is the same answer
     * for the desktop, and it is why the ask is "swap the wallpaper" rather than "blur the
     * layer".
     *
     * Sent on change and then repeated while it is on, because the message that ends it is also
     * the only thing that stops it: the wallpaper side expires the swap on its own if it stops
     * hearing from here, and a lost "off" would otherwise leave the home screen showing an album
     * cover.
     */
    static void shadeArtToWallpaper(boolean on) {
        final Context ctx = sAppCtx;
        if (ctx == null) return;
        Intent out = CoverPush.wallpaperIntent("shadeart");
        out.putExtra("on", on);
        ctx.sendBroadcast(out);
    }

    /**
     * Whether the module is logging its per-frame traces.
     *
     * The shade layer traces one line per 5% of a pull-down plus every frame for the forty after
     * a finger lifts - about sixty lines per gesture, which is the right amount while working on
     * the hand-off and far too much to leave running. Off unless asked for with the `verbose`
     * op.
     */
    static boolean verbose() {
        return sVerbose;
    }

    /**
     * Cover mode is leaving but has not finished.
     *
     * sCoverMode goes false at the START of the exit - the clock springs back and the wallpaper
     * fades out over ~665ms after it - so anything that tears down on `!sCoverMode` alone does it
     * a fifth of a second early. The module already pairs the two in its own guards; the shade
     * layer is one more place that has to.
     */
    static boolean releasing() {
        return ClockCollapse.exiting();
    }

    /**
     * How long one of these two answers is reused before the system is asked again.
     *
     * Both are binder calls into system_server - `isKeyguardLocked()` on WindowManagerService
     * and `isInteractive()` on PowerManagerService - and the card assert makes both of them, on
     * every frame of a transition, and then the card's own pre-draw makes them a second time in
     * the same frame. Measured with the cost probe: a steady 230us of every frame's placement,
     * against 30us for the clock's whole pre-draw, with the two calls unchanged in between.
     *
     * 150ms is short enough that nothing can be seen through it - the questions are "is this card
     * on the lock screen and is the screen lit", and both are invalidated outright by the
     * broadcasts that answer them (see `lifecycle`), so the moments that matter do not wait for
     * the timer at all. What the timer covers is everything in between, which is all frames.
     */
    private static final long SYS_READ_MS = 150L;
    private static long sSysReadAt;
    private static boolean sSysLocked;
    private static boolean sSysInteractive;

    private static void refreshSysReads() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sSysReadAt < SYS_READ_MS) return;
        sSysReadAt = now;
        // Written before the first question is asked, so a throw inside one of them leaves the
        // other's answer fresh rather than re-reading both on the next frame.
        sSysLocked = readKeyguardLocked();
        sSysInteractive = readInteractive();
    }

    /** The next caller asks the system again. Called from the broadcasts that change the answers. */
    private static void forgetSysReads() {
        sSysReadAt = 0L;
    }

    private static boolean readKeyguardLocked() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    sAppCtx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readInteractive() {
        try {
            PowerManager pm = (PowerManager) sAppCtx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
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
     *
     * What it reports is not acted on the moment it reports it: a tap on the cover is armed and
     * fires only once it can no longer be the first half of a double tap. See armLockTap.
     */
    private static void feedTap(MotionEvent ev) {
        if (ev == null) return;
        feedTwoFingerTap(ev);
        if (!sTapToggle) return;
        // A gesture the system took away is never going to produce the second tap.
        if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            cancelPendingTap("gesture cancelled");
        }
        if (sTapDetector == null) {
            Context c = sAppCtx;
            if (c == null) return;
            // Constructed from inside the dispatch, so the looper it picks up is the one the
            // lock screen draws on, which is where the callback has to land.
            sTapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    armLockTap(e.getRawY());
                    return false;
                }

                /**
                 * Fired on the second DOWN, which is the whole point: two taps this close are
                 * the user asking the lock screen to sleep, not asking the cover to leave.
                 */
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    cancelPendingTap("second tap");
                    return false;
                }
            });
        }
        try {
            sTapDetector.onTouchEvent(ev);
        } catch (Throwable ignored) {
        }
    }

    // ---- the two-finger tap: the lock screen's lyrics switch
    /** Where the two fingers went down, and when; 0 = no candidate in progress. */
    private static long sTwoDownAt;
    private static float sTwoX0, sTwoY0, sTwoX1, sTwoY1;
    /** Replaced by the framework's own slop as soon as there is a context to ask. */
    private static float sTwoSlop = 48f;
    /** The system cancelled this gesture. Watched on, not given up - see ACTION_CANCEL below. */
    private static boolean sTwoCancelled;
    /** Diagnostics for `op twotap`: how many two-finger downs were seen, and what became of them. */
    private static int sTwoSeen, sTwoFired;
    private static String sTwoWhy = "nothing yet";
    private static int sTwoMaxPointers;

    /**
     * Two fingers tapped at once toggles the lyrics.
     *
     * Every other gesture on the cover is taken: one tap is the cover itself, two in a row is the
     * OEM's sleep, a long press its lock screen editor. This one is free, and being anywhere on
     * the screen it needs no control of its own.
     *
     * The single-tap detector is not disturbed by it: the lock screen cancels its own gesture the
     * moment the second finger lands, and feedTap passes that cancel on to cancelPendingTap.
     */
    private static void feedTwoFingerTap(MotionEvent ev) {
        if (ev.getPointerCount() > sTwoMaxPointers) sTwoMaxPointers = ev.getPointerCount();
        noteTwoAction(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sTwoDownAt = 0L;
                sTwoCancelled = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                // Not the end of the touch stream here, and not a reason to drop the candidate.
                // The lock screen cancels as soon as the second finger lands - measured trail
                // DdXuU, with no move in it at all (2026-09-16) - and then delivers the real
                // POINTER_UP and UP to this window anyway. Giving up on the cancel is what made
                // all eight two-finger taps of that recording do nothing. A gesture the system
                // has genuinely taken away is kept from firing by the slop and the long-press
                // timeout below, both of which still apply, and by the next DOWN clearing this.
                if (sTwoDownAt != 0L) sTwoCancelled = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() != 2) {
                    // A third finger: not this gesture.
                    sTwoDownAt = 0L;
                    sTwoWhy = "a third finger (" + ev.getPointerCount() + ")";
                    break;
                }
                sTwoSeen++;
                sTwoWhy = "two fingers down";
                sTwoCancelled = false;
                sTwoDownAt = ev.getEventTime();
                sTwoX0 = ev.getX(0);
                sTwoY0 = ev.getY(0);
                sTwoX1 = ev.getX(1);
                sTwoY1 = ev.getY(1);
                if (sAppCtx != null) {
                    sTwoSlop = android.view.ViewConfiguration.get(sAppCtx).getScaledTouchSlop()
                            * 1.5f;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (sTwoDownAt == 0L || ev.getPointerCount() < 2) break;
                if (moved(ev.getX(0), ev.getY(0), sTwoX0, sTwoY0)
                        || moved(ev.getX(1), ev.getY(1), sTwoX1, sTwoY1)) {
                    // A pinch, a two-finger swipe, a scroll: not a tap.
                    sTwoDownAt = 0L;
                    sTwoWhy = "moved more than " + Math.round(sTwoSlop) + "px";
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (sTwoDownAt == 0L) break;
                long held = ev.getEventTime() - sTwoDownAt;
                sTwoDownAt = 0L;
                if (held <= android.view.ViewConfiguration.getLongPressTimeout()) {
                    onTwoFingerTap();
                } else {
                    sTwoWhy = "held " + held + "ms, too long";
                }
                break;
            default:
                break;
        }
    }

    /** The last gesture's actions, in order, for `op twotap`. */
    private static final StringBuilder sTwoTrail = new StringBuilder();
    /**
     * The last gesture that had a second finger in it, kept whole. The live trail is overwritten
     * by whatever is touched next, and a single tap on the way to reading it used to take the
     * only record of the gesture being diagnosed with it.
     */
    private static String sTwoTrailLast = "";

    private static void noteTwoAction(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (sTwoTrail.indexOf("d") >= 0) sTwoTrailLast = sTwoTrail.toString();
            sTwoTrail.setLength(0);
        }
        if (a == MotionEvent.ACTION_MOVE && sTwoTrail.length() > 0
                && sTwoTrail.charAt(sTwoTrail.length() - 1) == 'm') {
            return;
        }
        if (sTwoTrail.length() > 40) return;
        sTwoTrail.append(a == MotionEvent.ACTION_DOWN ? "D"
                : a == MotionEvent.ACTION_POINTER_DOWN ? "d"
                : a == MotionEvent.ACTION_MOVE ? "m"
                : a == MotionEvent.ACTION_POINTER_UP ? "u"
                : a == MotionEvent.ACTION_UP ? "U"
                : a == MotionEvent.ACTION_CANCEL ? "X" : "?");
    }

    private static boolean moved(float x, float y, float fromX, float fromY) {
        float dx = x - fromX, dy = y - fromY;
        return dx * dx + dy * dy > sTwoSlop * sTwoSlop;
    }

    /**
     * Swaps the cover and the lyrics on the lock screen, under the same guards as the cover's
     * own tap: only the lock screen itself, not the bouncer, the control centre or a shade
     * pulled down over an unlocked phone, and only while a track is on the card - there is
     * nothing to show without one.
     *
     * It is a view and not a setting: the app's switch and the state file are left alone, and
     * with the switch off the tap does nothing at all. See LockLyrics.toggleByTap.
     */
    private static void onTwoFingerTap() {
        View c = sContainer;
        String no = !screenOn() ? "the screen is off"
                : !keyguardShowing() ? "the keyguard is not showing"
                : c == null ? "no clock container"
                : !c.isShown() ? "the clock container is hidden"
                : bouncerUp() ? "the bouncer is up"
                : controlCenterUp() ? "the control centre is up"
                : chargeAnimUp() ? "the charging animation is up"
                : !sCardKnown ? "the card is not known"
                : !sCardShowing ? "no track on the card"
                : null;
        if (no != null) {
            sTwoWhy = "blocked: " + no;
            return;
        }
        if (!LockLyrics.sEnabled) {
            // Nothing on the lock screen to switch. Answering the gesture by bringing the
            // lyrics back would be writing the app's setting from here, which is the whole of
            // what this gesture stopped doing.
            sTwoWhy = "blocked: the lyrics switch is off";
            return;
        }
        if (!LockLyrics.hasLyrics()) {
            // The cover is the only page a track without lyrics has. Taken as a toggle, the tap
            // would flip the hidden flag with nothing changing, and the next song that does have
            // lyrics would then open on the cover for no reason anyone could see.
            sTwoWhy = "blocked: this track has no lyrics";
            return;
        }
        long t0 = android.os.SystemClock.uptimeMillis();
        if (sCoverMode) {
            int from = LockLyrics.wantsAttached()
                    ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
            int to = LockLyrics.willAttachAfterTapToggle()
                    ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
            if (CoverMorphRoute.shouldMorph(from, to)) {
                beginMorph(to == CoverMorphRoute.COVER);
            }
        }
        LockLyrics.toggleByTap(sTrackKey, sWatched);
        sTwoFired++;
        // How long the swap took. Nothing is written down, so this is its whole cost.
        sTwoWhy = "lyrics " + (LockLyrics.sTapHidden ? "hidden" : "shown") + " in "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms";
    }

    /**
     * Holds a tap on the cover back until it cannot be the first half of a double tap.
     *
     * This decides when the tap runs, not what it does: onLockTap re-reads the screen, the
     * keyguard and the card when it fires, so a screen that went off inside the window drops
     * the tap on its own - which is what covers a build whose double tap window is longer than
     * ours, and every double tap the framework itself did not recognise as one.
     */
    private static void armLockTap(final float y) {
        if (!sTapToggle) return;
        // Decided at the DOWN, because by the time this runs the centre - or the charging
        // animation - may have closed under the guard in onLockTap. See the dispatch hook for
        // the full account.
        if (sGestureOnCentre || sGestureOnCharge) return;
        flushPendingTap();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                sPendingTap = null;
                onLockTap(y);
            }
        };
        sPendingTap = r;
        main().postDelayed(r, TAP_CONFIRM_MS);
    }

    /**
     * Runs the tap that was waiting, because a new one has been armed on top of it.
     *
     * Reaching a second tap at all means the framework decided the first was not half of a
     * double tap - it cancels the waiting one on the second DOWN when it is. So the wait is
     * over: firing it here is what keeps "tap, pause, tap" meaning two toggles rather than the
     * second tap quietly replacing the first.
     */
    private static void flushPendingTap() {
        Runnable r = sPendingTap;
        if (r == null) return;
        sPendingTap = null;
        main().removeCallbacks(r);
        r.run();
    }

    /** A held-back tap that has stopped being one: log what ended it rather than guess later. */
    private static void cancelPendingTap(String why) {
        Runnable r = sPendingTap;
        if (r == null) return;
        sPendingTap = null;
        main().removeCallbacks(r);
        Xp.log(TAG + "held-back cover tap dropped (" + why + ")");
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
        // The control centre covers the same region without hiding the clock, so none of the
        // guards above see it: a tap aimed at a quick toggle must not toggle the cover.
        if (controlCenterUp()) return;
        // And the charging animation, which is a full-screen view over the same region. This is
        // the second half of the pair - a tap that began before the animation was up is caught
        // here, one that began under it was caught at the DOWN.
        if (chargeAnimUp()) return;
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

    static boolean screenOn() {
        refreshSysReads();
        return sSysInteractive;
    }

    static void detachCover() {
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
        // Nothing is owed to a view that is going away: a fade armed for it would otherwise be
        // picked up by whatever the guard sees next, and a wait still outstanding would outlive
        // the cover it was armed for.
        CoverPush.sCoverFadeWaitMs = 0;
        CoverPush.sCoverFadeWaiting = false;
        CoverPush.sCoverFadingOut = null;
        CoverPush.sOwedSwap = null;
        // The view is going, so it shows nothing: the next push owes a fade whatever it carries.
        CoverPush.sShownArtPrint = 0;
        main().removeCallbacks(CoverPush.sCoverFadeTimeout);
        if (iv == null) return;
        iv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    CoverPush.releaseCoverGuard();
                    iv.animate().cancel();
                    ViewGroup p = (ViewGroup) iv.getParent();
                    if (p != null) p.removeView(iv);
                    iv.setImageDrawable(null);
                    // Only the composed one is ours - albumArt() hands back a bitmap the media
                    // session owns, and recycling that would take the card's thumbnail with it.
                    Bitmap b = sCoverBitmap;
                    sCoverBitmap = null;
                    if (b != null) b.recycle();
                    Bitmap fb = sCoverBlurBitmap;
                    sCoverBlurBitmap = null;
                    if (fb != null && fb != b) fb.recycle();
                    sVideoCoverBlurred = false;
                    // The live wallpaper was only hidden because this view was covering it.
                    // Whatever removed the view - an exit, a keyguard rebuild, a failure part
                    // way through - the wallpaper has to come back with it, or the lock screen
                    // is left blank. Off the lock screen that hand-back is deferred rather than
                    // dropped; setVideoSurfacesHidden() says why.
                    View bg = sVideoBg, fg = sVideoFg;
                    sVideoBg = null;
                    sVideoFg = null;
                    CoverPush.setVideoSurfacesHidden(false, bg, fg);
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

    /** The cover's colour, or 0 when there is nothing to take one from. */
    static int coverTint() {
        // The screen check is the AOD one. Cover mode outlives the display going off, and the
        // glyphs are the same TimeViews in the always-on view: the cover's colour describes a
        // picture the AOD is not drawn on, and repainting those glyphs in it is the one way this
        // could make things worse than it found them. `isInteractive` is false in AOD, so the
        // OEM's own colouring stands there.
        if (!sCoverMode || !sScreenOn) return 0;
        int forced = sCoverTintOverride;
        return forced != 0 ? forced : sCoverTint;
    }

    /**
     * The OEM's colour with its hue and saturation replaced by the cover's, or that same colour
     * back when there is no cover to take one from.
     *
     * The system's automatic clock colouring is computed from the wallpaper's palette, and the
     * cover replaces the wallpaper behind SystemUI's back - so the palette arriving here
     * describes a picture the clock is no longer drawn on. What is wanted is a clock in the
     * colour of the picture it is actually over, which is what this puts back. Lightness is
     * deliberately NOT taken from the cover: that half belongs to the system.
     */
    private static int legible(View v, int argb) {
        int tint = coverTint();
        if (tint == 0) return argb;
        float[] cover = new float[3], hsv = new float[3];
        android.graphics.Color.colorToHSV(tint, cover);
        android.graphics.Color.colorToHSV(argb, hsv);
        hsv[0] = cover[0];
        hsv[1] = cover[1];
        int out = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(argb), hsv);
        if (sVerbose) {
            Xp.log(TAG + "colour " + Integer.toHexString(argb) + " -> "
                    + Integer.toHexString(out) + " over tint " + Integer.toHexString(tint)
                    + " " + viewIdOf(v));
        }
        return out;
    }

    /**
     * Whether the always-on display in play is the FULL-SCREEN one - the whole lock screen shown
     * dimmed - as opposed to the plain one that shows only a clock.
     *
     * The OEM's own answer rather than the secure setting: `fullAodEnable()` ANDs the user's
     * switch with the device support, the doze master switch and whether the current wallpaper and
     * template allow it, and any one of those going off is a phone whose AOD is not this mode. The
     * settings are the fallback for a build whose interfaces-manager does not have the impl.
     *
     * Asked once per sleep, from ClockCollapse.keepInAod(), so the reflection is not on any
     * per-frame path.
     */
    static boolean fullAodOn() {
        View c = sContainer;
        if (c != null) {
            try {
                ClassLoader cl = c.getClass().getClassLoader();
                Class<?> iface = Class.forName(
                        "com.miui.interfaces.keyguard.IMiuiFullAodManager", false, cl);
                Class<?> iim = Class.forName(
                        "com.miui.systemui.interfacesmanager.InterfacesImplManager", false, cl);
                Object mgr = iim.getMethod("getImpl", Class.class).invoke(null, iface);
                Object on = mgr == null ? null : Xp.callMethod(mgr, "fullAodEnable");
                if (on instanceof Boolean) return (Boolean) on;
            } catch (Throwable t) {
                // Not logged on every call: a build without the impl would fill the log with it,
                // and the fallback below answers the same question.
            }
        }
        try {
            // The current user's copy, which is the one the keyguard is drawn for.
            android.content.ContentResolver cr = sAppCtx.getContentResolver();
            return android.provider.Settings.Secure.getInt(cr, "full_screen_aod_on", 0) == 1
                    && android.provider.Settings.Secure.getInt(cr, "full_screen_aod_support", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The clock's glass as the screen fell asleep: r, g, b and the fill. See captureAodGlass. */
    private static final float[] sAodCoverGlass = new float[4];
    private static volatile boolean sAodCoverGlassSet;

    /**
     * Remembers what colour and fill the clock's glyphs were in as the screen falls asleep, for a
     * doze that keeps the cover's clock.
     *
     * The doze repaints those glyphs from the wallpaper's palette, and in cover mode the wallpaper
     * is the album art - which is what turned the always-on clock gold, and is why an ordinary
     * doze is held at a neutral instead. A held doze is meant to be the lock screen's clock
     * carried into sleep, so it is held at the lock screen's own values. Called from toAod(), the
     * last moment they are still the lock screen's: the doze inks its own over them a frame later.
     */
    static void captureAodGlass() {
        sAodCoverGlassSet = false;
        for (View root : clockRoots()) {
            for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                try {
                    int rid = root.getContext().getResources()
                            .getIdentifier(id, "id", "com.android.systemui");
                    View t = rid == 0 ? null : root.findViewById(rid);
                    if (t == null || t.getVisibility() != View.VISIBLE) continue;
                    float[] g = (float[]) Xp.getObjectField(t, "glassData");
                    if (g == null || g.length < 42) continue;
                    sAodCoverGlass[0] = g[11];
                    sAodCoverGlass[1] = g[12];
                    sAodCoverGlass[2] = g[13];
                    // glassData[36] carries the fill our own morph last pushed, which is what the
                    // lock screen is drawn with - not sAppliedGlassV, which is the value asked
                    // for rather than the one that landed.
                    sAodCoverGlass[3] = g[36];
                    sAodCoverGlassSet = true;
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Whether the doze clock's colour is ours to hold at all.
     *
     * Two of the three dozes are ours. A doze that KEPT the cover's clock is the lock screen's
     * carried into sleep, so it holds the lock screen's own colour (captureAodGlass). A plain
     * always-on display - the linkage AOD, the classic plugin one - is held at a neutral, because
     * what it would otherwise be painted is the wallpaper's palette and in cover mode the
     * wallpaper is the album art: that is the gold 84869b1 was written for.
     *
     * The third is the FULL-SCREEN doze whose clock was handed back, and its colour is the OEM's:
     * the setting exists to say whether that clock is ours, and with it off there is no reading of
     * ours to hold it at - the neutral is the first ink's lightness and nothing more - which comes
     * out as a flat grey slab where the system had glass over the wallpaper. Reported 2026-09-21:
     * "音乐封面模式开启 + 全屏息屏显示保持小时钟关闭" and the big clock came up grey. Only this
     * one configuration is left alone; the other two are held exactly as before.
     */
    private static boolean aodColourOurs() {
        return ClockCollapse.aodHeld() || !ClockCollapse.aodFullScreen();
    }

    /**
     * Holds the always-on clock's glyphs at the colour they are ours to hold.
     *
     * The colour lives in `glassData[11..13]` and is uploaded from the view's own field when the
     * material is drawn - proved on a dozing keyguard by poking those three indices and watching
     * the whole clock turn red on the next frame. So the FIELD is what has to be held, not the
     * array handed to any one setter: on this build the OEM's palette pass reaches the glyphs by a
     * route that goes through neither `TimeView.setGlassColor` (the tint op proved that one is
     * dead here) nor `View.setMiGlass` (guarding that one still let the clock turn gold). Assert
     * the field every doze frame instead, the way notifY and the depth cut-out are asserted.
     *
     * @return true when a glyph was rewritten and so needs the redraw
     */
    static boolean holdAodColour() {
        if (!sCoverMode || sScreenOn || !aodColourOurs()) return false;
        // A doze that kept the cover's clock is showing the LOCK SCREEN's clock, so it holds the
        // colour and the fill the lock screen had. Read at the moment of sleep, before the doze had
        // inked anything with its own palette. The capture is only ever taken for a held doze, so
        // this also says whether the reading below is the lock screen's or a dead one.
        boolean cover = ClockCollapse.aodHeld() && sAodCoverGlassSet;
        boolean wrote = false;
        for (View root : clockRoots()) {
            for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                try {
                    int rid = root.getContext().getResources()
                            .getIdentifier(id, "id", "com.android.systemui");
                    View t = rid == 0 ? null : root.findViewById(rid);
                    if (t == null || t.getVisibility() != View.VISIBLE) continue;
                    float[] g = (float[]) Xp.getObjectField(t, "glassData");
                    if (g == null || g.length < 42) continue;
                    if (cover) {
                        if (g[11] != sAodCoverGlass[0] || g[12] != sAodCoverGlass[1]
                                || g[13] != sAodCoverGlass[2]) {
                            g[11] = sAodCoverGlass[0];
                            g[12] = sAodCoverGlass[1];
                            g[13] = sAodCoverGlass[2];
                            wrote = true;
                        }
                        if (g[36] != sAodCoverGlass[3]) {
                            g[36] = sAodCoverGlass[3];
                            wrote = true;
                        }
                        if (wrote) t.invalidate();
                        continue;
                    }
                    // The plain always-on display, and a held doze whose glass could not be read at
                    // sleep. Neither has a colour of ours to be held at, and what the OEM would
                    // paint is the wallpaper's palette - the album art, in cover mode - so the
                    // neutral the doze first inks with is held instead. Only its lightness is kept,
                    // so a doze caught late still ends up neutral rather than gold.
                    if (Float.isNaN(sAodGrey)) {
                        sAodGrey = luminance(g[11], g[12], g[13]);
                    }
                    if (g[11] != sAodGrey || g[12] != sAodGrey || g[13] != sAodGrey) {
                        g[11] = sAodGrey;
                        g[12] = sAodGrey;
                        g[13] = sAodGrey;
                        wrote = true;
                    }
                    // And solid. This is the half that was missing: the gold is not a colour at
                    // all, it is the album-art wallpaper showing through a transparent glass
                    // glyph - proved on one doze by the date reading (182,182,182) neutral while
                    // the clock read (203,161,118) gold, the date being the one of the two with no
                    // glass. The doze's own fill is 0, so the backdrop shows; the two seconds of
                    // neutral the user saw at the start were the frames where it was still 1.
                    if (g[36] < 0.999f) {
                        g[36] = 1f;
                        wrote = true;
                    }
                    if (wrote) t.invalidate();
                } catch (Throwable ignored) {
                }
            }
        }
        if (wrote) pushGlassFill(cover ? sAodCoverGlass[3] : 1f);
        return wrote;
    }

    /** One `updateGlassValue` on both clock trees, screen on or off. */
    private static void pushGlassFill(float v) {
        for (View root : clockRoots()) {
            try {
                if (!(root instanceof ViewGroup)) continue;
                View c = ((ViewGroup) root).getChildAt(0);
                if (c != null) Xp.callMethod(c, "updateGlassValue", v);
            } catch (Throwable ignored) {
            }
        }
    }

    /** One line about the doze clock's colour state, readable from `am broadcast`. */
    private static String aodProbe() {
        boolean sbDone = false;
        StringBuilder sb = new StringBuilder("aodprobe cover=" + sCoverMode
                + " screenOn=" + sScreenOn + " grey=" + sAodGrey
                + " aodsmall=" + sAodSmall + " fullAod=" + fullAodOn()
                + " aodFull=" + ClockCollapse.aodFullScreen()
                + " held=" + ClockCollapse.aodHeld()
                + " coverGlass=" + (sAodCoverGlassSet
                        ? sAodCoverGlass[0] + "," + sAodCoverGlass[1] + ","
                          + sAodCoverGlass[2] + " fill=" + sAodCoverGlass[3]
                        : "none")
                + " guardHits=" + sMiGlassGuardHits);
        // The clock's own state, because the whole setting is about where it is drawn: phase,
        // the pose being held, and the two the AOD recorded for the wake to start from.
        sb.append(" | ").append(ClockCollapse.describe());
        // And which route took each of the last few entries, with what it found - a wake that
        // comes out two different ways is only visible here; see ClockCollapse.noteEntry.
        sb.append(" | ").append(ClockCollapse.entries());
        // And where the clock has been placed since it last settled - a pose that moves after
        // the spring has landed is one of these inputs moving; see ClockCollapse.notePose.
        sb.append(" | ").append(ClockCollapse.poses());
        View date = visibleDate();
        sb.append(" | date=").append(date == null ? "none" : geomOf(date));
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            sb.append(" | tg scale=").append(g.getScaleY()).append(" ty=")
              .append(g.getTranslationY()).append(" vis=").append(g.getVisibility());
            // Every view between the clock and the root that is scaling it, named. Which one
            // holds the doze's 0.9516 is the difference between "the whole lock screen is
            // zooming out of the AOD" and "the clock's own container is", and nothing else
            // says: the pre-draw only ever sees the product. See ClockCollapse.aboveScaleY.
            sb.append(" chain=");
            float prod = 1f;
            for (android.view.ViewParent p = g.getParent(); p instanceof View;
                 p = ((View) p).getParent()) {
                View v2 = (View) p;
                prod *= v2.getScaleY();
                if (v2.getScaleY() != 1f || v2.getTranslationY() != 0f) {
                    sb.append(' ').append(idOf(v2)).append("=sy").append(r3(v2.getScaleY()))
                      .append("/ty").append(r1(v2.getTranslationY()));
                }
            }
            sb.append(" product=").append(r3(prod));
            break;
        }
        for (View root : clockRoots()) {
            for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                try {
                    int rid = root.getContext().getResources()
                            .getIdentifier(id, "id", "com.android.systemui");
                    View t = rid == 0 ? null : root.findViewById(rid);
                    if (t == null) continue;
                    float[] g = (float[]) Xp.getObjectField(t, "glassData");
                    if (g == null || g.length < 42) continue;
                    sb.append(" | ").append(id).append(" vis=").append(t.getVisibility())
                      .append(" rgb=").append(g[11]).append(',').append(g[12]).append(',').append(g[13])
                      .append(" fill=").append(g[36]);
                    if (t.getVisibility() == View.VISIBLE && !sbDone) {
                        sbDone = true;
                        sb.append(" ALL=[");
                        for (int j = 0; j < g.length; j++) {
                            if (j > 0) sb.append(',');
                            sb.append(j).append(':').append(g[j]);
                        }
                        sb.append(']');
                        Object info = Xp.getObjectField(root, "mClockStyleInfo");
                        if (info == null) {
                            View clock = ((ViewGroup) root).getChildAt(0);
                            info = Xp.getObjectField(clock, "mClockStyleInfo");
                        }
                        if (info != null) {
                            sb.append(" style=[pri=")
                              .append(Xp.callMethod(info, "getPrimaryColor")).append(" sec=")
                              .append(Xp.callMethod(info, "getSecondaryColor")).append("]");
                        } else {
                            sb.append(" style=none");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
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
    static void measureCover(Bitmap full) {
        try {
            float d = sAppCtx.getResources().getDisplayMetrics().density;
            float topPx = sBandTopPx, botPx = sBandBotPx;
            if (Float.isNaN(topPx) || Float.isNaN(botPx) || botPx - topPx < 8f) {
                topPx = BAND_TOP_DP * d;
                botPx = BAND_BOT_DP * d;
            } else {
                topPx -= BAND_PAD_DP * d;
                botPx += BAND_PAD_DP * d;
            }
            int top = Math.max(0, Math.round(topPx));
            int bottom = Math.min(full.getHeight(), Math.round(botPx));
            if (bottom - top < 8) { sCoverTint = 0; return; }
            int stride = Math.max(1, (bottom - top) / 24);
            int[] row = new int[full.getWidth()];
            long sr = 0, sg = 0, sb = 0;
            int n = 0;
            for (int y = top; y < bottom; y += stride) {
                full.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int x = 0; x < row.length; x += 8) {
                    int c = row[x];
                    sr += (c >> 16) & 0xff;
                    sg += (c >> 8) & 0xff;
                    sb += c & 0xff;
                    n++;
                }
            }
            if (n == 0) { sCoverTint = 0; return; }
            // The plain average of the band, which the mirror-blur has already smoothed: one
            // number describing the picture behind the glyphs rather than any one pixel of it.
            sCoverTint = 0xff000000 | ((int) (sr / n) << 16) | ((int) (sg / n) << 8)
                    | (int) (sb / n);
            Xp.log(TAG + "cover tint #" + Integer.toHexString(sCoverTint) + " over " + n
                    + "px, band " + top + ".." + bottom);
        } catch (Throwable t) {
            sCoverTint = 0;
            Xp.log(TAG + "cover tint failed: " + t);
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
    static void recolorClock() {
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
                        } else if (hasField(c, "mClockStyleInfo")) {
                            Object info = Xp.getObjectField(c, "mClockStyleInfo");
                            Xp.callMethod(c, "updateClockColor",
                                    Xp.callMethod(info, "getPrimaryColor"),
                                    Xp.callMethod(info, "getSecondaryColor"));
                        } else {
                            // Nothing to nudge on this style, and nothing to change either: its
                            // colours are the SystemUI palette business. This used to throw,
                            // one IllegalArgumentException per frame, which is how it was found.
                        }
                    } catch (Throwable t) {
                        Xp.log(TAG + "recolor failed: " + t);
                    }
                }
            }
        });
    }

    /**
     * Every callback queued on the main thread's Choreographer and every animator its
     * AnimationHandler is running, by class - a view asking for frames forever shows up here as
     * the same entry on every read.
     */
    private static String frameCensus() {
        StringBuilder sb = new StringBuilder();
        try {
            Object ch = android.view.Choreographer.getInstance();
            Object[] queues = (Object[]) reflectField(ch, "mCallbackQueues");
            for (int q = 0; q < queues.length; q++) {
                for (Object rec = reflectField(queues[q], "mHead"); rec != null;
                     rec = reflectField(rec, "next")) {
                    Object action = reflectField(rec, "action");
                    sb.append(" q").append(q).append('=').append(censusName(action));
                    Object vri = action == null ? null : reflectField(action, "this$0");
                    if (vri != null && vri.getClass().getName().equals("android.view.ViewRootImpl")) {
                        describeDirty(vri, sb);
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(" choreographer: ").append(t);
        }
        try {
            Object h = Class.forName("android.animation.AnimationHandler")
                    .getMethod("getInstance").invoke(null);
            for (Object cb : (java.util.List<?>) reflectField(h, "mAnimationCallbacks")) {
                if (cb == null) continue;
                sb.append(" anim=").append(censusName(cb));
                if (cb instanceof android.animation.ObjectAnimator) {
                    sb.append(" target=").append(
                            censusName(((android.animation.ObjectAnimator) cb).getTarget()));
                }
                if (cb instanceof android.animation.AnimatorSet) {
                    for (android.animation.Animator a
                            : ((android.animation.AnimatorSet) cb).getChildAnimations()) {
                        sb.append(" child=").append(censusName(a));
                        if (a instanceof android.animation.ObjectAnimator) {
                            sb.append("->").append(censusName(
                                    ((android.animation.ObjectAnimator) a).getTarget()));
                        }
                        sb.append(" running=").append(a.isRunning());
                    }
                }
                if (cb instanceof android.animation.ValueAnimator) {
                    android.animation.ValueAnimator va = (android.animation.ValueAnimator) cb;
                    sb.append(" dur=").append(va.getDuration())
                            .append(" repeat=").append(va.getRepeatCount());
                    Object ls = reflectField(va, "mUpdateListeners");
                    if (ls instanceof java.util.List) {
                        for (Object l : (java.util.List<?>) ls) {
                            sb.append(" upd=").append(censusName(l));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(" animators: ").append(t);
        }
        return sb.length() == 0 ? "nothing queued" : sb.toString();
    }

    /**
     * The window a traversal is queued for, and the deepest views in it that asked for one: the
     * ones marked invalidated (PFLAG_INVALIDATED) or waiting on a layout, with no child that is.
     * A view that invalidates itself from its own draw is the one left standing on every read.
     */
    private static void describeDirty(Object vri, StringBuilder sb) {
        try {
            Object lp = reflectField(vri, "mWindowAttributes");
            if (lp instanceof android.view.WindowManager.LayoutParams) {
                sb.append(" window=").append(((android.view.WindowManager.LayoutParams) lp).getTitle());
            }
            sb.append(" layoutReq=").append(reflectField(vri, "mLayoutRequested"));
            Object root = reflectField(vri, "mView");
            if (root instanceof View) {
                java.util.List<String> hits = new java.util.ArrayList<>();
                collectDirty((View) root, hits);
                sb.append(" dirty=").append(hits);
            }
        } catch (Throwable t) {
            sb.append(" dirty: ").append(t);
        }
    }

    /** @return whether this view or anything under it is dirty */
    private static boolean collectDirty(View v, java.util.List<String> hits) {
        // A view that is not visible keeps its flags from whenever it was hidden, and draws
        // nothing: it cannot be what the window is redrawing for.
        if (v.getVisibility() != View.VISIBLE) return false;
        boolean below = false;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) below |= collectDirty(g.getChildAt(i), hits);
        }
        int flags = 0;
        try {
            Object f = reflectField(v, "mPrivateFlags");
            if (f instanceof Integer) flags = (Integer) f;
        } catch (Throwable ignored) {
        }
        boolean invalidated = (flags & 0x80000000) != 0;
        boolean layout = v.isLayoutRequested();
        if ((invalidated || layout) && !below && hits.size() < 20) {
            String id = "";
            try {
                if (v.getId() != View.NO_ID) id = "#" + v.getResources().getResourceEntryName(v.getId());
            } catch (Throwable ignored) {
            }
            hits.add(v.getClass().getName() + id + (invalidated ? "(inv)" : "") + (layout ? "(lay)" : "")
                    + (v.isShown() ? "" : "(hidden)"));
        }
        return below || invalidated || layout;
    }

    /** A class name, and the outer instance's for an anonymous or inner one. */
    private static String censusName(Object o) {
        if (o == null) return "null";
        String n = o.getClass().getName();
        try {
            Object outer = reflectField(o, "this$0");
            if (outer != null) n += "<" + outer.getClass().getName();
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** A field by name anywhere up the class chain, or null when there is none. */
    private static Object reflectField(Object o, String name) throws IllegalAccessException {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** Whether a class declares this field at all - getObjectField throws when it does not. */
    private static boolean hasField(Object o, String name) {
        try {
            for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
                k.getDeclaredField(name);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Whether the clock this view belongs to is the liquid-glass one.
     *
     * The colour takeover is FOR the glass style and stays there. On every other style the clock
     * is coloured by the SystemUI palette and it is to stay that way: our substitution was
     * replacing a colour the OEM had already worked out with one derived from a single average
     * over the cover, and on a cover with a bright face under a dark border that average reads
     * "dark cover", so the clock comes out white on white. Passing the OEM value straight
     * through is both less machinery and a better answer.
     *
     * Read off the view itself first - the glyphs are TimeViews and carry the flag - and then by
     * walking up to whichever of the clock views answers.
     */
    private static boolean glassStyleFor(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (declaresGlass(p)) return true;
            if (p == sContainer) break;
        }
        // Nothing on the walk answered. Asked of the tree instead: all_in_one is the family whose
        // clock is built this way, and it is the one that answers `time_group`. The date used to
        // be the view that landed here; it is no longer coloured at all. See anchoredStyle().
        return anchoredStyle();
    }

    /**
     * Whether this view is built on the glass TimeView - asked of its CLASS, not of the switch.
     *
     * The first version read `isMiGlassEffectEnable` and returned its VALUE, which is the
     * question "is the glass switched on right now". It is false once the morph has run all the
     * way to solid, which is exactly the state the clock is in when it is collapsed - so the
     * takeover switched itself off for the whole time it was needed and the clock kept the
     * palette's colour. Measured: two frames with the cover's luma forced to 0.95 and to 0.05
     * came out grey-white and grey-white.
     */
    private static boolean declaresGlass(View v) {
        Class<?> c = v.getClass();
        Boolean hit = sGlassClasses.get(c);
        if (hit != null) return hit;
        boolean glass = false;
        for (Class<?> k = c; k != null && !glass; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if ("setGlassColor".equals(m.getName())) { glass = true; break; }
            }
            if (!glass) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if ("isMiGlassEffectEnable".equals(f.getName())) { glass = true; break; }
                }
            }
        }
        sGlassClasses.put(c, glass);
        return glass;
    }

    /**
     * Counter-hook for HyperLight's "统一柔光玻璃" (its SoftGlassMaterialHook).
     *
     * HyperLight hooks {@code View.setMiGlass(float[])} and, for any view its classifier admits,
     * rewrites the whole 42-float MiGlass array to its soft-glass preset - indices 0..36, and
     * [34] and [36] in particular. Index 36 is the liquid-glass channel this module drives
     * through {@code updateGlassValue -> glassData[36]}; rewriting it is what turns the clock
     * from 液态玻璃 into plain glass ("非液态").
     *
     * Its classifier (gu1.o) admits a view whose setMiGlass happens with a
     * notification/shade/control-centre frame on the stack, which is exactly the media-card ->
     * cover-mode transition - which is why the clock comes up liquid after a restart and loses it
     * a moment after music starts, rather than immediately.
     *
     * The crucial asymmetry: HyperLight rewrites the COPY handed to setMiGlass, never the view's
     * own {@code glassData} field, so the field still holds the OEM's liquid values. Handing the
     * field back for the clock's own glyphs is the whole fix.
     *
     * Armed from the clock container's onAttachedToWindow rather than at onPackageLoaded - that
     * event reliably fires after both modules are loaded, so this hook registers AFTER HyperLight's
     * and runs last in the chain, where the last writer to args[0] is what the original receives.
     */
    /** Rec. 709 luminance of a glass colour triple, which is what an even grey has to match. */
    private static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static void armMiGlassGuard() {
        if (sMiGlassGuardArmed) return;
        sMiGlassGuardArmed = true;
        try {
            Xp.hookAll(View.class, "setMiGlass", chain -> {
                Object self = chain.getThisObject();
                if (!(self instanceof View) || !declaresGlass((View) self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                boolean restored = false;
                if (args.length >= 1 && args[0] instanceof float[]) {
                    try {
                        float[] oem = (float[]) Xp.getObjectField((View) self, "glassData");
                        if (oem != null && oem.length >= 42) {
                            args[0] = oem;
                            restored = true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // The always-on displays whose colour is ours (aodColourOurs). The colour pushed
                // here is the OEM's palette, computed from the wallpaper - which in cover mode is
                // our album art - and that palette would repaint the clock a moment after the
                // screen goes off. So the colour is dropped and the first lightness the doze inks
                // with is held instead, which is what holdAodColour() holds too.
                if (sCoverMode && !sScreenOn && aodColourOurs() && args[0] instanceof float[]) {
                    float[] a = (float[]) args[0];
                    if (a.length >= 42) {
                        // Luminance, not max: the palette's gold is (1.0, 0.694, 0.384), whose
                        // max is 1.0 - neutralising on that turns the clock pure white. Its
                        // luminance is 0.736, which is the neutral the doze was inking before the
                        // palette landed (measured 189/255 = 0.741 on screen).
                        float mx = luminance(a[11], a[12], a[13]);
                        if (Float.isNaN(sAodGrey)) sAodGrey = mx;
                        float[] neutral = a.clone();
                        neutral[11] = neutral[12] = neutral[13] = sAodGrey;
                        args[0] = neutral;
                    }
                }
                int hits = ++sMiGlassGuardHits;
                // The first few fires prove which view carries the clock's glass and that the
                // field is there to hand back; the rest are counted rather than spammed.
                if (hits <= 5 || hits % 50 == 0) {
                    Xp.log(TAG + "setMiGlass guard fired (" + hits + ") on "
                            + viewIdOf((View) self) + " restored=" + restored);
                }
                return chain.proceed(args);
            });
            Xp.log(TAG + "HyperLight setMiGlass guard armed");
        } catch (Throwable t) {
            Xp.log(TAG + "HyperLight setMiGlass guard unavailable: " + t);
        }
    }

    /**
     * Asked once per class, because the question is asked constantly.
     *
     * getDeclaredMethod/getDeclaredField answer by THROWING when the member is absent, and the
     * stack trace each throw fills in costs far more than the lookup. This runs from inside the
     * colour setters, which the OEM calls for every glyph on every frame of a collapse - so the
     * first version put dozens of exceptions per frame on the clock's own animation thread and
     * the collapse stuttered. Measured symptom: the entry scaling "not normal", with every
     * number in the log still correct. The answer cannot change while the process lives.
     */
    private static final java.util.HashMap<Class<?>, Boolean> sGlassClasses = new java.util.HashMap<>();

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

    /**
     * Whether the clock style on screen right now has a glass channel to drive at all.
     *
     * The liquid-glass morph is AllInOneBase.updateGlassValue(float) - the OEM's own continuous
     * glass -> solid ramp - and it exists exactly where the glass shader does. A rhombus clock
     * draws vector digits, a doodle clock bitmaps, the oriental and magazine clocks plain text:
     * none of them has anything to drive, and a slider that pretends otherwise is a slider that
     * silently does nothing, which is worse than one that says so.
     *
     * Asked of the live class rather than written down as a list of style names, so a style that
     * gains the channel in a later build is picked up without a change here.
     */
    private static boolean clockHasGlass() {
        for (View root : clockRoots()) {
            View clock = clockViewOf(root);
            if (clock == null) continue;
            for (Class<?> k = clock.getClass(); k != null && k != Object.class;
                 k = k.getSuperclass()) {
                try {
                    k.getDeclaredMethod("updateGlassValue", float.class);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    // --------------------------------------------- the OEM's own translation function

    /** Whether the walk below has been tried, so a build without it is asked once, not per frame. */
    private static boolean sTranslatorTried;
    private static Object sTranslator;
    private static java.lang.reflect.Method sComputeTranslationY;

    /**
     * The translation the OEM is putting on its clock views for a given notifY, from the OEM's
     * own function rather than from a reading of the screen.
     *
     * `ClockTranslationAnimator.computeTranslationY(float)` is what the OEM itself calls - it is
     * the line inside ClockBaseAnimation.notifStateChange that turns the y into the translation
     * its spring then applies. Being a function of y alone, it answers for the frame being placed
     * even though the view has not been written yet, which is exactly what the measurement cannot
     * do: Folme writes after this hook returns, so a measured translation is always one frame
     * old, and compensating with it displaces the date by the OEM's per-frame step.
     *
     * Reachable through fields the module already walks, and null - not throwing - when any of
     * them is missing, so a build that renames one falls back to measuring rather than breaking.
     */
    private static Float oemTranslationY(float y) {
        if (!sTranslatorTried) {
            sTranslatorTried = true;
            View v = sContainer;
            if (v != null) {
                try {
                    Object helper = Xp.getObjectField(v, "mAnimationHelper");
                    Object anim = Xp.getObjectField(helper, "mClockAnima");
                    Object tr = Xp.getObjectField(anim, "clockTranslationAnimator");
                    if (tr != null) {
                        java.lang.reflect.Method m = tr.getClass()
                                .getMethod("computeTranslationY", float.class);
                        m.setAccessible(true);
                        sTranslator = tr;
                        sComputeTranslationY = m;
                        Xp.log(TAG + "oem translation: " + tr.getClass().getName()
                                + ".computeTranslationY reached");
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "oem translation unavailable, measuring instead: " + t);
                }
            }
        }
        java.lang.reflect.Method m = sComputeTranslationY;
        if (m == null) return null;
        try {
            return (Float) m.invoke(sTranslator, y);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reports the whole walk on demand - `--es op oemtrans [--ef y 1200]`.
     *
     * The one-shot log inside oemTranslationY fires on the first placement of a process, which is
     * exactly when logcat is least likely to be attached and, after a restart, a moment that may
     * never come at all. This re-runs it whenever it is asked, and names the step that fails.
     */
    private static void dumpOemTranslation(final float y) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "oemtrans: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                Object helper = null, anim = null, tr = null;
                try {
                    helper = Xp.getObjectField(v, "mAnimationHelper");
                    Xp.log(TAG + "oemtrans: mAnimationHelper = "
                            + (helper == null ? "null" : helper.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: mAnimationHelper FAILED: " + t);
                }
                try {
                    anim = helper == null ? null : Xp.getObjectField(helper, "mClockAnima");
                    Xp.log(TAG + "oemtrans: mClockAnima = "
                            + (anim == null ? "null" : anim.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: mClockAnima FAILED: " + t);
                }
                try {
                    tr = anim == null ? null : Xp.getObjectField(anim, "clockTranslationAnimator");
                    Xp.log(TAG + "oemtrans: clockTranslationAnimator = "
                            + (tr == null ? "null" : tr.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: clockTranslationAnimator FAILED: " + t);
                }
                if (tr == null) { Xp.log(TAG + "oemtrans: chain stops here"); return; }
                try {
                    java.lang.reflect.Method m = tr.getClass()
                            .getMethod("computeTranslationY", float.class);
                    m.setAccessible(true);
                    float oem = (Float) m.invoke(tr, y);
                    View d = visibleDate();
                    int[] loc = new int[2];
                    if (d != null) d.getLocationOnScreen(loc);
                    float layout = d == null ? 0f : layoutScreenY(d);
                    Xp.log(TAG + "oemtrans: computeTranslationY(" + y + ") = " + oem
                            + " | date layoutScreenY=" + r1(layout)
                            + " + oem = " + r1(layout + oem)
                            + " | measured onScreen=" + (d == null ? -1 : loc[1])
                            + " diff=" + (d == null ? Float.NaN : r1(layout + oem - loc[1])));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: computeTranslationY FAILED: " + t);
                }
            }
        });
    }

    /** The view's screen y from the LAYOUT alone - no translation anywhere in the chain. */
    private static float layoutScreenY(View v) {
        float y = 0f;
        for (int guard = 0; guard < 32 && v != null; guard++) {
            y += v.getTop();
            android.view.ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return y;
    }

    /** Rides the same progress as the scale, so one OEM spring drives size and look together. */
    static void applyGlassMorph(float p) {
        if (Float.isNaN(sGlassV0)) return;
        // Not while the screen is off, and this is the one that matters for the AOD. The clock
        // keeps its hold through doze - the re-asserts below put the placement back on the doze
        // layout - but they drove this on the way, and cover mode's glass value is not the one
        // the AOD was inked with: the clock came up system-coloured and turned to the glass
        // tint a moment later, every time. The placement is ours; the colour is not.
        if (!sScreenOn) return;
        float g = sGlassV0 + p * (sGlassV1 - sGlassV0);
        // While the cover is up, the clock's glass belongs at the solid end. The collapse
        // progress p is read from the OEM's notifY, and a single frame of it reading the
        // natural position - a keyguard rebuild, a wake, a notification relayout - drags the
        // glass back toward the transparent end, where the hour glyphs vanish over the cover.
        // The placement recovers on the next driven frame; the glass does not - it freezes at
        // whatever was applied last, and that is exactly the broken clock being reported. So
        // once the entry morph has reached the solid end, the value holds there until cover
        // mode itself goes.
        if (sCoverMode) {
            if (g >= sGlassV1 - 0.01f) sGlassSettled = true;
            if (sGlassSettled) g = sGlassV1;
        }
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
    static View[] clockRoots() {
        View v = sContainer;
        if (v == null) return NO_ROOTS;
        View[] cached = sRootsCache;
        // The pair is remembered for the container it was found in, and only while both halves
        // are still in a window - a rebuilt keyguard re-inflates them and detaches the old ones,
        // and the detach is what says so.
        //
        // ONLY a complete pair is remembered. The two containers are inflated together on a
        // normal build, but "together" is not something this can assume: the first call of a
        // process can land while only the background half exists, and answering that reading
        // from the cache for the rest of the container's life would leave the minute half at
        // full size for ever - the same shape of bug as the doodle that answered "no clock
        // here" once and stayed unscaled. A half answer is therefore not cached, and costs
        // exactly what it costs today.
        if (v == sRootsFor && cached != null && rootsAttached(cached)) return cached;
        View root = v.getRootView();
        java.util.List<View> out = new java.util.ArrayList<>(2);
        for (String id : ROOT_IDS) {
            int resId = resId(root.getResources(), id);
            View c = resId == 0 ? null : root.findViewById(resId);
            if (c != null) out.add(c);
        }
        View[] found = out.toArray(new View[0]);
        if (found.length == ROOT_IDS.length) {
            sRootsCache = found;
            sRootsFor = v;
        } else {
            sRootsCache = null;
            sRootsFor = null;
        }
        return found;
    }

    private static final String[] ROOT_IDS = {"miui_keyguard_clock_container",
            "miui_keyguard_foreground_clock_container"};
    private static final View[] NO_ROOTS = new View[0];
    /** The containers the last resolution found, and the container it found them in. */
    private static View sRootsFor;
    private static View[] sRootsCache;

    private static boolean rootsAttached(View[] roots) {
        for (View r : roots) {
            if (!r.isAttachedToWindow()) return false;
        }
        return true;
    }

    /** A keyguard rebuild re-inflates the clock containers; the pair above is stale from here. */
    private static void forgetClockRoots() {
        sRootsCache = null;
        sRootsFor = null;
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
