package dev.kawarp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;

/** Owns shader uniforms per drawing target; preprocessed artwork stays shared in KawarpFrame. */
public final class KawarpFrameRenderer {
    private final RuntimeShader shader = new RuntimeShader(KawarpEngine.AGSL);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Full-screen flow uses identity coordinates and no shader darkening. */
    public void drawRoot(Canvas canvas, KawarpFrame frame, float width, float height) {
        draw(canvas, frame, width, height, 0f, 0f, width, height,
                1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
    }

    public void draw(Canvas canvas, KawarpFrame frame, float flowWidth, float flowHeight,
                     float left, float top, float right, float bottom,
                     float mapXX, float mapXY, float mapYX, float mapYY,
                     float originX, float originY, float radius, float alpha, float shade) {
        if (frame == null || !canvas.isHardwareAccelerated() || flowWidth <= 0f || flowHeight <= 0f
                || right <= left || bottom <= top || alpha <= 0f) return;
        shader.setInputShader("texA", frame.previous);
        shader.setInputShader("texB", frame.current);
        shader.setFloatUniform("uRes", flowWidth, flowHeight);
        shader.setFloatUniform("uTime", frame.time);
        shader.setFloatUniform("uBlend", frame.blend);
        shader.setFloatUniform("uWarp", frame.warp);
        shader.setFloatUniform("uSat", frame.saturation);
        shader.setFloatUniform("uDither", frame.dither);
        shader.setFloatUniform("uScale", frame.scale);
        shader.setFloatUniform("uBright", frame.brightness);
        shader.setFloatUniform("uContrast", frame.contrast);
        shader.setFloatUniform("uShade", Math.max(0f, Math.min(1f, shade)));
        shader.setFloatUniform("uMapX", mapXX, mapXY);
        shader.setFloatUniform("uMapY", mapYX, mapYY);
        shader.setFloatUniform("uOrigin", originX, originY);
        paint.setShader(shader);
        paint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }
}
