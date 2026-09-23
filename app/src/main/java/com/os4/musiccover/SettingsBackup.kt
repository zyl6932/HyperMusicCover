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
    private const val KEY_COVER_STYLE = "coverStyle"
    private const val KEY_COVER_CARD_FILL = "coverCardFill"
    private const val KEY_COVER_CARD_POS = "coverCardPos"
    private const val KEY_COVER_CARD_CORNER = "coverCardCorner"
    /** The dp size from before the shares; read, never written. Its margin and offset are dropped. */
    private const val KEY_COVER_CARD_SIZE = "coverCardSizeDp"
    private const val KEY_CLOCK_HEIGHT = "clockHeight"
    /** Written by versions that stored the collapse as a scale coefficient; read, never written. */
    private const val KEY_CLOCK_SCALE = "clockScale"
    /** The size as a fraction of the style's full clock; absent while the slider was never set. */
    private const val KEY_CLOCK_SIZE = "clockSizeFraction"
    private const val KEY_CLOCK_OFFSET = "clockOffset"
    private const val KEY_CLOCK_RESPONSE = "clockResponse"
    private const val KEY_GLASS_END = "glassEnd"
    private const val KEY_CARD_HIDE_ART = "cardHideArt"
    private const val KEY_CARD_ART_IN_LYRICS = "cardArtInLyrics"
    private const val KEY_CARD_TITLE_TAP = "cardTitleTap"
    private const val KEY_HIDE_FINGERPRINT = "hideFingerprint"
    private const val KEY_AOD_SMALL = "aodSmallClock"
    private const val KEY_FORCE_COLON = "forceClockColon"
    private const val KEY_LYRICS = "lyrics"
    private const val KEY_LYRICS_KEEP_ON = "lyricsKeepOn"
    private const val KEY_LYRICS_HDR = "lyricsHdr"
    private const val KEY_LYRICS_TRANS = "lyricsTranslation"
    /** The old dp window offset and gap are not read: what they meant depended on the room. */
    private const val KEY_LYRIC_FILL = "lyricBandFill"
    private const val KEY_LYRIC_POS = "lyricBandPos"
    private const val KEY_LYRIC_SIDE = "lyricSideMarginDp"
    private const val KEY_LYRIC_SIZE = "lyricMainSizeSp"
    private const val KEY_LYRIC_WEIGHT = "lyricMainWeight"
    private const val KEY_FP_AVOID = "fingerprintAvoid"
    /** The whole notification-shade page, as one object keyed the way the module names them. */
    private const val KEY_SHADE = "shade"

    suspend fun export(context: Context): String {
        val json = JSONObject(AppSettings.load(context).toJson())
        // With the module not answering there are no real values to write down, and writing the
        // defaults would quietly turn an export into a reset for whoever imports it.
        val module = ModuleBridge.query(context)
        if (module.alive) {
            json.put(KEY_BIAS, module.bias.toDouble())
            json.put(KEY_COVER_STYLE, module.coverStyle)
            json.put(KEY_COVER_CARD_FILL, module.coverCardFill.toDouble())
            json.put(KEY_COVER_CARD_POS, module.coverCardPos.toDouble())
            json.put(KEY_COVER_CARD_CORNER, module.coverCardCorner.toDouble())
            json.put(KEY_CLOCK_HEIGHT, module.clockHeightDp.toDouble())
            if (module.clockSize > 0f) json.put(KEY_CLOCK_SIZE, module.clockSize.toDouble())
            json.put(KEY_CLOCK_OFFSET, module.clockOffsetDp.toDouble())
            json.put(KEY_CLOCK_RESPONSE, module.clockResponse.toDouble())
            json.put(KEY_GLASS_END, module.glassEnd.toDouble())
            json.put(KEY_CARD_HIDE_ART, module.mcHideArt)
            json.put(KEY_CARD_ART_IN_LYRICS, module.mcArtInLyrics)
            json.put(KEY_CARD_TITLE_TAP, module.mcTitleTap)
            json.put(KEY_HIDE_FINGERPRINT, module.hideFingerprint)
            json.put(KEY_AOD_SMALL, module.aodSmall)
            json.put(KEY_FORCE_COLON, module.forceColon)
            json.put(KEY_LYRICS, module.lyrics)
            json.put(KEY_LYRICS_KEEP_ON, module.lyricsKeepOn)
            json.put(KEY_LYRICS_HDR, module.lyricsHdr)
            json.put(KEY_LYRICS_TRANS, module.lyricsTrans)
            json.put(KEY_LYRIC_FILL, module.lyricFill.toDouble())
            json.put(KEY_LYRIC_POS, module.lyricPos.toDouble())
            json.put(KEY_LYRIC_SIDE, module.lyricSideDp.toDouble())
            json.put(KEY_LYRIC_SIZE, module.lyricSizeSp.toDouble())
            json.put(KEY_LYRIC_WEIGHT, module.lyricWeight)
            json.put(KEY_FP_AVOID, module.fpAvoid)
            // Written whole rather than key by key, because the map is built from the module's
            // own list of keys - this file has no idea what is in it, which is the point.
            if (module.shade.isNotEmpty()) {
                json.put(KEY_SHADE, JSONObject(module.shade as Map<*, *>))
            }
        }
        return json.toString(2)
    }

    /** Applies both halves. Returns the app settings so the caller can restart the UI with them. */
    fun import(context: Context, json: String): AppSettings {
        val settings = AppSettings.importFromJson(context, json)
        try {
            val obj = JSONObject(json)
            if (obj.has(KEY_BIAS)) ModuleBridge.setBias(context, obj.getDouble(KEY_BIAS).toFloat())
            if (obj.has(KEY_COVER_CARD_FILL)) {
                ModuleBridge.setCoverStyle(context, "fill", obj.getDouble(KEY_COVER_CARD_FILL).toFloat())
            } else if (obj.has(KEY_COVER_CARD_SIZE)) {
                // Only the old slider's top translates without the room: it meant "as big as fits".
                val fill = if (obj.getDouble(KEY_COVER_CARD_SIZE) >= 420.0) 1f else 0.8f
                ModuleBridge.setCoverStyle(context, "fill", fill)
            }
            if (obj.has(KEY_COVER_CARD_POS)) {
                ModuleBridge.setCoverStyle(context, "pos", obj.getDouble(KEY_COVER_CARD_POS).toFloat())
            }
            if (obj.has(KEY_COVER_CARD_CORNER)) {
                ModuleBridge.setCoverStyle(context, "corner", obj.getDouble(KEY_COVER_CARD_CORNER).toFloat())
            }
            ModuleBridge.setCoverStyle(context, "mode",
                if (obj.has(KEY_COVER_STYLE)) obj.getInt(KEY_COVER_STYLE).toFloat() else 0f)
            // The old key held a coefficient. A value under the new range is recognised as one
            // of those by the module and converted there against the glyphs actually on screen,
            // which is the only thing that knows what a coefficient of 0.335 was worth.
            val clock = when {
                obj.has(KEY_CLOCK_HEIGHT) -> obj.getDouble(KEY_CLOCK_HEIGHT)
                obj.has(KEY_CLOCK_SCALE) -> obj.getDouble(KEY_CLOCK_SCALE)
                else -> null
            }
            if (clock != null) ModuleBridge.setClockHeight(context, clock.toFloat())
            if (obj.has(KEY_CLOCK_SIZE)) {
                ModuleBridge.setClockSize(context, obj.getDouble(KEY_CLOCK_SIZE).toFloat())
            }
            if (obj.has(KEY_CLOCK_OFFSET)) {
                ModuleBridge.setClockOffset(context, obj.getDouble(KEY_CLOCK_OFFSET).toFloat())
            }
            if (obj.has(KEY_CLOCK_RESPONSE)) {
                ModuleBridge.setClockResponse(context, obj.getDouble(KEY_CLOCK_RESPONSE).toFloat())
            }
            if (obj.has(KEY_GLASS_END)) {
                ModuleBridge.setGlassEnd(context, obj.getDouble(KEY_GLASS_END).toFloat())
            }
            if (obj.has(KEY_CARD_HIDE_ART)) {
                ModuleBridge.setCardHideArt(context, obj.getBoolean(KEY_CARD_HIDE_ART))
            }
            if (obj.has(KEY_CARD_ART_IN_LYRICS)) {
                ModuleBridge.setCardArtInLyrics(context, obj.getBoolean(KEY_CARD_ART_IN_LYRICS))
            }
            if (obj.has(KEY_CARD_TITLE_TAP)) {
                ModuleBridge.setCardTitleTap(context, obj.getBoolean(KEY_CARD_TITLE_TAP))
            }
            if (obj.has(KEY_HIDE_FINGERPRINT)) {
                ModuleBridge.setHideFingerprint(context, obj.getBoolean(KEY_HIDE_FINGERPRINT))
            }
            if (obj.has(KEY_AOD_SMALL)) {
                ModuleBridge.setAodSmall(context, obj.getBoolean(KEY_AOD_SMALL))
            }
            if (obj.has(KEY_FORCE_COLON)) {
                ModuleBridge.setForceColon(context, obj.getBoolean(KEY_FORCE_COLON))
            }
            if (obj.has(KEY_LYRICS)) {
                ModuleBridge.setLyrics(context, obj.getBoolean(KEY_LYRICS))
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
            if (obj.has(KEY_LYRIC_FILL)) {
                ModuleBridge.setLyricStyle(context, "fill", obj.getDouble(KEY_LYRIC_FILL).toFloat())
            }
            if (obj.has(KEY_LYRIC_POS)) {
                ModuleBridge.setLyricStyle(context, "pos", obj.getDouble(KEY_LYRIC_POS).toFloat())
            }
            if (obj.has(KEY_LYRIC_SIDE)) {
                ModuleBridge.setLyricStyle(context, "side", obj.getDouble(KEY_LYRIC_SIDE).toFloat())
            }
            if (obj.has(KEY_LYRIC_SIZE)) {
                ModuleBridge.setLyricStyle(context, "size", obj.getDouble(KEY_LYRIC_SIZE).toFloat())
            }
            if (obj.has(KEY_LYRIC_WEIGHT)) {
                ModuleBridge.setLyricStyle(context, "weight", obj.getDouble(KEY_LYRIC_WEIGHT).toFloat())
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
        } catch (_: Exception) {
            // The app half is already applied; a malformed module half is not worth losing it over.
        }
        return settings
    }
}
