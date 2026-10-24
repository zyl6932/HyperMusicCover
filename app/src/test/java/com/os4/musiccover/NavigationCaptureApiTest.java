package com.os4.musiccover;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ObjIntConsumer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Executable contracts model only the signatures used from AOSP Android 15/16 and 17. */
public class NavigationCaptureApiTest {
    public static class Surface { public Surface(Surface ignored, String name) {} }
    public static class Rect {}
    public static class Result {
        public Object getHardwareBuffer() { return null; }
        public Object asBitmap() { return null; }
        public boolean containsSecureLayers() { return true; }
    }
    public static class Listener {
        public Listener(ObjIntConsumer<Object> consumer) {}
    }
    public static class CommonBuilder {
        public CommonBuilder setSourceCrop(Rect crop) { return this; }
        public CommonBuilder setFrameScale(float x, float y) { return this; }
        public CommonBuilder setChildrenOnly(boolean childrenOnly) { return this; }
    }
    public static class LegacyArgs {
        public static class Builder extends CommonBuilder {
            boolean secure = true, protectedContent = true;
            public Builder(Surface layer) {}
            public Builder setCaptureSecureLayers(boolean value) { secure = value; return this; }
            public Builder setAllowProtected(boolean value) { protectedContent = value; return this; }
            public LegacyArgs build() { return new LegacyArgs(); }
        }
    }
    public static class ModernArgs {
        public static class Builder extends CommonBuilder {
            int secure = -1, protectedContent = -1;
            public Builder(Surface layer) {}
            public Builder setSecureContentPolicy(int value) { secure = value; return this; }
            public Builder setProtectedContentPolicy(int value) { protectedContent = value; return this; }
            public ModernArgs build() { return new ModernArgs(); }
        }
    }
    public static class Legacy {
        public static int captureLayers(LegacyArgs args, Listener listener) { return 0; }
    }
    public static class Modern {
        public static int captureLayers(ModernArgs args, Listener listener) { return 0; }
    }
    public static class WrongStatic {
        public int captureLayers(ModernArgs args, Listener listener) { return 0; }
    }
    public static class DisplayOnly {
        public static void capture(Object params, Object executor, Object receiver) {
            fail("The Android 17 whole-display API must never be used for map contrast");
        }
    }

    private static final NavigationCaptureApi.Members MEMBERS = new NavigationCaptureApi.Members() {
        public Constructor<?> constructor(Class<?> owner, Class<?>... params) throws ReflectiveOperationException {
            return owner.getConstructor(params);
        }
        public Method method(Class<?> owner, String name, Class<?>... params) throws ReflectiveOperationException {
            return owner.getMethod(name, params);
        }
    };
    private static final String LEGACY = "android.window.ScreenCapture";
    private static final String MODERN = "android.window.ScreenCaptureInternal";

    private static void install(Map<String, Class<?>> classes, String name, Class<?> root, boolean modern) {
        classes.put(name, root);
        classes.put(name + "$LayerCaptureArgs", modern ? ModernArgs.class : LegacyArgs.class);
        classes.put(name + "$LayerCaptureArgs$Builder", modern ? ModernArgs.Builder.class : LegacyArgs.Builder.class);
        classes.put(name + "$ScreenCaptureListener", Listener.class);
        classes.put(name + "$ScreenshotHardwareBuffer", Result.class);
    }
    private static NavigationCaptureApi discover(Map<String, Class<?>> classes) throws ReflectiveOperationException {
        return NavigationCaptureApi.discover(name -> {
            Class<?> found = classes.get(name);
            if (found == null) throw new ClassNotFoundException(name);
            return found;
        }, MEMBERS, Surface.class, Rect.class);
    }

    @Test public void android17UsesInternalLayerCaptureAndRejectsSensitivePixels() throws Exception {
        Map<String, Class<?>> classes = new HashMap<>();
        classes.put(LEGACY, DisplayOnly.class);
        install(classes, MODERN, Modern.class, true);
        NavigationCaptureApi api = discover(classes);
        assertEquals(MODERN, api.name);
        assertTrue(api.contentPolicies);
        ModernArgs.Builder builder = (ModernArgs.Builder) api.builder.newInstance((Object) null);
        api.excludeSensitiveContent(builder);
        assertEquals(2, builder.secure);
        assertEquals(2, builder.protectedContent);
        assertEquals(0, api.capture.invoke(null, api.build.invoke(builder), new Listener((v, s) -> {})));
        assertEquals(true, api.containsSecure.invoke(new Result()));
    }

    @Test public void android15And16KeepBooleanExclusionAndInheritedBuilderSetters() throws Exception {
        Map<String, Class<?>> classes = new HashMap<>();
        install(classes, LEGACY, Legacy.class, false);
        NavigationCaptureApi api = discover(classes);
        assertEquals(LEGACY, api.name);
        assertFalse(api.contentPolicies);
        LegacyArgs.Builder builder = (LegacyArgs.Builder) api.builder.newInstance((Object) null);
        api.excludeSensitiveContent(builder);
        assertFalse(builder.secure);
        assertFalse(builder.protectedContent);
        assertEquals(CommonBuilder.class, api.scale.getDeclaringClass());
    }

    @Test public void wholeDisplayOnlyApiReportsMissingLayerClassInsteadOfCapturingDisplay() throws Exception {
        Map<String, Class<?>> classes = new HashMap<>();
        classes.put(LEGACY, DisplayOnly.class);
        try {
            discover(classes);
            fail("No supported map-layer API");
        } catch (ReflectiveOperationException expected) {
            assertTrue(expected.toString().contains(MODERN));
            assertTrue(expected.getSuppressed()[0].toString().contains("$LayerCaptureArgs"));
        }
    }

    @Test public void wrongStaticContractFallsBackToCompleteLegacyContract() throws Exception {
        Map<String, Class<?>> classes = new HashMap<>();
        install(classes, MODERN, WrongStatic.class, true);
        install(classes, LEGACY, Legacy.class, false);
        assertEquals(LEGACY, discover(classes).name);
    }

    @Test public void missingInternalClassDoesNotPermanentlyDisableLaterDiscovery() throws Exception {
        Map<String, Class<?>> classes = new HashMap<>();
        try { discover(classes); fail("Initially unavailable"); }
        catch (ReflectiveOperationException expected) {}
        install(classes, MODERN, Modern.class, true);
        assertEquals(MODERN, discover(classes).name);
    }
}
