package com.os4.musiccover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The app's only channel to the hook, which lives inside SystemUI's process.
 *
 * There is no shared storage between the two - the module writes its state into SystemUI's own
 * files dir - so the app does not try to read settings from disk. It asks. The same broadcast
 * protocol the adb probe uses carries the commands, and a query goes out as an *ordered*
 * broadcast so the module can answer in the result extras.
 *
 * A reply arriving at all is also how the app knows the module is loaded: no reply within the
 * timeout means LSPosed has not injected it (or SystemUI has not been restarted since).
 */
object ModuleBridge {

    private const val ACTION = "com.os4.musiccover.PROBE"
    private const val TARGET = "com.android.systemui"
    private const val QUERY_TIMEOUT_MS = 1500L

    data class State(
        val alive: Boolean = false,
        val cover: Boolean = false,
        val auto: Boolean = false,
        val bias: Float = 0.34f,
        val coverStyle: Int = 0,
        /** The square's side as a share of the room between the clock and the media card. */
        val coverCardFill: Float = 0.8f,
        /** Where the square sits in the height it leaves: 0 top, 0.5 centre, 1 bottom. */
        val coverCardPos: Float = 0.5f,
        /** How round the square's corners are: 0 square, 1 a circle. */
        val coverCardCorner: Float = 0.12f,
        /**
         * How tall the collapsed clock's digits are, in dp.
         *
         * A height rather than the coefficient this used to be. The coefficient multiplied each
         * clock style's own glyph box and those differ by more than ten times over, so one
         * number was a different clock on every style - and a different one again on a
         * different screen. A height is the same clock everywhere: the module divides it by the
         * glyphs it measures and scales by what comes out.
         */
        val clockHeightDp: Float = 36f,
        /**
         * The collapsed clock's size as a fraction of the style's full clock, 1 = unchanged.
         * The module reports what the clock is at even before the slider sets it; 0 = unknown.
         */
        val clockSize: Float = 0f,
        /** How far the date and clock are moved together, in dp. Positive is down. */
        val clockOffsetDp: Float = 0f,
        /**
         * The cover transition's spring response, in seconds - how long the clock takes to
         * travel. Larger is slower.
         *
         * miuix's own unit for a spring: the module derives stiffness and damping from it, so
         * the number means the same thing on every style and screen, and the card and wallpaper
         * fades are derived from it too. 0.38 is the OEM's own preset for this transition.
         */
        val clockResponse: Float = 0.38f,
        val glassEnd: Float = 0.75f,
        val cardShowing: Boolean = false,
        val lockWallpaperOk: Boolean = false,
        /**
         * Whether the clock style loaded right now has a glass channel at all.
         *
         * The liquid-glass morph is AllInOneBase.updateGlassValue(float) and it exists exactly
         * where the glass shader does: the all_in_one family. The rhombus, doodle, oriental and
         * magazine clocks draw vector digits, bitmaps and plain text, and there is nothing on
         * them for the slider to drive. Reported by the module rather than guessed here,
         * because which views carry the channel is the module's business.
         */
        val clockHasGlass: Boolean = false,
        val track: String = "",
        val player: String = "",
        val mcHideArt: Boolean = false,
        /** With [mcHideArt] on, show the thumbnail anyway while the lyrics are up. */
        val mcArtInLyrics: Boolean = false,
        val mcTitleTap: Boolean = false,
        val hideFingerprint: Boolean = false,
        /**
         * Keep cover mode's small clock in the full-screen always-on display, instead of letting
         * it grow back into the OEM's own AOD clock.
         */
        val aodSmall: Boolean = false,
        /** Draw the big clock's colon on the styles that drop it. */
        val forceColon: Boolean = false,
        /** Lock screen lyrics, between the collapsed clock and the card. */
        val lyrics: Boolean = false,
        /** Keep the screen lit while lock screen lyrics are playing. */
        val lyricsKeepOn: Boolean = false,
        /** Draw the singing words brighter than white on an HDR screen. */
        val lyricsHdr: Boolean = false,
        /** Draw each line's translation under it. On unless the user turns it off. */
        val lyricsTrans: Boolean = true,
        /** The lyric band's height as a share of the room between the clock and the card. */
        val lyricFill: Float = 1f,
        /** Where the band sits in the height it leaves: 0 top, 0.5 centre, 1 bottom. */
        val lyricPos: Float = 0.5f,
        val lyricSideDp: Float = 30f,
        val lyricSizeSp: Float = 25f,
        val lyricWeight: Int = 600,
        /**
         * A session has actually carried its own lyric since SystemUI started.
         *
         * Not the same question as whether a provider module is installed, and the difference is
         * the one worth showing the user: LyricInfo is an LSPosed module, and one that is
         * installed but not enabled - or enabled without the player in its scope - writes
         * nothing while still being in the package list.
         */
        val sessionLyric: Boolean = false,
        /** 0 system default, 1 never avoid the fingerprint icon, 2 always avoid it. */
        val fpAvoid: Int = 0,
        /**
         * The notification-shade settings, keyed exactly as the module's own CFG_KEYS.
         *
         * Read out of the reply by PREFIX rather than field by field. The module generates its
         * half of this from one list - the same list its state file and its query reply are
         * built from - so enumerating the bundle here means a knob added on that side appears in
         * this map with nothing to change in this file. What the page does with each key is a
         * table in the UI; what it means is the module's business.
         */
        val shade: Map<String, Int> = emptyMap(),
        val geometry: Geometry = Geometry(),
    ) {
        /** "Artist - Title" out of the module's packageName|song|artist key. */
        val trackLabel: String
            get() {
                val parts = track.split("|")
                if (parts.size < 3) return ""
                val song = parts[1].takeIf { it.isNotBlank() && it != "null" } ?: return ""
                val artist = parts[2].takeIf { it.isNotBlank() && it != "null" }
                return if (artist == null) song else "$song — $artist"
            }
    }

    /**
     * The lock screen's own measurements, in screen pixels, so the preview on the features page
     * can be drawn to scale.
     *
     * None of it is knowable from this side: the media card and the clock belong to SystemUI,
     * every number is device- and clock-style-specific, and the card's position in particular is
     * only meaningful while the keyguard is up. So the module measures and reports, and anything
     * it has not measured yet comes back as zero for the preview to substitute for.
     */
    data class Geometry(
        val screenW: Int = 0,
        val screenH: Int = 0,
        val cardL: Int = 0,
        val cardT: Int = 0,
        val cardW: Int = 0,
        val cardH: Int = 0,
        /**
         * The collapsed clock's glyph box at scale 1, padded by [clockPad] on every side, and
         * the screen y the GLYPHS' top is pinned to - the phone pivots the collapse there, so
         * the pad sits above it and has to be scaled off with everything else.
         */
        val clockW: Float = 0f,
        val clockH: Float = 0f,
        val clockY: Float = 0f,
        val clockPad: Float = 0f,
        /**
         * The box's left at scale 1, and the x the phone scales about. Not always the middle of
         * the screen: the classic clock style is left-aligned under a left-aligned date, and it
         * has to stay left as it shrinks.
         */
        val clockX: Float = 0f,
        val clockPivotX: Float = 0f,
        /**
         * How many times the captured glyphs must be scaled to be the style's full clock - more
         * than 1 when the OEM is squeezing them. 0 = not reported.
         */
        val clockFull: Float = 0f,
    ) {
        val hasScreen: Boolean get() = screenW > 0 && screenH > 0
        val hasCard: Boolean get() = cardW > 0 && cardH > 0
        val hasClock: Boolean get() = clockW > 0f && clockH > 0f
    }

    private fun intent(op: String): Intent = Intent(ACTION).apply {
        setPackage(TARGET)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        putExtra("op", op)
    }

    fun send(context: Context, op: String, extras: Intent.() -> Unit = {}) {
        context.applicationContext.sendBroadcast(intent(op).apply(extras))
    }

    fun setAuto(context: Context, on: Boolean) = send(context, "auto") { putExtra("on", on) }

    fun setCover(context: Context, on: Boolean) = send(context, "pushart") { putExtra("on", on) }

    fun setBias(context: Context, v: Float) = send(context, "bias") { putExtra("v", v) }

    fun setCoverStyle(context: Context, key: String, value: Float) =
        send(context, "coverstyle") {
            putExtra("key", key)
            putExtra("v", value)
        }


    fun setClockHeight(context: Context, dp: Float) =
        send(context, "clockscale") { putExtra("v", dp) }

    fun setClockSize(context: Context, fraction: Float) =
        send(context, "clocksize") { putExtra("v", fraction) }

    fun setClockOffset(context: Context, dp: Float) =
        send(context, "clockoffset") { putExtra("v", dp) }

    fun setGlassEnd(context: Context, v: Float) = send(context, "glassend") { putExtra("v", v) }

    fun setClockResponse(context: Context, seconds: Float) =
        send(context, "clockspring") { putExtra("v", seconds) }

    fun setCardArtInLyrics(context: Context, on: Boolean) =
        send(context, "mediacard") { putExtra("lyricart", on) }

    fun setCardHideArt(context: Context, on: Boolean) =
        send(context, "mediacard") { putExtra("hideart", on) }

    fun setCardTitleTap(context: Context, on: Boolean) =
        send(context, "mediacard") { putExtra("titletap", on) }

    // Its own op rather than a third extra on "mediacard": the switch sits under the card
    // options on screen, but it has nothing to do with the card, and grouping it there on the
    // wire would make moving it later a protocol change.
    fun setHideFingerprint(context: Context, on: Boolean) =
        send(context, "hidefp") { putExtra("on", on) }

    fun setAodSmall(context: Context, on: Boolean) =
        send(context, "aodclock") { putExtra("small", on) }

    fun setForceColon(context: Context, on: Boolean) =
        send(context, "colon") { putExtra("on", on) }

    fun setLyrics(context: Context, on: Boolean) =
        send(context, "lyrics") { putExtra("on", on) }

    fun setLyricsHdr(context: Context, on: Boolean) =
        send(context, "lyrichdr") { putExtra("on", on) }

    fun setLyricsKeepOn(context: Context, on: Boolean) =
        send(context, "lyrickeep") { putExtra("on", on) }

    fun setLyricsTrans(context: Context, on: Boolean) =
        send(context, "lyrictrans") { putExtra("on", on) }

    /** The module validates every value, including imported settings and adb broadcasts. */
    fun setLyricStyle(context: Context, key: String, value: Float) =
        send(context, "lyricstyle") {
            putExtra("key", key)
            putExtra("v", value)
        }

    fun setFingerprintAvoid(context: Context, mode: Int) =
        send(context, "fpavoid") { putExtra("mode", mode) }

    /**
     * One shade setting, by the module's own key.
     *
     * A single op for the whole page rather than one per setting, so this side and the module
     * cannot drift. The key is matched and the value clamped inside ShadeLayer.configure, so the
     * settings page and an adb shell go through the same door.
     */
    fun setShade(context: Context, key: String, value: Int) =
        send(context, "shadecfg") {
            putExtra("key", key)
            putExtra("v", value)
        }

    /**
     * Asks the module for everything at once. Returns a dead State rather than throwing when the
     * module is not there - "not installed" is a normal thing for this screen to display.
     */
    suspend fun query(context: Context): State = fromBundle(ask(context, "query"))

    /**
     * The same question, asked again until it is answered.
     *
     * One query is a 1.5s shot at a process that may be on its way up. "重启全部作用域" kills
     * SystemUI, and the module's receiver is not registered until the keyguard's clock container
     * attaches - seconds later, and not at all until the keyguard is built. A screen that asks
     * once and keeps the answer therefore shows a DEAD module for as long as it stays open, with
     * every value on it drawn from a default rather than from a setting, and the settings the
     * user then appears to change go nowhere.
     *
     * Widening gaps and then it gives up: "the module is not installed" has to stay a state this
     * settles into, not a poll that runs for as long as the screen is open.
     */
    suspend fun queryAlive(context: Context): State {
        var state = query(context)
        for (gap in RETRY_GAPS_MS) {
            if (state.alive) return state
            kotlinx.coroutines.delay(gap)
            state = query(context)
        }
        return state
    }

    /** The waits between [queryAlive]'s attempts. Each attempt itself costs up to the timeout. */
    private val RETRY_GAPS_MS = longArrayOf(1000L, 2000L, 4000L, 8000L)

    /**
     * A picture of one of SystemUI's own views, with the screen rectangle it occupies.
     *
     * The preview paints these where they belong instead of drawing its own idea of them: the
     * media card's text, seek bar, buttons and thumbnail are the real ones, and so are the torch
     * and camera shortcuts, whose icons are whatever the user has assigned to them.
     */
    data class Shot(val bitmap: Bitmap, val l: Int, val t: Int, val w: Int, val h: Int)

    /**
     * Where the album art goes on the card and how rounded it is, as FRACTIONS of the card.
     *
     * The artwork is cut out of the captured card and painted back by the app - a software
     * canvas does not apply a view's outline clip, so leaving it in the capture put a
     * hard-cornered square where the OEM draws a rounded one.
     *
     * Fractions rather than pixels because the card is not always the size it is on the lock
     * screen: pull the shade down over the app and the same view is laid out for the shade, and
     * a capture taken there gets stretched to the lock screen's width when it is drawn.
     */
    data class ArtSlot(val l: Float, val t: Float, val w: Float, val h: Float, val radius: Float)

    /**
     * What the preview needs and cannot get for itself. [artUnchanged] means keep the artwork
     * you already have; [shortcuts] are only filled in when they were asked for.
     */
    data class Preview(
        val track: String = "",
        val art: Bitmap? = null,
        val artUnchanged: Boolean = false,
        val card: Shot? = null,
        val cardRadius: Float = 0f,
        val artSlot: ArtSlot? = null,
        /** The two clock trees: the hour is drawn in one, the minute in the other. */
        val clockHour: Bitmap? = null,
        val clockMinute: Bitmap? = null,
        /** The date line above the clock, with the place cover mode pins it to. */
        val date: Shot? = null,
        /**
         * The clock's geometry, carried with the pictures rather than only in a query: it
         * changes whenever the lock screen clock style does, and a stale one leaves the app
         * holding a clock it has nowhere to put.
         */
        val clockGeometry: Geometry? = null,
        /** See State.clockHasGlass - a property of the style, so it rides with every reply. */
        val clockHasGlass: Boolean = false,
        /** See State.clockSize. */
        val clockSize: Float = 0f,
        val left: Shot? = null,
        val right: Shot? = null,
    )

    /**
     * Asks the module for a picture of the lock screen's moving parts.
     *
     * [have] is the track whose artwork the caller already holds - a poller passes it and gets
     * a few hundred bytes back instead of the same JPEG. [shortcuts] asks for the torch and
     * camera buttons, which never change, so it is worth passing once and then not again.
     */
    suspend fun preview(
        context: Context,
        have: String = "",
        shortcuts: Boolean = false,
    ): Preview {
        val b = ask(context, "preview") {
            putExtra("have", have)
            putExtra("shortcuts", shortcuts)
        } ?: return Preview()
        val same = b.getBoolean("same", false)
        return Preview(
            track = if (same) have else (b.getString("track") ?: ""),
            art = if (same) null else decode(b.getByteArray("jpg")),
            artUnchanged = same,
            card = shot(b, "card"),
            cardRadius = b.getFloat("cardradius", 0f),
            artSlot = artSlot(b),
            clockHour = decode(b.getByteArray("clock0")),
            clockMinute = decode(b.getByteArray("clock1")),
            date = shot(b, "date"),
            left = shot(b, "sl"),
            right = shot(b, "sr"),
            clockGeometry = if (b.getFloat("clockw", 0f) > 0f) clockGeometry(b) else null,
            clockHasGlass = b.getBoolean("clockglass", false),
            clockSize = sizeOf(b),
        )
    }

    private fun clockGeometry(b: Bundle) = Geometry(
        clockW = b.getFloat("clockw", 0f),
        clockH = b.getFloat("clockh", 0f),
        clockY = b.getFloat("clocky", 0f),
        clockPad = b.getFloat("clockpad", 0f),
        clockX = b.getFloat("clockx", 0f),
        clockPivotX = b.getFloat("clockpivotx", 0f),
        clockFull = fullOf(b),
    )

    private fun artSlot(b: Bundle): ArtSlot? {
        val r = b.getFloatArray("artfrac") ?: return null
        if (r.size != 4 || r[2] <= 0f || r[3] <= 0f) return null
        return ArtSlot(r[0], r[1], r[2], r[3], b.getFloat("artradius", 0f))
    }

    private fun shot(b: Bundle, key: String): Shot? {
        val bitmap = decode(b.getByteArray(key)) ?: return null
        val r = b.getIntArray(key + "rect") ?: return null
        if (r.size != 4 || r[2] <= 0 || r[3] <= 0) return null
        return Shot(bitmap, r[0], r[1], r[2], r[3])
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * One ordered broadcast, answered in the result extras. Returns null rather than throwing
     * when nothing answers: "the module is not loaded" is a state this app displays, not an
     * error it recovers from.
     */
    private suspend fun ask(
        context: Context,
        op: String,
        extras: Intent.() -> Unit = {},
    ): Bundle? =
        suspendCancellableCoroutine { cont ->
            val app = context.applicationContext
            var done = false
            val handler = Handler(Looper.getMainLooper())

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    cont.resume(getResultExtras(false))
                }
            }
            // No reply means no module; do not leave the caller hanging on it.
            handler.postDelayed({
                if (!done) {
                    done = true
                    cont.resume(null)
                }
            }, QUERY_TIMEOUT_MS)

            try {
                app.sendOrderedBroadcast(
                    intent(op).apply(extras), null, receiver, handler, 0, null, null
                )
            } catch (_: Throwable) {
                if (!done) {
                    done = true
                    cont.resume(null)
                }
            }
        }

    private fun fromBundle(b: Bundle?): State {
        if (b == null || !b.getBoolean("alive", false)) return State()
        return State(
            alive = true,
            cover = b.getBoolean("cover", false),
            auto = b.getBoolean("auto", false),
            bias = b.getFloat("bias", 0.34f),
            coverStyle = b.getInt("coverstyle", 0),
            coverCardFill = b.getFloat("covercardfill", 0.8f),
            coverCardPos = b.getFloat("covercardpos", 0.5f),
            coverCardCorner = b.getFloat("covercardcorner", 0.12f),
            clockHeightDp = b.getFloat("clock", 36f),
            clockSize = sizeOf(b),
            clockOffsetDp = b.getFloat("clockoff", 0f),
            clockResponse = b.getFloat("spring", 0.38f),
            glassEnd = b.getFloat("glass", 0.75f),
            cardShowing = b.getBoolean("card", false),
            lockWallpaperOk = b.getBoolean("lockwp", false),
            clockHasGlass = b.getBoolean("clockglass", false),
            track = b.getString("track") ?: "",
            player = b.getString("player") ?: "",
            mcHideArt = b.getBoolean("mcart", false),
            mcArtInLyrics = b.getBoolean("mclyricart", false),
            mcTitleTap = b.getBoolean("mctap", false),
            hideFingerprint = b.getBoolean("hidefp", false),
            aodSmall = b.getBoolean("aodsmall", false),
            forceColon = b.getBoolean("colon", false),
            lyrics = b.getBoolean("lyrics", false),
            lyricsKeepOn = b.getBoolean("lyrickeep", false),
            lyricsHdr = b.getBoolean("lyrichdr", false),
            // Defaults the other way: this one is on for anyone whose module predates the key.
            lyricsTrans = b.getBoolean("lyrictrans", true),
            lyricFill = b.getFloat("lyricfill", 1f),
            lyricPos = b.getFloat("lyricpos", 0.5f),
            lyricSideDp = b.getFloat("lyricside", 30f),
            lyricSizeSp = b.getFloat("lyricsize", 25f),
            lyricWeight = b.getInt("lyricweight", 600),
            sessionLyric = b.getBoolean("sessionlyric", false),
            fpAvoid = b.getInt("fpavoid", 0),
            shade = b.keySet()
                .filter { it.startsWith("shade_") }
                .associate { it.removePrefix("shade_") to b.getInt(it, 0) },
            geometry = Geometry(
                screenW = b.getInt("sw", 0),
                screenH = b.getInt("sh", 0),
                cardL = b.getInt("cardl", 0),
                cardT = b.getInt("cardt", 0),
                cardW = b.getInt("cardw", 0),
                cardH = b.getInt("cardh", 0),
                clockW = b.getFloat("clockw", 0f),
                clockH = b.getFloat("clockh", 0f),
                clockY = b.getFloat("clocky", 0f),
                clockPad = b.getFloat("clockpad", 0f),
                clockX = b.getFloat("clockx", 0f),
                clockPivotX = b.getFloat("clockpivotx", 0f),
                clockFull = fullOf(b),
            ),
        )
    }

    /** The module sends NaN for a size it could not work out; that is 0 here. */
    private fun sizeOf(b: Bundle): Float = b.getFloat("clocksize", 0f).let { if (it > 0f) it else 0f }

    private fun fullOf(b: Bundle): Float = b.getFloat("clockfull", 0f).let { if (it > 0f) it else 0f }

    /** Restarting SystemUI is how most module changes are picked up. Needs root. */
    fun restartSystemUi(): Boolean = kill("com.android.systemui")

    /**
     * The wallpaper process is the other half of the module and restarts independently. It is
     * worth its own entry because the texture is read once when the GL surface is created: if a
     * cover ever ends up wrong on screen, this is what re-reads it from disk.
     */
    fun restartWallpaper(): Boolean = kill("com.miui.miwallpaper")

    /**
     * Every process the module is scoped to, in one go.
     *
     * Read from the scope list the module ships rather than hard-coded, so this keeps meaning
     * "everything the module touches" if that list ever grows.
     *
     * A package's own name is not enough any more. The lock screen editor runs as
     * `com.miui.aod:keyguardeditor`, and `pidof` matches a process name exactly - so the entry
     * that was added for the editor restarted everything except the editor, which is the one
     * process the hooks on it had to be re-read into. [killTree] takes the sub-processes too.
     */
    fun restartScope(context: Context): Boolean {
        val scoped = context.resources.getStringArray(R.array.xposedscope)
        var all = true
        for (pkg in scoped) if (!killTree(pkg)) all = false
        return all
    }

    /**
     * The package's process and every `package:name` process under it.
     *
     * `pkill -f` matches the whole command line, which for an Android app is its process name,
     * and the anchor stops `com.miui.aod` from also matching a package that merely ends with it.
     * A package with nothing running is not a failure: pkill reports "no match" and the caller
     * only wants to know that nothing refused to die.
     */
    private fun killTree(pkg: String): Boolean = try {
        val p = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "pkill -f '^$pkg(\$|:)' ; true"),
        )
        p.waitFor() == 0
    } catch (_: Throwable) {
        false
    }

    private fun kill(process: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "kill \$(pidof $process)"))
        p.waitFor() == 0
    } catch (_: Throwable) {
        false
    }
}
