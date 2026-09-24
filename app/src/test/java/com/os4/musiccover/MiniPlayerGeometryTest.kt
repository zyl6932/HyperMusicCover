package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerGeometryTest {
    @Test fun originalHeightSettingUsesDiameterWithMinimum() {
        assertEquals(72f, MiniPlayerGeometry.heightDp(36f))
        assertEquals(48f, MiniPlayerGeometry.heightDp(10f))
    }

    @Test fun widthFitsBetweenShortcutsAndHostEdges() {
        assertEquals(230, MiniPlayerGeometry.widthPx(230, 360, 180f, 12))
        val narrow = MiniPlayerGeometry.widthPx(230, 360, 50f, 12)
        assertTrue(narrow <= 88)
        assertTrue(narrow > 0)
    }

    @Test fun pillClearsBothDiscsByTheGap() {
        // Buttons at 57 and 343 on a 400 wide row, discs 72 across, 8 of gap: 99 each side.
        assertEquals(198, MiniPlayerGeometry.clearOfDiscsPx(240, 200f, 57f, 343f, 72f, 8f, 140))
        // Already narrower: untouched.
        assertEquals(160, MiniPlayerGeometry.clearOfDiscsPx(160, 200f, 57f, 343f, 72f, 8f, 140))
        // No room at all: the floor, and the discs are pressed on from the start.
        assertEquals(140, MiniPlayerGeometry.clearOfDiscsPx(240, 200f, 150f, 250f, 72f, 8f, 140))
        // A missing button takes nothing.
        assertEquals(240, MiniPlayerGeometry.clearOfDiscsPx(240, 200f, null, null, 72f, 8f, 140))
    }

    @Test fun pillBesideAnIslandStaysClearOfTheDiscs() {
        // The device filmed: a 477 pill, 162 island, 24 gap, 420 floor, 664 of room - the floor
        // wins and the pair is 606 wide, inside the room.
        assertEquals(420, MiniPlayerGeometry.pillBesideIslandPx(477, 664, 162, 24, 420))
        // Room for less than the floor: the room wins, the pair ends at the room's edge.
        assertEquals(314, MiniPlayerGeometry.pillBesideIslandPx(477, 500, 162, 24, 420))
        // A wide pill gives the island its room out of its own width.
        assertEquals(514, MiniPlayerGeometry.pillBesideIslandPx(700, 900, 162, 24, 420))
        // No room at all: never narrower than a circle; the discs are pressed on instead.
        assertEquals(162, MiniPlayerGeometry.pillBesideIslandPx(477, 250, 162, 24, 420))
    }

}
