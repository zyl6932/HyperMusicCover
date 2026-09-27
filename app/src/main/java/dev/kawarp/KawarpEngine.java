/*
 * Kawarp-AGSL - a fluid, animated album-art backdrop for Android.
 * Copyright (C) 2026 meowarex
 *
 * Portions derived from kawarp (https://github.com/better-lyrics/kawarp),
 * Copyright (c) better-lyrics, MIT licensed - see THIRD-PARTY.md.
 *
 * This library is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.kawarp;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kawarp for Android - a fluid, animated album-art backdrop (Apple Music style).
 *
 * AGSL port of @kawarp/core 1.2.0 (github.com/better-lyrics/kawarp, MIT). The original's
 * per-cover WebGL passes (tint + Kawase blur at 128x128) run on the CPU, because Android's
 * RuntimeShader cannot draw onto a software (Bitmap-backed) canvas; its three per-frame
 * programs (blend / domain warp / output) are folded into one AGSL shader that runs on the
 * hardware canvas you draw to.
 *
 * Usage: construct, push settings, {@link #setCover(Bitmap)} whenever the artwork changes,
 * and call {@link #draw(Canvas, float, float)} every frame while visible (the canvas must be
 * hardware accelerated - any View onDraw / Compose draw scope qualifies). Cover processing
 * happens off-thread; frames before the first cover is ready draw nothing and return false.
 *
 * Requires API 33 (AGSL). Check {@link #isSupported()} and keep your own fallback; construction
 * throws on unsupported devices or if the device's shader compiler rejects the AGSL.
 *
 * Maintainers: this class must stay dex-injectable for downstream users that compile it
 * straight into an APK patch - pure Java 8, zero dependencies beyond android.*, no nested
 * classes / lambdas / string concat in hot constants (they generate invoke-dynamic or
 * access$ bridges under d8 --no-desugaring).
 */
public final class KawarpEngine implements Runnable {

    /** The whole blur pipeline runs at this resolution (kawarp's BLUR_SIZE). */
    private static final int BLUR_SIZE = 128;
    /** Seconds for the playback-reactive ramp to coast between full speed and stopped. */
    private static final float RAMP_SECONDS = 1.8f;
    /** Auto-darken curve: luminance ceilings at strength 0 and 1, and the darkest we ever go. */
    private static final float CEIL_MIN = 0.9f, CEIL_MAX = 0.15f, MIN_BRIGHTNESS = 0.15f;

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    /** kawarp's BLEND + DOMAIN_WARP + OUTPUT programs, folded into one pass. */
    static final String AGSL =
        "uniform shader texA;\n" +
        "uniform shader texB;\n" +
        "uniform float2 uRes;\n" +
        "uniform float uTime;\n" +
        "uniform float uBlend;\n" +
        "uniform float uWarp;\n" +
        "uniform float uSat;\n" +
        "uniform float uDither;\n" +
        "uniform float uScale;\n" +
        "uniform float uBright;\n" +
        "uniform float uContrast;\n" +
        "uniform float uShade;\n" +
        "uniform float2 uMapX;\n" +
        "uniform float2 uMapY;\n" +
        "uniform float2 uOrigin;\n" +
        "\n" +
        "float3 m289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n" +
        "float2 m289_2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n" +
        "float3 permute(float3 x) { return m289_3(((x * 34.0) + 1.0) * x); }\n" +
        "\n" +
        "float snoise(float2 v) {\n" +
        "  float4 C = float4(0.211324865405187, 0.366025403784439,\n" +
        "                    -0.577350269189626, 0.024390243902439);\n" +
        "  float2 i = floor(v + dot(v, C.yy));\n" +
        "  float2 x0 = v - i + dot(i, C.xx);\n" +
        "  float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);\n" +
        "  float4 x12 = x0.xyxy + C.xxzz;\n" +
        "  x12.xy -= i1;\n" +
        "  i = m289_2(i);\n" +
        "  float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));\n" +
        "  float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);\n" +
        "  m = m * m;\n" +
        "  m = m * m;\n" +
        "  float3 x = 2.0 * fract(p * C.www) - 1.0;\n" +
        "  float3 h = abs(x) - 0.5;\n" +
        "  float3 ox = floor(x + 0.5);\n" +
        "  float3 a0 = x - ox;\n" +
        "  m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);\n" +
        "  float3 g;\n" +
        "  g.x = a0.x * x0.x + h.x * x0.y;\n" +
        "  g.yz = a0.yz * x12.xz + h.yz * x12.yw;\n" +
        "  return 130.0 * dot(m, g);\n" +
        "}\n" +
        "\n" +
        "float hash13(float3 seed) {\n" +
        "  float3 q = fract(seed * 0.1031);\n" +
        "  q += dot(q, q.zyx + 31.32);\n" +
        "  return fract((q.x + q.y) * q.z);\n" +
        "}\n" +
        "\n" +
        "half4 main(float2 fragCoord) {\n" +
        "  float2 flowCoord = float2(dot(uMapX, fragCoord), dot(uMapY, fragCoord)) + uOrigin;\n" +
        "  float2 uv0 = flowCoord / uRes;\n" +
        "  float2 uv = clamp((uv0 - 0.5) / uScale + 0.5, 0.0, 1.0);\n" +
        "\n" +
        "  float t = uTime * 0.05;\n" +
        "  float2 c = uv - 0.5;\n" +
        "  float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(c));\n" +
        "  float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));\n" +
        "  float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));\n" +
        "  float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));\n" +
        "  float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));\n" +
        "  float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;\n" +
        "  float2 wuv = clamp(uv + warp * uWarp, 0.0, 1.0);\n" +
        "\n" +
        "  float2 sp = wuv * 128.0;\n" +
        "  float3 col = mix(float3(texA.eval(sp).rgb), float3(texB.eval(sp).rgb), uBlend);\n" +
        "\n" +
        "  float2 c2 = uv0 - 0.5;\n" +
        "  col *= 1.0 - dot(c2, c2) * 0.3;\n" +
        "  float gray = dot(col, float3(0.299, 0.587, 0.114));\n" +
        "  col = mix(float3(gray), col, uSat);\n" +
        "  float n = hash13(float3(floor(uv0 * uRes), floor(uTime * 60.0)));\n" +
        "  col += (n - 0.5) * uDither;\n" +
        "\n" +
        "  col = (col - 0.5) * uContrast + 0.5;\n" +
        "  col *= uBright;\n" +
        "  col *= 1.0 - uShade;\n" +
        "  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);\n" +
        "}\n";

    // Settings (volatile: setters may be called from any thread). Defaults match @kawarp/core,
    // with contrast/brightness/autoDarken as extensions (the desktop plugin applies those as
    // CSS filters over the canvas).
    private volatile float warpIntensity = 1f;
    private volatile int blurPasses = 8;
    private volatile float animationSpeed = 1f;
    private volatile float saturation = 1.5f;
    private volatile float dithering = 0.008f;
    private volatile float scale = 1f;
    private volatile float contrast = 1f;
    private volatile float brightness = 1f;
    private volatile float autoDarken = 0f;
    private volatile int transitionMs = 1000;
    private volatile float tintR = 0.157f, tintG = 0.157f, tintB = 0.235f;
    private volatile float tintIntensity = 0.15f;
    private volatile boolean playbackReactive = false;
    private volatile boolean playing = true;

    private final KawarpFrameRenderer renderer;

    // Crossfade pair. BitmapShader retains its bitmap, so the blurred covers live through these.
    private volatile BitmapShader prevShader, nextShader;
    private volatile long transitionStart;
    private volatile float darkenBrightness = 1f;

    // Pending cover for the loader thread (this class is its own Runnable).
    private volatile Bitmap pendingCover;
    private volatile int coverToken;
    // HyperMusicCover integration: lets a paused View poll only while cover work is pending.
    private volatile int completedToken;

    // Frame clock (touched only from the draw thread).
    private float shaderTime;
    private float speedFactor = 1f;
    private long lastFrameUptime;

    /** True when this device can run the engine at all (AGSL needs API 33). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    /**
     * Compiles the shader eagerly; throws on API < 33 or if this device's shader compiler
     * rejects the AGSL, so a successful construction means drawing will work.
     */
    public KawarpEngine() {
        if (!isSupported()) throw new IllegalStateException("KawarpEngine needs API 33+ (AGSL)");
        renderer = new KawarpFrameRenderer();
    }

    // --- settings -------------------------------------------------------------------------

    /** Domain-warp strength; kawarp default 1. */
    public void setWarpIntensity(float value) { warpIntensity = value; }
    /** Kawase blur passes over the 128x128 cover, 1..40; kawarp default 8. Applies to the next cover. */
    public void setBlurPasses(int value) { blurPasses = Math.max(1, Math.min(40, value)); }
    /** Time multiplier for the warp animation; kawarp default 1. */
    public void setAnimationSpeed(float value) { animationSpeed = value; }
    /** 0 = grayscale, 1 = unchanged; kawarp default 1.5. */
    public void setSaturation(float value) { saturation = value; }
    /** Grain amplitude that hides banding; kawarp default 0.008. */
    public void setDithering(float value) { dithering = value; }
    /** Zoom applied before warping; kawarp default 1. */
    public void setScale(float value) { scale = value; }
    /** Contrast around mid-grey; 1 = unchanged. */
    public void setContrast(float value) { contrast = value; }
    /** Flat brightness multiplier; 1 = unchanged. */
    public void setBrightness(float value) { brightness = value; }
    /** 0 disables; at 1, bright covers are pulled down hardest. Applies to the next cover. */
    public void setAutoDarken(float value) { autoDarken = value; }
    /** Crossfade length between covers in ms; kawarp default 1000. */
    public void setTransitionDuration(int ms) { transitionMs = Math.max(0, ms); }
    /** Colour that dark regions are pushed towards before blurring. Applies to the next cover. */
    public void setTintColor(float r, float g, float b) { tintR = r; tintG = g; tintB = b; }
    /** Tint blend strength; kawarp default 0.15. Applies to the next cover. */
    public void setTintIntensity(float value) { tintIntensity = value; }
    /** When true, the animation coasts to a stop while {@link #setPlaying}(false). */
    public void setPlaybackReactive(boolean value) { playbackReactive = value; }
    /** Feed your player state here; only matters with playback-reactive on. */
    public void setPlaying(boolean value) { playing = value; }

    // --- covers ---------------------------------------------------------------------------

    /**
     * Push new artwork. The bitmap is downscaled, tinted and blurred on a background thread,
     * then crossfaded in; the caller keeps ownership of the passed bitmap. Safe to call from
     * any thread, at any rate - only the latest cover wins.
     */
    public void setCover(Bitmap cover) {
        if (cover == null) return;
        // Copy now so the caller can recycle theirs immediately.
        pendingCover = Bitmap.createScaledBitmap(cover, BLUR_SIZE, BLUR_SIZE, true);
        coverToken++;
        LOADER.execute(this);
    }

    /** True once at least one cover has been processed and frames will actually render. */
    public boolean isReady() {
        return nextShader != null;
    }

    /** Loader-thread entry point (an implementation detail; do not call). */
    public void run() {
        Bitmap small = pendingCover;
        int token = coverToken;
        if (small == null) return;
        process(small, token);
    }

    /**
     * kawarp's per-cover pipeline (blurSourceInto) on the CPU: tint, then N half-pixel-offset
     * Kawase passes. Covers are opaque, so only RGB is carried. The original's trailing
     * offset-0 pass is skipped: at the exact pixel centre it is the identity - it only existed
     * to copy into the album framebuffer.
     */
    private void process(Bitmap small, int token) {
        int n = BLUR_SIZE * BLUR_SIZE;
        int[] pixels = new int[n];
        small.getPixels(pixels, 0, BLUR_SIZE, 0, 0, BLUR_SIZE, BLUR_SIZE);

        float[] read = new float[n * 3];
        float[] write = new float[n * 3];
        float tr = tintR, tg = tintG, tb = tintB, ti = tintIntensity;
        float luma = 0f;
        for (int i = 0; i < n; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8) & 0xFF) / 255f;
            float b = (p & 0xFF) / 255f;
            // Mean luma of the untouched art, for the auto-darken curve.
            luma += 0.2126f * r + 0.7152f * g + 0.0722f * b;
            // kawarp's TINT_SHADER: push dark areas towards the tint colour.
            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            float t = y * 2f;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            float k = (1f - t * t * (3f - 2f * t)) * ti;
            int o = i * 3;
            read[o] = r + (tr - r) * k;
            read[o + 1] = g + (tg - g) * k;
            read[o + 2] = b + (tb - b) * k;
        }
        luma /= n;

        int passes = blurPasses;
        for (int i = 0; i < passes; i++) {
            kawasePass(read, write, i);
            float[] swap = read;
            read = write;
            write = swap;
        }

        for (int i = 0; i < n; i++) {
            int o = i * 3;
            pixels[i] = 0xFF000000
                | (channel(read[o]) << 16)
                | (channel(read[o + 1]) << 8)
                | channel(read[o + 2]);
        }
        Bitmap out = Bitmap.createBitmap(BLUR_SIZE, BLUR_SIZE, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, BLUR_SIZE, 0, 0, BLUR_SIZE, BLUR_SIZE);

        // A newer cover raced past us while we were blurring - drop this one.
        if (token != coverToken) return;
        BitmapShader s = new BitmapShader(out, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        prevShader = nextShader;
        nextShader = s;
        darkenBrightness = brightnessFor(luma);
        transitionStart = SystemClock.uptimeMillis();
        completedToken = token;
    }

    /**
     * kawarp's KAWASE_BLUR_SHADER, pass number `pass`. The GPU original takes four bilinear
     * taps at (±o, ±o) with o = pass + 0.5; a half-texel offset makes every bilinear weight
     * exactly 1/2, so the four taps collapse into a uniform average of the 16 texels at
     * x,y + {-(pass+1), -pass, pass, pass+1} (clamped to the edge).
     */
    private static void kawasePass(float[] src, float[] dst, int pass) {
        int[] offsets = {-(pass + 1), -pass, pass, pass + 1};
        for (int y = 0; y < BLUR_SIZE; y++) {
            for (int x = 0; x < BLUR_SIZE; x++) {
                float r = 0f, g = 0f, b = 0f;
                for (int dy : offsets) {
                    int sy = clamp(y + dy);
                    for (int dx : offsets) {
                        int i = (sy * BLUR_SIZE + clamp(x + dx)) * 3;
                        r += src[i];
                        g += src[i + 1];
                        b += src[i + 2];
                    }
                }
                int o = (y * BLUR_SIZE + x) * 3;
                dst[o] = r / 16f;
                dst[o + 1] = g / 16f;
                dst[o + 2] = b / 16f;
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v >= BLUR_SIZE ? BLUR_SIZE - 1 : v;
    }

    private static int channel(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }

    private float brightnessFor(float luminance) {
        float strength = autoDarken;
        if (strength <= 0f || luminance <= 0f) return 1f;
        float ceiling = CEIL_MIN - strength * (CEIL_MIN - CEIL_MAX);
        if (luminance <= ceiling) return 1f;
        return Math.max(ceiling / luminance, MIN_BRIGHTNESS);
    }

    // --- drawing --------------------------------------------------------------------------

    /**
     * Render one frame filling `width` x `height` at the canvas origin (translate/clip first
     * to place it elsewhere). The canvas must be hardware accelerated - a software canvas will
     * throw, like any RuntimeShader draw. Advances the animation clock by wall time, so just
     * keep invalidating while visible; gaps are clamped, so pausing the redraw loop is fine.
     *
     * Returns false (drawing nothing) until the first cover is ready.
     */
    public boolean draw(Canvas canvas, float width, float height) {
        if (!canvas.isHardwareAccelerated() || width <= 0f || height <= 0f) return false;
        KawarpFrame frame = frame(SystemClock.uptimeMillis());
        if (frame == null) return false;
        renderer.drawRoot(canvas, frame, width, height);
        return true;
    }

    /** Advance the one animation clock, then share this immutable state with other renderers. */
    public KawarpFrame frame(long now) {
        BitmapShader next = nextShader;
        if (next == null) return null;

        // Clamp so a stalled frame (or a paused redraw loop) does not jump the shader forwards.
        float dt = lastFrameUptime == 0L ? 0f : Math.max(0f,
                Math.min((now - lastFrameUptime) / 1000f, 0.1f));
        lastFrameUptime = now;
        float target = (!playbackReactive || playing) ? 1f : 0f;
        float step = dt / RAMP_SECONDS;
        if (speedFactor < target) speedFactor = Math.min(target, speedFactor + step);
        else if (speedFactor > target) speedFactor = Math.max(target, speedFactor - step);
        shaderTime += dt * animationSpeed * speedFactor;

        float blend = 1f;
        int fade = transitionMs;
        long elapsed = now - transitionStart;
        if (fade > 0 && elapsed < fade) blend = elapsed / (float) fade;

        return new KawarpFrame(prevShader != null ? prevShader : next, next,
                shaderTime, blend, warpIntensity, saturation, dithering, scale,
                brightness * darkenBrightness, contrast);
    }

    /** Draw a previously advanced frame without changing its clock. */
    public void drawFrame(Canvas canvas, KawarpFrame frame, float width, float height) {
        if (frame == null || width <= 0f || height <= 0f) return;
        renderer.drawRoot(canvas, frame, width, height);
    }

    /** True while a crossfade or (with playback-reactive) a speed ramp is still moving. */
    public boolean isAnimating() {
        if (nextShader == null) return false;
        if (SystemClock.uptimeMillis() - transitionStart < transitionMs) return true;
        float target = (!playbackReactive || playing) ? 1f : 0f;
        return speedFactor != 0f || target != 0f;
    }

    /** True while a newly submitted cover is still being prepared off-thread. */
    public boolean isProcessing() { return coverToken != completedToken; }
}
