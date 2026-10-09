package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintFrameRestoreTest {
    @Test fun restoresTheLatestSuppressedFrameOnlyOnce() {
        val frames = FingerprintFrameRestore<Any>()
        val surface = Any()
        frames.suppressed(surface, 10)
        frames.suppressed(surface, 20)
        val draws = ArrayList<Int>()
        frames.restore { _, resource -> draws.add(resource) }
        frames.restore { _, resource -> draws.add(resource) }
        assertEquals(listOf(20), draws)
    }

    @Test fun anAppFrameReplacesThePendingLockscreenFrame() {
        val frames = FingerprintFrameRestore<Any>()
        val surface = Any()
        frames.suppressed(surface, 10)
        frames.painted(surface)
        val draws = ArrayList<Int>()
        frames.restore { _, resource -> draws.add(resource) }
        assertTrue(draws.isEmpty())
    }

    @Test fun anotherHideDuringRestorationKeepsItsNewFrame() {
        val frames = FingerprintFrameRestore<Any>()
        val surface = Any()
        frames.suppressed(surface, 10)
        frames.restore { _, _ -> frames.suppressed(surface, 30) }
        val draws = ArrayList<Int>()
        frames.restore { _, resource -> draws.add(resource) }
        assertEquals(listOf(30), draws)
    }
}
