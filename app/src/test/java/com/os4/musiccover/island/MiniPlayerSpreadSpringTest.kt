package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class MiniPlayerSpreadSpringTest {
    @Test fun nativeHandoffRetiresTheOpeningSpringBeforeClosing() {
        val motion = MiniPlayerSpreadMotion()
        motion.progress = 0.4f
        motion.release(true, 3f)
        motion.finish(true)
        assertNull(motion.spring)
        assertEquals(1f, motion.progress, 0f)
        motion.takeOver()
        motion.progress = 0.7f
        motion.release(false, -2f)
        assertEquals(0.7f, motion.spring!!.step(0f), 0f)
        assertTrue(motion.spring!!.step(1f / 120f) < 0.7f)
    }

    @Test fun catchingAReleaseKeepsTheVisiblePoseAndDropsItsOldTarget() {
        val motion = MiniPlayerSpreadMotion()
        motion.progress = 0.65f
        motion.release(false, -2f)
        motion.progress = motion.spring!!.step(0.05f)
        val caught = motion.progress
        motion.takeOver()
        assertNull(motion.spring)
        assertEquals(caught, motion.progress, 0f)
        motion.release(true, 0f)
        assertEquals(caught, motion.spring!!.step(0f), 0f)
        assertTrue(motion.spring!!.step(1f / 60f) > caught)
    }

    @Test fun fastReleaseContinuesFromTheFingerInsteadOfSnappingToTheList() {
        val spring = MiniPlayerSpreadSpring(0.35f, 3f, true)
        assertEquals(0.35f, spring.step(0f), 0f)
        val first = spring.step(1f / 120f)
        assertTrue(first > 0.35f && first < 0.5f)
        var overshot = false
        repeat(240) { overshot = spring.step(1f / 120f) > 1f || overshot }
        assertTrue(overshot)
        assertTrue(spring.atRest)
        assertEquals(1f, spring.step(0f), 0f)
    }

    @Test fun reversingAndCancelledPullsReturnHomeWithBoundedOvershoot() {
        val spring = MiniPlayerSpreadSpring(0.65f, -30f, false)
        repeat(240) {
            assertTrue(spring.step(1f / 120f) in -0.025f..1.025f)
        }
        assertTrue(spring.atRest)
        assertEquals(0f, spring.step(0f), 0f)
    }

    @Test fun differentRefreshRatesReachTheSamePoseAtTheSameTime() {
        fun pose(fps: Int): Float {
            val spring = MiniPlayerSpreadSpring(0.4f, 2f, true)
            var value = 0.4f
            repeat(fps / 5) { value = spring.step(1f / fps) }
            return value
        }
        assertEquals(pose(60), pose(120), 0.0001f)
    }
}
