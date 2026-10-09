package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerStackGestureTest {
    @Test fun collapsingFollowsTheFingerAndCanReverseWithoutJumping() {
        assertEquals(0.5f, MiniPlayerStackGesture.collapseProgress(1f, 110f, 220f))
        assertEquals(0.75f, MiniPlayerStackGesture.collapseProgress(1f, 55f, 220f))
        assertEquals(0.25f, MiniPlayerStackGesture.collapseProgress(0.5f, 55f, 220f))
        assertEquals(1f, MiniPlayerStackGesture.collapseProgress(1f, -110f, 220f))
        assertEquals(0f, MiniPlayerStackGesture.collapseProgress(1f, 1000f, 220f))
    }
    @Test fun onlyDownwardVerticalMotionStartsBrowsing() {
        assertTrue(MiniPlayerStackGesture.starts(5f, 20f))
        assertFalse(MiniPlayerStackGesture.starts(0f, -80f))
        assertFalse(MiniPlayerStackGesture.starts(30f, 20f))
        assertFalse(MiniPlayerStackGesture.starts(0f, 0f))
    }

    @Test fun distanceThresholdScalesWithDensityAndShortPullReturnsHome() {
        assertFalse(MiniPlayerStackGesture.commits(99f, 0f, 2f, false))
        assertTrue(MiniPlayerStackGesture.commits(100f, 0f, 2f, false))
    }

    @Test fun aDownwardFlickStillNeedsIntentionalTravel() {
        assertFalse(MiniPlayerStackGesture.commits(1f, 6000f, 2f, false))
        assertTrue(MiniPlayerStackGesture.commits(20f, 2500f, 2f, false))
        assertFalse(MiniPlayerStackGesture.commits(20f, -2500f, 2f, false))
        assertFalse(MiniPlayerStackGesture.commits(-100f, 2500f, 2f, false))
    }

    @Test fun cancellationNeverChangesTheSelectedCard() {
        assertFalse(MiniPlayerStackGesture.commits(200f, 6000f, 1f, true))
    }

    @Test fun browsingVisitsEveryCardAndWrapsToTheFirst() {
        val keys = listOf("music", "app-a", "timer", "app-b")
        var selected = keys.first()
        val visited = ArrayList<String>()
        repeat(keys.size) {
            selected = MiniPlayerStackGesture.nextKey(keys, selected)!!
            visited.add(selected)
        }
        assertEquals(listOf("app-a", "timer", "app-b", "music"), visited)
    }

    @Test fun aLoneCardHasNothingToSwitchTo() {
        assertNull(MiniPlayerStackGesture.nextKey(emptyList(), null))
        assertNull(MiniPlayerStackGesture.nextKey(listOf("music"), "music"))
    }
}
