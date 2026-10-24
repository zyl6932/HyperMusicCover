package com.os4.musiccover;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.ObjIntConsumer;

/** Exact layer-capture contracts. Android 17 moved them out of ScreenCapture. */
final class NavigationCaptureApi {
    interface Classes { Class<?> find(String name) throws ClassNotFoundException; }
    interface Members {
        Constructor<?> constructor(Class<?> type, Class<?>... parameters) throws ReflectiveOperationException;
        Method method(Class<?> type, String name, Class<?>... parameters) throws ReflectiveOperationException;
    }

    final String name;
    final Constructor<?> copy, builder, listener;
    final Method crop, scale, children, secure, protectedContent, build, capture, buffer, bitmap, containsSecure;
    final boolean contentPolicies;

    static NavigationCaptureApi discover(Classes classes, Members members, Class<?> surface, Class<?> rect)
            throws ReflectiveOperationException {
        ReflectiveOperationException failed = null;
        // Probe capabilities instead of the SDK number: vendor builds can retain the old API.
        for (String name : new String[]{"android.window.ScreenCaptureInternal", "android.window.ScreenCapture"}) {
            try { return new NavigationCaptureApi(name, classes, members, surface, rect); }
            catch (ReflectiveOperationException error) {
                ReflectiveOperationException detail = new ReflectiveOperationException(name + ": " + error, error);
                if (failed == null) failed = detail;
                else failed.addSuppressed(detail);
            }
        }
        throw failed;
    }

    private NavigationCaptureApi(String name, Classes classes, Members members, Class<?> surface, Class<?> rect)
            throws ReflectiveOperationException {
        this.name = name;
        Class<?> root = classes.find(name);
        Class<?> args = classes.find(name + "$LayerCaptureArgs");
        Class<?> b = classes.find(name + "$LayerCaptureArgs$Builder");
        Class<?> l = classes.find(name + "$ScreenCaptureListener");
        Class<?> result = classes.find(name + "$ScreenshotHardwareBuffer");
        copy = members.constructor(surface, surface, String.class);
        builder = members.constructor(b, surface);
        listener = members.constructor(l, ObjIntConsumer.class);
        crop = instance(members, b, "setSourceCrop", rect);
        scale = instance(members, b, "setFrameScale", float.class, float.class);
        children = instance(members, b, "setChildrenOnly", boolean.class);
        Method securePolicy, protectedPolicy;
        boolean policies;
        try {
            securePolicy = instance(members, b, "setSecureContentPolicy", int.class);
            protectedPolicy = instance(members, b, "setProtectedContentPolicy", int.class);
            policies = true;
        } catch (NoSuchMethodException absent) {
            securePolicy = instance(members, b, "setCaptureSecureLayers", boolean.class);
            protectedPolicy = instance(members, b, "setAllowProtected", boolean.class);
            policies = false;
        }
        secure = securePolicy;
        protectedContent = protectedPolicy;
        contentPolicies = policies;
        build = instance(members, b, "build");
        capture = members.method(root, "captureLayers", args, l);
        if (!Modifier.isStatic(capture.getModifiers()) || capture.getReturnType() != int.class
                || build.getReturnType() != args)
            throw new NoSuchMethodException(name + ".captureLayers(LayerCaptureArgs, ScreenCaptureListener):int");
        buffer = instance(members, result, "getHardwareBuffer");
        bitmap = instance(members, result, "asBitmap");
        containsSecure = instance(members, result, "containsSecureLayers");
        if (containsSecure.getReturnType() != boolean.class)
            throw new NoSuchMethodException(name + ".ScreenshotHardwareBuffer.containsSecureLayers():boolean");
    }

    void excludeSensitiveContent(Object target) throws ReflectiveOperationException {
        if (contentPolicies) {
            // Android 17 ScreenCaptureParams.SECURE/PROTECTED_CONTENT_POLICY_THROW_EXCEPTION.
            // A failed sample is unknown; a redacted black rectangle is not a dark map.
            secure.invoke(target, 2);
            protectedContent.invoke(target, 2);
        } else {
            secure.invoke(target, false);
            protectedContent.invoke(target, false);
        }
    }

    private static Method instance(Members members, Class<?> type, String name, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = members.method(type, name, parameters);
        if (Modifier.isStatic(method.getModifiers()))
            throw new NoSuchMethodException(type.getName() + "." + name + " must be an instance method");
        return method;
    }
}
