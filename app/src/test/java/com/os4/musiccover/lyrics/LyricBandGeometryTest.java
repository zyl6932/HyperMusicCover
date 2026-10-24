package com.os4.musiccover.lyrics;

import org.junit.Test;

import static org.junit.Assert.*;

public class LyricBandGeometryTest {
    private static float add(float previous, boolean drawn, float top, float height) {
        return LyricBandGeometry.include(previous, drawn, 120f, top, 600f, height, 1080f, 2300f);
    }

    private static float[] band(float floor) {
        float[] band = new float[2];
        assertTrue(LyricStyle.DEFAULT.writeBand(600f, floor, 3f, 75f, band));
        return band;
    }

    private static float clip(float[] band, float layoutFloor, float floor) {
        return LyricBandGeometry.visibleBottom(band[0], band[1], layoutFloor, floor);
    }

    @Test public void wrappedRowTallerThanViewportRemainsReadableWithoutScaling() {
        assertEquals(1f, LyricBandGeometry.rowAlpha(-1000f, 2000f, 100f, 800f, 40f), 0f);
        assertEquals(0.5f, LyricBandGeometry.rowAlpha(780f, 2000f, 100f, 800f, 40f), 0f);
        assertEquals(0f, LyricBandGeometry.rowAlpha(800f, 2000f, 100f, 800f, 40f), 0f);
        assertEquals(0.5f, LyricBandGeometry.rowAlpha(120f, 300f, 100f, 800f, 40f), 0f);
    }

    @Test public void foldedCardDoesNotConsumeTheSpaceAboveTheCapsule() {
        float floor = add(Float.NaN, false, 1500f, 600f);
        floor = add(floor, true, 2160f, 140f);
        assertEquals(2160f, floor, 0f);
        float[] layout = band(1500f);
        float bottom = clip(layout, 1500f, floor);
        // Extra rows extend only below the original layout. Its centre is now above the
        // visible area's centre rather than moving down to centre the larger list.
        assertEquals(660f, bottom - layout[1], 0f);
        assertTrue((bottom - layout[1]) / 75f > 8f);
        assertTrue((layout[0] + layout[1]) / 2f < (layout[0] + bottom) / 2f);
        assertEquals(25f, LyricStyle.DEFAULT.sizeSp, 0f);
    }

    @Test public void risingMediaContainerOnlyClipsBottomWithoutRecenteringOrRescaling() {
        LyricBandGeometry.Reference reference = new LyricBandGeometry.Reference();
        float layoutFloor = reference.floor(1L, 2400f, 1500f);
        float[] layout = band(layoutFloor);
        float previous = clip(layout, layoutFloor, 2160f);
        for (float top : new float[]{2050f, 1900f, 1750f, 1600f, 1500f}) {
            float floor = add(add(Float.NaN, true, 2160f, 140f), true, top, 600f);
            assertEquals(top, floor, 0f);
            float stableFloor = reference.floor(1L, 2400f, top);
            float[] next = band(stableFloor);
            assertArrayEquals(layout, next, 0f);
            float now = clip(next, stableFloor, floor);
            assertTrue(now < previous);
            previous = now;
        }
        assertEquals(layout[1], previous, 0f);
    }

    @Test public void cancellingAReverseSwipeRestoresBottomRowsAtTheirOriginalPositions() {
        float[] layout = band(1500f);
        float expanded = clip(layout, 1500f, 2160f);
        float previous = clip(layout, 1500f, 1600f);
        for (float top : new float[]{1750f, 1900f, 2050f, 2160f}) {
            float now = clip(layout, 1500f, add(Float.NaN, true, top, 140f));
            assertTrue(now > previous);
            previous = now;
        }
        assertEquals(expanded, previous, 0f);
        assertArrayEquals(band(1500f), layout, 0f);
    }

    @Test public void referenceSurvivesRebuildAndHandoffButNewRequestCanChooseANewAnchor() {
        LyricBandGeometry.Reference reference = new LyricBandGeometry.Reference();
        assertEquals(1500f, reference.floor(7L, 2400f, 1500f), 0f);
        assertEquals(1500f, reference.floor(7L, 2400f, 1900f), 0f);
        // Recreated views ask the same request-owned reference; rotation scales that reference.
        assertEquals(750f, reference.floor(7L, 1200f, 1000f), 0f);
        assertEquals(1400f, reference.floor(8L, 2400f, 1400f), 0.001f);
    }

    @Test public void aFullyOccludingCardHidesRowsWithoutShrinkingTheLayout() {
        float[] layout = band(1500f);
        assertEquals(layout[0], clip(layout, 1500f, 400f), 0f);
        assertEquals(layout[1], clip(layout, 1500f, Float.NaN), 0f);
        assertArrayEquals(band(1500f), layout, 0f);
        LyricStyle style = LyricStyle.DEFAULT.with("fill", 0.5f).with("pos", 0.8f);
        float[] custom = new float[2];
        assertTrue(style.writeBand(600f, 1500f, 3f, 75f, custom));
        assertEquals(660f, clip(custom, 1500f, 2160f) - custom[1], 0f);
        assertEquals(1500f - custom[1], 2160f - clip(custom, 1500f, 2160f), 0f);
    }

    @Test public void invalidReferenceCannotPoisonTheNextValidLayout() {
        LyricBandGeometry.Reference reference = new LyricBandGeometry.Reference();
        assertTrue(Float.isNaN(reference.floor(1L, 0f, 1500f)));
        assertTrue(Float.isNaN(reference.floor(1L, 2400f, Float.NaN)));
        assertEquals(1500f, reference.floor(1L, 2400f, 1500f), 0f);
        assertEquals(1500f, reference.floor(1L, 2400f, Float.NaN), 0f);
    }

    @Test public void independentOpeningClockCannotClipRowsOrMoveTheirReadingAnchor() {
        float[] settled = band(1500f);
        float anchor = (settled[0] + settled[1]) / 2f;
        for (float drawnClock : new float[]{1700f, 1400f, 1100f, 800f, 600f}) {
            float[] opening = new float[2];
            float targetClock = LyricBandGeometry.layoutClock(true, drawnClock, 600f);
            assertTrue(LyricStyle.DEFAULT.writeBand(targetClock, 1500f, 3f, 75f, opening));
            assertArrayEquals(settled, opening, 0f);
            assertEquals(anchor, (opening[0] + opening[1]) / 2f, 0f);
            float clipTop = LyricBandGeometry.visibleTop(true, opening[0], targetClock, drawnClock);
            assertEquals(settled[0], clipTop, 0f);
            // The independently rising card still consumes only the bottom of this band.
            assertEquals(clip(settled, 1500f, 1800f), clip(opening, 1500f, 1800f), 0f);
        }
    }

    @Test public void missingDestinationDoesNotBrieflyLayOutAgainstTheFullClock() {
        float[] bounds = new float[2];
        float first = LyricBandGeometry.layoutClock(true, 1000f, Float.NaN);
        assertTrue(Float.isNaN(first));
        assertFalse(LyricStyle.DEFAULT.writeBand(first, 1500f, 3f, 75f, bounds));
        assertTrue(LyricStyle.DEFAULT.writeBand(
                LyricBandGeometry.layoutClock(true, 980f, 600f), 1500f, 3f, 75f, bounds));
        assertArrayEquals(band(1500f), bounds, 0f);
        // A new entry whose target is not ready must not borrow the prior entry's layout.
        assertTrue(Float.isNaN(LyricBandGeometry.layoutClock(true, 650f, Float.NaN)));
    }

    @Test public void ordinaryCoverKeepsItsLiveClockLayoutAndCustomSpacingStillApplies() {
        assertEquals(900f, LyricBandGeometry.layoutClock(false, 900f, 600f), 0f);
        assertEquals(800f, LyricBandGeometry.layoutClock(false, 800f, Float.NaN), 0f);
        LyricStyle style = LyricStyle.DEFAULT.with("fill", 0.5f).with("pos", 0.8f);
        for (float size : new float[]{54f, 75f, 108f}) {
            float[] bounds = new float[2];
            assertTrue(style.writeBand(600f, 1500f, 3f, size, bounds));
            float gap = bounds[0] - 600f;
            assertEquals(bounds[0], LyricBandGeometry.visibleTop(true, bounds[0], 600f, 950f), 0f);
            // Turning the new independent-motion switch off restores the old clock occlusion.
            assertEquals(950f + gap, LyricBandGeometry.visibleTop(false, bounds[0], 600f, 950f), 0.001f);
            assertEquals(bounds[0], LyricBandGeometry.visibleTop(false, bounds[0], 600f, 600f), 0f);
            assertEquals(bounds[0], LyricBandGeometry.visibleTop(false, bounds[0], 600f, Float.NaN), 0f);
        }
    }

    @Test public void focusWaitsForAVisibleValidBandInsteadOfTakingAZeroAnchor() {
        assertFalse(LyricBandGeometry.readyToPlace(false, 0f, 1f));
        assertFalse(LyricBandGeometry.readyToPlace(true, 0f, 0f));
        assertTrue(LyricBandGeometry.readyToPlace(true, 0f, 1f));
        assertTrue(LyricBandGeometry.readyToPlace(true, 0.4f, 0f)); // A closing page still animates.
        assertTrue(LyricBandGeometry.initialPlacement(-1, false));
        assertTrue(LyricBandGeometry.initialPlacement(7, true)); // Reused parked view.
        assertFalse(LyricBandGeometry.initialPlacement(7, false)); // Normal line change.
    }

    @Test public void firstVisibleBandDoesNotSpringBackFromAnInvisibleOldAnchor() {
        float[] band = band(1500f);
        float centeredFix = 0.1f * (band[1] - band[0]) - 500f / 2f;
        assertTrue(centeredFix < 0f); // A tall lead/translation/background group.
        float anchor = band[0] + 0.4f * (band[1] - band[0]) + centeredFix;
        float destination = 840f;
        float[] scroll = {destination}, aim = {destination}, next = {destination}, velocity = {85f};
        // Prior code had already made focus=7 while bandOk=false. Rebasing the later band's
        // correction put the first visible line below its destination, then sprang it upward.
        LyricTransition.shift(centeredFix, scroll, aim, next);
        assertTrue(anchor + destination - scroll[0] > anchor + 100f);
        if (LyricBandGeometry.initialPlacement(7, true))
            LyricBandGeometry.snapScroll(destination, scroll, aim, next, velocity);
        assertEquals(anchor, anchor + destination - scroll[0], 0f);
        assertEquals(0f, scroll[0] - aim[0], 0f);
        assertEquals(0f, velocity[0], 0f);
        assertEquals(destination, next[0], 0f);
        // A visible group's rebase is still continuous and does not become an initial snap.
        assertFalse(LyricBandGeometry.initialPlacement(7, false));
    }

    @Test public void clockPhaseChangeKeepsTheLastTargetUntilTheOnPoseIsWritten() {
        LyricBandGeometry.ClockTarget target = new LyricBandGeometry.ClockTarget();
        Object clock = new Object();
        target.record(clock, 1080, 2400, 600f);
        assertEquals(600f, target.read(clock, 1080, 2400), 0f);
        // Merely landing ENTER -> ON does not publish the preceding moving ink (e.g. 640).
        assertEquals(600f, target.read(clock, 1080, 2400), 0f);
        target.record(clock, 1080, 2400, 601f); // First genuinely placed ON pose.
        assertEquals(601f, target.read(clock, 1080, 2400), 0f);
        target.record(clock, 1080, 2400, 650f); // Later legitimate layout changes are not frozen.
        assertEquals(650f, target.read(clock, 1080, 2400), 0f);
    }

    @Test public void clockTargetCannotSurviveRotationHostReplacementOrExplicitInvalidation() {
        LyricBandGeometry.ClockTarget target = new LyricBandGeometry.ClockTarget();
        Object clock = new Object();
        target.record(clock, 1080, 2400, 600f);
        assertTrue(Float.isNaN(target.read(clock, 2400, 1080)));
        assertTrue(Float.isNaN(target.read(new Object(), 1080, 2400)));
        target.clear(); // New entry, settings update, attachment change or release.
        assertTrue(Float.isNaN(target.read(clock, 1080, 2400)));
        target.record(clock, 1080, 2400, Float.NaN);
        assertTrue(Float.isNaN(target.read(clock, 1080, 2400)));
        target.record(clock, 1080, 2400, 630f);
        assertEquals(630f, target.read(clock, 1080, 2400), 0f);
    }

    @Test public void shrinkingTheGroupCanBringAnOffscreenRowBackInsideTheFixedClip() {
        float top = 1410f, bottom = 1430f, clipBottom = 1400f;
        assertTrue(top > clipBottom); // The old pre-transform cull rejected this entire row.
        float shownTop = LyricBandGeometry.presentedY(top, 1000f, 0f, 0.86f);
        float shownBottom = LyricBandGeometry.presentedY(bottom, 1000f, 0f, 0.86f);
        assertTrue(shownTop < shownBottom && shownBottom < clipBottom);
        assertEquals((bottom - top) * 0.86f, shownBottom - shownTop, 0.001f);
        assertTrue(LyricLineExit.edgeAlpha(1f, shownTop, shownBottom, 600f, clipBottom, 40f) > 0f);
        // A cancelled drag restores the original geometry, without changing the row's pose.
        assertEquals(top, LyricBandGeometry.presentedY(top, 1000f, 0f, 1f), 0f);
        assertEquals(bottom, LyricBandGeometry.presentedY(bottom, 1000f, 0f, 1f), 0f);
    }

    @Test public void entryAndDepartingRowsFadeAtTheirDrawnEdgesRatherThanHardClipping() {
        // This applies equally to a dot slot, a live row and a cached departing-row pose.
        float top = 1300f, bottom = 1320f;
        float before = LyricLineExit.edgeAlpha(1f, top, bottom, 600f, 1400f, 40f);
        assertEquals(1f, before, 0f);
        float shiftedTop = LyricBandGeometry.presentedY(top, 1000f, 54f, 0.97f);
        float shiftedBottom = LyricBandGeometry.presentedY(bottom, 1000f, 54f, 0.97f);
        float edge = LyricLineExit.edgeAlpha(1f, shiftedTop, shiftedBottom, 600f, 1400f, 40f);
        assertTrue(edge > 0f && edge < before);
        assertEquals(0.4f, LyricLineExit.edgeAlpha(0.4f, shiftedTop, shiftedBottom,
                600f, 1400f, 40f), 0f);
        assertEquals(1054f, LyricBandGeometry.presentedY(1000f, 1000f, 54f, 0.97f), 0f);
        // Screen-space checks never overwrite the saved untransformed pose.
        assertEquals(1300f, top, 0f);
        assertEquals(1320f, bottom, 0f);
    }

    @Test public void highestOfSeveralCapsulesAndMovingCardsWinsIndependentlyOfOrder() {
        float pillFirst = add(add(add(Float.NaN, true, 2160f, 140f), true, 2090f, 180f),
                true, 1770f, 450f);
        float cardFirst = add(add(add(Float.NaN, true, 1770f, 450f), true, 2090f, 180f),
                true, 2160f, 140f);
        assertEquals(1770f, pillFirst, 0f);
        assertEquals(pillFirst, cardFirst, 0f);
    }

    @Test public void commitContinuesUsingMovingGeometryUntilTheNativeCardLands() {
        boolean used = LyricBandGeometry.usesCapsuleSpace(true, true, false, false);
        assertTrue(used);
        used = LyricBandGeometry.usesCapsuleSpace(true, true, true, used);
        assertTrue(used);
        used = LyricBandGeometry.usesCapsuleSpace(false, false, true, used);
        assertTrue(used);
        used = LyricBandGeometry.usesCapsuleSpace(false, false, false, used);
        assertFalse(used);
        // Cancellation keeps the request, so the same policy still reads the pill at rest.
        assertTrue(LyricBandGeometry.usesCapsuleSpace(true, true, false, true));
    }

    @Test public void ordinaryCoverMorphsAndRevokedRequestsNeverAcquireCapsuleSpace() {
        assertFalse(LyricBandGeometry.usesCapsuleSpace(false, false, true, false));
        boolean used = LyricBandGeometry.usesCapsuleSpace(true, true, false, false);
        // The request is closed without opening a card. Release the qualification immediately.
        used = LyricBandGeometry.usesCapsuleSpace(false, false, false, used);
        assertFalse(used);
        assertFalse(LyricBandGeometry.usesCapsuleSpace(false, false, true, used));
        // A reconstructed controller starts with no previous qualification.
        assertFalse(LyricBandGeometry.usesCapsuleSpace(false, false, true, false));
    }

    @Test public void invisibleDetachedOrUnmeasuredSurfacesKeepTheExistingFallback() {
        assertTrue(Float.isNaN(add(Float.NaN, false, 1500f, 500f)));
        assertTrue(Float.isNaN(add(Float.NaN, true, 0f, 0f)));
        assertTrue(Float.isNaN(add(Float.NaN, true, Float.NaN, 120f)));
        assertTrue(Float.isNaN(add(Float.NaN, true, 2100f, Float.POSITIVE_INFINITY)));
        assertEquals(2100f, add(2100f, true, Float.NaN, 120f), 0f);
    }

    @Test public void screenInsetsAndOffscreenGeometryCannotExpandTheBandPastTheWindow() {
        assertEquals(2300f, LyricBandGeometry.usableBottom(2400f, 100f), 0f);
        assertTrue(Float.isNaN(LyricBandGeometry.usableBottom(2400f, 2500f)));
        assertTrue(Float.isNaN(LyricBandGeometry.usableBottom(0f, 0f)));
        assertTrue(Float.isNaN(add(Float.NaN, true, 2320f, 50f)));
        assertTrue(Float.isNaN(LyricBandGeometry.include(Float.NaN, true,
                1200f, 2100f, 100f, 100f, 1080f, 2300f)));
        assertTrue(Float.isNaN(LyricBandGeometry.include(Float.NaN, true,
                100f, 2100f, 100f, 100f, 0f, 2300f)));
        assertEquals(0f, add(Float.NaN, true, -30f, 100f), 0f);
    }
}
