package com.os4.musiccover;

/** Small, platform-independent rules shared by the backdrop animation and its tests. */
final class CoverFlowScene {
    private CoverFlowScene() {}

    static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    static float opacity(float cardProgress, float lit, float readiness) {
        return clamp(cardProgress) * clamp(lit) * clamp(readiness);
    }

    static float cardOpacity(float flowOpacity, boolean eligible) {
        return eligible ? 0.65f * clamp(flowOpacity) : 0f;
    }

    static int lyricShade(float lyricShow) {
        return Math.round(255f * 0.4f * clamp(lyricShow));
    }

    /** The flow must precede both the keyguard subtree and any already attached discs. */
    static int layerIndex(int keyguardIndex, int... discIndices) {
        int index = Math.max(0, keyguardIndex);
        for (int disc : discIndices) if (disc >= 0) index = Math.min(index, disc);
        return index;
    }
}
