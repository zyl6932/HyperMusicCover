package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class CapsuleFeedbackStateTest {
    @Test fun enableWritesWordFromTopLeftAndReturnsToLivePlayback() {
        val state = LyricFeedbackState()
        state.begin(true)
        assertEquals(LyricFeedbackState.Frame(), state.at(0))
        val half = state.at(LyricFeedbackState.WORD_DRAW_MS / 2)
        assertEquals(0.5f, half.reveal)
        assertEquals(0f, half.cross)
        val edge = LyricFeedbackGeometry.revealEdge(half.reveal)
        assertTrue(0.1f + 0.1f < edge) // upper-left is already inked
        assertTrue(0.9f + 0.9f > edge) // lower-right still waits
        assertEquals(1f, state.at(LyricFeedbackState.WORD_DRAW_MS).reveal)
        assertEquals(LyricFeedbackState.Frame(), state.at(state.durationMs))
    }

    @Test fun wordRemainsCompletelyDrawnForThreeHundredMilliseconds() {
        val state = LyricFeedbackState()
        state.begin(true)
        val written = LyricFeedbackState.WORD_DRAW_MS
        for (ms in written..written + LyricFeedbackState.WORD_HOLD_MS) {
            val frame = state.at(ms)
            assertEquals(1f, frame.word)
            assertEquals(1f, frame.reveal)
            assertEquals(0f, frame.cross)
            assertEquals(0f, frame.playback)
        }
        assertTrue(state.at(written + LyricFeedbackState.WORD_HOLD_MS + 1).word < 1f)
    }

    @Test fun closingAfterCompletedHintRedrawsWordBeforeDrawingCross() {
        val state = LyricFeedbackState()
        state.begin(true)
        state.at(state.durationMs)
        state.begin(false)
        assertEquals(LyricFeedbackState.Frame(), state.at(0))
        assertEquals(0f, state.at(LyricFeedbackState.CLOSE_WORD_MS / 2).cross)
        assertEquals(1f, state.at(LyricFeedbackState.CLOSE_WORD_MS).reveal)
        val crossed = state.at(LyricFeedbackState.CLOSE_WORD_MS + LyricFeedbackState.CROSS_DRAW_MS)
        assertEquals(1f, crossed.word)
        assertEquals(1f, crossed.cross)
        assertEquals(0f, crossed.playback)
        assertEquals(LyricFeedbackState.Frame(), state.at(state.durationMs))
    }

    @Test fun crossDrawsTwoCompleteStrokesInSequence() {
        assertEquals(0f, LyricFeedbackGeometry.firstCrossStroke(0f))
        assertEquals(0f, LyricFeedbackGeometry.secondCrossStroke(0f))
        assertEquals(0.5f, LyricFeedbackGeometry.firstCrossStroke(0.25f))
        assertEquals(0f, LyricFeedbackGeometry.secondCrossStroke(0.25f))
        assertEquals(1f, LyricFeedbackGeometry.firstCrossStroke(0.75f))
        assertEquals(0.5f, LyricFeedbackGeometry.secondCrossStroke(0.75f))
        assertEquals(1f, LyricFeedbackGeometry.secondCrossStroke(1f))
    }

    @Test fun rapidReversalsContinueFromCurrentGeometryAndLastRequestWins() {
        val state = LyricFeedbackState()
        state.begin(true)
        val partiallyWritten = state.at(100)
        state.begin(false)
        assertEquals(partiallyWritten, state.at(0))
        val partialCross = state.at(LyricFeedbackState.CLOSE_WORD_MS + 80)
        assertTrue(partialCross.cross > 0f)
        state.begin(true)
        assertEquals(partialCross, state.at(0))
        val uncrossing = state.at(100)
        assertTrue(uncrossing.cross < partialCross.cross)
        assertEquals(1f, uncrossing.reveal)
        for (i in 0..30) {
            val previous = state.frame
            state.begin(i % 2 == 0)
            assertEquals(previous, state.at(0))
            state.at(35)
        }
        state.begin(false)
        assertEquals(1f, state.at(500).cross)
        assertEquals(0f, state.frame.playback)
        state.begin(true)
        assertEquals(0f, state.at(500).cross)
        assertEquals(1f, state.frame.word)
        assertEquals(LyricFeedbackState.Frame(), state.at(state.durationMs))
    }

    @Test fun interruptingFadeRestoresFromItsActualOpacityWithoutJumping() {
        val state = LyricFeedbackState()
        for (on in listOf(true, false)) {
            state.begin(on)
            val fading = state.at(state.durationMs - LyricFeedbackState.RESTORE_MS / 2)
            assertEquals(0.5f, fading.playback)
            assertEquals(0.5f, fading.word)
            assertEquals(1f, fading.reveal)
            state.begin(!on)
            assertEquals(fading, state.at(0))
            state.at(200)
        }
    }

    @Test fun clearOrExpiredTimelineLeavesNoDrawingOrCrossOverCurrentPlayback() {
        val state = LyricFeedbackState()
        state.begin(false)
        state.at(500)
        state.clear()
        assertEquals(LyricFeedbackState.Frame(), state.frame)
        state.begin(true)
        assertEquals(LyricFeedbackState.Frame(), state.at(-100))
        assertEquals(LyricFeedbackState.Frame(), state.at(Long.MAX_VALUE))
        state.begin(false)
        assertEquals(LyricFeedbackState.Frame(), state.at(0))
    }

    @Test fun unavailableWritesTheWordBeforeTheQuestionAndHoldsBothForThreeHundredMilliseconds() {
        val state = LyricFeedbackState()
        state.begin(LyricFeedbackState.Mode.UNAVAILABLE)
        val wordAt = LyricFeedbackState.WORD_DRAW_MS
        assertEquals(0.5f, state.at(wordAt / 2).reveal)
        assertEquals(0f, state.frame.question)
        assertEquals(1f, state.at(wordAt).reveal)
        assertEquals(0f, state.frame.question)
        assertEquals(0.5f, state.at(wordAt + LyricFeedbackState.QUESTION_DRAW_MS / 2).question)
        val completed = wordAt + LyricFeedbackState.QUESTION_DRAW_MS
        for (ms in completed..completed + LyricFeedbackState.WORD_HOLD_MS) {
            val frame = state.at(ms)
            assertEquals(1f, frame.word)
            assertEquals(1f, frame.question)
            assertEquals(0f, frame.cross)
            assertEquals(0f, frame.playback)
        }
        assertEquals(LyricFeedbackState.Frame(), state.at(state.durationMs))
    }

    @Test fun pendingNeverDrawsAbsenceAndItsAsyncResultContinuesFromTheActualFrame() {
        val state = LyricFeedbackState()
        state.begin(LyricFeedbackState.Mode.PENDING)
        for (ms in 0L..state.durationMs) {
            assertEquals(0f, state.at(ms).question)
            assertEquals(0f, state.frame.cross)
        }
        state.begin(LyricFeedbackState.Mode.PENDING)
        val partial = state.at(140)
        state.begin(LyricFeedbackState.Mode.UNAVAILABLE)
        assertEquals(partial, state.at(0))
        assertEquals(0f, state.at(LyricFeedbackState.WORD_DRAW_MS).question)
        assertEquals(1f, state.at(LyricFeedbackState.WORD_DRAW_MS + LyricFeedbackState.QUESTION_DRAW_MS).question)
    }

    @Test fun missingQuestionCanBeInterruptedByCloseOrAvailableWithoutResurrectingOldGeometry() {
        val state = LyricFeedbackState()
        state.begin(LyricFeedbackState.Mode.UNAVAILABLE)
        val partial = state.at(LyricFeedbackState.WORD_DRAW_MS + 140L)
        state.begin(false)
        assertEquals(partial, state.at(0))
        assertTrue(state.at(60L).question < partial.question)
        assertEquals(0f, state.at(LyricFeedbackState.CLOSE_WORD_MS).question)
        assertEquals(1f, state.at(500L).cross)
        state.begin(LyricFeedbackState.Mode.UNAVAILABLE)
        state.at(LyricFeedbackState.WORD_DRAW_MS + 140L)
        state.begin(true)
        assertEquals(0f, state.at(LyricFeedbackState.WORD_DRAW_MS).question)
        assertEquals(0f, state.frame.cross)
        state.clear() // identity replacement or view detachment cancels the whole old hint
        assertEquals(LyricFeedbackState.Frame(), state.frame)
        state.begin(LyricFeedbackState.Mode.PENDING)
        assertEquals(LyricFeedbackState.Frame(), state.at(0))
    }

    @Test fun questionDrawsItsHookBeforeItsDotAndPreservesPlaybackDuringFadeInterruption() {
        assertEquals(0f, LyricFeedbackGeometry.questionHook(0f))
        assertEquals(0.5f, LyricFeedbackGeometry.questionHook(0.41f))
        assertEquals(1f, LyricFeedbackGeometry.questionHook(0.82f))
        assertEquals(0f, LyricFeedbackGeometry.questionDot(0.82f))
        assertEquals(1f, LyricFeedbackGeometry.questionDot(1f), 0.00001f)
        val state = LyricFeedbackState()
        state.begin(LyricFeedbackState.Mode.UNAVAILABLE)
        val fading = state.at(state.durationMs - LyricFeedbackState.RESTORE_MS / 2)
        assertEquals(0.5f, fading.word)
        assertEquals(0.5f, fading.playback)
        assertEquals(1f, fading.question)
        state.begin(LyricFeedbackState.Mode.PENDING)
        assertEquals(fading, state.at(0))
    }

    @Test fun navigationMessageIsReplacedBeforeNormalTextReturns() {
        val state = CapsuleTextFeedbackState()
        state.begin("已进入导航界面")
        assertEquals(CapsuleTextFeedbackState.Frame(), state.at(0))
        assertNull(state.at(119).message)
        assertEquals(CapsuleTextFeedbackState.Frame("已进入导航界面", 0f), state.at(120))
        assertEquals("已进入导航界面", state.at(1139).message)
        assertEquals(CapsuleTextFeedbackState.Frame(null, 0f), state.at(1140))
        assertEquals(CapsuleTextFeedbackState.Frame(), state.at(CapsuleTextFeedbackState.DURATION_MS))
    }

    @Test fun smallIslandNeverDrawsDirectionArtworkAndStatusTextTogether() {
        val state = CapsuleTextFeedbackState()
        state.begin("已退出导航界面")
        for (ms in 0L..CapsuleTextFeedbackState.DURATION_MS) {
            val frame = state.at(ms)
            val layers = CapsuleStatusContent.layers(frame, 1f)
            if (layers.message) assertEquals(0f, layers.artworkAlpha)
            else assertEquals(layers.wordsAlpha, layers.artworkAlpha)
        }
        // Restoring the icon uses the same fade as the stacked card's normal text.
        assertEquals(0f, CapsuleStatusContent.layers(CapsuleTextFeedbackState.Frame(null, 0f), 1f).artworkAlpha)
    }

    @Test fun rowMorphAndFeedbackAlphaComposeInsteadOfOverridingEachOther() {
        for (message in listOf(null, "已进入导航界面")) {
            val frame = CapsuleTextFeedbackState.Frame(message, 0.4f)
            val layers = CapsuleStatusContent.layers(frame, 0.5f)
            assertEquals(0.2f, layers.wordsAlpha)
            assertEquals(if (message == null) 0.2f else 0f, layers.artworkAlpha)
            assertEquals(0f, CapsuleStatusContent.layers(frame, 0f).wordsAlpha)
        }
    }

    @Test fun remotePageReadyReplacesPendingFeedbackWithoutResettingDrawnOpacity() {
        val state = CapsuleTextFeedbackState()
        state.begin("正在打开导航界面")
        val pending = state.at(180)
        assertEquals(0.5f, pending.alpha)
        state.begin("已进入导航界面")
        assertEquals(pending, state.at(0))
        assertEquals(pending.copy(alpha = 0.25f), state.at(60))
        assertEquals(CapsuleTextFeedbackState.Frame("已进入导航界面", 0f), state.at(120))
        assertEquals(CapsuleTextFeedbackState.Frame("已进入导航界面", 1f), state.at(240))
    }

    @Test fun repeatedBindingDoesNotRestartOrRevealNormalArtworkDuringFeedback() {
        val state = CapsuleTextFeedbackState()
        state.begin("已进入导航界面")
        val held = state.at(500)
        repeat(20) {
            assertFalse(state.begin("已进入导航界面"))
            assertEquals(held, state.frame)
            assertEquals(0f, CapsuleStatusContent.layers(state.frame, 1f).artworkAlpha)
        }
        assertNull(state.at(CapsuleTextFeedbackState.DURATION_MS).message)
        assertTrue(state.begin("已进入导航界面"))
    }

    @Test fun rapidNavigationReversalsAndClearKeepOnlyTheLatestRequestedResult() {
        val state = CapsuleTextFeedbackState()
        state.begin("已进入导航界面")
        state.at(400)
        repeat(20) { index ->
            val before = state.frame
            state.begin(if (index % 2 == 0) "已退出导航界面" else "已进入导航界面")
            assertEquals(before, state.at(0))
            state.at(60)
        }
        state.begin("已退出导航界面")
        assertEquals("已退出导航界面", state.at(300).message)
        state.clear()
        assertEquals(CapsuleTextFeedbackState.Frame(), state.frame)
        assertEquals(CapsuleTextFeedbackState.Frame(), state.at(300))
    }

    @Test fun roundIslandUsesLegibleNavigationLabelsWithoutRelabellingOtherScenes() {
        assertEquals("导航\n已进入", CapsuleStatusContent.compact("已进入导航界面"))
        assertEquals("导航\n已退出", CapsuleStatusContent.compact("已退出导航界面"))
        assertEquals("导航\n打开中", CapsuleStatusContent.compact("正在打开导航界面"))
        assertEquals("已收起", CapsuleStatusContent.compact("已收起"))
        assertEquals("已打开", CapsuleStatusContent.compact("已打开"))
    }
}
