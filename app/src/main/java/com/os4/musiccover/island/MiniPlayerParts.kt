package com.os4.musiccover.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.session.MediaController
import android.os.Handler
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.os4.musiccover.cover.CoverMorphMotion
import java.lang.ref.WeakReference
import org.json.JSONObject

/*
 * What the mini player's parts (SessionSource, the motions, StackHost and the rest) are given of
 * the controller, in place of the whole of it (MiniPlayerController plan, M2). The controller
 * implements all of these and hands itself to each part as only the ones it needs, so a part's
 * constructor is the list of what it reaches. Each interface holds only what some part uses
 * today; a member is added here when a part is moved over, not before.
 *
 * Nothing here is an object of its own: the members stay where they were, on the controller,
 * and are read through the same fields as before.
 */

/** The views the row is made of and what they live in: the host, its context and its thread. */
internal interface RowViews {
    val context: Context
    val handler: Handler
    val host: ViewGroup
    val left: View
    val right: View
    val loader: ClassLoader
    val player: MiniPlayerView?
    val smallIsland: ShortcutDisc?
    /** Views kept from earlier switches for the next one to take. */
    val spareViews: List<MiniPlayerView>
    /** The small island's resting centre in host pixels, and its grow spring. */
    val smallRest: FloatArray
    val smallGrow: Jelly
    /** Steps [smallGrow] each frame while it runs. */
    val smallGrowFrame: Choreographer.FrameCallback
    /** The lock screen root the buttons are followed in, and host-to-screen matrices. */
    val followRoot: WeakReference<View>?
    val followHost: Matrix
    val hostInverse: Matrix
    /** Scratch for one measurement at a time, on the main thread. */
    val scratch: Matrix
    /** The pill's place in the row, sprung (controller, "the row's layout, animated"). */
    val rowLeft: Jelly
    val rowWidth: Jelly
    fun density(): Float
    fun stackedStyle(): Boolean
    fun dp(value: Float): Int
    fun notificationMaterial(): Boolean
    fun discMaterialGeneration(): Int
    fun smallNudgeX(): Float
    fun smallNudgeY(): Float
    fun resizeSmallIsland(small: ShortcutDisc, width: Int, height: Int)
    fun smallRestWidth(): Int
    fun smallRestHeight(): Int
    fun smallBoxOnScreen(): CoverMorphMotion.Box
    fun widenSmallIsland(rest: CoverMorphMotion.Box)
    fun restoreSmallIslandShape()
    fun setSmallShown(shown: Boolean, animate: Boolean)
    fun clearSmallNudge()
    fun setSmallNudge(x: Float, y: Float)
    fun springSmallNudgeBack(vx: Float, vy: Float)
    /** A small island's view for [key]; [pooled] takes one from [spareViews] when there is one. */
    fun prepareSmallView(key: String, pooled: Boolean = false): MiniPlayerView?
    fun recycleSmallView(v: MiniPlayerView)
    fun applySmallGrow()
    fun takeSpare(): MiniPlayerView?
    /** The small island on its rest place, carried by the row's motion as the pill is. */
    fun placeSmallIsland(follow: Matrix?, fade: Float)
    fun fitToPill(view: MiniPlayerView, small: Boolean)
    /** The small island's pulse when the island out as its card goes back into hiding. */
    fun startSmallPulse(key: String?)
    /** Where in the host the pill goes, over the lock screen's own layer. */
    fun lockScreenLayerIndex(): Int
    /** A view's centre as drawn, every ancestor's transform included, in host pixels. */
    fun drawnCentre(v: View, out: FloatArray, inverse: Matrix = hostInverse): Boolean
    /** The same centre with no transform anywhere: layout positions only. */
    fun restCentre(v: View, out: FloatArray = FloatArray(2)): FloatArray
    fun layerSlot(parent: ViewGroup): Int
    fun onButton(button: View, x: Float, y: Float): Boolean
    /** The media card's header the music morphs into, if it is in the stack. */
    fun transitionHeader(): View?
}

/** The row's state, read only. What a part writes is in [RowEdits]. */
internal interface RowState {
    val controller: MediaController?
    val destroyed: Boolean
    val lastTrack: String
    val cachedCover: Bitmap?
    val nativeSuppressionRequested: Boolean
    val selectedIsland: String?
    val smallKey: String?
    val config: JSONObject
    val configuredHeightDp: Float
    val morph: MiniCardMorph?
    val rowHeldOff: Boolean
    val smallGrowing: Boolean
    val stackLeadKey: String?
    /** The row's islands in order (refreshUnsafe). */
    val islandKeys: List<String>
    val preferredSmall: String?
    val snapSmallOnce: Boolean
    /** The lock screen's own view for an island: the media card's header, a notification row. */
    fun nativeFor(key: String): View?
    fun expandedKey(): String?
    fun groupLead(key: String): String?
    /** Refresh had nothing to bind the pill's island to: it still holds another's picture. */
    val pillUnbound: Boolean
    fun firstAlone(keys: List<String>, selected: String?): Boolean
    /** The small island and the row as they stand this frame, in host pixels. */
    fun smallState(): String
    /** The dynamic switch can be pulled from this end right now. */
    fun canDrag(fromNative: Boolean, small: Boolean = false): Boolean
    val morphScene: Boolean
    val artBridged: Boolean
    val smallWide: Boolean
    fun takesPlace(cur: String, origin: String, big: Boolean): Boolean
    val rowKnown: Boolean
    val rowAnimating: Boolean
    fun pillSeen(): Boolean
}

/** The few fields of the row's state a part writes, kept apart so the writers can be read off. */
internal interface RowEdits {
    var controller: MediaController?
    var rowKnown: Boolean
    var lastTrack: String
    var cachedCover: Bitmap?
    var rowHeldOff: Boolean
    var smallGrowing: Boolean
    var preferredSmall: String?
    var snapSmallOnce: Boolean
    var selectedIsland: String?
    var morph: MiniCardMorph?
    var morphScene: Boolean
    var smallWide: Boolean
    var stackLeadKey: String?
    var followRoot: WeakReference<View>?
    /** The member an app group's island shows, by group key. */
    val appLeadKeys: MutableMap<String, String>
    /** Islands on their way home, by key, with when they set off. */
    val returning: MutableMap<String, Long>
}

/** What a part asks the controller to do to the row: refresh it, lay it out, apply its frame. */
internal interface RowHost {
    fun refresh()
    fun scheduleRefresh()
    fun applyRow()
    /** Moves the pill and the discs with the buttons, as the preDraw does. */
    fun followShortcuts()
    fun updateVisibility()
    fun position()
    fun schedulePosition()
    fun endRow()
    fun resetIslandDrag()
    fun updateNativeSuppression(suppress: Boolean)
    fun ensureNativeHeaderVisible()
    /** One line in the row's landing trace (`op mini`). */
    fun trace(what: String)
    /** Listens to the row's own card morphs (the controller's). */
    val morphListener: MiniCardMorph.Listener
    fun applyArtBridge()
    /** The pill's media buttons, sent to the session the row follows. */
    fun togglePlayback()
    fun skip(next: Boolean)
}

/**
 * Which motions are running, for a part deciding whether it may start or step (the busyForXxx
 * checks named in M0). Queries only: each reads the motion's own field, as the checks did.
 */
internal interface Motions {
    val swapping: Boolean
    val grouping: Boolean
    val exchanging: Boolean
    val noteMorphing: Boolean
    /** The flying pill of a notification morph is up. */
    val flying: Boolean
    /** Another motion is drawing the pill's frame (the controller's frameTaken). */
    fun frameTaken(): Boolean
}

/** Plain arithmetic the controller and the motions share; moved here off the controller (M2). */
internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

internal fun lerpBox(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box, t: Float) =
    CoverMorphMotion.Box(lerp(a.x, b.x, t), lerp(a.y, b.y, t),
        maxOf(1f, lerp(a.w, b.w, t)), maxOf(1f, lerp(a.h, b.h, t)))
