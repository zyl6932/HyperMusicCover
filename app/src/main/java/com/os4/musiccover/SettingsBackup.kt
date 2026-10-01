package com.os4.musiccover

import android.content.Context
import org.json.JSONObject

/**
 * Export and import of everything the user has set, which lives in two places.
 *
 * [AppSettings] owns how the app looks. The module's own parameters - where the cover sits, what
 * it does to the card and to the fingerprint, which of the lyric options are on - belong to the
 * hook inside SystemUI, and
 * [AppSettings] deliberately keeps no copy of them so there is only ever one truth. That is why
 * exporting has to ask the module for them and importing has to hand them back, rather than
 * reading and writing a local mirror.
 *
 * A file written before the module's values were included still imports: the keys are simply
 * absent and nothing is pushed. One written after the settings were trimmed imports too, minus
 * everything that is no longer settable - see the note in export.
 */
object SettingsBackup {

    private const val KEY_BIAS = "coverBias"
    private const val KEY_COVER_STYLE = "coverStyle"
    /** The size as a fraction of the style's full clock; the same key the trimmed builds dropped. */
    private const val KEY_CLOCK_SIZE = "clockSizeFraction"
    private const val KEY_CARD_TITLE_TAP = "cardTitleTap"
    private const val KEY_HIDE_FINGERPRINT = "hideFingerprint"
    private const val KEY_FORCE_COLON = "forceClockColon"
    private const val KEY_LYRICS_KEEP_ON = "lyricsKeepOn"
    private const val KEY_LYRICS_HDR = "lyricsHdr"
    private const val KEY_LYRICS_TRANS = "lyricsTranslation"
    private const val KEY_FP_AVOID = "fingerprintAvoid"
    /** The whole notification-shade page, as one object keyed the way the module names them. */
    private const val KEY_SHADE = "shade"
    private const val KEY_MINI = "lockscreenMiniPlayer"

    suspend fun export(context: Context): String {
        val json = JSONObject(AppSettings.load(context).toJson())
        // With the module not answering there are no real values to write down, and writing the
        // defaults would quietly turn an export into a reset for whoever imports it.
        val module = ModuleBridge.query(context)
        if (module.alive) {
            // The settings that are no longer settings are not written, and not read back on
            // import either: the square's size and place, the clock's offset, the glass
            // end, the spring, the two media card switches, the small AOD clock, the lyrics
            // switch and the five values the lyric band and its type were. Each is fixed where it
            // is used now, so a backup that carries one is a backup of something that can no
            // longer be set - see Main and LyricStyle. The keys are gone from this file rather
            // than kept for compatibility: importing a value nothing can hold is the one outcome
            // worse than not importing it.
            json.put(KEY_BIAS, module.bias.toDouble())
            json.put(KEY_COVER_STYLE, module.coverStyle)
            if (module.clockSize > 0f) json.put(KEY_CLOCK_SIZE, module.clockSize.toDouble())
            json.put(KEY_CARD_TITLE_TAP, module.mcTitleTap)
            json.put(KEY_HIDE_FINGERPRINT, module.hideFingerprint)
            json.put(KEY_FORCE_COLON, module.forceColon)
            json.put(KEY_LYRICS_KEEP_ON, module.lyricsKeepOn)
            json.put(KEY_LYRICS_HDR, module.lyricsHdr)
            json.put(KEY_LYRICS_TRANS, module.lyricsTrans)
            json.put(KEY_FP_AVOID, module.fpAvoid)
            // Written whole rather than key by key, because the map is built from the module's
            // own list of keys - this file has no idea what is in it, which is the point.
            if (module.shade.isNotEmpty()) {
                json.put(KEY_SHADE, JSONObject(module.shade as Map<*, *>))
            }
            json.put(KEY_MINI, JSONObject(module.miniConfig))
        }
        return json.toString(2)
    }

    /** Applies both halves. Returns the app settings so the caller can restart the UI with them. */
    fun import(context: Context, json: String): AppSettings {
        val settings = AppSettings.importFromJson(context, json)
        try {
            val obj = JSONObject(json)
            if (obj.has(KEY_BIAS)) ModuleBridge.setBias(context, obj.getDouble(KEY_BIAS).toFloat())
            // Only the mode. The square's size, place and corners are fixed, so a file that still
            // carries coverCardFill, coverCardPos, coverCardCorner or the dp coverCardSizeDp from
            // before them is not read for any of it - the module holds them where they are.
            // Only when the file has one: a file without the module's half (exported while the
            // module did not answer, or from before it was included) used to reset it to FULL.
            if (obj.has(KEY_COVER_STYLE)) {
                ModuleBridge.setCoverStyle(context, "mode", obj.getInt(KEY_COVER_STYLE).toFloat())
            }
            if (obj.has(KEY_CLOCK_SIZE)) {
                ModuleBridge.setClockSize(context, obj.getDouble(KEY_CLOCK_SIZE).toFloat())
            }
            if (obj.has(KEY_CARD_TITLE_TAP)) {
                ModuleBridge.setCardTitleTap(context, obj.getBoolean(KEY_CARD_TITLE_TAP))
            }
            if (obj.has(KEY_HIDE_FINGERPRINT)) {
                ModuleBridge.setHideFingerprint(context, obj.getBoolean(KEY_HIDE_FINGERPRINT))
            }
            if (obj.has(KEY_FORCE_COLON)) {
                ModuleBridge.setForceColon(context, obj.getBoolean(KEY_FORCE_COLON))
            }
            if (obj.has(KEY_LYRICS_HDR)) {
                ModuleBridge.setLyricsHdr(context, obj.getBoolean(KEY_LYRICS_HDR))
            }
            if (obj.has(KEY_LYRICS_KEEP_ON)) {
                ModuleBridge.setLyricsKeepOn(context, obj.getBoolean(KEY_LYRICS_KEEP_ON))
            }
            if (obj.has(KEY_LYRICS_TRANS)) {
                ModuleBridge.setLyricsTrans(context, obj.getBoolean(KEY_LYRICS_TRANS))
            }
            if (obj.has(KEY_FP_AVOID)) {
                ModuleBridge.setFingerprintAvoid(context, obj.getInt(KEY_FP_AVOID))
            }
            // EVERY key in the object, not a chosen few. A restore that puts back some of the
            // shade page and leaves the rest at whatever this device happened to have is worse
            // than one that puts back none of it: the result is a combination nobody chose and
            // nothing on screen says so.
            if (obj.has(KEY_SHADE)) {
                val shade = obj.getJSONObject(KEY_SHADE)
                for (key in shade.keys()) {
                    ModuleBridge.setShade(context, key, shade.getInt(key))
                }
            }
            if (obj.has(KEY_MINI)) {
                ModuleBridge.setMiniConfig(context, obj.getJSONObject(KEY_MINI).toString())
            }
        } catch (_: Exception) {
            // The app half is already applied; a malformed module half is not worth losing it over.
        }
        return settings
    }
}
