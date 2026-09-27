package com.os4.musiccover

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowConfigTest {
    @Test fun oldConfigurationRemainsStatic() {
        assertFalse(CoverFlowConfig.fromJson(null).enabled)
        assertFalse(CoverFlowConfig.fromJson("{}").enabled)
        assertEquals(8, CoverFlowConfig.fromJson(null).blur)
    }

    @Test fun presetsResetTheThreeAdjustments() {
        val custom = CoverFlowConfig.Settings(enabled = true, warp = 0.2f, speed = 2f, blur = 35)
        val soft = CoverFlowConfig.selectPreset(custom, CoverFlowConfig.SOFT)
        assertTrue(soft.enabled)
        assertEquals(0.45f, soft.warp, 0f)
        assertEquals(0.55f, soft.speed, 0f)
        assertEquals(12, soft.blur)
        val vivid = CoverFlowConfig.selectPreset(soft, CoverFlowConfig.VIVID)
        assertEquals(0.9f, vivid.warp, 0f)
        assertEquals(1.4f, vivid.speed, 0f)
        assertEquals(6, vivid.blur)
    }

    @Test fun malformedAndOutOfRangeValuesAreClamped() {
        val low = CoverFlowConfig.fromJson("""{"enabled":true,"warp":-3,"speed":0,"blur":0}""")
        assertEquals(0f, low.warp, 0f)
        assertEquals(0.1f, low.speed, 0f)
        assertEquals(1, low.blur)
        val high = CoverFlowConfig.fromJson("""{"warp":8,"speed":90,"blur":99}""")
        assertEquals(1f, high.warp, 0f)
        assertEquals(3f, high.speed, 0f)
        assertEquals(40, high.blur)
    }

    @Test fun backupImportHandlesNewAndOldFiles() {
        assertNull(CoverFlowConfig.backupValue(JSONObject("""{"coverStyle":1}""")))
        val backup = JSONObject().put(CoverFlowConfig.BACKUP_KEY,
            JSONObject("""{"enabled":true,"preset":2,"warp":0.75,"speed":2.2,"blur":7}"""))
        val imported = CoverFlowConfig.fromJson(CoverFlowConfig.backupValue(backup))
        assertTrue(imported.enabled)
        assertEquals(CoverFlowConfig.VIVID, imported.preset)
        assertEquals(0.75f, imported.warp, 0.0001f)
        assertEquals(2.2f, imported.speed, 0.0001f)
        assertEquals(7, imported.blur)
    }
}
