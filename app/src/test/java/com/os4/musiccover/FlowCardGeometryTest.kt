package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowCardGeometryTest {
    @Test fun translatedAndScaledCardKeepsGlobalFlowCoordinates() {
        val basis = FlowCardGeometry.fromMappedBasis(floatArrayOf(100f, 200f, 102f, 200f, 100f, 201.5f))
        val point = FlowCardGeometry.map(basis, 30f, 40f)
        assertEquals(160f, point[0], 0.0001f)
        assertEquals(260f, point[1], 0.0001f)
    }

    @Test fun skewedCardUsesBothBasisVectors() {
        val basis = FlowCardGeometry.fromMappedBasis(floatArrayOf(7f, 11f, 9f, 12f, 8f, 14f))
        val point = FlowCardGeometry.map(basis, 2f, 3f)
        assertEquals(14f, point[0], 0.0001f)
        assertEquals(22f, point[1], 0.0001f)
    }
}
