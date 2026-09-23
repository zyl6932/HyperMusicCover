package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/** The module's own square artwork. The OEM clock and media card remain above this layer. */
final class CoverCardLayer extends View implements Choreographer.FrameCallback {
    private static CoverCardLayer sView;
    private static volatile Prepared sPending;
    private static volatile int sGeneration;
    private static volatile CoverCardStyle sStyle = CoverCardStyle.defaults();
    private static volatile boolean sPlayingState;

    private static final class Prepared {
        final Bitmap art;
        final Bitmap aodBackdrop;
        final int generation;
        Prepared(Bitmap art, Bitmap aodBackdrop, int generation) {
            this.art = art;
            this.aodBackdrop = aodBackdrop;
            this.generation = generation;
        }
        void recycle() {
            art.recycle();
            if (aodBackdrop != null) aodBackdrop.recycle();
        }
    }

    private Prepared current, previous;
    private long changedAt;
    private long lastFrame;
    private boolean ticking;
    private float opacity;
    private final CardSpring scale = new CardSpring();
    private boolean playing = sPlayingState;
    private boolean exitWithCard;
    private CoverCardStyle style = sStyle;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF square = new RectF();
    /** Per-frame scratch, kept rather than allocated on every draw. */
    private final Path clipPath = new Path();
    private final int[] tmpLoc = new int[2];
    private ViewTreeObserver geometryObserver;
    private ViewTreeObserver.OnPreDrawListener geometryListener;
    private boolean lastLockEligible;
    private int lastWidth = -1, lastHeight = -1, lastTop = Integer.MIN_VALUE;
    private float lastClock = Float.NaN, lastMedia = Float.NaN;

    private final Wash wash;
    /** Dozed under the OEM's big clock and not yet back to a settled lock screen; see doFrame. */
    private boolean afterBigClock;
    /** 0..1, how far the square has grown out of RISE_FROM; 1 everywhere but the big clock. */
    private float rise = 1f;
    /** The size the square is uncovered at, under the big clock's retreating digits. */
    private static final float RISE_FROM = 0.92f;
    /** How far down the square the big clock has to reach for it to be fully covered. */
    private static final float REVEAL_SPAN = 0.8f;
    /**
     * Falling asleep, the wallpaper goes to black in ~200ms along (1 - t)^2 (read frame by frame
     * off a recording), and the square goes with it. It used to ease at the clock's response and
     * was still half lit when the doze took the layer away, so it hung over black and then cut.
     */
    private static final float SLEEP_FADE_S = 0.2f;
    /** When this fall began (frame time, 0 when not falling) and the opacity it began from. */
    private long fallAt;
    private float fallFrom;
    /**
     * Waking, the wallpaper comes back from black in ~350ms along 1 - (1 - t)^3, read off the
     * same recording. The square was lit in one frame at 3/4 while the screen was still dark.
     */
    private static final float WAKE_FADE_S = 0.35f;
    /** How lit the wallpaper is taken to be, 0..1: what a wake starts from. */
    private float lit = 1f;
    private long wakeAt;
    private float wakeFrom;

    private CoverCardLayer(Context context) {
        super(context);
        wash = new Wash(context);
        setClickable(false);
        setFocusable(false);
    }

    static void attach(ViewGroup layer) {
        if (layer == null) return;
        final ViewGroup target = layer;
        Main.main().post(new Runnable() {
            @Override public void run() { attachNow(target); }
        });
    }

    private static void attachNow(ViewGroup layer) {
        if (!layer.isAttachedToWindow()) return;
        CoverCardLayer v = sView;
        if (v == null || v.getContext() != layer.getContext()) {
            if (v != null) {
                v.stop();
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                if (v.wash.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.wash.getParent()).removeView(v.wash);
                }
                if (v.previous != null && v.previous != sPending) v.previous.recycle();
                if (v.current != null && v.current != sPending) v.current.recycle();
            }
            v = new CoverCardLayer(layer.getContext());
            v.scale.snap(sPlayingState ? CardSpring.PLAYING : CardSpring.PAUSED);
            sView = v;
        }
        if (v.getParent() != layer) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            layer.addView(v, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // Called on every entry; bringToFront lays the layer out again, so only when needed.
        if (layer.getChildAt(layer.getChildCount() - 1) != v) v.bringToFront();
        // Beside keyguard_root_view rather than inside it: the doze zooms that view to 0.95 and
        // its bounds clip after the zoom, so nothing under it reaches the screen edge - a
        // counter-scaled wash in the layer was measured still cut to 95%. Just below it keeps
        // the same stacking the layer gave. The layer is the fallback, zoom and all.
        ViewGroup host = layer;
        View below = v;
        View zoomed = zoomedRoot(layer);
        if (zoomed != null && zoomed.getParent() instanceof ViewGroup) {
            host = (ViewGroup) zoomed.getParent();
            below = zoomed;
        }
        if (v.wash.getParent() != host) {
            if (v.wash.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.wash.getParent()).removeView(v.wash);
            }
            host.addView(v.wash, host.indexOfChild(below), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        v.style = sStyle;
        v.playing = sPlayingState;
        v.watchGeometry(layer);
        v.adoptPending();
        v.start();
    }

    /** keyguard_root_view, the view the doze zooms, found upwards from the layer. */
    private static View zoomedRoot(View from) {
        int id = from.getResources().getIdentifier("keyguard_root_view", "id",
                "com.android.systemui");
        if (id == 0) return null;
        for (Object p = from; p instanceof View; p = ((View) p).getParent()) {
            if (((View) p).getId() == id) return (View) p;
        }
        return null;
    }

    private void watchGeometry(ViewGroup layer) {
        if (geometryObserver == layer.getViewTreeObserver() && geometryListener != null) return;
        unwatchGeometry();
        geometryObserver = layer.getViewTreeObserver();
        geometryListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                // This parent is also drawn behind the unlocked notification shade. The card's
                // animation stops when it settles, and ACTION_USER_PRESENT is not guaranteed to
                // arrive before the shade uses this layer. Enforce the lock-screen condition on
                // the parent's draw, even when no card frame has been requested.
                if (!layerVisible()) {
                    lastLockEligible = false;
                    hideOutsideKeyguard("parent pre-draw");
                    return true;
                }
                if (!lastLockEligible) {
                    lastLockEligible = true;
                    start();
                }
                // Every frame of the window while the wash is up: the doze zoom it has to undo
                // animates, and nothing else of ours is drawing frames through it.
                if (wash.getVisibility() == VISIBLE) wash.fit();
                int[] loc = tmpLoc;
                getLocationOnScreen(loc);
                float clock = ClockCollapse.contentBottomOnScreen();
                float media = Main.coverCardMediaTop();
                if (lastWidth != getWidth() || lastHeight != getHeight() || lastTop != loc[1]
                        || Float.floatToIntBits(lastClock) != Float.floatToIntBits(clock)
                        || Float.floatToIntBits(lastMedia) != Float.floatToIntBits(media)) {
                    lastWidth = getWidth();
                    lastHeight = getHeight();
                    lastTop = loc[1];
                    lastClock = clock;
                    lastMedia = media;
                    start();
                }
                return true;
            }
        };
        geometryObserver.addOnPreDrawListener(geometryListener);
    }

    private void unwatchGeometry() {
        if (geometryObserver != null && geometryListener != null
                && geometryObserver.isAlive()) {
            geometryObserver.removeOnPreDrawListener(geometryListener);
        }
        geometryObserver = null;
        geometryListener = null;
        lastLockEligible = false;
        lastWidth = lastHeight = -1;
    }

    static void style(CoverCardStyle config) {
        sStyle = config;
        CoverCardLayer v = sView;
        if (v != null) {
            v.style = config;
            // The pre-draw guard runs on every frame of the shade window, lock screen or not.
            // Only the square needs it, so the full-screen cover does not pay for it.
            if (config.mode == CoverCardStyle.CARD) {
                if (v.getParent() instanceof ViewGroup) v.watchGeometry((ViewGroup) v.getParent());
                v.start();
            } else {
                v.unwatchGeometry();
                v.hideImmediately();
            }
        }
    }

    /** Called on the existing art worker. Never retain the player's bitmap. */
    static void publish(Bitmap source) {
        final int generation = ++sGeneration;
        if (source == null || source.isRecycled()) return;
        Bitmap readable = null, art = null, aodBackdrop = null;
        try {
            readable = source.getConfig() == Bitmap.Config.HARDWARE
                    ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            int w = readable.getWidth(), h = readable.getHeight();
            int side = Math.min(w, h);
            Rect crop = new Rect((w - side) / 2, (h - side) / 2,
                    (w + side) / 2, (h + side) / 2);
            art = Bitmap.createBitmap(Math.min(512, side), Math.min(512, side),
                    Bitmap.Config.ARGB_8888);
            new Canvas(art).drawBitmap(readable, crop,
                    new Rect(0, 0, art.getWidth(), art.getHeight()),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            // The AOD's own dimming can flatten the wallpaper almost to black. A small copy of
            // the same static blur is drawn over it at low alpha only in the full-screen AOD.
            // Prepare it with the artwork, off the UI thread, then reuse it without animation.
            try {
                int backdropW = 256;
                int backdropH = Math.min(768, Math.max(256,
                        Math.round(backdropW * Main.screenHeight()
                                / (float) Main.screenWidth())));
                aodBackdrop = CoverCompose.cardBackground(readable, backdropW, backdropH);
            } catch (Throwable t) {
                Xp.log("[MCCard] AOD backdrop preparation failed: " + t);
            }
            final Prepared p = new Prepared(art, aodBackdrop, generation);
            art = null;
            aodBackdrop = null;
            final Prepared old = sPending;
            sPending = p;
            Main.main().post(new Runnable() {
                @Override public void run() {
                    CoverCardLayer v = sView;
                    // Decided here, on the thread that adopts: read from the worker, `current`
                    // could become `old` between the check and the recycle, and the next draw
                    // would throw inside SystemUI.
                    if (old != null && old != sPending && (v == null
                            || (old != v.current && old != v.previous))) old.recycle();
                    if (v == null) return;
                    // The first art has nothing to keep pace with. A swap waits for the
                    // wallpaper to start its own - see releaseHeld() - with a ceiling in case
                    // that word never comes.
                    Main.main().removeCallbacks(ADOPT_HELD);
                    if (v.current == null) v.adoptPending();
                    else Main.main().postDelayed(ADOPT_HELD, HOLD_MAX_MS);
                }
            });
        } catch (Throwable t) {
            Xp.log("[MCCard] artwork preparation failed: " + t);
        } finally {
            if (readable != null && readable != source) readable.recycle();
            if (art != null) art.recycle();
            if (aodBackdrop != null) aodBackdrop.recycle();
        }
    }

    /** Longer than the 70-160ms the wallpaper was measured behind by, short of feeling stuck. */
    private static final long HOLD_MAX_MS = 400L;

    private static final Runnable ADOPT_HELD = new Runnable() {
        @Override public void run() {
            CoverCardLayer v = sView;
            if (v != null) v.adoptPending();
        }
    };

    /**
     * The blurred background under the square has started changing - the wallpaper process
     * says so (op wpart), or the video path put it up itself - so the square turns over with it.
     */
    static void releaseHeld() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Main.main().post(new Runnable() { @Override public void run() { releaseHeld(); } });
            return;
        }
        Main.main().removeCallbacks(ADOPT_HELD);
        ADOPT_HELD.run();
    }

    private static boolean layerVisible() {
        return Main.coverCardVisible() || Main.coverCardBackdropInAod();
    }

    static void clear() {
        ++sGeneration;
        Main.main().post(new Runnable() {
            @Override public void run() {
                CoverCardLayer v = sView;
                if (v != null) v.start();
            }
        });
    }

    static void refresh() {
        CoverCardLayer v = sView;
        if (v != null) {
            v.start();
            v.invalidate();
        }
    }

    static void hideNow() {
        CoverCardLayer v = sView;
        if (v == null) return;
        v.hideImmediately();
    }

    private void hideOutsideKeyguard(String from) {
        if (getVisibility() == VISIBLE) {
            Xp.log("[MCCard] hidden outside keyguard on " + from);
        }
        if (getVisibility() != GONE || ticking || opacity != 0f) hideImmediately();
    }

    private void hideImmediately() {
        stop();
        opacity = 0f;
        exitWithCard = false;
        if (getVisibility() != GONE) setVisibility(GONE);
        wash.hideNow();
    }

    static void entering() {
        CoverCardLayer v = sView;
        if (v != null) v.exitWithCard = false;
    }

    static void leaving() {
        CoverCardLayer v = sView;
        if (v != null) v.exitWithCard = v.opacity > 0.05f
                && !LockLyrics.wantsAttached();
    }

    static void playback(boolean on) {
        sPlayingState = on;
        CoverCardLayer v = sView;
        if (v != null) {
            v.playing = on;
            // An invisible card has no scale transition to preserve. Place it at the correct
            // playback size before a later thumbnail morph first asks for its destination.
            if (v.opacity <= 0f && !CoverMorphLayer.cardSuppressed()) {
                v.scale.snap(on ? CardSpring.PLAYING : CardSpring.PAUSED);
            }
            v.start();
        }
    }

    /** The square's current art, for the morph to fly - null when it has none yet. */
    static Bitmap currentArt() {
        CoverCardLayer v = sView;
        Prepared p = v == null ? null : v.current;
        return p == null || p.art.isRecycled() ? null : p.art;
    }

    /** For `op cardstate`. */
    static String describe() {
        CoverCardLayer v = sView;
        if (v == null) return "view=none";
        // The ancestors' scale and whether each one clips its children: the AOD wash is drawn
        // past this view's bounds, which only reaches the screen edge if nothing clips it.
        StringBuilder chain = new StringBuilder();
        for (Object p = v.getParent(); p instanceof ViewGroup; p = ((ViewGroup) p).getParent()) {
            ViewGroup g = (ViewGroup) p;
            String id = "?";
            try {
                if (g.getId() != View.NO_ID) id = g.getResources().getResourceEntryName(g.getId());
            } catch (Throwable ignored) {
            }
            chain.append(' ').append(id).append("[s=").append(g.getScaleX())
                    .append(g.getClipChildren() ? ",clip" : "").append(']');
        }
        Wash w = v.wash;
        int[] at = new int[2];
        w.getLocationOnScreen(at);
        chain.append(" wash[vis=").append(w.getVisibility() == VISIBLE)
                .append(' ').append(w.getWidth()).append('x').append(w.getHeight())
                .append(" s=").append(w.getScaleX()).append('/').append(w.getScaleY())
                .append(" pivot=").append(w.getPivotX()).append(',').append(w.getPivotY())
                .append(" at=").append(at[0]).append(',').append(at[1])
                .append(" screen=").append(Main.screenWidth()).append('x')
                .append(Main.screenHeight()).append(']');
        return "view.playing=" + v.playing + " scale=" + v.scale.value
                + " ticking=" + v.ticking + " opacity=" + v.opacity
                + " attached=" + v.isAttachedToWindow() + " chain=" + chain;
    }

    static float renderedScale(ViewGroup layer) {
        CoverCardLayer v = sView;
        return v != null && v.getParent() == layer ? v.scale.value
                : (sPlayingState ? CardSpring.PLAYING : CardSpring.PAUSED);
    }

    /** A hidden card is kept drawing internally until it can take the morph without a jump. */
    static boolean morphHandoffReady() {
        CoverCardLayer v = sView;
        if (v == null || v.current == null || v.getParent() == null) return false;
        float target = v.playing ? CardSpring.PLAYING : CardSpring.PAUSED;
        return v.opacity >= 0.995f && v.scale.atRest(target);
    }

    private void adoptPending() {
        Prepared p = sPending;
        if (p == null || p == current || p.generation != sGeneration) return;
        if (previous != null) previous.recycle();
        previous = current;
        current = p;
        changedAt = SystemClock.uptimeMillis();
        start();
    }

    private void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(new Runnable() { @Override public void run() { start(); } });
            return;
        }
        if (ticking) return;
        ticking = true;
        lastFrame = 0L;
        // A temporary vote for panels that honour it. The spring is sampled on every VSYNC,
        // and the vote is removed as soon as it settles; some SystemUI windows still cap at 60Hz.
        setRequestedFrameRate(120f);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void stop() {
        if (ticking) Choreographer.getInstance().removeFrameCallback(this);
        if (ticking) setRequestedFrameRate(0f);
        ticking = false;
        lastFrame = 0L;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // A detached/re-attached keyguard layer gets a new ViewTreeObserver. Keep the guard even
        // when SystemUI reuses this same card view for the shade.
        if (style.mode == CoverCardStyle.CARD && getParent() instanceof ViewGroup) {
            watchGeometry((ViewGroup) getParent());
        }
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        unwatchGeometry();
        super.onDetachedFromWindow();
    }

    /** Where the square is drawn, eased towards placeNow(); NaN until it has a place. */
    private float drawX = Float.NaN, drawY, drawSide;
    /** The last place the lit lock screen gave it - what the doze keeps. */
    private CoverCardStyle.Rect lockPlace;
    /**
     * The place of the landed cover look, Phase.ON only - what the big clock's fall and wake
     * keep. Not lockPlace: that one is still written while the clock grows into the doze.
     */
    private CoverCardStyle.Rect restPlace;

    /**
     * 0..1, how much of the square's place the big clock has left on its way up: 0 while the
     * digits still reach REVEAL_SPAN of the way down it, 1 once they have cleared its top. The square is held
     * at restPlace and grows and brightens with this, so it comes out from under the clock at
     * the clock's own pace - no gap before it, and no place of its own that it has to jump from.
     */
    private float reveal() {
        CoverCardStyle.Rect r = restPlace;
        float clock = ClockCollapse.contentBottomOnScreen();
        if (r == null || Float.isNaN(clock) || r.side <= 0f) return 0f;
        int[] loc = tmpLoc;
        getLocationOnScreen(loc);
        // The big clock was measured reaching ~88% of the way down the square, not past it.
        float span = r.side * REVEAL_SPAN;
        float p = (r.y + span - (clock - loc[1])) / span;
        p = Math.max(0f, Math.min(1f, p));
        return p * p * (3f - 2f * p);
    }

    /** The square's place from the live clock and media card, in this view's coordinates. */
    private CoverCardStyle.Rect placeNow() {
        int[] loc = tmpLoc;
        getLocationOnScreen(loc);
        return style.place(getWidth(), getHeight(), getResources().getDisplayMetrics().density,
                ClockCollapse.contentBottomOnScreen() - loc[1],
                Main.coverCardMediaTop() - loc[1]);
    }

    /**
     * Moves the drawn place one frame towards where the square belongs.
     *
     * Through the doze it keeps the lock screen's place: asked live, the media card's AOD stand-in
     * (70% of the screen) re-centred it, and it jumped 63px on the way in and back 11px on the
     * wake, while everything around it slid with the doze zoom. It sits inside that zoom, so
     * holding still in its own coordinates is exactly sliding with it. Any other change of
     * place - the wake landing, a rebuilt media card - is eased at the 缩放动画阻尼 response
     * instead of cut. A square that is not showing yet just takes its place.
     *
     * @return whether it is still on its way
     */
    private boolean followPlace(ClockCollapse.Phase phase, float dt, float response,
                                float target) {
        CoverCardStyle.Rect goal;
        if (afterBigClock && restPlace != null) {
            // Placed live, the big clock's bottom pushes it small and low against the media card.
            goal = restPlace;
        } else if (phase == ClockCollapse.Phase.AOD) {
            goal = lockPlace != null ? lockPlace : placeNow();
        } else {
            goal = placeNow();
            if (goal != null) lockPlace = goal;
            if (goal != null && phase == ClockCollapse.Phase.ON) restPlace = goal;
        }
        if (goal == null) return false;
        if (Float.isNaN(drawX) || opacity <= 0f) {
            drawX = goal.x;
            drawY = goal.y;
            drawSide = goal.side;
            return false;
        }
        float k = Math.min(1f, dt * 3f / response);
        drawX += (goal.x - drawX) * k;
        drawY += (goal.y - drawY) * k;
        drawSide += (goal.side - drawSide) * k;
        boolean moving = Math.abs(goal.x - drawX) > 0.5f || Math.abs(goal.y - drawY) > 0.5f
                || Math.abs(goal.side - drawSide) > 0.5f;
        if (!moving) {
            drawX = goal.x;
            drawY = goal.y;
            drawSide = goal.side;
        }
        return moving;
    }

    @Override public void doFrame(long nowNs) {
        if (!ticking || !isAttachedToWindow()) { stop(); return; }
        float dt = lastFrame == 0L ? 1f / 60f
                : Math.min(0.05f, Math.max(0f, (nowNs - lastFrame) / 1e9f));
        lastFrame = nowNs;
        ClockCollapse.Phase phase = ClockCollapse.phase();
        boolean inAod = Main.coverCardInAod();
        boolean visible = style.mode == CoverCardStyle.CARD && Main.coverCardVisible();
        // 0.2.2 can keep lyrics in the full-screen AOD. The selected lyric page owns this space.
        boolean lyrics = LockLyrics.wantsAttached();
        // A doze under the OEM's big clock hides the square. Its wake holds the square at the
        // place the landed lock screen gave it and lets the collapsing clock uncover it - see
        // reveal(). Shown at once it sat full size across the big digits, and placed live it was
        // squeezed small and low under the shrinking clock, then grew back.
        //
        // The fall counts, not only the doze: pressed again before the big clock has settled, the
        // clock never reaches Phase.AOD - it goes EXIT (into the AOD) straight back to ENTER.
        if (!ClockCollapse.aodHeld() && (phase == ClockCollapse.Phase.AOD
                || (phase == ClockCollapse.Phase.EXIT && !ClockCollapse.exiting()))) {
            afterBigClock = true;
        } else if (phase == ClockCollapse.Phase.ON || phase == ClockCollapse.Phase.OFF) {
            afterBigClock = false;
        }
        boolean bigWake = afterBigClock && phase == ClockCollapse.Phase.ENTER;
        // The other half: the clock growing into the big one covers the square as it comes down.
        boolean bigFall = afterBigClock && phase == ClockCollapse.Phase.EXIT
                && !ClockCollapse.exiting();
        boolean underBig = bigWake || bigFall;
        float target = visible && current != null && (!afterBigClock || underBig)
                && (phase == ClockCollapse.Phase.EXIT && !bigFall ? exitWithCard : !lyrics)
                ? (inAod ? 1f : underBig ? reveal() : Main.cardProgress()) : 0f;
        float response = Math.max(0.18f, Main.sClockResponse);
        // Tied to the clock's own flight on the lit screen, and to the big clock's edge on the way
        // into and out of its doze. Otherwise falling asleep it eases.
        if (!bigFall) fallAt = 0L;
        if (!bigWake) wakeAt = 0L;
        if (!afterBigClock) lit = 1f;
        else if (phase == ClockCollapse.Phase.AOD) lit = 0f;
        if (bigFall) {
            // Out with the wallpaper as well, for a big clock that stops short of covering it.
            if (fallAt == 0L) {
                fallAt = nowNs;
                fallFrom = opacity;
            }
            float u = Math.min(1f, (nowNs - fallAt) / 1e9f / SLEEP_FADE_S);
            lit = (1f - u) * (1f - u);
            opacity = Math.min(target, fallFrom * lit);
        } else if (bigWake) {
            // And back in with it - from wherever a fall pressed short of the doze had left it.
            if (wakeAt == 0L) {
                wakeAt = nowNs;
                wakeFrom = lit;
            }
            float u = 1f - Math.min(1f, (nowNs - wakeAt) / 1e9f / WAKE_FADE_S);
            lit = wakeFrom + (1f - wakeFrom) * (1f - u * u * u);
            opacity = target * lit;
        } else if ((inAod && lyrics) || phase == ClockCollapse.Phase.ENTER
                || (phase == ClockCollapse.Phase.EXIT && Main.screenOn())) {
            opacity = target;
        } else {
            opacity += (target - opacity) * Math.min(1f, dt * 3f / response);
        }
        // Grows with the reveal on the way up, and shrinks under the digits on the way down.
        if (afterBigClock) rise = underBig ? target : Math.min(rise, opacity);
        else if (opacity <= 0f) rise = 1f;
        else rise += (1f - rise) * Math.min(1f, dt * 3f / response);
        if (rise > 0.998f) rise = 1f;
        if (Math.abs(opacity - target) < 0.002f) opacity = target;
        float scaleTarget = playing ? CardSpring.PLAYING : CardSpring.PAUSED;
        if (phase == ClockCollapse.Phase.AOD) scale.snap(scaleTarget);
        // At the response the app's 缩放动画阻尼 sets, so the card keeps time with the clock,
        // the wallpaper and the morph instead of running on a clock of its own.
        else scale.step(scaleTarget, dt, Main.sClockResponse);
        boolean placing = followPlace(phase, dt, response, target);
        if (previous != null && (phase == ClockCollapse.Phase.AOD
                || SystemClock.uptimeMillis() - changedAt > TRACK_FADE_MS)) {
            previous.recycle();
            previous = null;
        }
        boolean backdrop = Main.coverCardBackdropInAod() && current != null
                && current.aodBackdrop != null;
        wash.show(backdrop);
        int visibility = opacity > 0f || target > 0f ? VISIBLE : GONE;
        if (getVisibility() != visibility) setVisibility(visibility);
        if (visibility == VISIBLE) invalidate();
        boolean settling = Math.abs(target - opacity) > 0.001f
                || (phase != ClockCollapse.Phase.AOD && !scale.atRest(scaleTarget))
                || previous != null || placing || (rise < 1f && opacity > 0f)
                // Still waking from the big clock: keep looking until the clock has landed.
                || (afterBigClock && phase != ClockCollapse.Phase.AOD);
        if (settling) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            stop();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        // Last line of defence if SystemUI replaced the parent's observer during a transition.
        if (!layerVisible()) {
            lastLockEligible = false;
            hideOutsideKeyguard("card draw");
            return;
        }
        if (current == null) return;
        // A doze frame may draw before the queued animation frame clears a stale lock-screen
        // opacity. The lyric page must never show the square underneath it in the full AOD.
        if (opacity <= 0f || CoverMorphLayer.cardSuppressed()
                || (Main.coverCardInAod() && LockLyrics.wantsAttached())) return;
        float density = getResources().getDisplayMetrics().density;
        if (Float.isNaN(drawX)) return;
        CoverMorphMotion.Box actual = CoverMorphMotion.cardSquare(drawX, drawY,
                drawSide, scale.value * (RISE_FROM + (1f - RISE_FROM) * rise));
        float scaled = actual.w;
        square.set(actual.x, actual.y, actual.x + actual.w, actual.y + actual.h);
        float radius = Math.min(20f * density, scaled * 0.10f);
        drawShadow(canvas, square, radius, density, opacity, paint);
        int save = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(square, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        drawArt(canvas, previous, 1f);
        drawArt(canvas, current, fadeFraction());
        canvas.restoreToCount(save);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0x40FFFFFF);
        paint.setAlpha(Math.round(opacity * 64f));
        canvas.drawRoundRect(square, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * A soft drop shadow under the square, shared with CoverMorphLayer so the hand-over does not
     * change it. Blurred and pulled in from the edges: an unblurred copy offset downwards reads
     * as a dark slab along the bottom edge rather than as a shadow.
     *
     * Drawn from one small pre-blurred tile, scaled to the square. It was a BlurMaskFilter on the
     * live rect, and a rect that changes size every frame - the morph, the play/pause spring - is
     * a fresh GPU blur every frame: the frames at the start of a tap toggle measured 15-18ms of
     * GPU against an 8.3ms budget, on top of the wallpaper's own crossfade. Scaled, the shadow
     * keeps its proportions to the square: at a 300dp square it is the 16dp blur, 6dp inset and
     * 8dp drop it was drawn with before.
     */
    static void drawShadow(Canvas canvas, RectF box, float radius, float density,
                           float strength, Paint paint) {
        if (strength <= 0f || box.width() <= 0f) return;
        Bitmap tile = shadowTile();
        float k = box.width() / SHADOW_UNIT;
        float pad = SHADOW_PAD * k, dy = SHADOW_DROP * box.width();
        sShadowDst.set(box.left - pad, box.top - pad + dy, box.right + pad, box.bottom + pad + dy);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        paint.setAlpha(Math.round(strength * 90f));
        canvas.drawBitmap(tile, null, sShadowDst, paint);
    }

    /** The tile's square, in its own pixels; the ratios below are the old dp values over 300dp. */
    private static final float SHADOW_UNIT = 200f;
    private static final float SHADOW_BLUR = SHADOW_UNIT * 16f / 300f;
    private static final float SHADOW_INSET = SHADOW_UNIT * 6f / 300f;
    private static final float SHADOW_RADIUS = SHADOW_UNIT * 20f / 300f;
    private static final float SHADOW_DROP = 8f / 300f;
    /** Room round the square for the blur to fall off in. */
    private static final float SHADOW_PAD = SHADOW_BLUR * 2f;
    private static Bitmap sShadowTile;
    private static final RectF sShadowDst = new RectF();

    private static Bitmap shadowTile() {
        Bitmap t = sShadowTile;
        if (t != null && !t.isRecycled()) return t;
        int side = Math.round(SHADOW_UNIT + 2f * SHADOW_PAD);
        t = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR, BlurMaskFilter.Blur.NORMAL));
        float lo = SHADOW_PAD + SHADOW_INSET, hi = SHADOW_PAD + SHADOW_UNIT - SHADOW_INSET;
        new Canvas(t).drawRoundRect(lo, lo, hi, hi, SHADOW_RADIUS, SHADOW_RADIUS, p);
        sShadowTile = t;
        return t;
    }

    /**
     * The full-screen AOD's faint colour wash, as a view of its own beside the square.
     *
     * The doze scales keyguard_root_view to 0.95 (`op cardstate` lists the chain). Drawn inside
     * it, the wash came out 5% short and framed by the dark wallpaper, and neither drawing past
     * the card layer's bounds nor scaling this view back out by the inverse of the zoom got past
     * that: the second one was measured landing on the full screen at (0,0) and still cut to
     * 95%. So it lives beside keyguard_root_view, just below it (see attachNow), and fit() is
     * left to undo whatever zoom is still above it - normally none. The zoom itself is right and
     * stays: the square rides it with the rest of the lock screen.
     */
    private static final class Wash extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] loc = new int[2];
        private final RectF bounds = new RectF();

        Wash(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(GONE);
        }

        /**
         * In over the doze's own dimming and out over the wallpaper's brightening. Put up and
         * taken down in one frame it flashed: brighter for a moment going into the AOD, while the
         * wallpaper had not dimmed yet, and darker for ~100ms coming out, while it was still dim.
         */
        private static final long IN_MS = 500L, OUT_MS = 250L;
        private boolean want;
        private float from;
        private long changedAt;

        /** 0..1, how much of the wash is up right now. */
        private float level() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - changedAt)
                    / (float) (want ? IN_MS : OUT_MS));
            float e = t * t * (3f - 2f * t);
            return from + ((want ? 1f : 0f) - from) * e;
        }

        void show(boolean on) {
            if (on != want) {
                from = getVisibility() == VISIBLE ? level() : 0f;
                want = on;
                changedAt = SystemClock.uptimeMillis();
            }
            if (on && getVisibility() != VISIBLE) setVisibility(VISIBLE);
            if (getVisibility() == VISIBLE) {
                if (on) fit();
                invalidate();
            }
        }

        /** Unlocked, or anywhere else the wash has no business: gone at once. */
        void hideNow() {
            want = false;
            from = 0f;
            if (getVisibility() != GONE) setVisibility(GONE);
        }

        /** Scales this view so that its bounds land on the whole display. */
        void fit() {
            if (!(getParent() instanceof View) || getWidth() <= 0 || getHeight() <= 0) return;
            View parent = (View) getParent();
            float sx = 1f, sy = 1f;
            for (Object p = parent; p instanceof View; p = ((View) p).getParent()) {
                sx *= ((View) p).getScaleX();
                sy *= ((View) p).getScaleY();
            }
            int sw = Main.screenWidth(), sh = Main.screenHeight();
            if (sw <= 0 || sh <= 0 || !(sx > 0f) || !(sy > 0f)) return;
            parent.getLocationOnScreen(loc);
            fitAxis(true, loc[0], sx, sw / (getWidth() * sx));
            fitAxis(false, loc[1], sy, sh / (getHeight() * sy));
        }

        /**
         * On screen, x in this view lands at origin + s * (pivot + (x - pivot) * k). Solving for
         * x = 0 landing on 0 gives the pivot; k is what makes the far edge land on the far edge.
         */
        private void fitAxis(boolean x, float origin, float s, float k) {
            float pivot = 0f;
            if (Math.abs(1f - k) > 1e-4f) pivot = -origin / (s * (1f - k));
            else k = 1f;
            if (x) {
                if (getScaleX() != k) setScaleX(k);
                if (getPivotX() != pivot) setPivotX(pivot);
            } else {
                if (getScaleY() != k) setScaleY(k);
                if (getPivotY() != pivot) setPivotY(pivot);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            CoverCardLayer v = sView;
            Prepared p = v == null ? null : v.current;
            float level = level();
            boolean moving = SystemClock.uptimeMillis() - changedAt < (want ? IN_MS : OUT_MS);
            if (!want && !moving) {
                // Faded all the way out: off the draw list until it is wanted again.
                post(new Runnable() {
                    @Override public void run() { if (!want) setVisibility(GONE); }
                });
                return;
            }
            if (moving) postInvalidateOnAnimation();
            if (p == null || p.aodBackdrop == null || p.aodBackdrop.isRecycled()) return;
            paint.setAlpha(Math.round(64f * level));
            bounds.set(0f, 0f, getWidth(), getHeight());
            canvas.drawBitmap(p.aodBackdrop, null, bounds, paint);
        }
    }

    /**
     * The same crossfade the wallpaper runs under it on a track change - WallpaperProbe's
     * sTrackFadeMs and its cubic ease-out - so the square and its blurred backdrop turn over
     * together instead of the art lagging the background by 400ms.
     */
    private static final long TRACK_FADE_MS = 180L;

    private float fadeFraction() {
        if (previous == null) return 1f;
        float t = Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / (float) TRACK_FADE_MS);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private void drawArt(Canvas canvas, Prepared p, float fraction) {
        if (p == null || fraction <= 0f) return;
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255f * opacity * fraction));
        canvas.drawBitmap(p.art, null, square, paint);
    }

}
