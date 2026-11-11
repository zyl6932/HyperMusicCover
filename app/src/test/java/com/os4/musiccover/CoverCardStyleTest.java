package com.os4.musiccover;

import com.os4.musiccover.cover.CoverCardStyle;
import org.junit.Test;

import static org.junit.Assert.*;

public class CoverCardStyleTest {
    @Test public void defaultsAreFullCoverFillingItsRoom() {
        CoverCardStyle style = CoverCardStyle.defaults();
        assertEquals(CoverCardStyle.FULL, style.mode);
        assertEquals(CoverCardStyle.FIXED_FILL, style.fill, 0f);
        assertEquals(CoverCardStyle.FIXED_POS, style.pos, 0f);
        // No override, so the corners come from the media card's own radius.
        assertTrue(Float.isNaN(style.corner));
    }

    @Test public void invalidValuesAreFiniteAndBounded() {
        CoverCardStyle style = new CoverCardStyle(8, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
        assertEquals(CoverCardStyle.FULL, style.mode);
        assertEquals(CoverCardStyle.FIXED_FILL, style.fill, 0f);
        assertEquals(CoverCardStyle.FIXED_POS, style.pos, 0f);
        // Non-finite is not an override either - same as not being set at all.
        assertTrue(Float.isNaN(style.corner));
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

    @Test public void anOverrideIsAShareOfTheSide() {
        assertEquals(18f, new CoverCardStyle(CoverCardStyle.CARD, 1f, 0.5f, 0.12f)
                .radius(300f), 0.01f);
        assertEquals(150f, new CoverCardStyle(CoverCardStyle.CARD, 1f, 0.5f, 1f)
                .radius(300f), 0.01f);
        assertEquals(0f, new CoverCardStyle(CoverCardStyle.CARD, 1f, 0.5f, 0f)
                .radius(300f), 0f);
    }

    @Test public void anUnmeasuredCardFallsBackToAShareOfTheSide() {
        CoverCardStyle style = CoverCardStyle.defaults();
        assertEquals(300f * 0.5f * CoverCardStyle.FALLBACK_CORNER, style.radius(300f), 0.01f);
        assertEquals(CoverCardStyle.FALLBACK_CORNER, style.cornerShare(300f), 0.001f);
    }

    /**
     * With no override the radius is the media card's, whatever size the square is - until the
     * square is small enough that the radius would take it past a circle.
     */
    @Test public void aMeasuredCardGivesEverySizeTheSameCorners() {
        CoverCardStyle.noteMediaCardRadius(60f);
        try {
            CoverCardStyle style = CoverCardStyle.defaults();
            // One radius, two sizes: the same corners on a bigger square are a smaller share.
            assertEquals(60f, style.radius(300f), 0.01f);
            assertEquals(60f, style.radius(600f), 0.01f);
            assertEquals(0.4f, style.cornerShare(300f), 0.001f);
            assertEquals(0.2f, style.cornerShare(600f), 0.001f);
            // A square small enough to be swallowed by it is a circle instead.
            assertEquals(15f, style.radius(30f), 0.01f);
            assertEquals(1f, style.cornerShare(30f), 0.001f);
            // An override still wins over the measurement.
            assertEquals(18f, new CoverCardStyle(CoverCardStyle.CARD, 1f, 0.5f, 0.12f)
                    .radius(300f), 0.01f);
        } finally {
            // The reading is a static on purpose - one card per screen - so a test that sets it
            // has to put it back for the others.
            CoverCardStyle.noteMediaCardRadius(0f);
        }
    }
}
