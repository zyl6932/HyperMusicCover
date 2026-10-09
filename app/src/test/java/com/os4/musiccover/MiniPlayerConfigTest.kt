package com.os4.musiccover

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerConfigTest {
    @Test fun aodReductionIsOptInAndSurvivesSettingsRoundTrips() {
        assertFalse(JSONObject(MiniPlayerConfig.normalizedJson(null))
            .getBoolean(MiniPlayerConfig.REDUCE_AOD_UPDATES))
        val saved = JSONObject(MiniPlayerConfig.defaultJson())
            .put(MiniPlayerConfig.REDUCE_AOD_UPDATES, true)
            .put(MiniPlayerConfig.MARQUEE, true)
        val restored = JSONObject(MiniPlayerConfig.normalizedJson(
            MiniPlayerConfig.normalizedJson(saved.toString())))
        assertTrue(restored.getBoolean(MiniPlayerConfig.REDUCE_AOD_UPDATES))
        assertTrue(restored.getBoolean(MiniPlayerConfig.MARQUEE))
        val discarded = JSONObject().put("pauseAodUpdates", true)
        assertFalse(JSONObject(MiniPlayerConfig.normalizedJson(discarded.toString()))
            .getBoolean(MiniPlayerConfig.REDUCE_AOD_UPDATES))
    }

    @Test fun ordinaryNotificationsStayInTheSystemListByDefaultAndAfterUpgrade() {
        assertTrue(JSONObject(MiniPlayerConfig.defaultJson())
            .getBoolean(MiniPlayerConfig.NORMALS_IN_STACK))
        val oldConfig = JSONObject().put(MiniPlayerConfig.ENABLED, true)
            .put(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP, true)
        val upgraded = JSONObject(MiniPlayerConfig.normalizedJson(oldConfig.toString()))
        assertTrue(upgraded.getBoolean(MiniPlayerConfig.NORMALS_IN_STACK))
        assertTrue(upgraded.getBoolean(MiniPlayerConfig.ENABLED))
        // The temporarily disabled grouping preference is kept for switching back later.
        assertTrue(upgraded.getBoolean(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP))
    }

    @Test fun optingIntoOrdinaryIslandsSurvivesRepeatedSettingsReadsAndOtherChanges() {
        val saved = JSONObject(MiniPlayerConfig.defaultJson())
            .put(MiniPlayerConfig.NORMALS_IN_STACK, false).toString()
        val readBack = JSONObject(MiniPlayerConfig.normalizedJson(
            MiniPlayerConfig.normalizedJson(saved)))
        assertFalse(readBack.getBoolean(MiniPlayerConfig.NORMALS_IN_STACK))
        readBack.put(MiniPlayerConfig.NAV_KEEP_ON, true)
        val updated = JSONObject(MiniPlayerConfig.normalizedJson(readBack.toString()))
        assertFalse(updated.getBoolean(MiniPlayerConfig.NORMALS_IN_STACK))
        assertTrue(updated.getBoolean(MiniPlayerConfig.NAV_KEEP_ON))
    }

    @Test fun malformedOrdinaryNotificationPreferenceFallsBackToTheSystemList() {
        val malformed = JSONObject().put(MiniPlayerConfig.NORMALS_IN_STACK, JSONObject())
        assertTrue(JSONObject(MiniPlayerConfig.normalizedJson(malformed.toString()))
            .getBoolean(MiniPlayerConfig.NORMALS_IN_STACK))
    }
}
