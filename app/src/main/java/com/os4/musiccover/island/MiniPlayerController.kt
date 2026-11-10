// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover.island

import com.os4.musiccover.AlbumArt
import com.os4.musiccover.CardTouch
import com.os4.musiccover.MediaCard
import com.os4.musiccover.ClockCollapse
import com.os4.musiccover.ClockRoom
import com.os4.musiccover.ClockRoomPolicy
import com.os4.musiccover.CoverMorphMotion
import com.os4.musiccover.Main
import com.os4.musiccover.Settings
import com.os4.musiccover.lyrics.TrackIdentity
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import com.os4.musiccover.immersive.ImmersiveHost
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * One mini player: the pill, the small island beside it and every morph between them and
 * the lock screen's cards, for one keyguard shortcut host. MiniPlayerRuntime owns one per host.
 *
 * Moved out of MiniPlayerRuntime.kt as it was (2026-10-14): the file was two things, and this
 * is the larger. The class and two constants the runtime also reads became internal; nothing
 * else changed.
 */
internal class MiniPlayerController(
    val host: ViewGroup,
    val left: View,
    val right: View,
    private val prefs: SharedPreferences,
    val loader: ClassLoader,
) {
    val context = host.context
    val handler = Gen.handler()
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    var player: MiniPlayerView? = null
    var controller: MediaController? = null
    var header: WeakReference<View>? = null
    private val suppressedHeaders = WeakViewOverrideRegistry<View>()
    internal var nativeSuppressionRequested = false
    var morph: MiniCardMorph? = null
    internal var morphScene = false
    internal var artBridged = false
    /** Whether the pill is bound to the music as refresh last bound it, not to a notification. */
    private var pillShowsMusic = false
    /** Refresh had nothing to bind the pill's island to: it still holds another's picture. */
    internal var pillUnbound = false
    internal var lastTrack = ""
    var cachedCover: Bitmap? = null
    private var refreshPosted = false
    private var positionPosted = false
    var destroyed = false
    var configuredHeightDp = MiniPlayerConfig.DEFAULT_HEIGHT_DP
    var config = JSONObject(MiniPlayerConfig.defaultJson())
    private var forceHeaderRefresh = true
    var lastPresentationLog = ""
    private var lastActive = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        configStale = true
        scheduleRefresh()
    }
    /** The config is read from preferences only when they change, not on every refresh. */
    private var configStale = true
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { scheduleRefresh() }
    private val mediaListener = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadataCalls++
            lastMetadataAt = android.os.SystemClock.uptimeMillis()
            liveMetadata = metadata
            scheduleRefresh()
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
            scheduleRefresh()
        }
        override fun onSessionDestroyed() {
            liveFor = null
            usableSeenAt = 0L
            scheduleRefresh()
        }
    }

    /** When the row's session last read as usable (uptime); 0 since it was chosen or destroyed. */
    private var usableSeenAt = 0L
    private val usableGapEnd = Runnable { refresh() }

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
        val c = controller
        val w = Main.miniPlayerSession()
        return "session=${id(c)} live=${c != null && c.sessionToken == liveFor} calls=$metadataCalls rearms=$rearms " +
            "liveTitle=${title(liveMetadata)} binderTitle=${runCatching { title(c?.metadata) }.getOrNull()} " +
            "state=${stateOf(c ?: return "session=null watched=${id(w)} ")?.state} seenAt=$usableSeenAt " +
            "watched=${id(w)} watchedTitle=${runCatching { title(w?.metadata) }.getOrNull()} " +
            "card=${Main.miniPlayerMediaCardPresent()} sameObj=${c === w} " +
            "cbReg=${runCatching { Xp.getObjectField(c, "mCbRegistered") }.getOrNull()} " +
            "cbs=${runCatching { (Xp.getObjectField(c, "mCallbacks") as List<*>).size }.getOrNull()} " +
            "wCbs=${runCatching { (Xp.getObjectField(w, "mCallbacks") as List<*>).size }.getOrNull()} " +
            "lastCallAgo=${android.os.SystemClock.uptimeMillis() - lastMetadataAt} track=$lastTrack " +
            "ink=[${describeInk()}] "
    }

    /** For `op mini`: the text colours the system gave its own card and rows, by view id. */
    private fun describeInk(): String {
        fun hex(v: View?) = (v as? android.widget.TextView)?.let { Integer.toHexString(it.currentTextColor) }
        val sb = StringBuilder("${binding.describeInkCounts()} cardTitle=${hex(Main.miniPairTitle())} cardArtist=${hex(Main.miniPairArtist())}")
        val seen = HashSet<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.isShown) {
                val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "?"
                val entry = "$id=${Integer.toHexString(v.currentTextColor)}"
                if (seen.add(entry) && seen.size <= 12) sb.append(' ').append(entry)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        stackHost.notificationStack()?.let(::walk)
        Main.miniPlayerMediaHeader()?.let(::walk)
        return sb.toString()
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
        handler.post {
            val c = controller ?: return@post
            if (destroyed) return@post
            val own = runCatching { MediaController(context, c.sessionToken) }.getOrNull() ?: return@post
            runCatching { Gen.unsessionCallback(c, mediaListener) }
            controller = own
            runCatching { Gen.sessionCallback(own, mediaListener, handler) }
            // Not followLive: the gap a player between two tracks is given runs on (#26).
            liveFor = null
            liveState = own.playbackState
            liveMetadata = own.metadata
            liveFor = own.sessionToken
            rearms++
            refresh()
        }
    }
    /** For `op mini`: how often the callback has been put back on a track change. */
    private var rearms = 0

    /** The callbacks are [c]'s from here: what it has now, and what they bring after. */
    private fun followLive(c: MediaController?) {
        liveFor = null
        usableSeenAt = 0L
        handler.removeCallbacks(usableGapEnd)
        liveState = c?.playbackState
        liveMetadata = c?.metadata
        liveFor = c?.sessionToken
    }
    /** The stack's content top the clock was last given room by (ClockRoom.roomForRows). */
    private var clockTopAsked = Float.NaN

    private val preDraw = ViewTreeObserver.OnPreDrawListener { android.os.Trace.beginSection("MC islandsPreDraw"); try {
        stackHost.holdKept()
        groupMotion.holdRows()
        // The rows can settle after the stack last told the clock where they are - a row let out
        // is laid out a frame or more after the list changed. The clock is asked again then.
        val top = stackHost.stackContentTop()
        if (top.isNaN() != clockTopAsked.isNaN() || kotlin.math.abs(top - clockTopAsked) > 2f) {
            clockTopAsked = top
            ClockRoom.reassertClockRoom()
        }
        updateVisibility()
        if (player?.visibility == View.VISIBLE) {
            position()
            followShortcuts()
        }
        shortcutDiscs.updateDiscs()
        binding.lottiesFollowSight()
        true
    } finally { android.os.Trace.endSection() } }

    private val followRelative = Matrix()
    val followHost = Matrix()
    var followRoot: WeakReference<View>? = null
    var rowHeldOff = false
    private var shortcutRow: WeakReference<View>? = null

    /**
     * The torch and camera's motion and fade, on the pill, every frame. The pill hangs off the
     * window root, outside everything the OEM animates. Swiping up, keyguard_bottom_area fades
     * to a transitionAlpha of 0.05 and zooms to 0.9 (`op alphasweep`, 2026-09-24) - but the two
     * buttons also move on their own inside it, drawing in toward each other and rising less
     * than the row's zoom alone would carry them; following the row, the pill (filmed) rose
     * faster than they did and kept its width while they closed in. So the pill follows the
     * buttons themselves: its centre on the midpoint of where the two are drawn, its scale the
     * ratio of their drawn spacing to their resting spacing.
     *
     * In the full-screen doze the buttons are put away while the pill stays; there it takes only
     * keyguard_root_view's own zoom and fade, and it goes back to the buttons once they have
     * faded all the way back in after the wake - switching earlier, it dropped with their fade
     * and came back: a flash.
     */
    fun followShortcuts() {
        val view = player ?: return
        // The spread's morphs place the pill; out as the list, it is not drawn.
        if (spreadMotion.spread != null) {
            view.setAnimationMatrix(null)
            return
        }
        // A flight's morph is not the pill's: the pill (and the small island coming up beside
        // it) still ride with the buttons while the flight is out.
        if (groupMotion.pillMorph() != null) {
            view.setAnimationMatrix(null)
            lastFollow.fill(0f)
            // The small island still in its place under the media card's morph: waiting for its
            // row, or coming and going there with no row.
            if (groupMotion.group != null) smallIsland?.let { placeSmallIsland(null, it.transitionAlpha) }
            return
        }
        if (shortcutRow?.get()?.isAttachedToWindow != true) stackHost.findRow()?.let { shortcutRow = WeakReference(it) }
        val row = shortcutRow?.get() ?: return
        val keyguardRoot = followRoot?.get()
        followHost.reset()
        host.transformMatrixToGlobal(followHost)
        if (!followHost.invert(hostInverse)) return
        var fade = 1f
        var rowFade = 1f
        var aboveRoot = false
        var v: View? = row
        while (v != null && v !== host) {
            if (v === keyguardRoot) aboveRoot = true
            if (aboveRoot) fade *= v.alpha * v.transitionAlpha
            else rowFade *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        // Held with the buttons, for as long as they are: this runs before holdButtonsThroughDoze
        // each frame, and rowFade is whatever was last written - the wake's 0.04, or the 1 the
        // hold wrote a frame ago. Waking, the pill let go on one and took the other: the row
        // dropped to nothing and faded in beside buttons that stayed (filmed 2026-09-26).
        if (MiniPlayerScene.fullScreenAodActive || shortcutDiscs.holdButtons) rowHeldOff = true
        // ...or until the PIN pad is up, which keeps the row's fade down for as long as it is.
        else if (rowHeldOff && (rowFade >= 0.99f || Main.bouncerShown())) rowHeldOff = false
        followRowFade = rowFade
        // The lock screen's editor button, up after a long press on the clock, is where the row
        // is and under it: the row goes as it comes, on its own fade, and comes back as it goes.
        val editShown = editButtonShown(row)
        fade *= 1f - editShown
        view.yieldTouches = editShown > 0.01f
        val matrix = followRelative
        // With a shortcut switched off the row rides on the one left, or on the row's own frame
        // with neither; it used to take only the keyguard's zoom then, and stood still while
        // the swipe up carried the buttons away.
        // Both switched on is not both drawn: with one of them not shown for a frame the pill
        // fell through to the keyguard's zoom alone and stayed down while the swipe up carried
        // the buttons off (#36). It rides on whichever one is drawn then, as with one switched off.
        val lUp = followable(left)
        val rUp = followable(right)
        val single = if (lUp != rUp) (if (lUp) left else right) else null
        if (!rowHeldOff && lUp && rUp) {
            followBranch = "pair"
            fade *= rowFade
            // Where each button is drawn now and where it rests, both in the host's pixels.
            if (!drawnCentre(left, drawnL) || !drawnCentre(right, drawnR)) return
            restCentre(left, restL)
            restCentre(right, restR)
            val restSpan = restR[0] - restL[0]
            if (kotlin.math.abs(restSpan) < 1f) return
            var scale = (drawnR[0] - drawnL[0]) / restSpan
            var mx = (drawnL[0] + drawnR[0]) / 2f - (restL[0] + restR[0]) / 2f
            var my = (drawnL[1] + drawnR[1]) / 2f - (restL[1] + restR[1]) / 2f
            // At rest the arithmetic gives 0.99999 and a hair of shift; writing that every frame
            // invalidated the pill every frame - a redraw that never stopped, blur and all.
            if (kotlin.math.abs(scale - 1f) < 0.0005f) scale = 1f
            if (kotlin.math.abs(mx) < 0.25f) mx = 0f
            if (kotlin.math.abs(my) < 0.25f) my = 0f
            matrix.reset()
            matrix.setTranslate(-(restL[0] + restR[0]) / 2f, -(restL[1] + restR[1]) / 2f)
            matrix.postScale(scale, scale)
            matrix.postTranslate((restL[0] + restR[0]) / 2f + mx, (restL[1] + restR[1]) / 2f + my)
        } else if (!rowHeldOff && single != null) {
            followBranch = if (single === left) "left" else "right"
            fade *= rowFade
            // Moved as its centre is, and zoomed about it as it is.
            if (!drawnCentre(single, drawnL)) return
            restCentre(single, restL)
            var scale = shortcutDiscs.drawnZoom(single)
            var mx = drawnL[0] - restL[0]
            var my = drawnL[1] - restL[1]
            if (kotlin.math.abs(scale - 1f) < 0.0005f) scale = 1f
            if (kotlin.math.abs(mx) < 0.25f) mx = 0f
            if (kotlin.math.abs(my) < 0.25f) my = 0f
            matrix.reset()
            matrix.setTranslate(-restL[0], -restL[1])
            matrix.postScale(scale, scale)
            matrix.postTranslate(restL[0] + mx, restL[1] + my)
        } else if (!rowHeldOff && row.isShown) {
            followBranch = "row"
            fade *= rowFade
            // The row's own zoom and movement, as the keyguard's below.
            matrix.reset()
            row.transformMatrixToGlobal(matrix)
            matrix.postConcat(hostInverse)
            restOrigin(row, restL)
            matrix.preTranslate(-restL[0], -restL[1])
        } else if (keyguardRoot != null) {
            followBranch = if (rowHeldOff) "root/held" else "root"
            // The keyguard's own zoom only, as a matrix from its untransformed slot.
            matrix.reset()
            keyguardRoot.transformMatrixToGlobal(matrix)
            matrix.postConcat(hostInverse)
            restOrigin(keyguardRoot, restL)
            matrix.preTranslate(-restL[0], -restL[1])
        } else {
            followBranch = "none"
            matrix.reset()
        }
        fade = shortcutDiscs.unheldInDoze(fade)
        placeSmallIsland(matrix, fade)
        // Into the pill's own frame: conjugated by its slot in the host.
        matrix.preTranslate(view.left.toFloat(), view.top.toFloat())
        matrix.postTranslate(-view.left.toFloat(), -view.top.toFloat())
        // The squeeze, inside that: about the pill's own centre in its slot, its nudge included.
        // In pixels, not shares: with a small island the row the squeeze measured is wider
        // than the pill, and its shares of the row are not the pill's.
        val sx = shortcutDiscs.squeeze.pillScaleX(view.width.toFloat())
        val sy = shortcutDiscs.squeeze.pillScaleY()
        val shift = shortcutDiscs.squeeze.pillShiftPx()
        if (kotlin.math.abs(sx - 1f) > 0.0005f || kotlin.math.abs(sy - 1f) > 0.0005f
            || kotlin.math.abs(shift) > 0.25f) {
            pillSqueeze.setScale(sx, sy, view.translationX + view.width / 2f,
                view.translationY + view.height / 2f)
            pillSqueeze.postTranslate(shift, 0f)
            matrix.preConcat(pillSqueeze)
        }
        // Only a real change is written: each write invalidates the pill.
        matrix.getValues(followValues)
        if (!followValues.contentEquals(lastFollow)) {
            System.arraycopy(followValues, 0, lastFollow, 0, 9)
            if (matrix.isIdentity) view.setAnimationMatrix(null) else view.setAnimationMatrix(matrix)
        }
        // A notification flying out of the pill, or home into it, is the flight: the pill under
        // it stays out of sight until the flight lands on it.
        // Nor is it shown with another island's picture still in it, waiting to be bound.
        val pillFade = if (noteMorphMotion.pillHeld() || cardExchange.exchangeHoldsPill() || pillUnbound) 0f else fade
        followPillFade = pillFade
        if (kotlin.math.abs(view.transitionAlpha - pillFade) > 0.002f) view.transitionAlpha = pillFade
    }

    /**
     * How much of the lock screen's editor entry is up, 0 to 1: its button in the bottom area
     * (custom_lockscreen_button, KeyguardBottomAreaInjector.showCustomLockscreenButton, a long
     * press on the clock), its alpha as it fades in and out. It sits where the row is, and the
     * row, drawn over it, hid it (2026-09-26).
     */
    private fun editButtonShown(row: View): Float {
        val button = editButton?.get()?.takeIf { it.isAttachedToWindow } ?: run {
            val id = row.resources.getIdentifier("custom_lockscreen_button", "id", "com.android.systemui")
            if (id == 0) return 0f
            row.findViewById<View>(id)?.also { editButton = WeakReference(it) } ?: return 0f
        }
        if (!button.isShown) return 0f
        return (button.alpha * button.transitionAlpha).coerceIn(0f, 1f)
    }

    private var editButton: WeakReference<View>? = null

    /** The editor's button is up over the row: its touches are its own. */
    private fun editButtonUp(): Boolean =
        shortcutRow?.get()?.let { editButtonShown(it) > 0.01f } == true

    /** What followShortcuts read and gave the pill last, for the doze trace and `op mini`. */
    var followBranch = "-"
    var followRowFade = 1f
    private var followPillFade = 1f

    val hostInverse = Matrix()
    val discToScreen = Matrix()
    val discInverse = Matrix()
    val scratch = Matrix()
    private val pillSqueeze = Matrix()
    private val drawnL = FloatArray(2)
    private val drawnR = FloatArray(2)
    private val restL = FloatArray(2)
    private val restR = FloatArray(2)
    private val followValues = FloatArray(9)
    private val lastFollow = FloatArray(9)

    /** A button the pill can take its motion from this frame: switched on, drawn, laid out. */
    private fun followable(button: View): Boolean =
        !switchedOff(button) && button.isAttachedToWindow && button.isShown &&
            button.width > 0 && button.height > 0

    /** A view's centre as drawn, every ancestor's transform included, in host pixels. */
    fun drawnCentre(v: View, out: FloatArray, inverse: Matrix = hostInverse): Boolean {
        if (!v.isAttachedToWindow || v.width <= 0) return false
        scratch.reset()
        v.transformMatrixToGlobal(scratch)
        scratch.postConcat(inverse)
        out[0] = v.width / 2f
        out[1] = v.height / 2f
        scratch.mapPoints(out)
        return true
    }

    /** The same centre with no transform anywhere: layout positions only. */
    fun restCentre(v: View, out: FloatArray = FloatArray(2)): FloatArray {
        restOrigin(v, out)
        out[0] += v.width / 2f
        out[1] += v.height / 2f
        return out
    }

    private fun restOrigin(v: View, out: FloatArray) {
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== host) {
            val parent = cur.parent as? View
            x += cur.left - (parent?.scrollX ?: 0)
            y += cur.top - (parent?.scrollY ?: 0)
            cur = parent
        }
        out[0] = x
        out[1] = y
    }

    // ------------------------------------------------------------ the islands

    /** The row's islands in order: the focus ones that outrank the music, MUSIC_ISLAND, the rest. Set by refreshUnsafe. */
    var islandKeys: List<String> = emptyList()

    /** The islands wear the ordinary notification's colours and soft glass, always (2026-10-09). */
    fun notificationMaterial(): Boolean = true

    fun stackedStyle(): Boolean =
        config.optInt(MiniPlayerConfig.STYLE, MiniPlayerConfig.STYLE_ROW) == MiniPlayerConfig.STYLE_STACK

    fun smallRestHeight(): Int = if (stackedStyle())
        MiniPlayerGeometry.stackBackSizePx(shortcutDiscs.discDiameter()) else shortcutDiscs.discDiameter()

    internal fun materialKey(): String {
        MiniPlayerRuntime.islandGeneration()
        return "${if (notificationMaterial()) "notification" else "media"}:${MiniPlayerRuntime.materialStyleKey}"
    }

    /** Read first, the islands' material key: a new one is a new generation for every disc. */
    fun discMaterialGeneration(): Int =
        MiniPlayerRuntime.islandGeneration() * 2 + if (notificationMaterial()) 1 else 0

    fun smallRestWidth(): Int = if (stackedStyle())
        MiniPlayerGeometry.stackBackSizePx(pillRest(false)?.width ?: player?.width ?: shortcutDiscs.discDiameter())
    else shortcutDiscs.discDiameter()

    /** The island in the pill. The one after it is the small island beside the pill. */
    var selectedIsland: String? = null

    var smallIsland: ShortcutDisc? = null
    var smallKey: String? = null
    private var smallIconKey: Any? = null
    val smallRest = FloatArray(2)
    private val smallFollow = Matrix()
    private val smallFollowValues = FloatArray(9)
    private val lastSmallFollow = FloatArray(9)
    private val islandListener: () -> Unit = { refresh() }

    /** The row as it was last laid out: where a notification let out of it had its place. */
    private var islandOrder: List<String> = emptyList()

    /**
     * [key] - let out of the row and not among the notes for now - kept where it was in the row,
     * not at its end. Appended, the island after it was the row's first: with three islands, the
     * second pulled up out of the pill had the first's picture come up in the small place instead
     * of the third's, and it went back to the third's once the notification was home (2026-09-25).
     */
    private fun keptInPlace(base: List<String>, key: String): List<String> {
        if (key in base) return base
        val at = islandOrder.indexOf(key)
        if (at < 0) return base + key
        val before = islandOrder.subList(0, at).toSet()
        val insertAt = base.indexOfLast { it in before } + 1
        return base.toMutableList().apply { add(insertAt, key) }
    }

    /** How many islands the row has while the pill is showing; one is the pill alone. */
    fun islandCount(): Int = if (player?.visibility == View.VISIBLE) islandKeys.size else 0

    fun selectedIsMusic(): Boolean = selectedIsland == MUSIC_ISLAND

    fun allowsSideSwitch(): Boolean = !stackedStyle()

    fun allowsStackSwitch(): Boolean = stackedStyle() && islandCount() >= 2 &&
        !frameTaken() && !appearMotion.appearing

    /** The rear card takes the front; the current card goes down behind it. */
    fun stackSwipeEnd(dy: Float, velocityY: Float, cancelled: Boolean): Boolean {
        if (!allowsStackSwitch() ||
            !MiniPlayerStackGesture.commits(dy, velocityY, density(), cancelled)) return false
        val view = player ?: return false
        val oldBig = selectedIsland ?: return false
        val oldSmall = smallKey
        val next = oldSmall?.takeIf { it in islandKeys && it != oldBig }
            ?: MiniPlayerStackGesture.nextKey(islandKeys, oldBig) ?: return false
        val rest = view.restBoxOnScreen() ?: return false
        val w = rest.w * view.scaleX
        val h = rest.h * view.scaleY
        val drawn = CoverMorphMotion.Box(rest.cx() - w / 2f + view.nudgeX,
            rest.cy() - h / 2f + view.nudgeY, w, h)
        preferredSmall = null
        aloneFirst = null
        snapSmallOnce = true
        selectedIsland = next
        refresh()
        swapMotion.startSwap(oldBig, oldSmall, SWAP_STACK, pillFrom = drawn,
            flingPx = velocityY.coerceAtLeast(0f))
        return true
    }

    /**
     * The island a pull opens as a flight: the small island's, or the pill's. The music too,
     * once there are other islands in the row to stay behind; alone, it has the media card's
     * own morph (canDrag).
     */
    fun noteKeyFor(small: Boolean): String? =
        (if (small) smallKey else selectedIsland)?.takeIf { it != MUSIC_ISLAND || musicFlies() }

    /** For the touch log: the small island as a DOWN saw it, when there is one. */
    fun smallProbe(x: Float, y: Float): String? {
        val v = smallIsland ?: return null
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val dist = kotlin.math.hypot(x - xy[0] - smallRest[0], y - xy[1] - smallRest[1]).toInt()
        return "sk=${smallKey?.takeLast(12)} v=${v.visibility} grow=$smallGrowing " +
            "at=${(xy[0] + smallRest[0]).toInt()},${(xy[1] + smallRest[1]).toInt()} dist=$dist " +
            "w=${smallRestWidth()}"
    }

    /** For the touch log: why a notification could not be pulled out. */
    fun noteBusy(): String = "noteMorph=${noteMorphMotion.noteMorphKey != null} morph=${morph != null} " +
        "flight=${noteMorphMotion.flight != null} rowWait=${noteMorphMotion.rowWaitKey != null}"

    /**
     * The row's first island, swiped to the row's right end: alone in the pill, the small
     * island gone. Only while it is still the one in the pill and still first.
     */
    private var aloneFirst: String? = null

    internal fun firstAlone(keys: List<String>, selected: String?): Boolean =
        selected != null && aloneFirst == selected && keys.firstOrNull() == selected

    /**
     * Whether there is an island to switch to that way. The row has ends at both sides, each
     * an island alone in a full-width pill (2026-09-25, the user's choice): the first alone,
     * then first and second, second and third ... and the last alone. It used to go round,
     * and no island ever showed without a small one beside it.
     */
    private fun canSwitchIsland(next: Boolean): Boolean {
        val keys = islandKeys
        if (keys.size < 2) return false
        val at = keys.indexOf(selectedIsland).coerceAtLeast(0)
        return if (next) at < keys.size - 1 else at > 0 || !firstAlone(keys, selectedIsland)
    }

    /** The next island (a swipe to the left) or the one before it takes the pill. */
    fun switchIsland(next: Boolean, flingPx: Float = 0f) {
        // One island out as its card or not, a swipe switches the row's own islands: the card
        // stays as it is (2026-09-25).
        val keys = islandKeys
        if (!canSwitchIsland(next)) {
            resetIslandDrag()
            return
        }
        val oldBig = selectedIsland
        val oldSmall = smallKey
        val at = keys.indexOf(selectedIsland).coerceAtLeast(0)
        // At the first island the pill keeps it: to the left the second comes out beside it,
        // to the right the one beside it goes into hiding where it is and the pill widens.
        val kept = if (next) firstAlone(keys, oldBig) else at == 0
        // The pill as the finger left it - narrowed, and leaning toward the small island - and
        // the small island as small as the pull had made it: the switch goes on from there.
        // From the rest frame, both jumped back first and the widening started with a hitch that
        // the small island growing into the pill never had (2026-09-25).
        val oldRest = if (kept) player?.let(::drawnPillBox) else null
        val smallSide = smallIsland?.takeIf { kept && islandDragging && it.visibility == View.VISIBLE }
            ?.shapeWidth?.toFloat() ?: 0f
        preferredSmall = null
        if (kept) {
            selectedIsland = keys[0]
            aloneFirst = if (next) null else keys[0]
        } else {
            aloneFirst = null
            selectedIsland = keys[at + if (next) 1 else -1]
        }
        refresh()
        // One goes into hiding and another comes out of it, as the super island's row does
        // (SwipeEventCoordinator) - with two islands too, now that either can be alone.
        swapMotion.startSwap(oldBig, oldSmall, if (next) SWAP_NEXT else SWAP_PREV,
            pillFrom = oldRest, pillStays = kept, pillKept = kept, smallFromSide = smallSide,
            flingPx = flingPx)
    }

    /** The pill's rest frame as islandDrag has it drawn: scaled about its centre, and nudged. */
    private fun drawnPillBox(view: MiniPlayerView): CoverMorphMotion.Box? {
        val r = view.restBoxOnScreen() ?: return null
        if (!islandDragging) return r
        val w = r.w * view.scaleX
        val h = r.h * view.scaleY
        return CoverMorphMotion.Box(r.cx() - w / 2f + view.nudgeX, r.cy() - h / 2f + view.nudgeY, w, h)
    }

    /**
     * A tap on the small island opens what it stands for, as a tap on the pill would: a
     * notification comes out as its own row. The music has no row of its own to open - its
     * small island is the pill's to take, so the tap brings it into the pill.
     */
    fun selectSmallIsland() { android.os.Trace.beginSection("MC t.selectSmall"); try {
        val key = smallKey ?: return
        // The music opens the cover from the small island as from the pill.
        if (key == MUSIC_ISLAND && config.getBoolean(MiniPlayerConfig.ENABLED)) {
            cardExchange.tapIsland(key)
            return
        }
        // With one island out as its card, a tap on another opens it in that one's stead.
        if (key != MUSIC_ISLAND || expandedKey() != null) {
            noteMorphMotion.expandNote(key)
            return
        }
        val oldBig = selectedIsland
        selectedIsland = key
        refresh()
        swapMotion.startSwap(oldBig, key)
    } finally { android.os.Trace.endSection() } }

    /**
     * Whether a point on screen is on the small island's circle, a little slop included - less
     * than the gap to the camera's disc, so a tap on that disc's near edge stays the camera's.
     */
    fun smallIslandAt(x: Float, y: Float): Boolean {
        val v = smallIsland ?: return false
        // Out of sight under an exchange - the place it left, or the one it lands in - the place
        // is still the small island's to tap.
        if ((v.visibility != View.VISIBLE || smallKey == null) &&
            cardExchange.exchange?.seats?.small == null) return false
        val xy = IntArray(2).also(host::getLocationOnScreen)
        if (stackedStyle()) {
            // Only the exposed lower rim selects the rear card; the face belongs to the front.
            val front = player?.restBoxOnScreen() ?: return false
            val rear = smallBoxOnScreen()
            return x >= rear.x && x <= rear.x + rear.w &&
                y >= front.y + front.h && y <= front.y + front.h + dp(12f)
        }
        val r = shortcutDiscs.discDiameter() / 2f + minOf(dp(6f), dp(MiniPlayerGeometry.DISC_GAP_DP) * 3 / 4)
        val dx = x - (xy[0] + smallRest[0])
        val dy = y - (xy[1] + smallRest[1])
        return dx * dx + dy * dy <= r * r
    }

    /**
     * The small island: the island after the pill's, as a circle of the same glass beside it,
     * showing its picture. It sits under the pill in the host, as the super island's small
     * island sits under its big one.
     */
    private fun updateSmallIsland(music: MediaController?, notes: List<LockIslands.Note>) { android.os.Trace.beginSection("MC updateSmall"); try {
        // A notification folding back into the small island's place, or pulled out of it and
        // not let go yet, holds that place for as long as its flight is out: whatever was
        // there shrinks away and the flight lands in it. One that has left for good (a tap, a
        // committed pull) frees it: the next island comes up there while the flight is still
        // on its way, and with none the pill takes the room back - the first version held the
        // place empty till the flight had settled, and the next one popped up half a second
        // after (filmed 2026-09-25).
        val flying = noteMorphMotion.noteMorphKey?.takeIf { noteMorphMotion.flight != null }
        val held = flying?.takeIf { !flightOut }
        val keys = if (flying != null && flightOut) islandKeys - flying else islandKeys
        val preferred = preferredSmall?.takeIf { it in keys && it != selectedIsland }
        // A switch running: the small place is whoever the seats give it to - an island still on
        // its way there included, out of sight until it lands (exchangeHoldsSmall) - and nothing
        // else: with two islands, a circle came up beside the pill for every switch (2026-09-25).
        val seated = cardExchange.exchange?.seats
        // Anything else putting another island in the pill ends the first's being alone.
        if (aloneFirst != null && aloneFirst != selectedIsland) aloneFirst = null
        val key = when {
            seated != null -> seated.small?.takeIf { it != selectedIsland && it in islandKeys }
            held != null && held != selectedIsland -> held
            keys.size < 2 -> null
            preferred != null -> preferred
            // The first swiped alone, or the last with none after it: the pill alone (canSwitchIsland).
            firstAlone(keys, selectedIsland) && !stackedStyle() -> null
            stackedStyle() -> MiniPlayerStackGesture.nextKey(keys, selectedIsland)
            else -> keys.getOrNull(keys.indexOf(selectedIsland).coerceAtLeast(0) + 1)
        }
        val previous = smallKey
        smallKey = key
        // Gone from the row: updateVisibility shrinks it away rather than cutting it.
        if (key == null) return
        val d = shortcutDiscs.discDiameter()
        val frame = maxOf(shortcutDiscs.discFrame(d), smallRestWidth() + dp(16f))
        val view = smallIsland ?: ShortcutDisc(context).also {
            smallIsland = it
            // Right after the lock screen's layer, which puts it just under the pill.
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(frame, shortcutDiscs.discFrame(d)))
            Gen.ownView(host, it)
        }
        val frameHeight = shortcutDiscs.discFrame(d)
        if (!smallWide && (view.layoutParams.width != frame || view.layoutParams.height != frameHeight))
            resizeSmallIsland(view, frame, frameHeight)
        view.dress(discMaterialGeneration()) {
            MiniPlayerRuntime.material(it, loader, notificationMaterial())
        }
        view.setIconHidden(false)
        if (swapMotion.swap == null && !islandDragging && !smallGrowing && landingBox == null)
            view.setShape(smallRestWidth(), smallRestHeight())
        // The one showing, going for a flight coming in, goes as itself: it keeps its picture
        // while it shrinks away. It used to take the incoming one's at once - and, asked only
        // while it still showed, took it anyway on the next refresh, mid-shrink. It takes the
        // flight's picture once the flight is landing on it (flightLanding).
        if (key == held && !flightLanding) return
        val showing = view.visibility == View.VISIBLE && !(smallGrowing && smallGrow.target == 0f)
        val note = if (key == MUSIC_ISLAND) null
            else notes.firstOrNull { it.key == key } ?: LockIslands.noteFor(key)
        // A focus template's own picture for the small island, moving if it moves; bare as the
        // super island draws it.
        // Every notification's picture comes cut or whole already (LockIslands.roundIcon).
        if (stackedStyle()) {
            val metadata = music?.let(::metadataOf)
            view.setCardText(
                note?.timer?.displayText() ?: note?.title ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "正在播放" },
                note?.text ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
        } else view.setCardText(null, null)
        view.setIconBare(note != null)
        // The pill's own Lottie, not the plugin's smaller one for its small island: the two
        // files draw at different sizes on their canvases, and in the same box the small island's
        // stopwatch came out smaller than the pill's (2026-09-25).
        val moving = binding.focusLottie(view, note?.anim)
        view.setLive(binding.liveArt(view, note?.live))
        val picture: Any? = if (key == MUSIC_ISLAND) (binding.thumbShown ?: cachedCover) else moving ?: note?.icon
        // Another island in a small island that is showing, nothing else moving it (a switch
        // animates its own): it comes up anew in the place rather than just changing its picture.
        if (previous != null && previous != key && showing && swapMotion.swap == null && !islandDragging &&
            held == null && !snapSmallOnce) regrowSmall()
        if (picture !== smallIconKey) {
            smallIconKey = picture
            view.setIcon(when (picture) {
                is Bitmap -> android.graphics.drawable.BitmapDrawable(context.resources, picture)
                is android.graphics.drawable.Drawable -> picture
                else -> null
            })
        }
    } finally { android.os.Trace.endSection() } }

    private fun hideSmallIsland() {
        clearSmallNudge()
        smallKey = null
        smallGrowing = false
        smallIsland?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    /** A notification just folded back in, not yet given back by the stack: it keeps its place. */
    internal val returning = LinkedHashMap<String, Long>()

    /** The notification last folded back in: it stays the small island until the row moves on. */
    var preferredSmall: String? = null

    /** The next visibility pass shows the small island at once: a flight has just landed there. */
    var snapSmallOnce = false

    /**
     * The flight has left the small island's place for good - a tap, or a pull let go past
     * the threshold - and the place is the row's again. Until then it is the flight's to come
     * back to.
     */
    internal var flightOut = false

    /**
     * The flight is on its last stretch home: the real small island is already in its place
     * under it and the flight fades off it. Swapped only once it had landed, the glass's
     * refraction and the picture both changed on that frame (filmed 2026-09-25).
     */
    internal var flightLanding = false

    /**
     * Landing, the small island is the flight's shape frame by frame - through the spring's
     * overshoot and back - and not its own rest circle: the flight fades off an island moving
     * exactly as it does. Put at rest under the flight, the small island stood still while the
     * flight went through the overshoot invisible, and the landing had no give at all, where
     * the pill's has (2026-09-25). On screen; null when not landing.
     */
    var landingBox: CoverMorphMotion.Box? = null

    /**
     * The small island's pulse, the super island's smallIslandScaleAnimation: when the island out
     * as its card goes back into hiding - no place in the row for it - the small island swells to
     * 1.1 and back, to say the row still holds something (triggerScaleAnimationAfterExpandedHidden,
     * the phone's; a tablet pulses the big island). Each way sinInOut 200ms after 100ms, as
     * FolmeEase.sinInOut(200) with the 100ms delay the plugin gives both halves. Stopped when
     * the small place changes hands.
     */
    private var pulseKey: String? = null
    private var pulseAt = 0L

    private val pulseFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC pulse"); try {
            if (pulseKey == null) return
            smallPulse()
            followShortcuts()
            if (pulseKey != null) Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    internal fun startSmallPulse(key: String?) {
        if (key == null) return
        pulseKey = key
        pulseAt = android.os.SystemClock.uptimeMillis()
        Gen.unframe(pulseFrame)
        Gen.frame(pulseFrame)
        MiniPlayerRuntime.noteTouch("pulse small=${key.takeLast(6)}")
    }

    /** The pulse's scale now; 1 with none, and ends it once over or once the small place is another's. */
    private fun smallPulse(): Float {
        val key = pulseKey ?: return 1f
        if (smallKey != key) {
            pulseKey = null
            return 1f
        }
        val t = (android.os.SystemClock.uptimeMillis() - pulseAt).toFloat()
        fun sinInOut(x: Float) = (1f - kotlin.math.cos(Math.PI.toFloat() * x.coerceIn(0f, 1f))) / 2f
        val up = PULSE_DELAY_MS + PULSE_MS
        val hold = up + PULSE_DELAY_MS
        return when {
            t < PULSE_DELAY_MS -> 1f
            t < up -> 1f + (PULSE_SCALE - 1f) * sinInOut((t - PULSE_DELAY_MS) / PULSE_MS)
            t < hold -> PULSE_SCALE
            t < hold + PULSE_MS -> PULSE_SCALE - (PULSE_SCALE - 1f) * sinInOut((t - hold) / PULSE_MS)
            else -> {
                pulseKey = null
                1f
            }
        }
    }

    /** The small island on its rest place, carried by the row's motion as the pill is. */
    internal fun placeSmallIsland(follow: Matrix?, fade: Float) {
        val v = smallIsland ?: return
        if (v.visibility != View.VISIBLE) return
        // Its frame is centred on its rest place; a switch only widens the frame, never moves it.
        val fw = if (v.width > 0) v.width else v.layoutParams.width
        val fh = if (v.height > 0) v.height else v.layoutParams.height
        landingBox?.let { box ->
            // The flight's shape, where the flight draws it: the morph places both.
            val xy = IntArray(2).also(host::getLocationOnScreen)
            val lw = maxOf(v.layoutParams.width, fw)
            val lh = maxOf(v.layoutParams.height, fh)
            v.translationX = box.cx() - xy[0] - lw / 2f - v.left
            v.translationY = box.cy() - xy[1] - lh / 2f - v.top
            v.scaleX = 1f
            v.scaleY = 1f
            // Whole: it may have been shrinking away for the flight when the flight came in -
            // left at that alpha (0.08), the landing had nothing under the flight (2026-09-25).
            if (v.alpha != 1f) v.alpha = 1f
            v.setIconAlpha(1f)
            v.setShape(box.w.roundToInt().coerceAtLeast(1), box.h.roundToInt().coerceAtLeast(1), 0)
            if (lastSmallFollow.any { it != 0f }) {
                lastSmallFollow.fill(0f)
                v.setAnimationMatrix(null)
            }
            if (kotlin.math.abs(v.transitionAlpha - fade) > 0.002f) v.transitionAlpha = fade
            return
        }
        // The camera pushing on the row takes the small island back first: it is the row's end.
        // A push on it moves it aside.
        val d = shortcutDiscs.discDiameter().toFloat()
        val stacked = stackedStyle()
        // A stacked rear card shares the front card's slot. It is not a second place in the
        // squeeze chain, and follows the front card's pressure instead of reserving space right.
        val offX = if (stacked) shortcutDiscs.squeeze.pillShiftPx() + swapMotion.smallDx + smallNudgeX()
            else -shortcutDiscs.squeeze.rowGivePx(1) + swapMotion.smallDx + smallNudgeX() +
                shortcutDiscs.squeeze.smallShift() * d + shortcutDiscs.squeeze.smallPushPx()
        val offY = smallNudgeY()
        var tx = smallRest[0] - fw / 2f - v.left + offX
        var ty = smallRest[1] - fh / 2f - v.top + offY
        // Flattened by the pill, by its shape - a scale would draw its edge jagged - when nothing
        // else is shaping it this frame.
        if (swapMotion.swap == null && !smallGrowing && !islandDragging && groupMotion.group?.inPlace != true) {
            val shapeX = if (stacked) shortcutDiscs.squeeze.pillScaleX(player?.width?.toFloat() ?: 0f)
                else shortcutDiscs.squeeze.smallShapeX()
            val shapeY = if (stacked) shortcutDiscs.squeeze.pillScaleY() else shortcutDiscs.squeeze.smallShapeY()
            v.setShape((smallRestWidth() * shapeX).roundToInt().coerceAtLeast(1),
                (smallRestHeight() * shapeY).roundToInt().coerceAtLeast(1), 0)
            v.alpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        }
        // A finger on it sinks it about its centre, which is its frame's centre; the pulse
        // swells it the same way.
        val swell = shortcutDiscs.squeeze.smallSwell() * smallPulse()
        if (kotlin.math.abs(v.scaleX - swell) > 0.0005f) {
            v.scaleX = swell
            v.scaleY = swell
        }
        // At rest on whole pixels, as the pill and the discs are, or its rim is cut into steps.
        if (offX == 0f && offY == 0f && swell == 1f && (follow == null || follow.isIdentity)) {
            tx = kotlin.math.round(tx)
            ty = kotlin.math.round(ty)
            if (v.translationX != tx) v.translationX = tx
            if (v.translationY != ty) v.translationY = ty
        }
        if (kotlin.math.abs(v.translationX - tx) > 0.25f) v.translationX = tx
        if (kotlin.math.abs(v.translationY - ty) > 0.25f) v.translationY = ty
        if (follow == null || follow.isIdentity) {
            if (lastSmallFollow.any { it != 0f }) {
                lastSmallFollow.fill(0f)
                v.setAnimationMatrix(null)
            }
        } else {
            smallFollow.set(follow)
            smallFollow.preTranslate(v.left.toFloat(), v.top.toFloat())
            smallFollow.postTranslate(-v.left.toFloat(), -v.top.toFloat())
            smallFollow.getValues(smallFollowValues)
            if (!smallFollowValues.contentEquals(lastSmallFollow)) {
                System.arraycopy(smallFollowValues, 0, lastSmallFollow, 0, 9)
                v.setAnimationMatrix(smallFollow)
            }
        }
        if (kotlin.math.abs(v.transitionAlpha - fade) > 0.002f) v.transitionAlpha = fade
    }

    /**
     * The small island under a finger that has not opened or switched anything: it follows,
     * rubber-banded as the pill is, any way round, pressing on the camera and the pill; let go,
     * it springs home. In hundreds of pixels, as the pill's nudge spring runs.
     */
    private val smallNudgeXs = Jelly(SMALL_NUDGE_RESPONSE, SMALL_NUDGE_DAMPING)
    private val smallNudgeYs = Jelly(SMALL_NUDGE_RESPONSE, SMALL_NUDGE_DAMPING)
    private var smallNudging = false
    private var smallNudgeLast = 0L

    fun smallNudgeX() = smallNudgeXs.value * 100f
    fun smallNudgeY() = smallNudgeYs.value * 100f

    private val smallNudgeFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC smallNudge"); try {
            if (!smallNudging) return
            val dt = if (smallNudgeLast == 0L) 1f / 120f
                else ((frameTimeNanos - smallNudgeLast) / 1e9f).coerceIn(0f, 0.05f)
            smallNudgeLast = frameTimeNanos
            smallNudgeXs.step(dt)
            smallNudgeYs.step(dt)
            if (smallNudgeXs.atRest() && smallNudgeYs.atRest()) smallNudging = false
            else Gen.frame(this)
            nudgedSmall()
        } finally { android.os.Trace.endSection() } }
    }

    /** Where the finger has pulled the small island to, in pixels; the caller rubber-bands it. */
    fun setSmallNudge(x: Float, y: Float) {
        holdSmallNudge()
        smallNudgeXs.value = x / 100f; smallNudgeXs.target = smallNudgeXs.value; smallNudgeXs.velocity = 0f
        smallNudgeYs.value = y / 100f; smallNudgeYs.target = smallNudgeYs.value; smallNudgeYs.velocity = 0f
        nudgedSmall()
    }

    /** A finger on a small island still springing home: it stays where it is, under the finger. */
    fun holdSmallNudge() {
        smallNudging = false
        Gen.unframe(smallNudgeFrame)
    }

    val smallNudgeNow: Pair<Float, Float> get() = smallNudgeX() to smallNudgeY()

    /** Home with the finger's own speed, so a fling carries past the place and back. */
    fun springSmallNudgeBack(vx: Float, vy: Float) {
        if (smallNudgeXs.value == 0f && smallNudgeYs.value == 0f) return
        smallNudgeXs.target = 0f
        smallNudgeYs.target = 0f
        smallNudgeXs.velocity = vx / 100f
        smallNudgeYs.velocity = vy / 100f
        if (!smallNudging) {
            smallNudging = true
            smallNudgeLast = 0L
            Gen.frame(smallNudgeFrame)
        }
    }

    /**
     * Straight home: the small island is going, or something else is moving it now. Only the
     * springs: the next frame places it. Laying the row out from here brought the discs back
     * on a controller being destroyed, which removes them first.
     */
    fun clearSmallNudge() {
        holdSmallNudge()
        for (spring in arrayOf(smallNudgeXs, smallNudgeYs)) {
            spring.value = 0f
            spring.target = 0f
            spring.velocity = 0f
        }
    }

    /** The row again, this frame: the squeeze first, then the small island placed by it. */
    private fun nudgedSmall() {
        shortcutDiscs.updateDiscs()
        if (player?.visibility == View.VISIBLE) followShortcuts()
    }

    private fun removeSmallIsland() {
        clearSmallNudge()
        swapMotion.ghostPill?.let { runCatching { host.removeView(it) } }
        swapMotion.ghostPill = null
        swapMotion.ghostDisc?.let { runCatching { host.removeView(it) } }
        swapMotion.ghostDisc = null
        smallIsland?.let { runCatching { host.removeView(it) } }
        smallIsland = null
        smallKey = null
        smallIconKey = null
    }

    internal val swapMotion = SwapMotion(this)

    /** The small island on screen, at rest. */
    fun smallBoxOnScreen(): CoverMorphMotion.Box {
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val d = smallRestHeight().toFloat()
        val w = smallRestWidth().toFloat()
        return CoverMorphMotion.Box(xy[0] + smallRest[0] - w / 2f,
            xy[1] + smallRest[1] - d / 2f, w, d)
    }



    /** Room in the small island's frame for a shape as wide as the pill, where the pill is. */
    fun widenSmallIsland(rest: CoverMorphMotion.Box) {
        val small = smallIsland ?: return
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val reach = kotlin.math.abs(rest.cx() - xy[0] - smallRest[0]) + rest.w / 2f
        val wide = maxOf(shortcutDiscs.discFrame(shortcutDiscs.discDiameter()), (reach * 2f + dp(16f)).roundToInt())
        resizeSmallIsland(small, wide, small.layoutParams.height)
        smallWide = true
    }

    /**
     * The small island's frame to a new size now, about the centre it is drawn at. Set only in
     * its layout params, the size took the next layout pass, and for the frame before it the
     * island was placed by one size and drawn at the other - a jump sideways as it grew for a
     * flight or went back to its circle. From TakeKazeX's PR #15.
     */
    fun resizeSmallIsland(small: ShortcutDisc, width: Int, height: Int) {
        if (small.layoutParams.width != width || small.layoutParams.height != height) {
            small.layoutParams = small.layoutParams.apply { this.width = width; this.height = height }
        }
        if (small.width == width && small.height == height) return
        val cx = small.x + small.width / 2f
        val cy = small.y + small.height / 2f
        small.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        small.layout(small.left, small.top, small.left + width, small.top + height)
        small.translationX = cx - small.left - width / 2f
        small.translationY = cy - small.top - height / 2f
    }

    internal var smallWide = false

    fun restoreSmallIslandShape() {
        val small = smallIsland ?: return
        val d = shortcutDiscs.discDiameter()
        val frame = maxOf(shortcutDiscs.discFrame(d), smallRestWidth() + dp(16f))
        val frameHeight = shortcutDiscs.discFrame(d)
        if (smallWide || small.layoutParams.width != frame || small.layoutParams.height != frameHeight)
            resizeSmallIsland(small, frame, frameHeight)
        smallWide = false
        small.setShape(smallRestWidth(), smallRestHeight(), 0)
        small.setIconAlpha(1f)
        small.alpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
    }

    fun lerpBox(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box, t: Float) =
        CoverMorphMotion.Box(lerp(a.x, b.x, t), lerp(a.y, b.y, t),
            maxOf(1f, lerp(a.w, b.w, t)), maxOf(1f, lerp(a.h, b.h, t)))

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---- the row's layout, animated

    /**
     * The pill's place in the row, sprung: every change of the row's layout - a small island
     * coming or going, the pill giving room to a notification folding back in, taking it back
     * when one leaves - runs on the super island's CHANGE_EASE rather than jumping, as its
     * container clip does for each of those transitions. position() sets the target; the pill's
     * own layout is already at the target size, and the frame is drawn at the spring's.
     */
    val rowLeft = Jelly(SWAP_RESPONSE, ROW_DAMPING)
    val rowWidth = Jelly(SWAP_RESPONSE, ROW_DAMPING)
    var rowKnown = false
    var rowAnimating = false
    private var rowLast = 0L

    private val rowFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC row"); try {
            if (!rowAnimating) return
            val dt = if (rowLast == 0L) 1f / 120f
                else ((frameTimeNanos - rowLast) / 1e9f).coerceIn(0f, 0.05f)
            rowLast = frameTimeNanos
            rowLeft.step(dt)
            rowWidth.step(dt)
            // Under the media card's morph the morph draws the pill; under a switch the switch does.
            if (groupMotion.group == null && swapMotion.swap == null) applyRow()
            if (rowLeft.atRest() && rowWidth.atRest()) endRow()
            else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /** position(): the pill's rest place in the host is now [left] and [width]. */
    private fun rowTarget(left: Float, width: Float) {
        val view = player
        // A flight's morph moves the flight, not the pill: the pill still gives room on its
        // spring. Counted busy, it jumped to its new width the moment a flight set out.
        // A media card's morph lands on the row as it is laid out, so the row is put there at once.
        val pillMorphing = morph != null && noteMorphMotion.flight == null || groupMotion.group != null
        val busy = swapMotion.swap != null || pillMorphing || view == null || view.visibility != View.VISIBLE
        if (!rowKnown || busy) {
            // Nothing drawn to move from, or another motion owns the frame: straight there.
            rowLeft.value = left; rowLeft.target = left; rowLeft.velocity = 0f
            rowWidth.value = width; rowWidth.target = width; rowWidth.velocity = 0f
            rowKnown = view != null && view.visibility == View.VISIBLE
            if (rowAnimating) endRow()
            return
        }
        if (kotlin.math.abs(rowLeft.target - left) < 0.5f && kotlin.math.abs(rowWidth.target - width) < 0.5f) return
        rowLeft.target = left
        rowWidth.target = width
        if (!rowAnimating) {
            rowAnimating = true
            rowLast = 0L
            applyRow()
            Gen.frame(rowFrame)
        }
    }

    // ---- the row coming up out of nothing, and going back into it

    internal val appearMotion = AppearMotion(this)
    /** The clock going into the cover or out of it: the row is put away or brought back by that, not by its islands. */
    private fun coverMoving(): Boolean = Main.coverSceneActive() && ClockCollapse.phase().let {
        // The cover's clock only: 高德's map moves the same clock and puts nothing away.
        it == ClockCollapse.Phase.ENTER || it == ClockCollapse.Phase.EXIT
    }

    /** The pill is on screen enough to be seen changing: lit or held into the AOD, the keyguard staying. */
    fun pillSeen(): Boolean = !MiniPlayerScene.keyguardGoingAway && followPillFade > 0.5f &&
        (Main.screenOnCached() || MiniPlayerScene.aodActive)


    /** Nothing else has the pill's frame or is moving the row: a coming-up of its own is right. */
    private fun rowQuiet(): Boolean = !frameTaken() &&
        !rowHeldOff && followRowFade >= 0.99f && !MiniPlayerScene.aodActive

    internal val probe = MiniPlayerProbe(this)
    fun rowTree(match: String): String = probe.rowTree(match)
    fun describe(): String = probe.describe()
    fun describeFingerprint(): String = probe.describeFingerprint()

    /** Another motion is drawing the pill's frame, and ends its own morph when it is done. */
    fun frameTaken(): Boolean = swapMotion.swap != null || morph != null || groupMotion.group != null || noteMorphMotion.flight != null ||
        cardExchange.exchange != null || noteMorphMotion.noteMorphKey != null || noteMorphMotion.pillLandingBox != null || spreadMotion.spread != null



    /**
     * The pill's last notification gone, and what is left for the row is nothing it would show:
     * no island, or only the music out as its media card. The pill goes back into its circle
     * with the notification it had. Bound to the music and switched first, the way it would
     * be for a row the music stays in, it put the music in the pill, grew it from its end, and
     * then the row went at once - on a lock screen with a song on its card, the torch's island
     * switched off vanished in a frame (2026-09-29).
     */
    private fun rowGoing(music: MediaController?): Boolean {
        val shown = selectedIsland ?: return false
        if (shown == MUSIC_ISLAND || shown in islandKeys || islandKeys.any { it != MUSIC_ISLAND }) return false
        if (player?.visibility != View.VISIBLE) return false
        return islandKeys.isEmpty() || musicCarded(music)
    }

    /**
     * The music is out of the row as its card: the media card, or the cover - refresh's
     * `carded`. The cover was left out here, so with a song on the cover the torch's island
     * switched off handed the pill to the music, which the cover hides at once: every one went
     * in a frame (2026-09-29, `op mini`: CUT sel=music cover=true after each).
     */
    private fun musicCarded(music: MediaController?): Boolean =
        music != null && (MiniPlayerRuntime.nativeRequested(music.sessionToken) || Main.coverModeOn())




    fun applyRow() {
        val view = player ?: return
        val rest = view.restBoxOnScreen() ?: return
        if (noteMorphMotion.pillLandingBox != null) return
        // Coming up out of nothing: that frame carries the row's spring (applyAppear).
        if (appearMotion.appearing) return
        // Also after a media card's morph has handed the pill back mid-spring.
        if (!view.inMorph()) view.beginMorph(layoutOnly = true)
        val xy = IntArray(2).also(host::getLocationOnScreen)
        // Soft, as the pill is everywhere else: widening fast it thins, and coming back off its
        // overshoot it thickens - a squeeze that follows the width's own speed. On its spring
        // alone the pill slid to its new width like a rigid bar (2026-09-25).
        val squash = (rowWidth.velocity / rest.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = rest.h * (1f - squash)
        val box = CoverMorphMotion.Box(xy[0] + rowLeft.value, rest.y + (rest.h - h) / 2f,
            rowWidth.value.coerceAtLeast(h), h)
        view.setMorphFrame(box, box.h / 2f, 1f)
    }

    /** The row's spring hands the frame back: at rest, or to another motion taking it. */
    fun endRow() {
        if (!rowAnimating) return
        rowAnimating = false
        Gen.unframe(rowFrame)
        rowLeft.value = rowLeft.target; rowLeft.velocity = 0f
        rowWidth.value = rowWidth.target; rowWidth.velocity = 0f
        // The media card's morph owns the pill's frame: not the row's to hand back. Nor is a
        // coming-up still growing.
        if (groupMotion.group == null && !appearMotion.appearing) player?.endMorph()
    }

    internal val groupMotion = GroupMotion(this)
    fun anticipateRows(small: Boolean) = groupMotion.anticipateRows(small)
    fun stopAnticipating() = groupMotion.stopAnticipating()
    /**
     * A view of its own for the small island's place through a morph, bound to [key]'s island.
     * [pooled]: one kept from an earlier switch (spareViews) when there is one. A new one cost
     * 7ms - its material put on and its lines laid out for the first time - and a switch takes
     * two, on the frame it starts: the frame dropped on every tap (traced 2026-09-25, 14 of the
     * frame's 19ms). Kept, its material is already on and only what changed is bound again.
     */
    fun prepareSmallView(key: String, pooled: Boolean = false): MiniPlayerView? {
        val pill = player ?: return null
        val music = controller?.takeIf(::isUsable)
        val note = if (key == MUSIC_ISLAND) null
            else LockIslands.notes.firstOrNull { it.key == key } ?: LockIslands.noteFor(key)
        if (if (key == MUSIC_ISLAND) music == null else note == null) return null
        val w = pill.layoutParams.width.coerceAtLeast(1)
        val h = pill.layoutParams.height.coerceAtLeast(1)
        val spare = if (pooled) takeSpare() else null
        val view = spare ?: traced("MC sv.new") { MiniPlayerView(context) }
        if (spare == null) {
            traced("MC sv.add") { host.addView(view, lockScreenLayerIndex(), ViewGroup.LayoutParams(w, h)) }
            Gen.ownView(host, view)
        } else view.layoutParams.takeIf { it.width != w || it.height != h }?.let {
            it.width = w
            it.height = h
        }
        traced("MC sv.bind") { if (music != null && note == null) binding.bindMusic(view, music, config) else binding.bindNote(view, note!!, config) }
        // Laid out here and now, as a flight is: its morph starts on this frame.
        if (spare == null || view.isLayoutRequested || view.width != w || view.height != h) traced("MC sv.layout") {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        // Its second button as the pill has it: a pooled view kept whatever its last switch left,
        // and one flying home to the pill came down without its left button and grew it in
        // after landing - gone for every tap of a run of them (filmed 2026-09-26).
        view.setSecondShown(smallKey == null, animate = false)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        return view
    }

    /**
     * [view], landing in the pill, laid out as the pill will be once it is there: the row's width
     * with a small island beside it ([small]) or alone, and the second button that goes with it.
     * A stand-in made when the switch set out was laid out for the row as it was - the stopwatch
     * came into the big island's frame as the middle island, one button beside its text, and on
     * landing turned into the big island in a frame: the left button there whole, the right one
     * jumped to the end, the text let out (filmed 2026-09-26). Laid out mid-morph, it keeps its
     * frame (MiniPlayerView.onLayout); the morph moves what is in it.
     */
    internal fun fitToPill(view: MiniPlayerView, small: Boolean) {
        val rest = pillRest(small) ?: return
        val lp = view.layoutParams ?: return
        if (lp.width != rest.width || lp.height != rest.height) {
            lp.width = rest.width
            lp.height = rest.height
            view.layoutParams = lp
        }
        if (view.isLayoutRequested) {
            view.measure(View.MeasureSpec.makeMeasureSpec(rest.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rest.height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, rest.width, rest.height)
        }
        view.setSecondShown(!small, animate = false)
    }

    /** Views a switch has finished with, kept out of sight in the host for the next one. */
    internal val spareViews = ArrayList<MiniPlayerView>()

    /** A stack can spread several cards at once; prepare their views across idle frames. */
    private fun spareLimit(): Int = if (stackedStyle()) STACK_SPARE_VIEWS else SPARE_VIEWS

    internal fun takeSpare(): MiniPlayerView? {
        while (spareViews.isNotEmpty()) {
            val v = spareViews.removeAt(spareViews.size - 1)
            if (v.parent === host) return v
        }
        return null
    }

    /** Done with [v]: kept for the next switch, or let go if two are kept already. */
    fun recycleSmallView(v: MiniPlayerView) {
        if (v.parent !== host || spareViews.size >= spareLimit() || v in spareViews) {
            runCatching { host.removeView(v) }
            return
        }
        v.visibility = View.GONE
        v.alpha = 1f
        v.translationZ = 0f
        v.setArtworkHidden(false)
        spareViews += v
    }

    /**
     * Even one island can open into a card: its flight is made while
     * the thread is idle, bound to an island so their material is on, and laid out, not on the
     * tap's frame.
     */
    private fun prewarmSpares() {
        val wanted = min(islandKeys.size, spareLimit())
        if (destroyed || prewarmPosted || spareViews.size >= wanted) return
        prewarmPosted = true
        android.os.Looper.myQueue().addIdleHandler {
            prewarmPosted = false
            if (!destroyed && host.isAttachedToWindow && cardExchange.exchange == null &&
                spareViews.size < min(islandKeys.size, spareLimit())) {
                val key = islandKeys.firstOrNull()
                val warmed = traced("MC sv.prewarm") { key?.let(::prepareSmallView) }
                if (warmed != null) {
                    recycleSmallView(warmed)
                    // One a pass; don't keep retrying an island whose content is unavailable.
                    prewarmSpares()
                }
            }
            false
        }
    }

    private var prewarmPosted = false

    /** The notifications' keys behind the row's islands: the stack island's each, the others themselves. */
    internal fun rowKeys(): List<String> = LockIslands.notes.flatMap { LockIslands.membersOf(it.key) }

    /**
     * The stack island's notification whose row it opens into and comes home from: the one
     * pulled down (releasedRowAt), else the newest - the one it shows.
     */
    internal var stackLeadKey: String? = null
    internal val appLeadKeys = HashMap<String, String>()

    internal fun groupLead(key: String): String? {
        val members = LockIslands.membersOf(key)
        val preferred = (if (key == STACK_ISLAND) stackLeadKey else appLeadKeys[key])
            ?.takeIf { it in members }
        if (preferred != null && hasLaidOutRow(preferred)) return preferred
        // A filter after ours can still keep a member out of the stack: the island opened into a
        // row that was never there, and waited it out, while the others' rows stood ready (PR #15).
        return members.firstOrNull(::hasLaidOutRow)?.also {
            if (key == STACK_ISLAND) stackLeadKey = it else appLeadKeys[key] = it
        }
            ?: preferred ?: members.firstOrNull()
    }

    private fun hasLaidOutRow(key: String): Boolean = stackHost.findRow(key)?.let { (row, top) ->
        row.isAttachedToWindow && row.height > 0 && top.height > 0 &&
            row.visibility == View.VISIBLE && top.visibility == View.VISIBLE
    } == true


    // ---- the small island coming and going

    /** The small island's presence, 0 gone to 1 there, sprung the same way. */
    val smallGrow = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
    var smallGrowing = false
    private var smallGrowLast = 0L

    val smallGrowFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC smallGrow"); try {
            if (!smallGrowing) return
            val dt = if (smallGrowLast == 0L) 1f / 120f
                else ((frameTimeNanos - smallGrowLast) / 1e9f).coerceIn(0f, 0.05f)
            smallGrowLast = frameTimeNanos
            smallGrow.step(dt)
            applySmallGrow()
            if (smallGrow.atRest()) {
                smallGrowing = false
                if (smallGrow.target == 0f) smallIsland?.visibility = View.GONE
                else restoreSmallIslandShape()
            } else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * The small island shown or not: coming up it grows out of nothing in its place, going it
     * shrinks away there - unless the row itself is going, when it just goes with it.
     */
    fun setSmallShown(shown: Boolean, animate: Boolean) {
        val small = smallIsland ?: return
        val visible = small.visibility == View.VISIBLE
        if (visible != shown) {
            trace("small ${if (shown) "show" else "hide"} animate=$animate key=${smallKey?.takeLast(6)} " +
                "noteMorph=${noteMorphMotion.noteMorphKey?.takeLast(6)} out=$flightOut landing=$flightLanding group=${groupMotion.group != null}")
        }
        if (shown && visible && (!smallGrowing || smallGrow.target == 1f)) return
        if (!shown && (!visible || (smallGrowing && smallGrow.target == 0f))) return
        if (!animate || (swapMotion.swap?.smallMode ?: SMALL_KEPT) != SMALL_KEPT) {
            smallGrowing = false
            Gen.unframe(smallGrowFrame)
            smallGrow.value = if (shown) 1f else 0f
            smallGrow.target = smallGrow.value
            small.visibility = if (shown) View.VISIBLE else View.GONE
            if (shown && landingBox == null) restoreSmallIslandShape()
            return
        }
        if (shown && !visible) {
            smallGrow.value = 0f
            smallGrow.velocity = 0f
            small.visibility = View.VISIBLE
        }
        smallGrow.target = if (shown) 1f else 0f
        if (!smallGrowing) {
            smallGrowing = true
            smallGrowLast = 0L
            applySmallGrow()
            Gen.frame(smallGrowFrame)
        }
    }

    /** Another island in the small island's place: it comes up there from nothing. */
    private fun regrowSmall() {
        smallGrow.value = 0f
        smallGrow.velocity = 0f
        smallGrow.target = 1f
        if (!smallGrowing) {
            smallGrowing = true
            smallGrowLast = 0L
            Gen.frame(smallGrowFrame)
        }
        applySmallGrow()
    }

    internal fun applySmallGrow() {
        val small = smallIsland ?: return
        val w = smallRestWidth().toFloat()
        val v = smallGrow.value
        val width = lerp(w * 0.4f, w, v).coerceAtLeast(1f).roundToInt()
        val height = lerp(smallRestHeight() * 0.4f, smallRestHeight().toFloat(), v)
            .coerceAtLeast(1f).roundToInt()
        small.setShape(width, height, 0)
        small.setIconAlpha(MiniCardMorph.smooth(0.3f, 1f, v))
        small.alpha = MiniCardMorph.smooth(0f, 0.5f, v) *
            if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
    }

    // ---- the finger on a row of islands

    private var islandDragging = false

    /**
     * The finger pulling sideways on a row of islands, as the super island follows it
     * (IslandSwipeAnimator): progress is the pull over half the width, times 0.14. The pill
     * narrows by that share; pulled toward the small island it leans that way, pulled away the
     * small island reaches toward it, by a quarter of the pill's width. Neither fades as the
     * super island's do: let go, the switch took over at full opacity and the pill flashed
     * (2026-09-25, the user had the fade taken out).
     */
    fun islandDrag(dx: Float) {
        islandDragStep(dx)
    }

    private fun islandDragStep(dx: Float) {
        val view = player ?: return
        // A switch still settling is finished where it was headed: the finger has the row now.
        if (swapMotion.swap != null) swapMotion.endSwap()
        if (!islandDragging) springSmallNudgeBack(0f, 0f)
        islandDragging = true
        // The row's end: nothing to switch to, so the pull takes the pill with it instead - it
        // runs into the torch or the camera there and they give way, as they do for a lone
        // island's pull (the user, 2026-09-29).
        val atEnd = !canSwitchIsland(next = dx < 0f)
        if (atEnd) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.setNudge(pillNudgeX(dx, host.resources.displayMetrics.density), 0f)
            // Back from a pull the other way, the small island leaves the shape it had for it.
            if (smallIsland?.visibility == View.VISIBLE && !smallGrowing) restoreSmallIslandShape()
            return
        }
        val p = (kotlin.math.abs(dx) / (host.width / 2f).coerceAtLeast(1f) * SWIPE_SHARE)
            .coerceAtMost(SWIPE_SHARE * 1.5f)
        val towardSmall = dx > 0f
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = 1f - p
        view.scaleY = 1f - p * 0.25f
        val small = smallIsland?.takeIf { it.visibility == View.VISIBLE } ?: return
        val d = shortcutDiscs.discDiameter().toFloat()
        val w = view.width.toFloat()
        if (towardSmall) {
            view.setNudge((smallRest[0] - (view.left + view.translationX + w / 2f)) * p, 0f)
            val side = (d * (1f - 0.2f * p / SWIPE_SHARE)).roundToInt()
            small.setShape(side, side, 0)
        } else {
            view.setNudge(0f, 0f)
            val rest = view.restBoxOnScreen() ?: return
            widenSmallIsland(rest)
            small.setShape((d + w * p * 0.5f).roundToInt(), d.roundToInt(), -(w * p * 0.25f).roundToInt())
            small.alpha = 1f
        }
    }

    /** The finger is off: over the threshold the switch runs, under it the row springs back. */
    fun islandDragEnd(commit: Boolean, next: Boolean, vx: Float = 0f) {
        if (!islandDragging) return
        // Past the row's end there is nothing to switch to: it springs back as from a short pull.
        val switchable = canSwitchIsland(next)
        if (commit && switchable) {
            // A fling the way of the switch hands the switch its speed; one back against it none.
            switchIsland(next, flingPx = if (next == vx < 0f) kotlin.math.abs(vx) else 0f)
            return
        }
        val view = player ?: return
        islandDragging = false
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(SPRING_BACK_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1.05f))
            .start()
        view.springNudgeBack(0f, 0f)
        restoreSmallIslandShape()
    }

    fun resetIslandDrag() {
        if (!islandDragging) return
        islandDragging = false
        player?.let {
            it.animate().cancel()
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
            it.clearNudge()
        }
    }

    // ------------------------------------------------------------ one island out as its card

    /**
     * The music has other islands in the row to stay behind when it goes out as its card: it
     * goes out and comes back as a flight, as a notification does, and they keep the row.
     * Alone it has the media card's own morph, the pill and the card one or the other.
     */
    private fun musicFlies(): Boolean = !Main.coverSceneActive() && othersBesideMusic()

    /** There are islands other than the music's, in the row or out as their rows. */
    private fun othersBesideMusic(): Boolean {
        if (controller?.takeIf(::isUsable) == null) return false
        // A notification out as its row counts: the music going out takes its place.
        return LockIslands.notes.isNotEmpty() || islandKeys.any { it != MUSIC_ISLAND } ||
            LockIslands.releasedKeys().any { stackHost.rowFor(it) != null }
    }

    /**
     * Busy for a flight into or out of the cover or the lyrics: the motions that keep it from
     * starting (M0, the subset as it was).
     */
    private fun busyForSceneFlight(): Boolean = noteMorphMotion.noteMorphKey != null || noteMorphMotion.flight != null || cardExchange.exchange != null

    /**
     * Into the cover or out of it with other islands in the row: the music goes as a flight, as
     * it does pulled up into its card or down out of it - the row's next island grows into the
     * pill as it leaves, and the others make room as it comes back. The pill itself going into
     * the cover, the others stood still and the pill swapped islands in one jump at the end.
     */
    private fun startSceneFlight(native: View, toNative: Boolean): Boolean {
        if (busyForSceneFlight()) return false
        val view = player?.takeIf { it.visibility == View.VISIBLE } ?: return false
        swapMotion.endSwap()
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        view.clearNudge()
        if (toNative) {
            val fromSmall = selectedIsland != MUSIC_ISLAND
            if (fromSmall && smallKey != MUSIC_ISLAND) return false
            if (noteMorphMotion.prepareFlight(MUSIC_ISLAND) == null) return false
            noteMorphMotion.noteMorphKey = MUSIC_ISLAND
            noteMorphMotion.flightFromSmall = fromSmall
            noteMorphMotion.flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
            flightOut = false
        } else {
            if (noteMorphMotion.prepareFlight(MUSIC_ISLAND) == null) return false
            noteMorphMotion.noteMorphKey = MUSIC_ISLAND
            noteMorphMotion.flightFromSmall = false
            flightOut = false
            noteMorphMotion.flightHome = if (MUSIC_ISLAND in noteMorphMotion.releasedFromSmall) HOME_SMALL else HOME_PILL
            if (noteMorphMotion.flightHome == HOME_PILL) {
                // The pill is the music's again: whatever took it forms into the small place.
                val oldBig = selectedIsland
                val oldSmall = smallKey
                selectedIsland = MUSIC_ISLAND
                refresh()
                swapMotion.startSwap(oldBig, oldSmall, intoSmall = true)
            }
            refresh()
            position()
        }
        morphScene = true
        // The card is the flight's other end: out from under the pill's suppression.
        updateNativeSuppression(false)
        trace("scene flight ${if (toNative) "in" else "out"} home=${if (noteMorphMotion.flightHome == HOME_PILL) "pill" else "small"} " +
            smallState())
        if (!noteMorphMotion.startNoteMorph(MUSIC_ISLAND, native, toRow = toNative)) {
            morphScene = false
            return false
        }
        return true
    }

    /**
     * The media card dismissed while its session lives on: everything of the music's that was
     * waiting on the card lets go of it. A switch waited on a card being laid out that never
     * would be, and the notifications' islands stopped answering with it (PR #15).
     */
    fun onMediaCardRemoved() {
        cardExchange.exchange?.let { x ->
            if (x.expanded == MUSIC_ISLAND) x.expanded = null
            if (x.pending == MUSIC_ISLAND) x.pending = null
            // Out of the movers first: its morph ending must not settle it as a landing.
            x.movers.remove(MUSIC_ISLAND)?.let { m ->
                cardExchange.unland(m)
                m.morph?.cancel()
                recycleSmallView(m.view)
            }
            x.seats = x.seats?.let { s ->
                Seats(s.big?.takeUnless { it == MUSIC_ISLAND }, s.small?.takeUnless { it == MUSIC_ISLAND })
            }
            if (x.pending == null && x.movers.isEmpty()) cardExchange.endSwitch(x)
        }
        if (noteMorphMotion.noteMorphKey == MUSIC_ISLAND || morph != null && noteMorphMotion.noteMorphKey == null) morph?.cancel()
        if (noteMorphMotion.rowWaitKey == MUSIC_ISLAND) noteMorphMotion.abandonNoteMorph(MUSIC_ISLAND)
        noteMorphMotion.releasedFromPill -= MUSIC_ISLAND
        noteMorphMotion.releasedFromSmall -= MUSIC_ISLAND
        returning.remove(MUSIC_ISLAND)
        if (selectedIsland == MUSIC_ISLAND) selectedIsland = null
        if (preferredSmall == MUSIC_ISLAND) preferredSmall = null
        restoreHeader()
        scheduleRefresh()
    }

    /** The music is out as its card - the media card, or the cover - the row holding the others. */
    private fun musicExpanded(includeCover: Boolean): Boolean = MiniPlayerPresentationPolicy.mediaExpanded(
        cardPresent = Main.miniPlayerMediaCardPresent(),
        sessionUsable = controller?.let(::isUsable) == true,
        musicInRow = MUSIC_ISLAND in islandKeys,
        nativeRequested = MiniPlayerRuntime.nativeRequested(controller?.sessionToken),
        coverActive = includeCover && Main.coverModeOn(),
    )

    /** The music is out as the media card, the row holding the others. */
    private fun musicCarded(): Boolean = musicExpanded(includeCover = false)

    /** A pull down on the media card brings the music home as a flight (musicFlies). */
    fun musicCollapses(): Boolean = config.getBoolean(MiniPlayerConfig.ENABLED) &&
        musicFlies() && musicCarded() && morph == null && cardExchange.exchange == null && noteMorphMotion.noteMorphKey == null &&
        spreadMotion.spread == null

    /**
     * The one island out as its card and settled there - the super island's expanded island.
     * Null with every island in the row. Only ever one: another asked out takes its place.
     */
    fun expandedKey(): String? {
        // The cover is the music's card: another island opened there takes its place, and the
        // cover goes (2026-09-25) - not a row of its own in the cover's stack beside the card.
        if (musicExpanded(includeCover = true)) return MUSIC_ISLAND
        return LockIslands.releasedKeys().firstOrNull { it != noteMorphMotion.noteMorphKey && stackHost.rowFor(it) != null }
    }

    /**
     * The super island's compareState for this row: whether [origin] takes the place [cur]
     * holds, the big island's when [big]. The music is ranked as the super island ranks it
     * (MiuiIslandMediaControllerImpl: islandProperty 2, islandPriority 2), so a focus
     * notification of property 1 - 高德's navigation - takes the big place from it and it the
     * small place back (the user, 2026-10-24; it ranked above everything from 2026-09-25).
     * Against the ordinary notifications, which the super island has no island for, the music
     * still wins both places.
     */
    internal fun takesPlace(cur: String, origin: String, big: Boolean): Boolean {
        if (origin == MUSIC_ISLAND && !isFocusIsland(cur)) return true
        if (cur == MUSIC_ISLAND && !isFocusIsland(origin)) return false
        val c = rankOf(cur) ?: return true
        val o = rankOf(origin) ?: return false
        return LockIslands.takesPlace(c, o, big)
    }

    private fun rankOf(key: String): LockIslands.Rank? =
        if (key == MUSIC_ISLAND) LockIslands.Rank(2, 2, musicSince) else LockIslands.rankOf(key)

    /** A focus notification's island: not the music, the stack island or an app's group. */
    private fun isFocusIsland(key: String): Boolean =
        key != MUSIC_ISLAND && key != STACK_ISLAND && !LockIslands.isAppGroup(key) &&
            LockIslands.noteFor(key)?.focus == true

    /** When the music's session was first seen, on the notifications' clock (Note.since). */
    private var musicSince = 0L

    /**
     * The islands this lock screen's row has had, and since when each known one has been
     * missing from it. A focus island not among them is new, and may take the pill (refreshUnsafe);
     * one missing for less than PICK_GRACE_MS keeps the pill it had. The time unlocked is not
     * counted ([knownLocked]): locked again, the row is the one that was left.
     */
    private val islandKnown = HashSet<String>()
    private val islandMissing = HashMap<String, Long>()
    private var knownLocked = false
    private val graceCheck = Runnable { refresh() }

    private fun noteKnown(present: List<String>, now: Long) {
        for (k in present) {
            islandKnown += k
            islandMissing -= k
        }
        val it = islandKnown.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k in present) continue
            val since = islandMissing.getOrPut(k) { now }
            if (now - since > KNOWN_FORGET_MS) {
                it.remove()
                islandMissing -= k
            }
        }
    }

    internal val cardExchange = CardExchange(this)
    fun exchangeRunning(): Boolean = cardExchange.exchangeRunning()
    fun tapDuringExchange(small: Boolean) = cardExchange.tapDuringExchange(small)

    /** [key]'s card: the media card for the music, the notification's own row in the stack. */
    fun nativeFor(key: String): View? = if (key == MUSIC_ISLAND)
        transitionHeader()?.takeIf { Main.miniPlayerMediaCardPresent() } else stackHost.rowFor(key)

    internal val stackHost = StackHost(this)
    fun cardHeldDy(card: View): Float? = stackHost.cardHeldDy(card)
    val contentTopFrom: String get() = stackHost.contentTopFrom
    val lastScrollRead: String get() = stackHost.lastScrollRead
    fun nativeStackAtTop(readRest: Boolean): Boolean = stackHost.nativeStackAtTop(readRest)
    fun onNativeListScroll(stack: View) = stackHost.onNativeListScroll(stack)
    fun releasedRowAt(x: Float, y: Float): String? = stackHost.releasedRowAt(x, y)
    fun releaseNativeScrollPin() = stackHost.releaseNativeScrollPin()
    fun rowOf(key: String): View? = stackHost.rowOf(key)
    fun stackContentTop(): Float = stackHost.stackContentTop()
    fun stackDrawnTop(): Float = stackHost.stackDrawnTop()
    fun stackForProbe(): ViewGroup? = stackHost.stackForProbe()

    internal val spreadMotion = SpreadMotion(this)
    fun spreadCollapseAt(x: Float, y: Float): Boolean = spreadMotion.spreadCollapseAt(x, y)
    fun beginSpreadCollapse(startY: Float): Boolean = spreadMotion.beginSpreadCollapse(startY)
    fun moveSpreadCollapse(y: Float) = spreadMotion.moveSpreadCollapse(y)
    fun endSpreadCollapse(velocityY: Float, cancelled: Boolean) = spreadMotion.endSpreadCollapse(velocityY, cancelled)
    fun beginStackSpread(startY: Float): Boolean = spreadMotion.beginStackSpread(startY)
    fun spreadClockProgress(): Float? = spreadMotion.spreadClockProgress()
    fun spreadListShown(): Boolean = spreadMotion.spreadListShown()
    fun spreadFolding(): Boolean = spreadMotion.spreadFolding()
    fun spreadFingerMove(y: Float): Boolean = spreadMotion.spreadFingerMove(y)
    fun spreadFingerEnd(velocityY: Float, cancelled: Boolean): Boolean = spreadMotion.spreadFingerEnd(velocityY, cancelled)

    internal val noteMorphMotion = NoteMorphMotion(this)
    fun collapseRow(key: String, startY: Float): Boolean = noteMorphMotion.collapseRow(key, startY)
    internal val noteDragMotion = NoteDragMotion(this)
    fun beginNoteDrag(key: String, fromSmall: Boolean, startY: Float): Boolean =
        noteDragMotion.beginNoteDrag(key, fromSmall, startY)
    fun noteDragMove(y: Float, nudgeX: Float = 0f) = noteDragMotion.noteDragMove(y, nudgeX)
    fun noteDragEnd(velocityY: Float, cancelled: Boolean) = noteDragMotion.noteDragEnd(velocityY, cancelled)

    /** A released notification's row under a point on screen, by key. */
    internal val shortcutDiscs = ShortcutDiscs(this)
    fun press(side: Int, down: Boolean) = shortcutDiscs.press(side, down)
    fun discAt(x: Float, y: Float): Int? = shortcutDiscs.discAt(x, y)
    fun letGoOnDrag(dx: Float, dy: Float): Boolean = shortcutDiscs.letGoOnDrag(dx, dy)
    fun shortcutGeometry(): FloatArray? = shortcutDiscs.shortcutGeometry()


    /**
     * The shortcut row's layout listeners, kept so destroy() can take them off. The row is the
     * OEM's and outlives every controller; anonymous lambdas left on it called a destroyed
     * controller and piled up one pair per rebuild.
     */
    private val leftLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        schedulePosition()
    }
    private val rightLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        schedulePosition()
    }

    init {
        host.clipChildren = false
        host.clipToPadding = false
        Gen.prefListener(prefs, prefListener)
        Gen.preDraw(host, preDraw)
        Gen.layoutChange(left, leftLayoutListener)
        Gen.layoutChange(right, rightLayoutListener)
        runCatching { sessions?.let { Gen.activeSessionsListener(it, sessionListener, handler) } }
        LockIslands.addListener(islandListener)
        // Posted: run here, it came before the properties declared below this block were
        // made, and failed on the first of them (focusLotties) every time SystemUI started.
        scheduleRefresh()
    }

    fun destroy() {
        destroyed = true
        Gen.unprefListener(prefs, prefListener)
        runCatching { Gen.unpreDraw(host, preDraw) }
        runCatching { Gen.unlayoutChange(left, leftLayoutListener) }
        runCatching { Gen.unlayoutChange(right, rightLayoutListener) }
        runCatching { sessions?.let { Gen.unactiveSessionsListener(it, sessionListener) } }
        runCatching { controller?.let { Gen.unsessionCallback(it, mediaListener) } }
        followLive(null)
        morph?.cancel()
        if (groupMotion.piles.isNotEmpty() || groupMotion.pileMoving) groupMotion.finishPile(true)
        // Each of these ends itself once its own state has, but some wait on a frame of a tree
        // this runtime no longer watches: gone with it, not left asking for vsyncs. Through Gen,
        // because what is queued on the Choreographer is its wrapper, not the callback itself.
        listOf(pulseFrame, smallNudgeFrame, swapMotion.swapFrame, rowFrame, appearMotion.appearFrame, smallGrowFrame,
            groupMotion.pileSettle, spreadMotion.spreadFrame, cardExchange.switchWait, noteMorphMotion.rowWait).forEach { Gen.unframe(it) }
        // The waits for a row would otherwise go on to open it, on a controller already gone;
        // and a card still pinned is the stack's again, drawn where the stack has it.
        noteMorphMotion.rowWaitKey = null
        stackHost.unpinCard()
        if (spreadMotion.spread != null) {
            spreadMotion.spread?.items?.forEach { it.morph?.cancel() }
            spreadMotion.spread = null
            LockIslands.setSpread(false, folded = true)
        }
        // The lead row's fold alpha is the stack's again, the pre-draw holding it off the row.
        spreadMotion.holdLeadThroughFold(null)
        spreadMotion.endLeadTail()
        restoreHeader()
        shortcutDiscs.removeDiscs()
        LockIslands.removeListener(islandListener)
        LockIslands.setActive(false)
        LockIslands.stopFolding()
        removeSmallIsland()
        noteMorphMotion.dropFlight()
        spareViews.forEach { runCatching { host.removeView(it) } }
        spareViews.clear()
        player?.let { runCatching { host.removeView(it) } }
        player = null
        controller = null
        handler.removeCallbacksAndMessages(null)
    }


    // ---- the landing, frame by frame, for `op mini`

    val landTrace = ArrayDeque<String>()

    /** Frames the running notification morph has spent at its island's end. */
    internal var restFrames = 0

    fun trace(what: String) {
        landTrace.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what")
        while (landTrace.size > 600) landTrace.removeFirst()
    }

    /** The small island and the row as they stand this frame, in host pixels. */
    internal fun smallState(): String {
        val v = smallIsland ?: return "no small"
        val pill = player
        return "sk=${smallKey?.takeLast(6)} v=${v.visibility} a=${"%.2f".format(v.alpha)} " +
            "ta=${"%.2f".format(v.transitionAlpha)} tx=${v.translationX.toInt()} l=${v.left} " +
            "w=${v.width}/${v.layoutParams.width} rest=${smallRest[0].toInt()} " +
            "pill=${pill?.left?.plus(pill.translationX)?.toInt()}+${pill?.width}/${pill?.layoutParams?.width} " +
            "row=${rowLeft.value.toInt()}+${rowWidth.value.toInt()}>${rowWidth.target.toInt()} " +
            "grow=$smallGrowing:${"%.2f".format(smallGrow.value)} keys=${islandKeys.size} ret=${returning.size} " +
            "held=${noteMorphMotion.pillHeld()} pta=${"%.2f".format(pill?.transitionAlpha ?: -1f)} " +
            "pca=${"%.2f".format(pill?.contentAlphaNow() ?: -1f)} fca=${"%.2f".format(noteMorphMotion.flight?.contentAlphaNow() ?: -1f)} " +
            "ghost=${swapMotion.ghostPill?.let { "${it.visibility}/${"%.2f".format(it.alpha)}/${it.width}" }} swap=${swapMotion.swap?.smallMode}"
    }

    fun nativeHeaderHidden(): Boolean = nativeSuppressionRequested
    fun visibleHeightDp(): Float = configuredHeightDp

    fun transitionActive(): Boolean = morph != null
    /**
     * Only the media card's own morph lets the OEM show it. [morph] is a notification's too: while
     * one opened or went home, the stack's setVisibility(VISIBLE) on the header went through, and
     * with notifications coming one after another the card flashed up (the user, 2026-09-26).
     * Whose morph it is, is told the same way as cardMoving in updateVisibility.
     */
    fun hardSuppressionActive(): Boolean = nativeSuppressionRequested &&
        (morph == null || noteMorphMotion.noteMorphKey != null && noteMorphMotion.noteMorphKey != MUSIC_ISLAND)

    /** A morph into the cover or the lyrics landed on the card at this uptime, the scene not up yet. */
    private var sceneLandedAt = 0L

    internal val morphListener = object : MiniCardMorph.Listener {
        /** A scene exit hands the pill back only once the cover has let go of the lock screen. */
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) =
            toNative || !morphScene || !Main.coverSceneActive()

        override fun artBridged() = artBridged

        override fun onFrame(morph: MiniCardMorph, progress: Float) = groupMotion.groupFrame(morph)

        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (this@MiniPlayerController.morph !== morph) return
            this@MiniPlayerController.morph = null
            // Into the cover or the lyrics: landed on the card a moment before the scene has
            // the lock screen, the pill was the lock screen's again for those frames and came
            // up bare between the torch and the camera - its artwork still in the flight
            // (filmed 2026-09-25). It stays down until the scene is up, or not coming.
            if (toNative && morphScene) sceneLandedAt = android.os.SystemClock.uptimeMillis()
            morphScene = false
            Xp.log("MCMini: container morph ${if (completed) "landed" else "cancelled"} " +
                "at ${if (toNative) "native" else "mini"}")
            groupMotion.endGroup(toNative)
            position()
            updateVisibility()
        }
    }

    /**
     * The music on its way up into the cover in a switch, and the cover asked to go: the island
     * that came down into the pill goes back up as its card and the music comes home. The
     * caller is taking the cover away itself, so the music's way home is not the cover's to end
     * again (sendDown turns the scene for a mover whose card is the cover).
     */
    private fun turnSwitchOutOfCover(x: CardExchange.Switch, music: CardExchange.Mover): Boolean {
        val big = x.seats?.big?.takeIf { it != MUSIC_ISLAND } ?: return false
        music.cover = false
        cardExchange.requestUp(x, big, fromPill = true, cover = false)
        MiniPlayerRuntime.noteTouch("cover out turns the switch: ${big.takeLast(6)} up, music home")
        return true
    }

    /**
     * Starts, or turns round, the container morph. A dynamic switch goes wherever the selection
     * now points; a scene route needs the mini player to be the selected presentation.
     */
    fun beginTransition(toNative: Boolean, scene: Boolean): Boolean {
        // The stack spread out as its list - with the music alone, that list is the media card -
        // and the card's artwork tapped into the cover: the spread stayed open under the scene,
        // and updateVisibility leaves everything to an open spread, so the card stood beside the
        // pill from then on, through the cover and after it (#82/#78; reproduced 2026-10-14,
        // `op mini` showed "spread open" with no end). The scene takes the card from here.
        spreadMotion.spread?.takeIf { scene }?.let { s ->
            val token = controller?.sessionToken
            // Into the scene with the music out as the list's card: that card is the choice,
            // as one opened from the pill would be. Chosen before the spread ends, so the refresh
            // it ends with keeps the card and not the pill; and no morph - run from the pill, the
            // card played its opening over again (the user, 2026-10-14).
            val musicOut = toNative && token != null && s.items.any { it.key == MUSIC_ISLAND }
            if (musicOut) MiniPlayerRuntime.noteNative(token!!, "spread into the scene")
            spreadMotion.endSpread(folded = false, why = "scene")
            if (musicOut) return false
        }
        morph?.let { running ->
            running.aim(toNative)
            morphScene = scene
            // A flight turned round holds its home again, or frees it.
            if (noteMorphMotion.noteMorphKey == MUSIC_ISLAND) noteMorphMotion.setFlightOut(toNative)
            return true
        }
        // The music exchanged into the cover with the island out, or out of it for an island
        // opened there: the exchange is the scene's morph - when it is going the scene's way.
        if (scene) cardExchange.exchange?.takeIf { it.has(MUSIC_ISLAND) }?.let { x ->
            val m = x.movers[MUSIC_ISLAND]
            when {
                toNative || m != null && m.headedHome -> return true
                // Pulled down out of the cover while the switch still had the music on its way
                // up into it: taken as going the scene's way, the switch landed the music as the
                // cover's card, the cover went, and the music was left out as a card nobody
                // showed - in neither the pill nor the card (filmed 2026-09-29 20:54, 460ms after
                // the switch). Turned round now, as a tap on the pill turns a switch.
                m?.morph != null -> return turnSwitchOutOfCover(x, m)
                // Already landed as the cover's card, the rest still moving: they land now, and
                // the music flies home as it does with no switch.
                else -> {
                    x.movers.values.toList().forEach { it.morph?.cancel() }
                    if (cardExchange.exchange != null) return false
                }
            }
        }
        val what = if (scene) "scene morph" else "switch morph"
        val view = player ?: return MiniPlayerRuntime.refused(what, "no pill")
        val token = controller?.sessionToken ?: return MiniPlayerRuntime.refused(what, "no session")
        // The card already chosen: nothing to become.
        if (scene && MiniPlayerRuntime.nativeRequested(token)) return false
        if (!view.isAttachedToWindow) return MiniPlayerRuntime.refused(what, "pill detached")
        if (!Main.miniPlayerMorphAllowed()) {
            return MiniPlayerRuntime.refused(what, "not allowed " + Main.miniPlayerMorphWhy())
        }
        val native = transitionHeader() ?: return MiniPlayerRuntime.refused(what, "no card")
        if (scene && othersBesideMusic()) return startSceneFlight(native, toNative)
        // A switch still settling is finished where it was headed: the card morph has the pill.
        val next = groupMotion.prepareGroup(native, toNative)
            ?: return MiniPlayerRuntime.refused(what, "no group " + groupMotion.groupRefusal(toNative))
        morph = next
        morphScene = scene
        if (!next.start()) {
            morph = null
            morphScene = false
            groupMotion.endGroup(!toNative)
            updateVisibility()
            return MiniPlayerRuntime.refused(what, "no geometry; switching in place")
        }
        return true
    }

    fun pill(): MiniPlayerView? = player

    /** The music's island - the pill or the small island, whichever holds it - drawn centre. */
    fun musicIslandCentreOnScreen(): FloatArray? {
        val v: View = when (MUSIC_ISLAND) {
            selectedIsland -> player
            smallKey -> smallIsland
            else -> null
        }?.takeIf { it.isShown && it.isAttachedToWindow && it.width > 0 } ?: return null
        val m = Matrix()
        v.transformMatrixToGlobal(m)
        val out = floatArrayOf(v.width / 2f, v.height / 2f)
        m.mapPoints(out)
        val onScreen = IntArray(2)
        val inWindow = IntArray(2)
        v.getLocationOnScreen(onScreen)
        v.getLocationInWindow(inWindow)
        out[0] += (onScreen[0] - inWindow[0]).toFloat()
        out[1] += (onScreen[1] - inWindow[1]).toFloat()
        return out
    }

    /**
     * The music's island at rest: the small island's circle when the music sits there, else the
     * pill's rest box - the music is in its card now, so neither is drawn where it will be.
     */
    fun musicIslandHomeOnScreen(): FloatArray? {
        val box = if (smallKey == MUSIC_ISLAND && smallIsland != null) smallBoxOnScreen()
            else player?.restBoxOnScreen() ?: return null
        return floatArrayOf(box.cx(), box.cy())
    }

    /** The row can be shown and touched: with music, the media card has to be up; without, not. */
    private fun canShow(): Boolean =
        // The music on its way in or out of its card or the cover is not the row's to gate: the
        // row takes taps meanwhile, to turn the switch round (2026-09-25).
        if (MUSIC_ISLAND in islandKeys && cardExchange.exchange == null && noteMorphMotion.noteMorphKey != MUSIC_ISLAND)
            Main.miniPlayerCanShow() else Main.miniPlayerIslandsCanShow()

    /** A morph moving on its springs under this point - the switch's, or a scene's. */
    /** A notification's morph is not the music's to catch: its drag engine would drive it wrong. */
    fun catchableAt(x: Float, y: Float): Boolean = noteDragMotion.noteDrag == null && morph?.catchableAt(x, y) == true

    /** Whether the running morph belongs to a cover or lyrics entry or exit. */
    fun morphIsScene(): Boolean = morph != null && morphScene

    /** Lets go of a caught scene morph toward an end, the scene itself decided by the caller. */
    fun releaseMorph(toNative: Boolean, velocity: Float) {
        morph?.release(toNative, velocity)
        if (noteMorphMotion.noteMorphKey == MUSIC_ISLAND) noteMorphMotion.setFlightOut(toNative)
    }

    fun grabMorph(): MiniCardMorph.Grab? = morph?.grab()

    fun cardAbovePill(): Boolean {
        // A notification's row is always above its island.
        if (noteMorphMotion.noteMorphKey != null) return true
        val pill = player?.restBoxOnScreen() ?: return true
        val card = transitionHeader() ?: return true
        val xy = IntArray(2).also(card::getLocationOnScreen)
        return xy[1] <= pill.y
    }

    /** Where a cover flight starts or lands: the artwork as a running morph draws it, else its slot. */
    fun artworkRestBox(): CoverMorphMotion.Box? = morph?.artworkBox()
        ?: cardExchange.exchange?.movers?.get(MUSIC_ISLAND)?.morph?.artworkBox()
        ?: musicSmallArtBox()
        ?: player?.artworkRestBoxOnScreen()

    /**
     * The music resting as the small island: its picture's place in the circle. The cover pulled
     * down into the small island flew on to the pill's artwork slot once the exchange had ended
     * - the pill holding a notification by then - and faded out over it (filmed 2026-09-25).
     */
    private fun musicSmallArtBox(): CoverMorphMotion.Box? {
        if (smallKey != MUSIC_ISLAND || smallIsland == null || pillShowsMusic) return null
        val b = smallBoxOnScreen()
        val side = b.h * MiniCardMorph.CIRCLE_ICON_SHARE
        return CoverMorphMotion.Box(b.x + (b.w - side) / 2f, b.y + (b.h - side) / 2f, side, side)
    }

    fun artworkRadius(): Float? = musicSmallArtBox()?.let { it.h / 2f } ?: player?.artworkRestRadius()

    fun setArtBridged(bridged: Boolean) {
        artBridged = bridged
        applyArtBridge()
    }

    /**
     * Hides the artwork the cover's copy stands in for, wherever the music is drawn now. Asked
     * again whenever that changes under a running flight: into the cover out of an exchange,
     * the bridge was set once while the exchange still waited for the card - the pill, still the
     * music's, was hidden - and the island that landed in the pill had its icon hidden with it
     * until the flight ended (filmed 2026-09-25, Amap in the pill with no icon).
     */
    internal fun applyArtBridge() {
        val bridged = artBridged
        // The music's artwork: the small island's view's while the music is the small island.
        val small = groupMotion.group?.takeIf { it.musicSmall }?.smallView
        small?.setArtworkHidden(bridged)
        // The music as a flight: the flight's artwork is the one the cover's stands in for.
        val flying = noteMorphMotion.flight?.takeIf { noteMorphMotion.noteMorphKey == MUSIC_ISLAND }
            ?: cardExchange.exchange?.movers?.get(MUSIC_ISLAND)?.view
        flying?.setArtworkHidden(bridged)
        // Where the music comes to rest, hidden too till the cover's copy has landed: the pill
        // a flight lands on is shown under it before the end, and the small island takes over
        // from its flight at the same point - both drew the music's picture there while the
        // cover's was still on its way down, two pictures at once (filmed 2026-09-25, 22:44).
        player?.setArtworkHidden(bridged && small == null && pillShowsMusic)
        smallIsland?.setIconHidden(bridged && smallKey == MUSIC_ISLAND)
    }

    internal fun transitionHeader(): View? {
        val current = header?.get()?.takeIf { it.isAttachedToWindow }
            ?: Main.miniPlayerMediaHeader()
        if (current != null) header = WeakReference(current)
        return current
    }

    fun wantsNativeArtworkGesture(): Boolean {
        val token = controller?.sessionToken
        return config.getBoolean(MiniPlayerConfig.ENABLED) &&
            MiniPlayerRuntime.nativeRequested(token) &&
            Main.miniPlayerCanShow()
    }

    fun density(): Float = context.resources.displayMetrics.density

    /**
     * Busy for the music's own switch pulled from either end (an exchange is asked next, on its
     * own): the motions that keep it from starting (M0, the subset as it was).
     */
    private fun busyForDrag(): Boolean = morph != null || spreadMotion.spread != null

    /** The dynamic switch can be pulled from this end right now. */
    fun canDrag(fromNative: Boolean, small: Boolean = false): Boolean {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED) || busyForDrag()) return false
        // With other islands in the row the music goes out and back as a flight, as they do.
        if (musicFlies() || cardExchange.exchange != null) return false
        // Only the music island has a card to open into - in the pill, or as the small island.
        if (!fromNative && (if (small) smallKey != MUSIC_ISLAND else !selectedIsMusic())) return false
        val token = controller?.sessionToken ?: return false
        if (!Main.miniPlayerMorphAllowed()) return false
        return MiniPlayerRuntime.nativeRequested(token) == fromNative
    }

    /** Which of canDrag's answers it was, for [MiniPlayerRuntime.refused]. */
    fun dragRefusal(fromNative: Boolean, small: Boolean): String = when {
        !config.getBoolean(MiniPlayerConfig.ENABLED) -> "off"
        morph != null -> "morph running"
        spreadMotion.spread != null -> "spread"
        musicFlies() -> "music flies"
        cardExchange.exchange != null -> "exchange"
        !fromNative && (if (small) smallKey != MUSIC_ISLAND else !selectedIsMusic()) -> "not the music"
        controller?.sessionToken == null -> "no session"
        !Main.miniPlayerMorphAllowed() -> "not allowed " + Main.miniPlayerMorphWhy()
        else -> "nativeRequested=${MiniPlayerRuntime.nativeRequested(controller?.sessionToken)}"
    }

    /** How far apart the pill and the card are: what a full pull covers. */
    fun dragSpan(): Float? {
        if (noteMorphMotion.noteMorphKey != null) return noteDragMotion.noteSpan.takeIf { it > 0f }
        val pill = player?.restBoxOnScreen() ?: return null
        val card = transitionHeader() ?: return null
        val xy = IntArray(2).also(card::getLocationOnScreen)
        return kotlin.math.abs(pill.y - xy[1]).coerceAtLeast(160f * density())
    }

    fun beginDragMorph(fromNative: Boolean): Boolean { android.os.Trace.beginSection("MC beginDragMorph"); try {
        if (player == null) return MiniPlayerRuntime.refused("drag", "no pill")
        val native = transitionHeader() ?: return MiniPlayerRuntime.refused("drag", "no card")
        // Starts at the end it was pulled from; release() aims it once the lift decides.
        val next = groupMotion.prepareGroup(native, toNative = !fromNative)
            ?: return MiniPlayerRuntime.refused("drag", "no group " + groupMotion.groupRefusal(!fromNative))
        morph = next
        morphScene = false
        if (!next.startDragging()) {
            morph = null
            groupMotion.endGroup(toNative = fromNative)
            updateVisibility()
            return MiniPlayerRuntime.refused("drag", "no geometry")
        }
        return true
    } finally { android.os.Trace.endSection() } }

    fun dragTo(progress: Float, nudge: Float, nudgeX: Float) {
        morph?.drag(progress, nudge, nudgeX)
    }

    /** The lift: the choice follows where the container is going, then the springs. */
    fun releaseDrag(toNative: Boolean, velocity: Float) {
        if (noteMorphMotion.noteMorphKey != null) {
            noteDragMotion.releaseNote(toNative, velocity)
            return
        }
        val token = controller?.sessionToken
        val backIntoScene = toNative && MiniPlayerRuntime.takeRestoreScene()
        if (!backIntoScene && token != null) {
            if (toNative) MiniPlayerRuntime.chooseNative(token) else MiniPlayerRuntime.chooseMini(token)
        }
        morph?.release(toNative, velocity)
        if (backIntoScene) {
            // Back into the cover or the lyrics the moment the lift says it is going up - not
            // once the card has landed: the clock, the wallpaper and the artwork start with the
            // card's last stretch. The scene's own entry finds this morph running and takes it
            // over (beginTransition), and its artwork leaves from where the pill's is drawn.
            Main.miniPlayerEnterCover()
        }
    }

    fun takesRows(): Boolean = config.getBoolean(MiniPlayerConfig.ENABLED) && LockIslands.notes.isNotEmpty()

    fun wantsCardSwipe(): Boolean {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED)) return false
        // Out as the stack's list, a pull down on the media card is the list's to fold, the
        // whole row coming home with it; taken as the card's, it was cancelled out from under
        // the stack and nothing folded.
        if (spreadMotion.spread != null) return false
        val current = controller ?: return false
        if (!isUsable(current)) return false
        return Main.coverModeOn() || wantsNativeArtworkGesture()
    }

    fun sessionToken(): Any? = controller?.sessionToken

    fun preferMini() {
        controller?.sessionToken?.let { token ->
            MiniPlayerRuntime.chooseMini(token)
        }
    }

    /**
     * The pill, when it is showing, takes touches and the point is on it - or near it: the
     * pill is a small target, and a swipe up that began just off it went to swipe-to-unlock.
     * The margin stops short of the torch and camera buttons, which keep their own touches.
     */
    fun touchTarget(x: Float, y: Float): MiniPlayerView? {
        val view = player ?: return null
        if (editButtonUp()) return null
        val d = density()
        val xy = IntArray(2).also(view::getLocationOnScreen)
        // Across to the torch and camera, which keep their own touches.
        val lx = buttonEdge(left, inner = true) ?: (xy[0] - 8f * d)
        val rx = buttonEdge(right, inner = false) ?: (xy[0] + view.width + 8f * d)
        // From just above the pill down to the foot of the screen - a thumb starts a swipe low.
        // A row lifted off the sensor has left that room, though: unlifted it ends at the foot,
        // where the pill itself is, but lifted the region down there took swipes the row was no
        // longer under, and the islands switched from the bottom of the screen with the pill up
        // in the air (#81). Lifted, it ends 12dp under the pill's own bottom instead - the same
        // margin the lift keeps from the sensor, so the sensor's own rect begins about where the
        // region does and a finger put there is unlocking either way (#66).
        val foot = if (fodLiftPx > 0.5f) (xy[1] + view.height + dp(FOD_MARGIN_DP)).toFloat()
            else host.height.toFloat()
        val inside = x >= minOf(lx, xy[0].toFloat()) && x < maxOf(rx, xy[0] + view.width.toFloat())
            && y >= xy[1] - 36f * d && y < foot
            && fingerprintInHost()?.contains(x.toInt(), y.toInt()) != true
        // The small island first: the camera's button (its shortcut_view_right_layout above
        // all) reaches well past its disc, over the small island's right half. Asked second, a
        // tap there was the camera's - and opened it (filmed 2026-09-25). Taken here, the whole
        // gesture is the row's and the button never hears of it.
        val onSmall = smallIslandAt(x, y)
        if (!onSmall && (!inside || onButton(left, x, y) || onButton(right, x, y))) return null
        val refused = when {
            view.visibility != View.VISIBLE -> "hidden"
            !view.isAttachedToWindow -> "detached"
            morph != null -> "morph running"
            !view.acceptsTouch() -> "not interactive (canShow=${canShow()} " +
                "aod=${MiniPlayerScene.aodActive})"
            else -> null
        }
        if (refused != null) {
            // The finger was meant for the pill and went to swipe-to-unlock: say why.
            Xp.log("MCMini: down on the pill not taken: $refused")
            MiniPlayerRuntime.noteTouch("refused: $refused")
            return null
        }
        return view
    }

    /**
     * Right above the lock screen's own layer, not at the top of the window: the shade and the
     * control centre are drawn after it, and their blur covers the pill as it covers the torch
     * and camera. Added last, the pill sat above both, sharp - and was hidden for them instead.
     * A morph lifts it over the card for as long as it runs (MiniPlayerView.beginMorph).
     */
    fun lockScreenLayerIndex(): Int {
        val at = lockScreenLayerSlot()
        return if (at >= host.childCount) at else at + 1
    }

    /** The lock screen's own layer in the host. */
    private fun lockScreenLayerSlot(): Int = layerSlot(host)

    /** Where in [parent] the child holding the buttons is: the discs go right before it. */
    fun layerSlot(parent: ViewGroup): Int {
        var v: View = left
        while (true) {
            val up = v.parent as? View ?: return parent.childCount
            if (up === parent) {
                val at = parent.indexOfChild(v)
                return if (at < 0) parent.childCount else at
            }
            v = up
        }
    }

    /**
     * The discs' parent: keyguard_root_view, not the window root, when the buttons are in it.
     *
     * Under the buttons is not enough - the lock screen's depth cut-out (the wallpaper's subject,
     * deducted_image_view in keyguard_panel_view) is drawn in keyguard_root_view too, after
     * everything before that view in the root and before the buttons' keyguard_bottom_area. A disc
     * in the root, right before keyguard_root_view, was drawn under the subject: wherever the
     * subject reached the torch or the camera, that part of the disc was gone and the icon sat on
     * the picture (#18, measured: the disc's pixels over the subject equal the subject's). Right
     * before the buttons' own branch in keyguard_root_view it is over the subject and under the
     * icon, and otherwise stacked exactly as the buttons are, the shade's blur included.
     */
    fun discParent(): ViewGroup {
        val root = followRoot?.get() as? ViewGroup ?: return host
        var v: View? = left
        while (v != null && v !== host) {
            if (v === root) return root
            v = v.parent as? View
        }
        return host
    }

    /** Right before the buttons' branch in [parent], with nothing but the other disc between. */
    fun discInPlace(disc: View, parent: ViewGroup): Boolean {
        if (disc.parent !== parent) return false
        val slot = layerSlot(parent)
        val at = parent.indexOfChild(disc)
        if (at < 0 || at >= slot) return false
        for (i in at + 1 until slot) if (parent.getChildAt(i) !is ShortcutDisc) return false
        return true
    }

    /** The torch's right edge or the camera's left edge, where the pill's touch area stops. */
    private fun buttonEdge(button: View, inner: Boolean): Float? {
        if (!button.isShown || button.width <= 0) return null
        val xy = IntArray(2).also(button::getLocationOnScreen)
        return if (inner) (xy[0] + button.width).toFloat() else xy[0].toFloat()
    }

    fun onButton(button: View, x: Float, y: Float): Boolean {
        if (!button.isShown || button.width <= 0) return false
        val xy = IntArray(2).also(button::getLocationOnScreen)
        return x >= xy[0] && x < xy[0] + button.width && y >= xy[1] && y < xy[1] + button.height
    }

    fun showMiniPlayer() {
        if (!wantsNativeArtworkGesture()) return
        controller?.sessionToken?.let(MiniPlayerRuntime::selectMini)
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { scheduleRefresh(); return }
        if (destroyed) return
        runCatching { refreshUnsafe() }.onFailure { Xp.w("MCMini: refresh failed: $it") }
        // The lift switched on or off in the settings comes here, not to position().
        runCatching { MiniPlayerRuntime.checkStackFloor() }
            .onFailure { Xp.w("MCMini: notification bottom check failed: $it") }
    }

    /** One refresh on the next turn of the main loop, however many ask for it before then. */
    internal fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        handler.post { refreshPosted = false; refresh() }
    }

    /**
     * Busy for the row switching to an island that has just taken the pill's place: the motions
     * that keep it from starting (M0, the subset as it was).
     */
    private fun busyForTakerSwap(): Boolean = swapMotion.swap != null || morph != null || cardExchange.exchange != null || noteMorphMotion.flight != null ||
        groupMotion.group != null || noteMorphMotion.rowWaitKey != null || islandDragging

    private fun refreshUnsafe() { android.os.Trace.beginSection("MC refresh"); try {
        binding.lottiesFollowDoze()
        if (configStale) {
            configStale = false
            val wasStacked = stackedStyle()
            config = JSONObject(MiniPlayerConfig.fromPreferences(prefs))
            if (wasStacked != stackedStyle()) shortcutDiscs.squeeze.reset()
            configuredHeightDp = MiniPlayerConfig.visibleHeightDp(config.toString())
        }
        val config = this.config
        forceHeaderRefresh = true
        val enabled = config.getBoolean(MiniPlayerConfig.ENABLED)
        if (!enabled) MiniPlayerRuntime.resetDynamicChoice("feature disabled")
        if (!enabled) {
            islandKeys = emptyList()
            player?.visibility = View.GONE
            hideSmallIsland()
            LockIslands.setActive(false)
            LockIslands.stopFolding()
            restoreHeader()
            return
        }
        // Active-session lists can be momentarily empty while a player advances its queue. Keep a
        // still-usable controller instead of flashing the vendor card during that bookkeeping gap.
        val chosen = traced("MC r.choose") { chooseController() ?: controller?.takeIf(::isUsable) }
        if (chosen?.sessionToken != controller?.sessionToken) {
            val previous = controller
            if (chosen == null && previous != null && !isUsable(previous)) {
                MiniPlayerRuntime.endSession(previous.sessionToken, "playback inactive")
            }
            runCatching { previous?.let { Gen.unsessionCallback(it, mediaListener) } }
            controller = chosen
            runCatching { chosen?.let { Gen.sessionCallback(it, mediaListener, handler) } }
            followLive(chosen)
            musicSince = System.currentTimeMillis()
            cachedCover = null
            // Another player: its artwork, not the last one's.
            binding.thumbShown = null
            lastTrack = ""
        }
        // The row is out as the stack's list: its views are the spread's till it folds back,
        // and it is put together again then (endSpread).
        if (spreadMotion.spread != null) return
        val musicMoving = groupMotion.group != null || groupMotion.musicComingDown || morph != null && noteMorphMotion.noteMorphKey == null ||
            noteMorphMotion.noteMorphKey == MUSIC_ISLAND || cardExchange.exchange?.has(MUSIC_ISLAND) == true
        // No music island without its card: a session outlives a dismissed card, and the row
        // kept an island for it that no card would ever be there to back.
        val music = traced("MC r.music") {
            controller?.takeIf(::isUsable)?.takeIf { Main.miniPlayerMediaCardPresent() || musicMoving }
        }
        val notes = traced("MC r.notes") { LockIslands.notes }
        // One island out as its card, the rest in the row (the super island's expanded state):
        // the music out as the media card leaves the row to the notifications. Alone, it keeps
        // the row as it always has - the card or the pill, one or the other. Moving between
        // the two it keeps its place until it lands.
        // The cover is the music's card as much as the media card is.
        val carded = music != null &&
            (MiniPlayerRuntime.nativeRequested(music.sessionToken) || Main.coverModeOn())
        val others = notes.isNotEmpty() || returning.isNotEmpty() || cardExchange.exchange != null ||
            noteMorphMotion.noteMorphKey.let { it != null && it != MUSIC_ISLAND }
        val musicInRow = music != null && (!carded || !others || musicMoving)
        // The row in the super island's order: the focus islands that take the big place from
        // the music (高德's navigation, islandProperty 1 against the music's 2) ahead of it, then
        // the music, then the rest in LockIslands' order. The music used to be first whatever
        // else there was, and the navigation had to be swiped to every time (the user, 2026-10-24).
        val (ahead, behind) = notes.partition { isFocusIsland(it.key) && takesPlace(MUSIC_ISLAND, it.key, big = true) }
        islandKeys = ahead.map { it.key } + (if (musicInRow) listOf(MUSIC_ISLAND) else emptyList()) +
            behind.map { it.key }
        // Two can be on their way back at once: a tap mid-switch brings one down as another lands.
        val now = android.os.SystemClock.uptimeMillis()
        returning.entries.removeAll { (key, since) ->
            notes.any { it.key == key } || now - since > RETURN_WAIT_MS || !Main.keyguardLocked()
        }
        for (key in returning.keys) islandKeys = keptInPlace(islandKeys, key)
        // A notification on its way out to its row, or in from it, stays in the pill till it lands.
        noteMorphMotion.noteMorphKey?.let { key -> islandKeys = keptInPlace(islandKeys, key) }
        // Every island of a switch holds its place in the row till it is over.
        cardExchange.exchange?.let { x -> for (key in x.keys()) islandKeys = keptInPlace(islandKeys, key) }
        // The time unlocked does not count against an island: the row comes back as it was left.
        val locked = Main.keyguardLocked()
        if (locked && !knownLocked) for (k in islandMissing.keys) islandMissing[k] = now
        knownLocked = locked
        // The island in the pill missing from one run - 高德 re-posts its island every second, and
        // a row being re-inflated is left out of that run - keeps the pill for PICK_GRACE_MS.
        // It fell to the row's first island at once, and the navigation picked went back to the
        // music a moment later (the user, 2026-10-24).
        val graced = selectedIsland?.takeIf { k ->
            locked && k != MUSIC_ISLAND && k !in islandKeys && k in islandKnown &&
                !LockIslands.isReleased(k) && LockIslands.noteFor(k) != null &&
                now - (islandMissing[k] ?: now) < PICK_GRACE_MS
        }
        if (graced != null) {
            islandKeys = keptInPlace(islandKeys, graced)
            handler.removeCallbacks(graceCheck)
            handler.postDelayed(graceCheck, PICK_GRACE_MS + 50L)
        }
        // Islands out as their cards keep their places too, for when they come back.
        var order = islandKeys
        // A new island in the row comes up beside the first, swiped alone or not.
        if (aloneFirst != null && islandKeys.any { it !in islandOrder }) aloneFirst = null
        for (k in islandOrder) {
            if (k !in order && (LockIslands.isReleased(k) || k == MUSIC_ISLAND && carded)) {
                order = keptInPlace(order, k)
            }
        }
        islandOrder = order
        // Islands new to this lock screen's row, before they are counted as known below.
        val fresh = if (locked) order.filter { it !in islandKnown } else emptyList()
        if (locked) noteKnown(order - listOfNotNull(graced), now)
        noteMorphMotion.noteMorphKey?.let { key ->
            // The pill itself morphing: it keeps what it is showing. Under a flight it goes on
            // being the row's (the island beside the flight can take it).
            if (noteMorphMotion.flight == null) {
                updateSmallIsland(music, notes)
                applyArtBridge()
                updateVisibility()
                schedulePosition()
                return
            }
        }
        if (islandKeys.isEmpty() || rowGoing(music)) {
            // Not put away here: updateVisibility does it, and on a lock screen that stays it
            // goes back into its circle first. Set GONE here, the torch's island switched off
            // with a double tap was gone in one frame, the way back never started.
            hideSmallIsland()
            restoreHeader()
            updateVisibility()
            return
        }
        val lost = selectedIsland != null && selectedIsland !in islandKeys
        var selected = selectedIsland?.takeIf { it in islandKeys } ?: islandKeys.first()
        // A focus island new to the row takes the big place from the one there when it outranks
        // it, as the super island's handleReplacedState gives it: the navigation started comes up
        // in the pill and the music goes into the small place. An ordinary notification never
        // does - each message would take the pill. Busy with another motion, it is tried again
        // on the next refresh.
        val taker = if (lost || selectedIsland == null) null else fresh.firstOrNull {
            it != selected && it in islandKeys && isFocusIsland(it) && takesPlace(selected, it, big = true)
        }
        if (taker != null) {
            val busy = busyForTakerSwap()
            if (busy) islandKnown.remove(taker)
            else {
                val oldBig = selected
                val oldSmall = smallKey
                selected = taker
                preferredSmall = null
                aloneFirst = null
                MiniPlayerRuntime.noteTouch("island ${taker.takeLast(6)} takes the pill from ${oldBig.takeLast(6)}")
                if (player?.visibility == View.VISIBLE) handler.post {
                    if (swapMotion.swap == null && morph == null && selectedIsland == taker) {
                        swapMotion.startSwap(oldBig, oldSmall, intoSmall = smallKey == oldBig)
                    }
                }
            }
        }
        selectedIsland = selected
        // The notification in the pill went (dismissed, answered elsewhere): the next island
        // grows into the pill from its end, as one coming out of the stack does.
        // A pill not on screen - the music hidden by its cover, say - has nothing to switch from:
        // a switch here took the next island's coming-up from its first frame (`op mini`: up done
        // frames=1), and it grows in as a new row instead.
        if (lost && player?.visibility == View.VISIBLE) {
            val oldSmall = smallKey
            handler.post { if (swapMotion.swap == null && morph == null) swapMotion.startSwap(null, oldSmall) }
        }
        val view = player ?: MiniPlayerView(context).also {
            player = it
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
            Gen.ownView(host, it)
        }
        if (music != null) binding.trackMusic(music)
        // A notification let out and on its way home to the pill is not among the notes yet - nor
        // for a run of the pipeline after it has landed (returning). Looked up in the notes
        // alone, the pill took the music for those frames: the music flashed in the pill right
        // after the notification had landed there (2026-09-25).
        val note = notes.firstOrNull { it.key == selected }
            ?: selected.takeIf { it != MUSIC_ISLAND }?.let(LockIslands::noteFor)
        // The music only for the music's seat: a notification whose row has not come back yet
        // took the music's picture for those frames, under the flight landing on the pill. It
        // binds nothing now, and the pill stays out of sight until it has (pillUnbound).
        // From TakeKazeX's PR #15.
        val showsMusic = selected == MUSIC_ISLAND && music != null
        traced("MC r.bind") {
            if (showsMusic) binding.bindMusic(view, music!!, config)
            else if (note != null) binding.bindNote(view, note, config)
        }
        pillShowsMusic = showsMusic
        // The music's own seat with no session for a moment (a stop between two tracks) keeps
        // what it showed, as it always has.
        pillUnbound = selected != MUSIC_ISLAND && note == null
        // After the small island is chosen: whether the music is it decides its picture's bridge.
        updateSmallIsland(music, notes)
        traced("MC r.artBridge") { applyArtBridge() }
        binding.applyInk()
        updateVisibility()
        schedulePosition()
        prewarmSpares()
    } finally { android.os.Trace.endSection() } }

    internal val binding = MiniPlayerBinding(this)

    private fun chooseController(): MediaController? {
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
            if (value.sessionToken == controller?.sessionToken) usableSeenAt = android.os.SystemClock.uptimeMillis()
            return true
        }
        if (cardHolds(value)) return true
        if (usableSeenAt == 0L || value.sessionToken != controller?.sessionToken ||
            value.sessionToken != liveFor) return false
        val left = usableSeenAt + USABLE_GAP_MS - android.os.SystemClock.uptimeMillis()
        if (left <= 0L) return false
        // Settled either way when the gap runs out: the state back, or the music gone for good.
        handler.removeCallbacks(usableGapEnd)
        handler.postDelayed(usableGapEnd, left + 1L)
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

    internal fun updateVisibility() { android.os.Trace.beginSection("MC updateVisibility"); try {
        val view = player
        // Spread out as the stack's list, the row, the small island and the media card are the
        // spread's (endSpread hands them back).
        if (spreadMotion.spread != null) {
            view?.setInteractionsEnabled(false)
            return
        }
        val config = this.config
        val enabled = config.getBoolean(MiniPlayerConfig.ENABLED)
        val current = controller
        // Any island at all: the music, or a notification.
        val sessionUsable = islandKeys.isNotEmpty()
        // Unlocking, the keyguard says it is going away on the first frame of its fade-out.
        // Handing the card back then showed the whole OEM card through that fade - the
        // artwork flashing on every unlock. Until the lock screen is actually off the screen,
        // it keeps what it had; the pill fades with it (followShortcuts).
        if (enabled && sessionUsable && MiniPlayerScene.keyguardGoingAway && Main.onKeyguardNow()) {
            player?.setInteractionsEnabled(false)
            return
        }
        val keyguardOwned = enabled && sessionUsable &&
            !MiniPlayerScene.keyguardGoingAway && Main.keyguardLocked()
        // The lock screen's own choice of pill or card holds through everything that passes over
        // the lock screen - the doze, the PIN pad, the control centre. It used to be dropped
        // for each of them, so the card came back for the doze and for every swipe that raised
        // the pad; the pill now fades and zooms with the shortcut row instead (followShortcuts)
        // and only stops taking touches.
        // With music, the lock screen's media card has to be up; a row of notifications alone
        // needs only the lock screen.
        // The music on its flight out of the cover is not gated by the card either, as canShow():
        // startSceneFlight puts it back first in the row a moment before its morph exists, and
        // for that refresh the cover still up read as "not presentable" - the row went and came
        // back within 2ms, a whole list rebuild each way, on every exit (probed 2026-09-25).
        val presentable = if (MUSIC_ISLAND in islandKeys && noteMorphMotion.noteMorphKey != MUSIC_ISLAND)
            Main.miniPlayerPresentable() else Main.miniPlayerIslandsPresentable()
        val sceneVisible = keyguardOwned && presentable && !MiniPlayerScene.blocksMiniPlayer
        // Unlocking or a session ending mid-morph: straight to where it was going.
        if (!keyguardOwned) morph?.cancel()
        // The discs are the lock screen's, not the row's: with no island at all - no music, no
        // notification, or the last one pulled out as its row - the torch and camera kept losing
        // their glass (2026-09-25, and #10/#17 after it). They stay wherever the row could be.
        shortcutDiscs.discsWanted = enabled && !MiniPlayerScene.keyguardGoingAway && Main.keyguardLocked()
        shortcutDiscs.rowWanted = keyguardOwned || shortcutDiscs.discsWanted && LockIslands.releasedKeys().isNotEmpty()
        // The control centre stands in for the lock screen's scene block, not for the row being
        // presentable at all. Tapped into the cover, the music is still the pill's choice - the
        // scene only borrows the card - so pulling the centre down put the pill up beside the
        // cover's card: two media cards (the user, 2026-09-30). Pulled up into it, the choice
        // was the card and it never showed.
        val controlCenterOpen = keyguardOwned && presentable &&
            (MiniPlayerScene.controlCenterIsActive || Main.miniPlayerControlCenterUp())
        val nativeRequested = MiniPlayerRuntime.nativeRequested(current?.sessionToken)
        // The music's choice of card or pill is the row's only while the music is settled in
        // the row. Out as its card with other islands in the row, the row still shows, and the
        // card with it; on its way out or in, the card is its morph's.
        val musicSettled = MUSIC_ISLAND in islandKeys && noteMorphMotion.noteMorphKey != MUSIC_ISLAND &&
            cardExchange.exchange?.has(MUSIC_ISLAND) != true
        // A notification's morph is not the media card's: the card stays put away. The music's own
        // flight is: the row stays up under it, through a scene too.
        val cardMoving = (morph != null || groupMotion.group != null) &&
            (noteMorphMotion.noteMorphKey == null || noteMorphMotion.noteMorphKey == MUSIC_ISLAND)
        fun presentationWith(transition: Boolean) = MiniPlayerPresentationPolicy.evaluate(
            MiniPlayerPresentationInput(
                enabled = enabled,
                sessionUsable = sessionUsable,
                nativeRequested = nativeRequested && musicSettled,
                keyguardOwned = keyguardOwned,
                sceneVisible = sceneVisible,
                nativeSceneOverride = keyguardOwned && Main.coverSceneActive(),
                transitionActive = transition,
                controlCenterOpen = controlCenterOpen,
                miniShowsMusic = pillShowsMusic,
            ),
        )
        // An exchange keeps the row up - out of the cover, the row went for its frames and every
        // island's notification stood in the stack with the card - but lets the media card out
        // only when the music is one of its two (musicSettled): two notifications switching, the
        // card came up over them and went again as the switch ended (2026-09-25).
        val presentation = presentationWith(cardMoving || cardExchange.exchange != null)
        val suppressCard = presentationWith(cardMoving).suppressNative
        if (sceneLandedAt != 0L && (Main.coverSceneActive() ||
                android.os.SystemClock.uptimeMillis() - sceneLandedAt > SCENE_WAIT_MS)) sceneLandedAt = 0L
        val shown = presentation.showMini && sceneLandedAt == 0L
        // The stack leaves the notifications out only while the row is there to show them.
        // ...and not while the media card's morph has the rows turning into islands or back.
        // No notification island in the row, but one would show if it came - the empty row, or
        // the music alone out as its card: the stack is kept from it already. Asked only once
        // it had come, the row let it into the stack first, and its row was drawn there, text and
        // icon, for the frames before the island took it (the torch's own notification after a
        // double tap on it, 2026-09-29).
        val noteWouldShow = enabled && !MiniPlayerScene.keyguardGoingAway && Main.keyguardLocked() &&
            sceneLandedAt == 0L && MiniPlayerPresentationPolicy.evaluate(
                MiniPlayerPresentationInput(
                    enabled = true,
                    sessionUsable = true,
                    nativeRequested = false,
                    keyguardOwned = true,
                    sceneVisible = Main.miniPlayerIslandsPresentable() && !MiniPlayerScene.blocksMiniPlayer,
                    controlCenterOpen = MiniPlayerScene.controlCenterIsActive || Main.miniPlayerControlCenterUp(),
                ),
            ).showMini
        val active = (shown && keyguardOwned || noteWouldShow && islandKeys.none { it != MUSIC_ISLAND }) &&
            !groupMotion.rowsHeld()
        if (active != lastActive) {
            // Which input turned the row off or on, and who asked: leaving the cover the row
            // went and came back within 10ms, each a whole list rebuild (2026-09-25).
            lastActive = active
            val by = Throwable().stackTrace.getOrNull(1)?.let { "${it.methodName}:${it.lineNumber}" }
            MiniPlayerRuntime.noteTouch("active=$active by=$by keys=${islandKeys.size} " +
                "ko=$keyguardOwned pres=$presentable blk=${MiniPlayerScene.blocksMiniPlayer} " +
                "nr=$nativeRequested ms=$musicSettled tr=${cardMoving || cardExchange.exchange != null} " +
                "cc=$controlCenterOpen landed=${sceneLandedAt != 0L} morph=${morph != null} " +
                "scene=${Main.coverSceneActive()}")
        }
        LockIslands.setActive(active)
        spreadMotion.reopenKeptList(active && sceneVisible)
        // The island in the pill is kept across an unlock (the user, 2026-10-24): locked again,
        // the row is as it was left, and an island gone meanwhile falls to the row's first.
        // While a flight is out and coming back, its end is the small island's place: the small
        // island is the flight, and whatever was there makes way. Once it is out for good the
        // place is the next island's.
        // Coming in (a collapse), from the moment the flight exists: counted only once its morph
        // ran, the small island grew in its place for the frame before and then shrank for it.
        // Going out it waits for the morph, which takes it over in place.
        val held = noteMorphMotion.flight != null && (morph != null || !noteMorphMotion.flightFromSmall) && !flightOut &&
            smallKey == noteMorphMotion.noteMorphKey && !flightLanding
        // Under the media card's morph the group has the small island (groupShowsSmall).
        setSmallShown(shown && smallKey != null && !held && !cardExchange.exchangeHoldsSmall() &&
            (groupMotion.groupShowsSmall() ?: true),
            animate = shown && !snapSmallOnce && groupMotion.group == null && cardExchange.exchange == null)
        snapSmallOnce = false
        if (!keyguardOwned) preferredSmall = null
        // Cards still out across an unlock (LockIslands keeps the focus ones) go home where they
        // came from; the rest of the record is this lock screen's.
        if (!Main.keyguardLocked()) {
            noteMorphMotion.releasedFromPill.retainAll { it != MUSIC_ISLAND && LockIslands.isReleased(it) }
            noteMorphMotion.releasedFromSmall.retainAll { it != MUSIC_ISLAND && LockIslands.isReleased(it) }
        }
        if (view != null) {
            val target = if (shown) View.VISIBLE else View.GONE
            if (view.visibility != target) {
                // Up out of nothing on a lock screen already there: it grows in. Coming back
                // under a flight, a card's morph or a switch, or with the row's wake fade, those
                // bring it; and a row going just goes.
                // New is a notification island the row did not have when last shown - not only an
                // empty record. The record is emptied only by the way back, so a row put away any
                // other way (the unlock, the doze, the cover) left it full, and the next
                // notification came up in a frame unless the lock screen happened to be quiet:
                // the animation "sometimes lost" (2026-10-14). The same islands coming back on
                // the wake's fade are still not new, and still come with it.
                val newRow = appearMotion.lastShownKeys.isEmpty() ||
                    islandKeys.any { it != MUSIC_ISLAND && it !in appearMotion.lastShownKeys }
                val growIn = target == View.VISIBLE && keyguardOwned && !frameTaken() &&
                    (rowQuiet() && Main.screenOnCached() || newRow)
                // The last island gone from a lock screen that stays: it goes back into nothing.
                // Not for a row put away with the card, the cover, the unlock or the control centre.
                // The pill still has a notification in it and nothing is left for the row: no
                // island, or only the music on its card (rowGoing). Not the pill that was the
                // music and has just gone up into its card - that has already gone, as its morph.
                val rowGone = target == View.GONE && !pillShowsMusic &&
                    islandKeys.none { it != MUSIC_ISLAND } &&
                    (islandKeys.isEmpty() || nativeRequested || Main.coverModeOn()) && enabled &&
                    Main.keyguardLocked() && !coverMoving() && !MiniPlayerScene.blocksMiniPlayer &&
                    !controlCenterOpen && !frameTaken()
                // Only a pill on screen goes back where it can be seen. The power key takes the
                // torch's island in the same moment it starts the screen off - the row faded to
                // nothing, the keyguard flagged as going - and a double press brings the screen
                // back without it: gone there in one frame, it was never seen going (2026-09-29).
                // Such a pill waits, as it is, until it is on screen again - lit, or held into
                // the AOD with the row - and goes back then; waited out, it just goes.
                // Half there is enough: coming back on the wake's fade, it goes back as it fades in.
                val seen = pillSeen()
                val shrinkOut = rowGone && seen
                val now = android.os.SystemClock.uptimeMillis()
                val waitBack = rowGone && !seen && !appearMotion.leaving() &&
                    (appearMotion.backWaitSince == 0L || now - appearMotion.backWaitSince < BACK_WAIT_MS)
                if (waitBack && appearMotion.backWaitSince == 0L) {
                    appearMotion.backWaitSince = now
                    MiniPlayerRuntime.noteGone("wait away=${MiniPlayerScene.keyguardGoingAway} " +
                        "pillFade=${"%.2f".format(followPillFade)} screen=${Main.screenOnCached()} aod=${MiniPlayerScene.aodActive}")
                }
                if (!waitBack) appearMotion.backWaitSince = 0L
                // Also the way back already running and cut here: the else below ends it in a frame.
                if (target == View.VISIBLE) MiniPlayerRuntime.noteGone(
                    "UP grow=$growIn new=$newRow seen=${pillSeen()} sel=${selectedIsland?.takeLast(6)} keys=${islandKeys.joinToString(",") { it.takeLast(6) }} " +
                    "owned=$keyguardOwned screen=${Main.screenOnCached()} aod=${MiniPlayerScene.aodActive} row=${probe.quietWhy()}")
                if (target == View.GONE && !waitBack && (!appearMotion.leaving() || !shrinkOut)) MiniPlayerRuntime.noteGone(
                    (if (shrinkOut) "back" else if (appearMotion.leaving()) "CUT mid-way" else "CUT") + " sel=${selectedIsland?.takeLast(6)} " +
                    "keys=${islandKeys.joinToString(",") { it.takeLast(6) }} pillMusic=$pillShowsMusic " +
                    "native=$nativeRequested on=$enabled away=${MiniPlayerScene.keyguardGoingAway} " +
                    "locked=${Main.keyguardLocked()} cover=${Main.coverSceneActive()} " +
                    "blocks=${MiniPlayerScene.blocksMiniPlayer} cc=$controlCenterOpen " +
                    "screen=${Main.screenOnCached()} row=${probe.quietWhy()}")
                if (shrinkOut) appearMotion.startDisappear()
                else if (waitBack) Unit
                else if (rowGone && appearMotion.leaving()) Unit // on its way back already, seen or not
                else {
                    if (rowGone) appearMotion.lastShownKeys = emptyList()
                    if (target == View.GONE) appearMotion.endAppear()
                    view.visibility = target
                    if (growIn) appearMotion.startAppear()
                }
            } else {
                if (target == View.VISIBLE && appearMotion.leaving()) appearMotion.startAppear()
                appearMotion.backWaitSince = 0L
            }
            view.setInteractionsEnabled(canShow() && !controlCenterOpen && !MiniPlayerScene.aodActive && !appearMotion.leaving() &&
                appearMotion.backWaitSince == 0L)
            if (view.visibility == View.VISIBLE && !appearMotion.leaving() && appearMotion.backWaitSince == 0L && islandKeys.isNotEmpty()) {
                appearMotion.lastShownKeys = islandKeys
            }
        }
        updateNativeSuppression(suppressCard && musicSettled)
        if (morph == null && keyguardOwned &&
            (nativeRequested || Main.coverSceneActive())) ensureNativeHeaderVisible()
        val log = "nativeRequested=$nativeRequested " +
            "showMini=${presentation.showMini} suppressNative=${presentation.suppressNative} " +
            "keyguardOwned=$keyguardOwned sceneVisible=$sceneVisible center=$controlCenterOpen " +
            "sceneOverride=${Main.coverSceneActive()} transition=${morph != null}"
        if (log != lastPresentationLog) {
            lastPresentationLog = log
            Xp.d("MCMini: presentation $log")
        }
        // A focus notification's second button while the pill is the only island: the big one.
        // Out of sight under a switch or a flight, it is set, not grown: it grew in only once the
        // pill was given back, a beat after everything else had landed.
        view?.setSecondShown(shown && smallKey == null, animate = !noteMorphMotion.pillHeld() && !cardExchange.exchangeHoldsPill())
    } finally { android.os.Trace.endSection() } }

    fun updateNativeSuppression(suppress: Boolean) {
        val wasRequested = nativeSuppressionRequested
        nativeSuppressionRequested = suppress
        if (suppress) updateHeader()
        else if (wasRequested || suppressedHeaders.hasOverrides) restoreHeader()
    }

    fun ensureNativeHeaderVisible() {
        // A dismissed card is the OEM's to take away, not ours to bring back.
        if (!Main.miniPlayerMediaCardPresent()) return
        val current = transitionHeader() ?: return
        if (current.visibility != View.VISIBLE) current.visibility = View.VISIBLE
        if (current.alpha < 0.99f) current.alpha = 1f
    }

    private fun updateHeader() {
        val current = header?.get()?.takeIf { it.isAttachedToWindow && !forceHeaderRefresh }
            ?: Main.miniPlayerMediaHeader()
        forceHeaderRefresh = false
        val previous = header?.get()
        if (previous !== current) {
            header = current?.let(::WeakReference)
            if (previous != null && current != null) {
                Xp.log("MCMini: native media header replaced while suppression remains active")
            }
        }
        if (current == null) return
        // INVISIBLE keeps the OEM layout measured but prevents its renderer/property animator
        // from winning a frame over an alpha-only suppression during metadata replacement.
        suppressedHeaders.suppress(current,
            if (current.visibility == View.INVISIBLE) View.VISIBLE else current.visibility,
            if (current.alpha <= 0f) 1f else current.alpha) { state ->
            if (current.visibility != state.visibility) current.visibility = state.visibility
            if (current.alpha != state.alpha) current.alpha = state.alpha
        }
    }

    private fun restoreHeader() {
        nativeSuppressionRequested = false
        suppressedHeaders.restore { view, state ->
            view.visibility = state.visibility
            view.alpha = state.alpha
        }
    }

    /** The row laid out again on the next frame, for `op fod`. */
    fun relayout() = schedulePosition()

    fun schedulePosition() {
        if (positionPosted) return
        positionPosted = true
        host.postOnAnimation { positionPosted = false; position() }
    }

    /**
     * Where the pill rests with a small island beside it ([small]) or alone - the row's own
     * layout (position), asked for another row than the one showing: an island landing in the
     * pill is laid out for the row it lands in (fitToPill).
     */
    fun pillRest(small: Boolean): PillRest? {
        if (host.width <= 0 || host.height <= 0) return null
        // Where the buttons are laid out, not where they are drawn: whatever moves them on top
        // of their layout (the swipe, the doze) reaches the pill through followShortcuts.
        // Taking their drawn place here as well moved the pill twice as far as they went.
        // Only the ones switched on: one switched off takes no disc, and gives no room up.
        // Both had to be laid out before - one switched off since SystemUI started never was,
        // and the row went to the middle of the screen (#13).
        val l = if (placesRow(left)) restCentre(left) else null
        val r = if (placesRow(right)) restCentre(right) else null
        val centerX = if (l != null && r != null) (l[0] + r[0]) / 2f else host.width / 2f
        val config = this.config
        val requestedWidth = dp(config.getDouble(MiniPlayerConfig.WIDTH).toFloat())
        val height = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))
        val centerY = clearOfFingerprint(rowCentreY(l, r, height), height, l, r)
        val beside = small && !stackedStyle()
        if ((l == null || r == null) && config.optBoolean(MiniPlayerConfig.ADAPTIVE_WIDTH)) {
            widenedRest(l, r, beside, height, centerY)?.let { return it }
        }
        // Clear of the discs, a circle as tall as the pill on each button. Laid out rather than
        // shown: the buttons are put away in the doze, and the pill must not widen for it.
        val width = MiniPlayerGeometry.clearOfDiscsPx(
            MiniPlayerGeometry.widthPx(min(requestedWidth, (host.width * .64f).toInt()),
                host.width, centerX, dp(12f)),
            centerX, l?.get(0), r?.get(0), height.toFloat(),
            dp(MiniPlayerGeometry.DISC_GAP_DP).toFloat(), dp(MiniPlayerGeometry.MIN_PILL_DP))
        // With a small island the pill makes room for it, the two centred as one group - and
        // the group kept clear of both discs: the camera's touch area is wider than its disc,
        // and a group reaching into it lost the small island's taps to the camera.
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        val pillWidth = if (!beside) width else MiniPlayerGeometry.pillBesideIslandPx(width,
            MiniPlayerGeometry.clearOfDiscsPx(
                MiniPlayerGeometry.widthPx(host.width, host.width, centerX, dp(12f)),
                centerX, l?.get(0), r?.get(0), height.toFloat(), gap.toFloat(), 0),
            height, gap, dp(MiniPlayerGeometry.MIN_PILL_DP))
        return PillRest(pillWidth, height, centerX, centerY)
    }

    /**
     * A shortcut switched off in the lock screen's settings: the OEM makes its image and its
     * frame GONE and leaves both in the tree (ShortcutViewLayoutController.updateShortcutView).
     * Put away for the doze or the PIN pad it is still switched on - that is the row's fade,
     * and the row must not widen for it.
     */
    private fun switchedOff(button: View): Boolean = button.visibility == View.GONE

    /** A button the row is laid out by: switched on, and laid out. */
    private fun placesRow(button: View): Boolean =
        !switchedOff(button) && button.width > 0 && button.height > 0

    /**
     * The row's height on the screen: the buttons', or where one switched off was laid out
     * last - GONE is not laid out again, it keeps its place - or, with neither ever laid out,
     * where a row was last seen in this SystemUI, as a share of the screen. Only a SystemUI
     * started with both switched off has none of those, and the row sits above the
     * navigation bar.
     */
    private fun rowCentreY(l: FloatArray?, r: FloatArray?, height: Int): Float {
        val y = when {
            l != null && r != null -> (l[1] + r[1]) / 2f
            l != null -> l[1]
            r != null -> r[1]
            left.height > 0 -> restCentre(left)[1]
            right.height > 0 -> restCentre(right)[1]
            else -> null
        }
        if (y != null) {
            MiniPlayerRuntime.rowCentreShare = y / host.height
            return y
        }
        val share = MiniPlayerRuntime.rowCentreShare
        if (!share.isNaN()) return share * host.height
        val inset = runCatching {
            host.rootWindowInsets?.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars())?.bottom
        }.getOrNull() ?: 0
        return (host.height - inset - dp(12f) - height / 2f).coerceAtLeast(height / 2f)
    }

    /**
     * The row's centre with the lift applied, and how far it had to move ([fodLiftPx]): the touch
     * region is the row's too, and it needs the second as much as the first (#81).
     */
    private fun clearOfFingerprint(centerY: Float, height: Int, l: FloatArray?, r: FloatArray?): Float {
        val lifted = liftOffFingerprint(centerY, height, l, r)
        fodLiftPx = (centerY - lifted).coerceAtLeast(0f)
        return lifted
    }

    /**
     * The row's centre moved up off the fingerprint sensor where the two would meet
     * (MiniPlayerRuntime.fingerprintArea, #66); the torch and camera stay where they are. Only
     * the span between the buttons counts across: the row never reaches past them. Under a
     * switch (MiniPlayerConfig.FOD_LIFT); off, the row stays put and only the touches that start
     * on the sensor are still left to it.
     */
    private fun liftOffFingerprint(centerY: Float, height: Int, l: FloatArray?, r: FloatArray?): Float {
        if (!config.optBoolean(MiniPlayerConfig.FOD_LIFT, true)) return centerY
        val fod = fingerprintInHost() ?: return centerY
        val from = l?.get(0) ?: 0f
        val to = r?.get(0) ?: host.width.toFloat()
        if (fod.right <= from || fod.left >= to) return centerY
        val margin = dp(FOD_MARGIN_DP)
        val top = centerY - height / 2f
        val bottom = centerY + height / 2f
        if (bottom + margin <= fod.top || top - margin >= fod.bottom) return centerY
        val lifted = fod.top - margin - height / 2f
        if (lifted != fodLiftedTo) {
            fodLiftedTo = lifted
            Xp.log("MCMini: row lifted off the fingerprint sensor $fod: centre $centerY -> $lifted")
        }
        return min(centerY, lifted)
    }

    var fodLiftedTo = Float.NaN

    /**
     * How far up the row actually sits off the line the buttons lay it out on, in px - 0 while
     * nothing lifts it. From the last layout, as [pillRest] worked it out.
     */
    var fodLiftPx = 0f

    /**
     * Where the keyguard's notifications must end for the row lifted off the sensor - the lifted
     * row's top less the same margin it keeps from the sensor - in the stack's own coordinates,
     * as SystemUI's stacking bottom is; null with the row not lifted (MiniPlayerRuntime.
     * notificationBottom). Whether the row is shown does not count.
     */
    fun stackFloor(): Float? {
        if (host.width <= 0 || host.height <= 0) return null
        if (!config.optBoolean(MiniPlayerConfig.FOD_LIFT, true)) return null
        val l = if (placesRow(left)) restCentre(left) else null
        val r = if (placesRow(right)) restCentre(right) else null
        val height = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))
        val natural = rowCentreY(l, r, height)
        val lifted = clearOfFingerprint(natural, height, l, r)
        if (lifted >= natural) return null
        val stackTop = stackTopInHost() ?: return null
        return lifted - height / 2f - dp(FOD_MARGIN_DP) - stackTop
    }

    /**
     * The stack's top in the host, as laid out: SystemUI's bottom is a layout position, and the
     * swipes and the unlock translate the stack's parents.
     */
    private fun stackTopInHost(): Float? {
        var v: View = stackHost.notificationStack() ?: return null
        var y = 0f
        while (v !== host) {
            y += v.top
            v = v.parent as? View ?: return null
        }
        return y
    }

    /** The sensor in the host's coordinates, or null. */
    fun fingerprintInHost(): android.graphics.Rect? {
        val fod = MiniPlayerRuntime.fingerprintArea(context) ?: return null
        val at = IntArray(2).also(host::getLocationOnScreen)
        return android.graphics.Rect(fod).apply { offset(-at[0], -at[1]) }
    }


    /**
     * The row across the room a switched-off shortcut leaves (MiniPlayerConfig.ADAPTIVE_WIDTH):
     * from the disc still there, or from where the missing disc's outer edge would be, across
     * to the other side - the pill and its small island centred in it as one group. Null when
     * that room is narrower than the pill's least width.
     */
    private fun widenedRest(l: FloatArray?, r: FloatArray?, small: Boolean, height: Int,
                            centerY: Float): PillRest? {
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        // The discs' outer margin, from a button still laid out, switched off or not.
        val margin = listOf(left, right).firstOrNull { it.width > 0 && it.height > 0 }
            ?.let { restCentre(it)[0] }
            ?.let { cx -> (min(cx, host.width - cx) - height / 2f).coerceAtLeast(dp(12f).toFloat()) }
            ?: dp(12f).toFloat()
        val start = l?.let { it[0] + height / 2f + gap } ?: margin
        val end = r?.let { it[0] - height / 2f - gap } ?: (host.width - margin)
        val room = (end - start).toInt()
        val least = dp(MiniPlayerGeometry.MIN_PILL_DP)
        if (room < least) return null
        val pillWidth = if (!small) room
            else MiniPlayerGeometry.pillBesideIslandPx(room, room, height, gap, least)
        return PillRest(pillWidth, height, (start + end) / 2f, centerY)
    }

    fun position() {
        // The buttons laid out again may have moved the floor; the pill need not be up for it.
        runCatching { MiniPlayerRuntime.checkStackFloor() }
        val view = player ?: return
        if (view.visibility != View.VISIBLE || spreadMotion.spread != null) return
        val small = smallKey != null
        val rest = pillRest(small) ?: return
        val pillWidth = rest.width
        val height = rest.height
        val centerY = rest.centerY
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        val beside = small && !stackedStyle()
        val group = pillWidth + if (beside) gap + height else 0
        val groupLeft = rest.centerX - group / 2f
        if (small) {
            if (stackedStyle()) {
                smallRest[0] = rest.centerX
                // The 90% rear card exposes a rim below the front card.
                smallRest[1] = MiniPlayerGeometry.stackBackCenterYPx(centerY, height,
                    dp(MiniPlayerGeometry.STACK_EDGE_DP))
                if (!smallWide) smallIsland?.let {
                    resizeSmallIsland(it, maxOf(shortcutDiscs.discFrame(height), smallRestWidth() + dp(16f)),
                        shortcutDiscs.discFrame(height))
                }
            } else {
                smallRest[0] = groupLeft + pillWidth + gap + height / 2f
                smallRest[1] = centerY
            }
        }
        // The asked-for size, not view.width: a morph resizes the frame while this still runs.
        // The spring first, then the new size: set the other way round, the pill was drawn at
        // its new width for the frame before its spring set out from the old one.
        val x = groupLeft - view.left
        val y = centerY - height / 2f - view.top
        view.setBaseTranslation(x, y)
        rowTarget(groupLeft, pillWidth.toFloat())
        if (view.layoutParams.width != pillWidth || view.layoutParams.height != height) {
            view.layoutParams = view.layoutParams.apply { this.width = pillWidth; this.height = height }
            schedulePosition()
        }
    }

    internal fun togglePlayback() = command(
        PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    ) { current, playing ->
        if (playing) current.transportControls.pause() else current.transportControls.play()
    }

    internal fun skip(next: Boolean) = command(
        if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    ) { current, _ ->
        if (next) current.transportControls.skipToNext() else current.transportControls.skipToPrevious()
    }

    private fun command(actionWhenPlaying: Long, actionWhenPaused: Long, fallbackKey: Int,
                        transport: (MediaController, Boolean) -> Unit) {
        handler.post {
            runCatching {
                val current = chooseController() ?: controller
                val playing = current?.playbackState?.state == PlaybackState.STATE_PLAYING
                val action = if (playing) actionWhenPlaying else actionWhenPaused
                val supported = current?.playbackState?.actions?.and(action) != 0L
                if (current != null && supported) {
                    runCatching { transport(current, playing) }
                        .onFailure { dispatchFallback(fallbackKey) }
                } else dispatchFallback(fallbackKey)
            }.onFailure { Xp.w("MCMini: media command failed: $it") }
        }
    }

    private fun dispatchFallback(key: Int) {
        val manager = audio ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now,
                android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
        }
    }

    fun dp(value: Float) = (value * context.resources.displayMetrics.density + .5f).toInt()

}

/** The music's place in the row of islands, beside the notifications' keys. */
internal const val MUSIC_ISLAND = "\u0000music"

/**
 * Sideways, tightly: the row is pressing on what is beside it - the torch, the camera, the small
 * island - and a loose follow slid it far too easily (the user, 2026-09-29). It carries the row
 * past the end of its islands too (islandDrag), where the pull presses on a disc instead of
 * switching. Here rather than in the object: the controller's own drag reads it as well.
 */
internal fun pillNudgeX(dx: Float, d: Float): Float =
    Math.copySign(MiniCardMorph.rubber(kotlin.math.abs(dx), 24f * d, 0.6f), dx)
