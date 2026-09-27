package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowSceneTest {
    @Test fun backdropFollowsEntryAndExitProgress() {
        assertEquals(0f, CoverFlowScene.opacity(0f, 1f, 1f), 0f)
        assertEquals(0.35f, CoverFlowScene.opacity(0.35f, 1f, 1f), 0.0001f)
        assertEquals(1f, CoverFlowScene.opacity(1f, 1f, 1f), 0f)
        assertEquals(0.35f, CoverFlowScene.opacity(0.35f, 1f, 1f), 0.0001f)
        assertEquals(0f, CoverFlowScene.opacity(0f, 1f, 1f), 0f)
    }

    @Test fun lateShaderStartsTransparentAndFollowsTheCurrentScene() {
        assertEquals(0f, CoverFlowScene.opacity(0.8f, 1f, 0f), 0f)
        assertEquals(0.4f, CoverFlowScene.opacity(0.8f, 1f, 0.5f), 0.0001f)
        assertEquals(0.8f, CoverFlowScene.opacity(0.8f, 1f, 1f), 0.0001f)
        assertEquals(0.2f, CoverFlowScene.opacity(1f, 0.2f, 1f), 0.0001f)
    }

    @Test fun lyricFadeGraduallyReachesFortyPercentBlack() {
        assertEquals(0, CoverFlowScene.lyricShade(0f))
        assertEquals(51, CoverFlowScene.lyricShade(0.5f))
        assertEquals(102, CoverFlowScene.lyricShade(1f))
    }

    @Test fun backdropPrecedesExistingShortcutDiscs() {
        val position = CoverFlowScene.layerIndex(9, 7, 8)
        assertEquals(7, position)
        assertTrue(position < 9)
        assertEquals(9, CoverFlowScene.layerIndex(9, -1, -1))
    }

    @Test fun cardBackdropUsesSixtyFivePercentAndStopsOutsideAwakeScene() {
        assertEquals(0f, CoverFlowScene.cardOpacity(1f, false), 0f)
        assertEquals(0.325f, CoverFlowScene.cardOpacity(0.5f, true), 0.0001f)
        assertEquals(0.65f, CoverFlowScene.cardOpacity(2f, true), 0.0001f)
    }
}
