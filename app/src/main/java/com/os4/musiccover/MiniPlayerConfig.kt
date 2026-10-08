// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.SharedPreferences
import org.json.JSONObject

/** Portable settings for the HyperChanger lockscreen mini player. */
object MiniPlayerConfig {
    const val ENABLED = "enabled"
    const val WIDTH = "widthDp"
    const val HEIGHT_RADIUS = "heightRadiusDp"
    const val ART_RADIUS = "artRadiusDp"

    /** The row takes the room a switched-off torch or camera leaves (MiniPlayerRuntime.pillRest). */
    const val ADAPTIVE_WIDTH = "adaptiveWidth"

    /** 屏幕常亮 while a navigation page is up behind the lock screen (ImmersiveHost.holdScreen, #63). */
    const val NAV_KEEP_ON = "navKeepOn"

    /**
     * 勿扰, charging and the like drawn after the date, clear of the pill (DateStatus).
     *
     * Not a setting any more (2026-10-09): it is always on, so it sits with the sizes below and
     * nothing that arrives from anywhere can turn it off. The key stays in the JSON so the state
     * file and [DateStatus] keep reading the same map.
     */
    const val STATUS_AT_DATE = "statusAtDate"

    /** The row lifted off a low under-display fingerprint sensor (MiniPlayerRuntime.fingerprintArea, #66). */
    const val FOD_LIFT = "fodLift"

    private val switches = setOf(ENABLED, ADAPTIVE_WIDTH, NAV_KEEP_ON, FOD_LIFT)

    private val defaults = linkedMapOf<String, Any>(
        ENABLED to false,
        WIDTH to 221f,
        HEIGHT_RADIUS to 27f,
        ART_RADIUS to 12f,
        ADAPTIVE_WIDTH to false,
        NAV_KEEP_ON to false,
        STATUS_AT_DATE to true,
        FOD_LIFT to true,
    )

    @JvmStatic fun defaultJson(): String = normalizedJson(null)

    /**
     * The config as the module will use it: the switches ([ENABLED], [ADAPTIVE_WIDTH],
     * [NAV_KEEP_ON], [FOD_LIFT]) from the input, the three size keys and [STATUS_AT_DATE] always
     * at the values above.
     *
     * The sizes were sliders and are not settings any more - the app has no rows for them - so a
     * config that still carries one is not obeyed, whoever wrote it. They stay in the JSON all
     * the same: the module's runtime, `MiniPlayerGeometry` and the state file all read this map
     * by key, and a missing key would be read as zero rather than as the default wherever a
     * caller used `getDouble` directly.
     *
     * The values themselves were never arbitrary: 221dp is what fits between the two shortcut
     * discs on this screen, and both of the others are held to the pill's own height. See
     * MiniPlayerRuntime, which clamps them again against the room it actually has.
     *
     * [ART_RADIUS] is not read at all: the picture is the small island's circle now, its share of
     * the height, and a corner setting has nothing left to say (2026-09-28). The key stays in the
     * JSON for the reason above.
     */
    @JvmStatic fun normalizedJson(raw: String?): String {
        val input = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        val out = JSONObject()
        defaults.forEach { (key, fallback) ->
            out.put(key, if (key in switches) {
                runCatching { input.getBoolean(key) }.getOrDefault(fallback)
            } else {
                fallback
            })
        }
        return out.toString()
    }

    @JvmStatic fun fromPreferences(prefs: SharedPreferences): String =
        normalizedJson(prefs.getString("config", null))

    @JvmStatic fun apply(prefs: SharedPreferences, raw: String?): String {
        val normalized = normalizedJson(raw)
        prefs.edit().putString("config", normalized).apply()
        return normalized
    }

    /** The original height setting is a shortcut radius, so the visible pill uses its diameter. */
    @JvmStatic fun visibleHeightDp(raw: String?): Float =
        MiniPlayerGeometry.heightDp(JSONObject(normalizedJson(raw))
            .getDouble(HEIGHT_RADIUS).toFloat())
}
