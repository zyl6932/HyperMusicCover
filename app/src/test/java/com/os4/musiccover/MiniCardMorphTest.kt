package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniCardMorphTest {
    private val pill = CoverMorphMotion.Box(240f, 2300f, 720f, 216f)
    private val card = CoverMorphMotion.Box(42f, 1700f, 1116f, 557f)

    private fun assertBox(expected: CoverMorphMotion.Box, actual: CoverMorphMotion.Box) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
        assertEquals(expected.w, actual.w, 0.01f)
        assertEquals(expected.h, actual.h, 0.01f)
    }

    @Test fun containerIsExactlyEachEndAtRest() {
        assertBox(pill, MiniCardMorph.containerFrame(pill, card, 0f))
        assertBox(card, MiniCardMorph.containerFrame(pill, card, 1f))
    }

    @Test fun anOvershootCarriesOnPastTheCardAndOnlyALittleInSize() {
        val over = MiniCardMorph.containerFrame(pill, card, 1.1f)
        // The card is above the pill: past it means further up, along the same straight line.
        assertTrue(over.y < card.y)
        assertEquals(card.cx(), over.cx(), 0.01f)
        assertEquals(card.w * 1.025f + pill.w * -0.025f, over.w, 0.5f)
    }

    @Test fun theRubberBandGivesLessTheHarderItIsPulled() {
        val limit = 100f
        val a = MiniCardMorph.rubber(50f, limit)
        val b = MiniCardMorph.rubber(100f, limit)
        val c = MiniCardMorph.rubber(1000f, limit)
        assertTrue(a > 0f && b > a && c > b && c < limit)
        // Each further pixel of pull buys less than the one before.
        assertTrue((b - a) / 50f > (c - b) / 900f)
        assertEquals(0f, MiniCardMorph.rubber(-5f, limit), 0f)
    }

    @Test fun eachEndIsOnlyItsOwnLiveView() {
        assertEquals(1f, MiniCardMorph.materialOut(0f), 0f)
        assertEquals(1f, MiniCardMorph.earlyOut(0f), 0f)
        assertEquals(1f, MiniCardMorph.pairedOut(0f), 0f)
        assertEquals(0f, MiniCardMorph.nativeIn(0f), 0f)
        assertEquals(0f, MiniCardMorph.materialOut(1f), 0f)
        assertEquals(0f, MiniCardMorph.earlyOut(1f), 0f)
        assertEquals(0f, MiniCardMorph.pairedOut(1f), 0f)
        assertEquals(1f, MiniCardMorph.nativeIn(1f), 0f)
    }

    @Test fun theGlassNeverThinsInTheMiddle() {
        // The mini player's material only fades once the card's is (all but) fully in.
        var p = 0f
        while (p <= 1f) {
            if (MiniCardMorph.materialOut(p) < 1f) assertTrue(MiniCardMorph.nativeIn(p) > 0.95f)
            p += 0.01f
        }
    }

    @Test fun pairedPiecesLandBeforeTheyFade() {
        var p = 0f
        while (p <= 1f) {
            if (MiniCardMorph.pairedOut(p) < 1f) assertEquals(1f, MiniCardMorph.pieceMix(p), 0f)
            p += 0.01f
        }
    }
}
