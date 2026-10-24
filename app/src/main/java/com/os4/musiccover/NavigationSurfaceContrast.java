package com.os4.musiccover;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.SurfaceControl;
import com.os4.musiccover.immersive.ImmersiveHost;
import com.os4.musiccover.immersive.LiveAlertScene;
import com.os4.musiccover.lyrics.LockLyrics;
import com.os4.musiccover.lyrics.NavigationPixelContrast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ObjIntConsumer;

/** In-memory, low-resolution sampling of the map's own remote layer subtree, never the display. */
final class NavigationSurfaceContrast {
    private NavigationSurfaceContrast() {}
    private static final long INTERVAL_MS = 1000L, TIMEOUT_MS = 2500L, API_RETRY_MS = 30000L;
    private static final long ENTRY_RETRY_MS = 100L, ENTRY_WINDOW_MS = 2000L, CONFIRM_MS = 250L;
    private static final NavigationPixelContrast.Stability STABILITY = new NavigationPixelContrast.Stability();
    private static String sScene, sSurfaceKey = "pending", sLastDiagnostic;
    private static long sGeneration, sContrastEpoch, sNextRead, sEnteredAt;
    private static boolean sPollPosted, sLookupPending;
    private static long sLookupRetryAt;
    private static Request sPending;
    private static final NavigationCaptureSlots<Request> SLOTS = new NavigationCaptureSlots<>();
    private static LyricContrastBackground.Reading sReading = unknown("navigation:unknown");
    private static volatile NavigationCaptureApi sApi;

    static LyricContrastBackground.Reading current(String scene) {
        if (!scene.equals(sScene)) {
            stop();
            sScene = scene;
            sEnteredAt = SystemClock.uptimeMillis();
            sReading = unknown(key());
        }
        pollSoon();
        return sReading;
    }

    static void stop() {
        if (sScene == null && !sPollPosted) return;
        sScene = null;
        sGeneration++;
        if (sPending != null) sPending.expired = true;
        sSurfaceKey = "pending";
        sContrastEpoch = 0L;
        sNextRead = 0L;
        STABILITY.reset();
        Main.main().removeCallbacks(POLL);
        sPollPosted = false;
        // Outstanding native captures retain their callbacks, including across a page close.
        // Timeout allows one bounded replacement; a late callback still closes its buffer.
    }

    private static boolean wanted() {
        return sScene != null && Main.screenOnCached() && LockLyrics.wantsAttached()
                && !LockLyrics.lockScreenGone()
                && sScene.equals(ImmersiveHost.requestedNavigationKey());
    }

    private static final Runnable POLL = () -> {
        sPollPosted = false;
        try {
            if (!wanted()) { stop(); return; }
            readDue();
        } catch (Throwable error) { fail("sample-host:" + describe(error)); }
        pollSoon();
    };

    private static void pollSoon() {
        try {
            if (sPollPosted || !wanted()) return;
            sPollPosted = Main.main().postDelayed(POLL,
                    Math.max(0L, sNextRead - SystemClock.uptimeMillis()));
        } catch (Throwable ignored) {}
    }

    private static String key() { return "navigation:" + sScene + ":" + sSurfaceKey + ":" + sContrastEpoch; }
    private static LyricContrastBackground.Reading unknown(String key) {
        return new LyricContrastBackground.Reading(key, 0, "navigation-surface-unknown");
    }

    private static void readDue() {
        long now = SystemClock.uptimeMillis();
        sNextRead = now + INTERVAL_MS;
        if (!ensureApi()) return;
        LiveAlertScene.ContrastSurface target = ImmersiveHost.presentedNavigationSurface();
        if (target == null) {
            // The first poll often lands during the map's entry fade. Checking readiness is
            // cheap and captures no pixels; do not make a ready map wait a full steady poll.
            if (now - sEnteredAt < ENTRY_WINDOW_MS) sNextRead = now + ENTRY_RETRY_MS;
            STABILITY.accept(null);
            diagnostic("surface-not-ready");
            return;
        }
        String surfaceKey = target.generation + ":" + target.width + "x" + target.height;
        if (!surfaceKey.equals(sSurfaceKey)) {
            sSurfaceKey = surfaceKey;
            sGeneration++;
            if (sPending != null) sPending.expired = true;
            sContrastEpoch = 0L;
            STABILITY.reset();
            sReading = unknown(key());
        }
        if (sPending != null) return;
        if (SLOTS.size() >= 2) { diagnostic("capture-waiting-for-late-callback limit=2"); return; }
        SurfaceControl copy = null;
        try {
            // Copy on the main thread while the scene still owns its handle. The worker never
            // races release() against a borrowed Java SurfaceControl's native pointer.
            copy = (SurfaceControl) sApi.copy.newInstance(target.control, "MCLyricContrast");
            Request request = new Request(sScene, sGeneration, target, copy);
            if (!SLOTS.acquire(request)) { release(copy); return; }
            sPending = request;
            // Install the timeout before handing the owned control to another thread. Once
            // post succeeds only that worker releases it, even if later host work fails.
            Main.main().postDelayed(request.timeout, TIMEOUT_MS);
            if (!Main.worker().post(() -> startCapture(request))) {
                sPending = null;
                SLOTS.finish(request);
                Main.main().removeCallbacks(request.timeout);
                release(copy);
                fail("worker-unavailable");
                return;
            }
        } catch (Throwable error) {
            if (sPending != null && sPending.control == copy) {
                Main.main().removeCallbacks(sPending.timeout);
                SLOTS.finish(sPending);
                sPending = null;
            }
            release(copy);
            fail("surface-copy:" + describe(error));
        }
    }

    /** Hidden member discovery can take tens of ms; keep it off all animation/drawing frames. */
    private static boolean ensureApi() {
        if (sApi != null) return true;
        if (sLookupPending || SystemClock.uptimeMillis() < sLookupRetryAt) return false;
        sLookupPending = true;
        try {
            if (Main.worker().post(() -> {
                NavigationCaptureApi found = null;
                String error = null;
                try {
                    found = NavigationCaptureApi.discover(Class::forName, MEMBERS,
                            SurfaceControl.class, Rect.class);
                    LiveAlertScene.prepareContrastSurfaceAccess();
                } catch (Throwable failure) { error = describe(failure); }
                final NavigationCaptureApi result = found;
                final String failed = error;
                Main.main().post(() -> {
                    sLookupPending = false;
                    sLookupRetryAt = SystemClock.uptimeMillis() + API_RETRY_MS;
                    if (failed != null) fail("api-lookup:" + failed);
                    else {
                        sApi = result;
                        diagnostic("api-ready=" + result.name + " secure="
                                + (result.contentPolicies ? "throw-on-sensitive" : "excluded"));
                        // First entry should not spend another normal sampling interval in
                        // provisional white after reflection has already completed.
                        Main.main().removeCallbacks(POLL);
                        sPollPosted = false;
                    }
                    sNextRead = SystemClock.uptimeMillis();
                    pollSoon();
                });
            })) return false;
        } catch (Throwable failure) { fail("api-worker:" + describe(failure)); }
        sLookupPending = false;
        sLookupRetryAt = SystemClock.uptimeMillis() + API_RETRY_MS;
        fail("api-worker-unavailable");
        return false;
    }

    private static final NavigationCaptureApi.Members MEMBERS = new NavigationCaptureApi.Members() {
        @Override public Constructor<?> constructor(Class<?> type, Class<?>... parameters)
                throws ReflectiveOperationException {
            Constructor<?> found;
            try { found = type.getConstructor(parameters); }
            catch (NoSuchMethodException hidden) {
                found = org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredConstructor(type, parameters);
            }
            found.setAccessible(true);
            return found;
        }
        @Override public Method method(Class<?> type, String name, Class<?>... parameters)
                throws ReflectiveOperationException {
            try {
                Method found = type.getMethod(name, parameters);
                found.setAccessible(true);
                return found;
            } catch (NoSuchMethodException hidden) {
                // Builder setters live in CaptureArgs.Builder, not LayerCaptureArgs.Builder.
                for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                    try {
                        Method found = org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(
                                owner, name, parameters);
                        found.setAccessible(true);
                        return found;
                    } catch (NoSuchMethodException absent) {}
                }
                throw hidden;
            }
        }
    };

    private static final class Request {
        final String scene;
        final long generation, surfaceGeneration;
        final int width, height;
        final SurfaceControl control;
        final AtomicBoolean callbackSeen = new AtomicBoolean();
        final long started = SystemClock.uptimeMillis();
        volatile boolean expired;
        volatile String area = "pending", stage = "queued", bufferSize = "none";
        volatile String apiName = "pending";
        Object listener; // Keep the native listener and its weakly-held consumer alive.
        ObjIntConsumer<Object> consumer;
        final Runnable timeout;
        Request(String scene, long generation, LiveAlertScene.ContrastSurface target, SurfaceControl control) {
            this.scene = scene;
            this.generation = generation;
            surfaceGeneration = target.generation;
            width = target.width;
            height = target.height;
            this.control = control;
            timeout = () -> {
                if (sPending != this) return;
                expired = true;
                sPending = null;
                if (generation == sGeneration) {
                    STABILITY.accept(null);
                    fail("capture-timeout " + detail());
                }
            };
        }
        String detail() {
            return "api=" + apiName + " stage=" + stage + " viewport=" + width + "x" + height
                    + " area=" + area + " buffer=" + bufferSize
                    + " elapsedMs=" + (SystemClock.uptimeMillis() - started);
        }
    }

    private static void startCapture(Request request) {
        try {
            if (request.expired) { complete(request, null, "capture-obsolete"); return; }
            if (!request.control.isValid()) { complete(request, null, "surface-invalid"); return; }
            NavigationCaptureApi api = sApi;
            request.apiName = api.name;
            request.stage = "build-args";
            Rect area = new Rect(Math.round(request.width * 0.08f), Math.round(request.height * 0.25f),
                    Math.round(request.width * 0.92f), Math.round(request.height * 0.80f));
            request.area = area.left + "," + area.top + ":" + area.width() + "x" + area.height();
            if (area.isEmpty()) { complete(request, null, "surface-empty"); return; }
            Object builder = api.builder.newInstance(request.control);
            api.crop.invoke(builder, area);
            // Downsample inside SurfaceFlinger. No full-resolution bitmap is ever requested.
            api.scale.invoke(builder, Math.min(1f, 48f / area.width()), Math.min(1f, 64f / area.height()));
            api.children.invoke(builder, false);
            api.excludeSensitiveContent(builder);
            request.consumer = (captured, status) -> {
                if (!request.callbackSeen.compareAndSet(false, true)) { closeBuffer(api, captured); return; }
                try {
                    if (Main.worker().post(() -> consume(api, request, captured, status))) return;
                } catch (Throwable ignored) {}
                closeBuffer(api, captured);
                complete(request, null, "worker-unavailable");
            };
            request.listener = api.listener.newInstance(request.consumer);
            request.stage = "captureLayers";
            int status = ((Number) api.capture.invoke(null, api.build.invoke(builder), request.listener)).intValue();
            if (status != 0) complete(request, null, "capture-status:" + status);
        } catch (Throwable error) {
            complete(request, null, "capture-api:" + describe(error));
        } finally {
            release(request.control);
        }
    }

    private static void consume(NavigationCaptureApi api, Request request, Object captured, int status) {
        Bitmap hardware = null, software = null;
        NavigationPixelContrast.Counts counts = null;
        String error = null;
        request.stage = "consume-buffer";
        try {
            if (request.expired || status != 0 || captured == null) {
                error = request.expired ? "capture-late" : "capture-status:" + status;
            } else if (Boolean.TRUE.equals(api.containsSecure.invoke(captured))) {
                error = "secure-layers-redacted";
            } else {
                HardwareBuffer buffer = (HardwareBuffer) api.buffer.invoke(captured);
                request.bufferSize = buffer == null || buffer.isClosed() ? "unavailable"
                        : buffer.getWidth() + "x" + buffer.getHeight();
                if (buffer == null || buffer.isClosed() || buffer.getWidth() > 96 || buffer.getHeight() > 96
                        || (long) buffer.getWidth() * buffer.getHeight() > 8192) {
                    error = "buffer-size-invalid";
                } else {
                    hardware = (Bitmap) api.bitmap.invoke(captured);
                    software = hardware == null ? null : hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (software != null) {
                        int w = software.getWidth(), h = software.getHeight();
                        int[] pixels = new int[w * h];
                        software.getPixels(pixels, 0, w, 0, 0, w, h);
                        counts = NavigationPixelContrast.count(pixels);
                        if (!counts.usable()) error = "pixels-unavailable";
                    } else error = "bitmap-unavailable";
                }
            }
        } catch (Throwable failure) {
            error = "buffer-read:" + describe(failure);
        } finally {
            recycle(software);
            recycle(hardware);
            closeBuffer(api, captured);
        }
        complete(request, counts, error);
    }

    private static void closeBuffer(NavigationCaptureApi api, Object captured) {
        if (captured == null) return;
        try {
            HardwareBuffer buffer = (HardwareBuffer) api.buffer.invoke(captured);
            if (buffer != null && !buffer.isClosed()) buffer.close();
        } catch (Throwable ignored) {}
    }

    private static void recycle(Bitmap bitmap) {
        try { if (bitmap != null) bitmap.recycle(); } catch (Throwable ignored) {}
    }
    private static void release(SurfaceControl control) {
        try { if (control != null) control.release(); } catch (Throwable ignored) {}
    }

    private static void complete(Request request, NavigationPixelContrast.Counts counts,
                                 String error) {
        try {
            Main.main().post(() -> publish(request, counts, error));
        } catch (Throwable ignored) {}
    }

    private static void publish(Request request, NavigationPixelContrast.Counts counts,
                                String error) {
        try {
            if (!SLOTS.finish(request)) return;
            if (sPending == request) sPending = null;
            Main.main().removeCallbacks(request.timeout);
            if (request.expired || request.generation != sGeneration
                    || !request.scene.equals(sScene) || !wanted()) return;
            LiveAlertScene.ContrastSurface current = ImmersiveHost.presentedNavigationSurface();
            if (current == null || current.generation != request.surfaceGeneration
                    || current.width != request.width || current.height != request.height) return;
            if (error != null || counts == null) {
                STABILITY.accept(null);
                fail((error == null ? "capture-empty" : error) + " " + request.detail());
                return;
            }
            int before = STABILITY.color();
            int color = STABILITY.accept(counts);
            if (before != 0 && color != before) sContrastEpoch++;
            LyricContrastBackground.Reading previous = sReading;
            sReading = new LyricContrastBackground.Reading(key(), color,
                    color == 0 ? "navigation-surface-unknown" : "navigation-surface-ratio");
            diagnostic("pixels " + counts.bright + " bright/" + counts.dark + " dark/"
                    + counts.total + " total -> " + (color == 0 ? "unknown" : color == 0xff000000 ? "black" : "white")
                    + " " + request.detail());
            if (STABILITY.needsConfirmation()) {
                // Keep the two-sample 60% rule, but promptly verify an actual map day/night
                // change instead of waiting another whole idle sampling interval.
                sNextRead = SystemClock.uptimeMillis() + CONFIRM_MS;
                Main.main().removeCallbacks(POLL);
                sPollPosted = false;
                pollSoon();
            }
            if (previous.color != sReading.color || !previous.key.equals(sReading.key)
                    || !previous.source.equals(sReading.source)) LockLyrics.refresh();
        } catch (Throwable failure) {
            fail("sample-publish:" + describe(failure));
        }
    }

    private static void fail(String reason) {
        sReading = unknown(key());
        diagnostic(reason);
    }
    private static void diagnostic(String message) {
        // Only state/color transitions, not every map frame. Never include pixels or map text.
        String state = sGeneration + ":" + (message.startsWith("pixels ") ? sReading.color
                : message.replaceAll(" elapsedMs=\\d+", ""));
        if (state.equals(sLastDiagnostic)) return;
        sLastDiagnostic = state;
        Xp.log("MCLyricContrast: sdk=" + Build.VERSION.SDK_INT + " source=map scene=" + sScene
                + " candidate=" + (sReading.color == 0 ? "unknown" : sReading.color == 0xff000000 ? "black" : "white")
                + " " + message + " " + ImmersiveHost.navigationContrastState());
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        for (int i = 0; i < 8 && root.getCause() != null; i++) root = root.getCause();
        String description = failure.toString();
        if (root != failure) description += " cause=" + root;
        for (Throwable suppressed : failure.getSuppressed()) description += " alternative=" + suppressed;
        return description.replace('\n', ' ').replace('\r', ' ');
    }
}
