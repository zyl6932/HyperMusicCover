package com.os4.musiccover;

/** Affine map from a card's local pixels to the full-screen flow's pixels. */
final class FlowCardGeometry {
    private FlowCardGeometry() {}

    static float[] fromMappedBasis(float[] p) {
        if (p == null || p.length < 6) throw new IllegalArgumentException("three points required");
        return new float[] {
                p[2] - p[0], p[4] - p[0],
                p[3] - p[1], p[5] - p[1],
                p[0], p[1]
        };
    }

    static float[] map(float[] basis, float x, float y) {
        return new float[] {
                basis[0] * x + basis[1] * y + basis[4],
                basis[2] * x + basis[3] * y + basis[5]
        };
    }
}
