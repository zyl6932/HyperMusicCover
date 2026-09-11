package com.os4.musiccover;

public class NativeHelper {

    private static volatile boolean sLoaded = false;
    private static volatile Throwable sLoadError = null;

    static {
        try {
            System.loadLibrary("mcpatch");
            sLoaded = true;
            Xp.log("[MCNative] libmcpatch.so loaded successfully");
        } catch (Throwable t) {
            sLoadError = t;
            Xp.log("[MCNative] failed to load libmcpatch.so: " + t);
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static Throwable getLoadError() {
        return sLoadError;
    }

    public static native boolean patchFastPlayer(long baseAddr);

    public static native boolean mprotect(long addr, long len, int prot);
}
