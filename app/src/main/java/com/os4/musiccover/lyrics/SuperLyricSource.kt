package com.os4.musiccover.lyrics
import com.os4.musiccover.Xp
import com.hchen.superlyricapi.LyricListener
import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.SuperLyric
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Stop
import com.hchen.superlyricapi.model.Word

/**
 * The player's own lyrics, pushed by a SuperLyric server - the same shape of source as the
 * Lyricon bridge, over a different wire.
 *
 * Where Lyricon's subscriber binds to whichever app hosts the provider plugins, SuperLyric's
 * receiver talks to a system service the SuperLyric module registers (ServiceManager, name
 * "super_lyric.v4"): no package to find, no intent to resolve, no Context needed at all. What
 * arrives is again the whole song - lines, word timings, translation, romanisation - for the
 * track the player says it is playing, with the publisher's package naming who sent it.
 *
 * It sits beside Lyricon rather than replacing it: a phone runs one central or the other, and
 * LyricSource.load asks Lyricon first so an installed Lyricon keeps answering exactly as before.
 * A device with no SuperLyric server gets a rejected register and a stream of nulls from
 * latest() - nothing else - so this can be asked unconditionally, exactly like the bridge.
 *
 * The registration is a call, not something that lives on by itself: the receiver keeps the
 * listeners it was handed and the transport they were bound over, and nothing in it re-registers
 * when that transport is replaced. Renewing it is therefore this side's job, and attach() does
 * it on every keyguard container attach - which is also what picks the server up on a boot where
 * it was not there yet. Once attached it is only ever read, so the whole object is a snapshot
 * and a listener.
 */
object SuperLyricSource {

    /** The server's latest snapshot - current line, position, and the lyric itself. */
    @Volatile
    private var sView: LyricView? = null

    /** The last connection state, for the probe. */
    @Volatile
    private var sState = "not started"

    /** How many frames have arrived - "registered" vs "registered and receiving". */
    @Volatile
    private var sFrames = 0

    /** The publisher's package - which player the lyric belongs to. */
    @Volatile
    private var sPlayer: String? = null

    /** Lyric documents the current publisher has sent - whether it can be waited for at all. */
    @Volatile
    private var sPlayerDocs = 0

    /** The lyric id last seen, so the lock screen is poked once per song, not once per frame. */
    @Volatile
    private var sLyricId: String? = null

    /** The last failure, for the probe. */
    @Volatile
    private var sError: String? = null

    /** Whether attach() has registered the listener. */
    @Volatile
    private var sAttached = false

    /** Notified on every frame the server pushes, for a lookup waiting out a stale one. */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // java.lang.Object: monitor for wait/notifyAll
    private val sViewLock = Object()

    /**
     * How long a lookup waits for the server to catch up with a track change.
     *
     * Only ever spent when the pushed lyric is the hooked player's own, i.e. when a new song's
     * lyric is known to be on its way; a server that is connected but pushing nothing for this
     * player is skipped at once so the file and the catalogues still answer. Taken from the
     * bridge's constant, which the same reasoning sized.
     */
    private const val CATCH_UP_MS = 2000L

    /**
     * The frames the listener receives are Snapshots: the song, the position inside it, and the
     * publisher. Only the song is kept - the position comes from the MediaSession this module
     * already follows, and the lock screen re-asks for lines on the frames that matter rather
     * than on every one the server sends.
     *
     * Every method is wrapped: these arrive on whichever thread the binder likes and an
     * exception here is an exception in SystemUI.
     */
    private object Listener : LyricListener {
        override fun onLyric(view: LyricView) {
            try {
                val lyric = view.lyric
                val fresh = synchronized(sViewLock) {
                    sView = view
                    sFrames++
                    if (view.publisher != sPlayer) {
                        sPlayer = view.publisher
                        sPlayerDocs = 0
                    }
                    val fresh = lyric.id != sLyricId
                    sLyricId = lyric.id
                    sPlayerDocs++
                    sViewLock.notifyAll()
                    fresh
                }
                // A new lyric document means a new song's words can be drawn; a frame of the
                // same document only moved the position, which the MediaSession already drives.
                if (fresh) LockLyrics.onProviderLyrics()
            } catch (t: Throwable) {
                sError = t.toString()
            }
        }

        override fun onStop(stop: Stop) {
            try {
                synchronized(sViewLock) {
                    sView = null
                    sLyricId = null
                    sViewLock.notifyAll()
                }
            } catch (t: Throwable) {
                sError = t.toString()
            }
        }
    }

    /**
     * Registers the listener with the SuperLyric receiver. No Context: the transport is a
     * system service lookup, not a bound app service.
     *
     * Called on every keyguard container attach, not once, and that is the point: the receiver
     * answers at once while it is still bound to the same transport - an identity check, no
     * binder call - and binds again when the server has been replaced. Guarding this behind
     * "we already registered" would leave the process deaf for the rest of its life after such
     * a replacement, which is the state the old comment here wrongly said the receiver handled
     * by itself (2026-11-18 review).
     */
    @JvmStatic
    fun attach() {
        sAttached = true
        try {
            val result = SuperLyric.receiver().register(Listener)
            sState = "register: " + result
            if (result != Receiver.RegisterResult.OK) sAttached = false
        } catch (t: Throwable) {
            // No API classes resolved, or the service lookup was refused: either way there is
            // no server for this process, and attach failing must not take anything else down.
            sState = "unavailable"
            sError = t.toString()
            sAttached = false
        }
    }

    /**
     * Drops the registration and the snapshot. Called when the lyric page is torn down, so a
     * reloaded module does not leak a listener into the server - or keep answering from a view
     * the old copy held.
     */
    @JvmStatic
    fun detach() {
        if (!sAttached) return
        sAttached = false
        try {
            SuperLyric.receiver().unregister(Listener)
        } catch (t: Throwable) {
            sError = t.toString()
        }
        synchronized(sViewLock) {
            sView = null
            sLyricId = null
        }
        sState = "detached"
    }

    /**
     * Whether a SuperLyric server answered the lookup. The check is whether the system service
     * exists, not whether an app is installed - the module can be present but not holding the
     * service yet (its own host is still coming up), which reads the same to a subscriber.
     */
    @JvmStatic
    fun installed(): Boolean {
        return try {
            SuperLyric.isAvailable()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * The pushed lyric's lines for the track the session is on, or null when the server has
     * none for it - which covers no server attached, a lyric belonging to another player, and
     * the quiet moment between a track change and the next push.
     *
     * When the lyric in hand is not this one but the server is publishing this player's, the
     * right one is on its way, so this waits for it - briefly, on the lookup's worker - rather
     * than handing the track to the catalogues, which is where the server's copy would be lost.
     */
    @JvmStatic
    fun linesFor(track: TrackIdentity, player: String?): List<LyricLine>? {
        var view = sView
        if (view == null || !isTrack(view, track, player)) {
            // Only a player that has shown it publishes is waited for; see sPlayerDocs.
            if (player != null && player == sPlayer && sPlayerDocs > 0) {
                val until = android.os.SystemClock.uptimeMillis() + CATCH_UP_MS
                synchronized(sViewLock) {
                    while (true) {
                        val v = sView
                        if (v != null && isTrack(v, track, player)) { view = v; break }
                        val left = until - android.os.SystemClock.uptimeMillis()
                        if (left <= 0) break
                        sViewLock.wait(left)
                    }
                }
            }
            val held = view
            if (held == null || !isTrack(held, track, player)) {
                if (held != null) {
                    Xp.log("[MCSuperLyric] holding \"" + held.lyric.title
                            + "\" while the track is " + track.readings + "; not reading it")
                }
                return null
            }
        }
        val lyric = view.lyric
        val rich = lyric.lines
        if (rich.isEmpty()) return null
        val out = ArrayList<LyricLine>(rich.size)
        // Where the tail of a line may run to when the server gave its last word no end of its
        // own: the arrival of the line after it, the same room every other word-timed source
        // leaves - see LyricParse.closeUntimedTail. By time, since the pushed order is not
        // trusted either (the rows are sorted below).
        val begins = rich.map { ms(it.start) }.sorted().toIntArray()
        for (line in rich) {
            val nextStart = LyricParse.nextAfter(begins, ms(line.start), ms(line.end))
            convert(line, nextStart)?.let { out.add(it) }
        }
        if (out.isEmpty()) return null
        out.sortBy { it.start }
        return out
    }

    /**
     * Whether the pushed lyric is the session's own song.
     *
     * The strongest check is the publisher's package: the server is fed by the player, so a
     * lyric pushed for this player is this player's. The title then has to agree with what the
     * media session claims is playing - a lyric still on its way out from under the previous
     * song would otherwise answer for it. A lyric that names neither publisher nor title can
     * place itself nowhere and is never taken.
     */
    internal fun isTrack(view: LyricView, track: TrackIdentity, player: String?): Boolean {
        val lyric = view.lyric
        if (lyric.lines.isEmpty()) return false
        if (player != null && view.publisher != null && view.publisher != player) return false
        if (lyric.title.isNotBlank()) {
            val artist = lyric.artist.takeIf { it.isNotBlank() }
            return track.accepts(lyric.title, artist)
        }
        return player != null && view.publisher == player
    }

    /**
     * One server line, with its word timings and whatever hangs off it.
     *
     * The three syllable arrays have to satisfy LyricLine's contract exactly - same length,
     * charEnd running up to the text's length, and every end after its own start - so they are
     * built from the words rather than from the line's own text: a server line whose words do
     * not add up to its text would otherwise produce arrays that index past the end of it.
     * A line whose words are missing or unusable stays line-timed, which draws correctly and
     * simply does not fill word by word.
     */
    internal fun convert(line: Line, nextStart: Int): LyricLine? {
        val text = line.text.trim()
        // A time past an Int would wrap negative in toInt() and send the fill backwards; such a
        // line is drawn line-timed instead (PR #106).
        val words = line.words?.takeIf { ws -> ws.all { fits(it.start) && fits(it.end) } }
        val begin = ms(line.start)
        val end = ms(line.end)
        var built: LyricLine? = null
        if (!words.isNullOrEmpty()) {
            // The drawn string is the words' own: the timing is per word, so a line text they
            // do not sum to cannot be hung on them.
            val sb = StringBuilder()
            val starts = ArrayList<Int>(words.size)
            val ends = ArrayList<Int>(words.size)
            val chars = ArrayList<Int>(words.size)
            for (w in words) {
                val t = w.text
                if (t.isEmpty()) continue
                sb.append(t)
                starts.add(w.start.toInt())
                // A word with no end of its own is left to the tail-closing pass below, which is
                // the same thing every other word-timed source here needs.
                ends.add(if (w.end > w.start) w.end.toInt() else w.start.toInt())
                chars.add(sb.length)
            }
            var n = sb.length
            while (n > 0 && sb[n - 1].isWhitespace()) n--
            if (n > 0 && starts.isNotEmpty()) {
                for (k in chars.indices) if (chars[k] > n) chars[k] = n
                val s = starts.toIntArray()
                val e = ends.toIntArray()
                LyricParse.closeUntimedTail(s, e, end, nextStart)
                built = LyricLine(sb.substring(0, n), line.translation,
                    LyricParse.romaOf(line.phonetic, sb.substring(0, n)), begin,
                    maxOf(end, e.max()), false, s, e, chars.toIntArray())
            }
        }
        if (built == null) {
            // Words whose times could not be used still say what is sung.
            val shown = text.ifEmpty { words?.joinToString("") { it.text }?.trim().orEmpty() }
            if (shown.isEmpty()) return null
            built = LyricLine(shown, line.translation, LyricParse.romaOf(line.phonetic, shown),
                begin, end, false, null, null, null)
        }
        // Deliberately not unwrapBackground: that is for a line already drawn small and faint
        // under the one it echoes, and this server has no background lane - every line arrives
        // as a line. Stripping the brackets here would show "（笑）" as "笑" on this route only,
        // while a file and Lyricon leave the line as published (2026-11-18 review).
        return built
    }

    private fun fits(v: Long) = v in 0L..Int.MAX_VALUE.toLong()

    /** Milliseconds into an Int the way LyricLine wants them: clamped. */
    private fun ms(v: Long) = v.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** Diagnostics: what the server is doing, for the probe. Never throws. */
    @JvmStatic
    fun describe(): String {
        val sb = StringBuilder("state=").append(sState)
        sb.append(" frames=").append(sFrames).append(" playerDocs=").append(sPlayerDocs)
        sPlayer?.let { sb.append(" publisher=").append(it) }
        val view = sView
        if (view == null) {
            sb.append(" view=none")
        } else {
            val lyric = view.lyric
            sb.append(" title=").append(lyric.title)
                .append(" lines=").append(lyric.lines.size)
                .append(" index=").append(view.index)
                .append(" position=").append(view.position)
        }
        sError?.let { sb.append(" error=").append(it) }
        if (!sAttached) sb.append(" (not attached)")
        return sb.toString()
    }
}
