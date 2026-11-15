package com.os4.musiccover.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerCollapseGestureTest {
    @Test fun positiveOemListPlacementIsNotUserScrolling() {
        assertTrue(MiniPlayerCollapseGesture.atListTop(749, 749f))
        assertTrue(MiniPlayerCollapseGesture.atListTop(750, 749f))
        assertFalse(MiniPlayerCollapseGesture.atListTop(966, 749f))
        assertTrue(MiniPlayerCollapseGesture.atListTop(0, 749f))
        assertTrue(MiniPlayerCollapseGesture.atListTop(0, 0f))
        assertFalse(MiniPlayerCollapseGesture.atListTop(40, 0f))
    }

    @Test fun anUnreadListLeavesThePullAsItWas() {
        assertTrue(MiniPlayerCollapseGesture.atListTop(null, 749f))
        assertTrue(MiniPlayerCollapseGesture.atListTop(749, null))
        assertTrue(MiniPlayerCollapseGesture.atListTop(900, Float.NaN))
    }

    @Test fun listPlacementAllowsCollapseButAUserScrollStillOwnsItsGesture() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(MiniPlayerCollapseGesture.atListTop(749, 749f))
        assertTrue(gesture.move(0f, 30f, 10f, true))
        gesture.start(MiniPlayerCollapseGesture.atListTop(966, 749f))
        assertFalse(gesture.move(0f, 300f, 10f, true))
    }

    @Test fun scrollingBackToTheTopCannotCollapseUntilTheNextDown() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(false)
        assertFalse(gesture.move(0f, 100f, 10f, false))
        assertFalse(gesture.move(0f, 300f, 10f, true))
        gesture.start(true)
        assertTrue(gesture.move(0f, 30f, 10f, true))
    }

    @Test fun upwardScrollCannotTurnIntoCollapseOnDirectionReversal() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(0f, -30f, 10f, true))
        assertFalse(gesture.move(0f, 80f, 10f, true))
    }

    @Test fun horizontalDismissalKeepsTheGesture() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(40f, 10f, 10f, true))
        assertFalse(gesture.move(40f, 100f, 10f, true))
    }

    @Test fun scrollingAfterDownDisarmsCollapse() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(0f, 30f, 10f, false))
        assertFalse(gesture.move(0f, 80f, 10f, true))
    }

    @Test fun smallMotionThenPullAtTopFiresOnlyOnce() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(2f, -3f, 10f, true))
        assertTrue(gesture.move(2f, 30f, 10f, true))
        assertFalse(gesture.move(2f, 60f, 10f, true))
    }

    @Test fun longListFallbackKeepsScrollingOwnershipThroughReturningToTopAndFling() {
        val fallback = SpreadListGesture()
        // Rest remains 749 after scrolling to 1500 and letting the list stop there.
        fallback.down(MiniPlayerCollapseGesture.atListTop(1500, 749f))
        fallback.move(0f, 500f, 10f, false)
        fallback.move(0f, 900f, 10f, true)
        fallback.end(cancelled = false)
        assertFalse(fallback.canFold)
        fallback.down(true)
        fallback.move(0f, 30f, 10f, true)
        assertTrue(fallback.canFold)
    }

    @Test fun anUpwardReadGestureInAGapDoesNotFoldWhenItReverses() {
        val fallback = SpreadListGesture()
        fallback.down(true)
        fallback.move(0f, -100f, 10f, false)
        fallback.move(0f, 200f, 10f, true)
        fallback.end(cancelled = false)
        assertFalse(fallback.canFold)
        fallback.down(true)
        fallback.move(0f, 50f, 10f, true)
        fallback.end(cancelled = true)
        assertFalse(fallback.canFold)
    }

    @Test fun aClaimedShortPullCannotFoldAgainThroughTheOemFallbackAfterRelease() {
        val fallback = SpreadListGesture()
        fallback.down(true)
        fallback.move(0f, 30f, 10f, true)
        assertTrue(fallback.canFold)
        fallback.claim()
        fallback.move(0f, 40f, 10f, true)
        fallback.end(cancelled = false)
        assertFalse(fallback.canFold)
        fallback.down(true)
        fallback.move(0f, 100f, 10f, true)
        assertTrue(fallback.canFold)
    }
}
