package com.os4.musiccover.island

import com.os4.musiccover.CoverMorphMotion
import com.os4.musiccover.Gen
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1), and given only
 * what it uses of the controller (M2, MiniPlayerParts.kt). See the section comments below for
 * what it is.
 */
internal class AppearMotion(
    private val views: RowViews,
    private val rowState: RowState,
    private val edits: RowEdits,
    private val rowHost: RowHost,
    private val motions: Motions,
    /** Made after this part: read when a line is logged, not when this is built. */
    private val probeRef: () -> MiniPlayerProbe,
) {
    private val probe get() = probeRef()

    /**
     * The pill coming up where there was no row at all - the first island on a quiet lock screen,
     * the torch's own focus notification after a double tap on it. It used to be switched on
     * whole in one frame. It grows as an island does coming back into the row beside the media
     * card (startSwap's own-left-end case, the user's pick): out of a circle at its left end,
     * rightward, on CHANGE_EASE, thinned while it widens fast, the content coming in over it.
     * The row's spring, if the row moves meanwhile, is carried inside it.
     *
     * The last island going - the torch switched off - it goes back the same way, right to left
     * into that circle, which fades as it gets there; only then is it GONE. It used to go in one
     * frame too. [appear]'s target says which way; one coming in mid-way turns it round from
     * where it has got to.
     */
    // A spring of its own, bouncier than the super island's APPEAR (0.7 overshoots ~5%, which
    // the first build showed as no bounce at all, 2026-10-14): the rise carries well past its
    // place and settles back - the landing the squash in applyAppear answers.
    val appear = Jelly(ROW_POP_RESPONSE, ROW_POP_DAMPING)
    var appearing = false
    private var appearLast = 0L

    /** Since when a pill whose island has gone has been waiting to be on screen to go back (updateVisibility). */
    var backWaitSince = 0L

    /**
     * The row the pill last stood for on screen; empty once its last island has gone. A pill
     * shown again with this empty is a new row, and grows in even when the screen is not yet
     * back to show it (appearFrame waits); one only hidden a while - the doze, a double press
     * of the power key, the cover - keeps its row and comes back on the lock screen's own fade.
     */
    var lastShownKeys: List<String> = emptyList()
    private var growHeldSince = 0L

    /** The way back as it was drawn, for its gone line: frames, frames the pill was in its morph, narrowest, start. */
    private var backFrames = 0
    private var backDrawn = 0
    private var backMinW = Float.NaN
    private var backStartedAt = 0L

    val appearFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC appear"); try {
            if (!appearing) return
            val dt = if (appearLast == 0L) 1f / 120f
                else ((frameTimeNanos - appearLast) / 1e9f).coerceIn(0f, 0.05f)
            appearLast = frameTimeNanos
            // A new row come up while the screen is still coming back: it waits out of sight
            // and rises once it can be seen doing so.
            val held = appear.target == 1f && appear.value == 0f && !rowState.pillSeen() &&
                android.os.SystemClock.uptimeMillis() - growHeldSince < BACK_WAIT_MS
            if (!held) appear.step(dt)
            val applied = applyAppear()
            if (!applied && leaving()) MiniPlayerRuntime.noteGone("back cut at ${"%.2f".format(appear.value)}: ${probe.quietWhy()}")
            if (!applied || appear.atRest()) endAppear()
            else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }
    /** Going back into nothing: set to GONE once it is there (endAppear). */
    fun leaving(): Boolean = appearing && appear.target == 0f
    fun startAppear() {
        val view = views.player ?: return
        if (appearing) {
            // Turned round on its way out: back up from where it is, at the speed it had.
            appear.target = 1f
            return
        }
        appear.value = 0f
        appear.velocity = 0f
        appear.target = 1f
        backFrames = 0
        backDrawn = 0
        backMinW = Float.NaN
        backStartedAt = android.os.SystemClock.uptimeMillis()
        growHeldSince = backStartedAt
        view.beginMorph(layoutOnly = true)
        appearing = true
        appearLast = 0L
        // The row's spring still holds wherever the pill was last shown: set afresh by the next
        // position(), not sprung from there.
        edits.rowKnown = false
        // The first frame drawn is already the start of the rise, not the whole pill for one frame.
        if (!applyAppear()) view.setContentAlpha(0f)
        Gen.unframe(appearFrame)
        Gen.frame(appearFrame)
    }
    fun startDisappear() {
        val view = views.player ?: return
        if (leaving()) return
        if (!appearing) {
            appear.value = 1f
            appear.velocity = 0f
            view.beginMorph(layoutOnly = true)
            appearing = true
            appearLast = 0L
            edits.rowKnown = false
            Gen.unframe(appearFrame)
            Gen.frame(appearFrame)
        }
        appear.target = 0f
        backFrames = 0
        backDrawn = 0
        backMinW = Float.NaN
        backStartedAt = android.os.SystemClock.uptimeMillis()
        view.setInteractionsEnabled(false)
    }
    /**
     * The torch's and the camera's icons pushed out by [push] pixels and pressed thin, each from
     * its outer edge. The icon inside each button, never the button's own layout: the pill
     * follows where those layouts are drawn (followShortcuts), and moving them would read to it
     * as the row zooming - it would grow with the push. 0 puts both back.
     */
    private fun squeezeShortcuts(push: Float) {
        fun one(layout: View, outward: Float) {
            val icon = (layout as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0) ?: return
            if (push < 0.25f) {
                if (icon.translationX != 0f || icon.scaleX != 1f) {
                    icon.translationX = 0f
                    icon.scaleX = 1f
                    icon.scaleY = 1f
                }
                return
            }
            val k = push / icon.width.coerceAtLeast(1)
            icon.pivotX = if (outward < 0f) 0f else icon.width.toFloat()
            icon.pivotY = icon.height / 2f
            icon.translationX = outward * push
            icon.scaleX = (1f - k * 0.5f).coerceAtLeast(0.7f)
            icon.scaleY = (1f + k * 0.25f).coerceAtMost(1.15f)
        }
        one(views.left, -1f)
        one(views.right, 1f)
    }
    /** One frame of it; false when another motion has taken the frame (it is theirs from here). */
    private fun applyAppear(): Boolean {
        val view = views.player ?: return false
        // Cut only by a motion taking the frame. The doze, the wake's fade and the row held into
        // the AOD only change how much of it is seen; cut by them, the pill went in a frame.
        if (motions.frameTaken() || view.visibility != View.VISIBLE) return false
        val rest = view.restBoxOnScreen() ?: return true
        val xy = IntArray(2).also(views.host::getLocationOnScreen)
        // The row as its own spring has it, grown from its left end.
        val fullW = (if (rowState.rowKnown) views.rowWidth.value else rest.w).coerceAtLeast(rest.h)
        val left = if (rowState.rowKnown) xy[0] + views.rowLeft.value else rest.x
        val p = appear.value
        backFrames++
        // setMorphFrame does nothing to a pill out of its morph: the way is then not drawn.
        if (view.inMorph()) backDrawn++
        if (views.stackedStyle()) {
            val glass = p.coerceIn(0f, 1f)
            if (backMinW.isNaN() || fullW < backMinW) backMinW = fullW
            view.setMorphFrame(CoverMorphMotion.Box(left, rest.y, fullW, rest.h), rest.h / 2f, glass)
            view.setContentAlpha(1f)
            return true
        }
        // Up from below, whole (2026-10-14, user): it rose out of its left end's circle before,
        // and from the middle before that - both turned down. The pill comes up from under its
        // place a little small, the spring carrying it just past and back; rising fast it is
        // drawn a touch tall and narrow, and settling back off the overshoot a touch flat and
        // wide - the landing. Going, the same in reverse: it sinks away.
        val d = views.density()
        val shown = p.coerceAtLeast(0f)
        val rise = (1f - p) * APPEAR_RISE_DP * d
        // Not held at 1: past its place it is a touch bigger too, which is most of the bounce.
        // The height takes all of it; the width far less - from nearer its own size, and only a
        // share of the overshoot (2026-10-14, user: it spread sideways too much).
        val scale = lerp(APPEAR_SCALE_FROM, 1f, shown)
        val across = if (shown <= 1f) shown else 1f + (shown - 1f) * APPEAR_WIDTH_OVERSHOOT
        val widthScale = lerp(APPEAR_WIDTH_FROM, 1f, across)
        val v = appear.velocity
        val stretch = (if (v > 0f) v * APPEAR_STRETCH else v * APPEAR_LAND)
            .coerceIn(-APPEAR_SQUASH_MAX, APPEAR_SQUASH_MAX)
        val h = (rest.h * scale * (1f + stretch)).coerceAtLeast(1f)
        val w = (fullW * widthScale * (1f - stretch * 0.25f)).coerceAtLeast(h)
        val cx = left + fullW / 2f
        val cy = rest.y + rest.h / 2f + rise
        val glass = MiniCardMorph.smooth(0f, 0.3f, shown)
        if (backMinW.isNaN() || w < backMinW) backMinW = w
        view.setMorphFrame(CoverMorphMotion.Box(cx - w / 2f, cy - h / 2f, w, h), h / 2f, glass)
        // Wider than its place - the overshoot and the landing - it shoulders the torch and the
        // camera aside, and they give: pushed out and pressed thin against it.
        squeezeShortcuts(((w - fullW) / 2f).coerceAtLeast(0f) * APPEAR_PUSH_GAIN)
        // Out of focus to in, the glass left sharp, as the super island's content arrives.
        view.setMorphContentBlur((1f - MiniCardMorph.smooth(0f, 0.5f, shown)) * APPEAR_BLUR_DP * d)
        // The content in two beats: the picture first, popping in on its centre, the text a
        // little after it, sliding in from the left. Early, both: the whole of it is in by the
        // time the spring first reaches its place, not waiting on the bounce to settle.
        val icon = MiniCardMorph.smooth(0.05f, 0.4f, shown)
        val text = MiniCardMorph.smooth(0.2f, 0.65f, shown)
        view.setAppearStagger(
            iconScale = lerp(APPEAR_ICON_FROM, 1f, icon),
            iconAlpha = icon,
            textShift = -(1f - text) * APPEAR_TEXT_SHIFT_DP * d,
            textAlpha = text,
        )
        return true
    }

    fun endAppear() {
        if (!appearing) return
        val left = appear.target == 0f
        MiniPlayerRuntime.noteGone((if (left) "back" else "up") + " done ${android.os.SystemClock.uptimeMillis() - backStartedAt}ms " +
            "frames=$backFrames drawn=$backDrawn minW=${if (backMinW.isNaN()) "-" else backMinW.toInt().toString()} " +
            "at=${"%.2f".format(appear.value)} row=${probe.quietWhy()} pill=${views.player?.let { "${it.visibility}/t${"%.2f".format(it.transitionAlpha)}/a${"%.2f".format(it.alpha)}" }}")
        appearing = false
        Gen.unframe(appearFrame)
        squeezeShortcuts(0f)
        val view = views.player ?: return
        view.clearAppearStagger()
        view.setMorphContentBlur(0f)
        view.setContentAlpha(1f)
        if (left) {
            // Gone back into nothing (or cut short there): the row is not there any more. The
            // pill still holds the notification it went with; the refresh puts the row as it now is.
            lastShownKeys = emptyList()
            view.endMorph()
            view.visibility = View.GONE
            rowHost.scheduleRefresh()
            return
        }
        // Handed on: the row's spring, still running, draws the frame and ends the morph itself;
        // so does any other motion that has taken the frame. Cut short by anything else - the
        // doze, the wake's fade, the row held off - nobody draws it again, and asked whether the
        // row was quiet the pill was left as that frame had it: a circle's width with the title
        // laid out past its end (screenshot 2026-09-29 20:20). It goes to its whole width.
        if (rowState.rowAnimating && !motions.grouping && !motions.swapping) rowHost.applyRow()
        else if (!motions.frameTaken() && !rowState.rowAnimating) view.endMorph()
    }
}
