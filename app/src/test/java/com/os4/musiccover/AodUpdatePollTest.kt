package com.os4.musiccover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodUpdatePollTest {
    @Test fun burstsAreCoalescedAtThirtySecondsAndIdlePollsDoNotRefresh() {
        val poll = AodUpdatePoll()
        assertTrue(poll.setPaused(true, 1_000L))
        repeat(100) { assertTrue(poll.deferUpdate()) }
        assertFalse(poll.poll(30_999L))
        assertTrue(poll.poll(31_000L))
        assertFalse(poll.poll(61_000L))
        assertTrue(poll.deferUpdate())
        assertFalse(poll.poll(90_999L))
        assertTrue(poll.poll(91_000L))
    }

    @Test fun wakingOrDisablingRestoresImmediateUpdatesAndCancelsPendingContent() {
        val poll = AodUpdatePoll()
        poll.setPaused(true, 0L)
        poll.deferUpdate()
        assertTrue(poll.setPaused(false, 10_000L))
        assertFalse(poll.deferUpdate())
        assertFalse(poll.poll(30_000L))
        assertTrue(poll.setPaused(true, 40_000L))
        assertFalse(poll.poll(70_000L))
    }

    @Test fun repeatedModeChecksDoNotDelayPendingUpdates() {
        val poll = AodUpdatePoll()
        poll.setPaused(true, 0L)
        poll.deferUpdate()
        assertFalse(poll.setPaused(true, 29_000L))
        assertTrue(poll.poll(30_000L))
        assertFalse(poll.poll(30_001L))
    }
}
