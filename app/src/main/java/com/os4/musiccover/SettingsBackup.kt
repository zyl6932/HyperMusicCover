package com.os4.musiccover

import android.content.Context
import org.json.JSONObject

/**
 * Export and import of everything the user has set, which lives in two places.
 *
 * [AppSettings] owns how the app looks. The module's own parameters - where the cover sits, how
 * far the clock collapses, how solid the glass goes - belong to the hook inside SystemUI, and
 * [AppSettings] deliberately keeps no copy of them so there is only ever one truth. That is why
 * exporting has to ask the module for them and importing has to hand them back, rather than
 * reading and writing a local mirror.
 *
 * A file written before the module's values were included still imports: the keys are simply
 * absent and nothing is pushed.
 */
object SettingsBackup {

    private const val KEY_BIAS = "coverBias"
    private const val KEY_CLOCK_SCALE = "clockScale"
    private const val KEY_GLASS_END = "glassEnd"

    suspend fun export(context: Context): String {
        val json = JSONObject(AppSettings.load(context).toJson())
        // With the module not answering there are no real values to write down, and writing the
        // defaults would quietly turn an export into a reset for whoever imports it.
        val module = ModuleBridge.query(context)
        if (module.alive) {
            json.put(KEY_BIAS, module.bias.toDouble())
            json.put(KEY_CLOCK_SCALE, module.clockScale.toDouble())
            json.put(KEY_GLASS_END, module.glassEnd.toDouble())
        }
        return json.toString(2)
    }

    /** Applies both halves. Returns the app settings so the caller can restart the UI with them. */
    fun import(context: Context, json: String): AppSettings {
        val settings = AppSettings.importFromJson(context, json)
        try {
            val obj = JSONObject(json)
            if (obj.has(KEY_BIAS)) ModuleBridge.setBias(context, obj.getDouble(KEY_BIAS).toFloat())
            if (obj.has(KEY_CLOCK_SCALE)) {
                ModuleBridge.setClockScale(context, obj.getDouble(KEY_CLOCK_SCALE).toFloat())
            }
            if (obj.has(KEY_GLASS_END)) {
                ModuleBridge.setGlassEnd(context, obj.getDouble(KEY_GLASS_END).toFloat())
            }
        } catch (_: Exception) {
            // The app half is already applied; a malformed module half is not worth losing it over.
        }
        return settings
    }
}
