package com.os4.musiccover.island

import com.os4.musiccover.Main
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1), and given only
 * what it uses of the controller (M2, MiniPlayerParts.kt). See the section comments below for
 * what it is.
 */
internal class SessionSource(
    private val views: RowViews,
    private val rowState: RowState,
    private val edits: RowEdits,
    private val rowHost: RowHost,
    private val sessions: MediaSessionManager?,
    /** The controller's `describeInk`, for `op mini`: it reads the card and the stack, not the session. */
    private val describeInk: () -> String,
) {

    internal val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { rowHost.scheduleRefresh() }
    internal val mediaListener = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadataCalls++
            lastMetadataAt = android.os.SystemClock.uptimeMillis()
            liveMetadata = metadata
            rowHost.scheduleRefresh()
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Usable up to this very change: the gap is counted from here. Counted from the
            // last refresh that saw it usable, a track played through left that minutes old,
            // and QQ Music's stop between two tracks ended the music every time (#26, log
            // 2026-09-30: ended, and the same session back 220ms later).
            if (playbackUsable(liveState?.state) && !playbackUsable(state?.state)) {
                usableSeenAt = android.os.SystemClock.uptimeMillis()
            }
            liveState = state
            rowHost.scheduleRefresh()
        }
        override fun onSessionDestroyed() {
            liveFor = null
            usableSeenAt = 0L
            rowHost.scheduleRefresh()
        }
    }

    /** When the row's session last read as usable (uptime); 0 since it was chosen or destroyed. */
    private var usableSeenAt = 0L
    private val usableGapEnd = Runnable { rowHost.refresh() }

    /**
     * The registered session's state and metadata, as its callbacks last handed them over.
     * Each read of a MediaController's own is a binder call into the system server - a refresh
     * made four or five, and on the lock screen's slow cores they came to ~2ms of the frame a
     * switch lands on (traced 2026-09-25). Another session's are still asked for.
     */
    private var liveFor: android.media.session.MediaSession.Token? = null
    private var liveState: PlaybackState? = null
    private var liveMetadata: MediaMetadata? = null
    /** For `op mini`: the metadata callbacks the registered session has delivered. */
    private var metadataCalls = 0
    private var lastMetadataAt = 0L

    /** For `op mini`: the session the pill follows, what it holds, and what is really there. */
    fun describeSession(): String {
        fun id(c: MediaController?) = c?.let { it.packageName + "@" + Integer.toHexString(it.sessionToken.hashCode()) }
        fun title(m: MediaMetadata?) = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val c = rowState.controller
        val w = Main.miniPlayerSession()
        return "session=${id(c)} live=${c != null && c.sessionToken == liveFor} calls=$metadataCalls rearms=$rearms " +
            "liveTitle=${title(liveMetadata)} binderTitle=${runCatching { title(c?.metadata) }.getOrNull()} " +
            "state=${stateOf(c ?: return "session=null watched=${id(w)} ")?.state} seenAt=$usableSeenAt " +
            "watched=${id(w)} watchedTitle=${runCatching { title(w?.metadata) }.getOrNull()} " +
            "card=${Main.miniPlayerMediaCardPresent()} sameObj=${c === w} " +
            "cbReg=${runCatching { Xp.getObjectField(c, "mCbRegistered") }.getOrNull()} " +
            "cbs=${runCatching { (Xp.getObjectField(c, "mCallbacks") as List<*>).size }.getOrNull()} " +
            "wCbs=${runCatching { (Xp.getObjectField(w, "mCallbacks") as List<*>).size }.getOrNull()} " +
            "lastCallAgo=${android.os.SystemClock.uptimeMillis() - lastMetadataAt} track=${rowState.lastTrack} " +
            "ink=[${describeInk()}] "
    }
    internal fun stateOf(c: MediaController): PlaybackState? =
        if (liveFor != null && c.sessionToken == liveFor) liveState else c.playbackState

    internal fun metadataOf(c: MediaController): MediaMetadata? =
        if (liveFor != null && c.sessionToken == liveFor) liveMetadata else c.metadata

    /**
     * The media card has moved to another track: the callback is put on a controller of the
     * pill's own and the session read again. The system server drops a controller callback for
     * good the first time a call to it fails (MediaSessionRecord.performOnCallbackHolders), and
     * says nothing to the controller, which goes on believing it is registered. Apple's metadata
     * with its lyric attached runs to 50k characters, to every controller in SystemUI at once:
     * skipping through tracks filled SystemUI's binder buffer, the pill's callback was dropped,
     * and the pill showed one song while another played, until SystemUI restarted (2026-10-14).
     * Its own controller because the one it was handed is often Main's: with Main's callback
     * still on it, taking ours off and putting it back never registers anything again.
     */
    fun cardTrackChanged() {
        views.handler.post {
            val c = rowState.controller ?: return@post
            if (rowState.destroyed) return@post
            val own = runCatching { MediaController(views.context, c.sessionToken) }.getOrNull() ?: return@post
            runCatching { Gen.unsessionCallback(c, mediaListener) }
            edits.controller = own
            runCatching { Gen.sessionCallback(own, mediaListener, views.handler) }
            // Not followLive: the gap a player between two tracks is given runs on (#26).
            liveFor = null
            liveState = own.playbackState
            liveMetadata = own.metadata
            liveFor = own.sessionToken
            rearms++
            rowHost.refresh()
        }
    }
    /** For `op mini`: how often the callback has been put back on a track change. */
    private var rearms = 0

    /** The callbacks are [c]'s from here: what it has now, and what they bring after. */
    internal fun followLive(c: MediaController?) {
        liveFor = null
        usableSeenAt = 0L
        views.handler.removeCallbacks(usableGapEnd)
        liveState = c?.playbackState
        liveMetadata = c?.metadata
        liveFor = c?.sessionToken
    }
    internal fun chooseController(): MediaController? {
        val platform = Main.miniPlayerSession()
        if (platform != null && isUsable(platform)) return platform
        val active = runCatching { sessions?.getActiveSessions(null).orEmpty() }.getOrDefault(emptyList())
        return active.firstOrNull(::isUsable)
    }

    /**
     * A player between two tracks can say STOPPED or NONE for a moment. Read as the music ending,
     * the music island left the row, the card's suppression let go, and the whole media card
     * came up where the pill was and went again (#26). The session the row is showing stays
     * usable for USABLE_GAP_MS after it last was; a session destroyed gets no such grace.
     */
    fun isUsable(value: MediaController): Boolean {
        if (playbackUsable(stateOf(value)?.state)) {
            if (value.sessionToken == rowState.controller?.sessionToken) usableSeenAt = android.os.SystemClock.uptimeMillis()
            return true
        }
        if (cardHolds(value)) return true
        if (usableSeenAt == 0L || value.sessionToken != rowState.controller?.sessionToken ||
            value.sessionToken != liveFor) return false
        val left = usableSeenAt + USABLE_GAP_MS - android.os.SystemClock.uptimeMillis()
        if (left <= 0L) return false
        // Settled either way when the gap runs out: the state back, or the music gone for good.
        views.handler.removeCallbacks(usableGapEnd)
        views.handler.postDelayed(usableGapEnd, left + 1L)
        return true
    }

    /**
     * The session the lock screen's media card is showing, while the card is up, in a state that
     * says nothing has played yet. QQ Music puts up its notification - and so the card - as soon
     * as it is opened, with the session at NONE or STOPPED until the first play: read by state
     * alone that was no music, so the card stayed a card on the lock screen and never went into
     * the pill until something had been played. The card is what the lock screen shows as the
     * music; once it goes, the state decides again.
     */
    private fun cardHolds(value: MediaController): Boolean {
        val state = stateOf(value)?.state
        if (state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED) return false
        if (!Main.miniPlayerMediaCardPresent()) return false
        return value.sessionToken == Main.miniPlayerSession()?.sessionToken
    }

    private fun playbackUsable(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }
}
