package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class CoverCardStyleTest {
    @Test public void oldSettingsKeepFullCoverAndDefaults() {
        CoverCardStyle style = CoverCardStyle.defaults();
        assertEquals(CoverCardStyle.FULL, style.mode);
        assertEquals(0.8f, style.fill, 0f);
        assertEquals(0.5f, style.pos, 0f);
        assertEquals(0.12f, style.corner, 0f);
    }

    @Test public void invalidValuesAreFiniteAndBounded() {
        CoverCardStyle style = new CoverCardStyle(8, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
        assertEquals(CoverCardStyle.FULL, style.mode);
        assertEquals(0.8f, style.fill, 0f);
        assertEquals(0.5f, style.pos, 0f);
        assertEquals(0.12f, style.corner, 0f);
        style = new CoverCardStyle(CoverCardStyle.CARD, 999f, -10f, 5f);
        assertEquals(1f, style.fill, 0f);
        assertEquals(0f, style.pos, 0f);
        assertEquals(1f, style.corner, 0f);
        assertEquals(CoverCardStyle.MIN_FILL,
                new CoverCardStyle(CoverCardStyle.CARD, 0f, 0.5f, 0f).fill, 0f);
    }

    @Test public void aFullCardFillsTheRoomAndPositionHasNothingToMove() {
        for (float pos : new float[]{0f, 1f}) {
            CoverCardStyle style = new CoverCardStyle(CoverCardStyle.CARD, 1f, pos, 0.12f);
            CoverCardStyle.Rect r = style.place(400f, 850f, 1f, 190f, 640f);
            assertNotNull(r);
            // The width is the tighter side: 400 less a 16 gap each side.
            assertEquals(368f, r.side, 0.01f);
            assertEquals(16f, r.x, 0.01f);
            assertTrue(r.y >= 206f);
            assertTrue(r.y + r.side <= 624f);
        }
    }

    @Test public void positionSpansTheHeightASmallerCardLeaves() {
        CoverCardStyle top = new CoverCardStyle(CoverCardStyle.CARD, 0.5f, 0f, 0.12f);
        CoverCardStyle bottom = new CoverCardStyle(CoverCardStyle.CARD, 0.5f, 1f, 0.12f);
        CoverCardStyle.Rect a = top.place(400f, 850f, 1f, 190f, 640f);
        CoverCardStyle.Rect b = bottom.place(400f, 850f, 1f, 190f, 640f);
        assertEquals(184f, a.side, 0.01f);
        assertEquals(206f, a.y, 0.01f);
        assertEquals(624f, b.y + b.side, 0.01f);
    }

    @Test public void aSmallShareOfATightRoomIsHeldAtTheLeastSize() {
        CoverCardStyle style = new CoverCardStyle(CoverCardStyle.CARD, 0.4f, 0.5f, 0.12f);
        CoverCardStyle.Rect r = style.place(400f, 850f, 1f, 400f, 560f);
        assertNotNull(r);
        assertEquals(96f, r.side, 0.01f);
    }

    @Test public void crampedBandHidesCard() {
        CoverCardStyle style = new CoverCardStyle(CoverCardStyle.CARD, 0.8f, 0.5f, 0.12f);
        assertNull(style.place(390f, 800f, 1f, 300f, 400f));
        assertNull(style.place(Float.NaN, 800f, 1f, 100f, 600f));
        assertNull(style.place(390f, 800f, 1f, 100f, Float.NaN));
    }

    @Test public void cornerIsAShareOfTheSide() {
        assertEquals(18f, new CoverCardStyle(CoverCardStyle.CARD, 1f, 0.5f, 0.12f)
                .radius(300f), 0.01f);
        assertEquals(150f, new CoverCardStyle(CoverCardStyle.CARD, 1f, 0.5f, 1f)
                .radius(300f), 0.01f);
        assertEquals(0f, new CoverCardStyle(CoverCardStyle.CARD, 1f, 0.5f, 0f)
                .radius(300f), 0f);
    }

    @Test public void onlyTheOldSliderTopTranslates() {
        assertEquals(1f, CoverCardStyle.fillFromLegacySizeDp(420f), 0f);
        assertEquals(CoverCardStyle.DEFAULT_FILL, CoverCardStyle.fillFromLegacySizeDp(240f), 0f);
    }
}
