package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class SpringRowVisibilityTest {
    @Test fun stackingCannotHideAnOpeningOrClosingRowHalfwayThroughItsSpring() {
        for (opening in listOf(true, false)) {
            val pose = SpringSpreadState(if (opening) 0f else 1f).also { it.aim(opening) }
            for (time in 0L..160L step 10) {
                // The OEM reissues INVISIBLE on each layout while the row is beyond its pile.
                val visible = SpringRowVisibilityPolicy.reveal(4, stackingHidden = true, layoutHidden = false)
                assertTrue(visible)
                pose.advance(time, ready = visible)
            }
            assertTrue(pose.started)
            assertTrue(pose.position.value in 0.01f..0.99f)
            assertTrue(pose.opacity.value > 0f)
        }
    }

    @Test fun anInvisibleStackedRowStartsAtItsOwnFirstFrameInsteadOfPoppingInAtTheEnd() {
        val pose = SpringSpreadState(0f).also { it.aim(true) }
        val reveal = SpringRowVisibilityPolicy.reveal(4, stackingHidden = true, layoutHidden = false)
        pose.advance(500L, ready = reveal)
        assertEquals(0f, pose.position.value, 0f)
        assertEquals(0f, pose.opacity.value, 0f)
        pose.advance(516L, ready = reveal)
        assertTrue(pose.position.value > 0f && pose.position.value < 0.1f)
    }

    @Test fun removalAndOtherReasonsForHidingAreNeverOverridden() {
        assertFalse(SpringRowVisibilityPolicy.reveal(8, true, false))
        assertFalse(SpringRowVisibilityPolicy.reveal(4, false, false))
        assertFalse(SpringRowVisibilityPolicy.reveal(4, true, true))
        assertFalse(SpringRowVisibilityPolicy.reveal(0, true, false))
    }

    @Test fun cleanupUsesTheCurrentNativeStateRatherThanItsOriginalCapture() {
        assertEquals(4, SpringRowVisibilityPolicy.restore(stackingHidden = true, layoutHidden = false))
        // It was stacked at startup, but the now-open list wants it visible.
        assertEquals(0, SpringRowVisibilityPolicy.restore(stackingHidden = false, layoutHidden = false))
        assertEquals(4, SpringRowVisibilityPolicy.restore(stackingHidden = false, layoutHidden = true))
    }
}
