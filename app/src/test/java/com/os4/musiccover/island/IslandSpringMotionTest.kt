package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class IslandSpringMotionTest {
    @Test fun theRenderedPullLagsTheFingerAndCommitsWhileStillHeld() {
        val pull = IslandSpringPull()
        pull.move(80f)
        pull.step(1f / 120f)
        assertTrue(pull.value in 0f..20f)
        assertFalse(pull.committed)
        repeat(20) { pull.step(1f / 120f) }
        assertTrue(pull.committed)
        assertFalse(pull.ended)
        assertEquals(40f, pull.value, 0f)
        // A big motion event cannot carry the capsule all the way up before handoff.
        val jump = IslandSpringPull()
        jump.move(2000f)
        jump.step(0.05f)
        assertEquals(40f, jump.value, 0f)
    }

    @Test fun aShortPullAndCancellationReturnWithoutOpening() {
        val pull = IslandSpringPull()
        pull.move(30f)
        repeat(30) { pull.step(1f / 120f) }
        pull.release(4000f, 3f, cancelled = true)
        repeat(90) { pull.step(1f / 120f) }
        assertFalse(pull.committed)
        assertTrue(pull.atRest())
        assertEquals(0f, pull.value, 0.01f)
        pull.move(100f)
        pull.step(0.05f)
        assertFalse(pull.committed)
    }

    @Test fun theInitialThresholdKeepsItsCommitDecisionForHandoff() {
        val pull = IslandSpringPull()
        pull.move(100f)
        repeat(20) { pull.step(1f / 120f) }
        pull.move(0f)
        pull.release(-5000f, 3f, cancelled = true)
        repeat(20) { pull.step(1f / 120f) }
        assertTrue(pull.committed)
        assertEquals(40f, pull.value, 0f)
    }

    @Test fun flingRequiresBothSpeedAndTheProjectedDisplacement() {
        fun flung(start: Float, speed: Float) = IslandSpringPull(start).also {
            it.release(speed, 1f, cancelled = false)
        }.committed
        assertFalse(flung(30f, 1000f))
        assertFalse(flung(0f, 1100f))
        assertTrue(flung(20f, 1100f))
        assertFalse(flung(30f, -2000f))
        // Direction is decided by the projected position, even on a fast reversal near the limit.
        val reversed = IslandSpringPull(35f)
        reversed.release(-1100f, 3f, cancelled = false)
        assertTrue(reversed.committed)
    }

    @Test fun criticalDragSpringIsStableAcrossFrameRatesAndLongFrames() {
        fun value(hz: Int): Float {
            val pull = IslandSpringPull()
            pull.move(30f)
            repeat(hz / 10) { pull.step(1f / hz) }
            return pull.value
        }
        assertEquals(value(60), value(120), 0.0001f)
        assertEquals(value(60), value(240), 0.0001f)
        val pull = IslandSpringPull()
        pull.move(30f)
        pull.step(1f)
        assertTrue(pull.value.isFinite())
        assertEquals(30f, pull.value, 0.0001f)
    }

    @Test fun notificationResponsesFollowTheDexLevelRules() {
        assertEquals(0.35f, IslandSpringMotion.response(0, 1), 0.0001f)
        assertEquals(0.4f, IslandSpringMotion.response(1, 2), 0.0001f)
        assertEquals(0.35f, IslandSpringMotion.response(0, 5), 0.0001f)
        assertEquals(0.4f, IslandSpringMotion.response(2, 5), 0.0001f)
        assertEquals(0.45f, IslandSpringMotion.response(4, 5), 0.0001f)
        val first = IslandSpringMotion.progress(0.1f, IslandSpringMotion.response(0, 5))
        val last = IslandSpringMotion.progress(0.1f, IslandSpringMotion.response(4, 5))
        assertTrue(first > last)
        for (count in listOf(1, 2, 5, 20)) for (i in 0 until count) {
            val response = IslandSpringMotion.response(i, count)
            val samples = (0..96).map { IslandSpringMotion.progress(it / 120f, response) }
            assertTrue(samples.all { it.isFinite() })
            assertTrue(samples.max() > 1f)
            assertEquals(1f, samples.last(), 0.001f)
        }
    }

    @Test fun renderWritesDoNotFeedBackIntoTheDestinationAndOemChangesStillWin() {
        val y = SpringRenderValue(600f)
        var rendered = 600f
        repeat(120) { frame ->
            assertEquals(600f, y.read(rendered), 0f)
            rendered = 900f + (600f - 900f) * IslandSpringMotion.progress(frame / 120f, 0.4f)
            y.written(rendered)
        }
        // The clock or a notification update moves the OEM target while we are drawing.
        assertEquals(520f, y.read(520f), 0f)
        y.written(530f)
        assertEquals(520f, y.read(530f), 0f)
        // Restore reads the latest OEM value, not the original capture or our final write.
        assertEquals(510f, y.read(510f), 0f)
    }
}
