package com.os4.musiccover;

/** An explicit music-page background deferred while navigation owns the lock-screen backdrop. */
final class NavigationLyricBackground {
    private String scene;

    void requestedOver(String navigationScene) {
        if (navigationScene != null) scene = navigationScene;
    }

    boolean pending() { return scene != null; }
    boolean belongsTo(String navigationScene) { return scene != null && scene.equals(navigationScene); }

    /** Consume once, including when current settings/session no longer allow a music background. */
    boolean finish(String navigationScene, boolean coverEnabled, boolean sessionAvailable) {
        if (!belongsTo(navigationScene)) return false;
        scene = null;
        return coverEnabled && sessionAvailable;
    }

    void cancel() { scene = null; }
}
