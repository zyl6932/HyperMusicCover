package com.os4.musiccover.island

import com.os4.musiccover.Gen
import android.view.View
import kotlin.math.abs

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1); `c` is the
 * controller. See the section comments below for what it is.
 */
internal class NoteDragMotion(private val c: MiniPlayerController) {

    // ---- a finger pulling a notification island out

    /**
     * A notification island - the pill's or the small one - pulled up by the finger into its
     * row, as the music pill is pulled up into the media card. The row only exists once the
     * stack has been given the notification back, a frame or two later, so the finger is
     * followed from the start and the morph picks up wherever it has got to when the row is
     * there. Let go before then, the pull's own distance and speed decide.
     */
    class NoteDrag(
        val key: String,
        val startY: Float,
        /** Up out of the island into its row; false for a row pulled down into its island. */
        val opening: Boolean,
        val threshold: Float,
        val nudgeLimit: Float,
    ) {
        var y = startY
        var nudgeX = 0f
        var ended = false
        /** The stack's list asked for: the pull went past its threshold. */
        var listAsked = false
        /** Where the lift sends it: its row (true) or its island. */
        var toRow = false
        /** Toward the far end, in pixels per second, as the lift measured it. */
        var speed = 0f
        /** The pull's length, from the island to the row: unknown (0) until the row is there. */
        var span = 0f

        fun pulled() = if (opening) startY - y else y - startY
    }

    var noteDrag: NoteDrag? = null

    /** The running notification morph's pull length: what a finger catching it moves over. */
    var noteSpan = 0f

    private fun spanOf(drag: NoteDrag) = drag.span.takeIf { it > 0f } ?: NOTE_SPAN_MIN_DP * c.density()

    /** The pull as the music's is posed (MiniPlayerRuntime.pose): progress, then nudge. */
    fun notePose(drag: NoteDrag): Pair<Float, Float> = MiniPlayerRuntime.pose(drag.pulled(),
        !drag.opening, drag.threshold, spanOf(drag), drag.nudgeLimit)

    /** The lift's speed as the morph's progress speed. */
    fun noteVelocity(drag: NoteDrag): Float =
        (if (drag.opening) drag.speed else -drag.speed) / spanOf(drag)

    /** A notification's morph, let go by a finger that caught it: to its row or its island. */
    fun releaseNote(toRow: Boolean, velocity: Float) {
        c.morph?.release(toRow, velocity)
        c.noteMorphMotion.noteMorphKey?.let { c.stackHost.turnTo(it, toRow) }
        if (toRow && c.noteMorphMotion.noteMorphKey == STACK_ISLAND) c.stackHost.showStackAsList("turned out")
        // A flight headed out frees its home; headed home, it holds it again.
        c.noteMorphMotion.setFlightOut(toRow)
    }

    /**
     * Busy for a finger pulling a notification island out: the motions that keep it from starting
     * (M0, the subset as it was).
     */
    private fun busyForNoteDrag(): Boolean = c.noteMorphMotion.noteMorphKey != null || c.morph != null || c.cardExchange.exchange != null

    fun beginNoteDrag(key: String, fromSmall: Boolean, startY: Float): Boolean {
        // Folded natively, a pull past the threshold opens the stack's list as a tap does; the
        // pull is taken (true) so nothing else runs under it, and the island springs back as it
        // goes.
        if (c.spreadMotion.spread != null) return false
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            // The pill stays where the finger has lifted it, and the morph starts from there
            // (startSpreadMorphs takes the nudge over). Sprung home here, it dropped back down
            // for the frame before the morph came up past it (#122).
            val lift = (-(c.player?.nudgeY ?: 0f)).coerceAtLeast(0f)
            c.player?.holdNudge()
            c.springSmallNudgeBack(0f, 0f)
            c.spreadMotion.openSpread("pull", SpreadFinger(startY, lift))
            // Refused (another motion under way): nothing takes the nudge over.
            if (c.spreadMotion.spread == null) c.player?.springNudgeBack(0f, 0f)
            return true
        }
        if (busyForNoteDrag()) return false
        val view = c.player ?: return false
        if (view.visibility != View.VISIBLE) return false
        if (fromSmall && key != c.smallKey) return false
        if (!fromSmall && c.selectedIsland != key) return false
        // Another island out as its card: the pull opens this one in its stead, the two
        // exchanged on their own spring (not under the finger, which only asked for it).
        if (c.expandedKey()?.let { it != key } == true) {
            c.player?.springNudgeBack(0f, 0f)
            c.springSmallNudgeBack(0f, 0f)
            // The music left the cover for the island out now (a page opened from it): pulled
            // back up it goes into the cover, as a tap does. Exchanged as a plain card it came
            // up over the wallpaper with the cover still held off (#63).
            if (key == MUSIC_ISLAND && MiniPlayerRuntime.takeRestoreScene()) {
                c.cardExchange.openCover()
                return true
            }
            return c.cardExchange.startExchange(key)
        }
        if (c.noteMorphMotion.prepareFlight(key) == null) return false
        c.swapMotion.endSwap()
        c.resetIslandDrag()
        // The island keeps following the finger until its row is there to morph into; its
        // nudge is handed to the morph then (startNoteMorph), as the pill's is to the card's.
        c.noteMorphMotion.noteMorphKey = key
        c.noteMorphMotion.flightFromSmall = fromSmall
        c.noteMorphMotion.flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
        c.flightOut = false
        noteDrag = NoteDrag(key, startY, opening = true,
            MiniPlayerRuntime.dragThresholdPx(c.density()), MiniPlayerRuntime.dragNudgePx(c.density()))
        c.stackHost.letOut(key)
        c.noteMorphMotion.rowWaitKey = key
        c.noteMorphMotion.rowWaitSince = android.os.SystemClock.uptimeMillis()
        c.noteMorphMotion.appListAsked = false
        Gen.frame(c.noteMorphMotion.rowWait)
        return true
    }

    fun noteDragMove(y: Float, nudgeX: Float = 0f) { android.os.Trace.beginSection("MC t.noteDragMove"); try {
        if (c.spreadMotion.spreadFingerMove(y)) return
        val drag = noteDrag ?: return
        drag.y = y
        drag.nudgeX = nudgeX
        applyNoteDrag()
    } finally { android.os.Trace.endSection() } }

    /**
     * The finger is off: the music's lift decides (MiniPlayerRuntime.lift). Let go before the
     * row was there, the decision takes the shortest span a pull can have and the real one
     * sets the speed once the row is laid out (startNoteMorph) - with a 1px span, the flight
     * was once flung hundreds of times too fast (filmed 2026-09-25).
     */
    fun noteDragEnd(velocityY: Float, cancelled: Boolean) {
        if (c.spreadMotion.spreadFingerEnd(velocityY, cancelled)) return
        val drag = noteDrag ?: return
        drag.ended = true
        val speed = if (drag.opening) -velocityY else velocityY
        val (toRow, _) = MiniPlayerRuntime.lift(drag.pulled(), speed, !drag.opening, drag.threshold,
            spanOf(drag), cancelled)
        drag.toRow = toRow
        drag.speed = if (drag.pulled() > drag.threshold) speed else 0f
        // Flung open short of the threshold: the list now, not once the card is there.
        if (toRow && drag.opening && drag.key == STACK_ISLAND && !drag.listAsked) {
            drag.listAsked = true
            c.stackHost.showStackAsList("flung")
        }
        val running = c.morph
        if (running != null) {
            noteDrag = null
            releaseNote(toRow, noteVelocity(drag))
            return
        }
        if (!toRow && drag.opening) {
            // Let go short, before the row was even there: nothing opened.
            noteDrag = null
            c.noteMorphMotion.abandonNoteMorph(drag.key)
        }
        // Headed for the row: its arrival opens it on its own.
    }

    fun applyNoteDrag() {
        val drag = noteDrag ?: return
        // The list only once the pull means it: a pull let go short would have pressed the
        // clock down and let it back up for nothing.
        if (drag.key == STACK_ISLAND && drag.opening && !drag.listAsked && drag.pulled() > drag.threshold) {
            drag.listAsked = true
            c.stackHost.showStackAsList("pulled")
        }
        if (drag.ended) return
        val running = c.morph
        if (running != null) {
            val (p, nudge) = notePose(drag)
            running.drag(p, nudge, drag.nudgeX)
            return
        }
        if (!drag.opening) return
        // No row to morph into yet: the island itself goes on following the finger, as far as
        // the morph would have carried it - rubber-banded to the threshold, then one to one.
        val pulled = drag.pulled()
        val up = if (pulled <= drag.threshold) MiniCardMorph.rubber(pulled.coerceAtLeast(0f), drag.nudgeLimit)
            else MiniCardMorph.rubber(drag.threshold, drag.nudgeLimit) + (pulled - drag.threshold)
        if (c.noteMorphMotion.flightFromSmall) c.setSmallNudge(drag.nudgeX, -up) else c.player?.setNudge(drag.nudgeX, -up)
    }

    /** The row's arrival: how far the pull has to go, from the island's place to the row's. */
    fun noteDragSpan(row: View, fromSmall: Boolean): Float {
        val start = (if (fromSmall) c.smallBoxOnScreen() else c.player?.restBoxOnScreen())?.y ?: return 1f
        val xy = IntArray(2).also(row::getLocationOnScreen)
        return kotlin.math.abs(start - xy[1]).coerceAtLeast(NOTE_SPAN_MIN_DP * c.density())
    }
}
