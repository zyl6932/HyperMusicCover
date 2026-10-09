package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateStatusLayoutTest {
    private fun layout(left: Int = 300, extra: Int = 0, base: Int = 200,
                       top: Int = 100, height: Int = 50, parent: Int = 800, aod: Boolean = false) =
        DateStatusLayout(left, top, base + extra, height, parent, extra, aod)

    @Test fun statusWidthMovesACentredDateFromItsPreviousDrawnPosition() {
        val before = layout()
        val after = layout(left = 270, extra = 60)
        val offset = DateStatusLayoutPolicy.offset(before, after, 0f)!!
        assertEquals(30f, offset)
        assertEquals(before.left.toFloat(), after.left + offset)
        assertEquals(-30f, DateStatusLayoutPolicy.offset(after, before, 0f)!!)
    }

    @Test fun aSecondStatusArrivingMidAnimationKeepsTheCurrentDrawnPosition() {
        val before = layout(left = 270, extra = 60)
        val after = layout(left = 240, extra = 120)
        val offset = DateStatusLayoutPolicy.offset(before, after, 20f)!!
        assertEquals(before.left + 20f, after.left + offset)
    }

    @Test fun oemHorizontalMovementWithoutAStatusWidthChangeClearsAnOldOffset() {
        assertNull(DateStatusLayoutPolicy.offset(layout(extra = 60),
            layout(left = 200, extra = 60), 30f))
    }

    @Test fun aChangedClockFontOrDateSizeIsNotAStatusAnimation() {
        assertNull(DateStatusLayoutPolicy.offset(layout(),
            layout(left = 250, extra = 60, base = 240), 30f))
        assertNull(DateStatusLayoutPolicy.offset(layout(),
            layout(left = 270, extra = 60, height = 70), 30f))
    }

    @Test fun aodAndReattachmentNeverInheritTheOldClockOffset() {
        assertNull(DateStatusLayoutPolicy.offset(null, layout(extra = 60), 30f))
        assertNull(DateStatusLayoutPolicy.offset(layout(),
            layout(left = 270, extra = 60, aod = true), 30f))
        assertNull(DateStatusLayoutPolicy.offset(layout(aod = true),
            layout(left = 270, extra = 60), 30f))
    }

    @Test fun movingOrResizingTheClockContainerClearsCompensation() {
        assertNull(DateStatusLayoutPolicy.offset(layout(),
            layout(left = 270, extra = 60, top = 80), 30f))
        assertNull(DateStatusLayoutPolicy.offset(layout(),
            layout(left = 270, extra = 60, parent = 1000), 30f))
    }

    @Test fun unrelatedMovementCannotBeCompensatedAsAddedStatusWidth() {
        assertNull(DateStatusLayoutPolicy.offset(layout(),
            layout(left = 100, extra = 60), 30f))
        assertNull(DateStatusLayoutPolicy.offset(layout(),
            layout(left = 320, extra = 60), 30f))
    }

    @Test fun unchangedLayoutKeepsTheRunningSpringAndLeftAlignedDatesNeedNoNewOffset() {
        val unchanged = layout(extra = 60)
        assertEquals(20f, DateStatusLayoutPolicy.offset(unchanged, unchanged, 20f)!!)
        assertEquals(0f, DateStatusLayoutPolicy.offset(layout(), layout(extra = 60), 0f)!!)
    }
}
