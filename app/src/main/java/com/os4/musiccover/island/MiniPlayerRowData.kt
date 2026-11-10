package com.os4.musiccover.island

import android.graphics.Matrix
import android.view.View
import com.os4.musiccover.Settings

// Small holders of MiniPlayerController's row, moved out of the class as they were
// (MiniPlayerController plan, M0). None reads the controller; each is its state only.

/** One of those rows on its way out from under the island: its own spring, 0 there, 1 in the stack. */
internal class PileRow(val row: View) {
    val out = Jelly(PILE_RESPONSE, PILE_DAMPING)
    val matrix = Matrix()
    /** Its turn, from the lead: 1 the nearest to it. */
    var turn = 1
}

/** Who is in the big place and the small one once an expanded switch is over. */
internal class Seats(val big: String?, val small: String?)

/**
 * A row given back to its island before its morph home has landed, and where it was drawn
 * then. The stack lays out the rest without it at once - the card coming out lands where the
 * stack really puts it, nothing estimated - while the row, a transient view now, is drawn on
 * for the morph and held where it was (2026-09-26, the user's plan B).
 */
internal class Kept(val row: View, val ty: Float, val alpha: Float, val since: Long)

/**
 * [wasOut]: already out as its card when the spread began - a focus island tapped open before
 * the stack island was. It is in the list as it is, and folding it goes home with the rest;
 * it stayed behind as a card over the folded stack before (the user, 2026-09-30).
 */
internal class SpreadItem(val key: String, val seat: Int, val wasOut: Boolean = false) {
    /** The stack island itself, into its newest notification's row (stackLeadKey). */
    val lead get() = key == STACK_ISLAND
    /** Its card: the media card, or the notification's row. */
    var native: View? = null
    /** The island's view morphing: the pill, or a stand-in for the small island's place. */
    var view: MiniPlayerView? = null
    var standIn = false
    var morph: MiniCardMorph? = null
}

internal class SpreadCollapseFinger(val startY: Float, val startProgress: Float, val range: Float) {
    var y = startY
    var ended = false
    var target = 1f
    /**
     * Let go: from where the finger left it to [target], on the cards' own spring with the
     * finger's speed. Jumped straight to the end, a pull let go half way had the card in
     * the island (or back in the list) on the very next frame (#122, filmed 2026-11-04).
     */
    val motion = com.os4.musiccover.CoverMorphMotion()
    var steppedAt = 0L
    fun progress(): Float = if (ended) motion.value
        else MiniPlayerStackGesture.collapseProgress(startProgress, y - startY, range)
    /** Still on its way to [target] after the let-go. */
    fun settling(): Boolean = ended && !motion.atRest()
    fun step() {
        if (!ended || motion.atRest()) return
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (steppedAt == 0L) 1f / 120f else ((now - steppedAt) / 1000f).coerceIn(0f, 0.05f)
        steppedAt = now
        motion.step(dt, Settings.SPRING.get())
    }
}

/**
 * A pull up on the stack island past the threshold: from there the finger has the list, as
 * a pull on the music has its card - it scrolls the stack, and every island rides on the
 * scroll. Opened on the stack's own scroll instead, the finger was heard no more: pulled
 * back down, the list went on opening, stopped half way under the held finger and went to
 * an end on its own a moment later (the user, 2026-09-30).
 */
internal class SpreadFinger(val startY: Float, val lift: Float = 0f) {
    var y = startY
    /** The pull at the moment the stack started to follow: its scroll's zero. */
    var base = Float.NaN
    var ended = false
    var toList = false
    /**
     * On screen, from the pill at rest to the lead row: the finger's pull over this is the
     * spread's progress, so the card stays under the finger. Over the stack's scroll (220px
     * against ~700px on screen) the card ran three times ahead of it and the first frame
     * leapt a quarter of the way (#122). 0 until the morphs have started.
     */
    var span = 0f
    fun pulled() = startY - y
}

/** The pill at rest: its size, and the centre of the row it is laid out in. */
internal class PillRest(val width: Int, val height: Int, val centerX: Float, val centerY: Float)
