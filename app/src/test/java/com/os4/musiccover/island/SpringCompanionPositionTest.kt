package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class SpringCompanionPositionTest {
    @Test fun onlyVisibleNativeCardsOutsideTheIslandTransitionJoinAsCompanions() {
        assertTrue(SpringCompanionPlan.keepsCard(true, false, media = true, focus = false))
        assertTrue(SpringCompanionPlan.keepsCard(true, false, media = false, focus = true))
        assertFalse(SpringCompanionPlan.keepsCard(true, true, media = true, focus = false))
        assertFalse(SpringCompanionPlan.keepsCard(true, true, media = false, focus = true))
        assertFalse(SpringCompanionPlan.keepsCard(false, false, media = true, focus = false))
        assertFalse(SpringCompanionPlan.keepsCard(true, false, media = false, focus = false))
    }

    @Test fun independentMusicStartsReturningWhileTheNotificationsAreStillClosing() {
        val card = SpringCompanionPosition(1700f)
        val pose = SpringSpreadState(1f).also { it.aim(false) }
        assertEquals(500f, card.top(500f, 300f, pose.position.value), 0f)
        for (t in 0L..100L step 10) pose.advance(t)
        val moving = card.top(500f, 300f, pose.position.value)
        assertTrue(moving > 500f && moving < 1400f)
        assertFalse(pose.position.atRest())
        for (t in 110L..1600L step 10) pose.advance(t)
        assertEquals(1400f, card.top(500f, 300f, pose.position.value), 0f)
        // Delayed OEM writes cannot pull a finished capsule motion back up for another slide.
        for (native in listOf(500f, 900f, 1200f, 1400f))
            assertEquals(1400f, card.top(native, 300f, 0f), 0f)
    }

    @Test fun aReversalUsesTheSamePoseAndOpeningReturnsToTheNativeList() {
        val card = SpringCompanionPosition(1700f)
        val pose = SpringSpreadState(1f).also { it.aim(false) }
        for (t in 0L..100L step 10) pose.advance(t)
        val top = card.top(500f, 300f, pose.position.value)
        pose.aim(true)
        assertEquals(top, card.top(500f, 300f, pose.position.value), 0f)
        for (t in 110L..1600L step 10) pose.advance(t)
        assertEquals(500f, card.top(500f, 300f, pose.position.value), 0f)
    }

    @Test fun theFoldedEndpointRetainsTheNativeBottomWhenContentHeightChanges() {
        val card = SpringCompanionPosition(1700f)
        assertEquals(1400f, card.top(500f, 300f, 0f), 0f)
        assertEquals(1300f, card.top(500f, 400f, 0f), 0f)
        assertFalse(card.reachedHome(500f, 400f))
        assertFalse(card.reachedHome(1280f, 400f))
        assertTrue(card.reachedHome(1300f, 400f))
        // Starting a new transition while the OEM is catching up keeps the same home.
        assertEquals(1300f, card.top(1000f, 400f, 0f), 0f)
    }

    @Test fun containerMovementCannotShiftTheRecordedScreenPositionDuringHandoff() {
        val card = SpringCompanionPosition(1700f)
        for (parentY in listOf(0f, 200f, -120f)) {
            val localNativeTop = 700f
            val screenTop = card.top(parentY + localNativeTop, 300f, 0f)
            val renderTranslation = screenTop - parentY
            assertEquals(1400f, parentY + renderTranslation, 0f)
            assertEquals(parentY + localNativeTop,
                card.top(parentY + localNativeTop, 300f, 1f), 0f)
        }
    }
}
