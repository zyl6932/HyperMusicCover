package com.os4.musiccover.island

import com.os4.musiccover.cover.CoverMorphMotion.Box
import org.junit.Assert.*
import org.junit.Test

class SpringSpreadPlanTest {
    private class Row(val visibility: Int, val child: Row? = null)

    private fun plan(rows: List<Row>, owned: List<Row> = emptyList()) =
        SpringSpreadPlan.rows(rows, { it.visibility == 8 }, owned) { parent, child ->
            parent === child || parent.child === child
        }

    @Test fun hiddenRowsBeyondTheThirdStillParticipateInTheSameOrder() {
        val rows = List(7) { Row(if (it < 3) 0 else 4) }
        val result = plan(rows + Row(8))
        assertEquals(rows, result.map { it.view })
        assertTrue(result.all { it.animate })
        assertEquals(0.35f, result.first().response, 0.0001f)
        assertEquals(0.45f, result.last().response, 0.0001f)
        // Becoming visible must not renumber or suddenly enroll the later rows.
        val unfolded = plan(List(7) { Row(0) })
        assertEquals(result.map { it.response }, unfolded.map { it.response })
    }

    @Test fun theIslandCanOwnAnyRowWithoutExemptingTheFirstNotification() {
        val rows = List(7) { Row(if (it < 3) 0 else 4) }
        for (selected in rows.indices) {
            val result = plan(rows, listOf(rows[selected]))
            assertEquals(listOf(rows[selected]), result.filterNot { it.animate }.map { it.view })
            assertEquals(rows.filterIndexed { index, _ -> index != selected },
                result.filter { it.animate }.map { it.view })
        }
    }

    @Test fun groupedChildrenAndRowsAddedDuringLayoutAreResolvedByIdentity() {
        val child = Row(4)
        val rows = listOf(Row(0), Row(4, child), Row(4))
        val initial = plan(rows, listOf(child))
        assertEquals(2, initial.count { it.animate })
        assertFalse(initial[1].animate)
        val late = Row(4)
        val updated = plan(rows + late, listOf(child))
        assertEquals(rows + late, updated.map { it.view })
        assertTrue(updated.last().animate)
        assertFalse(updated[1].animate)
    }

    @Test fun anAlreadyOpenCardAndTheSelectedIslandKeepTheirOwnOwners() {
        val rows = List(7) { Row(if (it < 3) 0 else 4) }
        val result = plan(rows, listOf(rows[1], rows[5]))
        assertEquals(listOf(rows[1], rows[5]), result.filterNot { it.animate }.map { it.view })
        assertEquals(5, result.count { it.animate })
        assertTrue(result.first().animate)
        assertTrue(result.last().animate)
    }

    @Test fun collapsedCardsFitBothDimensionsOfTheTouchedIsland() {
        for (island in listOf(Box(100f, 700f, 221f, 54f), Box(360f, 660f, 54f, 54f))) {
            for (height in listOf(80f, 120f, 240f, 600f)) {
                val from = SpringSpreadPlan.foldedBox(island, 380f, height)!!
                assertTrue(from.w <= island.w + 0.001f)
                assertTrue(from.h <= island.h + 0.001f)
                assertTrue(from.w < 380f)
                assertEquals(island.cx(), from.cx(), 0.0001f)
                assertEquals(island.cy(), from.cy(), 0.0001f)
                assertEquals(from.w / 380f, from.h / height, 0.0001f)
                val p = IslandSpringMotion.progress(0.1f, 0.4f)
                val growingWidth = from.w + (380f - from.w) * p
                assertTrue(growingWidth > from.w && growingWidth < 380f)
            }
        }
    }

    @Test fun unmeasuredRowsWaitForGeometryInsteadOfUsingAFullSizeStart() {
        val island = Box(100f, 700f, 221f, 54f)
        assertNull(SpringSpreadPlan.foldedBox(island, 0f, 100f))
        assertNull(SpringSpreadPlan.foldedBox(island, 380f, 0f))
        val ready = SpringSpreadPlan.foldedBox(island, 380f, 100f)!!
        assertEquals(54f, ready.h, 0.0001f)
        assertEquals(205.2f, ready.w, 0.001f)
    }

    @Test fun aSecondGestureCanCatchTheOriginalIslandHomeDuringItsLift() {
        val lifted = Box(100f, 660f, 221f, 54f)
        assertTrue(SpringSpreadPlan.catchesOrigin(lifted, 40f, 200f, 740f))
        assertTrue(SpringSpreadPlan.catchesOrigin(lifted, 40f, 200f, 680f))
        assertFalse(SpringSpreadPlan.catchesOrigin(lifted, 40f, 90f, 700f))
        assertFalse(SpringSpreadPlan.catchesOrigin(lifted, 40f, 200f, 800f))
    }

    @Test fun foldedNotificationOutlinesFitInsideTheCapsuleNotJustItsBoundingBox() {
        val island = Box(100f, 700f, 240f, 60f)
        fun insideCapsule(x: Float, y: Float): Boolean {
            val r = island.h / 2f
            val cx = x.coerceIn(island.x + r, island.x + island.w - r)
            val dx = x - cx
            val dy = y - island.cy()
            return dx * dx + dy * dy <= r * r + 0.01f
        }
        for (height in listOf(70f, 100f, 190f, 500f)) {
            val nativeWidth = 380f
            val box = SpringSpreadPlan.foldedBox(island, nativeWidth, height)!!
            val scale = box.w / nativeWidth
            val radius = SpringSpreadPlan.cornerRadius(nativeWidth, height, 16f, 0f) * scale
            assertEquals(minOf(box.w, box.h) / 2f, radius, 0.0001f)
            // Sample the entire curved boundary. A small native rectangle radius fails near
            // either capsule end even when the width and height both fit the island.
            for (corner in 0..3) for (step in 0..90) {
                val left = corner == 1 || corner == 2
                val top = corner >= 2
                val cx = if (left) box.x + radius else box.x + box.w - radius
                val cy = if (top) box.y + radius else box.y + box.h - radius
                val angle = Math.toRadians((corner * 90 + step).toDouble())
                assertTrue(insideCapsule(cx + radius * kotlin.math.cos(angle).toFloat(),
                    cy + radius * kotlin.math.sin(angle).toFloat()))
            }
        }
    }

    @Test fun closingRadiusAndReversalShareTheSamePoseAndRestoreTheNativeCorner() {
        val width = 380f
        val height = 120f
        val radius = 24f
        assertEquals(radius, SpringSpreadPlan.cornerRadius(width, height, radius, 1f), 0f)
        assertEquals(60f, SpringSpreadPlan.cornerRadius(width, height, radius, -0.02f), 0f)
        assertEquals(radius, SpringSpreadPlan.cornerRadius(width, height, radius, 1.02f), 0f)
        val pose = SpringSpreadState(1f).also { it.aim(false) }
        for (t in 0L..180L step 10) pose.advance(t)
        val closing = SpringSpreadPlan.cornerRadius(width, height, radius, pose.position.value)
        assertTrue(closing > radius)
        pose.aim(true)
        assertEquals(closing, SpringSpreadPlan.cornerRadius(width, height, radius, pose.position.value), 0f)
        for (t in 190L..1800L step 10) pose.advance(t)
        assertEquals(radius, SpringSpreadPlan.cornerRadius(width, height, radius, pose.position.value), 0f)
    }

}
