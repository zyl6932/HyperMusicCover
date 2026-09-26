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

    private val defaults = linkedMapOf<String, Any>(
        ENABLED to false,
        WIDTH to 221f,
        HEIGHT_RADIUS to 27f,
        ART_RADIUS to 12f,
    )

    @JvmStatic fun defaultJson(): String = normalizedJson(null)

    @JvmStatic fun normalizedJson(raw: String?): String {
        val input = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        val out = JSONObject()
        defaults.forEach { (key, fallback) ->
            val value: Any = runCatching {
                when (fallback) {
                    is Boolean -> input.getBoolean(key)
                    is Int -> input.getInt(key)
                    is Float -> input.getDouble(key).toFloat().takeIf(Float::isFinite) ?: fallback
                    else -> fallback
                }
            }.getOrDefault(fallback)
            out.put(key, value)
        }
        fun decimal(key: String, min: Float, max: Float) {
            val value = out.getDouble(key).toFloat()
            out.put(key, if (value.isFinite()) value.coerceIn(min, max) else defaults[key])
        }
        decimal(WIDTH, 160f, 360f)
        decimal(HEIGHT_RADIUS, 24f, 60f)
        decimal(ART_RADIUS, 0f, 60f)
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
