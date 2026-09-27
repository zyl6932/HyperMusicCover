package com.os4.musiccover

import org.json.JSONObject

/** The card backdrop's settings, kept separate from the island material configuration. */
object CoverFlowConfig {
    data class Settings(
        val enabled: Boolean = false,
        val preset: Int = APPLE,
        val warp: Float = 1f,
        val speed: Float = 1f,
        val blur: Int = 8,
    ) {
        fun json(): String = JSONObject().apply {
            put("enabled", enabled)
            put("preset", preset)
            put("warp", warp.toDouble())
            put("speed", speed.toDouble())
            put("blur", blur)
        }.toString()
    }

    const val APPLE = 0
    const val SOFT = 1
    const val VIVID = 2
    const val BACKUP_KEY = "coverFlow"

    fun preset(index: Int): Settings = when (index) {
        SOFT -> Settings(preset = SOFT, warp = 0.45f, speed = 0.55f, blur = 12)
        VIVID -> Settings(preset = VIVID, warp = 0.9f, speed = 1.4f, blur = 6)
        else -> Settings()
    }

    fun selectPreset(current: Settings, index: Int): Settings = preset(index).copy(enabled = current.enabled)

    fun fromJson(raw: String?): Settings {
        val obj = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        val index = obj.optInt("preset", APPLE).coerceIn(APPLE, VIVID)
        val base = preset(index)
        fun finite(key: String, fallback: Float, min: Float, max: Float): Float =
            runCatching { obj.getDouble(key).toFloat() }.getOrDefault(fallback)
                .takeIf(Float::isFinite)?.coerceIn(min, max) ?: fallback
        return base.copy(
            enabled = obj.optBoolean("enabled", false),
            warp = finite("warp", base.warp, 0f, 1f),
            speed = finite("speed", base.speed, 0.1f, 3f),
            blur = obj.optInt("blur", base.blur).coerceIn(1, 40),
        )
    }

    @JvmStatic fun normalizedJson(raw: String?): String = fromJson(raw).json()
    @JvmStatic fun defaultJson(): String = Settings().json()

    /** Null preserves the current setting when importing a backup from an older version. */
    fun backupValue(backup: JSONObject): String? =
        backup.optJSONObject(BACKUP_KEY)?.toString()?.let(::normalizedJson)
}
