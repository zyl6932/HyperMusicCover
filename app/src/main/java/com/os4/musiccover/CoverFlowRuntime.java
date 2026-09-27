package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import dev.kawarp.KawarpEngine;
import dev.kawarp.KawarpFrame;

/** Album-colour backdrop. The static wallpaper stays visible until its first hardware frame. */
final class CoverFlowRuntime extends View {
    private static final long READY_FADE_MS = 220L;
    private static CoverFlowRuntime sView;
    private static String sConfig = CoverFlowConfig.defaultJson();
    private static CoverFlowConfig.Settings sSettings = CoverFlowConfig.INSTANCE.fromJson(sConfig);
    private static boolean sPlaying;

    private final Paint shadePaint = new Paint();
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            frameScheduled = false;
            updateFrame();
        }
    };
    private KawarpEngine engine;
    private KawarpFrame frameState;
    private long waitingSince;
    private long lastFrame;
    private float readiness;
    private boolean hadReady;
    private boolean hasCover;
    private boolean frameScheduled;
    private boolean sceneWasVisible;
    private boolean lyricsWereVisible;
    private boolean failed;
    private ImageView videoFallback;

    private CoverFlowRuntime(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
        setAlpha(0f);
    }

    static String configJson() { return sConfig; }

    static void applyConfig(String json) {
        final String normalized = CoverFlowConfig.normalizedJson(json);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Main.main().post(() -> applyConfig(normalized));
            return;
        }
        CoverFlowConfig.Settings before = sSettings;
        CoverFlowConfig.Settings after = CoverFlowConfig.INSTANCE.fromJson(normalized);
        sConfig = normalized;
        sSettings = after;
        CoverFlowRuntime v = sView;
        if (v == null) return;
        if (v.engine != null) {
            v.configure(after);
            if (before.getBlur() != after.getBlur() || before.getPreset() != after.getPreset()) {
                Bitmap art = CoverCardLayer.currentFlowArt();
                if (art != null && !art.isRecycled()) publish(art);
            }
        }
        v.refreshView();
    }

    static void attach(ViewGroup host, View below) {
        if (host == null || below == null) return;
        CoverFlowRuntime v = sView;
        if (v == null || v.getContext() != host.getContext()) {
            if (v != null && v.getParent() instanceof ViewGroup)
                ((ViewGroup) v.getParent()).removeView(v);
            v = new CoverFlowRuntime(host.getContext());
            sView = v;
        }
        int anchor = host.indexOfChild(below);
        if (anchor < 0) return;
        int desired = MiniPlayerRuntime.flowLayerIndex(host, anchor);
        int current = host.indexOfChild(v);
        if (v.getParent() != host || current > desired) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            host.addView(v, Math.min(desired, host.getChildCount()), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Xp.log("[MCFlow] layer attached at " + host.indexOfChild(v)
                    + ", shortcut/keyguard anchor " + anchor);
        }
        v.refreshView();
    }

    static void publish(Bitmap art) {
        CoverFlowRuntime v = sView;
        if (v == null || art == null || art.isRecycled() || !sSettings.getEnabled()) return;
        v.ensureEngine();
        if (v.engine == null || v.failed) return;
        // KawarpEngine returns its input at exactly 128px. Use a 129px temporary so its worker
        // owns a fresh 128px copy after this method returns.
        Bitmap copy = null;
        try {
            copy = Bitmap.createBitmap(129, 129, Bitmap.Config.ARGB_8888);
            new Canvas(copy).drawBitmap(art, null, new Rect(0, 0, 129, 129),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            v.engine.setCover(copy);
            v.hasCover = true;
            v.waitingSince = SystemClock.uptimeMillis();
            v.refreshView();
        } catch (Throwable t) {
            v.fail("cover preparation", t);
        } finally {
            if (copy != null) copy.recycle();
        }
    }

    static void playback(boolean playing) {
        sPlaying = playing;
        CoverFlowRuntime v = sView;
        if (v != null) {
            if (v.engine != null) v.engine.setPlaying(playing);
            v.refreshView();
        }
    }

    static void refresh() {
        CoverFlowRuntime v = sView;
        if (v != null) v.refreshView();
    }

    static void hide() {
        CoverFlowRuntime v = sView;
        if (v != null) {
            v.removeCallbacks(v.frame);
            v.frameScheduled = false;
            v.setAlpha(0f);
            v.setVisibility(GONE);
            v.frameState = null;
            v.restoreVideoFallback();
        }
        CoverFlowCards.clear();
    }

    private boolean sceneVisible() {
        return sSettings.getEnabled() && Main.sCoverCardStyle.mode == CoverCardStyle.CARD
                && Main.coverCardVisible();
    }

    private void ensureEngine() {
        if (engine != null || failed || !KawarpEngine.isSupported()) return;
        try {
            engine = new KawarpEngine();
            engine.setTransitionDuration(180);
            engine.setPlaybackReactive(true);
            engine.setPlaying(sPlaying);
            configure(sSettings);
        } catch (Throwable t) {
            fail("shader creation", t);
        }
    }

    private void configure(CoverFlowConfig.Settings settings) {
        engine.setWarpIntensity(settings.getWarp());
        engine.setAnimationSpeed(settings.getSpeed());
        engine.setBlurPasses(settings.getBlur());
        if (settings.getPreset() == CoverFlowConfig.SOFT) {
            engine.setSaturation(1.1f);
            engine.setAutoDarken(0.55f);
        } else if (settings.getPreset() == CoverFlowConfig.VIVID) {
            engine.setSaturation(1.8f);
            engine.setAutoDarken(0.1f);
        } else {
            engine.setSaturation(1.5f);
            engine.setAutoDarken(0f);
        }
    }

    private void refreshView() {
        if (failed) { hide(); return; }
        if (sceneVisible()) {
            ensureEngine();
            if (engine != null && !hasCover) {
                Bitmap art = CoverCardLayer.currentFlowArt();
                if (art != null && !art.isRecycled()) publish(art);
            }
        }
        updateFrame();
    }

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        postOnAnimation(frame);
    }

    private void updateFrame() {
        if (failed) { hide(); return; }
        boolean scene = sceneVisible();
        if (scene != sceneWasVisible) {
            sceneWasVisible = scene;
            Xp.log("[MCFlow] scene " + (scene ? "enter" : "exit")
                    + " at card=" + Main.cardProgress());
        }
        long now = SystemClock.uptimeMillis();
        boolean processing = engine != null && engine.isProcessing();
        if (processing && waitingSince != 0L && now - waitingSince >= 5000L) {
            fail("cover preparation timed out", null);
            return;
        }
        boolean ready = engine != null && engine.isReady();
        if (ready && !hadReady) {
            hadReady = true;
            lastFrame = now;
            Xp.log("[MCFlow] first shader ready at card=" + Main.cardProgress());
        }
        long dt = lastFrame == 0L ? 0L : Math.min(50L, Math.max(0L, now - lastFrame));
        lastFrame = now;
        if (ready && readiness < 1f)
            readiness = Math.min(1f, readiness + dt / (float) READY_FADE_MS);
        float progress = scene ? Main.cardProgress() : 0f;
        float opacity = CoverFlowScene.opacity(progress, CoverCardLayer.flowLit(), readiness);
        frameState = ready ? engine.frame(now) : null;
        if (getVisibility() != VISIBLE && (scene || opacity > 0f)) setVisibility(VISIBLE);
        if (getAlpha() != opacity) setAlpha(opacity);
        if (getVisibility() == VISIBLE && !scene && opacity == 0f) setVisibility(GONE);
        float lyricShow = LockLyrics.flowShow();
        boolean lyrics = lyricShow > 0.01f;
        if (lyrics != lyricsWereVisible) {
            lyricsWereVisible = lyrics;
            Xp.log("[MCFlow] lyric shade " + (lyrics ? "enter" : "exit"));
        }
        updateVideoFallback(opacity);
        CoverFlowCards.update(this, frameState,
                CoverFlowScene.cardOpacity(opacity, scene && Main.flowCardsEligible()),
                0.4f * lyricShow);
        if (opacity > 0f && (engine.isAnimating() || lyrics || processing || readiness < 1f))
            postInvalidateOnAnimation();
        if (scene && (!ready || processing || readiness < 1f || opacity > 0f
                && engine.isAnimating())) scheduleFrame();
    }

    /** The video path has a static ImageView inside the keyguard, above the flow in the root. */
    private void updateVideoFallback(float flowOpacity) {
        ImageView current = Main.sVideoWallpaper ? Main.sCover : null;
        if (videoFallback != current) {
            restoreVideoFallback();
            videoFallback = current;
        }
        if (videoFallback != null) {
            float fallbackAlpha = 1f - flowOpacity;
            if (videoFallback.getTransitionAlpha() != fallbackAlpha)
                videoFallback.setTransitionAlpha(fallbackAlpha);
        }
    }

    private void restoreVideoFallback() {
        if (videoFallback != null) videoFallback.setTransitionAlpha(1f);
        videoFallback = null;
    }

    private void fail(String operation, Throwable t) {
        failed = true;
        Xp.log("[MCFlow] " + operation + (t == null ? "" : ": " + t));
        hide();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        frameScheduled = false;
        restoreVideoFallback();
        CoverFlowCards.clear();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // OEM screenshot and blur probes use a software Canvas. RuntimeShader cannot draw there;
        // the real hardware layer must remain healthy for the next lock-screen frame.
        if (!canvas.isHardwareAccelerated() || engine == null || frameState == null
                || getAlpha() <= 0f || failed) return;
        try {
            engine.drawFrame(canvas, frameState, getWidth(), getHeight());
            int shade = CoverFlowScene.lyricShade(LockLyrics.flowShow());
            if (shade > 0) {
                shadePaint.setColor(0xff000000);
                shadePaint.setAlpha(shade);
                canvas.drawRect(0, 0, getWidth(), getHeight(), shadePaint);
            }
        } catch (Throwable t) {
            fail("draw failed", t);
        }
    }
}
