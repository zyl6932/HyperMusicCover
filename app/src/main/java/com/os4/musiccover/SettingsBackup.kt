package com.os4.musiccover

import android.content.Context
import org.json.JSONObject

/**
 * Export and import of everything the user has set, which lives in two places.
 *
 * [AppSettings] owns how the app looks. The module's own parameters - where the cover sits, how
 * far the clock collapses, how solid the glass goes, what it does to the media card - belong to
 * the hook inside SystemUI, and
 * [AppSettings] deliberately keeps no copy of them so there is only ever one truth. That is why
 * exporting has to ask the module for them and importing has to hand them back, rather than
 * reading and writing a local mirror.
 *
 * A file written before the module's values were included still imports: the keys are simply
 * absent and nothing is pushed.
 */
object SettingsBackup {

    private const val KEY_BIAS = "coverBias"
    private const val KEY_CLOCK_HEIGHT = "clockHeight"
    /** Written by versions that stored the collapse as a scale coefficient; read, never written. */
    private const val KEY_CLOCK_SCALE = "clockScale"
    private const val KEY_CLOCK_RESPONSE = "clockResponse"
    private const val KEY_GLASS_END = "glassEnd"
    private const val KEY_CARD_HIDE_ART = "cardHideArt"
    private const val KEY_CARD_CENTER_TEXT = "cardCenterText"
    private const val KEY_CARD_TITLE_TAP = "cardTitleTap"
    private const val KEY_HIDE_FINGERPRINT = "hideFingerprint"
    private const val KEY_FP_AVOID = "fingerprintAvoid"

    suspend fun export(context: Context): String {
        val json = JSONObject(AppSettings.load(context).toJson())
        // With the module not answering there are no real values to write down, and writing the
        // defaults would quietly turn an export into a reset for whoever imports it.
        val module = ModuleBridge.query(context)
        if (module.alive) {
            json.put(KEY_BIAS, module.bias.toDouble())
            json.put(KEY_CLOCK_HEIGHT, module.clockHeightDp.toDouble())
            json.put(KEY_CLOCK_RESPONSE, module.clockResponse.toDouble())
            json.put(KEY_GLASS_END, module.glassEnd.toDouble())
            json.put(KEY_CARD_HIDE_ART, module.mcHideArt)
            json.put(KEY_CARD_CENTER_TEXT, module.mcCenterText)
            json.put(KEY_CARD_TITLE_TAP, module.mcTitleTap)
            json.put(KEY_HIDE_FINGERPRINT, module.hideFingerprint)
            json.put(KEY_FP_AVOID, module.fpAvoid)
        }
        return json.toString(2)
    }

    /** Applies both halves. Returns the app settings so the caller can restart the UI with them. */
    fun import(context: Context, json: String): AppSettings {
        val settings = AppSettings.importFromJson(context, json)
        try {
            val obj = JSONObject(json)
            if (obj.has(KEY_BIAS)) ModuleBridge.setBias(context, obj.getDouble(KEY_BIAS).toFloat())
            // The old key held a coefficient. A value under the new range is recognised as one
            // of those by the module and converted there against the glyphs actually on screen,
            // which is the only thing that knows what a coefficient of 0.335 was worth.
            val clock = when {
                obj.has(KEY_CLOCK_HEIGHT) -> obj.getDouble(KEY_CLOCK_HEIGHT)
                obj.has(KEY_CLOCK_SCALE) -> obj.getDouble(KEY_CLOCK_SCALE)
                else -> null
            }
            if (clock != null) ModuleBridge.setClockHeight(context, clock.toFloat())
            if (obj.has(KEY_CLOCK_RESPONSE)) {
                ModuleBridge.setClockResponse(context, obj.getDouble(KEY_CLOCK_RESPONSE).toFloat())
            }
            if (obj.has(KEY_GLASS_END)) {
                ModuleBridge.setGlassEnd(context, obj.getDouble(KEY_GLASS_END).toFloat())
            }
            if (obj.has(KEY_CARD_HIDE_ART)) {
                ModuleBridge.setCardHideArt(context, obj.getBoolean(KEY_CARD_HIDE_ART))
            }
            if (obj.has(KEY_CARD_CENTER_TEXT)) {
                ModuleBridge.setCardCenterText(context, obj.getBoolean(KEY_CARD_CENTER_TEXT))
            }
            if (obj.has(KEY_CARD_TITLE_TAP)) {
                ModuleBridge.setCardTitleTap(context, obj.getBoolean(KEY_CARD_TITLE_TAP))
            }
            if (obj.has(KEY_HIDE_FINGERPRINT)) {
                ModuleBridge.setHideFingerprint(context, obj.getBoolean(KEY_HIDE_FINGERPRINT))
            }
            if (obj.has(KEY_FP_AVOID)) {
                ModuleBridge.setFingerprintAvoid(context, obj.getInt(KEY_FP_AVOID))
            }
        } catch (_: Exception) {
            // The app half is already applied; a malformed module half is not worth losing it over.
        }
        return settings
    }
}
