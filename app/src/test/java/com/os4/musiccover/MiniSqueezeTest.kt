package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniSqueezeTest {
    private fun box(x: Float, y: Float, w: Float, h: Float) = CoverMorphMotion.Box(x, y, w, h)

    private val left = box(21f, 0f, 72f, 72f)
    private val right = box(307f, 0f, 72f, 72f)

    @Test fun aPillAtRestPressesOnNothing() {
        val t = MiniSqueeze.targets(box(101f, 0f, 198f, 72f), left, right, 8f, true)
        t.forEach { assertEquals(0f, it, 0f) }
    }

    @Test fun theFirstTouchEasesIn() {
        // Ramp over 2 x 6: nothing at the edge of the gap, then slowly, then one for one.
        assertEquals(0f, MiniSqueeze.ramp(0f, 6f), 0f)
        assertEquals(1f / 24f, MiniSqueeze.ramp(1f, 6f), 1e-6f)
        assertEquals(6f, MiniSqueeze.ramp(12f, 6f), 1e-6f)
        assertEquals(14f, MiniSqueeze.ramp(20f, 6f), 1e-6f)
    }

    @Test fun nudgedLeftMovesTheTorchAsideMoreThanItFlattensIt() {
        // 20 into the gap: 14 to give, 8.4 of it the disc's, which moves 1.4 times that.
        val t = MiniSqueeze.targets(box(81f, 0f, 198f, 72f), left, right, 8f, true)
        assertEquals(8.4f * MiniSqueeze.PUSH_GAIN / 72f, t[4], 1e-4f)
        assertEquals(0f, t[0], 0f)
        assertEquals(5.6f / 198f, t[2], 1e-4f)
        assertEquals(0f, t[1], 0f)
        assertEquals(0f, t[5], 0f)
    }

    @Test fun theyNeverMeetAndEveryCapHolds() {
        for (x in 0..101) {
            val pill = box(101f - x, 0f, 198f, 72f)
            val t = MiniSqueeze.targets(pill, left, right, 8f, true)
            // The torch's inner edge and the pill's left edge after both have given.
            val discEdge = left.x + left.w - 72f * (t[4] + t[0])
            val pillEdge = pill.x + 198f * t[2]
            assertTrue("met at $x", pillEdge - discEdge >= 8f * MiniSqueeze.CONTACT_SHARE - 1e-3f
                || t[4] >= MiniSqueeze.HARD_PUSH - 1e-6f && t[0] >= MiniSqueeze.HARD_SQUASH - 1e-6f)
            assertTrue(t[4] <= MiniSqueeze.HARD_PUSH + 1e-6f)
            assertTrue(t[0] <= MiniSqueeze.HARD_SQUASH + 1e-6f)
            assertTrue(t[2] <= MiniSqueeze.MAX_GIVE + 1e-6f)
        }
    }

    @Test fun aMorphContainerDoesNotGive() {
        val t = MiniSqueeze.targets(box(0f, 0f, 400f, 72f), left, right, 8f, false)
        assertEquals(MiniSqueeze.HARD_PUSH, t[4], 1e-6f)
        assertEquals(MiniSqueeze.HARD_SQUASH, t[0], 1e-6f)
        assertEquals(MiniSqueeze.HARD_PUSH, t[5], 1e-6f)
        assertEquals(0f, t[2], 0f)
        assertEquals(0f, t[3], 0f)
    }

    @Test fun aContainerRisingClearOfTheDiscsLetsThemGo() {
        val half = MiniSqueeze.targets(box(0f, -36f, 400f, 72f), left, right, 8f, false)
        val clear = MiniSqueeze.targets(box(0f, -200f, 400f, 72f), left, right, 8f, false)
        assertTrue(half[4] > 0f)
        assertEquals(0f, clear[4], 0f)
        assertEquals(0f, clear[0], 0f)
        assertEquals(0.5f, MiniSqueeze.verticalOverlap(box(0f, -36f, 400f, 72f), left), 1e-4f)
    }
}
