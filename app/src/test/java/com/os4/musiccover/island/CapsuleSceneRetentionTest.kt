package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class CapsuleSceneRetentionTest {
    @Test fun bodyChoiceSurvivesRepeatedPanelRoundTripsUntilExplicitlyClosed() {
        val state = CapsuleSceneRetention()
        state.opened("map", navigation = true, fromBody = true)
        repeat(4) { assertTrue(state.keeps("map")) }
        state.closed("map")
        assertFalse(state.keeps("map"))
    }

    @Test fun ordinaryPanelOpeningAndOtherSceneTypesKeepTheirExistingCloseBehavior() {
        val state = CapsuleSceneRetention()
        state.opened("map", navigation = true, fromBody = false)
        assertFalse(state.keeps("map"))
        state.opened("timer", navigation = false, fromBody = true)
        assertFalse(state.keeps("timer"))
    }

    @Test fun LateCloseOfAnOldPageCannotClearTheNewExplicitChoice() {
        val state = CapsuleSceneRetention()
        state.opened("map1", navigation = true, fromBody = true)
        state.opened("map2", navigation = true, fromBody = true)
        state.closed("map1")
        assertFalse(state.keeps("map1"))
        assertTrue(state.keeps("map2"))
    }

    @Test fun EndingOrReplacingNavigationReleasesTheRetainedChoice() {
        val state = CapsuleSceneRetention()
        state.opened("map", navigation = true, fromBody = true)
        state.closed("map")
        state.opened("map", navigation = true, fromBody = false)
        assertFalse(state.keeps("map"))
        state.opened("map", navigation = true, fromBody = true)
        state.opened("timer", navigation = false, fromBody = false)
        assertFalse(state.keeps("map"))
    }
}
