package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricAvailabilityTest {
    @Test public void previousSongCannotAnnounceSuccessForCurrentSongStillLoading() {
        assertEquals(LyricAvailability.PENDING,
                LyricAvailability.resolve("new", "old", true, true, false, false, "old"));
        assertEquals(LyricAvailability.PENDING,
                LyricAvailability.resolve("new", "new", false, false, true, false, "old"));
    }

    @Test public void currentLinesOrCurrentCacheAreUsableDuringAnUpgradeLookup() {
        assertEquals(LyricAvailability.AVAILABLE,
                LyricAvailability.resolve("song", "song", true, false, true, false, null));
        assertEquals(LyricAvailability.AVAILABLE,
                LyricAvailability.resolve("song", "song", false, true, false, false, null));
    }

    @Test public void onlyFinalEmptyAnswerIsUnavailableAndParkedFallbackStaysPending() {
        assertEquals(LyricAvailability.PENDING,
                LyricAvailability.resolve("song", "song", false, false, false, true, null));
        assertEquals(LyricAvailability.PENDING,
                LyricAvailability.resolve("song", "song", false, false, false, false, null));
        assertEquals(LyricAvailability.UNAVAILABLE,
                LyricAvailability.resolve("song", "song", false, false, false, false, "song"));
    }

    @Test public void newlyDiscoveredProviderPayloadDefersTheJustSettledEmptyFeedback() {
        // settle() publishes empty lines and then rereads a new payload on the same stack.
        // The coalesced UI observer sees the restarted lookup, not an intermediate failure.
        assertEquals(LyricAvailability.PENDING,
                LyricAvailability.resolve("song", "song", false, false, true, false, "song"));
        assertEquals(LyricAvailability.AVAILABLE,
                LyricAvailability.resolve("song", "song", true, true, false, false, "song"));
    }

    @Test public void noTrackIdentityAndNoLookupHasNoUsableLyrics() {
        assertEquals(LyricAvailability.UNAVAILABLE,
                LyricAvailability.resolve("", "", false, false, false, false, null));
    }

    @Test public void confirmedEmptyKeepsQuestionOnBothOpenAndCloseTaps() {
        LyricAvailability empty = LyricAvailability.resolve("song", "song", false, false,
                false, false, "song");
        assertEquals(LyricAvailability.Feedback.UNAVAILABLE, empty.feedbackFor(true));
        assertEquals(LyricAvailability.Feedback.UNAVAILABLE, empty.feedbackFor(false));
    }

    @Test public void closingPendingLookupOrCachedLyricsStillUsesTheActualCloseFeedback() {
        // Cancelling a still-running lookup clears its loading flag without settling it.
        LyricAvailability cancelled = LyricAvailability.resolve("song", "song", false, false,
                false, false, null);
        assertEquals(LyricAvailability.Feedback.DISABLED, cancelled.feedbackFor(false));
        // Hiding actual lyrics empties drawing lines, but their cache is still this track's.
        LyricAvailability cached = LyricAvailability.resolve("song", "song", false, true,
                false, false, "song");
        assertEquals(LyricAvailability.Feedback.DISABLED, cached.feedbackFor(false));
        assertEquals(LyricAvailability.Feedback.ENABLED, cached.feedbackFor(true));
    }
}
