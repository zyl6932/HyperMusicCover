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

}
