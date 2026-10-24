package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class CapsuleBodyTouchTest {
    @Test fun oneBodyGestureClicksExactlyOnce() {
        val touch = CapsuleBodyTouch()
        touch.down()
        assertTrue(touch.up(50f, 20f, 200, 40))
        assertFalse(touch.up(50f, 20f, 200, 40))
    }
    @Test fun swipeOrSystemCancellationCannotFallThroughToBodyClick() {
        val touch = CapsuleBodyTouch()
        touch.down()
        touch.cancel() // routeTouch sends CANCEL when its swipe threshold is crossed.
        assertFalse(touch.up(50f, 20f, 200, 40))
        touch.down()
        assertTrue(touch.up(50f, 20f, 200, 40))
    }
    @Test fun leavingAndReturningDoesNotResurrectCancelledTap() {
        val touch = CapsuleBodyTouch()
        touch.down()
        touch.move(202f, 20f, 200, 40)
        touch.move(50f, 20f, 200, 40)
        assertFalse(touch.up(50f, 20f, 200, 40))
        // A child playback button owns its DOWN: the body never began a gesture.
        assertFalse(touch.up(175f, 20f, 200, 40))
    }
}
