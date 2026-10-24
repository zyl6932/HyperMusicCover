package com.os4.musiccover.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricPageVisibilityTest {
    @Test public void capsulePageIsVisibleWhileDetailedMediaCardRemainsFolded() {
        // Regression: MediaCard.setCardProgressFrom clamps the card to zero outside cover mode.
        // Using that number for both pages made an attached, populated capsule page invisible.
        assertEquals(1f, LyricPageVisibility.progress(true, false, 0f), 0f);
    }

    @Test public void closeAndImmediateReopenDoNotRequireAChangeInMediaCardProgress() {
        assertEquals(1f, LyricPageVisibility.progress(true, false, 0f), 0f);
        assertEquals(0f, LyricPageVisibility.progress(false, false, 0f), 0f);
        assertEquals(1f, LyricPageVisibility.progress(true, false, 0f), 0f);
    }

    @Test public void ordinaryCoverPageStillFollowsItsCard() {
        assertEquals(0f, LyricPageVisibility.progress(false, true, 0f), 0f);
        assertEquals(.35f, LyricPageVisibility.progress(false, true, .35f), 0f);
        assertEquals(1f, LyricPageVisibility.progress(false, true, 1f), 0f);
    }

    @Test public void retainedCoverBehindNavigationCannotDelayTheCapsuleOverlay() {
        assertEquals(1f, LyricPageVisibility.progress(true, true, 0f), 0f);
    }

    @Test public void openingAndFoldingControlsKeepsExplicitLyricsVisibleInBothBackgroundModes() {
        for (boolean cover : new boolean[]{false, true}) {
            for (float card : new float[]{0f, .3f, 1f, .7f, 0f}) {
                assertEquals(1f, LyricPageVisibility.progress(true, cover, card), 0f);
            }
        }
    }

    @Test public void staleCardProgressCannotKeepAClosedPageVisible() {
        assertEquals(0f, LyricPageVisibility.progress(false, false, 1f), 0f);
    }

    @Test public void configuredBackgroundOwnsTheClockWithoutAnIndependentLyricsHold() {
        assertFalse(LyricPageVisibility.needsIndependentClock(true, true, true, true));
        assertTrue(LyricPageVisibility.needsIndependentClock(true, false, true, true));
    }

    @Test public void noDataDismissalAndHandoffReleaseTheIndependentClock() {
        assertFalse(LyricPageVisibility.needsIndependentClock(true, false, true, false));
        assertFalse(LyricPageVisibility.needsIndependentClock(true, false, false, true));
        assertFalse(LyricPageVisibility.needsIndependentClock(false, false, true, true));
    }
}
