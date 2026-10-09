package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FocusTimerAodTest {
    @Test fun allCountdownFormatsUsePlaceholderIncludingPausedAndExpiredTimers() {
        for (type in listOf(-1, -2, -3, -4)) {
            val timer = LockIslands.Timer(type, 60_000L, 10_000L)
            assertEquals("--:--", timer.displayText(true, 20_000L))
            assertEquals("--:--", timer.displayText(true, 70_000L))
        }
    }

    @Test fun wakingRestoresActualRemainingTimeAcrossRepeatedAodCycles() {
        val timer = LockIslands.Timer(-3, 120_000L, 0L)
        assertEquals("01:50", timer.displayText(false, 10_000L))
        assertEquals("--:--", timer.displayText(true, 20_000L))
        assertEquals("01:30", timer.displayText(false, 30_000L))
        assertEquals("--:--", timer.displayText(true, 40_000L))
        assertEquals("00:00", timer.displayText(false, 130_000L))
    }

    @Test fun pausedCountdownRetainsItsPausedValueAfterWaking() {
        val timer = LockIslands.Timer(-4, 120_000L, 30_000L)
        assertEquals("--:--", timer.displayText(true, 60_000L))
        assertEquals("01:30", timer.displayText(false, 90_000L))
    }

    @Test fun stopwatchRemainsLiveWithoutTheOptionalContentPause() {
        for (type in listOf(1, 2, 3, 4)) {
            assertFalse(LockIslands.Timer(type, 0L, 0L).aodPlaceholder(true))
        }
        val timer = LockIslands.Timer(3, 0L, 0L)
        assertEquals("00:10", timer.displayText(true, 10_000L))
        assertEquals("00:20", timer.displayText(true, 20_000L))
    }
}
