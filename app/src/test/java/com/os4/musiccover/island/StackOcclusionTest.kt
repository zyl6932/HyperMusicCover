package com.os4.musiccover.island

import com.os4.musiccover.cover.CoverMorphMotion.Box
import org.junit.Assert.*
import org.junit.Test

class StackOcclusionTest {
    private fun row(key: Int, x: Float = 20f, y: Float = 100f + key * 20f,
                    z: Float = -key.toFloat(), visible: Boolean = true) =
        StackOcclusion.Row(key, z, key, StackOcclusion.Shape(Box(x, y, 300f, 100f), 24f), visible)

    @Test fun anIslandChosenFromTheMiddleOrBottomKeepsItsNativeOccluders() {
        val rows = (0..6).map { row(it) }
        for (selected in rows.indices) {
            val masks = StackOcclusion.covers(rows[selected], rows)
            val expected = rows.take(selected).filter {
                StackOcclusion.overlaps(it.shape.box, rows[selected].shape.box)
            }.map { it.shape }
            assertEquals(expected, masks)
        }
        assertTrue(StackOcclusion.covers(rows.first(), rows).isEmpty())
        assertTrue(StackOcclusion.covers(rows.last(), rows).isNotEmpty())
    }

    @Test fun nativeZBeatsChildOrderAndEqualZFollowsAndroidSiblingDrawing() {
        val first = row(0, z = 0f)
        val second = row(1, z = 0f)
        assertEquals(listOf(second.shape), StackOcclusion.covers(first, listOf(first, second)))
        assertTrue(StackOcclusion.covers(second, listOf(first, second)).isEmpty())
        val raised = second.copy(z = 2f)
        assertEquals(listOf(raised.shape), StackOcclusion.covers(first, listOf(first, raised)))
        assertTrue(StackOcclusion.covers(raised, listOf(first, raised)).isEmpty())
    }

    @Test fun sideBySideIslandsAreNotClippedUntilTheirMovingShapesOverlap() {
        val left = row(1, x = 20f)
        val right = row(0, x = 350f)
        assertTrue(StackOcclusion.covers(left, listOf(left, right)).isEmpty())
        val converging = row(0, x = 250f)
        assertEquals(listOf(converging.shape), StackOcclusion.covers(left, listOf(left, converging)))
        assertEquals(24f, StackOcclusion.covers(left, listOf(left, converging)).single().radius, 0f)
    }

    @Test fun hiddenDetachedAndNonoverlappingRowsCannotPunchHolesInTheIsland() {
        val back = row(3)
        val hidden = row(0, visible = false)
        val elsewhere = row(1, y = 500f)
        val empty = row(2).copy(shape = StackOcclusion.Shape(Box(20f, 150f, 300f, 0f), 24f))
        assertTrue(StackOcclusion.covers(back, listOf(hidden, elsewhere, empty, back)).isEmpty())
        val front = row(0)
        assertEquals(listOf(front.shape), StackOcclusion.covers(back, listOf(front, back)))
        assertTrue(StackOcclusion.covers(back, listOf(back)).isEmpty())
    }

    @Test fun stackingOutlineUsesTheGreaterBottomClipAndHonoursTheMinimumHeight() {
        assertEquals(150f, StackOcclusion.visibleBottom(200f, 30f, 50f, 0f), 0f)
        assertEquals(150f, StackOcclusion.visibleBottom(200f, 50f, 30f, 0f), 0f)
        assertEquals(80f, StackOcclusion.visibleBottom(200f, 30f, 190f, 80f), 0f)
        assertEquals(0f, StackOcclusion.visibleBottom(200f, 0f, 250f, 0f), 0f)
        assertEquals(200f, StackOcclusion.visibleBottom(200f, 0f, 0f, 0f), 0f)
    }

    @Test fun nativeClippingScalesIntoTheMorphWithoutChangingItsPositionOrFullSize() {
        val islandHeight = 54f
        assertEquals(islandHeight, StackOcclusion.morphVisibleHeight(islandHeight, 200f, 100f, 0.3f, 0f), 0f)
        assertEquals(100f, StackOcclusion.morphVisibleHeight(200f, 200f, 100f, 1f, 1f), 0f)
        assertEquals(50f, StackOcclusion.morphVisibleHeight(100f, 200f, 100f, 0.5f, 1f), 0f)
        val moving = StackOcclusion.morphVisibleHeight(127f, 200f, 100f, 0.65f, 0.5f)
        assertEquals(94.5f, moving, 0.0001f)
        assertEquals(0f, StackOcclusion.morphVisibleHeight(20f, 200f, 0f, 1f, 1.02f), 0f)
    }

    @Test fun currentFrameGeometryAndOrderReplaceThePreviousFramesMasks() {
        val back = row(3)
        val moving = row(0)
        assertEquals(1, StackOcclusion.covers(back, listOf(back, moving)).size)
        val next = moving.copy(shape = StackOcclusion.Shape(Box(500f, 100f, 300f, 100f), 24f))
        assertTrue(StackOcclusion.covers(back, listOf(back, next)).isEmpty())
        val returnedBehind = moving.copy(z = -5f)
        assertTrue(StackOcclusion.covers(back, listOf(back, returnedBehind)).isEmpty())
    }

    @Test fun aShortNativeCardCannotShortenTheCapsuleOutlineAndExposeItsTopCorner() {
        val capsuleHeight = 180f
        val nativeHeight = 220f
        val scale = 0.5f
        val clipHeight = StackOcclusion.morphClipHeight(capsuleHeight, nativeHeight, nativeHeight, scale, 0f)
        // The native card is only 110px high here. It still clips against the island's 180px
        // capsule, rather than a smaller 110px capsule whose top corners stick out.
        assertTrue(clipHeight > nativeHeight)
        assertEquals(capsuleHeight, clipHeight * scale, 0f)
        assertEquals(capsuleHeight / 2f, clipHeight * scale / 2f, 0f)
        assertEquals(150f, StackOcclusion.morphClipHeight(220f, 220f, 150f, 1f, 1f), 0f)
    }

    @Test fun switchingTheStackedSeatsChangesHomeOrderWithoutChangingNativeOrder() {
        for (frontNative in listOf(-6f, 0f)) {
            val rearNative = if (frontNative == 0f) -6f else 0f
            assertTrue(StackOcclusion.morphZ(frontNative, 2f, 0f) > StackOcclusion.morphZ(rearNative, 1f, 0f))
            assertEquals(frontNative, StackOcclusion.morphZ(frontNative, 2f, 1f), 0f)
            assertEquals(rearNative, StackOcclusion.morphZ(rearNative, 1f, 1f), 0f)
            var previous = 2f
            for (step in 0..100) {
                val p = step / 100f
                val current = StackOcclusion.morphZ(frontNative, 2f, p)
                assertTrue(current in frontNative..2f)
                assertTrue(current <= previous)
                assertTrue(previous - current < 0.25f)
                previous = current
            }
        }
        assertEquals(-4f, StackOcclusion.morphZ(-4f, null, 0.3f), 0f)
    }

    @Test fun ordinaryAndMorphRowsShareTheSameOcclusionOrderDuringTheSpring() {
        val ordinary = row(0)
        val rearMorph = row(6, y = 140f)
        val frontMorph = row(4, y = 120f)
        assertEquals(listOf(ordinary.shape, frontMorph.shape),
            StackOcclusion.covers(rearMorph, listOf(ordinary, frontMorph, rearMorph)))
        val frontHome = frontMorph.copy(z = StackOcclusion.morphZ(frontMorph.z, 2f, 0f))
        val rearHome = rearMorph.copy(z = StackOcclusion.morphZ(rearMorph.z, 1f, 0f))
        assertEquals(listOf(frontHome.shape), StackOcclusion.covers(rearHome, listOf(ordinary, frontHome, rearHome)))
    }
}
