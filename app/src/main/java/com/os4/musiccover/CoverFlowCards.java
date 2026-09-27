package com.os4.musiccover;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import dev.kawarp.KawarpFrame;
import dev.kawarp.KawarpFrameRenderer;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

/** Paints the same live flow on the OEM's background-only card views, above their glass tint. */
final class CoverFlowCards {
    private static final String TAG = "[MCFlowCards] ";
    private static final WeakHashMap<View, Slot> ROWS = new WeakHashMap<>();
    private static final HashSet<String> MISSING = new HashSet<>();
    private static final Slot MEDIA = new Slot();
    private static boolean installed;

    private CoverFlowCards() {}

    static void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        try {
            Class<?> cls = Xp.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    loader);
            Xp.hookAllConstructors(cls, chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof View) track((View) chain.getThisObject());
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "notification row tracking unavailable: " + t);
        }
    }

    private static void track(View row) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Main.main().post(() -> track(row));
            return;
        }
        if (ROWS.containsKey(row)) return;
        Slot slot = new Slot();
        ROWS.put(row, slot);
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { slot.detach(); }
        });
    }

    static void update(CoverFlowRuntime source, KawarpFrame frame, float alpha, float shade) {
        if (frame == null || alpha <= 0f || !source.isAttachedToWindow()
                || source.getWidth() <= 0 || source.getHeight() <= 0) {
            clear();
            return;
        }
        View root = source.getRootView();
        View header = Main.miniPlayerMediaHeader();
        View mediaBg = Main.miniPlayerMediaBg(header);
        if (usable(mediaBg, root)) {
            MEDIA.bind(mediaBg);
            MEDIA.frame(source, frame, alpha, shade,
                    Main.miniPlayerCardRadius(header));
        } else MEDIA.detach();

        for (Map.Entry<View, Slot> entry : ROWS.entrySet()) {
            View row = entry.getKey();
            Slot slot = entry.getValue();
            if (row == null || !usable(row, root)) {
                slot.detach();
                continue;
            }
            View bg = slot.target();
            if (bg == null || !descendantOf(bg, row) || !usable(bg, root)) {
                slot.detach();
                if (SystemClock.uptimeMillis() < slot.nextSearchAt) continue;
                bg = rowBackground(row);
            }
            if (bg == null || !usable(bg, root)) {
                slot.detach();
                slot.nextSearchAt = SystemClock.uptimeMillis() + 250L;
                if (MISSING.add(row.getClass().getName()))
                    Xp.log(TAG + "notification background unavailable on "
                            + row.getClass().getName());
                continue;
            }
            slot.nextSearchAt = 0L;
            slot.bind(bg);
            slot.frame(source, frame, alpha, shade, Main.notificationRowRadius(row));
        }
    }

    static void clear() {
        MEDIA.detach();
        for (Slot slot : ROWS.values()) slot.detach();
    }

    private static boolean usable(View v, View root) {
        return v != null && v.isAttachedToWindow() && v.isShown() && v.getWidth() > 0
                && v.getHeight() > 0 && v.getRootView() == root;
    }

    private static boolean descendantOf(View child, View ancestor) {
        for (View v = child; v != null;) {
            if (v == ancestor) return true;
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return false;
    }

    private static View rowBackground(View row) {
        for (String method : new String[] {"getBackgroundNormal"}) {
            try {
                Method m = row.getClass().getMethod(method);
                Object found = m.invoke(row);
                View background = backgroundOnly(found);
                if (background != null) return background;
            } catch (Throwable ignored) {}
        }
        for (Class<?> c = row.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("mBackgroundNormal");
                f.setAccessible(true);
                Object found = f.get(row);
                View background = backgroundOnly(found);
                if (background != null) return background;
            } catch (Throwable ignored) {}
        }
        int id = row.getResources().getIdentifier("backgroundNormal", "id", "com.android.systemui");
        if (id != 0) {
            View found = row.findViewById(id);
            View background = backgroundOnly(found);
            if (background != null) return background;
        }
        return findBackground(row, 0);
    }

    private static View backgroundOnly(Object candidate) {
        if (!(candidate instanceof View)) return null;
        View view = (View) candidate;
        return view.isShown() && (!(view instanceof ViewGroup)
                || ((ViewGroup) view).getChildCount() == 0) ? view : null;
    }

    private static View findBackground(View view, int depth) {
        if (view.getClass().getName().contains("NotificationBackgroundView") && view.isShown())
            return view;
        if (!(view instanceof ViewGroup) || depth >= 7) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findBackground(group.getChildAt(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static final class Slot {
        private WeakReference<View> target = new WeakReference<>(null);
        private CardDrawable drawable;
        private boolean failed;
        private long nextSearchAt;
        private final Matrix toScreen = new Matrix();
        private final Matrix flowToScreen = new Matrix();
        private final Matrix screenToFlow = new Matrix();
        private final float[] basisPoints = new float[6];

        View target() { return target.get(); }

        void bind(View bg) {
            if (target() == bg) return;
            detach();
            if (failed) return;
            try {
                drawable = new CardDrawable();
                bg.getOverlay().add(drawable);
                target = new WeakReference<>(bg);
            } catch (Throwable t) {
                failed = true;
                Xp.log(TAG + "card shader unavailable: " + t);
            }
        }

        void frame(CoverFlowRuntime source, KawarpFrame frame, float alpha,
                   float shade, float fallbackRadius) {
            View bg = target();
            if (bg == null || drawable == null) return;
            toScreen.reset();
            bg.transformMatrixToGlobal(toScreen);
            flowToScreen.reset();
            source.transformMatrixToGlobal(flowToScreen);
            if (!flowToScreen.invert(screenToFlow)) { detach(); return; }
            basisPoints[0] = 0f; basisPoints[1] = 0f;
            basisPoints[2] = 1f; basisPoints[3] = 0f;
            basisPoints[4] = 0f; basisPoints[5] = 1f;
            toScreen.mapPoints(basisPoints);
            screenToFlow.mapPoints(basisPoints);
            float[] map = FlowCardGeometry.fromMappedBasis(basisPoints);
            float radius = fallbackRadius;
            try {
                Outline outline = new Outline();
                bg.getOutlineProvider().getOutline(bg, outline);
                if (outline.getRadius() > 0f) radius = outline.getRadius();
            } catch (Throwable ignored) {}
            drawable.frame(frame, source.getWidth(), source.getHeight(), bg.getWidth(),
                    bg.getHeight(), map, radius, alpha, shade);
        }

        void detach() {
            View bg = target();
            if (bg != null && drawable != null) bg.getOverlay().remove(drawable);
            target = new WeakReference<>(null);
            drawable = null;
        }
    }

    private static final class CardDrawable extends Drawable {
        private final KawarpFrameRenderer renderer = new KawarpFrameRenderer();
        private KawarpFrame frame;
        private float[] map;
        private float flowWidth, flowHeight, radius, alpha, shade;
        private boolean failed;

        void frame(KawarpFrame next, float fw, float fh, int width, int height,
                   float[] basis, float corner, float strength, float lyricShade) {
            frame = next;
            flowWidth = fw;
            flowHeight = fh;
            map = basis;
            radius = Math.max(0f, corner);
            alpha = strength;
            shade = lyricShade;
            setBounds(0, 0, width, height);
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            if (failed || frame == null || map == null || !canvas.isHardwareAccelerated()) return;
            try {
                renderer.draw(canvas, frame, flowWidth, flowHeight, 0f, 0f,
                        getBounds().width(), getBounds().height(), map[0], map[1], map[2], map[3],
                        map[4], map[5], radius, alpha, shade);
            } catch (Throwable t) {
                failed = true;
                Xp.log(TAG + "card drawing unavailable; native material retained: " + t);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
