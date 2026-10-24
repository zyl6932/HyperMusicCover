package com.os4.musiccover.lyrics;

import org.junit.Test;

import static org.junit.Assert.*;

public class LyricNavigationColorTest {
    private static final int BLACK = LyricContrastState.BLACK;
    private static final int WHITE = LyricContrastState.WHITE;
    private static final int MONET = 0xff6c95dd;

    @Test public void hiddenNavigationRestoresCurrentPaletteEvenWithCachedMapSample() {
        LyricContrastState state = new LyricContrastState();
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(state.update(true, "map", 1L, BLACK), false, 0L);
        assertEquals(BLACK, color.apply(MONET));
        // Navigation may still be running and its last sample may remain cached.
        // Only actual presentation authorizes monochrome colors.
        color.update(state.update(false, "map", 1L, BLACK), true, 10L);
        color.update(state.update(false, "map", 1L, WHITE), true, 310L);
        for (int palette : new int[]{MONET, 0x7fdd955a, 0xff102030, WHITE}) {
            assertEquals(palette, color.apply(palette));
        }
        assertTrue(color.identity());
        assertEquals(WHITE, state.update(true, "map", 1L, WHITE));
    }

    @Test public void hiddenEntryHasNoColoredFirstFrame() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(0, false, 0L);
        color.update(BLACK, false, 10L);
        assertEquals(BLACK, color.apply(MONET));
        assertFalse(color.moving());
        color.update(BLACK, true, 16L);
        assertEquals(BLACK, color.apply(MONET));
    }

    @Test public void visibleEntryInterpolatesEveryRoleToTheSameInk() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(0, true, 0L);
        assertTrue(color.update(BLACK, true, 10L));
        assertEquals(MONET, color.apply(MONET));
        assertTrue(color.moving());
        color.update(BLACK, true, 160L);
        assertEquals(0xff364b6f, color.apply(MONET));
        assertEquals(0xff808080, color.apply(WHITE));
        color.update(BLACK, true, 310L);
        for (int role : new int[] {MONET, WHITE, 0xffdd955a, 0xffe0e4e9}) {
            assertEquals(BLACK, color.apply(role));
        }
        assertFalse(color.moving());
    }

    @Test public void navigationEndingSmoothlyRestoresEachSavedColor() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(WHITE, false, 0L);
        color.update(0, true, 10L);
        assertEquals(WHITE, color.apply(MONET));
        color.update(0, true, 160L);
        assertNotEquals(WHITE, color.apply(MONET));
        assertNotEquals(MONET, color.apply(MONET));
        color.update(0, true, 310L);
        assertEquals(MONET, color.apply(MONET));
        assertEquals(0xffdd955a, color.apply(0xffdd955a));
        assertTrue(color.identity());
        assertFalse(color.moving());
    }

    @Test public void rapidReversalKeepsTheCurrentlyDrawnColorAndLastTargetWins() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(0, false, 0L);
        color.update(BLACK, true, 10L);
        color.update(BLACK, true, 120L);
        int before = color.apply(MONET);
        color.update(0, true, 120L);
        assertEquals(before, color.apply(MONET));
        color.update(0, true, 190L);
        before = color.apply(MONET);
        color.update(WHITE, true, 190L);
        assertEquals(before, color.apply(MONET));
        color.update(WHITE, true, 490L);
        assertEquals(WHITE, color.apply(MONET));
        assertFalse(color.moving());
        color.update(WHITE, true, 5000L);
        assertEquals(WHITE, color.apply(MONET));
    }

    @Test public void lateSampleAndSceneSwitchBothAnimateFromTheVisibleInk() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(WHITE, false, 0L);
        color.update(BLACK, true, 100L);
        assertEquals(WHITE, color.apply(MONET));
        color.update(BLACK, true, 250L);
        assertEquals(0xff808080, color.apply(MONET));
        color.update(BLACK, true, 400L);
        assertEquals(BLACK, color.apply(MONET));
        color.update(WHITE, true, 500L);
        color.update(WHITE, true, 800L);
        assertEquals(WHITE, color.apply(MONET));
    }

    @Test public void bitmapMatrixMatchesTextAndPreservesEveryRolesAlpha() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(0, false, 0L);
        color.update(WHITE, true, 100L);
        color.update(WHITE, true, 231L);
        float[] matrix = new float[20];
        color.matrix(matrix);
        for (int role : new int[] {MONET, 0x7fdd955a, 0x28e0e4e9, 0x00000000}) {
            int actual = color.apply(role);
            assertEquals(role >>> 24, actual >>> 24);
            assertEquals(Math.round(((role >>> 16) & 255) * matrix[0] + matrix[4]),
                    (actual >>> 16) & 255);
            assertEquals(Math.round(((role >>> 8) & 255) * matrix[6] + matrix[9]),
                    (actual >>> 8) & 255);
            assertEquals(Math.round((role & 255) * matrix[12] + matrix[14]), actual & 255);
        }
        assertEquals(1f, matrix[18], 0f);
        assertEquals(0f, matrix[19], 0f);
    }

    @Test public void pauseDoesNotAffectVsyncTimeAndStoppedFramesCatchUp() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(0, false, 0L);
        color.update(BLACK, true, 10L);
        assertTrue(color.moving());
        assertTrue(color.update(BLACK, true, 1000L));
        assertEquals(BLACK, color.apply(MONET));
        assertFalse(color.moving());
        assertFalse(color.update(BLACK, true, 1001L));
    }

    @Test public void hidingOrDetachingDoesNotRetainAnOldTransition() {
        LyricNavigationColor color = new LyricNavigationColor();
        color.update(0, false, 0L);
        color.update(BLACK, true, 10L);
        color.update(BLACK, true, 100L);
        color.update(WHITE, false, 100L);
        assertEquals(WHITE, color.apply(MONET));
        assertFalse(color.moving());
        color.clear();
        assertTrue(color.identity());
        color.update(BLACK, true, 200L);
        assertEquals(BLACK, color.apply(MONET));
        assertFalse(color.moving());
    }
}
