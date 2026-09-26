package com.os4.musiccover

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMaterialConfigTest {
    @Test fun oldConfigKeepsItsGeometryAndSystemMaterial() {
        val config = JSONObject(MiniPlayerConfig.normalizedJson(
            """{"enabled":true,"widthDp":250,"heightRadiusDp":30,"artRadiusDp":9}"""))
        assertTrue(config.getBoolean(MiniPlayerConfig.ENABLED))
        assertEquals(250.0, config.getDouble(MiniPlayerConfig.WIDTH), 0.0)
        assertEquals(30.0, config.getDouble(MiniPlayerConfig.HEIGHT_RADIUS), 0.0)
        assertEquals(9.0, config.getDouble(MiniPlayerConfig.ART_RADIUS), 0.0)
        assertTrue(config.getBoolean(MiniPlayerConfig.SHORTCUT_FOLLOW_ISLAND))
        assertEquals(MiniMaterialStyle.SYSTEM,
            config.getJSONObject(MiniPlayerConfig.ISLAND_MATERIAL).getInt("mode"))
        assertEquals(MiniMaterialStyle.SYSTEM,
            config.getJSONObject(MiniPlayerConfig.SHORTCUT_MATERIAL).getInt("mode"))
    }

    @Test fun profilesStayIndependentAndClampMalformedParameters() {
        val raw = JSONObject().apply {
            put(MiniPlayerConfig.SHORTCUT_FOLLOW_ISLAND, false)
            put(MiniPlayerConfig.ISLAND_MATERIAL, JSONObject().apply {
                put("mode", MiniMaterialStyle.SOFT_GLASS)
                put("softOpacity", 200)
                put("softBackdropBlur", -9)
                put("softGlassBlur", 80)
                put("softLuminance", 2.0)
                put("softColor", 0xFF123456.toInt())
            })
            put(MiniPlayerConfig.SHORTCUT_MATERIAL, JSONObject().apply {
                put("mode", MiniMaterialStyle.PURE)
                put("pureColor", 0x80445566.toInt())
            })
        }
        val normalized = JSONObject(MiniPlayerConfig.normalizedJson(raw.toString()))
        assertFalse(normalized.getBoolean(MiniPlayerConfig.SHORTCUT_FOLLOW_ISLAND))
        val island = normalized.getJSONObject(MiniPlayerConfig.ISLAND_MATERIAL)
        val shortcut = normalized.getJSONObject(MiniPlayerConfig.SHORTCUT_MATERIAL)
        assertEquals(100, island.getInt("softOpacity"))
        assertEquals(0, island.getInt("softBackdropBlur"))
        assertEquals(40, island.getInt("softGlassBlur"))
        assertEquals(0.4, island.getDouble("softLuminance"), 0.00001)
        assertEquals(0xFF123456.toInt(), island.getInt("softColor"))
        assertEquals(MiniMaterialStyle.PURE, shortcut.getInt("mode"))
        assertEquals(0x80445566.toInt(), shortcut.getInt("pureColor"))
        assertEquals(MiniMaterialStyle.SOFT_GLASS, MiniMaterialStyle.fromJson(island).mode)
    }

    @Test fun systemModeKeyTracksOemRecipeAndCustomKeyDoesNot() {
        val system = MiniMaterialStyle.defaults(false)
        val solid = system.copy(mode = MiniMaterialStyle.PURE)
        assertFalse(system.key(1) == system.key(2))
        assertEquals(solid.key(1), solid.key(2))
    }
}
