package com.os4.musiccover.island

import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import com.os4.musiccover.CoverMorphMotion

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
    /** The pill's place in the row, sprung (controller, "the row's layout, animated"). */
    val rowLeft: Jelly
    val rowWidth: Jelly
    fun density(): Float
    fun stackedStyle(): Boolean
    fun dp(value: Float): Int
    fun notificationMaterial(): Boolean
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
}

/** What a part asks the controller to do to the row: refresh it, lay it out, apply its frame. */
internal interface RowHost {
    fun refresh()
    fun scheduleRefresh()
    fun applyRow()
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
    /** Another motion is drawing the pill's frame (the controller's frameTaken). */
    fun frameTaken(): Boolean
}

/** Plain arithmetic the controller and the motions share; moved here off the controller (M2). */
internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

internal fun lerpBox(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box, t: Float) =
    CoverMorphMotion.Box(lerp(a.x, b.x, t), lerp(a.y, b.y, t),
        maxOf(1f, lerp(a.w, b.w, t)), maxOf(1f, lerp(a.h, b.h, t)))
