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
    private var nativeSuppressionRequested = false
    var morph: MiniCardMorph? = null
    internal var morphScene = false
    internal var artBridged = false
    /** Whether the pill is bound to the music as refresh last bound it, not to a notification. */
    private var pillShowsMusic = false
    /** Refresh had nothing to bind the pill's island to: it still holds another's picture. */
    internal var pillUnbound = false
    private var lastTrack = ""
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
        val sb = StringBuilder("${describeInkCounts()} cardTitle=${hex(Main.miniPairTitle())} cardArtist=${hex(Main.miniPairArtist())}")
        val seen = HashSet<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.isShown) {
                val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "?"
                val entry = "$id=${Integer.toHexString(v.currentTextColor)}"
                if (seen.add(entry) && seen.size <= 12) sb.append(' ').append(entry)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        notificationStack()?.let(::walk)
        Main.miniPlayerMediaHeader()?.let(::walk)
        return sb.toString()
    }

    private fun stateOf(c: MediaController): PlaybackState? =
        if (liveFor != null && c.sessionToken == liveFor) liveState else c.playbackState

    private fun metadataOf(c: MediaController): MediaMetadata? =
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
        holdKept()
        groupMotion.holdRows()
        // The rows can settle after the stack last told the clock where they are - a row let out
        // is laid out a frame or more after the list changed. The clock is asked again then.
        val top = stackContentTop()
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
        lottiesFollowSight()
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
        if (shortcutRow?.get()?.isAttachedToWindow != true) findRow()?.let { shortcutRow = WeakReference(it) }
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
        val pillFade = if (noteMorphMotion.pillHeld() || exchangeHoldsPill() || pillUnbound) 0f else fade
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

    private fun materialKey(): String {
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
            tapIsland(key)
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
            exchange?.seats?.small == null) return false
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
        val seated = exchange?.seats
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
        val moving = focusLottie(view, note?.anim)
        view.setLive(liveArt(view, note?.live))
        val picture: Any? = if (key == MUSIC_ISLAND) (thumbShown ?: cachedCover) else moving ?: note?.icon
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
    private val returning = LinkedHashMap<String, Long>()

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

    private fun startSmallPulse(key: String?) {
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
        exchange != null || noteMorphMotion.noteMorphKey != null || noteMorphMotion.pillLandingBox != null || spreadMotion.spread != null



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
        traced("MC sv.bind") { if (music != null && note == null) bindMusic(view, music, config) else bindNote(view, note!!, config) }
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
    private fun fitToPill(view: MiniPlayerView, small: Boolean) {
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
    private val spareViews = ArrayList<MiniPlayerView>()

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
            if (!destroyed && host.isAttachedToWindow && exchange == null &&
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

    private fun hasLaidOutRow(key: String): Boolean = findRow(key)?.let { (row, top) ->
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
            LockIslands.releasedKeys().any { rowFor(it) != null }
    }

    /**
     * Busy for a flight into or out of the cover or the lyrics: the motions that keep it from
     * starting (M0, the subset as it was).
     */
    private fun busyForSceneFlight(): Boolean = noteMorphMotion.noteMorphKey != null || noteMorphMotion.flight != null || exchange != null

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
        exchange?.let { x ->
            if (x.expanded == MUSIC_ISLAND) x.expanded = null
            if (x.pending == MUSIC_ISLAND) x.pending = null
            // Out of the movers first: its morph ending must not settle it as a landing.
            x.movers.remove(MUSIC_ISLAND)?.let { m ->
                unland(m)
                m.morph?.cancel()
                recycleSmallView(m.view)
            }
            x.seats = x.seats?.let { s ->
                Seats(s.big?.takeUnless { it == MUSIC_ISLAND }, s.small?.takeUnless { it == MUSIC_ISLAND })
            }
            if (x.pending == null && x.movers.isEmpty()) endSwitch(x)
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
        musicFlies() && musicCarded() && morph == null && exchange == null && noteMorphMotion.noteMorphKey == null &&
        spreadMotion.spread == null

    /**
     * The one island out as its card and settled there - the super island's expanded island.
     * Null with every island in the row. Only ever one: another asked out takes its place.
     */
    fun expandedKey(): String? {
        // The cover is the music's card: another island opened there takes its place, and the
        // cover goes (2026-09-25) - not a row of its own in the cover's stack beside the card.
        if (musicExpanded(includeCover = true)) return MUSIC_ISLAND
        return LockIslands.releasedKeys().firstOrNull { it != noteMorphMotion.noteMorphKey && rowFor(it) != null }
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
    private fun takesPlace(cur: String, origin: String, big: Boolean): Boolean {
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

    /**
     * The super island's expanded switch, as ClickEventCoordinator and its state handlers run it
     * (read from the plugin, 2026-09-25). [up] - in the big place if [fromPill], else the small
     * one - goes out as its card, and [down], out now, comes back in. Tapped in the big place,
     * that place is filled first (BigIslandStateHandler.handleFillInState): the small island
     * moves up into it and the next hidden one into the small place. Tapped in the small place,
     * the next hidden one fills that. Then [down] comes in at the big place
     * (handleReplacedState): if it takes the place from what is there, that one moves down to
     * the small place's turn; else [down] tries the small place itself, and whatever loses that
     * goes into hiding. So the card coming down does not simply land where the tapped island
     * was: with three, the third island can change places with it. With nothing out - the one
     * that was is on its way home already - only the place [up] leaves is filled.
     */
    private fun reseat(up: String, down: String?, fromPill: Boolean): Seats {
        val big0 = selectedIsland
        val small0 = smallKey
        val keys = islandKeys
        val at = keys.indexOf(big0).coerceAtLeast(0)
        // The hidden ones, next first: after the small island round the row.
        val hidden = (keys.drop(at) + keys.take(at))
            .filter { it != big0 && it != small0 && it != up && it != down }.toMutableList()
        var big: String?
        var small: String?
        if (fromPill) {
            big = small0?.takeIf { it != up && it != down } ?: hidden.removeFirstOrNull()
            small = hidden.removeFirstOrNull()
        } else {
            big = big0?.takeIf { it != up && it != down }
            small = hidden.removeFirstOrNull()
        }
        fun intoSmall(key: String) {
            val s = small
            when {
                s == null -> small = key
                takesPlace(s, key, big = false) -> { hidden.add(0, s); small = key }
                else -> hidden.add(0, key)
            }
        }
        val b = big
        when {
            down == null -> {}
            b == null -> big = down
            takesPlace(b, down, big = true) -> { big = down; intoSmall(b) }
            else -> intoSmall(down)
        }
        return Seats(big, small)
    }

    /** [key]'s card: the media card for the music, the notification's own row in the stack. */
    fun nativeFor(key: String): View? = if (key == MUSIC_ISLAND)
        transitionHeader()?.takeIf { Main.miniPlayerMediaCardPresent() } else rowFor(key)

    // ---- a card landing where the stack is settling it

    /**
     * How far down [native] is still to go, on screen: from where the stack has it this frame
     * to where it will have it once [leaving]'s row and the rows already on their way out are
     * gone. The keyguard's stack is laid out from the bottom up, so every row going away under
     * a card moves that card down by the room it took. A card opening while the one it replaces
     * was still in the stack landed above that one's row and was slid down to the bottom by the
     * stack half a second later (filmed 2026-09-25); it lands at the bottom now. The stack's own
     * target (the view state it is animating each row to) is taken, not where it is mid-animation.
     */
    internal fun settleDy(native: View, leaving: Collection<String>): Float { return traced("MC settleDy") {
        val stack = notificationStack() ?: return 0f
        if (native.parent !== stack) return 0f
        val gone = HashSet<View>()
        for (k in leaving) if (k != MUSIC_ISLAND) findRow(k)?.let { (row, top) -> if (row === top) gone += top }
        val kids = ArrayList<View>()
        for (i in 0 until stack.childCount) {
            val c = stack.getChildAt(i)
            if (c.visibility == View.GONE || c.height <= 0) continue
            val row = c.javaClass.name.contains("ExpandableNotificationRow")
            if (!row && c !== native) continue
            kids += c
            // Not the stack island's piled rows while it opens: hidden while they come up out of
            // the pile, they stay. Counted as leaving, the island's card flew 244px low, to where
            // WeChat's row was, and was pulled back up when the pile ended. Going home they go
            // with it: kept, a focus card let out in its place landed a row too high (2026-09-26).
            if (row && c !== native && c in hiddenRows && (c !in groupMotion.pileRows || groupMotion.stackGoingHome())) gone += c
        }
        val top = kids.associateWith { stackTargetY(it) + it.top }
        val nativeTop = top[native] ?: return 0f
        var shift = 0f
        var above = Float.NaN
        val now = android.os.SystemClock.uptimeMillis()
        val recordDiagnostic = now - settleDiagnosticAt >= 100L
        val goneTrace = if (recordDiagnostic) StringBuilder() else null
        // Rows given back early are transient views, not among these: what is left here is only
        // a row the stack has not been told about yet, for the frame or two until it has.
        for (c in kids.sortedBy { top[it] }) {
            val t = top[c] ?: continue
            val bottom = t + stackTargetHeight(c)
            if (c in gone && t > nativeTop && !above.isNaN()) shift += bottom - above
            if (c in gone && goneTrace != null) goneTrace.append(if (t > nativeTop) " v" else " ^").append(t.toInt())
                .append('+').append((bottom - t).toInt()).append("/ty").append(c.translationY.toInt())
            above = bottom
        }
        // The stack's own target, plus the room of the rows it has not been told are going yet.
        val stackTarget = stackTargetY(native)
        // Where a card rests alone at the bottom of the pile, learned while the stack is one.
        if (gone.isEmpty() && shift == 0f) learnBottomLine(kids, top)
        // Never past that bottom: the rows going are counted as if the stack were piled from the
        // bottom up, and in the stack island's list they reach below it - the stopwatch, laid out
        // over the list's three rows on its way up, was sent 244px under its place and pulled back
        // up once they had gone (2026-09-26).
        // In the stack's translations, as stackTarget is: the line is laid-out top plus translation.
        val floor = stackBottomLine.takeIf { shift > 0f && !it.isNaN() }
            ?.let { it - stackTargetHeight(native) - native.top }
        val target = if (floor != null) minOf(stackTarget + shift, floor) else stackTarget + shift
        lastGone = gone.size
        lastStackTarget = stackTarget
        if (recordDiagnostic) {
            settleDiagnosticAt = now
            lastSettle = (if (floor != null && stackTarget + shift > floor) "floor=${floor.toInt()} " else "") +
            "t=${target.toInt()} st=${stackTarget.toInt()}${if (targetRead) "" else "?"} " +
            "sh=${shift.toInt()} gone=${gone.size}$goneTrace kept=${kept.size} " +
            "ty=${native.translationY.toInt()} " +
            "rows=${kids.size} mt=${Main.liveMediaTop().let { if (it.isNaN()) "-" else it.toInt().toString() }}"
        }
        return target - native.translationY
    } }

    /**
     * The bottom of the keyguard stack's pile - where a lone card's bottom rests - in the stack's
     * own translation space; NaN until seen. Read off the rows as they are laid out while the
     * stack is a pile (not the island's list, which runs on below it), nothing leaving.
     */
    private var stackBottomLine = Float.NaN
    private var bottomLineAt = 0L

    private fun learnBottomLine(kids: List<View>, top: Map<View, Float>) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - bottomLineAt < 500L) return
        bottomLineAt = now
        if (stackState() != "STACK") return
        // Laid-out top plus translation, as settleDy's tops are.
        val bottom = kids.mapNotNull { c -> top[c]?.let { it + stackTargetHeight(c) } }.maxOrNull() ?: return
        stackBottomLine = bottom
    }

    /** The last settleDy's count of rows still on their way out. */
    private var lastGone = 0

    /** The stack's own target for the card, as the last settleDy read it. */
    private var lastStackTarget = 0f

    /** The last settleDy reading, for the landing trace. */
    private var lastSettle = ""
    private var settleDiagnosticAt = 0L
    private var contentDiagnosticAt = 0L

    /**
     * The translation the stack is taking [v] to: its view state's mYTranslation, which the
     * stack's animator runs translationY to (ViewState.startYTranslationAnimation) - else where
     * it is. Asked by the AOSP names first, it read where the row was every frame (2026-09-25).
     */
    fun stackTargetY(v: View): Float = runCatching {
        val state = Xp.callMethod(v, "getViewState")!!
        (Xp.getObjectField(state, "mYTranslation") as Number).toFloat().also { targetRead = true }
    }.getOrElse {
        targetRead = false
        v.translationY
    }

    /** Whether the last stackTargetY read the stack's own target. */
    private var targetRead = false

    private var contentTopAt = 0L
    private var contentTop = Float.NaN
    /** The same rows as [contentTop], where they are drawn this frame rather than headed. */
    private var contentDrawnTop = Float.NaN

    /**
     * [stackContentTop]'s rows where they are drawn now, on screen: for what has to keep off them
     * frame for frame - the countdown's disc - rather than make room for where they will be.
     */
    fun stackDrawnTop(): Float =
        if (stackContentTop().isNaN()) Float.NaN else contentDrawnTop

    /**
     * See the static stackContentTop: every row and card in the stack, at the stack's own target -
     * but not the rows this controller has hidden. A notification gone into an island keeps its
     * row in the stack, invisible, and the stack lays out from the bottom up: with the stopwatch
     * let out under it, the hidden QQ row stood 258px above the stopwatch's card and the clock
     * shrank for a row nobody could see (filmed 2026-09-26). settleDy skips them the same way.
     * Nor any child not drawn at all - the media card behind the pill is the other one.
     * With every row hidden, there is no notification constraint on the clock's edited position.
     */
    fun stackContentTop(): Float {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED)) return Float.NaN
        // Asked on every frame of the clock's own animation: read once a frame at most.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - contentTopAt < 6L) return contentTop
        contentTopAt = now
        val stack = notificationStack()
        contentDrawnTop = Float.NaN
        contentTop = if (stack == null || !stack.isAttachedToWindow) Float.NaN else {
            var top = Float.POSITIVE_INFINITY
            var drawnTop = Float.POSITIVE_INFINITY
            var from: View? = null
            var skipped = 0
            val stackY = IntArray(2).also(stack::getLocationOnScreen)[1]
            // Every child weighed, for `op mini`: name:target-on-screen/translation and why skipped.
            val recordDiagnostic = now - contentDiagnosticAt >= 100L
            val seen = if (recordDiagnostic) StringBuilder() else null
            // A card a switch is taking home to its island is the morph's, drawn on its way to the
            // row, not where the stack still has it. The media card going back to the pill for a
            // focus notification was pushed up the stack by the row let out under it, 1700 -> 1425,
            // and the clock shrank for it until it went (probe, 2026-09-26).
            // The same for the music's own morph into the pill: pulled down out of the cover,
            // the exit aimed at the card and then jumped to full size as it went (2026-09-26).
            val retractingMusic = morph?.toNative == false &&
                (noteMorphMotion.noteMorphKey == null || noteMorphMotion.noteMorphKey == MUSIC_ISLAND)
            // The retraction is asked before the header, not after it: this runs on every
            // pre-draw, and with no header to reuse transitionHeader() resolves the card's id and
            // walks the whole window - about a quarter of SystemUI's cycles opening and closing
            // the shade with no media session (simpleperf, #138).
            val leaving = exchange?.movers?.values?.filter { it.headedHome }?.map { it.native }.orEmpty() +
                listOfNotNull(if (retractingMusic) transitionHeader() else null)
            // The row spreading out as the list or folding back: where its islands start from.
            val spreadRest = spreadMoving()?.let {
                player?.takeIf { v -> v.isAttachedToWindow }
                    ?.let { v -> (IntArray(2).also(v::getLocationOnScreen)[1] - stackY).toFloat() }
                    ?: stack.height.toFloat()
            }
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i)
                if (c.visibility == View.GONE || c.height <= 0) continue
                val name = c.javaClass.name
                if (!name.contains("ExpandableNotificationRow") && !name.contains("MediaHeader")) continue
                val t = stackTargetY(c) + c.top
                // One of the spread's cards, hidden or drawn at nothing while its island's morph
                // has it: weighed where the morph is taking it, from its island's place to its own
                // by the spread's progress. Skipped as not drawn, the clock kept its size till the
                // whole list was out and then shrank all at once (the user, 2026-09-30).
                val share = if (spreadRest != null) spreadShare(c) else null
                if (share != null) {
                    val from0 = maxOf(spreadRest!!, t)
                    val at = from0 + (t - from0) * share
                    seen?.let { it.append(' ').append(shortName(c)).append(':').append((stackY + at).toInt()).append('S') }
                    if (at < top) { top = at; from = c }
                    // Drawn by the morph, not the row: where the morph has it is the share's.
                    val drawnAt = from0 + (c.top + c.translationY - from0) * share
                    if (drawnAt < drawnTop) drawnTop = drawnAt
                    continue
                }
                val home = leaving.any { isInside(it, c) }
                if (seen != null) {
                    seen.append(' ').append(shortName(c)).append(':').append((stackY + t).toInt())
                        .append('/').append((stackY + c.top + c.translationY).toInt())
                    if (c in hiddenRows) seen.append('H')
                    if (c.visibility != View.VISIBLE) seen.append('I')
                    if (c.alpha <= 0.01f) seen.append('A')
                    if (pinned?.get() === c) seen.append('P')
                    if (home) seen.append('L')
                }
                // Hidden only while its morph is being made - a tap has sent it out, the row is
                // there, the morph takes it a frame or two later: it is on its way, and the clock
                // gives way to where it is going. Skipped, the clock grew for the frame or two
                // and shrank back on every tap of a run of them (2026-09-26).
                val coming = c in hiddenRows && rowComingOut(c)
                if (coming) seen?.append('C')
                // The media card the pill stands in for is kept INVISIBLE, still laid out (see
                // the setVisibility hook in install): the clock gave way to it too (2026-09-26).
                // Folded into the stack's "N个通知" state, a row is laid out and drawn at nothing
                // (its numStateAlpha): not there for the clock either. Only while the stack says it
                // is folded - opening, the rows fade in from nothing and count from the first frame.
                val folded = NumState.inNumber == true && foldedAway(c)
                if (folded) seen?.append('F')
                if (home || c in hiddenRows && !coming || c.visibility != View.VISIBLE ||
                    c.alpha <= 0.01f && !coming || folded) { skipped++; continue }
                if (t < top) { top = t; from = c }
                val d = c.top + c.translationY
                if (d < drawnTop) drawnTop = d
            }
            contentDrawnTop = ClockRoomPolicy.contentTop(stackY.toFloat(), drawnTop)
            if (seen != null) {
                contentDiagnosticAt = now
                contentTopFrom = (from?.let(::shortName) ?: "-") + " skip=$skipped" +
                (if (exchange != null) " X" else "") + (if (morph != null) " M" else "") + " [" + seen.toString().trim() + "]"
            }
            ClockRoomPolicy.contentTop(stackY.toFloat(), top)
        }
        return contentTop
    }

    /** The spread on its way out or back in (not waiting, not open); null otherwise. */
    private fun spreadMoving(): SpreadMotion.Spread? =
        spreadMotion.spread?.takeIf { it.phase == SPREAD_OPENING || it.phase == SPREAD_CLOSING }

    /**
     * [c], a stack child, is one of the moving spread's cards: its progress, 0 at the islands'
     * end. Not a card out from before while the list opens - that one is where it is.
     */
    private fun spreadShare(c: View): Float? {
        val s = spreadMoving() ?: return null
        val item = s.items.firstOrNull { item ->
            item.native?.let { isInside(it, c) } == true && !(item.wasOut && s.phase != SPREAD_CLOSING)
        } ?: return null
        return if (item.native == null) null else s.progress
    }

    /** A stack row the stack's fold has faded to nothing (its injector's numStateAlpha). */
    private fun foldedAway(c: View): Boolean = runCatching {
        (Xp.getObjectField(Xp.callMethod(c, "getInjector"), "numStateAlpha") as Float) <= 0.01f
    }.getOrDefault(false)

    /**
     * [c], a stack child this controller keeps out of sight, is on its way out as a card: the
     * row a tap or a pull is waiting to morph into, the card a switch is waiting to send up, or
     * one of the stack island's other rows while the island opens.
     */
    private fun rowComingOut(c: View): Boolean {
        fun top(key: String?): View? = when (key) {
            null -> null
            MUSIC_ISLAND -> transitionHeader()
            else -> findRow(key)?.second
        }
        if (noteMorphMotion.rowWaitKey != null && top(noteMorphMotion.rowWaitKey) === c) return true
        val pending = exchange?.pending
        if (pending != null && top(pending) === c) return true
        return c in groupMotion.pileRows && !groupMotion.stackGoingHome()
    }

    /** See stackContentSource. */
    var contentTopFrom = "-"
        private set

    internal fun shortName(v: View): String =
        rowKey(v)?.substringAfter('|')?.substringBefore('|')?.substringAfterLast('.') ?: v.javaClass.simpleName

    private fun stackTargetHeight(v: View): Float = runCatching {
        (Xp.getObjectField(Xp.callMethod(v, "getViewState")!!, "height") as Number).toFloat()
    }.getOrElse {
        runCatching { (Xp.callMethod(v, "getActualHeight") as Number).toFloat() }.getOrDefault(v.height.toFloat())
    }.takeIf { it > 0f } ?: v.height.toFloat()

    /**
     * A card that has landed where the stack is settling it stays there, drawn, while the stack
     * catches up: the rows under it go and it moves down to the same place underneath.
     */
    var pinned: WeakReference<View>? = null
    private var pinnedLeaving: Collection<String> = emptyList()
    private var pinnedSince = 0L

    /**
     * [from]: the stack translation the morph let go of it at, when that was not where the stack
     * is settling it - a card come back down out of a turned-round exchange lands where it left.
     */
    internal fun pinCard(native: View, leaving: Collection<String>, from: Float? = null) {
        unpinCard()
        pinned = WeakReference(native)
        pinnedLeaving = leaving
        pinnedSince = android.os.SystemClock.uptimeMillis()
        // Where the morph let go of it, in the stack's translation.
        val settle = native.translationY + settleDy(native, leaving)
        pinY.value = from ?: settle
        pinY.target = pinY.value
        pinY.velocity = 0f
        pinWant = pinY.value
        pinLast = 0L
        pinPrevTarget = lastStackTarget
        pinSteady = 0
        observePin(native)
        // Placed now, in the frame the morph lets go of it; the callback carries on from there.
        pinFrame.doFrame(0L)
    }

    /**
     * The list scrolled from the landed card: the pin lets go, or the card stays where it landed
     * while the rest of the list moves. Not a card frozen for a morph about to take it.
     */
    fun releaseNativeScrollPin() {
        if (pinned?.get() == null || pinnedAt != null) return
        MiniPlayerRuntime.noteTouch("pin let go for a list scroll")
        unpinCard()
    }

    /**
     * The stack island opened out as the lock screen's list, and the list scrolling: the rows'
     * places are the list's from here. A morph still bringing the rows out ends where it was
     * going, the pile comes apart into the list and the pin lets go - kept, each of them held
     * its rows against the scroll.
     */
    fun onNativeListScroll(stack: View) {
        if (stack !== notificationStack() || !LockIslands.isReleased(STACK_ISLAND)) return
        val opening = morph?.takeIf { noteMorphMotion.noteMorphKey == STACK_ISLAND && it.toNative }
        val moving = exchange?.movers?.get(STACK_ISLAND)?.morph?.takeIf { it.toNative }
        val held = pinned?.get() != null && pinnedAt == null
        if (opening == null && moving == null && groupMotion.piles.isEmpty() && !held) return
        MiniPlayerRuntime.noteTouch("list scroll takes the rows: open=${opening != null} " +
            "move=${moving != null} pile=${groupMotion.piles.size} pin=$held")
        opening?.cancel()
        moving?.cancel()
        if (groupMotion.piles.isNotEmpty()) groupMotion.finishPile(true)
        // Read again: a flight ended here pins its card where it lands (the flight's onSettled).
        if (pinned?.get() != null && pinnedAt == null) unpinCard()
    }

    /** [keep]: the card stays drawn where the pin had it, for a morph taking it this frame. */
    internal fun unpinCard(keep: Boolean = false) {
        val v = pinned?.get()
        pinned = null
        Gen.unframe(pinFrame)
        pinObserved?.get()?.let { Gen.unpreDraw(it, pinPreDraw) }
        pinObserved = null
        if (!keep) v?.setAnimationMatrix(null)
        pinnedDy = 0f
        pinnedAt = null
    }

    /**
     * Holds [v] drawn at the stack translation [at], whatever the stack does with it meanwhile:
     * a card about to come down, for the frames before its morph takes it.
     */
    private fun freezeCard(v: View, at: Float) {
        unpinCard(keep = true)
        pinned = WeakReference(v)
        pinnedLeaving = emptyList()
        pinnedAt = at
        pinnedSince = android.os.SystemClock.uptimeMillis()
        observePin(v)
        pinFrame.doFrame(0L)
    }

    /**
     * The pin is written again right before the frame is drawn, against the translation the
     * stack has given the row by then. Written from a frame callback alone, it was against the
     * last frame's: the stack's animator moves the row later in the same frame, and while it
     * slid the row 20px a frame toward the card, the card was drawn off by that much and came
     * back - the shake after every switch (2026-09-25), with the held place itself never moving.
     */
    private val pinPreDraw = android.view.ViewTreeObserver.OnPreDrawListener { android.os.Trace.beginSection("MC pinPreDraw"); try {
        val v = pinned?.get()
        if (v != null) {
            val dy = (pinnedAt ?: pinY.value) - v.translationY
            if (kotlin.math.abs(dy - pinnedDy) > 0.01f) {
                pinnedDy = dy
                pinMatrix.setTranslate(0f, dy)
                v.setAnimationMatrix(pinMatrix)
            }
        }
        true
    } finally { android.os.Trace.endSection() } }

    private var pinObserved: WeakReference<View>? = null

    private fun observePin(v: View) {
        pinObserved?.get()?.let { Gen.unpreDraw(it, pinPreDraw) }
        Gen.preDraw(v, pinPreDraw)
        pinObserved = WeakReference(v)
    }

    /**
     * [card] - the media card or a view in it - is held below where the stack has it now: by the
     * pin, or as the card an exchange is taking the music up into. The cover lays its artwork
     * out above the card's top, read on screen, which is the stack's place for it: with the row
     * coming down still in the stack under it, a row higher. Into the cover out of an exchange,
     * the artwork flew to a size for that higher card and grew as the stack caught up (filmed
     * 2026-09-25). Null when it is neither.
     */
    fun cardHeldDy(card: View): Float? {
        pinned?.get()?.takeIf { viewWithin(it, card) }?.let { v ->
            return if (pinnedAt != null) pinnedAt!! - v.translationY else pinY.value - v.translationY
        }
        // The music going up into the cover lands where the stack is settling its card. Coming down
        // out of it - or, turned round, going back up into it - it leaves from and lands at where
        // it was held; the row going up pushes it up in the stack meanwhile, 180px, and the cover
        // was laid out small for that.
        val m = exchange?.movers?.get(MUSIC_ISLAND)?.takeIf { it.morph != null && viewWithin(it.native, card) }
            ?: return null
        return (if (m.fromCard) m.cardAt else m.settleAt) - m.native.translationY
    }

    private fun viewWithin(ancestor: View, v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }

    /** A frozen card's stack translation (freezeCard); null for one landing (pinCard). */
    var pinnedAt: Float? = null

    /** The pin's offset this frame, for a morph taking the card from where it is drawn. */
    var pinnedDy = 0f

    private val pinMatrix = Matrix()

    /**
     * A landed card, held: it goes on its own spring toward where it is to be - the estimate
     * (rows still leaving) until the stack has taken them away and laid the rest out again, then
     * the stack's own target. The estimate can be a few pixels off the stack's final layout; held
     * at it until the hold ran out, the card then jumped those pixels (2026-09-25).
     */
    val pinY = Jelly(PIN_RESPONSE, HIDDEN_DAMPING)
    private var pinWant = 0f
    private var pinLast = 0L

    /**
     * Where the landed card is to be drawn. It landed where the stack settles it (measured the
     * same, 2024 both, 2026-09-25), so it stays there: the frames while the rows leave are not
     * read for it - the stack's children, their targets and the rows going out are not all
     * updated in one frame, and followed frame by frame the card dipped 12px and came back. Only
     * a target the stack has held for a few frames with nothing leaving is taken, and only if it
     * is somewhere else (the stack itself changed meanwhile).
     */
    private fun landingDy(v: View, now: Long): Float {
        settleDy(v, pinnedLeaving)
        val st = lastStackTarget
        if (lastGone == 0 && kotlin.math.abs(st - pinPrevTarget) < 0.5f) pinSteady++ else pinSteady = 0
        pinPrevTarget = st
        if (pinSteady >= PIN_STEADY_FRAMES) pinWant = st
        val dt = if (pinLast == 0L) 1f / 120f else ((now - pinLast) / 1000f).coerceIn(0f, 0.05f)
        pinLast = now
        pinY.target = pinWant
        pinY.step(dt)
        return pinY.value - v.translationY
    }

    private var pinPrevTarget = 0f
    private var pinSteady = 0

    private val pinFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC pinFrame"); try {
            val v = pinned?.get() ?: return
            val at = pinnedAt
            val now = android.os.SystemClock.uptimeMillis()
            val dy = if (at != null) at - v.translationY else landingDy(v, now)
            val waited = now - pinnedSince
            val settled = at == null && kotlin.math.abs(dy) < 0.5f && pinY.atRest() &&
                kotlin.math.abs(pinWant - v.translationY) < 0.5f && pinSteady >= PIN_STEADY_FRAMES
            if (!v.isAttachedToWindow || waited > PIN_MS || settled && waited > PIN_SETTLE_MS) {
                trace("pin off ${if (settled) "settled" else if (waited > PIN_MS) "timeout" else "detached"} " +
                    "dy=${"%.1f".format(dy)} $lastSettle")
                unpinCard()
                return
            }
            pinnedDy = dy
            pinMatrix.setTranslate(0f, dy)
            v.setAnimationMatrix(pinMatrix)
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * [key] goes out as its card: the music is chosen as the media card, a notification is
     * given back to the stack. The card is kept out of sight until a morph takes it.
     */
    fun letOut(key: String) { android.os.Trace.beginSection("MC letOut"); try {
        if (key != MUSIC_ISLAND) {
            LockIslands.release(key)
            return
        }
        val token = controller?.sessionToken ?: return
        MiniPlayerRuntime.chooseNative(token)
        updateVisibility()
        transitionHeader()?.let(::hideRow)
    } finally { android.os.Trace.endSection() } }

    internal val kept = HashMap<String, Kept>()

    /** [key] - a notification, or the stack island with all of its rows - back into the row now. */
    internal fun takeInEarly(key: String) {
        if (key == MUSIC_ISLAND || !LockIslands.isReleased(key)) return
        val now = android.os.SystemClock.uptimeMillis()
        val rows = StringBuilder()
        for (member in LockIslands.membersOf(key)) {
            val row = findRow(member)?.second ?: continue
            val held = pinned?.get() === row
            kept[member] = kept.values.firstOrNull { it.row === row }
                ?: Kept(row, row.translationY + if (held) pinnedDy else 0f, row.alpha, now)
            MiniPlayerRuntime.holdTransient(row)
            rows.append(' ').append(shortName(row)).append('@').append(row.translationY.toInt())
        }
        trace("early in ${groupMotion.shortKey(key)}$rows")
        takeIn(key)
    }

    /** [key]'s kept rows are the stack's again: landed home, or let out once more. */
    internal fun letGo(key: String) {
        val keys = (LockIslands.membersOf(key) + key).filter { it in kept }
        if (keys.isEmpty()) return
        val rows = keys.mapNotNull { kept.remove(it)?.row }.distinct()
        for (row in rows) if (kept.values.none { it.row === row }) MiniPlayerRuntime.releaseTransient(row)
        trace("let go ${groupMotion.shortKey(key)} rows=${rows.size}")
    }

    /**
     * [key] was given back early and is turning round: out again. Its rows stay held until the
     * stack has them back as rows - it moves a transient view back itself when it re-adds it. Let
     * go at once, a removal the stack had asked for meanwhile ran then and there: the row left the
     * window under the morph turning it round, and the stack island's other rows jumped out
     * whole where they were (2026-09-26).
     */
    private fun letOutAgain(key: String) {
        val keys = (LockIslands.membersOf(key) + key).filter { it in kept }
        if (keys.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        for (row in keys.mapNotNull { kept.remove(it)?.row }.distinct()) {
            if (kept.values.none { it.row === row }) reAdding[row] = now
        }
        letOut(key)
        trace("out again ${groupMotion.shortKey(key)} rows=${reAdding.size}")
    }

    /** Rows let out again, held until the stack has them back (holdKept). */
    private val reAdding = HashMap<View, Long>()

    /**
     * An island turned round on its way home again is told to the stack only once it has kept
     * to that way for TURN_SETTLE_MS; turned back out meanwhile, nothing is told at all. Every release and recapture rebuilds the stack's whole list (ShadeListBuilder, 5-10ms,
     * and two or three smaller passes after it), and taken at once on every turn, a run of taps
     * mid-switch rebuilt it every 150-300ms: the frames dropped with each (traced 2026-09-26).
     * The first move - a tap, a card let go - still goes at once; landing, the island's last
     * way is told at once too (flushTurn).
     */
    private val turnWanted = HashMap<String, Boolean>()

    private val turnApply = Runnable {
        val due = HashMap(turnWanted)
        turnWanted.clear()
        for ((key, out) in due) tellTurn(key, out)
    }

    fun turnTo(key: String, out: Boolean) {
        // Out again at once: the stack puts its row back with an animation of its own, and told
        // 200ms late, that played mid-way through the card's way up - the stopwatch flashed on
        // a second tap (2026-09-26). At once, the card is still the pill then. Home again is
        // what waits: the row is held where it is drawn meanwhile (holdKept), and a turn out
        // before the wait is up finds it still out, with nothing to tell.
        if (out) {
            turnWanted.remove(key)
            tellTurn(key, true)
            return
        }
        turnWanted[key] = out
        handler.removeCallbacks(turnApply)
        handler.postDelayed(turnApply, TURN_SETTLE_MS)
        trace("turn ${groupMotion.shortKey(key)} ${if (out) "out" else "home"} held")
    }

    /** [key]'s turn not told yet, told now: it has landed, or is being taken over. */
    internal fun flushTurn(key: String) {
        val out = turnWanted.remove(key) ?: return
        tellTurn(key, out)
    }

    private fun tellTurn(key: String, out: Boolean) {
        // Back the way it was before the turns: nothing to tell the stack. But a page behind it
        // may have been taken over meanwhile - the map's island going home, the countdown's out,
        // the map's turned out again before its home was told: its row never went back, and the
        // countdown's home, told later, closed the only page open, down to the plain wallpaper
        // (2026-09-30). Released again, it takes its page back; nothing else happens to a key
        // that is released already.
        if (key != MUSIC_ISLAND && out == LockIslands.isReleased(key)) {
            if (out) LockIslands.release(key)
            return
        }
        if (out) {
            letOutAgain(key)
            if (key != MUSIC_ISLAND && !LockIslands.isReleased(key)) letOut(key)
        } else takeInEarly(key)
    }

    /**
     * The kept rows, each frame before it is drawn: where they were when given back, as they
     * were. The stack animates a removed row away on its own; the morph has it now.
     */
    private fun holdKept() {
        val now = android.os.SystemClock.uptimeMillis()
        if (reAdding.isNotEmpty()) {
            val it = reAdding.entries.iterator()
            while (it.hasNext()) {
                val (row, since) = it.next()
                val back = (row.parent as? ViewGroup)?.indexOfChild(row)?.let { i -> i >= 0 } == true
                if (back) MiniPlayerRuntime.forgetTransient(row)
                else if (now - since > REEADD_MAX_MS) MiniPlayerRuntime.releaseTransient(row)
                else continue
                trace("re-added ${shortName(row)} back=$back")
                it.remove()
            }
        }
        if (kept.isEmpty()) return
        val stale = kept.filter { (_, k) -> !k.row.isAttachedToWindow || now - k.since > KEPT_MAX_MS || !Main.keyguardLocked() }
        for (key in stale.keys) {
            trace("kept ${groupMotion.shortKey(key)} dropped: attached=${stale[key]?.row?.isAttachedToWindow}")
            letGo(key)
        }
        for (k in kept.values.distinctBy { System.identityHashCode(it.row) }) {
            if (k.row.translationY != k.ty) k.row.translationY = k.ty
            if (k.row.alpha != k.alpha) k.row.alpha = k.alpha
        }
    }

    /** ...and comes back into the row. */
    internal fun takeIn(key: String) { android.os.Trace.beginSection("MC takeIn"); try {
        if (key == MUSIC_ISLAND) controller?.sessionToken?.let(MiniPlayerRuntime::chooseMini)
        else LockIslands.recapture(key)
    } finally { android.os.Trace.endSection() } }

    /** A notification taken back in keeps its place until the stack has given it back. */
    fun markReturning(key: String) {
        returning[key] = android.os.SystemClock.uptimeMillis()
        // Asked again once the wait is up, in case the stack never gives it back.
        handler.postDelayed({ if (key in returning) refresh() }, RETURN_WAIT_MS + 50L)
    }

    /**
     * The super island's expanded state switching, as its plugin runs it (read 2026-09-25): a tap
     * on an island in the row sends it out as its card and brings the one out now back in, and
     * the row reseats around the two (reseat). Every island on its way between its place in the
     * row and its card is a [Mover]: a stand-in view on a MiniCardMorph of its own, on its own
     * spring.
     *
     * The super island takes a tap at any moment - DynamicIslandContentView.canClick does not wait
     * for its animations - gives it to whoever holds the place tapped (performClick, by the
     * place's rest frame), reseats the row at once, and every island turns toward its new end from
     * where it is and as fast as it is going (IslandTransitionExecutor: folme.to from the current
     * values, no start state). So does this: a mover turns round (MiniCardMorph.aim keeps the
     * spring's speed) and its row end springs to its new place; an island at rest in the row moves
     * with a switch of the pill and the small island (startSwap). The pill and the small island
     * are always the seats' islands, out of sight while the island they hold is still on its way
     * to them, and take over from it on its last stretch, as from a flight.
     *
     * The first version was one pair, the one going up leading and the one coming down following
     * it the other way; a tap on the place turned the two round, a tap anywhere else finished the
     * pair where it was headed at once and started the next - a jump on every such tap.
     */
    class Mover(val key: String, val view: MiniPlayerView, val native: View) {
        var morph: MiniCardMorph? = null
        /** Its place in the row, from the seats: where it lands once it is headed home. */
        var place = LAND_PILL
        /** The row end on screen: springs from where it was to a new place, on CHANGE_EASE. */
        var endFrom: CoverMorphMotion.Box? = null
        var endTo: CoverMorphMotion.Box? = null
        val endT = Jelly(SWAP_RESPONSE, SWAP_DAMPING).apply { value = 1f; target = 1f }
        /** 0 a pill, 1 the small island's circle: the row end's shape, on the same spring. */
        val round = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
        var endLast = 0L
        /** Came down out of its card: the card is held where it left from and, turned round, lands there. */
        var fromCard = false
        var cardAt = 0f
        /** The stack translation its card is landing at going up, as its morph last read it. */
        var settleAt = 0f
        /** Its card's end is the cover, not the media card chosen. */
        var cover = false
        /** The place's island has taken over from it on its last stretch home. */
        var landed = false
        /** The place it left the row from, for a pull down to bring it back there. */
        var origin = LAND_HIDDEN
        /** What its last trace line was about (moverFrame). */
        var traced = ""
        /** The stack translation it is held at on its way home; null to take it where it is drawn. */
        var homeAt: Float? = null
        /** The offset its card's end had last frame, so a turn starts from where it is drawn. */
        var lastDy = 0f
        val headedHome: Boolean get() = morph?.toNative == false
    }

    class Switch {
        val movers = LinkedHashMap<String, Mover>()
        /** The island out as its card, or on its way there: the last one tapped. */
        var expanded: String? = null
        /** The row as the last tap reseated it. */
        var seats: Seats? = null
        /** A tapped island whose card is being laid out; nothing moves for it till it is. */
        var pending: String? = null
        var pendingPill = false
        var pendingCover = false
        var waitSince = 0L
        fun has(key: String) = key == expanded || key in movers || key == pending
        fun keys(): List<String> = listOfNotNull(expanded) + movers.keys + listOfNotNull(pending)
        fun homeKeys(): List<String> = movers.values.filter { it.headedHome }.map { it.key }
    }

    var exchange: Switch? = null

    /** The island a place holds is still on its way to it: the place is out of sight till it lands. */
    private fun switchHolds(key: String?): Boolean {
        val m = exchange?.movers?.get(key ?: return false) ?: return false
        return m.headedHome && !m.landed
    }

    private fun exchangeHoldsPill(): Boolean = exchange?.let { switchHolds(it.seats?.big) } == true

    private fun exchangeHoldsSmall(): Boolean = exchange?.let { switchHolds(it.seats?.small) } == true

    fun exchangeRunning(): Boolean = exchange != null

    /**
     * Busy for an exchange - one island out as its card, the expanded one home: the motions that
     * keep it from starting (M0, the subset as it was).
     */
    private fun busyForExchange(): Boolean = exchange != null || morph != null || noteMorphMotion.noteMorphKey != null || noteMorphMotion.flight != null || groupMotion.group != null

    /** [up] - the pill's island or the small one - goes out in place of the one out now. */
    fun startExchange(up: String, toCover: Boolean = false): Boolean { android.os.Trace.beginSection("MC startExchange"); try {
        val down = expandedKey()
        val busy = busyForExchange()
        if (down == null || down == up || busy) {
            MiniPlayerRuntime.noteTouch("exchange refused: out=${down?.takeLast(6)} busy=$busy")
            return false
        }
        val pill = player?.takeIf { it.visibility == View.VISIBLE } ?: return false
        val inPill = selectedIsland == up
        if (!inPill && smallKey != up) return false
        swapMotion.endSwap()
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        pill.clearNudge()
        val x = Switch()
        x.expanded = down
        x.seats = Seats(selectedIsland, smallKey)
        exchange = x
        requestUp(x, up, inPill, toCover && up == MUSIC_ISLAND)
        return true
    } finally { android.os.Trace.endSection() } }

    /**
     * [key] - in the big place if [fromPill], else the small one - asked out as its card. On its
     * way home still, its card is out yet and it turns round now. Else its card is let out, and
     * the row reseats and everything moves once that card is laid out (switchWait): meanwhile the
     * island stays drawn where it is and the rest goes on as it was.
     */
    private fun requestUp(x: Switch, key: String, fromPill: Boolean, cover: Boolean) {
        if (x.movers[key]?.morph != null) {
            if (!applyUp(x, key, fromPill, cover)) MiniPlayerRuntime.noteTouch("switch $key: no morph")
            return
        }
        // The one out resting in its card is held where it is drawn while the stack makes room
        // for the one coming out: the card the last switch pinned where the stack is settling
        // it, let go, jumped back up to wherever the stack had got to (2026-09-25).
        x.expanded?.takeIf { it !in x.movers }?.let { out ->
            nativeFor(out)?.let { native ->
                freezeCard(native, native.translationY + if (pinned?.get() === native) pinnedDy else 0f)
            }
            // Back into the row now, not once it has landed: the stack lays out the card coming
            // out without it, and that card's place is the stack's own.
            takeInEarly(out)
        }
        x.pending = key
        x.pendingPill = fromPill
        x.pendingCover = cover
        x.waitSince = android.os.SystemClock.uptimeMillis()
        if (cover) {
            // Into the cover the card is shown by the scene, not chosen: out from under the
            // pill's suppression for the morph, and out of sight until the morph takes it.
            updateNativeSuppression(false)
            transitionHeader()?.let(::hideRow)
        } else {
            letOut(key)
            if (key == STACK_ISLAND) showStackAsList("switch")
        }
        refresh()
        trace("switch ask ${key.takeLast(6)} pill=$fromPill out=${x.expanded?.takeLast(6)} " + smallState())
        MiniPlayerRuntime.noteTouch("switch ask ${key.takeLast(6)} pill=$fromPill out=${x.expanded?.takeLast(6)}")
        Gen.unframe(switchWait)
        Gen.frame(switchWait)
    }

    /** Frame by frame until the card asked for is laid out: it has only just been let out. */
    private val switchWait = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC exchangeWait"); try {
            val x = exchange ?: return
            val key = x.pending ?: return
            val native = traced("MC x.findUp") { nativeFor(key) }
            native?.let(::hideRow)
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) groupMotion.pileGroup(key, 0f, null)
            val out = x.expanded
            // A row given back early is a transient view: detached and re-attached as one, it has
            // lost its laid-out flag and is never laid out again, and the switch waited for it
            // until it gave up - the stack island's rows gone, nothing opened (2026-09-26).
            val outReady = out == null || out in x.movers || nativeFor(out)?.let {
                it.isAttachedToWindow && (it.isLaidOut || kept.values.any { k -> k.row === it }) && it.width > 0
            } == true
            if (native != null && traced("MC x.ready") { groupMotion.rowReady(native) } && outReady) {
                x.pending = null
                if (!traced("MC x.begin") { applyUp(x, key, x.pendingPill, x.pendingCover) }) abandonUp(x, key, "no morph")
                return
            }
            if (android.os.SystemClock.uptimeMillis() - x.waitSince > ROW_WAIT_MS) {
                abandonUp(x, key, when {
                    native == null -> "no card"
                    !outReady -> "card out not ready: " + (out?.let(::nativeFor)?.let { "attached=${it.isAttachedToWindow} " +
                        "laid=${it.isLaidOut} w=${it.width} kept=${kept.values.any { k -> k.row === it }}" } ?: "none")
                    else -> "card not on screen: " + offScreenWhy(native)
                })
                return
            }
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    private fun placeOf(key: String, seats: Seats): Int = when (key) {
        seats.big -> LAND_PILL
        seats.small -> LAND_SMALL
        else -> LAND_HIDDEN
    }

    /** A place's rest frame on screen now; hiding is the super island's cutout, mid-row. */
    private fun placeBoxNow(place: Int): CoverMorphMotion.Box? = when (place) {
        LAND_PILL -> player?.restBoxOnScreen()
        LAND_SMALL -> smallBoxOnScreen()
        else -> player?.restBoxOnScreen()?.let(swapMotion::cutoutBox)
    }

    /** [m]'s row end this frame, its end and shape springs stepped by the real gap. */
    private fun moverEnd(m: Mover): CoverMorphMotion.Box? {
        val now = System.nanoTime()
        if (m.endLast != 0L) {
            val dt = ((now - m.endLast) / 1e9f).coerceIn(0f, 0.05f)
            m.endT.step(dt)
            m.round.step(dt)
        }
        m.endLast = now
        val to = m.endTo ?: return m.endFrom
        val from = m.endFrom ?: return to
        return lerpBox(from, to, m.endT.value)
    }

    /**
     * [m]'s row end heads for [place]: from where it is drawn now, on CHANGE_EASE, unless
     * [snap] (a mover just made there). The shape follows on the same spring.
     */
    private fun aimEnd(m: Mover, place: Int, snap: Boolean = false, at: CoverMorphMotion.Box? = null) {
        val current = if (snap) null else moverEnd(m)
        m.place = place
        m.endTo = at ?: placeBoxNow(place)
        m.endFrom = current
        m.endT.value = if (current == null) 1f else 0f
        m.endT.velocity = 0f
        m.endT.target = 1f
        val round = if (place == LAND_HIDDEN || place == LAND_SMALL && !stackedStyle()) 1f else 0f
        if (snap) {
            m.round.value = round
            m.round.velocity = 0f
        }
        m.round.target = round
    }

    /**
     * The card's end of [m]'s morph, by the way it is headed now. Up: where the stack puts the
     * card, read live - the ones going home are out of its layout already (takeInEarly). Home:
     * held where it was drawn when it turned. Fixed by the way it was made, a card sent home and
     * turned back up flew to where it had left from, and was pulled up from there to the stack's
     * place for it once it landed (2026-09-26).
     */
    private fun moverDy(x: Switch, m: Mover): Float {
        val native = m.native
        // Asked while its morph is being made, it goes the way it was made.
        val dy = if (m.morph?.toNative ?: !m.fromCard) {
            m.homeAt = null
            settleDy(native, x.homeKeys()).also { m.settleAt = native.translationY + it }
        } else {
            val at = m.homeAt ?: (native.translationY + m.lastDy).also { m.homeAt = it }
            at - native.translationY
        }
        m.lastDy = dy
        return dy
    }

    /** [m] is not landing in its place after all: the place's island has its own frame back. */
    private fun unland(m: Mover) {
        if (!m.landed) return
        m.landed = false
        if (m.place == LAND_PILL) noteMorphMotion.endPillLanding()
        else if (landingBox != null) {
            landingBox = null
            smallIsland?.iconAtStart = false
            restoreSmallIslandShape()
        }
        m.view.alpha = 1f
    }

    private fun moverListener(m: Mover) = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = m.key == MUSIC_ISLAND && artBridged
        override fun onFrame(morph: MiniCardMorph, progress: Float) {
            if (m.key == STACK_ISLAND || LockIslands.isAppGroup(m.key))
                groupMotion.pileGroup(m.key, progress, morph.miniEndBox())
            moverFrame(m)
        }
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (m.key == STACK_ISLAND || LockIslands.isAppGroup(m.key)) groupMotion.endPile(toNative)
            moverSettled(m, toNative, completed)
        }
    }

    /**
     * [key] the one asked out now, the one out till now coming back in: the row reseated, and
     * every island on its way to its new end.
     */
    private fun applyUp(x: Switch, key: String, fromPill: Boolean, cover: Boolean): Boolean {
        // Nothing out: the one that was is on its way home already (adoptNote).
        val out = x.expanded
        val oldBig = selectedIsland
        val oldSmall = smallKey
        // The pill as drawn now: an island still growing into it is taken from where it has got to.
        val pillDrawn = swapMotion.swap?.takeIf { !it.holdPill }?.let { lerpBox(it.pillFrom, it.pillTo, it.spring.value) }
        // The small island asked for, still shrinking into its place as a stand-in of itself (a
        // collapse onto the pill sends the pill's island there so): it goes up from the stand-in,
        // which goes - left, it drew the island a second time under its own way up.
        val ghostDrawn = swapMotion.swap?.takeIf { !fromPill && it.smallMode == SMALL_FROM_GHOST && it.ghostKey == key &&
            it.ghostFrom != null }?.let(swapMotion::ghostSmallBox)
        if (ghostDrawn != null) swapMotion.endSwap()
        val seats = reseat(key, out, fromPill)
        // The one asked for: turned round on its way home, or up out of its place.
        val up = x.movers[key]
        if (up != null) {
            unland(up)
            turnTo(key, true)
            up.morph?.aim(true)
            if (up.cover) Main.miniPlayerTurnScene(true)
        } else {
            val native = nativeFor(key) ?: return false
            val view = prepareSmallView(key, pooled = true) ?: return false
            val m = Mover(key, view, native)
            m.origin = if (fromPill) LAND_PILL else LAND_SMALL
            m.cover = cover
            aimEnd(m, m.origin, snap = true, at = if (fromPill) pillDrawn ?: player?.restBoxOnScreen() else ghostDrawn)
            // Out of sight while it waited; the morph saves the card's own alpha.
            showRow(native)
            m.settleAt = native.translationY
            val morph = traced("MC x.upMorph") {
                MiniCardMorph(view, native, true, moverListener(m), noteMorphMotion.landingFor(key, native),
                    { moverEnd(m) }, !fromPill, nativeDy = { moverDy(x, m) }, roundness = { m.round.value })
            }
            m.morph = morph
            x.movers[key] = m
            if (!traced("MC x.upStart") { morph.start() }) {
                x.movers.remove(key)
                recycleSmallView(view)
                return false
            }
        }
        if (out != null && !sendDown(x, key, out, seats)) return true
        // Everyone else on the way home: to its new place, from where it is.
        for (m in x.movers.values) {
            if (m.key == key || m.key == out || !m.headedHome) continue
            val place = placeOf(m.key, seats)
            if (place != m.place) {
                unland(m)
                aimEnd(m, place)
            }
        }
        x.expanded = key
        x.seats = seats
        seats.big?.let { selectedIsland = it }
        snapSmallOnce = true
        refresh()
        if (key == STACK_ISLAND) showStackAsList("switched out")
        traced("MC x.seat") { seatAtRest(x, oldBig, oldSmall, pillDrawn) }
        // What comes down into the pill comes down as the pill will be.
        for (m in x.movers.values) if (m.headedHome && m.place == LAND_PILL) fitToPill(m.view, seats.small != null)
        traced("MC x.after") {
            orderMovers(x)
            // The music is drawn by its view from here, not by the place it left.
            applyArtBridge()
            // A place whose island is on its way to it goes out of sight under it this frame.
            updateVisibility()
            followShortcuts()
        }
        val plan = "seats=${seats.big?.takeLast(6)}/${seats.small?.takeLast(6)} " +
            x.movers.values.joinToString(" ") { moverState(it) }
        trace("switch up=${key.takeLast(6)} down=${out?.takeLast(6)} pill=$fromPill $plan " + smallState())
        MiniPlayerRuntime.noteTouch("switch up=${key.takeLast(6)} down=${out?.takeLast(6)} pill=$fromPill $plan")
        return true
    }

    /**
     * [out], the one out till now, on its way to its new place: turned round on its way up, or
     * down out of its card. False when it has no card to come down from: the switch is given up.
     */
    private fun sendDown(x: Switch, key: String, out: String, seats: Seats): Boolean {
        // The one out: turned round on its way up, or down out of its card, to its new place.
        val outPlace = placeOf(out, seats)
        // Into hiding, with no place in the row: the small island pulses (ExpandedToHidden).
        if (outPlace == LAND_HIDDEN) startSmallPulse(seats.small)
        val down = x.movers[out]
        if (down != null) {
            down.homeAt = null
            down.morph?.aim(false)
            turnTo(out, false)
            if (down.cover) Main.miniPlayerTurnScene(false)
            aimEnd(down, outPlace)
        } else {
            val native = nativeFor(out)
            val view = native?.let { prepareSmallView(out, pooled = true) }
            if (native == null || view == null) {
                view?.let(::recycleSmallView)
                abandonMover(x, key)
                return false
            }
            val m = Mover(out, view, native)
            m.fromCard = true
            m.cover = out == MUSIC_ISLAND && Main.coverModeOn()
            // Leaves from where it is drawn and stays there: the row going up into the stack moves
            // it on the stack's side (the stack is laid out from the bottom up), and the pin may
            // still be holding it lower than the stack has it.
            val frozen = pinnedAt?.takeIf { pinned?.get() === native }
                ?: (native.translationY + if (pinned?.get() === native) pinnedDy else 0f)
            if (pinned?.get() === native) unpinCard(keep = true)
            m.cardAt = frozen
            m.homeAt = frozen
            aimEnd(m, outPlace, snap = true)
            val morph = traced("MC x.downMorph") {
                MiniCardMorph(view, native, false, moverListener(m), noteMorphMotion.landingFor(out, native),
                    { moverEnd(m) }, outPlace != LAND_PILL, nativeDy = { moverDy(x, m) }, roundness = { m.round.value })
            }
            m.morph = morph
            x.movers[out] = m
            if (!traced("MC x.downStart") { morph.start() }) {
                x.movers.remove(out)
                recycleSmallView(view)
                abandonMover(x, key)
                return false
            }
            // Made anew on its way home - its last morph had already ended, as a turn a moment
            // after a landing does: back into the row now too, or its rows stayed in the stack
            // and the card coming out was laid out above them (2026-09-26).
            takeInEarly(out)
            // Out of the cover: the music coming down is the cover's card, and the cover goes with it.
            if (m.cover) Main.miniPlayerLeaveCover()
        }
        return true
    }

    fun moverState(m: Mover): String = "${m.key.takeLast(6)}:" +
        (if (m.morph?.toNative == true) "card" else "p${m.place}") + (if (m.landed) "L" else "") +
        "@${"%.2f".format(m.morph?.progress ?: -1f)}"

    /**
     * The islands at rest in the row take their new places as the movers go (seatOthers of the
     * first version): the small island grows into the big place (SmallIslandToBigIsland); the big
     * one shrinks into the small place as itself (BigIslandToSmallIsland, SMALL_FROM_GHOST), or
     * into hiding with the next one out of it (BigIslandToHidden, HiddenToSmallIsland). A place a
     * mover is on its way to is its: out of sight, and the switch leaves it alone. An island still
     * growing into the pill goes on from where it has got to.
     */
    private fun seatAtRest(x: Switch, oldBig: String?, oldSmall: String?, pillDrawn: CoverMorphMotion.Box?) {
        val seats = x.seats ?: return
        val movers = x.movers
        val holdPill = seats.big != null && seats.big in movers
        val holdSmall = seats.small != null && seats.small in movers
        val bigRest = oldBig?.takeIf { it !in movers }
        val smallRest = oldSmall?.takeIf { it !in movers }
        val bigMoves = !holdPill && seats.big != bigRest
        val intoSmall = bigRest != null && seats.small == bigRest
        val bigHides = bigRest != null && bigRest != seats.big && bigRest != seats.small
        val smallChanges = !holdSmall && seats.small != smallRest
        val stillGrowing = !holdPill && pillDrawn != null
        if (!bigMoves && !intoSmall && !bigHides && !smallChanges && !stillGrowing) return
        swapMotion.startSwap(oldBig, if (intoSmall) null else oldSmall, kind = if (bigHides) SWAP_NEXT else SWAP_PAIR,
            intoSmall = intoSmall, holdPill = holdPill, holdSmall = holdSmall,
            pillFrom = if (!bigMoves && !holdPill) pillDrawn else null, pillStays = !bigMoves && !holdPill)
    }

    /**
     * Each in its destination's layer, as the super island stacks its islands' backgrounds - big
     * 3, expanded 2, small 1, hidden 0: one on its way to the big place over everything; one
     * going up to its card over the small island but under an island in the pill; one on its way
     * to the small place or into hiding under both. The pill and the small island are at 0, the
     * stand-ins added under them.
     */
    private fun orderMovers(x: Switch) {
        val pillShown = !exchangeHoldsPill()
        for (m in x.movers.values) {
            val z = when {
                !m.headedHome -> if (pillShown) 0f else 1f
                m.place == LAND_PILL -> 2f
                else -> -1f
            }
            if (m.view.translationZ != z) m.view.translationZ = z
        }
    }

    /** Every frame of [m]: on its last stretch home, its place's island takes over from it. */
    private fun moverFrame(m: Mover) {
        val x = exchange ?: return
        if (x.movers[m.key] !== m) return
        val morph = m.morph ?: return
        // 1 at its card, 0 in its place.
        val p = morph.progress
        val home = !morph.toNative
        // Into hiding, nothing takes over from it: it fades out on the cutout.
        val hiding = home && m.place == LAND_HIDDEN
        val holder = when (m.place) {
            LAND_PILL -> x.seats?.big
            LAND_SMALL -> x.seats?.small
            else -> null
        }
        // Only once its end has got to the place: the place's island is at rest there.
        val landing = home && !hiding && p < FLIGHT_HANDOFF && holder == m.key && m.endT.atRest()
        if (landing != m.landed) {
            if (landing) {
                m.landed = true
                snapSmallOnce = true
            } else unland(m)
            refresh()
            orderMovers(x)
            followShortcuts()
        }
        if (m.landed) morph.containerBox()?.let { if (m.place == LAND_PILL) noteMorphMotion.landOnPill(it) else noteMorphMotion.landOn(it) }
        val a = if (m.landed || hiding) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, p)
            else if (m.place == LAND_SMALL && stackedStyle()) lerp(MiniPlayerGeometry.STACK_BACK_ALPHA, 1f, p)
            else 1f
        if (kotlin.math.abs(m.view.alpha - a) > 0.002f) m.view.alpha = a
        traced("MC xTrace") {
            // Only as it changes: a tenth of the way, a turn, the stack's target moving. Every
            // frame of two movers filled the whole trace with one switch (2026-09-26).
            val b = morph.containerBox()
            val mark = "${m.key}${morph.toNative}${(p * 10).toInt()}|" + lastStackTarget.toInt() / 3
            if (mark != m.traced) {
                m.traced = mark
                trace("x ${moverState(m)} box=${b?.cx()?.toInt()},${b?.y?.toInt()} " +
                    "${b?.w?.toInt()}x${b?.h?.toInt()} end=${"%.2f".format(m.endT.value)} " +
                    "at=${m.cardAt.toInt()} dty=${m.native.translationY.toInt()} " + lastSettle)
            }
        }
    }

    /** [m] is at an end: out as its card, or home in the row. */
    private fun moverSettled(m: Mover, toNative: Boolean, completed: Boolean) {
        val x = exchange ?: return
        if (x.movers[m.key] !== m) return
        flushTurn(m.key)
        x.movers.remove(m.key)
        unland(m)
        recycleSmallView(m.view)
        val key = m.key
        if (toNative) {
            // Pulled down again, it comes back where it went from.
            when (m.origin) {
                LAND_PILL -> { noteMorphMotion.releasedFromPill += key; noteMorphMotion.releasedFromSmall -= key }
                LAND_SMALL -> { noteMorphMotion.releasedFromSmall += key; noteMorphMotion.releasedFromPill -= key }
            }
            // Held where the stack is settling it until the stack has taken the others away.
            // Turned round, the card that came back up lands where it left, the rows that went
            // up on their way back out of the stack under it: held the same (2026-09-25).
            // Where the stack has it: the rows going home are out of its layout already.
            pinCard(m.native, x.homeKeys())
        } else {
            noteMorphMotion.releasedFromPill -= key
            noteMorphMotion.releasedFromSmall -= key
            if (key != MUSIC_ISLAND) {
                rowFor(key)?.let { hideRowUntilGone(it, key) }
                markReturning(key)
            } else transitionHeader()?.let(::showRow)
            takeIn(key)
            letGo(key)
        }
        trace("switch settled ${key.takeLast(6)} ${if (toNative) "card" else "home"} completed=$completed " +
            "left=${x.movers.size}" + if (completed) "" else " why=" + m.morph?.lastCancel)
        snapSmallOnce = true
        refresh()
        orderMovers(x)
        if (x.movers.isEmpty() && x.pending == null) endSwitch(x)
    }

    /** Everyone is at an end: the row is the seats'. */
    private fun endSwitch(x: Switch) {
        if (exchange !== x) return
        exchange = null
        noteMorphMotion.endPillLanding()
        x.seats?.let { s ->
            s.big?.let { selectedIsland = it }
            preferredSmall = s.small
        }
        snapSmallOnce = true
        refresh()
        trace("exchange end " + smallState())
    }

    /**
     * A tap on the row while a switch runs, the super island's way: it goes to whoever holds the
     * place tapped - landed or still on its way there - and that island goes out as its card,
     * the one out (still going up, or resting in its card) coming back in, all from where they
     * are. Only a card still being laid out for the last tap keeps the next one waiting.
     */
    fun tapDuringExchange(small: Boolean) {
        val x = exchange ?: return
        if (x.pending != null) {
            MiniPlayerRuntime.noteTouch("exchange tap: still waiting")
            return
        }
        val seats = x.seats ?: return
        val key = (if (small) seats.small else seats.big) ?: return
        if (key == x.expanded) return
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            if (!spreadMotion.openSingleApp("tap during a switch")) spreadMotion.openSpread("tap during a switch")
            return
        }
        trace("switch tap ${key.takeLast(6)} small=$small")
        val wasOut = x.movers[key]?.morph != null
        requestUp(x, key, fromPill = !small, cover = key == MUSIC_ISLAND)
        // The music opens the cover from any place, as from the pill (tapIsland).
        if (key == MUSIC_ISLAND && !wasOut) {
            MiniPlayerRuntime.forgetRestoreScene()
            Main.miniPlayerEnterCover()
        }
    }

    /** What a tap on an island does: the music opens the cover, a notification its row. */
    private fun tapIsland(key: String) {
        if (key == MUSIC_ISLAND) openCover() else noteMorphMotion.expandNote(key)
    }

    /**
     * The music into its cover. With another island out as its card, that one comes down into
     * the music's place as the music goes up - one card at a time; it stood there beside the
     * cover's card before (2026-09-25).
     */
    fun openCover() {
        MiniPlayerRuntime.forgetRestoreScene()
        // A notification still on its way - opening, or springing back out after a pull too
        // short to collapse it - is not "out" yet to expandedKey, and the cover came up over it
        // with the timer's card left standing in the stack under the media card (filmed
        // 2026-09-26). Taken into a switch as it is, it turns home while the music goes up, as
        // the super island does it: the next version waited for it to land and only then opened
        // the cover, one after the other.
        val moving = noteMorphMotion.noteMorphKey?.takeIf { it != MUSIC_ISLAND }
        when {
            moving != null && morph != null -> {
                val m = morph!!
                trace("cover takes ${moving.takeLast(6)} in: p=${"%.2f".format(m.progress)} toRow=${m.toNative} " +
                    "out=$flightOut drag=${noteDragMotion.noteDrag != null} " + smallState())
                val inPill = selectedIsland == MUSIC_ISLAND
                val x = if (inPill || smallKey == MUSIC_ISLAND) adoptNote(moving) else null
                if (x == null) {
                    MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} moving, not taken in")
                    return
                }
                requestUp(x, MUSIC_ISLAND, inPill, cover = true)
                MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} taken in, music up")
            }
            moving != null && noteMorphMotion.rowWaitKey == moving -> {
                noteDragMotion.noteDrag = null
                noteMorphMotion.abandonNoteMorph(moving)
                MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} not opened")
            }
            expandedKey()?.let { it != MUSIC_ISLAND } == true -> startExchange(MUSIC_ISLAND, toCover = true)
        }
        Main.miniPlayerEnterCover()
    }

    /**
     * No card to go up into: the one asked for stays in the row, the one out stays out, and
     * whatever was already moving goes on.
     */
    private fun abandonUp(x: Switch, key: String, why: String) {
        if (exchange !== x) return
        x.pending = null
        Xp.log("MCIsland: switch $key not run: $why")
        MiniPlayerRuntime.noteTouch("exchange $why")
        abandonMover(x, key)
    }

    private fun abandonMover(x: Switch, key: String) {
        flushTurn(key)
        x.expanded?.let(::flushTurn)
        // It was given back for the other's sake: out again as it was.
        x.expanded?.takeIf { it !in x.movers }?.let(::letOutAgain)
        // The card that was to come down stays where it was held, while the row that was let out
        // for the other goes back out of the stack under it.
        x.expanded?.takeIf { it !in x.movers }?.let { out ->
            val held = pinned?.get()
            val at = pinnedAt
            if (held != null && at != null && held === nativeFor(out)) pinCard(held, listOf(key), from = at)
            else unpinCard()
        }
        x.movers.remove(key)?.let { m ->
            m.morph?.cancel()
            recycleSmallView(m.view)
        }
        if (key != MUSIC_ISLAND) rowFor(key)?.let { hideRowUntilGone(it, key) }
        else transitionHeader()?.let(::showRow)
        takeIn(key)
        if (key != MUSIC_ISLAND) markReturning(key)
        refresh()
        if (x.movers.isEmpty() && x.pending == null) {
            exchange = null
            refresh()
        }
    }

    /**
     * Busy for a running morph taken over as a notification's flight: the motions that keep it from
     * starting (M0, the subset as it was).
     */
    private fun busyForAdoptNote(): Boolean = exchange != null || noteMorphMotion.rowWaitKey != null

    /**
     * The notification opening or folding back as the flight, made a [Mover] of a new switch as
     * it is: its morph handed over (MiniCardMorph.handTo) at its progress and speed, the flight
     * its stand-in, its row end where the flight was headed. Out on its way up, it is the switch's
     * island out - the next tap turns it home. On its way home, nothing is out. Null with no
     * flight to take.
     */
    private fun adoptNote(key: String): Switch? {
        val running = morph ?: return null
        val view = noteMorphMotion.flight ?: return null
        if (busyForAdoptNote()) return null
        val native = running.card
        flushTurn(key)
        // Let go by no finger yet: from here the switch moves it, on its spring.
        if (running.held) running.release(running.toNative, 0f)
        noteDragMotion.noteDrag = null
        // Pulled and not let go, it was not out yet: its home is given up now, as a tap's is.
        if (running.toNative) noteMorphMotion.setFlightOut(true) else takeInEarly(key)
        val home = if (noteMorphMotion.flightHome == HOME_PILL) LAND_PILL else LAND_SMALL
        val end = if (home == LAND_PILL) player?.restBoxOnScreen() else smallBoxOnScreen()
        // The flight's landing - the pill or the small island in its shape - is the switch's to
        // make again (moverFrame), once the seats have it.
        noteMorphMotion.endPillLanding()
        if (landingBox != null) {
            landingBox = null
            smallIsland?.iconAtStart = false
            restoreSmallIslandShape()
        }
        val x = Switch()
        val m = Mover(key, view, native)
        m.origin = home
        m.fromCard = !running.toNative
        m.lastDy = if (running.toNative) settleDy(native, emptyList()) else 0f
        m.settleAt = native.translationY + m.lastDy
        aimEnd(m, home, snap = true, at = end)
        m.morph = running
        running.handTo(moverListener(m), { moverEnd(m) }, { moverDy(x, m) }, { m.round.value })
        x.movers[key] = m
        x.expanded = key.takeIf { running.toNative }
        x.seats = Seats(selectedIsland, smallKey)
        if (!running.toNative && home == LAND_PILL) fitToPill(view, smallKey != null)
        // Nothing is the flight's any more.
        morph = null
        noteMorphMotion.noteMorphKey = null
        noteMorphMotion.flight = null
        noteMorphMotion.flightFromSmall = false
        noteMorphMotion.flightHome = HOME_SMALL
        flightOut = false
        flightLanding = false
        exchange = x
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        player?.clearNudge()
        trace("adopt ${key.takeLast(6)} ${moverState(m)} seats=${x.seats?.big?.takeLast(6)}/" +
            "${x.seats?.small?.takeLast(6)} " + smallState())
        refresh()
        orderMovers(x)
        updateVisibility()
        followShortcuts()
        return x
    }

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
    private var stackRef: WeakReference<View>? = null
    internal val noteDragMotion = NoteDragMotion(this)
    fun beginNoteDrag(key: String, fromSmall: Boolean, startY: Float): Boolean =
        noteDragMotion.beginNoteDrag(key, fromSmall, startY)
    fun noteDragMove(y: Float, nudgeX: Float = 0f) = noteDragMotion.noteDragMove(y, nudgeX)
    fun noteDragEnd(velocityY: Float, cancelled: Boolean) = noteDragMotion.noteDragEnd(velocityY, cancelled)

    /** A released notification's row under a point on screen, by key. */
    fun releasedRowAt(x: Float, y: Float): String? {
        val xy = IntArray(2)
        for (key in LockIslands.releasedKeys()) {
            // The stack island's rows are all its: the one under the finger leads it home.
            for (member in LockIslands.membersOf(key)) {
                val row = findRow(member)?.first ?: continue
                if (!row.isShown || row.width <= 0) continue
                row.getLocationOnScreen(xy)
                if (x >= xy[0] && x < xy[0] + row.width && y >= xy[1] && y < xy[1] + row.height) {
                    if (key == STACK_ISLAND && noteMorphMotion.noteMorphKey != STACK_ISLAND) stackLeadKey = member
                    else if (LockIslands.isAppGroup(key) && noteMorphMotion.noteMorphKey != key)
                        appLeadKeys[key] = member
                    return key
                }
            }
        }
        return null
    }

    /** The list's own scroll: NSSL keeps it in mOwnScrollY, not in View.scrollY. Null unread. */
    private fun nativeScrollY(): Int? {
        val stack = notificationStack() ?: return null
        return (runCatching { Xp.callMethod(stack, "getOwnScrollY") }.getOrNull() as? Number)?.toInt()
            ?: (runCatching { Xp.getObjectField(stack, "mOwnScrollY") }.getOrNull() as? Number)?.toInt()
    }

    /** Where the lock screen rests the list, as the gesture's DOWN read it. */
    private var restScrollY: Float? = 0f
    private var listTopStrategy: Any? = null
    /** For the touch log: the scroll and rest last compared. */
    var lastScrollRead = "unread"
        private set

    /**
     * The list at its top ([MiniPlayerCollapseGesture.atListTop]). Its rest is 0 except with the
     * stack island opened out as the list, where the lock screen scrolls it down under the clock.
     *
     * Folded natively, the stack island is never "released" (isReleased), so the open list was
     * read against a rest of 0: resting at 220 it counted as scrolled away, the pull down was not
     * the islands', and the stack's own fold ran home in four frames - the card gone in one and
     * the island popping up past its place (#122). Spread out, the rest is where the spread
     * opened it to: the same scroll its progress is measured on.
     */
    fun nativeStackAtTop(readRest: Boolean): Boolean {
        val spreadRest = spreadMotion.spread?.takeIf { it.phase == SPREAD_OPEN || it.phase == SPREAD_CLOSING }
            ?.cardsY?.toFloat()
        if (readRest) restScrollY = spreadRest
            ?: if (LockIslands.isReleased(STACK_ISLAND)) listRestScroll() else 0f
        val scrollY = nativeScrollY()
        lastScrollRead = "own=$scrollY/rest=$restScrollY"
        return MiniPlayerCollapseGesture.atListTop(scrollY, restScrollY)
    }

    /**
     * The LIST's resting scroll, as the lock screen works it out for a tap on its pile
     * (KeyguardNotificationClickToListStateStrategy): the list's origin less the target it
     * places the list's top at. Null when any of it cannot be read.
     */
    private fun listRestScroll(): Float? = runCatching {
        val model = containerModel() ?: return@runCatching null
        val sample = Xp.callMethod(Xp.callMethod(model, "getOnUpdateChildSampleStackInfo"), "getValue")
        val event = Xp.findClass("com.miui.systemui.notification.view.strategy.KeyguardStackStateChangeEvent", loader)
            .getField("CLICK_SHOW_LIST").get(null)
        val info = Xp.callMethod(model, "getKeyguardStackStatusInfo", sample, event)
        val focusHeight = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val origin = (Xp.callMethod(model, "positionWithoutMinScrollRange", focusHeight) as Number).toFloat()
        val strategy = listTopStrategy ?: Xp.findClass(
            "com.miui.systemui.notification.view.strategy.KeyguardNotificationClickToListStateStrategy", loader)
            .getDeclaredConstructor().newInstance().also { listTopStrategy = it }
        val top = (Xp.callMethod(strategy, "calculateTargetYPosition", info) as Number).toFloat()
        (origin - top).takeIf { it.isFinite() }?.coerceAtLeast(0f)
    }.onFailure { MiniPlayerRuntime.noteTouch("list rest unread: $it") }.getOrNull()

    /** The lock screen's notification stack, found once in the window. */
    fun notificationStack(): ViewGroup? {
        (stackRef?.get() as? ViewGroup)?.takeIf { it.isAttachedToWindow }?.let { return it }
        val queue = ArrayDeque<View>()
        queue.add(host)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.endsWith("NotificationStackScrollLayout") && v is ViewGroup) {
                stackRef = WeakReference(v)
                return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** A notification's row in the stack, by its key. */
    fun rowFor(key: String): View? = findRow(key)?.first

    /** [rowFor], for MiniPlayerRuntime.rowOf. */
    fun rowOf(key: String): View? = rowFor(key)

    /** [notificationStack], for MiniPlayerRuntime.stackForProbe. */
    fun stackForProbe(): ViewGroup? = notificationStack()


    fun dumpTree(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 22) return
        sb.append(" ".repeat(depth)).append(v.javaClass.simpleName)
        val id = runCatching { if (v.id > 0) v.resources.getResourceEntryName(v.id) else null }.getOrNull()
        if (id != null) sb.append(" #").append(id)
        sb.append(when (v.visibility) { View.VISIBLE -> ""; View.INVISIBLE -> " INV"; else -> " GONE" })
        if (v.alpha < 1f) sb.append(" a=").append("%.2f".format(v.alpha))
        if (v.transitionAlpha < 1f) sb.append(" ta=").append("%.2f".format(v.transitionAlpha))
        sb.append(' ').append(v.width).append('x').append(v.height)
            .append('@').append(v.left).append(',').append(v.top)
        when (v) {
            is android.widget.Chronometer -> sb.append(" CHRONO base=").append(v.base)
                .append(" down=").append(v.isCountDown).append(" text='").append(v.text).append("'")
            is android.widget.TextView -> sb.append(" '").append(v.text?.toString()?.take(60))
                .append("' sz=").append("%.0f".format(v.textSize)).append(" col=#")
                .append(Integer.toHexString(v.currentTextColor))
            is android.widget.ImageView -> v.drawable.let { d ->
                sb.append(" img=").append(d?.javaClass?.simpleName)
                    .append(' ').append(d?.intrinsicWidth).append('x').append(d?.intrinsicHeight)
            }
            is android.widget.ProgressBar -> sb.append(" progress=").append(v.progress).append('/').append(v.max)
            else -> {}
        }
        if (v.contentDescription != null) sb.append(" cd='").append(v.contentDescription.toString().take(40)).append("'")
        sb.appendLine()
        if (v is ViewGroup) for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, sb)
    }

    /**
     * [key]'s row, and the stack's own row it is in: itself, or the group it is a child of. The
     * system groups an app's notifications by itself (高德's navigation came as two children of
     * an Aggregate_SilentSection summary), and looked for among the stack's rows alone a child
     * was never found: the media card's morph left its row standing, unfolded (2026-09-25).
     */
    internal fun findRow(key: String): Pair<View, View>? {
        if (key == STACK_ISLAND || LockIslands.isAppGroup(key))
            return findRow(groupLead(key) ?: return null)
        // Given back early and drawn on as a transient view: no longer among the stack's rows.
        kept[key]?.row?.takeIf { it.isAttachedToWindow }?.let { return it to it }
        val stack = notificationStack() ?: return null
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            if (rowKey(child) == key) return child to child
            // One app, one row, as the stack groups them: a notification in a group is found as
            // the group's row, never its own child row. A child is not even drawn with the group
            // folded (the children's container INVISIBLE): three QQ messages grouped, the stack
            // island waited for a child that never came on screen and the tap did nothing, and
            // opened, the rows of one app were piled one by one (2026-09-26, the user's rule).
            for (sub in childRows(child)) if (rowKey(sub) == key) return child to child
        }
        return null
    }

    fun rowKey(row: View): String? = runCatching {
        (Xp.getObjectField(Xp.callMethod(row, "getEntry"), "mSbn") as? android.service.notification.StatusBarNotification)?.key
    }.getOrNull()

    /** A group's rows for its children; none for a row that is not a group. */
    fun childRows(row: View): List<View> = runCatching {
        (Xp.callMethod(row, "getAttachedChildren") as? List<*>)?.filterIsInstance<View>()
    }.getOrNull().orEmpty()

    /**
     * What the stack shows of [key] as a whole: its group's row - the group goes with its last
     * child - unless that group also holds [keep], the row an island is morphing with, which
     * would go with it: then the child's row alone.
     */
    internal fun wholeRowFor(key: String, keep: View?): View? {
        val (row, top) = findRow(key) ?: return null
        if (row === top) return row
        return if (keep != null && isInside(keep, top)) row else top
    }

    internal fun isInside(v: View, ancestor: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }

    /** The first shown view under [root] with one of [names] as its id, breadth first. */
    internal fun findNamed(root: View, names: Set<String>, kind: (View) -> Boolean): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.id != View.NO_ID && v.isShown && kind(v)) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in names) return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** Rows this controller has made invisible (transitionAlpha 0), and what they had. */
    internal val hiddenRows = java.util.WeakHashMap<View, Float>()

    fun hideRow(row: View) {
        if (row in hiddenRows) return
        trace("hide ${shortName(row)}")
        hiddenRows[row] = row.transitionAlpha
        row.transitionAlpha = 0f
    }

    fun showRow(row: View) {
        val was = hiddenRows.remove(row) ?: return
        trace("show ${shortName(row)} ta=$was")
        row.transitionAlpha = was
    }

    /**
     * A row going back into the row of islands: invisible for as long as the stack takes to
     * animate it away, its alpha given back after that. Not on detach: the stack re-adds a
     * removed row as a transient view for exactly that animation, so the detach comes first
     * and would show it again for it. Given back whatever happens, a row can never be left
     * invisible in a stack that shows it later - the cover's, the unlocked shade's.
     */
    fun hideRowUntilGone(row: View, key: String) {
        hideRow(row)
        handler.postDelayed({
            // Released again meanwhile and waiting to be morphed: that morph gives it back.
            // Held for the media card's morph meanwhile: that morph's end gives it back.
            if (noteMorphMotion.rowWaitKey != key && !(noteMorphMotion.noteMorphKey == key && morph != null) && !groupMotion.rowsHeld()) showRow(row)
        }, ROW_GONE_MS)
    }

    /** A row's alpha as drawn: its own and every ancestor's up to the window. */
    private fun drawnAlpha(row: View): Float {
        var a = 1f
        var v: View? = row
        while (v != null) {
            a *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        return a
    }

    /**
     * Whether a row is there to be landed on: shown, drawn at least half there, and inside the
     * screen above the row of islands. Not the stack's folded state: it read true with the row
     * plainly drawn (alpha 1, 2026-09-25), and the tap went back into its island - it is only
     * logged now.
     */
    /** Every ancestor of [v] not VISIBLE, with its id, up to the window: what isShown tripped on. */
    private fun hiddenAncestors(v: View): String {
        val out = ArrayList<String>()
        var p: View? = v.parent as? View
        while (p != null) {
            if (p.visibility != View.VISIBLE) {
                val id = runCatching { if (p.id > 0) p.resources.getResourceEntryName(p.id) else null }.getOrNull()
                out += p.javaClass.simpleName + (id?.let { "#$it" } ?: "") + "=" + p.visibility
            }
            p = p.parent as? View
        }
        return if (out.isEmpty()) "none(attached=${v.isAttachedToWindow} win=${v.windowVisibility})" else out.joinToString(">")
    }

    /** Why rowOnScreen said no, for the touch log. */
    internal fun offScreenWhy(row: View): String {
        val xy = IntArray(2).also(row::getLocationOnScreen)
        val parent = row.parent as? View
        return "${shortName(row)} v=${row.visibility} shown=${row.isShown} a=${"%.2f".format(row.alpha)} " +
            "ta=${"%.2f".format(row.transitionAlpha)} parentA=${parent?.let { "%.2f".format(drawnAlpha(it)) }} " +
            "y=${xy[1]} h=${row.height} laid=${row.isLaidOut} bottom=${player?.restBoxOnScreen()?.y?.toInt()} " +
            "folded=${stackFolded()} lead=${groupLead(STACK_ISLAND)?.let(groupMotion::shortKey)} hiddenBy=" + hiddenAncestors(row) +
            (findRow(groupLead(STACK_ISLAND) ?: "")?.second?.takeIf { it !== row }?.let { top ->
                " group: v=${top.visibility} a=${"%.2f".format(top.alpha)} ta=${"%.2f".format(top.transitionAlpha)} " +
                    "h=${top.height} y=${IntArray(2).also(top::getLocationOnScreen)[1]} " +
                    "expanded=${runCatching { Xp.callMethod(top, "isGroupExpanded") }.getOrNull()}"
            } ?: "")
    }

    internal fun rowOnScreen(row: View): Boolean {
        // The row's own alpha is the stack's fade-in (and our hiding): the morph owns it from
        // here. Its ancestors' are whether the stack shows it at all.
        val parent = row.parent as? View ?: return false
        if (!row.isShown || drawnAlpha(parent) < 0.5f) return false
        val xy = IntArray(2).also(row::getLocationOnScreen)
        val bottom = player?.restBoxOnScreen()?.y ?: host.height.toFloat()
        return xy[1] + row.height > 0 && xy[1] < bottom
    }

    /**
     * The stack folded into its "N notifications" count (KeyguardNotificationState.NUMBER),
     * read as CardTouch.readFoldState reads it but from the stack itself: the media card it starts
     * from is not there with a row of islands showing. Null when it cannot be read.
     */
    private fun stackFolded(): Boolean? = runCatching {
        val stack = notificationStack() ?: return null
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        val model = Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
        Xp.callMethod(Xp.callMethod(model, "isInNumState"), "getValue") == true
    }.getOrNull()

    /** The stack's container model (NotificationContainerViewModel), as stackFolded reads it. */
    private fun containerModel(): Any? = runCatching {
        val stack = notificationStack() ?: return null
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
    }.getOrNull()

    /** The keyguard stack's state - NUMBER, STACK or LIST (KeyguardNotificationState); null unread. */
    private fun stackState(model: Any? = containerModel()): String? = runCatching {
        (Xp.callMethod(Xp.callMethod(model ?: return null, "getCurrentsStackState"), "getValue") as Enum<*>).name
    }.getOrNull()

    /**
     * The stack island's rows laid out as the lock screen's own list, one row an app: the stack
     * asked to do what a tap on its pile does (NotificationRowViewBinder's click on a stacked row:
     * NotificationContainerViewModel._onClicked, read 2026-09-26). The keyguard then moves its
     * notifications' top up to the list's, the clock pressed down to its smallest for them
     * (KeyguardNotificationClickToListStateStrategy), and every row is laid out whole instead of
     * piled at the bottom. The rows' ends are read from where the stack draws them (pileStack,
     * settleDy), so they go to the list as it opens. Asked again for a while if the stack is not
     * a pile yet to be tapped - the rows only just given back - and never once it is a list.
     */
    fun showStackAsList(why: String) {
        listAskedAt = android.os.SystemClock.uptimeMillis()
        listWhy = why
        listClicked = false
        handler.removeCallbacks(listAsk)
        listAsk.run()
    }

    /** A card on its way home from the stack that the stack still lays out; null when none. */
    private fun leavingCards(): String? {
        val x = exchange ?: return null
        for (m in x.movers.values) {
            if (!m.headedHome || m.key == STACK_ISLAND || m.key == MUSIC_ISLAND) continue
            // Told to the stack now, if its turn home was waiting: the list waits for it.
            flushTurn(m.key)
            val parent = m.native.parent as? ViewGroup ?: continue
            if (parent.indexOfChild(m.native) >= 0) return shortName(m.native)
        }
        return null
    }

    private var listAskedAt = 0L
    private var listWhy = ""

    /**
     * The stack has been sent its tap for this ask: once, never again. Sent every step while the
     * stack stayed a pile - one notification, which fits whole and has no list to open into - the
     * lock screen took each for a tap of the user's, and buzzed for every one (2026-09-26).
     */
    private var listClicked = false

    /** The stack's own answer to whether a tap on its pile opens the list; null unread. */
    private fun stackWantsList(model: Any): Boolean? = runCatching {
        Xp.callMethod(Xp.callMethod(model, "getShouldNotifClickToList"), "getValue") as? Boolean
    }.getOrNull()

    private val listAsk = object : Runnable {
        override fun run() {
            // Only while the stack island is out, or on its way.
            val out = noteMorphMotion.noteMorphKey == STACK_ISLAND && morph?.toNative != false ||
                exchange?.let { x -> x.expanded == STACK_ISLAND || x.pending == STACK_ISLAND } == true ||
                noteMorphMotion.noteMorphKey == null && exchange == null && expandedKey() == STACK_ISLAND
            if (!out) return
            val model = containerModel() ?: return
            val state = stackState(model)
            val waited = android.os.SystemClock.uptimeMillis() - listAskedAt
            // A card on its way home still laid out in the stack: the list is worked out once,
            // from the rows there as it is asked for (ClickToListStateStrategy), and a stopwatch
            // still among them had the list stop half way up - its card gone, the notifications'
            // top never worked out again (2026-09-26). Asked once that card is out of the layout.
            val leaving = leavingCards()
            when {
                state == "LIST" -> {
                    trace("list is up ($listWhy) after ${waited}ms")
                    return
                }
                leaving != null -> if (waited % 100L < LIST_ASK_STEP_MS) trace("list waits for $leaving")
                listClicked -> {}
                state == "STACK" && stackWantsList(model) == false -> {
                    // One notification, whole where it is: the stack keeps it so.
                    trace("list not wanted ($listWhy)")
                    return
                }
                state == "STACK" -> runCatching {
                    listClicked = true
                    // SystemUI's own Unit, not ours: the module's kotlin is another class loader's,
                    // and whatever collects the click may cast what it is sent. The name put
                    // together here: as a constant in Class.forName, R8 rewrote it to the module's
                    // own obfuscated Unit ("hc2", ClassNotFoundException - 2026-09-26).
                    val unit = Class.forName(listOf("kotlin", "Unit").joinToString("."), false,
                        model.javaClass.classLoader).getField("INSTANCE").get(null)
                    Xp.callMethod(Xp.getObjectField(model, "_onClicked"), "tryEmit", unit)
                    trace("list asked ($listWhy) at ${waited}ms")
                }.onFailure { trace("list ask failed: $it") }
            }
            if (waited < LIST_ASK_MS) handler.postDelayed(this, LIST_ASK_STEP_MS)
            else trace("list not up ($listWhy): state=$state")
        }
    }

    internal val shortcutDiscs = ShortcutDiscs(this)
    fun press(side: Int, down: Boolean) = shortcutDiscs.press(side, down)
    fun discAt(x: Float, y: Float): Int? = shortcutDiscs.discAt(x, y)
    fun letGoOnDrag(dx: Float, dy: Float): Boolean = shortcutDiscs.letGoOnDrag(dx, dy)
    fun shortcutGeometry(): FloatArray? = shortcutDiscs.shortcutGeometry()

    /** keyguard_bottom_area above the torch, and keyguard_root_view above that. */
    internal fun findRow(): View? {
        var row: View? = null
        var v: View? = left
        while (v != null && v !== host) {
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (name == "keyguard_bottom_area" && row == null) row = v
            if (name == "keyguard_root_view") followRoot = WeakReference(v)
            v = v.parent as? View
        }
        return row ?: left.parent as? View
    }

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
            groupMotion.pileSettle, spreadMotion.spreadFrame, switchWait, noteMorphMotion.rowWait).forEach { Gen.unframe(it) }
        // The waits for a row would otherwise go on to open it, on a controller already gone;
        // and a card still pinned is the stack's again, drawn where the stack has it.
        noteMorphMotion.rowWaitKey = null
        unpinCard()
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
    private fun turnSwitchOutOfCover(x: Switch, music: Mover): Boolean {
        val big = x.seats?.big?.takeIf { it != MUSIC_ISLAND } ?: return false
        music.cover = false
        requestUp(x, big, fromPill = true, cover = false)
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
        if (scene) exchange?.takeIf { it.has(MUSIC_ISLAND) }?.let { x ->
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
                    if (exchange != null) return false
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
        if (MUSIC_ISLAND in islandKeys && exchange == null && noteMorphMotion.noteMorphKey != MUSIC_ISLAND)
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
        ?: exchange?.movers?.get(MUSIC_ISLAND)?.morph?.artworkBox()
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
    private fun applyArtBridge() {
        val bridged = artBridged
        // The music's artwork: the small island's view's while the music is the small island.
        val small = groupMotion.group?.takeIf { it.musicSmall }?.smallView
        small?.setArtworkHidden(bridged)
        // The music as a flight: the flight's artwork is the one the cover's stands in for.
        val flying = noteMorphMotion.flight?.takeIf { noteMorphMotion.noteMorphKey == MUSIC_ISLAND }
            ?: exchange?.movers?.get(MUSIC_ISLAND)?.view
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
        if (musicFlies() || exchange != null) return false
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
        exchange != null -> "exchange"
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
    private fun busyForTakerSwap(): Boolean = swapMotion.swap != null || morph != null || exchange != null || noteMorphMotion.flight != null ||
        groupMotion.group != null || noteMorphMotion.rowWaitKey != null || islandDragging

    private fun refreshUnsafe() { android.os.Trace.beginSection("MC refresh"); try {
        lottiesFollowDoze()
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
            thumbShown = null
            lastTrack = ""
        }
        // The row is out as the stack's list: its views are the spread's till it folds back,
        // and it is put together again then (endSpread).
        if (spreadMotion.spread != null) return
        val musicMoving = groupMotion.group != null || groupMotion.musicComingDown || morph != null && noteMorphMotion.noteMorphKey == null ||
            noteMorphMotion.noteMorphKey == MUSIC_ISLAND || exchange?.has(MUSIC_ISLAND) == true
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
        val others = notes.isNotEmpty() || returning.isNotEmpty() || exchange != null ||
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
        exchange?.let { x -> for (key in x.keys()) islandKeys = keptInPlace(islandKeys, key) }
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
        if (music != null) trackMusic(music)
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
            if (showsMusic) bindMusic(view, music!!, config)
            else if (note != null) bindNote(view, note, config)
        }
        pillShowsMusic = showsMusic
        // The music's own seat with no session for a moment (a stop between two tracks) keeps
        // what it showed, as it always has.
        pillUnbound = selected != MUSIC_ISLAND && note == null
        // After the small island is chosen: whether the music is it decides its picture's bridge.
        updateSmallIsland(music, notes)
        traced("MC r.artBridge") { applyArtBridge() }
        applyInk()
        updateVisibility()
        schedulePosition()
        prewarmSpares()
    } finally { android.os.Trace.endSection() } }

    /**
     * The words' colour, as SystemUI colours its own media card's (IslandMaterial.ink, after
     * MiuiMediaViewControllerImpl.updateForegroundColors): white on the glass and in the full
     * AOD, the light or dark frost's own otherwise. [title] the card title's, else the action
     * buttons'. Opaque: the pill's own alphas say how strong each line is. The same state the
     * islands' material is chosen by, so the two cannot disagree (the card title, a row title
     * and a fallback of our own used to, #87 and #91).
     */
    private fun ink(title: Boolean = true): Int {
        val ctx = Main.sAppCtx ?: context
        val color = IslandMaterial.ink(ctx)[if (title) 0 else 1]
        // The AOD darkens the plate under the light frost, and the frost's near black, kept on
        // it, could not be read on the lock screen (2026-10-24). Toward white as deep as the
        // plate has gone, so the words turn with it rather than at once.
        val dim = MiniPlayerRuntime.aodDimFraction
        val base = color or 0xFF000000.toInt()
        return if (dim <= 0f) base
        else androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.WHITE, dim)
    }

    /** What applyInk last tinted the buttons with; null while they are the OEM's own. */
    private var buttonInk: Int? = null

    // For `op mini` (ink=): how often the ink, the buttons' tint and their layer changed.
    // Counts since the controller was made.
    private var lastInk = 0
    private var inkChanges = 0
    private var buttonInkChanges = 0
    private var buttonLayerWrites = 0

    private fun describeInkCounts(): String =
        "inkChanges=$inkChanges buttonInk=${buttonInk?.let(Integer::toHexString)} " +
            "buttonChanges=$buttonInkChanges layerWrites=$buttonLayerWrites " +
            "buttons=${left.javaClass.simpleName}/${right.javaClass.simpleName}"

    fun applyInk() {
        val color = ink()
        if (color != lastInk) {
            lastInk = color
            inkChanges++
        }
        player?.setInk(color)
        smallIsland?.setInk(color)
        swapMotion.ghostPill?.setInk(color)
        swapMotion.ghostDisc?.setInk(color)
        noteMorphMotion.flight?.setInk(color)
        for (v in spareViews) v.setInk(color)
        // The torch and the camera are white glyphs; on discs of the light frost they vanished.
        // Tinted as the card's own buttons are, only while the discs are under them, and given
        // back as they were after.
        val want = ink(title = false).takeIf {
            shortcutDiscs.discsWanted && shortcutDiscs.discs.any { d -> d?.visibility == View.VISIBLE } && it != android.graphics.Color.WHITE
        }
        if (want == buttonInk) return
        buttonInk = want
        buttonInkChanges++
        for (side in 0..1) tintButton(shortcutDiscs.button(side), want)
    }

    private fun tintButton(v: View, color: Int?) {
        val filter = color?.let { android.graphics.PorterDuffColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
        if (v is ImageView) { v.colorFilter = filter; return }
        buttonLayerWrites++
        if (filter == null) v.setLayerType(View.LAYER_TYPE_NONE, null)
        else v.setLayerType(View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply { colorFilter = filter })
    }

    /** The session and track the music island is showing; a new one starts its artwork afresh. */
    private fun trackMusic(current: MediaController) {
        if (MiniPlayerRuntime.observeSession(current.sessionToken, current.packageName)) {
            cachedCover = null
            // Another player: its artwork, not the last one's.
            thumbShown = null
            lastTrack = ""
        }
        val metadata = metadataOf(current)
        // The song, not the title: Salt, 汽水, QQ and NetEase sing into TITLE, and every line
        // threw the artwork away as a track change (#47, #56).
        val track = TrackIdentity.of(current.packageName, metadata).songKey().ifEmpty {
            current.packageName + "|" + metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        }
        if (track != lastTrack) {
            if (lastTrack.isNotEmpty()) {
                Xp.log("MCMini: track changed; preserving dynamic choice=" +
                    if (MiniPlayerRuntime.nativeRequested(current.sessionToken)) "native" else "mini")
            }
            lastTrack = track
            cachedCover = null
        }
    }

    /** The music island's artwork, as far as it has been fetched and scaled. */
    private fun musicCover(metadata: MediaMetadata?): Bitmap? {
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: cachedCover?.takeUnless { it.isRecycled }
            ?: (if (!nativeSuppressionRequested) AlbumArt.cardThumbnail() else null)
        if (cover != null && !cover.isRecycled) cachedCover = cover
        return cover
    }

    fun bindMusic(view: MiniPlayerView, current: MediaController, config: JSONObject) {
        view.setArtworkBare(false)
        view.setToggleFace(null)
        view.setSecondFace(null)
        view.skippable = true
        showTimer(view, null)
        showFocusAnim(view, null, null)
        val metadata = metadataOf(current)
        val shown = traced("MC b.thumb") { thumbnailFor(musicCover(metadata), view) }
        view.setToggleShown(true)
        // A player singing into TITLE: the song and singer it spells into ARTIST instead, when
        // they can be read out (TrackIdentity). A title that is still the line being sung is cut,
        // never scrolled (MiniPlayerView.titleScrolls).
        val identity = TrackIdentity.of(current.packageName, metadata)
        val sung = identity.titleIsLine()
        val readOut = sung && identity.songIsRead()
        view.titleScrolls = !sung || readOut
        val title = if (readOut) identity.song() else metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = if (readOut) identity.singer() else metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        view.bind(
            title.orEmpty().ifBlank { "正在播放" },
            artist.orEmpty()
                .ifBlank { metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() },
            shown,
            stateOf(current)?.state == PlaybackState.STATE_PLAYING,
            config,
            materialKey(),
            { target -> MiniPlayerRuntime.material(target, loader, notificationMaterial()) },
            ::togglePlayback,
            { skip(next = false) },
            { skip(next = true) },
            {
                // Up undoes down: out of the cover or lyrics by a swipe, back into it by one.
                if (MiniPlayerRuntime.takeRestoreScene()) Main.miniPlayerEnterCover()
                else MiniPlayerRuntime.selectNative(current.sessionToken)
            },
            { openCover() },
        )
    }

    /**
     * A notification in the pill: its picture, title and newest line, no play button. A tap
     * puts it back in the stack as the OEM's own row for now; the morph into that row is to
     * come.
     */
    fun bindNote(view: MiniPlayerView, note: LockIslands.Note, config: JSONObject) {
        // A focus notification shown in full is its template: its picture bare, as its row has
        // it, and its main button where the music's play button is.
        val template = note.focus && !note.redacted
        // Every notification's picture at the small island's size, cut to its circle or whole
        // as LockIslands.roundIcon left it (2026-09-26): a plain one was the cover's rounded
        // square, bigger, on a grey plate.
        view.setArtworkBare(true)
        view.skippable = false
        view.titleScrolls = true
        // Its buttons as its row has them: the last - its main one - in the play button's place,
        // the one before it beside it while the pill is the big island (setSecondShown).
        val buttons = if (template) note.buttons else emptyList()
        val primary = buttons.lastOrNull()
        val second = buttons.getOrNull(buttons.size - 2)
        view.setToggleShown(primary != null)
        view.setToggleFace(primary?.let { buttonFace(it, note.timer) })
        view.setSecondFace(second?.let { buttonFace(it, note.timer) })
        // The template's own text, spans and all: a focus protocol writes colour into it and the
        // row shows it coloured, so flattening it here showed the pill the markup instead
        // (the user, 2026-09-28).
        view.bind(
            note.timer?.displayText() ?: note.title.takeUnless { it.isBlank() } ?: appLabel(note.pkg),
            note.text,
            noteBitmap(note),
            false,
            config,
            materialKey(),
            { target -> MiniPlayerRuntime.material(target, loader, notificationMaterial()) },
            {},
            {},
            {},
            {},
            { noteMorphMotion.expandNote(note.key) },
        )
        showTimer(view, note.timer)
        showFocusAnim(view, note.anim, note.live)
    }

    /**
     * Each pill's own player of a focus template's Lottie, and which picture it plays. The row's
     * Lottie is the plugin's own copy of the library, obfuscated, its composition out of reach:
     * the same file is loaded again with SystemUI's copy - the plugin names it
     * (LottieResUtils.getLottieRes) and its resources hold it - into a LottieAnimationView of
     * SystemUI's that is never attached, whose drawable the pill's artwork shows and runs.
     */
    val focusLotties = java.util.WeakHashMap<Any, Pair<String, android.widget.ImageView>>()

    private fun showFocusAnim(view: MiniPlayerView, anim: LockIslands.Anim?, live: LockIslands.Live?) {
        val d = focusLottie(view, anim)
        if (d == null) view.clearArtworkDrawable() else view.showArtworkDrawable(d)
        view.showArtworkView(liveArt(view, live))
    }

    /**
     * Each place's own live picture ([LockIslands.Live]) and which one it is: a view has one
     * parent, so the pill, the small island and a flight each make their own.
     */
    val liveArts = java.util.WeakHashMap<Any, Pair<String, View>>()
    private val liveFailed = HashSet<String>()

    /**
     * Live pictures no place shows any more, by id, for the next place that asks for the same.
     * Each one made is the plugin's own view, and the torch's inflates three bitmaps on the main
     * thread: made afresh for every place that took the torch - a switch's stand-ins, a flight,
     * a pill that had been the music in between - it was 7-18ms of the first frame of every
     * switch and every pull (perfetto 2026-09-29, `decodeBitmap` under FlashLightView inflate).
     */
    private val liveSpares = HashMap<String, ArrayDeque<View>>()

    private fun spareLive(held: Pair<String, View>) {
        // Not a clip: it is set playing once, when it is made, and put back up after its surface
        // went it is not known to play again.
        if (held.first.startsWith(LockIslands.Live.VIDEO + "#")) return
        val spares = liveSpares.getOrPut(held.first) { ArrayDeque() }
        if (spares.none { it === held.second } && spares.size < LIVE_SPARES) spares.addLast(held.second)
    }

    /** A spare of [id] that has left its last place: one still in it would be taken from under it. */
    private fun takeSpareLive(id: String): View? {
        val spares = liveSpares[id] ?: return null
        val it = spares.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (v.parent == null) {
                it.remove()
                return v
            }
        }
        return null
    }

    /** [owner]'s view of [live], made on first asking; null for none, or one that would not make. */
    fun liveArt(owner: Any, live: LockIslands.Live?): View? {
        val held = liveArts[owner]
        if (live == null) {
            liveArts.remove(owner)?.let(::spareLive)
            return null
        }
        if (held != null) {
            if (held.first == live.id) return held.second
            liveArts.remove(owner)
            spareLive(held)
        }
        if (live.id in liveFailed) return null
        takeSpareLive(live.id)?.let { spare ->
            liveArts[owner] = live.id to spare
            return spare
        }
        val view = runCatching { makeLiveArt(live) }.onFailure {
            // Thrown: this build has no way to it, not worth trying on every refresh.
            liveFailed += live.id
            lottieNote("live ${live.id} failed: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}", null)
        }.getOrNull() ?: return null
        liveArts[owner] = live.id to view
        lottieNote("live ${live.id} made", null)
        return view
    }

    /**
     * The plugin's own view for [live], set up as the row sets its own
     * (ModuleNewImageTextViewHolder.showEffects, IslandIconViewHolder.setMP4Icon), in a host that
     * draws it at the size the row's layout gives it.
     */
    private fun makeLiveArt(live: LockIslands.Live): View? {
        val plugin = LockIslands.pluginOf(live.row.get()) ?: return null
        val ctx = plugin.ctx
        fun dimen(name: String): Int {
            val id = ctx.resources.getIdentifier(name, "dimen", "miui.systemui.plugin")
            return if (id == 0) 0 else ctx.resources.getDimensionPixelSize(id)
        }
        return when (live.kind) {
            LockIslands.Live.FLASH -> {
                val v = plugin.loader.loadClass("com.mi.widget.view.FlashLightView")
                    .getConstructor(android.content.Context::class.java).newInstance(ctx) as View
                // Its row's layout lets its glow - taller than its square - out (clipChildren
                // false); made in code, a FrameLayout clips it.
                (v as? ViewGroup)?.clipChildren = false
                (Xp.callMethod(v, "getFlashLightOffset") as? FloatArray)?.let { o ->
                    o[0] = 0f; o[1] = 0.14f; o[2] = -50f; o[3] = 0f
                }
                LiveArtHost(context, v, dimen("focus_notify_profile_container_size"))
            }
            LockIslands.Live.VIDEO -> {
                val v = plugin.loader.loadClass("miui.systemui.widget.TextureVideoView")
                    .getConstructor(android.content.Context::class.java).newInstance(ctx) as View
                val uri = plugin.call("miui.systemui.util.VideoResUtils", "getVideoRes", live.src, ctx) as String
                Xp.callMethod(v, "setOnPreparedListener", android.media.MediaPlayer.OnPreparedListener { mp ->
                    // Silent on the lock screen whatever the clip carries.
                    runCatching { mp.setVolume(0f, 0f) }
                    mp.isLooping = live.loop
                    Xp.callMethod(v, "start")
                })
                Xp.callMethod(v, "setOnErrorListener", android.media.MediaPlayer.OnErrorListener { _, what, extra ->
                    lottieNote("live ${live.id} error $what/$extra", null)
                    runCatching { Xp.callMethod(v, "stopPlayback") }
                    true
                })
                Xp.callMethod(v, "setVideoURI", android.net.Uri.parse(uri))
                LiveArtHost(context, v, 0)
            }
            else -> null
        }
    }

    /**
     * [owner]'s player of [anim] - a pill, or the small island - its drawable to show, playing
     * or held as the template says; null, and [owner]'s player stopped, for none.
     */
    fun focusLottie(owner: Any, anim: LockIslands.Anim?): android.graphics.drawable.Drawable? {
        if (anim == null) {
            focusLotties.remove(owner)?.let(::spareLottie)
            return null
        }
        val id = "${anim.src}#${anim.number}"
        var held = focusLotties[owner]
        if (held?.first != id) {
            held?.let { focusLotties.remove(owner); spareLottie(it) }
            // Another place's, let go: as liveArt's spares, not a player made for every place.
            val lottie = lottieSpares[id]?.removeFirstOrNull() ?: loadFocusLottie(anim)
                ?: return focusLottie(owner, null)
            held = id to lottie
            focusLotties[owner] = held
        }
        val lottie = held.second
        lottieAutoplay[lottie] = anim.autoplay
        runCatching {
            Xp.callMethod(lottie, "setRepeatCount", anim.repeat)
            Xp.callMethod(lottie, "setRepeatMode", 1)
            if (anim.autoplay && !MiniPlayerScene.aodActive) {
                // Held unseen: its place coming back plays it (lottiesFollowSight), not a refresh.
                if (lottie !in lottiesUnseen && Xp.callMethod(lottie, "isAnimating") != true) {
                    lottieNote("asked to resume vis=${lottie.drawable?.isVisible}", lottie)
                    Xp.callMethod(lottie, "resumeAnimation")
                }
            } else Xp.callMethod(lottie, "pauseAnimation")
        }
        return lottie.drawable?.let { d -> lottieWatches.getOrPut(d) { LottieWatch(d, lottie) } }
    }

    /** Which players their template asks to play, for the wake to play them again. */
    private val lottieAutoplay = java.util.WeakHashMap<android.widget.ImageView, Boolean>()

    /**
     * The doze holds every place's Lottie on its frame; the wake plays again those that play.
     * The window stays visible through the AOD, so nothing pauses them on its own, and one that
     * loops redraws the whole NotificationShade every frame: the recorder's voiceWave kept the
     * AOD at ~63fps, RenderThread 57% and main 37% of a core (2026-09-30).
     */
    private fun lottiesFollowDoze() {
        val doze = MiniPlayerScene.aodActive
        for (held in focusLotties.values) runCatching {
            val lottie = held.second
            if (doze) Xp.callMethod(lottie, "pauseAnimation")
            else if (lottieAutoplay[lottie] == true && lottie !in lottiesUnseen &&
                Xp.callMethod(lottie, "isAnimating") != true)
                Xp.callMethod(lottie, "resumeAnimation")
        }
    }

    /** Players held because their place could not be seen, for the place coming back to play. */
    private val lottiesUnseen = java.util.WeakHashMap<android.widget.ImageView, Boolean>()

    /**
     * A lit lock screen holds every place's Lottie that nobody can see; the place coming back
     * plays it again. A Lottie drawable pauses itself when its ImageView goes GONE or its window
     * hides (setVisible), but not when a place is faded out - alpha or transitionAlpha 0 on it
     * or an ancestor, which is how most places here are put away - and one that loops redraws
     * the whole NotificationShade every frame, seen or not: a looping one (the clock's island)
     * kept a still lock screen at ~95fps, main and RenderThread 68% of a core (2026-10-01). Asked
     * before every frame: an unseen player's own tick is what schedules the frame that holds it,
     * and the frame a place comes back in is the one that plays it again.
     */
    private fun lottiesFollowSight() {
        if (focusLotties.isEmpty() || MiniPlayerScene.aodActive) return
        for ((owner, held) in focusLotties) runCatching {
            val lottie = held.second
            if (lottieAutoplay[lottie] != true) return@runCatching
            val seen = (owner as? View)?.let(::onScreen) ?: true
            val playing = Xp.callMethod(lottie, "isAnimating") == true
            if (playing && !seen) {
                Xp.callMethod(lottie, "pauseAnimation")
                lottiesUnseen[lottie] = true
                lottieNote("unseen, held", lottie)
            } else if (seen && lottiesUnseen.remove(lottie) != null && !playing) {
                Xp.callMethod(lottie, "resumeAnimation")
                lottieNote("seen, played", lottie)
            }
        }
    }

    /** Drawn and not faded out: attached to a showing window, shown, and no alpha 0 up the chain. */
    private fun onScreen(v: View): Boolean {
        if (!v.isAttachedToWindow || v.windowVisibility != View.VISIBLE || !v.isShown) return false
        var p: View? = v
        while (p != null) {
            if (p.alpha <= 0f || p.transitionAlpha <= 0f) return false
            p = p.parent as? View
        }
        return true
    }

    /** Players no place shows any more, by picture, for the next place that plays the same. */
    private val lottieSpares = HashMap<String, ArrayDeque<android.widget.ImageView>>()

    private fun spareLottie(held: Pair<String, android.widget.ImageView>) {
        runCatching { Xp.callMethod(held.second, "pauseAnimation") }
        lottiesUnseen.remove(held.second)
        val spares = lottieSpares.getOrPut(held.first) { ArrayDeque() }
        if (spares.none { it === held.second } && spares.size < LIVE_SPARES) spares.addLast(held.second)
    }

    /**
     * Compositions by the plugin's raw resource, shared by every pill that plays one; the
     * pictures that would not load, not tried again on every refresh (a failure was retried
     * twenty times in a minute, 2026-09-25).
     */
    private val focusCompositions = HashMap<Int, Any>()
    val focusLottieFailed = HashSet<String>()

    private fun loadFocusLottie(anim: LockIslands.Anim): android.widget.ImageView? {
        if (anim.src in focusLottieFailed) return null
        return runCatching { makeFocusLottie(anim) }.onFailure {
            // Thrown: this build has no way to it, not worth trying on every refresh.
            focusLottieFailed += anim.src
            focusLottieWhy = "failed ${anim.src}: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}"
            lottieNote(focusLottieWhy, null)
        }.getOrNull()
    }

    /** Why the last try gave nothing, said once until it changes. */
    var focusLottieWhy = ""

    private fun lottieMiss(why: String): android.widget.ImageView? {
        if (why != focusLottieWhy) {
            focusLottieWhy = why
            lottieNote("none: $why", null)
        }
        return null
    }

    /**
     * The plugin as a package of its own, code and resources: its raw Lottie files are its
     * resources, not SystemUI's, and a view of its in the row may carry SystemUI's.
     */
    private val pluginContext: android.content.Context? by lazy {
        runCatching {
            context.createPackageContext("miui.systemui.plugin",
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { MiniPlayerRuntime.noteTouch("focus lottie: no plugin package: $it") }.getOrNull()
    }

    /** The last load's own exception, when it gave no composition. */
    private var lottieLoadError: Any? = null

    /**
     * The plugin's raw Lottie [res] as SystemUI's copy of the library reads it: its
     * fromRawResSync(Context, String cacheKey, int), arguments reordered by R8 and matched by
     * type; its LottieResult's getters gone, its public fields read. One per file, shared.
     */
    private fun compositionFor(res: Int, plugin: android.content.Context): Any? {
        focusCompositions[res]?.let { return it }
        val systemui = host.javaClass.classLoader ?: return null
        val factory = systemui.loadClass("com.airbnb.lottie.LottieCompositionFactory")
        val load = factory.methods.first { m ->
            m.name == "fromRawResSync" && m.parameterTypes.any { it == Int::class.javaPrimitiveType } &&
                m.parameterTypes.any { it == android.content.Context::class.java }
        }
        val args = load.parameterTypes.map { t ->
            when (t) {
                android.content.Context::class.java -> plugin
                Int::class.javaPrimitiveType -> res
                String::class.java -> "musiccover_focus_$res"
                else -> null
            }
        }.toTypedArray()
        val result = load.invoke(null, *args)
        lottieLoadError = runCatching { Xp.getObjectField(result, "exception") }.getOrNull()
        return Xp.getObjectField(result, "value")?.also { focusCompositions[res] = it }
    }

    /** A LottieAnimationView of SystemUI's, never attached, holding [composition]. */
    private fun newLottie(composition: Any, plugin: android.content.Context, systemui: ClassLoader): android.widget.ImageView {
        val view = systemui.loadClass("com.airbnb.lottie.LottieAnimationView")
            .getConstructor(android.content.Context::class.java).newInstance(plugin) as android.widget.ImageView
        Xp.callMethod(view, "setComposition", composition)
        return view
    }

    private fun lottieView(res: Int, plugin: android.content.Context): android.widget.ImageView? {
        val composition = compositionFor(res, plugin) ?: return null
        return newLottie(composition, plugin, host.javaClass.classLoader ?: return null)
    }

    private fun makeFocusLottie(anim: LockIslands.Anim): android.widget.ImageView? {
        // The plugin as the row has it - its view's context and classes, which loaded the file
        // (2026-09-25) - else the plugin's package; its package context alone never did.
        val source = anim.row.get()?.let { LockIslands.focusIconView(it) }
        val plugin = source?.context ?: pluginContext ?: return lottieMiss("no plugin context")
        val loader = source?.javaClass?.classLoader ?: plugin.classLoader
        val utils = loader.loadClass("miui.systemui.util.LottieResUtils")
        val instance = runCatching { utils.getField("INSTANCE").get(null) }.getOrNull()
        val res = utils.methods.first { it.name == "getLottieRes" && it.parameterTypes.size == 2 }
            .invoke(instance, anim.src, anim.number) as Int
        if (res <= 0) return lottieMiss("no res for ${anim.src}")
        val systemui = host.javaClass.classLoader ?: return lottieMiss("no systemui loader")
        val composition = compositionFor(res, plugin)
            ?: return lottieMiss("load ${anim.src}: $lottieLoadError")
        val view = newLottie(composition, plugin, systemui)
        runCatching { utils.getField("IMAGE_ASSETS_FOLDER").get(null) as? String }.getOrNull()
            ?.let { Xp.callMethod(view, "setImageAssetsFolder", it) }
        runCatching {
            Xp.callMethod(view, "addAnimatorListener", object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(a: android.animation.Animator) = lottieNote("start", view)
                override fun onAnimationEnd(a: android.animation.Animator) = lottieNote("end", view)
                override fun onAnimationCancel(a: android.animation.Animator) = lottieNote("cancel", view)
                override fun onAnimationPause(a: android.animation.Animator) = lottieNote("pause", view)
                override fun onAnimationResume(a: android.animation.Animator) = lottieNote("resume", view)
            })
        }
        focusLottieWhy = ""
        lottieNote("loaded ${anim.src} res=$res", view)
        return view
    }

    private val noteBitmaps = HashMap<String, Triple<Long, android.graphics.drawable.Drawable?, Bitmap?>>()

    /** For `op mini`: what the focus Lotties did, and who hid them - kept apart from the touches. */
    val lottieLog = ArrayDeque<String>()

    private fun lottieNote(what: String, lottie: android.widget.ImageView?) {
        val p = lottie?.let { runCatching { Xp.callMethod(it, "getProgress") }.getOrNull() }
        synchronized(lottieLog) {
            lottieLog.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what p=$p")
            while (lottieLog.size > 24) lottieLog.removeFirst()
        }
    }

    /**
     * The Lottie drawable as the pill's artwork shows it: passed straight through, but its
     * visibility changes - the only thing that pauses a Lottie drawable on its own - are noted.
     */
    inner class LottieWatch(inner: android.graphics.drawable.Drawable, val lottie: android.widget.ImageView) :
        android.graphics.drawable.DrawableWrapper(inner) {
        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            if (visible != isVisible) lottieNote("visible=$visible", lottie)
            return super.setVisible(visible, restart)
        }
    }

    val lottieWatches = java.util.WeakHashMap<android.graphics.drawable.Drawable, LottieWatch>()

    /**
     * A focus button as the pill draws it, by the plugin's own rule (ModuleButtonViewHolder
     * .setActionNormalData / setActionTextData), the same for every app: its picture the
     * plugin's own for its name when it has one (ACTIONS_RESOURCE_MAP), or its Lottie held at
     * its first frame (LOTTIE_RES_MAP_NEXT, updateAndPauseAnimation), else the app's picture;
     * its plate the plugin's focus_button_background_n in its colour, a pressed one under it.
     */
    private fun buttonFace(b: LockIslands.Button, timer: LockIslands.Timer?) = ActionFace(
        key = "${b.type}|${b.iconName}|${b.bg}|${b.press}|${b.label}|${b.titleColor}|" +
            "${b.progress?.let { "${it.value}/${it.auto}/${it.ccw}/${it.color}/${it.colorEnd}" }}",
        icon = {
            when (b.type) {
                // setActionTextData: its title, in its title colour, on its plate.
                2 -> TextFaceDrawable(b.label?.toString().orEmpty(), b.titleColor ?: android.graphics.Color.WHITE,
                    13f * context.resources.displayMetrics.scaledDensity)
                // setActionProgressData: its picture inside its ring.
                1 -> progressFace(b, timer)
                else -> buttonIcon(b)
            }
        },
        // A plate only under words: the plugin's pictures carry their own round shell, and
        // only setActionTextData gives its button a background - one under a picture drew a
        // second shell round it (2026-09-25).
        background = { if (b.type == 2) buttonPlate(b) else null },
        label = b.label,
        onClick = { pressButton(b) },
        wide = b.type == 2,
    )

    /**
     * A progress button: its picture inset in its ring - the ring at its value, or at its
     * timer's share of the timer's total for an auto one (NotificationTimeKeeper.getProgresses),
     * redrawn on each tick of the timer.
     */
    private fun progressFace(b: LockIslands.Button, timer: LockIslands.Timer?): android.graphics.drawable.Drawable {
        val spec = b.progress
        val auto = spec?.auto == true && timer != null && timer.totalMs > 0L
        val ring = ProgressRingDrawable(
            stroke = dp(2.5f).toFloat(),
            color = spec?.color ?: android.graphics.Color.WHITE,
            colorEnd = spec?.colorEnd,
            ccw = spec?.ccw == true,
            progress = if (auto) ({ timer.progress() })
                else ({ (spec?.value ?: 0).toFloat() }),
        )
        if (auto) autoRings[ring] = true
        val icon = buttonIcon(b)
        val layers = listOfNotNull(icon?.let { android.graphics.drawable.InsetDrawable(it, 0.24f) }, ring)
        return android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }

    /** The plugin's context and classes as the row has them, else its package's. */
    private fun pluginOf(row: View?): Pair<android.content.Context, ClassLoader>? {
        LockIslands.pluginOf(row)?.let { return it.ctx to it.loader }
        val ctx = pluginContext ?: return null
        return ctx to ctx.classLoader
    }

    /** The plugin's name-to-picture maps for its buttons: its stills and its Lotties. */
    private var buttonMapsCache: Pair<Map<*, *>, Map<*, *>>? = null

    private fun buttonMaps(loader: ClassLoader): Pair<Map<*, *>, Map<*, *>>? = buttonMapsCache ?: runCatching {
        val holder = loader.loadClass("miui.systemui.notification.focus.moduleV3.ModuleButtonViewHolder")
        fun map(name: String) = holder.getDeclaredField(name).apply { isAccessible = true }.get(null) as Map<*, *>
        (map("ACTIONS_RESOURCE_MAP") to map("LOTTIE_RES_MAP_NEXT")).also { buttonMapsCache = it }
    }.onFailure { lottieNote("button maps: $it", null) }.getOrNull()

    private fun buttonIcon(b: LockIslands.Button): android.graphics.drawable.Drawable? {
        val name = b.iconName
        val plugin = pluginOf(b.row.get())
        if (name != null && plugin != null) {
            val maps = buttonMaps(plugin.second)
            // The Lottie first, as the plugin does (shouldAnimLottie, in setActionNormalData and
            // setActionProgressData alike): a name in both maps is drawn as its Lottie's first
            // frame. The still first drew the countdown's action_restart_timer as
            // restart_timer_v3 where the row has pause_island_blue (2026-09-30).
            (maps?.second?.get(name) as? Int)?.takeIf { it > 0 }?.let { res ->
                runCatching { lottieView(res, plugin.first) }.getOrNull()?.let { lottie ->
                    runCatching { Xp.callMethod(lottie, "setProgress", 0f) }
                    lottie.drawable?.let { return it }
                }
            }
            (maps?.first?.get(name) as? Int)?.takeIf { it != 0 }?.let { res ->
                runCatching { plugin.first.getDrawable(res) }.getOrNull()?.let { return it }
            }
        }
        return runCatching { (b.picture ?: b.action?.getIcon())?.loadDrawable(context) }.getOrNull()
    }

    private fun buttonPlate(b: LockIslands.Button): android.graphics.drawable.Drawable {
        val ctx = pluginOf(b.row.get())?.first
        fun plate(name: String, fallback: Int): android.graphics.drawable.Drawable {
            val id = ctx?.resources?.getIdentifier(name, "drawable", "miui.systemui.plugin") ?: 0
            return (if (id != 0) runCatching { ctx!!.getDrawable(id)?.mutate() }.getOrNull() else null)
                ?: android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(fallback)
                }
        }
        val normal = plate("focus_button_background_n", 0x33FFFFFF)
        val pressed = plate("focus_button_background_p", 0x55FFFFFF)
        b.bg?.let { (normal as? android.graphics.drawable.GradientDrawable)?.setColor(it) }
        // Pressed: its own colour, else its plate's a little lighter, as the dark template's.
        (b.press ?: b.bg?.let { androidx.core.graphics.ColorUtils.blendARGB(it, android.graphics.Color.WHITE, 0.15f) })
            ?.let { (pressed as? android.graphics.drawable.GradientDrawable)?.setColor(it) }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(IntArray(0), normal)
        }
    }

    /**
     * A tap: the row's own button is pressed - the plugin then does what it does for every app,
     * the keyguard asked to go for an activity, the state flipped, the notification collapsed
     * when asked. With no row to press, its intent is sent as the plugin would build it.
     */
    private fun pressButton(b: LockIslands.Button) {
        MiniPlayerRuntime.noteTouch("focus button ${b.index} '${b.label}' ${b.iconName}")
        val target = b.row.get()?.let { rowButton(it, b.index, b.type) }
        if (target != null && target.hasOnClickListeners()) {
            target.performClick()
            return
        }
        runCatching {
            val intent = b.action?.actionIntent ?: b.intentUri?.let { uri ->
                val i = android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
                val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                when (b.intentType) {
                    2 -> android.app.PendingIntent.getBroadcast(context, b.postTime.toInt(), i, flags)
                    3 -> android.app.PendingIntent.getService(context, b.postTime.toInt(), i, flags)
                    else -> android.app.PendingIntent.getActivity(context, b.postTime.toInt(), i, flags)
                }
            }
            intent?.send()
        }.onFailure { MiniPlayerRuntime.noteTouch("focus button failed: $it") }
    }

    /**
     * The row's own button: focus_button_icon1, 2, 3 for a picture, focus_button_progress1, 2, 3
     * for one in a ring, focus_button_container_action for the text one (ModuleButtonViewHolder).
     */
    private fun rowButton(row: View, index: Int, type: Int): View? {
        val names = when (type) {
            1 -> setOf("focus_button_progress${index + 1}")
            2 -> setOf("focus_button_container_action")
            else -> setOf("focus_button_icon${index + 1}")
        }
        val queue = ArrayDeque<View>()
        queue.add(row)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.id != View.NO_ID && v.visibility == View.VISIBLE) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in names) return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /**
     * Islands whose title is a focus template's timer - a stopwatch, a countdown - and the timer
     * each shows: the row's chronometer ticks, so the island's line does too, on the second as
     * the chronometer turns it, in tabular figures as HyperChronometer draws them ("tnum").
     */
    private val timerViews = java.util.WeakHashMap<MiniPlayerView, LockIslands.Timer>()

    private fun showTimer(view: MiniPlayerView, timer: LockIslands.Timer?) {
        if (timer == null) {
            rollers.remove(view)?.clear()
            if (timerViews.remove(view) != null) view.titleView.fontFeatureSettings = null
            return
        }
        // Bound again to the timer it already shows (every refresh): the line is set again over
        // what bind wrote, but a tick still coming goes on where it was rather than starting
        // over. One that stopped - every view it had detached - is started again. PR #15.
        val same = timerViews[view] === timer && handler.hasCallbacks(timerTick)
        timerViews[view] = timer
        if (view.titleView.fontFeatureSettings != "tnum") view.titleView.fontFeatureSettings = "tnum"
        timerText(view, timer, timer.displayText())
        if (same) return
        handler.removeCallbacks(timerTick)
        timerTick.run()
    }

    /** The pills whose timer line turns its digits: a running countdown's (timerType -1). */
    private val rollers = java.util.WeakHashMap<MiniPlayerView, RollingDigits>()

    private fun timerText(view: MiniPlayerView, timer: LockIslands.Timer, text: String) {
        if (timer.type == -1) {
            rollers.getOrPut(view) { RollingDigits(view.titleView) }.set(text)
        } else {
            rollers.remove(view)?.clear()
            if (view.titleView.text?.toString() != text) view.titleView.text = text
        }
    }

    /** Progress buttons run by their template's timer: redrawn on every tick. */
    private val autoRings = java.util.WeakHashMap<ProgressRingDrawable, Boolean>()

    private val timerTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var next = Long.MAX_VALUE
            for ((view, timer) in timerViews.entries.toList()) {
                if (!view.isAttachedToWindow || !timer.running) continue
                timerText(view, timer, timer.text(now))
                // To the chronometer's next second: up from its start, down to its end.
                val ms = timer.elapsedMs(now)
                val wait = if (timer.type > 0) 1000L - ms % 1000L else (ms % 1000L).takeIf { it > 0L } ?: 1000L
                next = minOf(next, wait)
            }
            autoRings.keys.toList().forEach { it.invalidateSelf() }
            if (next != Long.MAX_VALUE) handler.postDelayed(this, next + 5L)
        }
    }

    /** A note's picture as the pill's artwork wants it: a bitmap, drawn once per update. */
    private fun noteBitmap(note: LockIslands.Note): Bitmap? {
        // The picture too: the stack island's changes with its apps while its newest stays.
        noteBitmaps[note.key]?.let { (time, icon, bitmap) -> if (time == note.time && icon === note.icon) return bitmap }
        val drawable = note.icon
        val side = dp(56f)
        val bitmap = drawable?.let {
            runCatching {
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    // Fitted, not stretched: a picture left whole may not be square.
                    val w = it.intrinsicWidth
                    val h = it.intrinsicHeight
                    val old = it.copyBounds()
                    if (w > 0 && h > 0 && w != h) {
                        val k = side.toFloat() / maxOf(w, h)
                        val dw = (w * k).toInt()
                        val dh = (h * k).toInt()
                        it.setBounds((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2)
                    } else {
                        it.setBounds(0, 0, side, side)
                    }
                    it.draw(canvas)
                    it.bounds = old
                }
            }.getOrNull()
        }
        if (noteBitmaps.size > 60) noteBitmaps.clear()
        noteBitmaps[note.key] = Triple(note.time, note.icon, bitmap)
        return bitmap
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    var thumb: Bitmap? = null
    /** The artwork last handed to the pill; it stays until a new one is ready to replace it. */
    var thumbShown: Bitmap? = null

    /**
     * The artwork at the size the pill draws it. A player's album art is often 1000px or more,
     * and handing that straight to the ImageView uploaded the whole thing to the GPU on the
     * frame of every track change. The scaling runs off the main thread; until it lands the
     * pill keeps the artwork it had.
     */
    private fun thumbnailFor(cover: Bitmap?, view: MiniPlayerView): Bitmap? {
        // A skip sends the new title before the new artwork: keep the old one until it comes
        // rather than going blank for those frames - the flash under the spin.
        if (cover == null || cover.isRecycled) return shown(null)
        // By content, not by object: MediaController.getMetadata() is a binder call and hands
        // back a new Bitmap every time. Compared by identity, every refresh was "new artwork",
        // scaled it again, and the scaled one's own refresh fetched another - a loop that never
        // let a scaled one be shown, which left the pill's artwork empty.
        val key = artworkKey(cover)
        if (key == thumbKey) return shown(thumb)
        // One thumbnail for every view the music is bound to, so sized by a laid-out artwork -
        // the pill's, else this one's - never less than the pill's default. A flight or spare
        // bound before its first layout has an artwork 1px wide: the thumbnail came out 2x2,
        // was kept for the track, and the pill drew it as a blurred blob (filmed 2026-09-25).
        val minSide = dp(40f)
        val side = (listOf(player?.artworkView?.width ?: 0, view.artworkView.width)
            .firstOrNull { it >= minSide } ?: dp(48f)) * 2
        thumbKey = key
        if (cover.width <= side && cover.height <= side) {
            thumb = cover
            return shown(cover)
        }
        thumb = null
        MiniPlayerRuntime.artWorker.execute {
            val scaled = runCatching {
                val k = side.toFloat() / minOf(cover.width, cover.height)
                Bitmap.createScaledBitmap(cover, (cover.width * k).toInt().coerceAtLeast(1),
                    (cover.height * k).toInt().coerceAtLeast(1), true)
            }.getOrNull()
            handler.post {
                if (thumbKey != key || scaled == null) return@post
                thumb = scaled
                // Straight onto the pill: a refresh here fetched the metadata again for nothing.
                val ready = shown(scaled)
                if (selectedIsland == MUSIC_ISLAND) player?.showArtwork(ready)
                else if (smallKey == MUSIC_ISLAND) refresh()
            }
        }
        return shown(null)
    }

    private var thumbKey: String? = null

    /** Size and an 8x8 sample of the pixels: cheap, and different whenever the picture is. */
    private fun artworkKey(b: Bitmap): String {
        var hash = 17L
        if (b.config != Bitmap.Config.HARDWARE) {
            runCatching {
                for (j in 0 until 8) for (i in 0 until 8) {
                    hash = hash * 31 + b.getPixel(i * (b.width - 1) / 7, j * (b.height - 1) / 7)
                }
            }
        } else hash = b.generationId.toLong()
        return "${b.width}x${b.height}#$hash"
    }

    /**
     * A skip sends several metadata updates, one of them with no artwork, while the new
     * artwork is still being scaled: each of those used to blank the pill's artwork for a
     * frame - the grey flash, twice per skip on film. Nothing replaces the shown artwork but
     * a ready one.
     */
    private fun shown(ready: Bitmap?): Bitmap? {
        if (ready != null && !ready.isRecycled) thumbShown = ready
        return thumbShown?.takeUnless { it.isRecycled }
    }

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
            exchange?.has(MUSIC_ISLAND) != true
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
        val presentation = presentationWith(cardMoving || exchange != null)
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
                "nr=$nativeRequested ms=$musicSettled tr=${cardMoving || exchange != null} " +
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
        setSmallShown(shown && smallKey != null && !held && !exchangeHoldsSmall() &&
            (groupMotion.groupShowsSmall() ?: true),
            animate = shown && !snapSmallOnce && groupMotion.group == null && exchange == null)
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
        view?.setSecondShown(shown && smallKey == null, animate = !noteMorphMotion.pillHeld() && !exchangeHoldsPill())
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
        var v: View = notificationStack() ?: return null
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

    private fun togglePlayback() = command(
        PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    ) { current, playing ->
        if (playing) current.transportControls.pause() else current.transportControls.play()
    }

    private fun skip(next: Boolean) = command(
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
