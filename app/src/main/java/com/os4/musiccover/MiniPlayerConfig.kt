// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.SharedPreferences
import org.json.JSONObject

/** Portable settings for the HyperChanger lockscreen mini player. */
object MiniPlayerConfig {
    /** Sent by SystemUI so the settings app can reject an older loaded module. */
    const val SCHEMA_VERSION = 5
    const val ENABLED = "enabled"
    const val WIDTH = "widthDp"
    const val HEIGHT_RADIUS = "heightRadiusDp"
    const val ART_RADIUS = "artRadiusDp"

    /** The row takes the room a switched-off torch or camera leaves (MiniPlayerRuntime.pillRest). */
    const val ADAPTIVE_WIDTH = "adaptiveWidth"
    const val HIDE_AOD_SHORTCUTS = "hideAodShortcuts"
    const val MARQUEE = "marquee"
    const val NOTIFICATION_MATERIAL = "notificationMaterial"
    const val MEDIA_COLLAPSED_DEFAULT = "mediaCollapsedDefault"
    const val SINK_WITH_EXPANDED_BACKGROUND = "sinkWithExpandedBackground"
    const val GROUP_NOTIFICATIONS_BY_APP = "groupNotificationsByApp"
    const val STYLE = "style"
    const val STYLE_ROW = 0
    const val STYLE_STACK = 1
    const val MIN_HEIGHT_DP = 48f
    const val MAX_HEIGHT_DP = 72f
    const val DEFAULT_HEIGHT_DP = 54f

    private val defaults = linkedMapOf<String, Any>(
        ENABLED to false,
        WIDTH to 221f,
        HEIGHT_RADIUS to DEFAULT_HEIGHT_DP / 2f,
        ART_RADIUS to 12f,
        ADAPTIVE_WIDTH to false,
        HIDE_AOD_SHORTCUTS to false,
        MARQUEE to true,
        NOTIFICATION_MATERIAL to false,
        MEDIA_COLLAPSED_DEFAULT to false,
        SINK_WITH_EXPANDED_BACKGROUND to false,
        GROUP_NOTIFICATIONS_BY_APP to false,
        STYLE to STYLE_ROW,
    )

    @JvmStatic fun defaultJson(): String = normalizedJson(null)

    /**
     * The config as the module will use it. The height radius is shared by the island and both
     * shortcut discs; the width and artwork radius remain fixed at their original values.
     *
     * 221dp is the requested pill width before the runtime fits it between the shortcuts.
     *
     * [ART_RADIUS] is not read at all: the picture is the small island's circle now, its share of
     * the height, and a corner setting has nothing left to say (2026-09-28). The key stays in the
     * JSON for the reason above.
     */
    @JvmStatic fun normalizedJson(raw: String?): String {
        val input = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        val out = JSONObject()
        defaults.forEach { (key, fallback) ->
            out.put(key, if (key == HEIGHT_RADIUS) {
                val value = runCatching { input.getDouble(key).toFloat() }.getOrDefault(fallback as Float)
                if (value.isFinite()) value.coerceIn(MIN_HEIGHT_DP / 2f, MAX_HEIGHT_DP / 2f)
                else fallback
            } else if (key == STYLE) {
                runCatching { input.getInt(key) }.getOrDefault(STYLE_ROW)
                    .takeIf { it == STYLE_ROW || it == STYLE_STACK } ?: STYLE_ROW
            } else if (key == ENABLED || key == ADAPTIVE_WIDTH ||
                key == HIDE_AOD_SHORTCUTS || key == MARQUEE || key == NOTIFICATION_MATERIAL ||
                key == MEDIA_COLLAPSED_DEFAULT || key == SINK_WITH_EXPANDED_BACKGROUND ||
                key == GROUP_NOTIFICATIONS_BY_APP) {
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
        // SystemUI may be restarted immediately after a setting changes. Commit the small JSON
        // before acknowledging the broadcast, so a pending async write cannot lose that change.
        check(prefs.edit().putString("config", normalized).commit()) {
            "Could not persist mini player settings"
        }
        return normalized
    }

    /** The stored radius sets both the pill's height and the shortcut glass diameter. */
    @JvmStatic fun visibleHeightDp(raw: String?): Float =
        MiniPlayerGeometry.heightDp(JSONObject(normalizedJson(raw))
            .getDouble(HEIGHT_RADIUS).toFloat())
}
