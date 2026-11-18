package com.os4.musiccover.island
import kotlin.math.abs

/**
 * A pull down that folds a card back into the row of islands, told apart from a scroll of the
 * lock screen's list. Only a gesture that starts with the list at its top can become the pull;
 * one that scrolls first stays the list's until the finger lifts, even when it comes back up to
 * the top on the way (#12: reading back up a list opened out of the stack island folded it).
 *
 * Adapted from TakeKazeX's PR #15.
 */
internal class MiniPlayerCollapseGesture {
    var armed = false
        private set

    fun start(eligible: Boolean) { armed = eligible }

    /** True on the one move that turns the gesture into the pull. */
    fun move(dx: Float, dy: Float, slop: Float, atTop: Boolean): Boolean {
        if (!armed) return false
        if (!atTop || dy < -slop || abs(dx) > slop && abs(dx) >= abs(dy)) {
            armed = false
            return false
        }
        if (dy > slop && dy > abs(dx) * 1.2f) {
            armed = false
            return true
        }
        return false
    }

    fun reset() { armed = false }

    companion object {
        /**
         * The list at its top: its own scroll no further than where the lock screen rests it.
         * HyperOS lays a stack opened out as a LIST below the clock with a positive scroll
         * before anyone has scrolled it. Unread either way, it counts as the top - the pull
         * goes on working as it did before the list was asked, on a build that names these
         * differently.
         */
        fun atListTop(scrollY: Int?, restingScrollY: Float?): Boolean =
            scrollY == null || restingScrollY == null || !restingScrollY.isFinite() ||
                scrollY <= restingScrollY.coerceAtLeast(0f) + 1f
    }
}

/** The OEM fallback must respect the same whole-gesture ownership as CardTouch, including gaps. */
internal class SpreadListGesture {
    private val classifier = MiniPlayerCollapseGesture()
    private var observed = false
    private var pulling = false
    /** The list took this gesture: scrolled, read upward or dismissed sideways. */
    private var owned = false
    var canFold: Boolean = true
        private set

    fun down(atTop: Boolean) {
        observed = true
        pulling = false
        owned = false
        canFold = false
        classifier.start(atTop)
    }

    fun move(dx: Float, dy: Float, slop: Float, atTop: Boolean) {
        if (!observed || pulling) return
        if (classifier.move(dx, dy, slop, atTop)) {
            pulling = true
            canFold = true
        } else if (!classifier.armed) {
            // The classifier disarmed itself: this gesture is the list's, not the fold's.
            owned = true
        }
    }

    fun end(cancelled: Boolean) {
        if (cancelled) canFold = false
        // A tap decided nothing, so the OEM fallback is the list's again as soon as the finger
        // lifts; otherwise a single tap left a spread open over a stack the OEM had folded.
        else if (!pulling && !owned) canFold = true
        // An owned gesture keeps its ownership through the OEM's fling/hands-up callback,
        // until the next DOWN.
        classifier.reset()
    }

    fun claim() {
        // CardTouch now makes the direction decision. A residual OEM overscroll must not
        // turn a cancelled/short pull into a second fold through watchOpen's fallback.
        observed = false
        owned = true
        canFold = false
        classifier.reset()
    }

    /**
     * The list just came to rest open with no gesture owning it: the opening gesture's UP is
     * seen in SPREAD_OPENING and never reaches [end], so it has to be given back here.
     */
    fun rested() {
        observed = false
        pulling = false
        owned = false
        canFold = true
        classifier.reset()
    }
}
