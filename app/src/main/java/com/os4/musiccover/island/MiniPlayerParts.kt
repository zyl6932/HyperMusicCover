package com.os4.musiccover.island

import android.content.Context
import android.media.session.MediaController
import android.os.Handler

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
}

/** The row's state, read only. What a part writes is in [RowEdits]. */
internal interface RowState {
    val controller: MediaController?
    val destroyed: Boolean
    val lastTrack: String
}

/** The few fields of the row's state a part writes, kept apart so the writers can be read off. */
internal interface RowEdits {
    var controller: MediaController?
}

/** What a part asks of the controller: refresh the row, now or on the next frame. */
internal interface RowHost {
    fun refresh()
    fun scheduleRefresh()
}
