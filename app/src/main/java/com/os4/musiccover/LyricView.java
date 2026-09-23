package com.os4.musiccover;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Collections;
import java.util.List;

/**
 * The lock screen's lyrics: one view, drawn by hand, between the collapsed clock and the card.
 *
 * Every animated value belongs to a LINE, never to a slot on screen. The first version kept nine
 * TextViews and handed each its neighbour's text on a line change, so brightness, size and
 * weight stayed with the slot while the words moved - the line arriving at the focus snapped to
 * full brightness and the one leaving snapped dark, and only the far rows animated at all.
 * Here a line carries its own scroll position, emphasis, blur and word fill from the moment it
 * is parsed until the song changes.
 *
 * - Position: every line springs toward the scroll target, but only once its own start delay has
 *   passed - the lines below the focus set off one after another, so the stack flows up rather
 *   than moving as a slab. The spring is also a little softer further from the focus.
 * - Emphasis: one 0..1 per line. Brightness and scale both derive from it. The text is laid out
 *   once - the weight never changes, because a weight change re-wraps the line.
 * - Depth: a line that is not being sung is blurred by its distance from the one that is, drawn
 *   as a blurred bitmap made once per line and distance. A line is sharp while it moves and while it has any
 *   emphasis, and goes out of focus once it has settled - which keeps the motion clean and means
 *   no line ever switches between the blurred and the word-by-word path mid-blur.
 * - Words: a line with word timing is drawn a syllable at a time. Each syllable lifts a little
 *   as it is sung; a long one (a held note) also glows and swells while it lasts. The sung part
 *   is at the line's brightness and the rest at UNSUNG of it, through a soft-edged mask.
 * - Edges: a line fades as it approaches the top or bottom of the band.
 *
 * Frames are only asked for while something moves. Otherwise the view waits for its own
 * pre-draw, which fires whenever anything else in the keyguard window animates, and for
 * LockLyrics' tick, which wakes it at the next line start.
 */
final class LyricView extends View {

    private static final String TAG = "[MCLyric] ";

    // ---- the look. Sizes are sp/dp; nothing here is a pixel.
    private static final float TEXT_SP = 25f;
    private static final float TRANS_SP = 15f;
    /** The background vocal under a line: smaller, dimmer, and lifting less than the lead. */
    private static final float BG_SP = 17f;
    private static final int BG_WEIGHT = 500;
    private static final float BG_GAP_DP = 3f;
    private static final float BG_ALPHA = 0.72f;
    private static final float BG_LIFT = 0.6f;
    private static final int TRANS_WEIGHT = 500;
    /** Between one line's last row (or its translation) and the next line. */
    private static final float GAP_DP = 22f;
    private static final float TRANS_GAP_DP = 5f;
    /**
     * A line that is not being sung - and the unsung part of the one that is, which Apple draws at
     * the same level (measured: the arriving line's text is 146 on a 34 ground before its first
     * word and 146 after it is sharp, the same as a settled inactive line).
     */
    private static final float INACTIVE = 0.40f;
    /** The unsung words of the singing line, as a share of its emphasis above INACTIVE. None. */
    private static final float UNSUNG = 0f;
    /** Lines already sung, above the focus, are further back than the ones to come. */
    private static final float ABOVE_ALPHA = 0.6f;
    /** How saturated the dim text's tint may be; Apple's reads as a pale version of the cover. */
    private static final float TINT_SAT = 0.28f;
    private static final float TRANS_ALPHA = 0.62f;
    private static final float INACTIVE_SCALE = 0.97f;
    /** Half the width of the soft edge between sung and unsung, in text sizes. */
    private static final float FEATHER_EM = 0.45f;
    /**
     * Short on purpose: the top and bottom lines settle inside this zone, and at 44dp, on top of
     * their blur and the inactive level, they all but vanished.
     */
    private static final float EDGE_FADE_DP = 26f;
    /**
     * Where the singing line's top sits, as a fraction down the band.
     *
     * Only a fraction of a row now, not of the block: anchorY lays the rows out from the band's
     * middle, so this decides how many of them sit above the focus - which is what the depth blur
     * is measured from, and so how much of the stack above the singing line is in focus - and no
     * longer where the block is. It can be turned without touching the margins. On this band,
     * 0.23 holds one line above the focus, 0.40 two, 0.50 three.
     */
    private static final float ANCHOR = 0.40f;
    /**
     * The room between the lyrics and the clock, and between them and the media card.
     *
     * One number for both ends. The band is what is left of the screen between the clock's ink
     * and the card's top, and the lyrics are a whole number of rows laid out from its middle, so
     * two different numbers here came out as one margin visibly wider than the other: on
     * 2026-09-17 the first row sat 52dp below the clock with the last 37dp above the card.
     */
    /** A band shorter than this many rows of text is not worth showing lyrics in. */
    private static final float MIN_BAND_ROWS = 2.4f;
    /**
     * How long the block takes to slide to a new centre, once the band or the rows in it change.
     *
     * The centring is a step function: it asks how many of the rows fit between the clock and the
     * card, and one row more or less moves the block half a row at once. The band crosses those
     * thresholds while it is still moving - on the way into cover mode the clock is collapsing and
     * the card rising, so the count goes 4, 5, 6 as the room appears - and each crossing used to
     * land in a single frame. Same when a line long enough to wrap scrolls into the block: it is
     * two rows tall where its neighbours are one, so the block's height and its centre move
     * together. Easing the correction turns both into a slide.
     *
     * Short enough to read as the block settling rather than as the lyrics lagging behind: the
     * band's own ends are not eased at all, so everything the clock and the card do is still
     * tracked frame for frame.
     */
    private static final float TAU_ANCHOR = 0.09f;

    // ---- depth
    /**
     * Blur radius per row of distance beyond the first, up to BLUR_MAX_ROWS. The lines right
     * next to the singing one stay sharp: blurring the next line as well left it unreadable
     * behind the edge fade (recording 02:23).
     */
    private static final float BLUR_NEXT_DP = 0.9f;
    private static final float BLUR_DP_PER_ROW = 1.6f;
    private static final int BLUR_MAX_ROWS = 4;
    /** A line going out of focus does it fast; one coming in clears a little slower. */
    private static final float TAU_BLUR_IN = 0.05f;
    private static final float TAU_BLUR_OUT = 0.07f;
    /**
     * How much brighter than SDR white the singing words are drawn when HDR is on - Apple
     * Music's highlight, asked to be obvious. The window's headroom is set above this.
     */
    private static final float HDR_GAIN = 3f;
    /** The window is switched to HDR this long before a glow starts, so it is ready for it. */
    private static final int HDR_ARM_MS = 250;
    private static final android.graphics.ColorSpace EXTENDED =
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.EXTENDED_SRGB);
    private static final float BLUR_ALPHA_PER_DP = 0.12f;

    // ---- words
    /** How far a syllable rises as it is sung. */
    private static final float LIFT_DP = 1.8f;
    /** A syllable shorter than this still takes this long to rise, so quick words do not jitter. */
    private static final int LIFT_MIN_MS = 320;
    /** A syllable at least this long is a held note, and glows. */
    private static final int GLOW_MIN_MS = 1000;
    private static final float GLOW_DP = 9f;
    private static final float GLOW_ALPHA = 0.55f;
    private static final float GLOW_SWELL = 0.045f;
    /** How long a held note's glow takes to go after the note ends. */
    private static final int GLOW_TAIL_MS = 380;

    // ---- the motion: AMLL's (amll-dev/applemusic-like-lyrics, lyric-player/base), which is
    // what Apple's is taken to be. Not a bouncing spring - an over-damped one (ratio ~1.1) - and
    // what makes it read as water is the ripple: every line sets off a little after the one
    // above it, top of the view downwards.
    /** Normal play: stiffness between these, faster the closer the two lines are in time. */
    private static final float K_MIN = 170f, K_MAX = 220f;
    private static final int IV_MIN = 100, IV_MAX = 800;
    /** Damping is sqrt(stiffness) times this, mass 1. */
    private static final float DAMPING_MULT = 2.2f;
    /** A seek or an interlude: slower and softer. */
    private static final float K_SLOW = 90f, C_SLOW = 15f;
    /** The spring a seek travels on: softer than a line change, since the distance is arbitrary. */
    private static final float K_SEEK = 120f, C_SEEK = 24f;
    /**
     * The fastest the lyrics ever scroll, in band-heights per second.
     *
     * A spring's force grows with distance, so without a cap a seek across a whole song reaches
     * its target within a frame or two - technically animated, indistinguishable from a cut. The
     * cap is what turns that into a scroll you can follow, and it is in band-heights rather than
     * pixels so it means the same thing on every screen. An ordinary line change never reaches
     * it: one line's height carries a peak of a few hundred pixels a second against a cap in the
     * thousands, so this costs the normal animation nothing.
     */
    private static final float SEEK_SPEED_BANDS = 8f;
    /** The ripple: 50ms more per line from the top of the view, shrinking past the focus. */
    private static final float RIPPLE_MS = 50f;
    private static final float RIPPLE_DECAY = 1f / 1.05f;
    /** The stack moves to a line this long before its first word (measured 1.07s). */
    private static final long LEAD_MS = 1000L;
    /** Emphasis arrives quickly and leaves in one frame: the sung line drops out at once. */
    private static final float TAU_EMPH_IN = 0.08f;
    private static final float TAU_EMPH_OUT = 0.016f;
    /** AMLL's scale spring (stiffness 100, damping 25) settles in about this time constant. */
    private static final float TAU_SCALE = 0.15f;
    private static final float TAU_SHOW = 0.18f;
    /** Out faster than in: the clock starts growing into the lyrics' space at once. */
    private static final float TAU_HIDE = 0.06f;
    /**
     * How far the lyrics float up into place as they appear, and back down as they leave.
     *
     * A wake already moves them: the band is measured from the clock's live ink, so on the way
     * in from the AOD they ride the collapse down into place. A two-finger switch has no such
     * movement behind it - cover mode is already on and the clock is already small - so the
     * lyrics simply materialised where they were going to be. This gives the switch the same
     * arrival, and the same departure in reverse.
     *
     * Driven by `show` rather than by a timer of its own, so the movement and the fade are the
     * same event: in on TAU_SHOW, out on TAU_HIDE, and an arrival interrupted half way turns
     * around from where it is instead of from the far end.
     */
    private static final float FLOAT_DP = 26f;
    /** A gap between lines at least this long gets the interlude dots. */
    private static final int LULL_MS = 4000;

    // ---- interlude dots, in text sizes and milliseconds (see drawDots)
    private static final float DOT_EM = 0.25f;
    private static final float DOT_GAP_EM = 0.43f;
    private static final float DOT_CENTER_EM = 0.9f;
    private static final float DOTS_SLOT_EM = 1.8f;
    private static final float DOT_DIM = 0.18f;
    private static final float DOT_RAMP = 0.7f;
    /**
     * The breath: the whole group swells and shrinks about its centre by this much either way.
     * It was +-13.5% of each dot's radius on a 4.2s cosine - too slow and too small to read as
     * breathing (user, 2026-09-16).
     */
    private static final float DOT_BREATH = 0.2f;
    /** About this long a breath, stretched so a whole number of them fits the interlude. */
    private static final long DOT_BREATH_MS = 2400L;
    /** The share of a breath spent swelling; the shrink is slower, like breathing out. */
    private static final float DOT_INHALE = 0.42f;
    /** How long the breath takes to reach its full depth once the interlude starts. */
    private static final long DOT_BREATH_IN_MS = 700L;
    private static final long DOT_APPEAR_MS = 1200L;
    /** Out: a small swell, then shrinking to nothing, ending this long before the scroll. */
    private static final long DOT_EXIT_MS = 480L;
    private static final long DOT_EXIT_LEAD_MS = 120L;
    private static final float DOT_EXIT_BACK = 1.7f;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bgPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int tintSrc = -1;
    private float tintR = 1f, tintG = 1f, tintB = 1f;
    private final Matrix gradMatrix = new Matrix();
    private final LinearGradient[] grads = new LinearGradient[3];
    private final long[] gradHi = {-1L, -1L, -1L}, gradLo = {-1L, -1L, -1L};
    /** The row being drawn: where its gradient crosses (NaN = no gradient) and its two levels. */
    private float rowAt = Float.NaN, rowFeather, rowSungA, rowUnsungA;

    private final float density;
    private float textPx;
    private final float liftPx, glowPx;
    /** Room around a line for its glow and lift, and around a blurred node for the blur. */
    private int wordPad;
    private final int blurPad;
    private float sidePx;
    /** The typography and width currently represented by the layouts and blur cache. */
    private LyricStyle layoutStyle;

    // ---- content, rebuilt when the lines or the width change
    private int version = -1;
    private List<LyricLine> lines = Collections.emptyList();
    private int layoutWidth = -1;
    private StaticLayout[] main = new StaticLayout[0];
    private StaticLayout[] trans = new StaticLayout[0];
    private StaticLayout[] bgLay = new StaticLayout[0];
    private float[][] charXBg = new float[0][];
    /** Top of each line in content coordinates, and its full height with translation. */
    private float[] base = new float[0];
    private float[] height = new float[0];
    private float[][] charX = new float[0][];
    /**
     * Each line's blurred picture, at the radius its distance asks for. A bitmap, not a
     * RenderNode with a blur effect: that was the first version, and on this phone the node
     * drew nothing at all - every line vanished the moment it settled (recording 02:52).
     */
    /**
     * Indexed by line and distance - 1, kept for as long as the line is near the focus. It used
     * to be three slots a line with the farthest evicted, and a line's distance walks 4, 3, 2, 1,
     * 0, 2, 3 - so every line change threw pictures away that the next one asked for again: a
     * dozen blurs, bitmaps and texture uploads landing in the middle of every scroll.
     */
    private Bitmap[][] blurBmp = new Bitmap[0][];
    /** Pictures asked of the blur thread and not back yet, as line * 8 + distance. */
    private final java.util.HashSet<Integer> blurPending = new java.util.HashSet<>();
    /** Bumped by every rebuild, so a picture made for the previous layout is thrown away. */
    private int buildGen;
    /** The lines and width a layout is on its way for, or -1 with none in the air. */
    private int wantVersion = -1, wantWidth = -1;
    /** The translation switch the layout in the air is for; -1 above means none is. */
    private boolean wantTrans;
    private LyricStyle wantStyle;
    /** The translation switch the layout now in use was made under. */
    private boolean builtTrans = true;
    /** Diagnostics: how long the last layout took on its thread. */
    private long layoutMs;
    /** From this distance on the blur is wide enough to be made at a quarter of the resolution. */
    private static final int BLUR_QUARTER_ROWS = 3;
    private final android.graphics.RectF bmpDst = new android.graphics.RectF();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // ---- per-line animated state
    private float[] scroll = new float[0];
    private float[] vel = new float[0];
    /** What the line is springing to, what it will spring to, and when it switches. */
    private float[] aim = new float[0];
    private float[] nextAim = new float[0];
    private long[] aimAt = new long[0];
    private float[] emph = new float[0];
    /** A line-timed line's brightness: on while it is sung. */
    private float[] lit = new float[0];
    private float[] scale = new float[0];
    private float[] blur = new float[0];
    /** Where the interlude slot before each line sits; NaN where the gap is short. */
    private float[] dotsTop = new float[0];

    /** The line the stack is on, the line whose interlude is showing (-1), and both as one key. */
    private int focus = -1;
    private int dotsFor = -1;
    /** The scroll spring for the current move, set per move like AMLL's policy. */
    private float springK = K_SLOW, springC = C_SLOW;
    private boolean dotsWasShowing;
    private int focusKey = Integer.MIN_VALUE;
    private int ms;
    private float show;
    private float bandTop, bandBottom;
    private final float[] bandBounds = new float[2];
    private boolean bandOk;
    /**
     * The centring correction in force this frame, and the one the geometry is asking for, both
     * off the uncentred anchor and both in this view's pixels. Equal at rest; they differ only
     * while the block is sliding to a new centre (see TAU_ANCHOR).
     */
    private float anchorFix, anchorFixWant;

    private long lastStep;

    private boolean looping;
    private final int[] loc = new int[2];

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            noteFrameGap(now());
            looping = false;
            if (step()) invalidate();
            if (needsFrames()) {
                looping = true;
                postOnAnimation(this);
            } else {
                lastLoopFrame = 0L;
            }
        }
    };

    private final ViewTreeObserver.OnPreDrawListener preDraw =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (step()) invalidate();
                    if (!looping && needsFrames()) kick();
                    return true;
                }
            };

    LyricView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        liftPx = LIFT_DP * density;
        glowPx = GLOW_DP * density;
        blurPad = (int) Math.ceil((BLUR_NEXT_DP + BLUR_DP_PER_ROW * BLUR_MAX_ROWS) * density * 2f);
        paint.setColor(0xFFFFFFFF);
        transPaint.setColor(0xFFFFFFFF);
        bgPaint.setColor(0xFFFFFFFF);
        applyPaintStyle(LockLyrics.sStyle);
        // No frame rate is asked for here. The keyguard window renders at 60Hz on this 120Hz
        // panel unless the screen is touched, and neither setRequestedFrameRate on this view nor
        // a 120Hz vote on the window's own layer moved HyperOS off that (SurfaceFlinger dumps,
        // 2026-09-16). The user's call: the touch boost is enough, do not hold the panel up.
        // Touches go through to the lock screen: the double tap and the swipes are the OEM's.
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The screen's short side, which the text column is as wide as - see LyricStyle.sidePx. */
    private int shortSide() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.min(dm.widthPixels, dm.heightPixels);
    }

    private void applyPaintStyle(LyricStyle style) {
        layoutStyle = style;
        textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.sizeSp,
                getResources().getDisplayMetrics());
        wordPad = (int) Math.ceil(glowPx * 1.6f + liftPx + textPx * GLOW_SWELL);
        sidePx = style.sidePx(getWidth(), shortSide(), density, textPx);
        paint.setTextSize(textPx);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, style.weight, false));
        transPaint.setTextSize(textPx * TRANS_SP / TEXT_SP);
        transPaint.setTypeface(Typeface.create(Typeface.DEFAULT, TRANS_WEIGHT, false));
        bgPaint.setTextSize(textPx * BG_SP / TEXT_SP);
        bgPaint.setTypeface(Typeface.create(Typeface.DEFAULT, BG_WEIGHT, false));
    }

    /** Something outside changed - a line start, the song, the setting. Wakes the loop. */
    void kick() {
        if (looping) return;
        looping = true;
        postOnAnimation(frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
        lastStep = 0L;
        LockLyrics.startTick();
        kick();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        removeCallbacks(frame);
        looping = false;
        lastLoopFrame = 0L;
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ state

    /**
     * One step of everything. Returns whether anything visible changed. Safe to call twice in a
     * frame - the animation callback and the pre-draw both do - because the second call has no
     * time to integrate.
     */
    private boolean step() {
        // The frame's own vsync time, not the clock at the moment this runs: how late the main
        // thread gets to a frame varies by a few ms with whatever else the keyguard is doing, and
        // stepping on the wall clock put that jitter straight into the scroll and the word fill.
        long now = now();
        if (now == lastStep) return false;
        float dt = lastStep == 0L ? 0f : Math.min(0.05f, (now - lastStep) / 1000f);
        lastStep = now;

        boolean changed = followAodDim();
        changed |= followBouncer(dt);
        int why = 0;
        // Not before the first layout: a width of zero would wrap every line a character a row.
        // Leaving, the lines are frozen like the band below: switching the lyrics off empties
        // them, and laying the empty set out at once cut the lines off in one frame instead of
        // letting them fade.
        if (getWidth() > 0 && (LockLyrics.version() != version || getWidth() != layoutWidth
                // The translation switch changes the layout, not just the drawing: it takes a
                // row out from under every line, so it is asked for the same way a new lyric set
                // is. Compared against what the layout in use was built with, not the field, or
                // the request would still look outstanding the moment it landed.
                || LockLyrics.sTrans != builtTrans
                || !LockLyrics.sStyle.sameLayout(layoutStyle))
                && (LockLyrics.wantsAttached() || show == 0f)) {
            if (layOut()) {
                changed = true;
                why |= 1;
            }
        }
        // Leaving, the band is frozen where it was: following the clock as it grows would drag
        // the fading lines up through it.
        if (LockLyrics.wantsShown() && updateBand()) {
            changed = true;
            why |= 2;
        }

        // The block's centre, slid to rather than cut to. Snapped whenever there is nothing to
        // slide: hidden or not yet shown, so each arrival starts from the right place instead of
        // easing up from wherever the last one left off, and whenever there is no block to
        // centre, so a correction from the previous song cannot outlive it.
        anchorFixWant = anchorFixTarget();
        float before = anchorFix;
        if (dt <= 0f || show < 0.02f || !centring()) {
            anchorFix = anchorFixWant;
        } else {
            anchorFix = approach(anchorFix, anchorFixWant, dt, TAU_ANCHOR);
            // Land on it exactly: a correction that keeps closing by a hundredth of a pixel keeps
            // the view invalidating forever, and nothing here is drawn at that resolution anyway.
            if (Math.abs(anchorFixWant - anchorFix) < 0.01f) anchorFix = anchorFixWant;
        }
        if (anchorFix != before) {
            changed = true;
            why |= 512;
        }

        float showTo = showTarget();
        float s = approach(show, showTo, dt, showTo < show ? TAU_HIDE : TAU_SHOW);
        if (Math.abs(s - showTo) < 0.004f) s = showTo;
        if (s != show) {
            show = s;
            changed = true;
            why |= 4;
        }
        if (show == 0f && showTo == 0f && !LockLyrics.wantsAttached()) {
            // Faded out with nothing to come back for: leave the keyguard's tree, and let the
            // frozen lines and their pictures go if the switch emptied them.
            if (LockLyrics.lines().isEmpty() && !lines.isEmpty()) layOut();
            post(new Runnable() {
                @Override
                public void run() {
                    LockLyrics.detach(LyricView.this);
                }
            });
            return changed;
        }
        if (lines.isEmpty()) return changed;

        // The position moves on every step while playing, and that alone is NOT a change: it
        // used to be, so every pre-draw invalidated, which drew the next frame, whose pre-draw
        // invalidated again - the whole keyguard window redrawn at the refresh rate for as long
        // as music played. SystemUI's main thread went to GC for 27s in two minutes and was
        // killed for an ANR (2026-09-16 02:51). Only what moves redraws.
        ms = smoothPosition(now);
        int n = lines.size();
        int idx = indexAt(ms);

        // Which line the stack is on, which can be ahead of the one being sung: Apple scrolls to
        // the next line about a second before its first word (measured 1.07s), once the line
        // before has finished. A long gap scrolls to the interlude dots instead, as soon as the
        // line before it ends.
        int sf, dots = -1;
        if (idx < 0) {
            sf = 0;
            if (!Float.isNaN(dotsTop[0]) && ms < switchAt(0)) dots = 0;
        } else {
            sf = idx;
            int next = idx + 1;
            if (next < n) {
                if (ms >= switchAt(next)) {
                    sf = next;
                } else if (!Float.isNaN(dotsTop[next]) && ms >= lines.get(idx).end) {
                    sf = next;
                    dots = next;
                }
            }
        }
        int key = dots >= 0 ? -(dots + 1) : sf;
        if (key != focusKey) {
            // Whether this was a seek is a question about the playhead, not about how many lines
            // it crossed. Counting lines got it wrong in both directions and inconsistently
            // inside one drag: eight lines is seconds of a fast song and minutes of a slow one,
            // so dragging the progress bar animated a long scroll when the lines happened to be
            // dense and cut straight to the target when they happened to be sparse.
            // First lines of a song go straight to their place - there is nothing on screen for
            // them to travel from. A seek travels: see the spring picked for it below.
            boolean first = focus < 0;
            boolean seek = !first && now - jumpedAt < SEEK_WINDOW_MS;
            focus = sf;
            dotsFor = dots;
            focusKey = key;
            focusChangedAt = now;
            float to = dots >= 0 ? dotsTop[dots] : base[sf];
            if (first) {
                snap(to);
                // A freshly laid out song has nowhere to slide from, and the correction it needs
                // is not the one the song before it left behind: taken, not eased into.
                anchorFix = anchorFixWant = anchorFixTarget();
            } else if (seek) {
                // A drag scrolls there rather than cutting, however far it went - watching the
                // lyrics travel is what makes a seek legible, and the direction it travels says
                // which way the playhead moved.
                //
                // Two things separate this from an ordinary line change. There is no ripple: the
                // per-line delay exists to make one line hand over to the next, and across
                // twenty lines it reads as the list coming apart. And the spring is softer,
                // because the distance here is the distance between two arbitrary points in the
                // song rather than one line's height - what stops it from being a teleport is
                // the speed cap in the integrator, and a softer spring hands over to that cap
                // and back more gently.
                springK = K_SEEK;
                springC = C_SEEK;
                dotsWasShowing = dots >= 0;
                for (int i = 0; i < n; i++) {
                    aimAt[i] = now;
                    nextAim[i] = to;
                }
            } else {
                // The spring for this move: slow into and out of an interlude, otherwise stiffer
                // the shorter the gap from the line before.
                boolean slow = dots >= 0 || dotsWasShowing || sf == 0;
                dotsWasShowing = dots >= 0;
                if (slow) {
                    springK = K_SLOW;
                    springC = C_SLOW;
                } else {
                    int iv = lines.get(sf).start - lines.get(sf - 1).start;
                    iv = Math.max(IV_MIN, Math.min(IV_MAX, iv));
                    float ratio = 1f - (iv - IV_MIN) / (float) (IV_MAX - IV_MIN);
                    ratio = (float) Math.pow(ratio, 0.2);
                    springK = K_MIN + ratio * (K_MAX - K_MIN);
                    springC = (float) Math.sqrt(springK) * DAMPING_MULT;
                }
                // The ripple, counted from the first line whose target is on screen.
                float anchor = anchorY();
                float delay = 0f, step = RIPPLE_MS;
                for (int i = 0; i < n; i++) {
                    aimAt[i] = now + Math.round(delay);
                    nextAim[i] = to;
                    float y = anchor + base[i] - to;
                    if (y + height[i] >= bandTop) {
                        delay += step;
                        if (i >= sf) step *= RIPPLE_DECAY;
                    }
                }
            }
            prewarmBlur();
            if (LockLyrics.verbose) {
                Xp.log(TAG + (dots >= 0 ? "interlude before " : "line ") + sf + "/" + n
                        + " at " + ms + "ms");
            }
            changed = true;
            why |= 8;
        }

        float target = dotsFor >= 0 ? dotsTop[dotsFor] : base[focus];
        // Band-relative so it means the same on any screen; the fallback is for the frames
        // before the band has been measured.
        float band = bandBottom - bandTop;
        float speedCap = (band > 1f ? band : Math.max(1, getHeight())) * SEEK_SPEED_BANDS;
        int lo = Math.max(0, focus - 6), hi = Math.min(n - 1, focus + 12);
        for (int i = 0; i < n; i++) {
            if (i < lo || i > hi) {
                if (scroll[i] != target || emph[i] != 0f || blur[i] != 0f) {
                    scroll[i] = aim[i] = nextAim[i] = target;
                    vel[i] = 0f;
                    emph[i] = 0f;
                    lit[i] = 0f;
                    scale[i] = INACTIVE_SCALE;
                    blur[i] = 0f;
                }
                dropBlurred(i);
                continue;
            }
            // Position: the line's own ripple delay, then the move's spring.
            boolean started = now >= aimAt[i];
            if (aim[i] != nextAim[i] && started) aim[i] = nextAim[i];
            float x = scroll[i] - aim[i];
            if (dt > 0f && (Math.abs(x) > 0.3f || Math.abs(vel[i]) > 2f)) {
                float left = dt;
                while (left > 0f) {
                    float h = Math.min(left, 1f / 240f);
                    vel[i] += (-springK * x - springC * vel[i]) * h;
                    if (vel[i] > speedCap) {
                        vel[i] = speedCap;
                    } else if (vel[i] < -speedCap) {
                        vel[i] = -speedCap;
                    }
                    x += vel[i] * h;
                    left -= h;
                }
                scroll[i] = aim[i] + x;
                changed = true;
                why |= 16;
            } else if (x != 0f) {
                scroll[i] = aim[i];
                vel[i] = 0f;
                changed = true;
                why |= 32;
            }

            LyricLine l = lines.get(i);
            boolean focused = dotsFor < 0 && i == focus;
            // Emphasis: on the scroll focus, and on a duet's overlapping answer while it is sung.
            // Off in one frame - the line that has been sung drops to the inactive level at once.
            boolean on = focused || (dotsFor < 0 && i < focus && i >= focus - 2
                    && ms >= l.start && ms < l.end);
            float eTo = on ? 1f : 0f;
            float e = approach(emph[i], eTo, dt, eTo > emph[i] ? TAU_EMPH_IN : TAU_EMPH_OUT);
            if (Math.abs(e - eTo) < 0.003f) e = eTo;
            if (e != emph[i]) {
                emph[i] = e;
                changed = true;
                why |= 64;
            }
            // A line-timed line lights when it is sung, not when the stack arrives a second early.
            float lTo = on && ms >= l.start ? 1f : 0f;
            float lv = approach(lit[i], lTo, dt, lTo > lit[i] ? TAU_EMPH_IN : TAU_EMPH_OUT);
            if (Math.abs(lv - lTo) < 0.003f) lv = lTo;
            if (lv != lit[i]) {
                lit[i] = lv;
                changed = true;
                why |= 64;
            }
            // Size: the focus at full size, the rest a little smaller, travelling with the scroll.
            float scTo = focused ? 1f : INACTIVE_SCALE;
            float sc = started ? approach(scale[i], scTo, dt, TAU_SCALE) : scale[i];
            if (Math.abs(sc - scTo) < 0.0005f) sc = scTo;
            if (sc != scale[i]) {
                scale[i] = sc;
                changed = true;
                why |= 64;
            }
            // Depth: the line leaving goes out of focus fast, the one arriving clears a little
            // slower (about 100ms and 200ms in the frames).
            float bt = blurTarget(i);
            float b = approach(blur[i], bt, dt, bt > blur[i] ? TAU_BLUR_IN : TAU_BLUR_OUT);
            if (Math.abs(b - bt) < 0.05f) b = bt;
            if (b != blur[i]) {
                blur[i] = b;
                changed = true;
                why |= 128;
            }
        }
        if (wordsLive() || dotsLive()) why |= 256;
        LockLyrics.setGlowing(glowSoon());
        noteWhy(why);
        return changed || (why & 256) != 0;
    }

    /**
     * When the stack moves to line i: a second before its first word, but not before the line
     * ahead of it has finished, and never after its own start.
     */
    private long switchAt(int i) {
        LyricLine l = lines.get(i);
        long early = (long) l.start - LEAD_MS;
        if (i == 0) return early;
        long prevEnd = lines.get(i - 1).end;
        return Math.min(l.start, Math.max(early, prevEnd));
    }

    private float blurFor(int rows) {
        // The neighbours only just soft, so the next line still reads; then clearly out of focus.
        if (rows <= 0) return 0f;
        return (BLUR_NEXT_DP + BLUR_DP_PER_ROW * (Math.min(BLUR_MAX_ROWS, rows) - 1)) * density;
    }

    /** How many rows from the focus a line counts as - lines above count one further. */
    private int rowsFromFocus(int i) {
        if (dotsFor >= 0) return i >= dotsFor ? i - dotsFor + 1 : dotsFor - i + 1;
        if (i == focus) return 0;
        return i > focus ? i - focus : focus - i + 1;
    }

    /** The blur a line is heading for: none on the focus, then by distance. */
    private float blurTarget(int i) {
        return blurFor(rowsFromFocus(i));
    }

    private boolean moving(int i) {
        return aim[i] != nextAim[i] || scroll[i] != aim[i] || vel[i] != 0f;
    }

    /**
     * How visible the lyrics should be: the card's own progress into the cover look (so they
     * arrive and leave with the card's thumbnail), and the clock container's alpha (so they go
     * wherever the OEM fades the clock - the bouncer, the shade over the lock screen).
     */
    private float showTarget() {
        if (!LockLyrics.wantsShown() || !bandOk || lines.isEmpty()) return 0f;
        float v = clamp01(Main.cardProgress());
        View c = Main.sContainer;
        if (c != null) v *= clamp01(c.getAlpha());
        return v;
    }

    /** keyguard_info_layer, the view the full AOD dims - the one the lyrics take their alpha from. */
    private View dimSource;

    /**
     * The alpha the full always-on display is currently dimming the keyguard by.
     *
     * The OEM applies that dim to six views by name and this one is not among them: the lyrics
     * live in `keyguard_foreground_layer`, a sibling of `keyguard_info_layer` under the same
     * `constraintLayout` (KeyguardPanelViewController 1239-1262 and 5665-5674). Left alone they
     * would sit at full brightness over a screen that had just darkened itself.
     *
     * Read from that sibling rather than from a constant so the 500ms descent is followed frame
     * for frame, and looked up again whenever it is not attached - the keyguard is rebuilt.
     */
    private float dimTarget() {
        if (!LockLyrics.inHeldAod()) return 1f;
        View s = dimSource;
        if (s == null || !s.isAttachedToWindow()) {
            View root = getRootView();
            int id = getContext().getResources()
                    .getIdentifier("keyguard_info_layer", "id", "com.android.systemui");
            s = id == 0 || root == null ? null : root.findViewById(id);
            dimSource = s;
        }
        return s == null ? 1f : s.getTransitionAlpha();
    }

    /** @return whether it moved, so a dim of the keyguard keeps this view asking for frames */
    private boolean followAodDim() {
        float want = dimTarget();
        if (getTransitionAlpha() == want) return false;
        setTransitionAlpha(want);
        return true;
    }

    /**
     * How far the bouncer's blur has come over the lyrics, 0..1.
     *
     * The OEM blurs the lock screen under the PIN pad, but the lyrics live in
     * keyguard_foreground_layer and are not among what it blurs, and the clock container they
     * take their alpha from stays shown with the pad up (see Main.bouncerShown) - so they stood
     * sharp over a blurred screen. This blurs them itself, eased in and out with the pad.
     */
    private float bouncerP;
    private static final float BOUNCER_BLUR_DP = 24f;
    /**
     * Time constant of the ease, in seconds. Short: the level is the pad's own fade already
     * (Main.bouncerLevel), so this only smooths it. At 80ms it trailed the OEM's blur both ways.
     */
    private static final float BOUNCER_TAU = 0.03f;

    /** @return whether the blur moved, so the pad coming up keeps this view asking for frames */
    private boolean followBouncer(float dt) {
        float want = Main.bouncerLevel();
        if (bouncerP == want) return false;
        // The first step of a frame has no dt; it still has to start moving.
        float k = dt <= 0f ? 0.25f : (float) (1.0 - Math.exp(-dt / BOUNCER_TAU));
        bouncerP += (want - bouncerP) * k;
        if (Math.abs(want - bouncerP) < 0.01f) bouncerP = want;
        float r = bouncerP * BOUNCER_BLUR_DP * density;
        setRenderEffect(r < 0.5f ? null : android.graphics.RenderEffect.createBlurEffect(
                r, r, Shader.TileMode.DECAL));
        return true;
    }

    private boolean needsFrames() {
        if (!isAttachedToWindow()) return false;
        if (show != showTarget()) return true;
        // The keyguard dimming around us is a movement like any other, and so is the keyguard
        // coming back: without this the loop would stop the moment the words settled and leave
        // the lyrics at whatever alpha the AOD had put them at - dimmed on a lit screen, or at
        // full brightness on one that has just dimmed itself. Asked in both directions, because
        // dimTarget() is 1 outside the AOD and the frame that wakes the keyguard may change
        // nothing else.
        if (getTransitionAlpha() != dimTarget()) return true;
        if (bouncerP != Main.bouncerLevel()) return true;
        // The block sliding to a new centre is a movement like any other, and the slowest one
        // here: without this the loop would stop the moment the springs settled and leave the
        // correction half way.
        if (anchorFix != anchorFixWant) return true;
        ClockCollapse.Phase p = ClockCollapse.phase();
        if (p == ClockCollapse.Phase.ENTER || p == ClockCollapse.Phase.EXIT) return true;
        if (lines.isEmpty() || focus < 0 || show == 0f) return false;
        int n = lines.size();
        int lo = Math.max(0, focus - 6), hi = Math.min(n - 1, focus + 12);
        for (int i = lo; i <= hi; i++) {
            if (moving(i)) return true;
            float e = emph[i];
            if (e != 0f && e != 1f) return true;
            float lv = lit[i];
            if (lv != 0f && lv != 1f) return true;
            if (scale[i] != 1f && scale[i] != INACTIVE_SCALE) return true;
            if (now() < aimAt[i]) return true;
            if (blur[i] != blurTarget(i)) return true;
        }
        // The words move every frame while they are being sung - the fill, the lift, a held
        // note's glow - and the interlude dots breathe for as long as they are up.
        return wordsLive() || dotsLive();
    }

    /** Inside a frame, that frame's vsync time; outside one, the uptime clock it is based on. */
    private static long now() {
        return android.view.animation.AnimationUtils.currentAnimationTimeMillis();
    }

    private float smoothMs;
    private long smoothAt;

    /**
     * The session's position at this frame's time, eased rather than jumped when a fresh read of
     * the session disagrees with the extrapolation by a little. The tick re-reads it every second
     * and players round and batch what they report, so the raw number steps by tens of ms now
     * and then - a visible hitch in the word fill. A real jump (a seek) is still taken at once.
     */
    private int smoothPosition(long now) {
        float raw = LockLyrics.positionMs() + (now - SystemClock.uptimeMillis());
        if (smoothAt == 0L || !LockLyrics.playing()) {
            // Paused counts too: the progress bar can be dragged while paused, and that is still
            // a seek even though nothing is advancing between frames to compare against.
            if (smoothAt != 0L && Math.abs(raw - smoothMs) > JUMP_MS) {
                jumpedAt = now;
            }
            smoothMs = raw;
        } else {
            float pred = smoothMs + (now - smoothAt);
            float err = raw - pred;
            if (Math.abs(err) > JUMP_MS) {
                jumpedAt = now;
                smoothMs = raw;
            } else {
                smoothMs = pred + err * Math.min(1f, (now - smoothAt) / 300f);
            }
        }
        smoothAt = now;
        return smoothMs < 0f ? 0 : Math.round(smoothMs);
    }

    /** More than playback alone can explain between two reads: somebody moved the playhead. */
    private static final float JUMP_MS = 250f;

    /**
     * How long after a jump a focus change still counts as part of it.
     *
     * Not just the one frame the jump was noticed on. A drag lands the position first and the
     * focus catches up a frame or two later, and a seek whose scroll animates because the focus
     * moved one frame too late is exactly the inconsistency this window closes.
     */
    private static final long SEEK_WINDOW_MS = 250L;

    /** When the playhead last moved by more than playing could account for. */
    private long jumpedAt = Long.MIN_VALUE;

    /** The interlude dots are up, or about to be. */
    private boolean dotsLive() {
        // Not while paused: nothing about them moves then, and this kept the whole keyguard
        // window redrawing at the refresh rate for as long as the pause lasted.
        return dotsFor >= 0 && show > 0f && LockLyrics.playing();
    }

    /** A held note is glowing now, or is about to - the window's HDR mode follows this. */
    private boolean glowSoon() {
        if (!LockLyrics.sHdr || focus < 0 || show == 0f || !LockLyrics.playing()) return false;
        for (int i = Math.max(0, focus - 1); i <= focus; i++) {
            LyricLine l = lines.get(i);
            if (!l.hasWords() || emph[i] <= 0f) continue;
            for (int k = 0; k < l.sylStart.length; k++) {
                int s = l.sylStart[k], end = l.sylEnd[k];
                if (end - s >= GLOW_MIN_MS && ms >= s - HDR_ARM_MS && ms < end + GLOW_TAIL_MS) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A singing line's words are moving: the fill, the lift, a held note's glow. */
    private boolean wordsLive() {
        if (!LockLyrics.playing() || focus < 0 || show == 0f) return false;
        for (int i = Math.max(0, focus - 1); i <= focus; i++) {
            LyricLine l = lines.get(i);
            if (l.hasWords() && emph[i] > 0f && ms >= l.start
                    && ms < l.end + Math.max(LIFT_MIN_MS, GLOW_TAIL_MS)) {
                return true;
            }
        }
        return false;
    }

    private static float approach(float v, float to, float dt, float tau) {
        if (dt <= 0f) return v;
        return v + (to - v) * (1f - (float) Math.exp(-dt / tau));
    }

    private int indexAt(int t) {
        int lo = 0, hi = lines.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).start <= t) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private void snap(float target) {
        for (int i = 0; i < scroll.length; i++) {
            scroll[i] = aim[i] = nextAim[i] = target;
            vel[i] = 0f;
            emph[i] = 0f;
            lit[i] = 0f;
            scale[i] = INACTIVE_SCALE;
            blur[i] = 0f;
        }
    }

    /**
     * New lines, or a new width: lay every line out once. Returns whether the new layout is in
     * place already - only an empty one is; the rest is laid out on a thread of its own and put
     * in when it comes back, with the old lines standing until then.
     *
     * It used to be done right here in the frame, and a whole song is a StaticLayout per line,
     * per background vocal and per translation, all with the balanced breaker - the frame the
     * lyrics were meant to start fading in on, switched on or on a new song, was the one that
     * stalled.
     */
    private boolean layOut() {
        final int v = LockLyrics.version();
        final int width = getWidth();
        final boolean transOn = LockLyrics.sTrans;
        final LyricStyle style = LockLyrics.sStyle;
        final List<LyricLine> ls = LockLyrics.lines();
        if (!ls.isEmpty() && v == wantVersion && width == wantWidth && transOn == wantTrans
                && style.sameLayout(wantStyle)) return false;
        final float buildTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                style.sizeSp, getResources().getDisplayMetrics());
        final float buildSidePx = style.sidePx(width, shortSide(), density, buildTextPx);
        // Copies, including the requested typography: the current paints keep drawing the old
        // layout until the replacement is ready. A rapid slider drag cannot mix both styles.
        final TextPaint p = new TextPaint(paint);
        p.setTextSize(buildTextPx);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style.weight, false));
        final TextPaint bp = new TextPaint(bgPaint);
        bp.setTextSize(buildTextPx * BG_SP / TEXT_SP);
        final TextPaint tp = new TextPaint(transPaint);
        tp.setTextSize(buildTextPx * TRANS_SP / TEXT_SP);
        if (ls.isEmpty()) {
            wantVersion = wantWidth = -1;
            wantTrans = transOn;
            wantStyle = null;
            apply(build(v, width, ls, p, bp, tp, transOn, style, buildTextPx, buildSidePx));
            return true;
        }
        wantVersion = v;
        wantWidth = width;
        wantTrans = transOn;
        wantStyle = style;
        layoutHandler().post(new Runnable() {
            @Override
            public void run() {
                final Built b = build(v, width, ls, p, bp, tp, transOn,
                        style, buildTextPx, buildSidePx);
                post(new Runnable() {
                    @Override
                    public void run() {
                        // Overtaken by a newer request: that one's answer is the one to wait for.
                        if (v != wantVersion || width != wantWidth || transOn != wantTrans
                                || !style.sameLayout(wantStyle)) return;
                        wantVersion = wantWidth = -1;
                        wantStyle = null;
                        // Stale by the time it landed, or asked for and then frozen by the lyrics
                        // being switched off: the next step asks again when it is due.
                        if (v != LockLyrics.version() || width != getWidth()
                                || transOn != LockLyrics.sTrans
                                || !style.sameLayout(LockLyrics.sStyle)
                                || !LockLyrics.wantsAttached()) {
                            return;
                        }
                        apply(b);
                        invalidate();
                        kick();
                    }
                });
            }
        });
        return false;
    }

    /** One layout of the lines, made wherever build ran. */
    private static final class Built {
        int version, width, w;
        LyricStyle style;
        /** The translation switch this layout was made under; see LockLyrics.sTrans. */
        boolean transOn;
        List<LyricLine> lines;
        StaticLayout[] main, trans, bgLay;
        float[] base, height, dotsTop;
        float[][] charX, charXBg;
        long tookMs;
    }

    /** Touches nothing of the view's but its constants, so it can run off the UI thread. */
    private Built build(int v, int width, List<LyricLine> ls, TextPaint p, TextPaint bp,
                        TextPaint tp, boolean transOn, LyricStyle style, float buildTextPx,
                        float buildSidePx) {
        long t0 = SystemClock.uptimeMillis();
        Built b = new Built();
        b.version = v;
        b.width = width;
        b.style = style;
        b.transOn = transOn;
        b.lines = ls;
        int n = ls.size();
        int w = Math.max(1, width - Math.round(2f * buildSidePx));
        b.w = w;
        b.main = new StaticLayout[n];
        b.trans = new StaticLayout[n];
        b.bgLay = new StaticLayout[n];
        b.base = new float[n];
        b.height = new float[n];
        b.dotsTop = new float[n];
        b.charX = new float[n][];
        b.charXBg = new float[n][];
        float y = 0f;
        float gap = GAP_DP * density;
        for (int i = 0; i < n; i++) {
            LyricLine l = ls.get(i);
            // A long gap before this line holds the interlude dots, in a slot of their own.
            long gapStart = i == 0 ? 0L : ls.get(i - 1).end;
            if (l.start - gapStart >= LULL_MS) {
                b.dotsTop[i] = y;
                y += DOTS_SLOT_EM * buildTextPx + gap;
            } else {
                b.dotsTop[i] = Float.NaN;
            }
            Layout.Alignment align = l.opposite
                    ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            b.main[i] = StaticLayout.Builder.obtain(l.text, 0, l.text.length(), p, w)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                    .build();
            float h = b.main[i].getHeight();
            // The characters' places too, which the first draw of a word-timed line used to
            // measure one getPrimaryHorizontal at a time on the UI thread.
            if (l.hasWords()) b.charX[i] = charXOf(b.main[i], l);
            if (l.bg != null) {
                b.bgLay[i] = StaticLayout.Builder.obtain(l.bg.text, 0, l.bg.text.length(), bp, w)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                        .build();
                h += BG_GAP_DP * density + b.bgLay[i].getHeight();
                if (l.hasWords()) b.charXBg[i] = charXOf(b.bgLay[i], l.bg);
            }
            // Left out of the layout entirely when the switch is off, rather than laid out and
            // skipped in the draw: the rows it would have taken are most of a line's height, and
            // a gap there would leave every line floating with a hole under it.
            if (l.translation != null && transOn) {
                b.trans[i] = StaticLayout.Builder.obtain(l.translation, 0, l.translation.length(),
                                tp, w)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .build();
                h += TRANS_GAP_DP * density + b.trans[i].getHeight();
            }
            b.base[i] = y;
            b.height[i] = h;
            y += h + gap;
        }
        b.tookMs = SystemClock.uptimeMillis() - t0;
        return b;
    }

    /** Puts a finished layout in, and starts every line's animated state over. UI thread. */
    private void apply(Built b) {
        applyPaintStyle(b.style);
        version = b.version;
        builtTrans = b.transOn;
        lines = b.lines;
        layoutWidth = b.width;
        layoutMs = b.tookMs;
        buildGen++;
        blurPending.clear();
        int n = lines.size();
        main = b.main;
        trans = b.trans;
        bgLay = b.bgLay;
        charXBg = b.charXBg;
        base = b.base;
        height = b.height;
        charX = b.charX;
        dotsTop = b.dotsTop;
        blurBmp = new Bitmap[n][BLUR_MAX_ROWS];
        scroll = new float[n];
        vel = new float[n];
        aim = new float[n];
        nextAim = new float[n];
        aimAt = new long[n];
        emph = new float[n];
        lit = new float[n];
        scale = new float[n];
        java.util.Arrays.fill(scale, INACTIVE_SCALE);
        blur = new float[n];
        focus = -1;
        dotsFor = -1;
        focusKey = Integer.MIN_VALUE;
        if (LockLyrics.verbose || n > 0) {
            Xp.log(TAG + "view laid out " + n + " lines at width " + b.w + " in " + b.tookMs
                    + "ms");
        }
    }

    private static android.os.Handler sLayoutHandler;

    /**
     * Not the blur thread: that one runs at background priority behind a queue of pictures, and
     * the lyrics wait on this one to appear at all.
     */
    private static synchronized android.os.Handler layoutHandler() {
        if (sLayoutHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("MCLyricLayout");
            t.start();
            sLayoutHandler = new android.os.Handler(t.getLooper());
        }
        return sLayoutHandler;
    }

    /** Where the band between the clock and the card is, in this view's coordinates. */
    private boolean updateBand() {
        // Two getLocationOnScreen walks a frame are not free, and this runs from the keyguard's
        // pre-draw: nothing to show, nothing to measure.
        if (lines.isEmpty() && show == 0f) return false;
        float clock = ClockCollapse.contentBottomOnScreen();
        // The band's own lower edge, not the card view: the lock screen's media card can be hidden
        // for a whole song - the music capsule hides it outright, see
        // LockLyrics.bandBottomOnScreen() - and when it is, the band fills the block the card
        // would have taken rather than stopping above a card nobody is drawing.
        float floor = LockLyrics.bandBottomOnScreen();
        boolean ok = false;
        float top = bandTop, bottom = bandBottom;
        if (!Float.isNaN(clock) && isAttachedToWindow()) {
            getLocationOnScreen(loc);
            float me = loc[1];
            if (LockLyrics.sStyle.writeBand(clock, floor, density, textPx, bandBounds)) {
                top = bandBounds[0] - me;
                bottom = bandBounds[1] - me;
                ok = bottom - top >= MIN_BAND_ROWS * textPx - 0.01f;
            }
        }
        boolean changed = ok != bandOk
                || Math.abs(top - bandTop) >= 0.5f || Math.abs(bottom - bandBottom) >= 0.5f;
        bandOk = ok;
        if (ok) {
            bandTop = top;
            bandBottom = bottom;
        }
        return changed;
    }

    /** Whether there is a block of rows to centre: a measured band, and a focus line inside it. */
    private boolean centring() {
        int n = lines.size();
        return bandOk && focus >= 0 && focus < n && base.length == n && height.length == n;
    }

    /**
     * How far the block wants to sit from the band's uncentred anchor, in this view's pixels.
     *
     * A band holds a whole number of rows and its ends are fixed by the clock and the card, so
     * pinning the singing line to a fraction of the band left the remainder wherever the rows
     * happened to fall - under the first row when the band held only just enough of them, above
     * the last when it did not. Laying the rows the band holds out from the band's middle splits
     * that remainder between the two margins rather than leaving all of it at one of them.
     *
     * Measured off `base` and `height` rather than counted in pitches: a wrapped line is two rows
     * tall and a line with an interlude slot above it sits further down again, so the block is
     * whatever the band holds, not a number of pitch-sized slots. It is measured against the
     * uncentred anchor, and that anchor is a fraction of the band, so the answer is the same for
     * a whole line's travel: this cannot fight the spring, and it changes only when the band or
     * the rows under it do.
     *
     * A correction, not a position, because it is the part of the placement that steps and the
     * band is the part that moves: step() eases this and leaves the band alone.
     */
    private float anchorFixTarget() {
        if (!centring()) return 0f;
        float bandH = bandBottom - bandTop;
        float anchor = bandTop + ANCHOR * bandH;
        int n = lines.size();
        // Rows are at least a line's gap apart, so this brackets whatever the band can hold.
        int span = 2 + (int) (bandH / Math.max(1f, GAP_DP * density));
        int lo = Math.max(0, focus - span), hi = Math.min(n - 1, focus + span);
        int first = -1, last = -1;
        for (int i = lo; i <= hi; i++) {
            float y = anchor + base[i] - base[focus];
            if (y >= bandTop && y + height[i] <= bandBottom) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return 0f;
        float blockH = base[last] - base[first] + height[last];
        // The uncentred anchor drops out: where the block sits is the band's and its rows' doing.
        return bandTop + (bandH - blockH) / 2f + base[focus] - base[first] - anchor;
    }

    /**
     * Where the singing line's layout top goes this frame. Read by the ripple when a line changes
     * and by every draw, always off the value step() settled on for this frame, so the two cannot
     * disagree about where the block is.
     */
    private float anchorY() {
        return bandTop + ANCHOR * (bandBottom - bandTop) + anchorFix;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        long t0 = System.nanoTime();
        drawLyrics(canvas);
        long took = System.nanoTime() - t0;
        drawNsSum += took;
        if (took > drawNsMax) drawNsMax = took;
    }

    private void drawLyrics(Canvas canvas) {
        drawCount++;
        if (show <= 0.003f || lines.isEmpty() || focus < 0 || main.length != lines.size()) return;
        float bandH = bandBottom - bandTop;
        if (bandH <= 0f) return;
        // The float, added to the anchor so the lines, their dots and their edge fades all move
        // as one block: a line's alpha is taken from its own position against the band's edges,
        // and offsetting the canvas instead would have left those alphas describing where the
        // line was going to be rather than where it is.
        float anchor = anchorY() + (1f - show) * FLOAT_DP * density;
        float side = sidePx;
        float fade = Math.min(EDGE_FADE_DP * density, bandH / 3f);
        int n = lines.size();
        updateTint();

        int save = canvas.save();
        canvas.clipRect(0f, bandTop, getWidth(), bandBottom);
        for (int i = Math.max(0, focus - 6); i < n; i++) {
            // The interlude slot above this line, if it has one: its dots move with the line, and
            // sit there dim from the start rather than leaving a hole until their turn.
            if (!Float.isNaN(dotsTop[i])) {
                float dy = anchor + dotsTop[i] - scroll[i];
                if (dy > bandBottom) break;
                float dh = DOTS_SLOT_EM * textPx;
                float dEdge = Math.min(clamp01((dy - bandTop) / fade),
                        clamp01((bandBottom - (dy + dh)) / fade));
                float da = show * dEdge;
                if (dotsFor >= 0 ? i < dotsFor : i <= focus) da *= ABOVE_ALPHA;
                if (da > 0.003f) drawDots(canvas, i, side, dy, da);
            }
            float y = anchor + base[i] - scroll[i];
            if (y > bandBottom) break;
            if (y + height[i] < bandTop) continue;
            // Faded by the row's own top against the top edge and its own bottom against the
            // bottom edge, so a line is already gone by the time it would be cut.
            float edge = Math.min(clamp01((y - bandTop) / fade),
                    clamp01((bandBottom - (y + height[i])) / fade));
            float a = show * edge;
            // Lines already sung, above the focus, sit further back than the ones to come.
            if (dotsFor >= 0 ? i < dotsFor : i < focus) a *= ABOVE_ALPHA;
            if (a <= 0.003f) continue;
            drawLine(canvas, i, side, y, a);
        }
        canvas.restoreToCount(save);
    }

    /**
     * The interlude slot before line d: three dots.
     *
     * Before their turn they sit there dim and still, like any line to come. Once the gap starts
     * they light one after another across it (dim at 18%, each lit over about 70% of its third,
     * sizes and spacing off Apple's frames) while the group breathes about its centre - swelling
     * quicker than it shrinks, a whole number of breaths fitted to the gap so the last one ends
     * at rest. Just before the stack moves on they swell a touch and shrink away to nothing.
     * Once their interlude is over they are not drawn again.
     */
    private void drawDots(Canvas c, int d, float x, float top, float alphaMul) {
        long gapStart = d == 0 ? 0L : lines.get(d - 1).end;
        long sw = switchAt(d);
        long appear = gapStart + Math.min(DOT_APPEAR_MS, (long) ((sw - gapStart) * 0.15f));
        long exitEnd = sw - DOT_EXIT_LEAD_MS;
        long exitStart = Math.max(appear, exitEnd - DOT_EXIT_MS);
        if (ms >= exitEnd) return;

        float group = 1f, fade = 1f, u = 0f;
        if (ms >= appear) {
            long span = exitStart - appear;
            long t = Math.min(ms, exitStart) - appear;
            u = span <= 0 ? 3f : 3f * t / (float) span;
            if (span > 0) {
                float period = span / (float) Math.max(1, Math.round(span / (float) DOT_BREATH_MS));
                // Started a quarter of the way into the swell, where the curve is at rest level
                // and rising, so the first frame of the interlude matches the still dots.
                float p = (t + period * DOT_INHALE / 2f) / period;
                p -= (float) Math.floor(p);
                float b = p < DOT_INHALE
                        ? -(float) Math.cos(Math.PI * p / DOT_INHALE)
                        : (float) Math.cos(Math.PI * (p - DOT_INHALE) / (1f - DOT_INHALE));
                float depth = clamp01(t / (float) DOT_BREATH_IN_MS);
                depth = 1f - (1f - depth) * (1f - depth);
                group = 1f + DOT_BREATH * depth * b;
            }
            if (ms >= exitStart) {
                float e = clamp01((ms - exitStart) / (float) (exitEnd - exitStart));
                float back = e * e * ((DOT_EXIT_BACK + 1f) * e - DOT_EXIT_BACK);
                group *= Math.max(0f, 1f - back);
                fade = 1f - clamp01((e - 0.55f) / 0.45f);
            }
        }
        if (group <= 0.01f) return;
        float size = DOT_EM * textPx;
        float gap = DOT_GAP_EM * textPx * group;
        float cx = x + size / 2f + DOT_GAP_EM * textPx;
        float cy = top + DOT_CENTER_EM * textPx;
        float r = size / 2f * group;
        for (int k = 0; k < 3; k++) {
            float litK = clamp01((u - k) / DOT_RAMP);
            float alpha = alphaMul * fade * (DOT_DIM + (1f - DOT_DIM) * litK);
            if (alpha <= 0.003f) continue;
            dotPaint.setColor(ink(alpha, litK));
            c.drawCircle(cx + (k - 1) * gap, cy, r, dotPaint);
        }
    }

    private void drawLine(Canvas canvas, int i, float x, float y, float a) {
        LyricLine l = lines.get(i);
        StaticLayout lay = main[i];
        float e = emph[i];
        float sc = scale[i];
        // Pivot on the line's own edge, so a size change does not shift it sideways.
        float pivotX = l.opposite ? lay.getWidth() : 0f;
        boolean words = l.hasWords() && e > 0f;
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.scale(sc, sc, pivotX, 0f);
        if (words) {
            // From the first frame of emphasis, so the first syllable lifts from the start. While
            // the line's blur is still clearing, its blurred picture is crossfaded out underneath
            // - handing over to the word path only once the blur had gone made the first word
            // jump up already half lifted.
            float k = blur[i] <= 0f ? 0f : clamp01(blur[i] / Math.max(0.01f, blurFor(1)));
            drawWords(canvas, i, a * (1f - k), e);
            if (k > 0f) {
                float r = blurFor(1);
                for (int d = 1; d <= BLUR_MAX_ROWS && blurFor(d) < blur[i]; d++) r = blurFor(d + 1);
                drawBlurLevel(canvas, i, r, a * INACTIVE * k, r);
            }
        } else {
            // The radius sits between two of the fixed ones - sharp, or a distance's blur - and is
            // drawn as a crossfade of those two pictures, so each is blurred once, not per frame.
            float r = blur[i];
            float lo = 0f, hi = 0f;
            for (int d = 0; d <= BLUR_MAX_ROWS; d++) {
                float v = blurFor(d);
                if (v <= r + 0.01f) lo = v;
                if (v >= r - 0.01f) {
                    hi = v;
                    break;
                }
                hi = v;
            }
            if (hi < lo) hi = lo;
            float t = hi > lo ? clamp01((r - lo) / (hi - lo)) : 0f;
            // A word-timed line that is not the focus is all unsung colour; a line-timed one is
            // lit while it is sung.
            float w = l.hasWords() ? 0f : lit[i];
            float base = a * (INACTIVE + (1f - INACTIVE) * w);
            if (t < 1f) drawBlurLevel(canvas, i, lo, base * (1f - t), hi, w);
            if (t > 0f) drawBlurLevel(canvas, i, hi, base * t, lo, w);
        }
        canvas.restoreToCount(save);
    }

    private void drawBlurLevel(Canvas canvas, int i, float radius, float alpha, float keep) {
        drawBlurLevel(canvas, i, radius, alpha, keep, 0f);
    }

    private void drawBlurLevel(Canvas canvas, int i, float radius, float alpha, float keep,
                               float whiteness) {
        if (alpha <= 0.002f) return;
        Bitmap b = radius <= 0f ? null : blurredFor(i, rowsFor(radius));
        if (b == null) {
            drawStatic(canvas, i, alpha, whiteness);
            return;
        }
        // A blur spreads a glyph's ink over more pixels, so the same alpha reads fainter the more it
        // is blurred. Compensated a little, so distance reads as depth rather than as fading out.
        float boost = 1f + BLUR_ALPHA_PER_DP * (radius / density);
        bmpPaint.setAlpha(Math.round(255f * Math.min(1f, alpha * boost)));
        int w = main[i].getWidth() + 2 * blurPad;
        int h = Math.round(height[i]) + 2 * blurPad;
        bmpDst.set(-blurPad, -blurPad, w - blurPad, h - blurPad);
        canvas.drawBitmap(b, null, bmpDst, bmpPaint);
    }

    // ------------------------------------------------------------------ colour

    /**
     * The cover's hue, lightened, that the dim text takes on - Apple's lyrics let the background
     * through the unlit words (on a plum cover they read pink-grey, not grey). Sung text stays
     * white. Refreshed per frame from the tint the clock already samples; cheap when unchanged.
     */
    private void updateTint() {
        int src = Main.coverTint();
        if (src == tintSrc) return;
        tintSrc = src;
        if (src == 0) {
            tintR = tintG = tintB = 1f;
        } else {
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(src, hsv);
            hsv[1] = Math.min(TINT_SAT, hsv[1] * 1.5f);
            hsv[2] = 1f;
            int t = android.graphics.Color.HSVToColor(hsv);
            tintR = ((t >> 16) & 0xff) / 255f;
            tintG = ((t >> 8) & 0xff) / 255f;
            tintB = (t & 0xff) / 255f;
        }
        int ti = android.graphics.Color.rgb(Math.round(tintR * 255f), Math.round(tintG * 255f),
                Math.round(tintB * 255f));
        bmpPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(ti,
                android.graphics.PorterDuff.Mode.SRC_IN));
    }

    /** Text colour: the tint at whiteness 0, white at 1, with an alpha. */
    private long ink(float alpha, float whiteness) {
        float w = clamp01(whiteness);
        float r = tintR + (1f - tintR) * w, g = tintG + (1f - tintG) * w, b = tintB + (1f - tintB) * w;
        return android.graphics.Color.pack(r, g, b, clamp01(alpha), EXTENDED);
    }

    /** The distance a blur radius belongs to. */
    private int rowsFor(float radius) {
        for (int d = 1; d <= BLUR_MAX_ROWS; d++) {
            if (Math.abs(blurFor(d) - radius) < 0.01f) return d;
        }
        return BLUR_MAX_ROWS;
    }

    /**
     * The line's picture blurred for a distance, or the nearest one it has while that is still
     * being made. Never made here: blurring on the UI thread, several lines at every line change,
     * was the hitch the scroll showed (99th percentile 38ms, 2026-09-16).
     */
    private Bitmap blurredFor(int i, int rows) {
        Bitmap[] have = blurBmp[i];
        if (have[rows - 1] != null) return have[rows - 1];
        requestBlur(i, rows);
        for (int gap = 1; gap < BLUR_MAX_ROWS; gap++) {
            if (rows - gap >= 1 && have[rows - gap - 1] != null) return have[rows - gap - 1];
            if (rows + gap <= BLUR_MAX_ROWS && have[rows + gap - 1] != null) {
                return have[rows + gap - 1];
            }
        }
        return null;
    }

    /**
     * The pictures the lines around the focus want now and at the next line change: their
     * distance now and one less, and for the line being sung the distance it drops back to.
     * Once made they stay, so after the first few lines this asks for one new line a change.
     */
    private void prewarmBlur() {
        int n = lines.size();
        for (int i = Math.max(0, focus - 4); i <= Math.min(n - 1, focus + 10); i++) {
            int d = Math.min(rowsFromFocus(i), BLUR_MAX_ROWS);
            if (d >= 1) requestBlur(i, d);
            if (d >= 2) requestBlur(i, d - 1);
            if (d <= 1) requestBlur(i, 2);
            if (i < focus && d < BLUR_MAX_ROWS) requestBlur(i, d + 1);
        }
    }

    private void requestBlur(final int i, final int rows) {
        if (rows < 1 || rows > BLUR_MAX_ROWS || i < 0 || i >= blurBmp.length) return;
        if (blurBmp[i][rows - 1] != null) return;
        if (!blurPending.add(i * 8 + rows)) return;
        // Everything the other thread needs, taken here: the paints are this view's and change on
        // every frame, so it gets copies, and it lays the text out again for itself.
        final int gen = buildGen;
        final LyricLine l = lines.get(i);
        final int width = main[i].getWidth();
        final int fullW = width + 2 * blurPad, fullH = Math.round(height[i]) + 2 * blurPad;
        final float radius = blurFor(rows);
        final TextPaint p = new TextPaint(paint);
        final TextPaint tp = new TextPaint(transPaint);
        final TextPaint bp = new TextPaint(bgPaint);
        final int pad = blurPad;
        final float transGap = TRANS_GAP_DP * density;
        final float bgGap = BG_GAP_DP * density;
        final int down = rows >= BLUR_QUARTER_ROWS ? 4 : 2;
        blurHandler().post(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = makeBlurred(l, width, fullW, fullH, pad, radius, p, tp, bp,
                        transGap, bgGap, down);
                post(new Runnable() {
                    @Override
                    public void run() {
                        blurPending.remove(i * 8 + rows);
                        if (b == null || gen != buildGen || i >= blurBmp.length) return;
                        blurBmp[i][rows - 1] = b;
                        // While the loop runs its next frame draws it anyway.
                        if (!looping) invalidate();
                    }
                });
            }
        });
    }

    /**
     * Off the UI thread: the line at 1/down of the resolution, blurred. The blur scales with the
     * canvas.
     */
    private static Bitmap makeBlurred(LyricLine l, int width, int fullW, int fullH, int pad,
                                      float radius, TextPaint p, TextPaint tp, TextPaint bp,
                                      float transGap, float bgGap, int down) {
        try {
            Bitmap b = Bitmap.createBitmap(Math.max(1, fullW / down), Math.max(1, fullH / down),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(1f / down, 1f / down);
            c.translate(pad, pad);
            BlurMaskFilter mf = new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL);
            Layout.Alignment align = l.opposite
                    ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            p.setShader(null);
            p.clearShadowLayer();
            p.setAlpha(255);
            p.setMaskFilter(mf);
            StaticLayout lay = StaticLayout.Builder.obtain(l.text, 0, l.text.length(), p, width)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                    .build();
            lay.draw(c);
            float below = lay.getHeight();
            if (l.bg != null) {
                bp.setShader(null);
                bp.setColor(0xFFFFFFFF);
                bp.setAlpha(Math.round(255f * BG_ALPHA));
                bp.setMaskFilter(mf);
                StaticLayout bl = StaticLayout.Builder.obtain(l.bg.text, 0, l.bg.text.length(),
                                bp, width)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                        .build();
                int save = c.save();
                c.translate(0f, below + bgGap);
                bl.draw(c);
                c.restoreToCount(save);
                below += bgGap + bl.getHeight();
            }
            if (l.translation != null && LockLyrics.sTrans) {
                tp.setAlpha(Math.round(255f * TRANS_ALPHA));
                tp.setMaskFilter(mf);
                StaticLayout t = StaticLayout.Builder.obtain(l.translation, 0,
                                l.translation.length(), tp, width)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .build();
                c.translate(0f, below + transGap);
                t.draw(c);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A line far from the focus lets its pictures go. Not recycled: the render thread may still be
     * drawing one from the last frame. The collector frees them once nothing holds them.
     */
    private void dropBlurred(int i) {
        if (i >= blurBmp.length) return;
        Bitmap[] have = blurBmp[i];
        for (int k = 0; k < have.length; k++) have[k] = null;
    }

    private static android.os.Handler sBlurHandler;

    private static synchronized android.os.Handler blurHandler() {
        if (sBlurHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("MCLyricBlur",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sBlurHandler = new android.os.Handler(t.getLooper());
        }
        return sBlurHandler;
    }

    /** A line at one brightness: the text at a, its translation below it. */
    private void drawStatic(Canvas c, int i, float a) {
        drawStatic(c, i, a, 0f);
    }

    /** A line at one brightness, white by whiteness and the cover's tint otherwise. */
    private void drawStatic(Canvas c, int i, float a, float whiteness) {
        // Each layout's own paint: it draws with the copy it was laid out with, not the view's.
        StaticLayout lay = main[i];
        lay.getPaint().setColor(ink(a, whiteness));
        lay.draw(c);
        lay.getPaint().setColor(0xFFFFFFFF);
        StaticLayout b = bgLay[i];
        if (b != null) {
            int save = c.save();
            c.translate(0f, lay.getHeight() + BG_GAP_DP * density);
            b.getPaint().setColor(ink(a * BG_ALPHA, whiteness));
            b.draw(c);
            b.getPaint().setColor(0xFFFFFFFF);
            c.restoreToCount(save);
        }
        drawTranslation(c, i, a);
    }

    /** How far down the translation starts: under the line and its background vocal. */
    private float transTop(int i) {
        float y = main[i].getHeight();
        if (bgLay[i] != null) y += BG_GAP_DP * density + bgLay[i].getHeight();
        return y + TRANS_GAP_DP * density;
    }

    /** White at a brightness in SDR units - 1 is ordinary white, more is HDR - and an alpha. */
    private static long white(float alpha, float gain) {
        return android.graphics.Color.pack(gain, gain, gain, clamp01(alpha), EXTENDED);
    }

    /**
     * A held note's brightness: HDR only while it glows, scaled with the glow. Nothing else is
     * drawn above white - the highlight is the end of a long note lighting up, not the line.
     */
    private static float glowGain(float glow) {
        return LockLyrics.sHdr ? 1f + (HDR_GAIN - 1f) * glow : 1f;
    }

    private void drawTranslation(Canvas c, int i, float a) {
        StaticLayout t = trans[i];
        if (t == null) return;
        int save = c.save();
        c.translate(0f, transTop(i));
        t.getPaint().setColor(ink(a * TRANS_ALPHA, 0f));
        t.draw(c);
        t.getPaint().setColor(0xFFFFFFFF);
        c.restoreToCount(save);
    }

    /**
     * A line with word timing, a syllable at a time, row by row: a row already sung at the sung
     * brightness, a row not reached at the unsung one, and the row being sung through a gradient
     * that crosses from one to the other at the character being sung.
     *
     * The gradient is the text paint's own shader. It used to be a DST_IN mask over an offscreen
     * layer of the whole line - a second full-size pass on the render thread every frame, which
     * was a quarter of a core for as long as a word-timed song played (measured 2026-09-16).
     */
    private void drawWords(Canvas c, int i, float a, float e) {
        LyricLine l = lines.get(i);
        StaticLayout lay = main[i];
        float level = INACTIVE + (1f - INACTIVE) * e;
        drawWordRows(c, l, lay, paint, charXFor(i, lay, l, charX), e, a * level,
                a * (INACTIVE + (1f - INACTIVE) * e * UNSUNG), 1f, true, 0);
        StaticLayout bl = bgLay[i];
        if (bl != null && l.bg != null) {
            int save = c.save();
            c.translate(0f, lay.getHeight() + BG_GAP_DP * density);
            drawWordRows(c, l.bg, bl, bgPaint, charXFor(i, bl, l.bg, charXBg), e,
                    a * level * BG_ALPHA,
                    a * (INACTIVE + (1f - INACTIVE) * e * UNSUNG) * BG_ALPHA,
                    BG_LIFT, false, 1);
            c.restoreToCount(save);
        }
        drawTranslation(c, i, a * level);
    }

    /**
     * One word-timed layout, row by row: a row already sung at the sung brightness, a row not
     * reached at the unsung one, and the row being sung through a gradient that crosses from one
     * to the other at the character being sung.
     *
     * The gradient is the paint's own shader. It used to be a DST_IN mask over an offscreen layer
     * of the whole line - a second full-size pass on the render thread every frame, which was a
     * quarter of a core for as long as a word-timed song played (measured 2026-09-16).
     */
    private void drawWordRows(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                              float e, float sungA, float unsungA, float liftScale,
                              boolean glowOn, int gradSlot) {
        float sung = l.sungChars(ms);
        float feather = FEATHER_EM * p.getTextSize();
        int rows = lay.getLineCount();
        for (int r = 0; r < rows; r++) {
            int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
            rowAt = Float.NaN;
            if (sung >= re) {
                p.setColor(ink(sungA, e));
            } else if (sung <= rs) {
                p.setColor(ink(unsungA, 0f));
            } else {
                int ch = (int) sung;
                float f = sung - ch;
                float xa = xs[ch];
                float xb = ch + 1 < re ? xs[ch + 1] : lay.getLineRight(r);
                p.setColor(0xFFFFFFFF);
                rowAt = xa + (xb - xa) * f;
                p.setShader(gradient(ink(sungA, e), ink(unsungA, 0f), rowAt, feather,
                        gradSlot));
            }
            rowFeather = feather;
            rowSungA = sungA;
            rowUnsungA = unsungA;
            drawSyllables(c, l, lay, p, xs, e, r, liftScale, glowOn);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
        }
    }

    /**
     * One row's syllables in their places, each lifted by how far through it the singing is, and a
     * held note glowing and swelling while it lasts. Drawn as runs with the whole row as context,
     * so the shaping and the spacing are the ones the layout measured.
     */
    private void drawSyllables(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                               float e, int r, float liftScale, boolean glowOn) {
        int count = l.sylStart.length;
        int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
        float baseline = lay.getLineBaseline(r);
        for (int k = 0; k < count; k++) {
            int from = k == 0 ? 0 : l.charEnd[k - 1];
            int cs = Math.max(from, rs), ce = Math.min(l.charEnd[k], re);
            if (ce <= cs) continue;
            int s = l.sylStart[k], end = l.sylEnd[k], dur = end - s;
            float rise = ms <= s ? 0f : clamp01((ms - s) / (float) Math.max(dur, LIFT_MIN_MS));
            float lift = liftPx * liftScale * e * (1f - (1f - rise) * (1f - rise));
            float glow = 0f;
            if (glowOn && dur >= GLOW_MIN_MS && ms > s) {
                glow = ms < end ? clamp01((ms - s) / (dur * 0.3f))
                        : 1f - clamp01((ms - end) / (float) GLOW_TAIL_MS);
                glow *= e;
            }
            float x0 = xs[cs];
            if (glow > 0.01f) {
                float x1 = ce < re ? xs[ce] : lay.getLineRight(r);
                int save = c.save();
                float swell = 1f + GLOW_SWELL * glow;
                c.scale(swell, swell, (x0 + x1) / 2f, baseline);
                float g = glowGain(glow);
                p.setShadowLayer(glowPx * glow, 0f, 0f, white(GLOW_ALPHA * glow, g));
                // The glowing syllable itself goes above white with its halo: its sung part
                // through this row's gradient re-coloured, or all of it once the row is sung.
                Shader rowShader = p.getShader();
                long rowColor = p.getColorLong();
                if (g > 1.001f) {
                    if (Float.isNaN(rowAt)) {
                        if (rowShader == null && l.sungChars(ms) >= ce) {
                            p.setColor(white(rowSungA, g));
                        }
                    } else {
                        p.setShader(gradient(white(rowSungA, g), ink(rowUnsungA, 0f), rowAt,
                                rowFeather, 2));
                    }
                }
                c.drawTextRun(l.text, cs, ce, rs, re, x0, baseline - lift, false, p);
                p.setShader(rowShader);
                p.setColor(rowColor);
                p.clearShadowLayer();
                c.restoreToCount(save);
            } else {
                c.drawTextRun(l.text, cs, ce, rs, re, x0, baseline - lift, false, p);
            }
        }
    }

    /** A left-to-right gradient centred on at. Rebuilt only when its two alphas change. */
    private Shader gradient(long hi, long lo, float at, float feather, int slot) {
        LinearGradient g = grads[slot];
        if (g == null || hi != gradHi[slot] || lo != gradLo[slot]) {
            gradHi[slot] = hi;
            gradLo[slot] = lo;
            g = new LinearGradient(-1f, 0f, 1f, 0f, new long[]{hi, lo}, null,
                    Shader.TileMode.CLAMP);
            grads[slot] = g;
        }
        gradMatrix.setScale(feather, 1f);
        gradMatrix.postTranslate(at, 0f);
        g.setLocalMatrix(gradMatrix);
        return g;
    }

    /** Each character's x, measured once per line the first time its words are drawn. */
    private float[] charXFor(int i, StaticLayout lay, LyricLine l, float[][] cache) {
        float[] xs = cache[i];
        if (xs != null) return xs;
        xs = charXOf(lay, l);
        cache[i] = xs;
        return xs;
    }

    private static float[] charXOf(StaticLayout lay, LyricLine l) {
        int len = l.text.length();
        float[] xs = new float[len + 1];
        for (int k = 0; k < len; k++) xs[k] = lay.getPrimaryHorizontal(k);
        xs[len] = lay.getLineRight(lay.getLineCount() - 1);
        return xs;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Diagnostics: which state asked for a redraw, per step, since the last describe(). */
    private final int[] whyCount = new int[10];
    private int stepCount, drawCount;

    /**
     * Diagnostics: how evenly the animation's frames came while it ran. A gap is a vsync the
     * loop asked for and did not get - counted only between two frames of a running loop.
     */
    private long loopFrames, gapsOver1, gapsOver3, maxGapMs;
    private long drawNsMax, drawNsSum;
    private long lastLoopFrame;
    private long focusChangedAt;
    private final StringBuilder gapLog = new StringBuilder();
    private long vsyncMs;

    private void noteFrameGap(long now) {
        if (vsyncMs == 0L) {
            android.view.Display disp = getDisplay();
            float hz = disp == null ? 60f : disp.getRefreshRate();
            vsyncMs = Math.max(4L, Math.round(1000f / hz));
        }
        if (lastLoopFrame != 0L) {
            long gap = now - lastLoopFrame;
            loopFrames++;
            if (gap > vsyncMs * 3 / 2) gapsOver1++;
            if (gap > vsyncMs * 3) {
                gapsOver3++;
                // Each stall with how long after the last line change it came, to tell a stall
                // caused by a line change from one that is not.
                if (gapLog.length() < 600) {
                    gapLog.append(gap).append('@').append(now - focusChangedAt).append(' ');
                }
            }
            if (gap > maxGapMs) maxGapMs = gap;
        }
        lastLoopFrame = now;
    }

    private void noteWhy(int why) {
        stepCount++;
        for (int b = 0; b < whyCount.length; b++) {
            if ((why & (1 << b)) != 0) whyCount[b]++;
        }
    }

    String describe() {
        int words = 0;
        for (LyricLine l : lines) if (l.hasWords()) words++;
        StringBuilder w = new StringBuilder();
        String[] names = {"rebuild", "band", "show", "focus", "scroll", "snap", "emph", "blur",
                "words", "anchor"};
        for (int b = 0; b < whyCount.length; b++) {
            if (whyCount[b] > 0) w.append(names[b]).append('=').append(whyCount[b]).append(',');
            whyCount[b] = 0;
        }
        String counts = " steps=" + stepCount + " draws=" + drawCount + " why=" + w
                + " loopFrames=" + loopFrames + " late=" + gapsOver1 + " veryLate=" + gapsOver3
                + " maxGap=" + maxGapMs + "ms vsync=" + vsyncMs + "ms drawMax="
                + (drawNsMax / 100000L) / 10f + "ms drawAvg="
                + (drawCount == 0 ? 0f : (drawNsSum / drawCount / 100000L) / 10f) + "ms";
        stepCount = drawCount = 0;
        counts += " stalls(gap@sinceLineChange)=[" + gapLog.toString().trim() + "]";
        gapLog.setLength(0);
        loopFrames = gapsOver1 = gapsOver3 = maxGapMs = 0L;
        drawNsMax = drawNsSum = 0L;
        return "lines=" + lines.size() + " wordLines=" + words + " layoutMs=" + layoutMs + counts
                + " focus=" + focus + " ms=" + ms + " show=" + show
                // The route the band's lower edge came by, so a band placed off the fallback can
                // be told apart from one that was measured.
                + " band=" + (bandOk ? Math.round(bandTop) + ".." + Math.round(bandBottom)
                        + "(" + LockLyrics.bandSource() + ")" : "none")
                + " looping=" + looping + " parent=" + (getParent() instanceof ViewGroup);
    }
}
