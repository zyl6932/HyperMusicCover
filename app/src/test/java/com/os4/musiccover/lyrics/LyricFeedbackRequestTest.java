package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricFeedbackRequestTest {
    private final Object view = new Object();
    private final LyricFeedbackRequest request = new LyricFeedbackRequest(10L, "session", "song", "key", view, 3L);

    @Test public void currentRequestCanFinishOnlyWhileItsPageIsStillRequested() {
        assertTrue(request.owns(10L, "session", "song", "key", view, 3L, true));
        assertFalse(request.owns(10L, "session", "song", "key", view, 3L, false));
    }

    @Test public void closingAndReopeningCannotReceiveTheOlderTapAnswer() {
        assertFalse(request.owns(11L, "session", "song", "key", view, 3L, true));
    }

    @Test public void changedSongOrLyricIdentityInvalidatesTheOldAnswer() {
        assertFalse(request.owns(10L, "session", "next-song", "key", view, 3L, true));
        assertFalse(request.owns(10L, "session", "song", "new-duration-key", view, 3L, true));
    }

    @Test public void sessionReplacementAndViewRebuildInvalidateOldCallbacks() {
        assertFalse(request.owns(10L, "other-session", "song", "key", view, 3L, true));
        assertFalse(request.owns(10L, "session", "song", "key", new Object(), 3L, true));
    }

    @Test public void reboundSameViewCannotClearOrFinishAnOlderIdentityEvenAfterReturningToSong() {
        assertFalse(request.owns(10L, "session", "song", "key", view, 5L, true));
        assertFalse(request.sameBinding(view, 5L));
        assertTrue(request.sameBinding(view, 3L));
    }
}
