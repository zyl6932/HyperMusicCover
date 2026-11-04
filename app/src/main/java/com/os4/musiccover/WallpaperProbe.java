package com.os4.musiccover;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import android.util.Log;

import java.lang.reflect.Method;

import java.lang.reflect.Modifier;


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

    static final String TAG = "[MCWall] ";
    private static final String ACTION = "com.os4.musiccover.WPROBE";
    private static final String PKG = "com.miui.miwallpaper";

    static ClassLoader sCl;
    private static boolean sRegistered;
    static Context sCtx;
    /** The fitted copy of sArt, kept so the GL thread never rescales during a track change. */
    static volatile Bitmap sFitted;
    static volatile Bitmap sFittedOf;
    /**
     * Give the keyguard a texture the size of the SCREEN instead of the size of the wallpaper
     * file. On by default; the `texfit` probe turns it off.
     *
     * MIUI builds the keyguard texture at whatever `WallpaperManager.peekBitmapDimensions()`
     * says, and `ImageGLWallpaper.setupTexture()` allocates it from the bitmap it is handed, so
     * a phone whose lock wallpaper is 2121x4712 uploads 38MB per swap - twice - and our art has
     * to be scaled UP to that size to keep the GL matrix (built from the same dimensions) honest.
     * It also puts the fade over its own threshold, so the swap is a cut. Measured on the phone
     * that reported it: 1.3s to re-fit the wallpaper, then 38MB twice on every swap.
     *
     * The fix the module shipped first rewrote the wallpaper FILE (fitLockWallpaperToScreen in
     * Main), which is what costs the user their depth cut-out: MIUI's subject segmentation is
     * tied to the wallpaper the picker set, and nothing re-analyses a file we wrote ourselves.
     *
     * This does it without touching the file. Both ends of the pair move together:
     *   - the bitmap the upload gets, fitted to the surface, in screenSized() below;
     *   - the rectangle updateMVPMatrix() builds the matrix from, in the hook further down.
     * With those agreeing, the texture is the screen's size and the picture is where it belongs.
     * On a phone whose lock wallpaper IS the screen's size both halves return early and nothing
     * happens at all, which is every phone until someone picks a big picture.
     *
     * sKeyguardTexture is the wallpaper process's own ImageWallpaperRenderer$WallpaperTexture for
     * the KEYGUARD renderer - the desktop wallpaper shares this code and is not ours to resize.
     */
    private static volatile boolean sTexFit = true;
    static volatile Object sKeyguardTexture;
    /**
     * The size each keyguard texture was last uploaded at, so getTextureDimensions can answer for
     * the texture being asked about instead of for whichever screen uploaded most recently.
     * Weak, because these are the OEM's objects and a screen that goes away takes its own with it.
     */
    static final java.util.Map<Object, android.graphics.Rect> sTexSizes =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, android.graphics.Rect>());
    /**
     * Fitted copies of one source, keyed by the surface they were cut for.
     *
     * A map rather than a single bitmap because a foldable asks for more than one size and keeps
     * asking: the inner and outer screens each upload with their own renderer, so a single slot
     * was re-cut on every hand-over. Cleared whole when the source changes, which is the only
     * time these stop being wanted.
     */
    /**
     * Guarded by itself: each screen's renderer fits on its own GL thread, and a retirement
     * clears it from the main thread.
     */
    static final java.util.HashMap<Long, Bitmap> sScreenArts = new java.util.HashMap<>();
    private static Bitmap sScreenArtOf;
    static volatile int sReportedW, sReportedH;

    /**
     * While non-null, this replaces whatever the keyguard renderer would have uploaded as its
     * wallpaper texture. Because it becomes the real wallpaper surface, the clock glass and the
     * card blur sample it too - which is the whole point of coming into this process.
     */
    static volatile Bitmap sArt;
    /** Which of the two cover compositions the current source represents. */
    static volatile boolean sCardMode;
    /** When the running fade-out of the cover began, and how long it runs; 0 when none is. */
    private static volatile long sLeaveAt, sLeaveMs;

    /**
     * SystemUI is showing lyrics over the cover, so the cover is drawn frosted - blurred and
     * darkened - wherever it would be drawn sharp. Applied inside fittedArt(), which every path
     * (upload, getBitmap short-circuit, both fade ends) already reads, so a track change under
     * the lyrics fades frosted to frosted with nothing else knowing. Cleared when the cover goes.
     */
    static volatile boolean sLyricBlur;
    /**
     * The newest thing SystemUI has asked for, which is not the same as what is on screen: a
     * switch waits for a fade in the air before it takes effect. Recorded the moment the message
     * arrives, so a waiting one that has since been overtaken can drop out instead of writing a
     * stale answer back - which is how a track changed at the moment the lyrics arrived could
     * leave the cover sharp for the rest of the song, both sides believing it was blurred.
     */
    static volatile boolean sLyricBlurWant;
    /**
     * When the newest blur decision this process has been given was made, on the clock both
     * processes share. See LockLyrics.putBlurOn.
     *
     * The answer and the cover travel in two broadcasts sent from two different threads over
     * there - the cover is composed and sent on the worker, the answer is decided on the main
     * thread - so the one that arrives last is not the one that was decided last, and the cover
     * is the slow half. Tapping the artwork out of cover mode and quickly back in is exactly
     * that: the push, built before the tap, carries the answer from before it and reaches here
     * after the lyric switch has said the opposite. Taken at face value it undoes the switch, and
     * since both sides then believe they agree nothing re-sends it - a song playing out sharp
     * under its lyrics.
     *
     * Time rather than a count, because a count is only comparable within one SystemUI process:
     * a restarted SystemUI starts over at one, and every decision it makes would look older than
     * what this process already holds. 0 means the message carried no stamp at all - an older
     * SystemUI, which sends the answer on its own.
     */
    static volatile long sBlurSeq;
    /**
     * Every message that could carry a blur decision, and every one that carried a cover.
     *
     * The completion of the fade out of cover mode clears the art and the blur, and it runs a
     * whole crossfade after the message that started it. A tap back in lands inside that window,
     * so the completion has to be able to tell whether the look it belongs to is still the one
     * being asked for. Counted separately because the two halves are cleared for the same reason
     * but not by the same thing: a lyric switch arriving mid-fade owns the blur and not the art.
     */
    static volatile int sMsgSeq, sArtSeq;

    /**
     * One track change, segment by segment, read back with `op timing`.
     *
     * Both processes read the same uptimeMillis clock and SystemUI sends its own marks along with
     * the cover, so the whole path - the card naming a new track, the artwork being waited for,
     * the broadcast, the composition, the upload - subtracts into one timeline here. Everything
     * is a bare field write on a path that already exists; nothing is measured that was not
     * already happening.
     */
    static volatile long sTmT0, sTmBurst, sTmArt, sTmSent, sTmRecv, sTmRead, sTmComposed,
            sTmFrosted, sTmMain, sTmUploaded, sTmCheckMs;
    static volatile long sTmSkip;
    static volatile int sTmTries, sTmSkips, sTmSkipDir;
    static volatile String sTmKind = "";

    /**
     * The keyguard engine, captured so a new track can re-run the texture upload without the
     * process being killed. Its GL work all happens on one HandlerThread; nothing here touches
     * GL directly, it only asks the engine to run its own surface-created path again.
     */
    static volatile Object sKeyguardEngine;

    /**
     * Every keyguard engine built, newest last.
     *
     * A foldable builds one engine per screen, and sKeyguardEngine above holds whichever was
     * constructed last - so on those the cover can be pushed at a screen the user is not looking
     * at, with nothing in the log to say so. Kept as a list first and picked between second,
     * because which one is right is exactly the question the port has to answer.
     */
    static final java.util.List<Object> sKeyguardEngines =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Renderer class names already reported by the diagnostic above; one line each. */
    private static final java.util.Set<String> sUploadNames =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Engine class names seen at construction; one line each. Diagnostic. */
    private static final java.util.Set<String> sEngineNames =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * The desktop wallpaper's engine, captured so its texture can be re-uploaded.
     *
     * Not ours to leave swapped: a failure that strands this one shows the album cover on the
     * user's home screen, so anything that sets it has to have a path that puts it back.
     */
    static volatile Object sDesktopEngine;

    /**
     * The lock screen shows the desktop's still, with no keyguard engine of its own (#84: a
     * carousel applied to both screens). Told by SystemUI (CoverPush.sSharedLock). While it holds
     * and the lock screen is up, the desktop renderer IS the lock screen's renderer: its uploads
     * go through the keyguard branch, cover, fade, frost and all, and the cover is in the
     * wallpaper window where the clock's glass and the cards sample it.
     */
    static volatile boolean sSharedLock;

    /**
     * The desktop engine's own word on the lock screen: its show/hideKeyguardWallpaper calls,
     * which arrive as the keyguard locks and unlocks. Null until the first of them - a process
     * started on the lock screen - and then SystemUI's reading, carried on its messages, fills in.
     */
    static volatile Boolean sDesktopKgShown;

    /**
     * The desktop renderer's last getBitmap was answered with one of ours. Read by the upload
     * that follows it: if the lock screen went in between, that upload is the home screen's,
     * and it is carrying a cover.
     */
    static volatile boolean sDesktopGaveOurs;

    /** The desktop's renderer, from its last upload. */
    static volatile Object sDesktopRenderer;

    /** The desktop's last upload went through the keyguard branch. */
    static volatile boolean sDesktopOnLock;

    /**
     * The shade is up, so the DESKTOP wallpaper should be showing the cover.
     *
     * Why the desktop wallpaper at all: the notification shade's glass samples what is BEHIND
     * its window, and the cover SystemUI draws is inside it - so nothing we draw there can ever
     * be what the cards blur. The lock screen solved the same problem the same way, by having
     * the picture where the glass looks. This is that, for the desktop.
     */
    static volatile boolean sShadeOn;

    /** When the last shade-state message arrived, so a lost one cannot strand the wallpaper. */
    static volatile long sShadeAt;

    /** The composed cover, fitted to the desktop texture, and the size it was fitted for. */
    static volatile Bitmap sShadeFitted;
    static volatile String sShadeFitOf;

    private static final String CLS_KEYGUARD_ENGINE =
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardImageEngineImpl";

    private static final String CLS_DESKTOP_IMAGE_ENGINE =
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopImageEngineImpl";

    /** Asked for the cover again, at most this often. */
    private static final long ASK_MIN_MS = 2000L;
    /**
     * How many times one dry spell may ask.
     *
     * The gap between this process starting and SystemUI's receiver being up is about two
     * seconds wide, and both of the asks below fire inside it: measured on device, process start
     * at 18:53:04.169 and the engine at 18:53:05.064, SystemUI not loaded until 18:53:06.065,
     * and neither request arrived. So the ask is retried from the renderer for a few seconds.
     *
     * Nothing is retried once art has arrived, which is what keeps a cover that is simply
     * switched off - where this process is empty by design and always will be - from asking
     * forever.
     */
    private static final int ASK_TRIES = 5;
    /**
     * When askForArt() last sent a request, and how many it has sent since art last arrived.
     * Written from the GL thread as well as the main one, so these are only ever coarse.
     */
    private static volatile long sAskedAt;
    static volatile int sAsks;

    /**
     * Tells SystemUI that this process has no cover to draw, so it should send one again.
     *
     * The push is one-shot and SystemUI only pushes on a track change - and once it has pushed,
     * the same-artwork rule suppresses every later push for that song. So art composed while the
     * wallpaper process was not up is lost for the whole track: measured on device, nine pushes
     * between 16:23 and 16:24 with the receiver here only registering at 16:25:17, and not one of
     * them arrived - the lock screen stayed without a cover until the next track. Nothing on the
     * SystemUI side can notice, because it recorded the print as sent the moment it sent it.
     *
     * So the side that knows it is empty does the asking. Only ever called when there is nothing
     * here, which is also what stops it once the art arrives.
     */
    private static void askForArt(String why) {
        Context c = sCtx;
        if (c == null || sAsks >= ASK_TRIES) return;
        long now = SystemClock.uptimeMillis();
        if (now - sAskedAt < ASK_MIN_MS) return;
        sAskedAt = now;
        sAsks++;
        try {
            Intent out = new Intent("com.os4.musiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.putExtra("op", "needart");
            out.putExtra("why", why);
            ProbeGuard.send(c, out);
            Xp.log(TAG + "no art here, asked SystemUI for it (" + why + " "
                    + sAsks + "/" + ASK_TRIES + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "askForArt failed: " + t);
        }
    }

    // ------------------------------------------------------------------ the crossfade

    /**
     * The real lock wallpaper at texture size - the far end of the fade out of cover mode.
     *
     * Deliberately not persisted. It is only ever learnt by watching one go past in the upload
     * hook, so a copy read back from disk could be a wallpaper the user has since changed, and
     * fading to the wrong picture and then cutting to the right one is worse than not fading at
     * all. Missing it costs exactly one hard cut - the reload that performs it is the reload
     * that learns the original - so this heals itself on first use after a process restart.
     */
    static volatile Bitmap sOrig;
    private static volatile int sOrigPrint;

    // ------------------------------------------------------------------ the OEM's darkening

    /**
     * Whether the OEM darkens the lock wallpaper, as it last decided, and whether it has decided
     * at all since this process started.
     *
     * Read off the dex: KeyguardImageEngineImpl.W() calls renderer.updateMaskLayerStatus(need,
     * isDark), and isDark is `!(colorHints & SUPPORTS_DARK_TEXT) && support_dark` from the lock
     * slot's MIUI data - nothing to do with dark mode. It lands in AnimImageGLProgram.mDarken,
     * which commonDraw() turns into uDarken, and the shader then pulls every pixel 10% of its
     * brightness towards black: white comes out at 230. A lock slot the module had to split off
     * gets support_dark=true by default, so a user whose lock screen used to follow the desktop
     * sees it come back from cover mode a shade darker than it went in.
     *
     * The cover is not a wallpaper and is not meant to be dimmed, so the flag is withheld from
     * whatever picture is a cover. Per picture, not per moment: the fade in either direction
     * then crossfades the original at its own darkening with an undarkened cover, rather than
     * jumping 10% at one end of it.
     *
     * The original does not get the lock slot's own flag either, but the DESKTOP's - see
     * origDarken(). Keeping the OEM's value there was the first fix, and it left the complaint
     * standing: the user's measure is "the lock screen is darker than the desktop", and on the
     * device that reported it the two slots came back support_dark=true / false.
     */
    static volatile boolean sOemDarken;
    static volatile boolean sOemDarkenKnown;
    /**
     * The desktop renderer's own darkening, as the OEM last handed it over. It goes through the
     * same updateMaskLayerStatus(), so the hook reads it there without touching R8-renamed
     * WallpaperServiceController internals.
     */
    static volatile boolean sHomeDarken;
    static volatile boolean sHomeDarkenKnown;
    /** What the OEM's keyguard texture holds right now is a cover, not the real wallpaper. */
    static volatile boolean sTexShowsCover;
    /** Set when the program on screen has no mDarken to write; the OEM's value stands from then on. */
    static volatile boolean sDarkenBroken;
    /** The frame the fade is on. Non-null only while one is running. */
    static volatile Bitmap sFade;
    /** Reused across fades: a 12MB allocation per transition is itself a dropped frame. */
    static Bitmap sFadeBuf;
    /**
     * The second buffer, allocated only the first time the first one is still being read.
     *
     * One buffer used to be enough by a handshake: compose, publish, wait for the upload to
     * finish, compose again. But the wait gave up after FADE_ACK_MS, and on a phone whose upload
     * takes longer than that - every frame of the CPU fade rebuilt the EGL context as well as the
     * texture - the next frame was composed straight into the buffer texImage2D was still
     * copying. The uploaded texture then held two blend fractions at once, which is the tearing
     * reported from other phones. So a frame is only ever composed into a buffer that is neither
     * published nor being uploaded, and the second buffer is what keeps a slow upload from
     * stalling the fade instead.
     */
    static Bitmap sFadeBuf2;
    /**
     * The buffer the GL thread is copying right now, or null. Guarded by FADE_LOCK, and
     * volatile because the hot reload's check section reads it from a binder thread.
     */
    static volatile Bitmap sUploading;
    static final Object FADE_LOCK = new Object();
    /**
     * True from the moment a blended frame is handed to the engine until the GL thread has
     * finished reading it.
     *
     * One buffer is reused for the whole fade, so composing the next frame into it while the
     * upload is still walking down it puts two different blend fractions in one texture, with a
     * hard horizontal seam between them. That is not theoretical - it is what the tearing looks
     * like on video: the top third of the wallpaper a step or two behind the rest, for several
     * frames in a row. So the fade waits for its frame to be consumed before composing another.
     */
    static volatile boolean sFadeInFlight;
    /**
     * How long the crossfade takes.
     *
     * Apple's whole exit is about 300ms, but matching that number is not the same as matching
     * the feel: the clock is a critically damped spring, so it has covered most of its distance
     * long before it settles, and a linear-ish wallpaper fade of the same total length reads as
     * lagging behind it. Hence the ease-out below.
     *
     * The length itself is matched to the clock's spring rather than picked, and the two moved
     * together when the spring was changed to the OEM's own curve (EASE_COVER): the clock now
     * covers 95% of its travel at 235ms, and a cubic ease-out of length T covers its own 95% at
     * 0.632*T - so 370ms here against the 240 that went with the old, faster spring. Both
     * numbers reach 95% within a millisecond of each other, which is the whole of the rule:
     * 240ms of fade against a 235ms clock would have left the wallpaper sitting still while the
     * clock was still visibly growing, and 430 (the first attempt) is the same fault the other
     * way round.
     *
     * The number is no longer decided here. The clock's response is a setting now, and this is
     * the fade that belongs to it - proportional, because the same 95%-against-95% rule holds
     * for every response once zeta is fixed. SystemUI sends it when it changes and again on
     * every cover entry, and that second push is what covers a restart of this process: the
     * field below is a static with nothing behind it, so the 370 it starts at is only ever
     * right until the first push arrives.
     *
     * The op is kept for the case where SystemUI is not the one being tested:
     *   --es op fadems --ei v 370
     */
    static volatile long sFadeMs = 370L;
    /**
     * The crossfade for a TRACK CHANGE, which is a different animation with a different job.
     *
     * sFadeMs above is solved against the clock's spring, because entering and leaving cover mode
     * move the wallpaper and the clock together and they have to arrive together. A track change
     * moves neither the clock nor anything else: it swaps one cover for the next, with nothing on
     * screen to keep company with. Tying it to the clock's number only made it slow.
     *
     *   --es op trackfadems --ei v 60
     */
    static volatile long sTrackFadeMs = 180L;
    /**
     * Whether the OEM's frosted copy is regenerated on the fade's frames.
     *
     * On by default, on measurement rather than principle: it is worth 7 -> 9 frames across a
     * 240ms fade, because the round trip to the GL thread is what paces this and the blur is
     * part of it. What it costs is the notification and media cards blurring a wallpaper up to
     * 240ms stale, which nobody can see, and the upload that ends the fade puts it right.
     */
    static volatile boolean sSkipFrost = true;
    /** Live for the duration of one fade, read by the frosting hook on the GL thread. */
    static volatile boolean sFrostSkipping;

    static volatile WallpaperFade.GpuFade sGpuFade;
    /** The probe's switch: `--es op gpufade --ez on false` puts the CPU fade back. */
    static volatile boolean sGpuFadeOn = true;
    /** Set when the draw path met something it cannot handle; the CPU fade is used from then on. */
    static volatile boolean sGpuFadeBroken;
    static volatile boolean sDrawHooked;
    /**
     * What the keyguard texture must be uploaded from while a GPU fade runs: the far end of it.
     * Needed on the way OUT of cover mode above all, where the art is still set until the fade
     * lands and the real wallpaper would otherwise be decoded off disk - the 210ms the getBitmap
     * short-circuit exists to avoid.
     */
    static volatile Bitmap sUploadOverride;
    static volatile Object sKeyguardRenderer;
    static Class<?> sPlainProgram;
    /**
     * GL-thread state: our texture, the fade it belongs to and the context it was made in.
     * sGlTex is volatile because the hot reload's check section reads it from a binder thread.
     */
    static volatile int sGlTex;
    /** Which FRAME_REQUESTS entry this build answers to, once one has; -1 until then. */
    static volatile int sFrameReq = -1;

    /**
     * 保留壁纸's dim, 0..0.25: the wallpaper's fragment shader multiplies the texture by
     * 1 - this. It has to be inside the texture and not a view over it, because the media
     * card's and the clock's glass sample the wallpaper window and never see a SystemUI
     * view - which is why the card stayed bright over the dimmed wallpaper (issue #96).
     *
     * The value is animated HERE, not stepped by SystemUI: the `keepdim` op names a target
     * and a duration, and a frame pump asks the engine to draw until it is reached. Sending
     * one value per frame instead was a coarse fade - a wallpaper redraw per broadcast, and
     * whichever of the two the phone happened to miss read as a snap (2026-10-17).
     */
    static volatile float sKeepDim;
    static float sKeepFrom;
    static float sKeepTo;
    /** Written under KEEP_LOCK; volatile because the hot reload's check section reads it. */
    static volatile long sKeepAt;
    static int sKeepDurMs = 100;
    static boolean sKeepPumping;
    static final Handler KEEP_MAIN = Gen.watchHandler(new Handler(Looper.getMainLooper()));
    static final Object KEEP_LOCK = new Object();
    /** Diagnostics: messages received, drawn changes, and the last few drawn values. */
    static int sKeepMsgs;
    static int sKeepDraws;
    static final int KEEP_LOG = 8;
    static final long[] sKeepLogAt = new long[KEEP_LOG];
    static final float[] sKeepLogV = new float[KEEP_LOG];
    static int sKeepLogN;
    /** True while the keyguard renderer's own onDrawFrame runs. GL thread only. */
    private static boolean sKeyguardDrawing;

    /**
     * The lock clock's colours, taken from the cover while the cover is up.
     *
     * SystemUI asks this process for a palette of the clock's region of the lock wallpaper
     * (IMiuiWallpaperManagerService.getMiuiWallpaperPalette, `clock_palette_region`), and every
     * clock face builds its colours from it: the solid, overlay and gradient-blur effects take
     * their text colour from its secondary/neutral tones. The answer is computed from the
     * wallpaper FILE, which in cover mode is not what is on screen - so only the effects that
     * sample what is behind them (混色, liquid glass) followed the cover, and the rest kept the
     * real wallpaper's colours over an album they had nothing to do with (reported 2026-10-14).
     *
     * The OEM's own computation, run on the picture it is drawing instead: MiuiColorScheme's
     * palette over sArt, with the same screen-to-bitmap mapping of the regions. Only for the
     * LOCK wallpaper and only while the texture shows a cover; everything else - the home screen,
     * a kept wallpaper, the depth answer - stays the OEM's. The depth (getMiuiLockPartWallpaperIsDeep)
     * is deliberately not taken over: it decides light text or dark, and the user wants the
     * clock's colour from the cover without its lightness flipped by it.
     */
    private static int installCoverPalette() {
        int hooked = 0;
        try {
            Class<?> ctl = Xp.findClass("com.miui.miwallpaper.server.MiuiWallPaperControllerService", sCl);
            Class<?> scheme = Xp.findClass("com.miui.miwallpaper.material.MiuiColorScheme", sCl);
            Class<?> part = Xp.findClass("com.miui.miwallpaper.material.utils.PartRectColorUtils", sCl);
            final java.lang.reflect.Method palette = scheme.getMethod("getMiuiWallpaperPalette",
                    String.class, Bitmap.class, android.graphics.Rect.class);
            final java.lang.reflect.Method convert = part.getMethod("convertScreenRectsToBitmapRects",
                    Context.class, java.util.Map.class, float.class, float.class, int.class);
            // The regions an empty request means - every one the lock screen reads its colours
            // from - laid out the way the service does it, from the clock style the settings
            // name. Its two settings readers are obfuscated, so they are found by shape: the one
            // static no-argument method answering a String, and the one answering an int[].
            java.lang.reflect.Method regions = null, styleName = null, styleInfo = null;
            try {
                for (java.lang.reflect.Method m : part.getMethods()) {
                    if ("getPartScreenRectUtils".equals(m.getName()) && m.getParameterTypes().length == 6) {
                        regions = m;
                    }
                }
                Class<?> settings = Xp.findClass("com.miui.miwallpaper.utils.SystemSettingUtils", sCl);
                for (java.lang.reflect.Method m : settings.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            || m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() == String.class) styleName = styleName == null ? m : null;
                    if (m.getReturnType() == int[].class) styleInfo = styleInfo == null ? m : null;
                }
            } catch (Throwable t) {
                Xp.log(TAG + "cover palette: full requests stay the wallpaper's: " + t);
            }
            final java.lang.reflect.Method fRegions = regions, fName = styleName, fInfo = styleInfo;
            for (Class<?> inner : ctl.getDeclaredClasses()) {
                for (java.lang.reflect.Method m : inner.getDeclaredMethods()) {
                    if (!"getMiuiWallpaperPalette".equals(m.getName())) continue;
                    final String name = m.getName();
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length < 2 || pt[0] != int.class
                            || !java.util.Map.class.isAssignableFrom(pt[pt.length - 1])) continue;
                    Xp.hook("wall.palette.get." + Xp.slug(inner.getSimpleName()), m, chain -> {
                        java.util.List<Object> a = chain.getArgs();
                        int which = (Integer) a.get(0);
                        Object rects = a.get(a.size() - 1);
                        Bitmap art = coverForPalette();
                        if (art == null || (which != 2 && which != 8) || !(rects instanceof java.util.Map)) {
                            return PaletteCache.proceed(chain, name);
                        }
                        boolean full = ((java.util.Map<?, ?>) rects).isEmpty();
                        if (full && (fRegions == null || fName == null || fInfo == null)) return PaletteCache.proceed(chain, name);
                        try {
                            Context ctx = sCtx != null ? sCtx : (Context) Class.forName("android.app.ActivityThread")
                                    .getMethod("currentApplication").invoke(null);
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, android.graphics.Rect> mapped = full
                                    ? (java.util.Map<String, android.graphics.Rect>) fRegions.invoke(null, ctx,
                                            fName.invoke(null), fInfo.invoke(null), (float) art.getWidth(), (float) art.getHeight(), which)
                                    : (java.util.Map<String, android.graphics.Rect>) convert.invoke(null, ctx,
                                            rects, (float) art.getWidth(), (float) art.getHeight(), which);
                            java.util.HashMap<String, Object> out = new java.util.HashMap<>();
                            // A full request answers every region the wallpaper's would, the
                            // whole-picture one included: start from the OEM's own answer and
                            // replace what the cover can be read for.
                            if (full) {
                                Object orig = PaletteCache.proceed(chain, name);
                                if (orig instanceof java.util.Map) {
                                    for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) orig).entrySet()) {
                                        out.put(String.valueOf(e.getKey()), e.getValue());
                                    }
                                }
                                if (out.containsKey("full_image_rect")) {
                                    Object p = palette.invoke(null, "full_image_rect", art, null);
                                    if (p != null) out.put("full_image_rect", p);
                                }
                            }
                            for (java.util.Map.Entry<String, android.graphics.Rect> e : mapped.entrySet()) {
                                Object p = palette.invoke(null, e.getKey(), art, e.getValue());
                                out.put(e.getKey(), p == null ? new java.util.HashMap<>() : p);
                            }
                            sCoverPalettes++;
                            return out;
                        } catch (Throwable t) {
                            Xp.log(TAG + "cover palette failed, the wallpaper's stands: " + t);
                            return PaletteCache.proceed(chain, name);
                        }
                    });
                    hooked++;
                }
            }
            Xp.log(TAG + "cover palette: " + hooked + " entry point(s)");
        } catch (Throwable t) {
            Xp.log(TAG + "cover palette not installed: " + t);
        }
        return hooked;
    }

    /** How many palettes were answered from the cover, for the probe. */
    private static volatile int sCoverPalettes;

    /** The picture on the lock texture, when it is ours; null when it is the real wallpaper. */
    private static Bitmap coverForPalette() {
        Bitmap art = sArt;
        if (!sTexShowsCover || art == null || art.isRecycled()) return null;
        return art;
    }

    /**
     * Everything this process hooks, in the cold-start order.
     *
     * The class loader comes from the caller rather than from a PackageLoadedParam so a hot
     * reload can run the very same sequence in a process that has been up for hours; the
     * adoption validates that loader against the process's own classes first.
     */
    static void install(ClassLoader cl) {
        sCl = cl;
        Xp.log(TAG + "loaded into " + PKG);
        // The lock clock's palette read off the cover while the cover is up. Its own hook, failing
        // on its own: nothing else here depends on it.
        // Its fall-throughs to the OEM go through PaletteCache, which also takes the depth
        // question - and the palette itself if this hook did not go in.
        int coverHooks = installCoverPalette();
        PaletteCache.install(sCl, coverHooks == 0);
        // This process composes the covers: their full-screen draws on its GPU, not its CPU.
        CoverCompose.sGpu = true;

        // 保留壁纸's dim, written into the wallpaper shader rather than over the screen.
        //
        // Must be in before any GL program is built: the shader source is read and compiled when
        // that program is created, and a hook added later would only affect programs compiled
        // after it. The keyguard renderer's is built when its surface is created, after this.
        try {
            Class<?> prog = Xp.findClass("com.miui.miwallpaper.opengl.ImageGLProgram", sCl);
            Xp.hookAll("wall.shader.resource", prog, "getShaderResource", chain -> {
                Object r = chain.proceed();
                if (!(r instanceof String)) return r;
                String src = (String) r;
                // The still image's fragment shader, and only it: the vertex shader has no
                // uDarken, and the effect programs have their own fragment sources.
                if (!src.contains("uniform int uDarken;")) return r;
                String out = src.replace("uniform int uDarken;",
                        "uniform int uDarken;\nuniform float uKeepDim;");
                int end = out.lastIndexOf('}');
                if (end < 0) return r;
                return out.substring(0, end)
                        + "    fragColor.rgb *= (1.0 - uKeepDim);\n" + out.substring(end);
            });
            Xp.log(TAG + "keep-dim shader hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "keep-dim shader hook failed (the KEEP look keeps no dim): " + t);
        }

        // Must be installed at load time: getBitmap() runs when the GL surface is created and
        // the texture is then cached, so a hook added later never sees it.
        try {
            Class<?> base = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer", sCl);
            // The one place the wallpaper bitmap reaches the GL upload:
            //   onSurfaceCreated() -> mTexture.use(c) -> lambda$onSurfaceCreated$0(Bitmap)
            // Hooking here rather than on WallpaperTexture.getWallpaperBitmap() because
            // thisObject is the renderer, so we can tell the keyguard one from the desktop one
            // and leave the home wallpaper alone.
            Xp.hookAllLambdas("wall.render.surface_created", base, "lambda$onSurfaceCreated$0", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean keyguard = chain.getThisObject().getClass().getName().contains("Keyguard");
                if (!keyguard && args.length > 0 && args[0] instanceof Bitmap
                        && chain.getThisObject().getClass().getName().contains("Desktop")) {
                    keyguard = WallpaperEngines.desktopUpload(chain.getThisObject(), (Bitmap) args[0]);
                }
                // Temporary diagnostic: name every renderer that reaches the upload, so the
                // DESKTOP one can be addressed by name. It shares this code path and is let
                // through below - nothing here changes for it.
                if (!keyguard && sUploadNames.add(chain.getThisObject().getClass().getName())) {
                    Xp.log(TAG + "non-keyguard upload renderer: "
                            + chain.getThisObject().getClass().getName());
                }
                // The desktop wallpaper, while the notification shade is over it. Same
                // substitution the keyguard does below, on the sibling renderer, for the sibling
                // reason: the cards' glass samples what is behind the shade window, so the cover
                // has to BE the wallpaper for it to be what they show.
                if (!keyguard && args.length > 0 && args[0] instanceof Bitmap) {
                    Bitmap cover = WallpaperArt.shadeCoverFor((Bitmap) args[0]);
                    if (cover != null) {
                        args[0] = cover;
                        return chain.proceed(args);
                    }
                }
                if (keyguard && args.length > 0 && args[0] instanceof Bitmap) {
                    Bitmap orig = (Bitmap) args[0];
                    sKeyguardRenderer = chain.getThisObject();
                    // Kept so the dimension hook can tell the keyguard's texture from the
                    // desktop one's: same class, two instances, and only one of them is ours.
                    //
                    // Re-read on every upload rather than only the first. A foldable has a
                    // renderer per screen - five of them on the q18 build - each with its own
                    // texture, and holding the first one seen meant the dimension hook stopped
                    // recognising the texture as soon as another screen drew. This branch is
                    // already inside `if (keyguard)`, so whatever it finds here is a keyguard
                    // texture; the only question is which screen's, and the answer is always
                    // the one uploading right now.
                    try {
                        Object t = Xp.getObjectField(chain.getThisObject(), "mTexture");
                        if (t != null) sKeyguardTexture = t;
                    } catch (Throwable ignored) {
                    }
                    // BEFORE the fit below, not after. screenSized() crops to sSurfaceW/H, and
                    // those used to be written further down by noteRenderState - i.e. they held
                    // whichever renderer uploaded LAST. On one screen that is the same size and
                    // nothing shows; on a foldable it is routinely a different screen, so the
                    // picture was cropped for one screen and handed to another. The measured
                    // shape of that is a viewport and an upload that disagree:
                    // `viewport=Rect(0,0-2364,1672) mvpFrom=Rect(0,0-1168,1712) upload=1168x1712`
                    // - the cover cropped for the outer screen, uploaded to the inner one.
                    // Reading it off THIS renderer makes the crop, the MVP source and the
                    // viewport the same screen's by construction.
                    adoptSurfaceOf(chain.getThisObject());
                    // The experiment's other half. Everything below - the fade size check, the
                    // art fit, sReportedW/H that fittedArt() scales by - then sees the screen's
                    // size rather than the wallpaper file's, which is the whole point.
                    Bitmap screen = screenSized(orig);
                    if (screen != null) {
                        orig = screen;
                        args[0] = screen;
                    }
                    int w = orig.getWidth(), h = orig.getHeight();
                    if (w != sReportedW || h != sReportedH) {
                        sReportedW = w;
                        sReportedH = h;
                        Xp.log(TAG + "keyguard texture is " + w + "x" + h);
                    }
                    // Remembered against the texture itself, because the MVP matrix is built
                    // later, off whatever getTextureDimensions answers then - by which time
                    // another screen may have uploaded.
                    if (sKeyguardTexture != null) {
                        sTexSizes.put(sKeyguardTexture, new android.graphics.Rect(0, 0, w, h));
                    }
                    noteRenderState(chain.getThisObject(), w, h);
                    // A GPU fade's far end. Uploaded once, and from then on the fade is drawn
                    // over it, so this is the only upload the whole transition makes.
                    Bitmap ov = sUploadOverride;
                    WallpaperFade.GpuFade gf = sGpuFade;
                    if (ov != null && gf != null) {
                        if (ov.isRecycled() || ov.getWidth() != w || ov.getHeight() != h) {
                            Xp.log(TAG + "gpu fade dropped: its far end is " + describe(ov)
                                    + " but the texture is " + w + "x" + h);
                            WallpaperFade.abortGpuFade(gf);
                        } else {
                            args[0] = ov;
                            sTexShowsCover = ov != sOrig;
                            Object r = chain.proceed(args);
                            if (!gf.swapRequested) gf.armed = true;
                            else if (ov == gf.dest) gf.swapUploaded = true;
                            return r;
                        }
                    }
                    // A fade owns the texture outright while it runs: every frame of it is
                    // a blend this process composed, and neither the art nor the original is
                    // what should be uploaded until it lands.
                    Bitmap fade;
                    // Taken under the lock together with marking it as being read, so the fade
                    // cannot pick this buffer to compose into between the two - see pickFadeBuf().
                    synchronized (FADE_LOCK) {
                        fade = sFade;
                        sUploading = fade;
                    }
                    // Size-checked like the art below, and for the same reason: the GL matrix
                    // comes from the bitmap's dimensions, so a bitmap that is not exactly this
                    // texture lands the wallpaper askew - a corner of the picture in a corner
                    // of the screen. A fade composed for a texture that has since changed size
                    // (a new wallpaper, a surface rebuilt at another size) is not something to
                    // fit and show, it is stale: drop it and let the frame below draw the real
                    // art at the real size.
                    if (fade != null && (fade.getWidth() != w || fade.getHeight() != h)) {
                        Xp.log(TAG + "fade dropped: composed for " + describe(fade)
                                + " but the texture is " + w + "x" + h);
                        WallpaperFade.cancelFade();
                        fade = null;
                        synchronized (FADE_LOCK) {
                            sUploading = null;
                        }
                    }
                    if (fade != null) {
                        args[0] = fade;
                        // A CPU blend is both pictures in one texture, so it cannot be darkened
                        // per picture. It follows the art instead, which only differs from the
                        // GPU fade at the very end of a fade out of cover mode.
                        sTexShowsCover = sArt != null;
                        // proceed() is the upload: once it returns, the buffer has been read
                        // and may be composed into again.
                        try {
                            return chain.proceed(args);
                        } finally {
                            synchronized (FADE_LOCK) {
                                sUploading = null;
                            }
                            sFadeInFlight = false;
                        }
                    }
                    Bitmap art = sArt;
                    sTexShowsCover = art != null;
                    // The one moment the real lock wallpaper passes through here. Once the art
                    // is set the getBitmap short-circuit below means the OEM never decodes it
                    // again, so this is the only chance to learn what to fade back to.
                    if (art == null) {
                        rememberOriginal(orig);
                        // Drawing the keyguard with no cover to draw: the one moment worth
                        // asking, and the retry for the two asks above that fire before
                        // SystemUI's receiver exists. Rate-limited and capped in askForArt().
                        askForArt("renderer");
                    }
                    if (art != null) {
                        // Match the original exactly: updateDimensions/updateMatrix derive
                        // the GL matrix from these, so another size lands the wallpaper askew.
                        // Read once: a retirement clears these from the main thread while
                        // this is the GL thread's.
                        Bitmap fitted;
                        Bitmap had = sFitted;
                        if (art.getWidth() == w && art.getHeight() == h) {
                            fitted = art;                   // composed at exactly this size
                        } else if (had != null && sFittedOf == art
                                && had.getWidth() == w && had.getHeight() == h) {
                            fitted = had;                   // already fitted for this texture
                        } else {
                            fitted = WallpaperArt.centerCrop(art, w, h);
                            sFitted = fitted;
                            sFittedOf = art;
                        }
                        fitted = WallpaperArt.frostedIfWanted(fitted);
                        args[0] = fitted;
                        // The first upload after a push is the one the user sees. Later ones (a
                        // fade's frames) leave the mark where it was, so `op timing` reports the
                        // moment the new cover reached the screen, not the end of its animation.
                        if (sTmUploaded == 0L) sTmUploaded = SystemClock.uptimeMillis();
                        Xp.log(TAG + "wallpaper texture REPLACED " + describe(orig)
                                + " -> " + describe(fitted)
                                + (fitted == art ? " (no rescale)" : ""));
                    }
                }
                return chain.proceed(args);
            });
            Xp.log(TAG + "upload path hooked on ImageWallpaperRenderer");
        } catch (Throwable t) {
            // Said "getBitmap hook failed" until a HyperOS 3 report came in naming that and
            // the short-circuit below in the same breath. This is the one that matters: with
            // it gone the texture is never replaced, so the cover does nothing at all.
            Xp.log(TAG + "upload path hook FAILED - the cover cannot be drawn: " + t);
        }

        // The experiment's other half. updateMVPMatrix(surfaceW, surfaceH, getTextureDimensions())
        // builds the GL matrix from this rectangle, so a screen-sized upload with a
        // wallpaper-sized source rect draws the picture whichever way the two disagree - a corner
        // of it in a corner of the screen, which is the failure our own art-fit comment describes.
        // Both ends move together or neither does.
        try {
            Class<?> tex = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer$WallpaperTexture", sCl);
            Xp.hookAll("wall.texture.dimensions", tex, "getTextureDimensions", chain -> {
                Object self = chain.getThisObject();
                if (!sTexFit || self == null) return chain.proceed();
                // What THIS texture was last given, not what the last upload anywhere was. On a
                // foldable the two are routinely different screens, and answering with the other
                // one's size builds the MVP matrix from a rectangle the uploaded bitmap does not
                // have - the picture then lands scaled and offset, which is exactly the symptom
                // that only a fold or a rotation could clear. The map holds keyguard textures
                // only, so the desktop's still gets the OEM's own answer.
                android.graphics.Rect r = sTexSizes.get(self);
                if (r != null) return r;
                if (self != sKeyguardTexture) return chain.proceed();
                int w = sSurfaceW, h = sSurfaceH;
                if (w <= 0 || h <= 0) return chain.proceed();
                return new android.graphics.Rect(0, 0, w, h);
            });
            Xp.log(TAG + "texture dimension hook installed");
        } catch (Throwable t) {
            Xp.w(TAG + "texture dimension hook failed: " + t);
        }

        // Measured: of the ~380ms a track change took, 210ms was the OEM's own getBitmap()
        // decoding the real lock wallpaper off disk inside onSurfaceCreated - a bitmap thrown
        // away one call later when we substitute ours. Handing back the art we are going to
        // substitute anyway removes that decode outright. The upload path is still the lambda
        // below; this only short-circuits the source, and only once the texture size is known,
        // so a cold start still learns the real dimensions first.
        try {
            Class<?> kg = Xp.findClass(
                    "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer",
                    sCl);
            Xp.hookAll("wall.texture.bitmap", kg, "getBitmap", chain -> {
                // Not size-checked here on purpose: getBitmap is the SOURCE, and what the
                // texture ends up being is decided by the lambda above, which does check.
                Bitmap ov = sUploadOverride;
                if (ov != null && sGpuFade != null && !ov.isRecycled()) return ov;
                // Never the fade's buffer itself. What is returned here is read later, outside the
                // lock the lambda takes, while the fade may already be composing the next frame
                // into it. The lambda substitutes the buffer anyway; this only has to be a
                // picture of the right size that nobody writes to.
                if (sFade != null) {
                    Bitmap stable = fittedArt();
                    if (stable == null) stable = sOrig;
                    if (stable != null && !stable.isRecycled()) return stable;
                }
                Bitmap fitted = fittedArt();
                // Returning without proceeding IS the short-circuit: the OEM never decodes
                // the real lock wallpaper off disk, which is the 210ms this buys back.
                return fitted != null ? fitted : chain.proceed();
            });
            Xp.log(TAG + "keyguard getBitmap short-circuit installed");
        } catch (Throwable t) {
            Xp.w(TAG + "getBitmap short-circuit failed: " + t);
        }

        // The same short-circuit for the desktop renderer while it draws a shared lock screen's
        // cover. Its cache is dropped on every reload, so without this each one decoded the
        // wallpaper off disk first - 150-300ms measured, in front of every fade, and on the way
        // out the depth cut-out came back that long before the wallpaper began to (#84).
        try {
            Class<?> dr = Xp.findClass(
                    "com.miui.miwallpaper.container.openGL.DesktopAnimImageWallpaperRenderer",
                    sCl);
            Xp.hookAll("wall.desktop.bitmap", dr, "getBitmap", chain -> {
                if (!WallpaperEngines.desktopOnLockWanted()) {
                    sDesktopGaveOurs = false;
                    return chain.proceed();
                }
                Bitmap ours = null;
                Bitmap ov = sUploadOverride;
                if (ov != null && sGpuFade != null && !ov.isRecycled()) ours = ov;
                if (ours == null) ours = fittedArt();
                if (ours == null) ours = sOrig;
                if (ours == null || ours.isRecycled()) {
                    sDesktopGaveOurs = false;
                    return chain.proceed();
                }
                sDesktopGaveOurs = true;
                return ours;
            });
            Xp.log(TAG + "desktop getBitmap short-circuit installed");
        } catch (Throwable t) {
            Xp.w(TAG + "desktop getBitmap short-circuit failed: " + t);
        }

        // Runtime refresh. ImageEngineImpl.U() ("preRender", on the GL thread) re-runs
        //     mRenderer.onSurfaceCreated(); mRenderer.onSurfaceChanged(w, h)
        // whenever its pending-surface flag is set, and onSurfaceCreated is the path that ends in
        // mTexture.use(...) -> the lambda we already replace the bitmap in. So a new track only
        // has to set that flag and ask for a frame; the OEM does the upload itself, including the
        // frosted copy the notification cards blur against.
        try {
            Class<?> eng = Xp.findClass(CLS_KEYGUARD_ENGINE, sCl);
            Xp.hookAllConstructors("wall.engine.keyguard_ctor", eng, chain -> {
                Object result = chain.proceed();
                sKeyguardEngine = chain.getThisObject();
                // Read the obfuscated member names off this instance before anything reaches for
                // them. A foldable builds one engine per screen, so this also runs more than once
                // there - resolve() is per engine class and returns at once after the first.
                EngineNames.resolve(sKeyguardEngine);
                WallpaperEngines.noteEngineInstance(sKeyguardEngine);
                Xp.log(TAG + "keyguard engine captured: " + sKeyguardEngine);
                // From here the lock screen has an image engine, so this is the first moment a
                // cover has somewhere to go - and by now the receiver above is up, which is what
                // the pushes that went missing did not have.
                if (sArt == null) askForArt("keyguard engine");
                return result;
            });
            Xp.log(TAG + "keyguard engine hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "keyguard engine hook failed: " + t);
        }

        // The DESKTOP engine, found without knowing its name.
        //
        // The two engines are siblings - the renderers are (`Keyguard`/`Desktop` +
        // `AnimImageWallpaperRenderer`, both under container.openGL) and the reload mechanism
        // that drives them is shared, which is why reloadTexture()'s obfuscated u()/b/T(false)
        // work at all. So hooking the SUPERCLASS of the keyguard engine catches every sibling's
        // construction too, and the class name of each instance tells us which is which. The
        // name is not guessed anywhere in this file; it is read off the instances.
        try {
            Class<?> kg = Xp.findClass(CLS_KEYGUARD_ENGINE, sCl);
            Class<?> base = kg.getSuperclass();
            Xp.log(TAG + "engine base class = " + (base == null ? "null" : base.getName()));
            if (base != null) {
                Xp.hookAllConstructors("wall.engine.base_ctor", base, chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    String n = self.getClass().getName();
                    if (sEngineNames.add(n)) Xp.log(TAG + "engine instance: " + n);
                    if (n.contains("Desktop")) {
                        sDesktopEngine = self;
                        // The names live on the shared base class, so the desktop's are the
                        // keyguard's - but with no keyguard engine nothing would read them, and a
                        // reload without the pending-surface flag only redraws (#84, shared).
                        if (sKeyguardEngine == null) EngineNames.resolve(self);
                    }
                    return result;
                });
                Xp.log(TAG + "engine base constructors hooked");
            }
        } catch (Throwable t) {
            Xp.w(TAG + "engine base hook failed: " + t);
        }

        // The desktop still's word on the lock screen, for a lock screen that shares it. See
        // sSharedLock. The names are the EngineService interface's and survive R8.
        try {
            Class<?> de = Xp.findClass(CLS_DESKTOP_IMAGE_ENGINE, sCl);
            int n = 0;
            for (Method m : de.getDeclaredMethods()) {
                final boolean show = m.getName().equals("showKeyguardWallpaper");
                if (!show && !m.getName().equals("hideKeyguardWallpaper")) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                // Only the (boolean, int) pair: the controller sends it to every engine together
                // with its setKeyguardLocked. The no-argument hide is something else - sent from
                // the screen-on and unlock animations with the lock screen still up.
                if (m.getParameterCount() != 2) continue;
                Xp.hook("wall.desktop.keyguard_show", m, chain -> {
                    Object r = chain.proceed();
                    if (sSharedLock) WallpaperEngines.onDesktopKeyguard(show);
                    else sDesktopKgShown = show;
                    return r;
                });
                n++;
            }
            Xp.log(TAG + "desktop engine keyguard calls hooked (" + n + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "desktop engine keyguard hook failed, a shared lock wallpaper keeps the"
                    + " cover off: " + t);
        }

        // The live lock wallpaper's engine, its player and its path. See hookVideoEngines().
        VideoTakeover.hookVideoEngines();

        // The frosted copy the notification and media cards blur against is regenerated on
        // every texture upload. That is the right trade once per track change and the wrong one
        // twenty times in a row, so a fade can switch it off for its own frames; the upload that
        // ends the fade is a normal one and puts it right. Only ever engaged from startFade().
        try {
            Class<?> ap = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimatorProgram", sCl);
            Xp.hookAll("wall.render.mix_frost", ap, "setUpMixFrost", chain -> {
                if (sFrostSkipping) return null;
                return chain.proceed();
            });
            Xp.log(TAG + "frosting hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "frosting hook failed: " + t);
        }

        // The OEM's darkening decision, kept for the original and withheld from the cover. See
        // sOemDarken. Its own block: a build that renames this loses the fix, not the cover.
        try {
            Class<?> ar = Xp.findClass(
                    "com.miui.miwallpaper.opengl.AnimImageWallpaperRenderer", sCl);
            Xp.hookAll("wall.render.mask_layer", ar, "updateMaskLayerStatus", chain -> {
                Object self = chain.getThisObject();
                Object[] args = chain.getArgs().toArray();
                if (self == null || args.length != 2 || !(args[1] instanceof Boolean)) {
                    return chain.proceed();
                }
                String cls = self.getClass().getName();
                if (cls.contains("Desktop")) {
                    boolean home = (Boolean) args[1];
                    if (!sHomeDarkenKnown || home != sHomeDarken) {
                        Xp.log(TAG + "OEM darkens the desktop wallpaper: " + home
                                + " - the lock screen's own wallpaper follows it");
                    }
                    sHomeDarken = home;
                    sHomeDarkenKnown = true;
                    return chain.proceed();
                }
                if (!cls.contains("Keyguard")) return chain.proceed();
                boolean dark = (Boolean) args[1];
                if (!sOemDarkenKnown || dark != sOemDarken) {
                    Xp.log(TAG + "OEM darkens the lock wallpaper: " + dark
                            + (dark ? " - withheld from the cover" : ""));
                }
                sOemDarken = dark;
                sOemDarkenKnown = true;
                args[1] = WallpaperFade.origDarken() && !sTexShowsCover;
                return chain.proceed(args);
            });
            Xp.log(TAG + "darken hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "darken hook failed, the cover stays dimmed where the OEM dims: " + t);
        }

        // The GPU crossfade's frames. Declared on the base class, which the keyguard renderer
        // reaches through AnimImageWallpaperRenderer's super call; the desktop renderer passes
        // through here too and is let alone by the identity check.
        try {
            Class<?> base = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer", sCl);
            sPlainProgram = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimImageGLProgram", sCl);
            Xp.hookAll("wall.render.draw_frame", base, "onDrawFrame", chain -> {
                final boolean mine = chain.getThisObject() == sKeyguardRenderer;
                // The program draw hook reads this to know whose texture it is setting the
                // dim on. Single GL thread, so a plain flag is enough.
                if (mine) {
                    sKeyguardDrawing = true;
                    sKeyguardGlTid = android.os.Process.myTid();
                }
                try {
                    if (sOemDarkenKnown && !sDarkenBroken && mine) {
                        WallpaperFade.applyDarken(chain.getThisObject());
                    }
                    Object r = mine && (WallpaperVideo.on() || WallpaperEngines.sVideoGlLive)
                            ? WallpaperEngines.drawWithVideo(chain) : chain.proceed();
                    if (!mine) return r;
                    WallpaperFade.GpuFade f = sGpuFade;
                    if (f == null && sGlTex == 0) return r;
                    try {
                        WallpaperFade.drawGpuFade(chain.getThisObject(), f);
                    } catch (Throwable t) {
                        sGpuFadeBroken = true;
                        Xp.w(TAG + "gpu fade draw failed, using the CPU fade from now on: "
                                + Log.getStackTraceString(t));
                        if (f != null) WallpaperFade.abortGpuFade(f);
                    }
                    return r;
                } finally {
                    if (mine) sKeyguardDrawing = false;
                }
            });
            sDrawHooked = true;
            Xp.log(TAG + "draw hooked for the gpu fade");
        } catch (Throwable t) {
            Xp.w(TAG + "draw hook failed, the fade stays on the CPU: " + t);
        }

        // The one frame of the wallpaper's own draw. Everything else - the texture, the
        // uniforms, the program - has been set by the time this is reached, so the dim can
        // go on the current program immediately before its glDrawArrays.
        try {
            Class<?> glw = Xp.findClass("com.miui.miwallpaper.opengl.ImageGLWallpaper", sCl);
            Xp.hookAll("wall.render.gl_draw", glw, "draw", chain -> {
                if (sKeyguardDrawing) WallpaperEngines.applyKeepDim();
                return chain.proceed();
            });
            Xp.log(TAG + "keep-dim uniform hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "keep-dim uniform hook failed (the KEEP look keeps no dim): " + t);
        }

        // Its own block like every other: a build where this name moved must not take the whole
        // process's adoption down - the receiver is also installed by the hot reload's own
        // main-thread step, which does not depend on this hook.
        try {
            Xp.hook("wall.app.create", Xp.findMethodExact(Application.class, "onCreate"), chain -> {
                Object result = chain.proceed();
                try {
                    register((Application) chain.getThisObject());
                } catch (Throwable t) {
                    Xp.w(TAG + "register failed: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.w(TAG + "application hook failed (a cold start still registers): " + t);
        }
        // What was registered before this point (the log-level preference listener, from
        // Xp.attach) is install-time, like SystemUI's: a dry run must put it back, not sweep it.
        Gen.markInstallScope();
    }

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

    static String sRenderState = "";
    /** How the last GPU fade ended, for `op selftest`. */
    static volatile String sLastFade = "none yet";
    private static boolean sRenderStateFailed;
    /** The screen, as MIUI's own renderer has it (mSurfaceSize). 0 until an upload has run. */
    static volatile int sSurfaceW, sSurfaceH;

    /**
     * The three numbers that decide where the wallpaper lands on screen, read off MIUI's own
     * renderer at the moment of the upload:
     *
     * - mSurfaceSize, which is the glViewport onSurfaceChanged last set;
     * - the texture's dimensions, which is what AnimImageWallpaperRenderer.updateMVPMatrix()
     *   builds the MVP matrix from (updateMVPMatrix(surfaceW, surfaceH, textureDimensions));
     * - the bitmap actually being uploaded.
     *
     * A picture drawn small and cornered is one of these three disagreeing with the other two,
     * and none of them is visible from the SystemUI side or from a screenshot. Logged only when
     * the triple CHANGES, so a steady state costs one string compare per upload and says
     * nothing, and the frame that moves the picture is the one that prints.
     */
    /**
     * Takes this renderer's surface as the screen to fit to.
     *
     * The one place the SCREEN's own size is knowable in this process, and on a foldable it is a
     * different answer per renderer - so it is read from the renderer that is about to upload,
     * immediately before the crop that uses it, rather than left over from the last one.
     */
    static void adoptSurfaceOf(Object renderer) {
        try {
            Object surface = Xp.getObjectField(renderer, "mSurfaceSize");
            if (surface instanceof android.graphics.Rect) {
                android.graphics.Rect r = (android.graphics.Rect) surface;
                if (r.width() > 0 && r.height() > 0) {
                    sSurfaceW = r.width();
                    sSurfaceH = r.height();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void noteRenderState(Object renderer, int w, int h) {
        if (sRenderStateFailed) return;
        try {
            Object surface = Xp.getObjectField(renderer, "mSurfaceSize");
            Object texture = Xp.getObjectField(renderer, "mTexture");
            Object dims = texture == null ? null
                    : Xp.callMethod(texture, "getTextureDimensions");
            String now = "viewport=" + surface + " mvpFrom=" + dims
                    + " upload=" + w + "x" + h;
            if (now.equals(sRenderState)) return;
            sRenderState = now;
            Xp.log(TAG + "render state " + now);
        } catch (Throwable t) {
            sRenderStateFailed = true;
            Xp.log(TAG + "cannot read the renderer's geometry: " + t);
        }
    }

    /**
     * sArt scaled to the texture the keyguard actually uploads, or null while that size is still
     * unknown. Cached, so a track change scales once rather than on every GL callback.
     */
    static Bitmap fittedArt() {
        return WallpaperArt.frostedIfWanted(WallpaperArt.sharpFittedArt());
    }

    /** The keyguard renderer's GL thread, as its last frame ran on; 0 until one has drawn. */
    static volatile int sKeyguardGlTid;

    /**
     * Keeps a copy of the real lock wallpaper, which is the only thing the cover can fade back
     * to. The bitmap handed to the upload hook belongs to the OEM and is recycled behind us, so
     * this has to be a copy - and the fingerprint is what stops it being copied again on every
     * reload that happens while cover mode is off.
     */
    static void rememberOriginal(Bitmap b) {
        if (b == null || b.isRecycled()) return;
        try {
            int print = WallpaperDiag.print8(b);
            Bitmap have = sOrig;
            if (have != null && !have.isRecycled() && print == sOrigPrint
                    && have.getWidth() == b.getWidth() && have.getHeight() == b.getHeight()) {
                return;
            }
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) return;
            sOrig = copy;
            sOrigPrint = print;
            Xp.log(TAG + "lock wallpaper remembered " + describe(copy));
        } catch (Throwable t) {
            Xp.log(TAG + "could not remember the lock wallpaper: " + t);
        }
    }

    private static synchronized void register(Context ctx) {
        if (sRegistered) return;
        sRegistered = true;
        sCtx = ctx.getApplicationContext();
        VideoTakeover.checkTakeoverCrash(ctx);
        // A hot reload hands the cover over instead, and re-reading it from disk would compose
        // it again on this thread for a picture the texture already shows.
        if (sArt == null) WallpaperArt.loadArt(ctx);
        // Restored from disk or not: either way the cover that belongs on screen is the one
        // SystemUI holds, and SystemUI has no way to learn ours is missing.
        //
        // NOT asked for when something was restored, and that is a bug this file has already
        // paid for once: on a process that comes back up as the track changes, the art on disk
        // is the previous track's, this process cannot tell it is the wrong one, and SystemUI
        // never learns the push it sent was lost - so the lock screen keeps that cover for the
        // whole track. Asking on every start fixes exactly that, and was reverted because it
        // does not survive what is underneath it: measured on the phone, an art push takes the
        // wallpaper process down, so a start that asks is answered by a push that kills the
        // process that asked. 1.3s a round, for as long as the module is loaded. The push has
        // to stop killing it before this can be turned back on.
        if (sArt == null) askForArt("process start");
        class Probe extends ProbeGuard.Receiver {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!ProbeGuard.admit(this, i)) return;
                String op = i.getStringExtra("op");
                byte[] carried = i.getByteArrayExtra("jpg");
                Xp.d(TAG + "recv op=" + op
                        + (carried == null ? " " + i.getExtras() : " jpg=" + carried.length + "B"));
                try {
                    if (i.hasExtra("video")) VideoTakeover.noteLockWallpaper(i.getBooleanExtra("video", false));
                    if (i.hasExtra("shared")) WallpaperEngines.noteSharedLock(i.getBooleanExtra("shared", false));
                    // Only where the engine has said nothing yet - a process that started on the
                    // lock screen. Its own show/hide is the authority: a message sent just before
                    // an unlock can land just after the hide, and must not undo it.
                    if (i.hasExtra("locked") && sDesktopKgShown == null) {
                        sDesktopKgShown = i.getBooleanExtra("locked", false);
                    }
                    if ("cls".equals(op)) {
                        WallpaperDiag.dumpClass(i.getStringExtra("name"), i.getStringExtra("grep"));
                    } else if ("keepdim".equals(op)) {
                        // 保留壁纸's dim, as a target and the time to reach it. The frames
                        // are asked for by KEEP_PUMP and the value is interpolated at draw
                        // time - see applyKeepDim(). Nearly no logging here on purpose: a
                        // step arrives during the clock's transition.
                        float to = Math.max(0f, Math.min(0.5f, i.getFloatExtra("v", 0f)));
                        int ms = Math.max(1, i.getIntExtra("ms", 100));
                        synchronized (KEEP_LOCK) {
                            sKeepFrom = WallpaperEngines.keepDimNowLocked();
                            sKeepTo = to;
                            sKeepAt = android.os.SystemClock.uptimeMillis();
                            sKeepDurMs = ms;
                            sKeepMsgs++;
                        }
                        WallpaperEngines.startKeepPump();
                    } else if ("keepdimreport".equals(op)) {
                        setResultData(WallpaperDiag.keepDimReport());
                    } else if ("bmp".equals(op)) {
                        WallpaperDiag.traceBitmaps(i.getStringExtra("name"));
                    } else if ("texfit".equals(op)) {
                        // The one switch for the screen-sized keyguard texture, which is on unless
                        // this turns it off: if a phone ever draws the wallpaper into a corner,
                        // this is what to reach for. A reload is what makes the next upload
                        // re-read every one of these numbers, and reloadTexture() is also how the
                        // surface size gets known here (the first upload of a process runs before
                        // onSurfaceChanged has set it).
                        sTexFit = i.getBooleanExtra("on", !sTexFit);
                        Xp.log(TAG + "texture fit to screen " + (sTexFit ? "ON" : "off")
                                + " (surface " + sSurfaceW + "x" + sSurfaceH + ")");
                        WallpaperEngines.reloadTexture();
                    } else if ("precompose".equals(op)) {
                        String src = i.getStringExtra("src");
                        if (src != null && !VideoTakeover.videoPath()) {
                            WallpaperArt.precompose(src, i.getBooleanExtra("cardmode", false),
                                    i.getStringExtra("prekey"));
                        }
                    } else if ("art".equals(op)) {
                        sCardMode = i.getBooleanExtra("cardmode", false);
                        final Context cc = c;
                        boolean reload = i.getBooleanExtra("reload", false);
                        // A fade needs both ends of it in this process. Missing either one is
                        // not a failure, it is the old behaviour: swap the texture in one frame.
                        boolean fade = reload && i.getBooleanExtra("fade", false);
                        // The cover video's own crossfade, which SystemUI decides; carried on
                        // every push because the file it describes is rebuilt on every push.
                        VideoTakeover.sVideoFade = i.getBooleanExtra("vfade", VideoTakeover.sVideoFade);
                        // Whatever arrives now is newer than a composition still in progress,
                        // which would otherwise land after it and put an older cover back.
                        if (!i.hasExtra("src")) sSrcSeq++;
                        sArtSeq++;
                        sMsgSeq++;
                        // What the lyrics want of THIS cover. See applyArt(), which is where it
                        // takes effect; older builds of SystemUI send no such extra, and an older
                        // SystemUI sends the answer without its place in line.
                        boolean blurMine = true;
                        if (i.hasExtra("blurseq")) {
                            blurMine = WallpaperArt.takeBlurDecision(i.getLongExtra("blurseq", 0L),
                                    i.getBooleanExtra("lyricblur", false), "cover");
                        } else if (i.hasExtra("lyricblur")) {
                            sLyricBlurWant = i.getBooleanExtra("lyricblur", false);
                        }
                        if (i.hasExtra("tsent")) {
                            sTmT0 = i.getLongExtra("t0", 0L);
                            sTmSkip = i.getLongExtra("tskip", 0L);
                            sTmSkipDir = i.getIntExtra("skipdir", 0);
                            sTmBurst = i.getLongExtra("tburst", 0L);
                            sTmSkips = i.getIntExtra("skips", 0);
                            sTmArt = i.getLongExtra("tart", 0L);
                            sTmSent = i.getLongExtra("tsent", 0L);
                            sTmCheckMs = i.getLongExtra("checkms", 0L);
                            sTmTries = i.getIntExtra("tries", 0);
                            sTmRecv = SystemClock.uptimeMillis();
                            sTmRead = sTmComposed = sTmFrosted = sTmMain = sTmUploaded = 0L;
                            sTmKind = i.hasExtra("src") ? "source" : "jpeg";
                        }
                        if (i.getBooleanExtra("off", false)) {
                            // A live wallpaper has no texture to fade back to - the way back
                            // is handing the surface to its player again.
                            if (VideoTakeover.videoPath()) {
                                sCurrentArtChecksum = 0;
                                sArt = null;
                                sFitted = null;
                                sFittedOf = null;
                                sLyricBlur = false;
                                sLyricBlurWant = false;
                                WallpaperArt.dropFrosted();
                                CoverStore.clear(c);
                                // The cover videos stay: coming back into cover mode on the same
                                // album then reloads from the cache instead of encoding again.
                                VideoTakeover.videoWindowTakeover(true);
                                return;
                            }
                            WallpaperArt.composedLater();
                            Bitmap from = fittedArt();
                            Bitmap to = sOrig;
                            // The look this fade belongs to, read before it starts. Anything
                            // arriving while it runs - a tap back into cover mode, the next
                            // track, a lyric switch - describes the look coming back, and this
                            // completion must not touch it: it clears the art that one has
                            // already set and the blur it has already asked for. Measured as the
                            // cover coming back sharp under its lyrics after the artwork was
                            // tapped out and quickly back in, and staying sharp for the song.
                            final int msgSeq = sMsgSeq;
                            final int artSeq = sArtSeq;
                            final boolean blurStillMine = blurMine;
                            if (fade && from != null && to != null) {
                                sLeaveAt = SystemClock.uptimeMillis();
                                sLeaveMs = sFadeMs;
                                WallpaperFade.startFade(from, to, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Kept when a cover is already on its way: it arrives
                                        // to find the plain wallpaper on screen, and applyArt
                                        // reads this to start it from there. Cleared here, the
                                        // cover that landed 18ms after this fade was taken for
                                        // a track change - a 180ms fade from the old cover,
                                        // which is a cut from the wallpaper (2026-09-29).
                                        if (artSeq == sArtSeq) sLeaveAt = 0L;
                                        if (blurStillMine && msgSeq == sMsgSeq) {
                                            sLyricBlur = false;
                                            sLyricBlurWant = false;
                                            WallpaperArt.dropFrosted();
                                        } else {
                                            Xp.log(TAG + "the lyrics were switched while the cover"
                                                    + " was fading out, leaving their blur alone");
                                        }
                                        if (artSeq != sArtSeq) {
                                            Xp.log(TAG + "a cover arrived while the last one was"
                                                    + " fading out, leaving the art to it");
                                            return;
                                        }
                                        sCurrentArtChecksum = 0;
                                        sArt = null;
                                        sFitted = null;
                                        sFittedOf = null;
                                        CoverStore.clear(cc);
                                        Xp.log(TAG + "art cleared");
                                    }
                                });
                                return;
                            }
                            sCurrentArtChecksum = 0;
                            sArt = null;
                            if (blurStillMine) {
                                sLyricBlur = false;
                                sLyricBlurWant = false;
                                WallpaperArt.dropFrosted();
                            }
                            CoverStore.clear(c);
                            Xp.log(TAG + "art cleared");
                        } else if (i.hasExtra("prekey") && !i.hasExtra("src")) {
                            WallpaperArt.applyKept(i.getStringExtra("prekey"), reload, fade);
                            return;
                        } else if (i.hasExtra("src")) {
                            // The tap's own message has put this very picture up already: the
                            // push behind it has nothing to add, and a second fade from the
                            // cover to itself is all it would make.
                            String pk = i.getStringExtra("prekey");
                            WallpaperArt.Composed kc = sComposed;
                            if (pk != null && pk.equals(sKeptShown) && kc != null
                                    && sArt == kc.sharp) {
                                Xp.log(TAG + "push for the cover the tap put up, passed over");
                                return;
                            }
                            WallpaperArt.composeFromSource(c, i.getStringExtra("src"), reload, fade,
                                    sCardMode, pk);
                            return;
                        } else {
                            byte[] jpg = i.getByteArrayExtra("jpg");
                            String file = i.getStringExtra("file");
                            // The image normally arrives as a file rather than as an extra -
                            // SystemUI writes it where both processes can read it, because a
                            // large enough JPEG does not survive the trip through Binder at
                            // all. See the send in Main.pushArt(). Read here rather than
                            // decodeFile() so that the rest of this is unchanged: the same
                            // decoder scales to the texture, and the same bytes are what gets
                            // written to this process's own copy on disk for the next start.
                            // A composed picture arriving means SystemUI does not know this
                            // process composes for itself: its question or our answer went
                            // missing, which is what happens when both processes restart at once
                            // and each one's hello lands before the other has a receiver. Left
                            // alone it stands for the life of the process, and every track then
                            // pays for a full-screen compose AND a JPEG encode (~180ms measured)
                            // over there, plus the decode here. Answering again fixes the next one.
                            sayHello(c);
                            if (jpg == null && file != null) jpg = WallpaperArt.readBytes(file);
                            Bitmap b = null;
                            if (jpg != null) {
                                b = decodeToTextureSize(jpg);
                                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                                crc.update(jpg);
                                sCurrentArtChecksum = crc.getValue();
                            } else if (file != null) {
                                b = BitmapFactory.decodeFile(file);
                                sCurrentArtChecksum = 0;
                            }
                            if (b == null) {
                                Xp.w(TAG + "art decode failed (jpg="
                                        + (jpg == null ? "null" : jpg.length + "B")
                                        + " file=" + file + ")");
                            } else {
                                applyArt(b, reload, fade);
                                // Show it first, write it to disk afterwards: the file only
                                // matters for the next cold start of this process, and a 100KB
                                // write in front of the upload is pure added latency.
                                if (jpg != null) WallpaperArt.saveArtLater(c, jpg);
                                return;
                            }
                        }
                        if (reload) WallpaperEngines.reloadTexture();
                    } else if ("shadeart".equals(op)) {
                        // The notification shade went up or came down. Swapping the DESKTOP
                        // texture is what puts the cover where the cards' glass samples, which is
                        // the only way it can ever be what they blur.
                        boolean on = i.getBooleanExtra("on", false);
                        // Refreshed on every message, including repeats: the repeats are the
                        // heartbeat that keeps the TTL from expiring under an open shade.
                        if (on) sShadeAt = android.os.SystemClock.uptimeMillis();
                        if (on != sShadeOn) {
                            sShadeOn = on;
                            Xp.log(TAG + "shade art " + (on ? "ON - desktop wallpaper is the cover"
                                    : "off - desktop wallpaper back to its own picture"));
                            WallpaperEngines.reloadDesktopTexture();
                        }
                    } else if ("lyricblur".equals(op)) {
                        final boolean on = i.getBooleanExtra("on", false);
                        sMsgSeq++;
                        if (!i.hasExtra("blurseq")
                                || WallpaperArt.takeBlurDecision(i.getLongExtra("blurseq", 0L), on, "lyrics")) {
                            WallpaperArt.setLyricBlur(on);
                        }
                    } else if ("reload".equals(op)) {
                        WallpaperEngines.reloadTexture();
                    } else if ("fadems".equals(op)) {
                        long v = i.getIntExtra("v", (int) sFadeMs);
                        sFadeMs = v < 60L ? 60L : (v > 1200L ? 1200L : v);
                        Xp.log(TAG + "crossfade is now " + sFadeMs + "ms");
                    } else if ("trackfadems".equals(op)) {
                        long v = i.getIntExtra("v", (int) sTrackFadeMs);
                        // Floored at a frame: the GPU fade divides by this.
                        sTrackFadeMs = v < 16L ? 16L : (v > 1200L ? 1200L : v);
                        Xp.log(TAG + "track-change crossfade is now " + sTrackFadeMs + "ms");
                    } else if ("hello".equals(op)) {
                        sayHello(c);
                    } else if ("gpufade".equals(op)) {
                        sGpuFadeOn = i.getBooleanExtra("on", !sGpuFadeOn);
                        if (sGpuFadeOn) sGpuFadeBroken = false;
                        Xp.log(TAG + "gpu fade " + (sGpuFadeOn ? "on" : "off")
                                + " (draw hooked=" + sDrawHooked + ", usable now="
                                + WallpaperFade.gpuFadeUsable() + ")");
                    } else if ("pcache".equals(op)) {
                        // The clock-colour answers kept until the wallpaper changes; see
                        // PaletteCache. `--ez clear true` drops them.
                        if (i.getBooleanExtra("clear", false)) PaletteCache.clear();
                        setResultData(PaletteCache.describe());
                    } else if ("vidbias".equals(op)) {
                        WallpaperVideo.setBias(i.getFloatExtra("bias", 0.25f));
                    } else if ("vidtex".equals(op)) {
                        // The motion-cover experiment, see WallpaperVideo. `--ez on false`
                        // stops it; `--ez stats true` only reports (and restarts the counters).
                        if (i.getBooleanExtra("stats", false)) {
                            String s = WallpaperVideo.statsAndReset();
                            Xp.log(TAG + "video: " + s);
                            setResultData(s);
                        } else if (!i.getBooleanExtra("on", true)) {
                            WallpaperVideo.stop("probe");
                            setResultData("off");
                        } else {
                            String path = i.getStringExtra("path");
                            if (path == null) {
                                path = CoverStore.video(c).getPath();
                            }
                            float bias = i.getFloatExtra("bias", -1f);
                            if (bias < 0f) {
                                bias = 0.25f;
                                try {
                                    CoverCompose.Source s = CoverCompose.readSource(
                                            CoverStore.source(c));
                                    if (s != null) bias = s.bias;
                                } catch (Throwable ignored) {
                                }
                            }
                            WallpaperVideo.start(c, path, bias);
                            setResultData("on " + path + " bias=" + bias
                                    + " (filesDir " + c.getFilesDir() + ")");
                        }
                    } else if ("nofrost".equals(op)) {
                        sSkipFrost = i.getBooleanExtra("on", !sSkipFrost);
                        Xp.log(TAG + "frosting during a fade is "
                                + (sSkipFrost ? "skipped" : "kept"));
                    } else if ("gen".equals(op)) {
                        // This generation's ledger and where it is drawing from. The mirror of
                        // SystemUI's `op gen`; also what a hot reload is judged by.
                        String report = genReport();
                        Xp.log(TAG + "gen " + report);
                        setResultData(report);
                    } else if ("retire_dryrun".equals(op)) {
                        // The rehearsal: retirement and adoption in this generation, no code
                        // swapped. See dryRun().
                        String report = dryRun(c);
                        Xp.log(TAG + "retire_dryrun " + report);
                        setResultData(report);
                    } else if ("state".equals(op)) {
                        Xp.log(TAG + "art=" + describe(sArt)
                                + " orig=" + describe(sOrig)
                                + " fading=" + (sFade != null)
                                + " gpuFade=" + (sGpuFade != null) + "/on=" + sGpuFadeOn
                                + "/broken=" + sGpuFadeBroken + "/hooked=" + sDrawHooked
                                + " nofrost=" + sSkipFrost
                                + " fadems=" + sFadeMs
                                + " engine=" + sKeyguardEngine
                                + " videoEngine=" + VideoTakeover.sVideoEngine);
                        setResultData(VideoTakeover.describeVideo());
                    } else if ("tail".equals(op)) {
                        // This process's recent log lines; see Xp.tail().
                        setResultData(Xp.tail(i.getStringExtra("grep"), i.getIntExtra("n", 40)));
                    } else if ("vgl".equals(op)) {
                        VideoTakeover.videoWindowTakeover(i.getBooleanExtra("on", true));
                    } else if ("timing".equals(op)) {
                        String rep = WallpaperDiag.timingReport();
                        for (String line : rep.split("\n")) Xp.log(TAG + line);
                        setResultData(rep);
                    } else if ("selftest".equals(op)) {
                        String rep = WallpaperDiag.selfTest();
                        for (String line : rep.split("\n")) Xp.log(TAG + line);
                        setResultData(rep);
                    } else {
                        Xp.log(TAG + "ops: cls --es name <fqcn> [--es grep x]"
                                + " | bmp --es name <fqcn>");
                    }
                } catch (Throwable t) {
                    Xp.w(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        }
        // Only SystemUI's half of the module talks to this one, besides adb.
        ProbeGuard.register(ctx, new IntentFilter(ACTION), TAG, Probe::new,
                "com.android.systemui", BuildConfig.APPLICATION_ID);
        Xp.log(TAG + "receiver registered for " + ACTION);
        // The backstop for a hide that never reached the desktop engine. The show needs none:
        // it comes as the keyguard locks, which is at the screen going off, not at the wake.
        try {
            IntentFilter screen = new IntentFilter(Intent.ACTION_USER_PRESENT);
            Gen.receiver(ctx, new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    sDesktopKgShown = false;
                    if (!sSharedLock) return;
                    Gen.post(WallpaperEngines::syncSharedLock);
                }
            }, screen, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Xp.w(TAG + "screen receiver failed: " + t);
        }
        // Unprompted as well as when asked: a restart of this process alone would otherwise
        // leave SystemUI on whatever it last heard, which may be nothing.
        sayHello(ctx);
    }

    // ------------------------------------------------------------------ hot reload

    /** Compositions in flight: a source push or a precompose. The check section refuses. */
    static final java.util.concurrent.atomic.AtomicInteger sComposing =
            new java.util.concurrent.atomic.AtomicInteger();
    /** JPEG writes in flight: the next generation restores the cover from what is on disk. */
    static final java.util.concurrent.atomic.AtomicInteger sArtSaving =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The feature that cannot be interrupted right now, or null.
     *
     * Asked on the main thread, in the same task as the teardown (Gen.retireOnMain); the
     * composer's counters are atomics. The rule is SystemUI's, only wider: a wallpaper restart is a flash the user barely sees, so
     * anything mid-GL, mid-compose or mid-video is refused rather than handed over.
     */
    static String busyReason() {
        if (sFade != null || sGpuFade != null || sUploadOverride != null
                || sUploading != null || sFadeInFlight || sLeaveAt != 0L) {
            return "a cover crossfade is running";
        }
        if (sComposing.get() > 0) return "a cover is being composed";
        if (sArtSaving.get() > 0) return "the cover is still being written";
        if (sLyricBlurWant != sLyricBlur) return "a lyric blur change is in flight";
        // The keep dim is not a reason to refuse: sKeepFrom/To/At/DurMs travel with the
        // handover and adoptMain() restarts the pump, so the transition carries on at the same
        // point on the shared clock. A KEEP wallpaper was being refused on every install that
        // landed inside its 100-300ms move.
        if (sGlTex != 0) return "a gpu crossfade holds a texture";
        if (WallpaperEngines.sVideoGlLive || WallpaperVideo.holdsGl()) return "the motion cover holds GL objects";
        if (VideoTakeover.sCoverVideoActive || VideoTakeover.sActiveEngine != null || VideoTakeover.sPathPinned) {
            return "the cover video owns the wallpaper window";
        }
        if (Boolean.TRUE.equals(VideoTakeover.sLockIsVideo) || (VideoTakeover.sLockIsVideo == null && VideoTakeover.sVideoEngine != null)) {
            return "the lock wallpaper is a live video";
        }
        return null;
    }

    // What a generation hands the next one. Index 0 is the layout version: an array this build
    // does not know is ignored whole, and the new generation adopts from disk as on a cold start.
    private static final int WALL_STATE_SCHEMA = 1;
    private static final int S_ART = 1, S_CARD = 2, S_ORIG = 3, S_ORIG_PRINT = 4,
            S_TEX_COVER = 5, S_BLUR = 6, S_BLUR_WANT = 7, S_BLUR_SEQ = 8,
            S_MSG_SEQ = 9, S_ART_SEQ = 10, S_REPORTED_W = 11, S_REPORTED_H = 12,
            S_SURFACE_W = 13, S_SURFACE_H = 14, S_FADE_MS = 15, S_TRACK_FADE_MS = 16,
            S_SKIP_FROST = 17, S_GPU_FADE = 18, S_GPU_BROKEN = 19, S_TEX_FIT = 20,
            S_KG_ENGINE = 21, S_DESK_ENGINE = 22, S_KG_TEXTURE = 23, S_KG_RENDERER = 24,
            S_DESK_RENDERER = 25, S_OEM_DARK = 26, S_OEM_DARK_KNOWN = 27,
            S_HOME_DARK = 28, S_HOME_DARK_KNOWN = 29, S_SHARED = 30, S_DESK_KG = 31,
            S_DESK_ON_LOCK = 32, S_SHADE = 33, S_SHADE_AT = 34, S_LOCK_VIDEO = 35,
            S_FRAME_REQ = 36, S_KEEP_DIM = 37, S_KEEP_FROM = 38, S_KEEP_TO = 39,
            S_KEEP_AT = 40, S_KEEP_MS = 41, S_GL_TID = 42, S_ASKS = 43, S_TOKEN = 44,
            S_CTX = 45, S_TEX_SIZES = 46;
    /** What every build of this schema hands over; later fields are read only when present. */
    private static final int S_LEN = 46;

    /**
     * The runtime state the next generation cannot look up again, and nothing else: settings
     * stay in the state file, and the caches (frosted copies, compositions, fade buffers) are
     * recomputed on demand. Everything here is a framework object or a primitive, which is what
     * the framework lets cross a generation.
     */
    static Object[] save() {
        return new Object[]{
                WALL_STATE_SCHEMA,
                sArt, sCardMode, sOrig, sOrigPrint, sTexShowsCover,
                sLyricBlur, sLyricBlurWant, sBlurSeq, sMsgSeq, sArtSeq,
                sReportedW, sReportedH, sSurfaceW, sSurfaceH,
                sFadeMs, sTrackFadeMs, sSkipFrost, sGpuFadeOn, sGpuFadeBroken, sTexFit,
                sKeyguardEngine, sDesktopEngine, sKeyguardTexture, sKeyguardRenderer,
                sDesktopRenderer, sOemDarken, sOemDarkenKnown, sHomeDarken, sHomeDarkenKnown,
                sSharedLock, sDesktopKgShown, sDesktopOnLock, sShadeOn, sShadeAt, VideoTakeover.sLockIsVideo,
                sFrameReq, sKeepDim, sKeepFrom, sKeepTo, sKeepAt, sKeepDurMs,
                sKeyguardGlTid, sAsks, ProbeGuard.learnedToken(), sCtx, texSizes(),
        };
    }

    /**
     * Every keyguard texture's last upload size, as texture, Rect pairs. A foldable has one
     * texture per screen, and the dimension hook answers each from this map; handing over only
     * sKeyguardTexture's left the other screen's on the OEM's own size until its next upload.
     */
    private static Object[] texSizes() {
        synchronized (sTexSizes) {
            Object[] out = new Object[sTexSizes.size() * 2];
            int i = 0;
            for (java.util.Map.Entry<Object, android.graphics.Rect> e : sTexSizes.entrySet()) {
                out[i++] = e.getKey();
                out[i++] = new android.graphics.Rect(e.getValue());
            }
            return out;
        }
    }

    /**
     * Puts the handed-over state in place. Runs on the framework's binder thread *before*
     * install(): the new hooks are live the moment they are installed, and an upload arriving
     * before sArt is back would see no cover and hand MiWallpaper the real wallpaper to decode.
     */
    static void adoptState(Object[] f) {
        if (f == null || f.length < S_LEN) return;
        if (!(f[0] instanceof Integer) || (Integer) f[0] != WALL_STATE_SCHEMA) {
            Xp.w(TAG + "saved wall state is not this build's; the cover comes back from disk");
            return;
        }
        if (f[S_ART] instanceof Bitmap) sArt = (Bitmap) f[S_ART];
        if (f[S_CARD] instanceof Boolean) sCardMode = (Boolean) f[S_CARD];
        if (f[S_ORIG] instanceof Bitmap) sOrig = (Bitmap) f[S_ORIG];
        sOrigPrint = intAt(f, S_ORIG_PRINT, sOrigPrint);
        sLyricBlur = boolAt(f, S_BLUR, sLyricBlur);
        sLyricBlurWant = boolAt(f, S_BLUR_WANT, sLyricBlurWant);
        sBlurSeq = longAt(f, S_BLUR_SEQ, sBlurSeq);
        sMsgSeq = intAt(f, S_MSG_SEQ, sMsgSeq);
        sArtSeq = intAt(f, S_ART_SEQ, sArtSeq);
        sReportedW = intAt(f, S_REPORTED_W, sReportedW);
        sReportedH = intAt(f, S_REPORTED_H, sReportedH);
        sSurfaceW = intAt(f, S_SURFACE_W, sSurfaceW);
        sSurfaceH = intAt(f, S_SURFACE_H, sSurfaceH);
        sFadeMs = longAt(f, S_FADE_MS, sFadeMs);
        sTrackFadeMs = longAt(f, S_TRACK_FADE_MS, sTrackFadeMs);
        sSkipFrost = boolAt(f, S_SKIP_FROST, sSkipFrost);
        sGpuFadeOn = boolAt(f, S_GPU_FADE, sGpuFadeOn);
        sGpuFadeBroken = boolAt(f, S_GPU_BROKEN, sGpuFadeBroken);
        sTexFit = boolAt(f, S_TEX_FIT, sTexFit);
        sKeyguardEngine = f[S_KG_ENGINE];
        sDesktopEngine = f[S_DESK_ENGINE];
        sKeyguardTexture = f[S_KG_TEXTURE];
        sKeyguardRenderer = f[S_KG_RENDERER];
        sDesktopRenderer = f[S_DESK_RENDERER];
        sOemDarken = boolAt(f, S_OEM_DARK, sOemDarken);
        sOemDarkenKnown = boolAt(f, S_OEM_DARK_KNOWN, sOemDarkenKnown);
        sHomeDarken = boolAt(f, S_HOME_DARK, sHomeDarken);
        sHomeDarkenKnown = boolAt(f, S_HOME_DARK_KNOWN, sHomeDarkenKnown);
        sSharedLock = boolAt(f, S_SHARED, sSharedLock);
        sDesktopKgShown = f[S_DESK_KG] instanceof Boolean ? (Boolean) f[S_DESK_KG] : null;
        sDesktopOnLock = boolAt(f, S_DESK_ON_LOCK, sDesktopOnLock);
        sShadeOn = boolAt(f, S_SHADE, sShadeOn);
        sShadeAt = longAt(f, S_SHADE_AT, sShadeAt);
        VideoTakeover.sLockIsVideo = f[S_LOCK_VIDEO] instanceof Boolean ? (Boolean) f[S_LOCK_VIDEO] : null;
        sFrameReq = intAt(f, S_FRAME_REQ, sFrameReq);
        synchronized (KEEP_LOCK) {
            sKeepDim = floatAt(f, S_KEEP_DIM, sKeepDim);
            sKeepFrom = floatAt(f, S_KEEP_FROM, sKeepFrom);
            sKeepTo = floatAt(f, S_KEEP_TO, sKeepTo);
            sKeepAt = longAt(f, S_KEEP_AT, sKeepAt);
            sKeepDurMs = intAt(f, S_KEEP_MS, sKeepDurMs);
        }
        sKeyguardGlTid = intAt(f, S_GL_TID, sKeyguardGlTid);
        sAsks = intAt(f, S_ASKS, sAsks);
        sTexShowsCover = sArt != null;
        if (f[S_CTX] instanceof Context) sCtx = (Context) f[S_CTX];
        // Here rather than in adoptMain: the dimension hook is live as soon as install() runs.
        if (f.length > S_TEX_SIZES && f[S_TEX_SIZES] instanceof Object[]) {
            Object[] sizes = (Object[]) f[S_TEX_SIZES];
            for (int i = 0; i + 1 < sizes.length; i += 2) {
                if (sizes[i] != null && sizes[i + 1] instanceof android.graphics.Rect) {
                    sTexSizes.put(sizes[i], (android.graphics.Rect) sizes[i + 1]);
                }
            }
        }
        ProbeGuard.adoptLearnedToken(f[S_TOKEN] instanceof String ? (String) f[S_TOKEN] : null);
    }

    private static int intAt(Object[] f, int i, int had) {
        return f[i] instanceof Number ? ((Number) f[i]).intValue() : had;
    }

    private static long longAt(Object[] f, int i, long had) {
        return f[i] instanceof Number ? ((Number) f[i]).longValue() : had;
    }

    private static float floatAt(Object[] f, int i, float had) {
        return f[i] instanceof Number ? ((Number) f[i]).floatValue() : had;
    }

    private static boolean boolAt(Object[] f, int i, boolean had) {
        return f[i] instanceof Boolean ? (Boolean) f[i] : had;
    }

    /**
     * The rest of the adoption, on the main thread: the R8 names read back off the engine it
     * was handed, the texture's size put back where the dimension hook looks, and then the
     * cold-start registration path. No reload is asked for - the texture already shows the
     * cover, and re-running onSurfaceCreated would only upload the same picture again.
     */
    static void adoptMain(Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
        Object eng = sKeyguardEngine != null ? sKeyguardEngine : sDesktopEngine;
        if (eng != null) {
            try {
                EngineNames.resolve(eng);
            } catch (Throwable t) {
                Xp.w(TAG + "engine names not resolved after the reload: " + t);
            }
        }
        // A build that handed over no size map: the one texture it knew of, at the last size.
        if (sKeyguardTexture != null && sReportedW > 0 && sReportedH > 0
                && !sTexSizes.containsKey(sKeyguardTexture)) {
            sTexSizes.put(sKeyguardTexture,
                    new android.graphics.Rect(0, 0, sReportedW, sReportedH));
        }
        if (sCtx == null) {
            Xp.w(TAG + "no context after the reload; the receivers wait for a cold start");
            return;
        }
        register(sCtx);
        if (sKeepAt != 0L) WallpaperEngines.startKeepPump();
        Xp.log(TAG + "adopted art=" + describe(sArt) + " card=" + sCardMode
                + " blur=" + sLyricBlur + "/" + sLyricBlurWant
                + " engine=" + (sKeyguardEngine == null ? "none" : "yes"));
    }

    /**
     * What retirement takes back besides the ledger's registrations. The cover itself is
     * handed over, so it is not touched; everything here is a cache this generation was the
     * last holder of.
     */
    static void retireForReload() {
        sKeepPumping = false;
        KEEP_MAIN.removeCallbacks(WallpaperEngines.KEEP_PUMP);
        WallpaperVideo.retire();
        sComposed = null;
        sFitted = null;
        sFittedOf = null;
        WallpaperArt.dropFrosted();
        synchronized (sScreenArts) {
            sScreenArts.clear();
            sScreenArtOf = null;
        }
        sShadeFitted = null;
        sShadeFitOf = null;
        // The two fade buffers are screen-sized each; the check section has already said no
        // fade is running, so nothing is reading them.
        sFadeBuf = null;
        sFadeBuf2 = null;
        sKeptShown = null;
        VideoTakeover.sFadeFrom = null;
        Xp.log(TAG + "retired: cover handed over, caches let go");
    }

    /**
     * The rehearsal: the retirement steps, the ledger sweep and the adoption path, in this
     * generation and with no code swapped. `op retire_dryrun`, the probe only.
     */
    static String dryRun(Context ctx) {
        if (Looper.myLooper() != Looper.getMainLooper()) return "not on the main thread";
        String busy = busyReason();
        if (busy != null) return "deferred: " + busy;
        Object[] state = save();
        String before = Gen.report();
        String swept;
        Gen.quiescing = true;
        try {
            retireForReload();
            swept = Gen.sweepForDryRun(2000L) ? Gen.report() : "sweep timed out";
        } catch (Throwable t) {
            swept = "failed: " + t;
        } finally {
            Gen.quiescing = false;
        }
        sRegistered = false;
        adoptState(state);
        adoptMain(ctx);
        return "retire_dryrun before{" + before + "} swept{" + swept
                + "} after{" + Gen.report() + "}";
    }

    /**
     * What this generation holds in the wallpaper process, and where it is drawing from.
     * `adb shell am broadcast -a com.os4.musiccover.WPROBE --es op gen`.
     */
    private static String genReport() {
        return Gen.report() + " " + HotReload.status()
                + " art=" + describe(sArt) + " orig=" + describe(sOrig)
                + " card=" + sCardMode + " blur=" + sLyricBlur + "/" + sLyricBlurWant
                + " tex=" + sReportedW + "x" + sReportedH + "@" + sSurfaceW + "x" + sSurfaceH
                + " engine=" + (sKeyguardEngine == null ? "none" : "yes")
                + " fade=" + (sFade != null || sGpuFade != null)
                + " gpu=" + sGpuFadeOn + "/" + sDrawHooked + "/" + sGpuFadeBroken
                + " glTex=" + sGlTex + " video=" + (VideoTakeover.sLockIsVideo == null ? "?" : VideoTakeover.sLockIsVideo)
                + " takeover=" + VideoTakeover.sCoverVideoActive + " texfit=" + sTexFit
                + " keep=" + sKeepDim;
    }

    /** Tells SystemUI this build composes covers from their source. See Main.sWpComposes. */
    private static void sayHello(Context c) {
        try {
            Intent out = new Intent("com.os4.musiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", "wphello");
            out.putExtra("composes", true);
            ProbeGuard.send(c, out);
        } catch (Throwable t) {
            Xp.w(TAG + "hello failed: " + t);
        }
    }

    /**
     * Puts a new picture in as the art: the fade from what is on screen now, or a plain reload.
     * Main thread.
     */
    static void applyArt(Bitmap b, boolean reload, boolean fade) {
        sTmMain = SystemClock.uptimeMillis();
        // Read before sArt moves: on the way into cover mode this is the lock wallpaper, and on
        // a track change it is the album that is on screen right now.
        Bitmap from = fittedArt();
        // Already showing a cover means this picture replaces another one: a track change, which
        // is the fade that has nothing to keep company with and gets the short duration. With no
        // cover yet, what it fades from is the lock wallpaper - that is cover mode opening, and
        // it travels with the clock. See sTrackFadeMs.
        boolean trackChange = from != null;
        // Arriving while the last cover fades out. sArt still names that cover until the fade's
        // end, but the eased fade has put the wallpaper on screen long before then: taken at its
        // word, this faded from the old cover to the same cover - a cut from the wallpaper
        // straight to the art, on every quick tap back in. Past half way, it is the wallpaper
        // that is showing, and the way in starts from there.
        long leaveAt = sLeaveAt;
        sLeaveAt = 0L;
        if (leaveAt != 0L && sOrig != null && sLeaveMs > 0L) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - leaveAt) / (float) sLeaveMs);
            if (1f - (1f - t) * (1f - t) * (1f - t) >= 0.5f) {
                from = null;
                trackChange = false;
            }
        }
        if (from == null) from = sOrig;
        // On a live lock wallpaper the cover that is on screen is the art
        // this process already holds, whether or not it could be "fitted"
        // - the fitter knows a still wallpaper's texture size, and a video
        // wallpaper has no such texture. Read before sArt moves.
        if (from == null && VideoTakeover.videoPath()) from = sArt;
        // The video path's crossfade reads this from its worker, where
        // the composed cover video is built; the still path hands it
        // straight to startFade() below.
        VideoTakeover.sFadeFrom = from;
        // A cover carries what the lyrics wanted at the moment it was sent, so a track change is
        // also where the two processes settle any disagreement: a lost broadcast, or a switch
        // that never landed. Applied after `from` and before `to`, so the new cover simply
        // arrives with the right finish instead of fading in sharp and frosting a beat later.
        if (sLyricBlur != sLyricBlurWant) {
            sLyricBlur = sLyricBlurWant;
            Xp.log(TAG + "lyric blur " + (sLyricBlur ? "on" : "off") + " with the new cover");
        }
        sArt = b;
        sAsks = 0;
        sFitted = null;
        sFittedOf = null;
        // Scale here, not on the GL thread - on the still path. A live wallpaper's cover is built
        // by the video worker from sArt, and this was a full-screen frosting on the main thread
        // (110-140ms) in front of it on every entry under the lyrics, for a picture nothing read.
        Bitmap to = VideoTakeover.videoPath() ? null : fittedArt();
        Xp.log(TAG + "art set " + describe(b));
        if (VideoTakeover.videoPath()) VideoTakeover.videoWindowTakeover(false);
        else if (fade && from != null && to != null) {
            WallpaperFade.startFade(from, to, null, trackChange ? sTrackFadeMs : sFadeMs);
        } else if (reload) WallpaperEngines.reloadTexture();
        tellArtShown();
    }

    /**
     * The new cover has started onto the screen. SystemUI's square card holds its own swap for
     * this: it has its art the moment the track changes, while this side still has the broadcast,
     * the composition and the upload in front of it - measured 70-160ms - so the card used to
     * turn over visibly ahead of the blurred background it sits on.
     */
    private static void tellArtShown() {
        Context c = sCtx;
        if (c == null) return;
        try {
            Intent out = new Intent("com.os4.musiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", "wpart");
            ProbeGuard.send(c, out);
        } catch (Throwable t) {
            Xp.w(TAG + "wpart failed: " + t);
        }
    }
    /** The newest source asked for; a composition overtaken by a newer one is dropped. */
    static volatile int sSrcSeq;

    /**
     * The last composition, kept past the cover that showed it. Composer thread only.
     *
     * Every way into cover mode composed from scratch - 55-76ms here, and the frosting for the
     * lyrics after it - and all of it stood between the tap and the first frame of the fade, so
     * the blurred background came up behind the clock that was already moving. Kept, the same
     * song's way back in is a read of the source and nothing else; and SystemUI hands over the
     * source ahead of a likely entry (op precompose) so the first way in can find it too.
     *
     * One screen-sized bitmap and a ~0.3MB small frosting. Let go COMPOSED_KEEP_MS after the cover
     * stops showing it, PRECOMPOSED_KEEP_MS after a precompose nothing used.
     */
    static volatile WallpaperArt.Composed sComposed;

    /** The precompose key of the cover the tap last put up (applyKept). Main thread. */
    static String sKeptShown;
    /** CRC32 of the pushed JPEG, the cover video's cache key. 0 means "work it out". */
    static volatile long sCurrentArtChecksum;

    /**
     * Says something to SystemUI. Foreground, like every other broadcast between the two
     * processes: from the background queue a broadcast took a measured ~500ms to arrive, and
     * SystemUI is holding its half of the transition for this one.
     */
    static void tellSystemUi(String op, String why) {
        Context c = sCtx;
        if (c == null) return;
        try {
            Intent out = new Intent("com.os4.musiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", op);
            out.putExtra("why", why);
            ProbeGuard.send(c, out);
            Xp.log(TAG + "told SystemUI: " + op + " (" + why + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "tellSystemUi(" + op + ") failed: " + t);
        }
    }

    /**
     * The still cover's frosting for the lyrics is starting (or is there already), with the
     * decision it answers - SystemUI holds the lyrics back for it. See LockLyrics.onBlurShown.
     */
    static void tellBlurShown(String why) {
        Context c = sCtx;
        if (c == null) return;
        try {
            Intent out = new Intent("com.os4.musiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", "blurshown");
            out.putExtra("why", why);
            out.putExtra("blurseq", sBlurSeq);
            ProbeGuard.send(c, out);
            Xp.log(TAG + "told SystemUI: blurshown at " + sBlurSeq + " (" + why + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "blurshown failed: " + t);
        }
    }

    /**
     * The bitmap the GL upload should get, at the size the texture should be - or null when
     * there is nothing to change.
     *
     * Null for the three cases that must not be touched: the experiment is off, the surface size
     * is not known yet (the first upload of a process runs before onSurfaceChanged has set it),
     * or the bitmap is already that size. Cached per source, because the source is either the
     * module's own art or the OEM's one wallpaper and both repeat - and per SIZE within that,
     * because a foldable alternates between its screens and each wants its own cut.
     *
     * The size comes from sSurfaceW/H, which the caller has just read off the renderer doing
     * this upload. Taking it from anywhere else is what cropped the cover for one screen and
     * handed it to another.
     */
    static Bitmap screenSized(Bitmap src) {
        int w = sSurfaceW, h = sSurfaceH;
        if (!sTexFit || src == null || w <= 0 || h <= 0) return null;
        if (src.getWidth() == w && src.getHeight() == h) return null;
        Long key = ((long) w << 32) | (h & 0xffffffffL);
        synchronized (sScreenArts) {
            // A new source makes every cut of the old one useless at once.
            if (sScreenArtOf != src) {
                sScreenArts.clear();
                sScreenArtOf = src;
            }
            Bitmap have = sScreenArts.get(key);
            if (have != null && !have.isRecycled()) return have;
        }
        // Outside the lock: the other screen's upload should not wait on this one's crop.
        Bitmap fitted;
        try {
            fitted = WallpaperArt.centerCrop(src, w, h);
        } catch (Throwable t) {
            Xp.w(TAG + "screen-size fit failed: " + t);
            return null;
        }
        // Nothing is recycled here. The old cut used to be freed on the spot, which is right
        // when there is one screen and wrong the moment there are two: the other screen's
        // renderer can still be drawing from the cut this one is replacing, and a recycled
        // bitmap under GL is a black wallpaper, not an exception anyone sees. The collector
        // frees them once the map drops them.
        int held;
        synchronized (sScreenArts) {
            // Another source arrived while this one was cropped: the cut is still right for
            // this upload, it is only not kept.
            if (sScreenArtOf == src) sScreenArts.put(key, fitted);
            held = sScreenArts.size();
        }
        Xp.log(TAG + "texture fitted to the screen: " + describe(src)
                + " -> " + describe(fitted) + " (" + held + " cut(s) held)");
        return fitted;
    }

    static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof Bitmap) {
            Bitmap b = (Bitmap) o;
            return "Bitmap[" + b.getWidth() + "x" + b.getHeight() + " " + b.getConfig() + "]";
        }
        String s = String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
