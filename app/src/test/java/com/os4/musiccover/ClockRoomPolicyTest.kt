package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockRoomPolicyTest {
    @Test fun emptyOrEntirelyHiddenStackDoesNotReserveItsBottomForNotifications() {
        val empty = ClockRoomPolicy.contentTop(900f, Float.POSITIVE_INFINITY)
        assertEquals(Float.MAX_VALUE, empty)
        // A low stack boundary used to squeeze a centred clock even with every row hidden.
        assertEquals(2600f, ClockRoomPolicy.resolve(1200f, empty, 2600f))
        assertEquals(2600f, ClockRoomPolicy.resolve(1200f,
            ClockRoomPolicy.contentTop(1500f, Float.POSITIVE_INFINITY), 2600f))
    }

    @Test fun removingTheLastVisibleNotificationRestoresTheEditedClockBoundary() {
        val visible = ClockRoomPolicy.contentTop(900f, 500f)
        assertEquals(1400f, ClockRoomPolicy.resolve(1200f, visible, 2600f))
        val empty = ClockRoomPolicy.contentTop(900f, Float.POSITIVE_INFINITY)
        assertEquals(2600f, ClockRoomPolicy.resolve(1200f, empty, 2600f))
    }

    @Test fun aodAndWakeUseTheirCurrentEditedBoundaryInsteadOfAnOldStackPosition() {
        val empty = Float.MAX_VALUE
        assertEquals(2600f, ClockRoomPolicy.resolve(1200f, empty, 2600f))
        assertEquals(2100f, ClockRoomPolicy.resolve(1200f, empty, 2100f))
        assertEquals(2600f, ClockRoomPolicy.resolve(1200f, empty, 2600f))
    }

    @Test fun unavailableGeometryAndSentinelsPreserveTheSystemRequest() {
        assertEquals(1700f, ClockRoomPolicy.resolve(1700f, Float.NaN, 2600f))
        assertEquals(1700f, ClockRoomPolicy.resolve(1700f, Float.MAX_VALUE, Float.NaN))
        assertEquals(Float.MAX_VALUE, ClockRoomPolicy.resolve(Float.MAX_VALUE, 1400f, 2600f))
        assertTrue(ClockRoomPolicy.resolve(Float.NaN, 1400f, 2600f).isNaN())
    }

    @Test fun visibleRowsNeverPushTheClockFurtherThanTheSystemRequested() {
        assertEquals(2000f, ClockRoomPolicy.resolve(2000f, 1400f, 2600f))
        assertEquals(2200f, ClockRoomPolicy.resolve(2000f, 2200f, 2600f))
    }
}
