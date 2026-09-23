package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class LyricStyleTest {
    private static float[] band(LyricStyle s, float clock, float card, float textPx) {
        float[] out = new float[2];
        return s.writeBand(clock, card, 1f, textPx, out) ? out : null;
    }

    @Test public void defaultsPreserveTheOriginalBandAndTextWidth() {
        LyricStyle s = LyricStyle.DEFAULT;
        assertEquals(25f, s.sizeSp, 0f);
        assertEquals(600, s.weight);
        assertArrayEquals(new float[]{116f, 484f}, band(s, 100f, 500f, 25f), 0f);
        assertEquals(30f, s.sidePx(360, 360, 1f, 25f), 0f);
    }

    @Test public void aSmallerBandMovesWithoutShrinking() {
        LyricStyle half = LyricStyle.DEFAULT.with("fill", 0.5f);
        assertArrayEquals(new float[]{116f, 300f},
                band(half.with("pos", 0f), 100f, 500f, 25f), 0.01f);
        assertArrayEquals(new float[]{208f, 392f}, band(half, 100f, 500f, 25f), 0.01f);
        assertArrayEquals(new float[]{300f, 484f},
                band(half.with("pos", 1f), 100f, 500f, 25f), 0.01f);
    }

    @Test public void aFullBandHasNothingToMove() {
        LyricStyle s = LyricStyle.DEFAULT;
        assertArrayEquals(band(s, 100f, 500f, 25f),
                band(s.with("pos", 1f), 100f, 500f, 25f), 0f);
    }

    @Test public void compactBandsAndNarrowScreensStayInsideTheirBounds() {
        LyricStyle s = LyricStyle.DEFAULT.with("fill", 0.4f).with("pos", 1f)
                .with("side", 64f).with("size", 36f);
        float[] bounds = band(s, 100f, 200f, 36f);
        assertNotNull(bounds);
        assertTrue(bounds[0] >= 100f);
        assertTrue(bounds[1] <= 200f);
        assertTrue(bounds[1] - bounds[0] >= 2.4f * 36f - 0.01f);
        assertNull(band(s, 100f, 180f, 36f));
        assertEquals(0f, s.sidePx(100, 100, 1f, 36f), 0f);
        assertEquals(48f, s.sidePx(240, 240, 1f, 36f), 0f);
    }

    @Test public void aWideScreenCentresAColumnAsWideAsTheShortSide() {
        LyricStyle s = LyricStyle.DEFAULT;
        // 1000 wide, 400 short: the column is 400 in the middle, the 30 margin inside it.
        assertEquals(330f, s.sidePx(1000, 400, 1f, 25f), 0f);
        // No short side known: the whole width, as before.
        assertEquals(30f, s.sidePx(1000, 0, 1f, 25f), 0f);
    }

    @Test public void invalidAndOutOfRangeSettingsCannotEscapeTheSupportedRange() {
        LyricStyle s = LyricStyle.DEFAULT;
        assertSame(s, s.with("size", Float.NaN));
        assertSame(s, s.with("pos", Float.POSITIVE_INFINITY));
        assertSame(s, s.with("unknown", 42f));
        assertSame(s, s.with("offset", 20f));
        assertEquals(36f, s.with("size", 100f).sizeSp, 0f);
        assertEquals(LyricStyle.MIN_FILL, s.with("fill", -1f).fill, 0f);
        assertEquals(1f, s.with("pos", 5f).pos, 0f);
        assertEquals(700, s.with("weight", 9999f).weight);
        assertEquals(500, s.with("weight", 549f).weight);
        assertNull(band(s, Float.NaN, 500f, 25f));
    }

    @Test public void onlyTypographyAndSideMarginRequireLayoutRebuild() {
        LyricStyle s = LyricStyle.DEFAULT;
        assertTrue(s.sameLayout(s.with("fill", 0.6f).with("pos", 0.2f)));
        assertFalse(s.sameLayout(s.with("side", 40f)));
        assertFalse(s.sameLayout(s.with("size", 30f)));
        assertFalse(s.sameLayout(s.with("weight", 700f)));
    }
}
