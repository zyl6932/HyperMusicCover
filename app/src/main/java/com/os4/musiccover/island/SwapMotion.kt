package com.os4.musiccover.island

import com.os4.musiccover.cover.CoverMorphMotion
import com.os4.musiccover.Main
import com.os4.musiccover.Gen
import android.graphics.Bitmap
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1), and given only
 * what it uses of the controller (M2, MiniPlayerParts.kt). See the section comments below for
 * what it is.
 */
internal class SwapMotion(
    private val views: RowViews,
    private val rowState: RowState,
    private val edits: RowEdits,
    private val rowHost: RowHost,
    private val flightState: FlightState,
    private val sessionSource: SessionSource,
    // Made after this part: read while a swap runs, not when this is built.
    private val bindingRef: () -> MiniPlayerBinding,
    private val noteMorphRef: () -> NoteMorphMotion,
    private val discsRef: () -> ShortcutDiscs,
) {
    private val binding get() = bindingRef()
    private val noteMorphMotion get() = noteMorphRef()
    private val shortcutDiscs get() = discsRef()

    // ------------------------------------------------------------ islands in motion

    /**
     * An island switch in flight, as the super island switches (DynamicIslandAnimationDelegate,
     * CHANGE_EASE): the pill grows into its new island out of where that island was - the
     * small island's circle, or the pill's own left end for one coming back out of the stack -
     * while the island it held shrinks under it into the small island's place. Both are frames
     * changing size, never a scale, so the glass stays at its own pixels.
     */
    class Swap(
        val pillFrom: CoverMorphMotion.Box,
        val pillTo: CoverMorphMotion.Box,
        /**
         * SMALL_FROM_PILL: the small island forms out of the pill's place (it was the pill's).
         * SMALL_POP: another island comes up in the small place. SMALL_EMERGE: the next one
         * slides out from under the pill's end, as the super island's HiddenToSmallIsland.
         * SMALL_KEPT: it is the one that was there, and the switch leaves it alone.
         */
        val smallMode: Int,
        /** SWAP_PAIR: two islands trading places. SWAP_NEXT / SWAP_PREV: a row of three or more. */
        val kind: Int = SWAP_PAIR,
        /** The pill's place is an expanded switch's card's to land in: the switch leaves its frame alone. */
        val holdPill: Boolean = false,
        /**
         * The pill keeps its island and only its width changes: the row's first island alone
         * gaining a small island beside it, or losing it (switchIsland). Its content stays.
         */
        val pillKept: Boolean = false,
    ) {
        /** The pill's frame: CHANGE_EASE out of the small island, APPEAR_EASE out of the middle. */
        val spring = (if (kind == SWAP_PREV && !pillKept) Jelly(APPEAR_RESPONSE, APPEAR_DAMPING)
            else Jelly(SWAP_RESPONSE, SWAP_DAMPING)).apply { value = 0f; target = 1f }
        /** The small island's part: CHANGE_EASE forming out of the pill, APPEAR_EASE emerging. */
        val small = (if (smallMode == SMALL_EMERGE) Jelly(APPEAR_RESPONSE, APPEAR_DAMPING)
            else Jelly(SWAP_RESPONSE, SWAP_DAMPING)).apply { value = 0f; target = 1f }
        /** Out of the middle, the content comes into focus on HIDDEN_EASE, 100ms late. */
        val contentIn = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING).apply { value = 0f; target = 1f }
        /** The old big island into the middle: its frame on SHOW_EASE, its content on ALPHA_EASE. */
        val ghostBox = Jelly(SHOW_RESPONSE, SHOW_DAMPING).apply { value = 0f; target = 1f }
        val ghostContent = Jelly(ALPHA_RESPONSE, ALPHA_DAMPING).apply { value = 0f; target = 1f }
        /** The old small island going into hiding where it is, on HIDDEN_EASE. */
        val ghostDisc = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING).apply { value = 0f; target = 1f }
        var ghostFrom: CoverMorphMotion.Box? = null
        var ghost = false
        var disc = false
        /** The stand-in goes into the small island's place (BigIslandToSmallIsland), on [small]. */
        var ghostSmall = false
        /** The island the stand-in is: the one that left the pill for the small place. */
        var ghostKey: String? = null
        /** The stand-in's second button as it set out: 1 if it was the big island alone. */
        var ghostSecond = 0f
        /** The width the old small island's stand-in starts from; 0 is its whole size. */
        var discFromSide = 0f
        var started = 0L
        var last = 0L

        fun step(dt: Float, now: Long) {
            spring.step(dt)
            small.step(dt)
            if (now - started >= CONTENT_IN_DELAY_MS) contentIn.step(dt)
            if (ghost) { ghostBox.step(dt); ghostContent.step(dt) }
            if (disc) ghostDisc.step(dt)
        }

        fun atRest(now: Long) = spring.atRest() && small.atRest() &&
            (kind != SWAP_PREV || pillKept || (now - started >= CONTENT_IN_DELAY_MS && contentIn.atRest())) &&
            (!ghost || (ghostBox.atRest() && ghostContent.atRest())) && (!disc || ghostDisc.atRest())
    }

    var swap: Swap? = null

    val swapFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC swap"); try {
            val s = swap ?: return
            if (s.kind == SWAP_STACK && (rowState.destroyed || !Main.keyguardLocked() ||
                MiniPlayerScene.aodActive || !views.stackedStyle() || views.player?.visibility != View.VISIBLE)) {
                endSwap()
                return
            }
            val dt = if (s.last == 0L) 1f / 120f
                else ((frameTimeNanos - s.last) / 1e9f).coerceIn(0f, 0.05f)
            s.last = frameTimeNanos
            val now = android.os.SystemClock.uptimeMillis()
            s.step(dt, now)
            applySwap(s)
            if (s.atRest(now)) endSwap() else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }
    /**
     * Starts the switch that has just been made - [oldBig] was in the pill, [oldSmall] beside
     * it. Called after the refresh that put the new islands in, so the pill already holds the
     * new one and only its frame is still where the old one was.
     */
    fun startSwap(oldBig: String?, oldSmall: String?, kind: Int = SWAP_PAIR,
                          intoSmall: Boolean = false,
                          /** One of the two places is an expanded switch's card's to land in (seatAtRest). */
                          holdPill: Boolean = false, holdSmall: Boolean = false,
                          /**
                           * The pill's island stays and goes on from [pillFrom], where a switch
                           * cut short by this one had got it to, to its rest.
                           */
                          pillFrom: CoverMorphMotion.Box? = null, pillStays: Boolean = false,
                          /** The pill's island is the same one: only its width goes (Swap.pillKept). */
                          pillKept: Boolean = false,
                          /** The small island going into hiding starts at this width (0: whole). */
                          smallFromSide: Float = 0f,
                          /** The finger's speed let go, px/s: the pill's frame starts at it. */
                          flingPx: Float = 0f) {
        val view = views.player ?: return
        // A switch cut short goes on from what it was drawing, at its speed - the super island's
        // folme.to from the current value and velocity. Restarted from rest, the pill lost its
        // speed at every tap mid-switch, and an island shrinking into the small place (its
        // stand-in) jumped to a still circle there, or the pill grew out of that circle.
        val prev = swap
        val prevPillVel = prev?.takeIf { !it.holdPill }?.let { velocityOf(it.pillFrom, it.pillTo, it.spring) }
        val ghosting = prev?.takeIf { it.smallMode == SMALL_FROM_GHOST && it.ghostFrom != null &&
            it.ghostKey != null && !it.small.atRest() }
        val ghostNow = ghosting?.let(::ghostSmallBox)
        val ghostVel = ghosting?.let { velocityOf(it.ghostFrom!!, views.smallBoxOnScreen(), it.small) }
        // Still the small island's, it keeps its stand-in and its spring; decided below, once the
        // new switch's own stand-ins are known - there is one of each.
        val keepGhost = ghosting != null && rowState.smallKey == ghosting.ghostKey && !holdSmall &&
            kind != SWAP_NEXT && !intoSmall && !(kind == SWAP_PREV && oldSmall != null && oldSmall != rowState.smallKey)
        endSwap(keepGhosts = keepGhost)
        rowHost.endRow()
        rowHost.resetIslandDrag()
        views.clearSmallNudge()
        if (kind == SWAP_STACK) view.clearNudge()
        // A flight's morph is the flight's: the pill still switches under it.
        if (view.visibility != View.VISIBLE || rowState.morph != null && flightState.flight == null) {
            if (keepGhost) endGhosts()
            return
        }
        // Where the old big island was, before position() lays the pill out for the new row.
        val oldRest = view.restBoxOnScreen()
        val oldSmallBox = views.smallBoxOnScreen()
        val oldSmallRest = views.smallRest.copyOf()
        // The pill's size for the new row, as position() is about to lay it out. position() sets
        // the row's spring going toward it; the switch has the frame, so the spring stops again -
        // left running, it drew over the switch every frame and the pill went straight from its
        // old width to its new one instead of growing out of the small island (filmed 2026-09-25).
        rowHost.position()
        rowHost.endRow()
        val rest = view.restBoxOnScreen() ?: return
        val d = shortcutDiscs.discDiameter().toFloat()
        val fromSmall = oldSmall != null && rowState.selectedIsland == oldSmall
        // The stand-in shrinking into the small place is the one growing into the pill now:
        // it grows from where it has got to.
        val fromGhost = fromSmall && ghostNow != null && rowState.selectedIsland == ghosting?.ghostKey
        val from = when {
            pillStays -> pillFrom ?: rest
            fromGhost -> ghostNow!!
            views.stackedStyle() -> if (fromSmall) oldSmallBox else rest
            // Out of hiding, the super island's HiddenToBigIsland: out of the middle.
            kind == SWAP_PREV -> cutoutBox(rest)
            fromSmall -> oldSmallBox
            else -> CoverMorphMotion.Box(rest.x, rest.y, d, rest.h)
        }
        val fromVel = when {
            pillStays && pillFrom != null -> prevPillVel
            fromGhost -> ghostVel
            else -> null
        }
        // Growing out of the small island's own circle with nothing to take its place: the circle
        // is the pill from here, not something left behind to shrink away under it.
        if (fromSmall && rowState.smallKey == null) views.setSmallShown(false, animate = false)
        // The pill held for a flight landing on it shows nothing of the island leaving it: that
        // island goes to the small place as a stand-in of itself, content and all, and the
        // small island takes over from it there. Formed out of the pill's place as a bare
        // circle, the island's picture came in only at the end - the music vanished from the
        // pill the moment the row was pulled and came back beside it (filmed 2026-09-25).
        val ghostToSmall = intoSmall && oldBig != null && rowState.smallKey == oldBig && oldRest != null
        val mode = when {
            kind == SWAP_STACK -> SMALL_STACK
            keepGhost -> SMALL_FROM_GHOST
            rowState.smallKey == null || holdSmall -> SMALL_KEPT
            ghostToSmall -> SMALL_FROM_GHOST
            rowState.smallKey == oldBig -> SMALL_FROM_PILL
            rowState.smallKey != oldSmall -> if (kind == SWAP_NEXT) SMALL_EMERGE else SMALL_POP
            else -> SMALL_KEPT
        }
        val s = Swap(from, rest, mode, kind, holdPill, pillKept)
        s.started = android.os.SystemClock.uptimeMillis()
        fromVel?.let { s.spring.velocity = springVelocity(it, from, rest) }
        if (kind == SWAP_STACK && flingPx > 0f) {
            s.spring.velocity = (flingPx / rest.h.coerceAtLeast(1f)).coerceAtMost(2f)
        } else if (fromVel == null && flingPx > 0f) {
            // The finger's speed as the frame's: its moving edge goes on at it - growing
            // or narrowing, whichever the switch does, the same way the finger went.
            val dx = rest.cx() - from.cx()
            val dw = rest.w - from.w
            s.spring.velocity = springVelocity(floatArrayOf(
                kotlin.math.sign(dx) * flingPx / 2f, kotlin.math.sign(dw) * flingPx), from, rest)
        }
        if (keepGhost) {
            val g = ghosting!!
            s.ghostFrom = g.ghostFrom
            s.ghostSmall = g.ghostSmall
            s.ghostKey = g.ghostKey
            s.ghostSecond = g.ghostSecond
            s.small.value = g.small.value
            s.small.velocity = g.small.velocity
            if (g.disc) {
                s.disc = true
                s.ghostDisc.value = g.ghostDisc.value
                s.ghostDisc.velocity = g.ghostDisc.velocity
            }
        }
        swap = s
        if (kind == SWAP_STACK && oldBig != null &&
            startGhost(oldBig, pillFrom ?: oldRest ?: rest)) {
            s.ghostFrom = pillFrom ?: oldRest ?: rest
            s.ghost = true
        }
        // The old big island into hiding (BigIslandToHidden): a stand-in of it, under the
        // row, shrinks into the middle while its content goes out of focus.
        if (kind == SWAP_NEXT && !pillKept && !ghostToSmall && oldRest != null && oldBig != null &&
            startGhost(oldBig, oldRest)) {
            s.ghostFrom = oldRest
            s.ghost = true
        }
        if (ghostToSmall && startGhost(oldBig!!, oldRest!!)) {
            s.ghostFrom = oldRest
            s.ghostSmall = true
            s.ghostKey = oldBig
            // Two buttons in the big island: it had them if it was alone there.
            s.ghostSecond = if (oldSmall == null) 1f else 0f
        }
        // The old small island into hiding (SmallIslandToHidden): a stand-in of it, where it is.
        // Also when the island leaving the pill takes the small place from it: with three
        // islands, the small one was simply replaced there, gone in a frame (2026-09-25).
        if ((kind == SWAP_PREV || ghostToSmall) && oldSmall != null && oldSmall != rowState.smallKey &&
            startGhostDisc(oldSmall)) {
            s.disc = true
            s.discFromSide = smallFromSide
            if (rowState.smallKey == null) {
                // The first island left alone: the pill widens over the place the small island
                // had, which goes into hiding there - its stand-in where it was, the island itself
                // off at once rather than shrinking away a second time under it.
                ghostDisc?.let {
                    it.translationX = oldSmallRest[0] - it.layoutParams.width / 2f - it.left
                    it.translationY = oldSmallRest[1] - it.layoutParams.height / 2f - it.top
                }
                views.setSmallShown(false, animate = false)
            }
        }
        view.beginMorph(layoutOnly = true)
        if (mode != SMALL_KEPT) {
            // The switch has the small island now: whatever it was growing into, it stops.
            edits.smallGrowing = false
            Gen.unframe(views.smallGrowFrame)
            views.smallGrow.value = 1f
            views.smallGrow.target = 1f
            views.smallGrow.velocity = 0f
            views.smallIsland?.let { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
            views.widenSmallIsland(rest)
        }
        applySwap(s)
        Gen.frame(swapFrame)
    }

    /** A box moving from [a] to [b] on [spring], in pixels a second: its centre's x and its width. */
    private fun velocityOf(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box, spring: Jelly): FloatArray =
        floatArrayOf(spring.velocity * (b.cx() - a.cx()), spring.velocity * (b.w - a.w))

    /**
     * [v] (velocityOf) as a switch spring's own speed from [from] to [to]: read off whichever
     * of the two moves further, and kept within what a tap mid-switch could have given it.
     */
    private fun springVelocity(v: FloatArray, from: CoverMorphMotion.Box, to: CoverMorphMotion.Box): Float {
        val dx = to.cx() - from.cx()
        val dw = to.w - from.w
        val span = if (kotlin.math.abs(dw) >= kotlin.math.abs(dx)) dw else dx
        if (kotlin.math.abs(span) < views.dp(4f)) return 0f
        val px = if (span == dw) v[1] else v[0]
        return (px / span).coerceIn(-SWAP_CARRY_MAX, SWAP_CARRY_MAX)
    }

    /** The camera cutout's stand-in: where an island hides, a small capsule mid-row. */
    fun cutoutBox(rest: CoverMorphMotion.Box): CoverMorphMotion.Box {
        val side = rest.h * CUTOUT_SHARE
        return CoverMorphMotion.Box(rest.cx() - side / 2f, rest.cy() - side / 2f, side, side)
    }

    private fun swapBlurPx() = views.dp(SWAP_BLUR_DP).toFloat()

    private fun applySwap(s: Swap) {
        val view = views.player ?: return
        if (s.kind == SWAP_STACK) {
            applyStackSwap(s, view)
            return
        }
        val p = s.spring.value
        val grown = lerpBox(s.pillFrom, s.pillTo, p)
        // Soft as the row's spring is: thinner while it widens fast, a touch taller coming back
        // off its overshoot (applyRow). The widening speed in pixels a second.
        val widening = s.spring.velocity * (s.pillTo.w - s.pillFrom.w)
        val squash = (widening / s.pillTo.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = grown.h * (1f - squash)
        val box = CoverMorphMotion.Box(grown.x, grown.y + (grown.h - h) / 2f, maxOf(grown.w, h), h)
        if (flightState.pillLandingBox != null || s.holdPill) {
            // The pill is under a flight landing on it, or waiting for a card to: that has its frame.
        } else if (s.pillKept) {
            view.setMorphFrame(box, box.h / 2f, 1f)
            view.setContentAlpha(1f)
        } else if (s.kind == SWAP_PREV || views.stackedStyle()) {
            view.setMorphFrame(box, box.h / 2f, 1f)
            // Out of the middle: the content comes into focus as it arrives.
            val c = if (s.kind == SWAP_PREV) s.contentIn.value.coerceIn(0f, 1f)
                else MiniCardMorph.smooth(0f, 1f, p)
            view.setContentAlpha(if (views.stackedStyle()) 1f else c)
            view.setContentBlur((1f - c) * swapBlurPx())
        } else {
            view.setMorphFrame(box, box.h / 2f, 1f)
            view.setContentAlpha(MiniCardMorph.smooth(0.35f, 0.9f, p))
        }
        applyGhost(s)
        applyGhostDisc(s)
        if (s.smallMode == SMALL_KEPT) return
        val small = views.smallIsland?.takeIf { it.visibility == View.VISIBLE && rowState.smallKey != null } ?: return
        val d = shortcutDiscs.discDiameter().toFloat()
        val targetH = views.smallRestHeight().toFloat()
        val rearAlpha = if (views.stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        val targetW = views.smallRestWidth().toFloat()
        val q = s.small.value
        if (s.smallMode == SMALL_FROM_GHOST) {
            // Under the stand-in, in its very shape, and in its place once it is there - through
            // the overshoot the stand-in has faded out of. Standing as a still circle under it,
            // the small island hid the overshoot and the landing had no give (2026-09-25).
            val box = ghostSmallBox(s)
            if (box != null) {
                val xy = IntArray(2).also(views.host::getLocationOnScreen)
                small.setShape(box.w.roundToInt().coerceAtLeast(1), box.h.roundToInt().coerceAtLeast(1),
                    (box.cx() - xy[0] - views.smallRest[0]).roundToInt())
            } else small.setShape(targetW.roundToInt(), targetH.roundToInt(), 0)
            small.setIconAlpha(1f)
            small.alpha = MiniCardMorph.smooth(GHOST_HANDOFF, 1f, q) * rearAlpha
            return
        }
        if (s.smallMode == SMALL_EMERGE) {
            // Out from under the pill's end: from 0.6 of its size, sliding out to its place.
            val width = lerp(targetW * HIDDEN_SCALE, targetW, q).coerceAtLeast(1f).roundToInt()
            val height = lerp(targetH * HIDDEN_SCALE, targetH, q).coerceAtLeast(1f).roundToInt()
            small.setShape(width, height, 0)
            small.setIconAlpha(MiniCardMorph.smooth(0.15f, 0.7f, q))
            small.alpha = MiniCardMorph.smooth(0f, 0.12f, q) * rearAlpha
            smallDx = lerp(emergeDx(), 0f, q)
            rowHost.followShortcuts()
            return
        }
        if (s.smallMode == SMALL_FROM_PILL) {
            val p = q
            // The pill's old island shrinking into the small island's place, under the new one.
            val xy = IntArray(2).also(views.host::getLocationOnScreen)
            val restCx = s.pillTo.cx() - xy[0]
            val w = lerp(s.pillTo.w, targetW, p).coerceAtLeast(d * 0.5f)
            val cx = lerp(restCx, views.smallRest[0], p)
            small.setShape(w.roundToInt(),
                lerp(s.pillFrom.h, targetH, p).roundToInt(),
                (cx - views.smallRest[0]).roundToInt())
            small.setIconAlpha(MiniCardMorph.smooth(0.55f, 1f, p))
            small.alpha = lerp(1f, rearAlpha, p)
        } else {
            // A new small island coming up in its own place.
            val p = q
            val width = lerp(targetW * 0.4f, targetW, p).coerceAtLeast(1f).roundToInt()
            val height = lerp(targetH * 0.4f, targetH, p).coerceAtLeast(1f).roundToInt()
            small.setShape(width, height, 0)
            small.setIconAlpha(MiniCardMorph.smooth(0.3f, 1f, p))
            small.alpha = MiniCardMorph.smooth(0f, 0.5f, p) * rearAlpha
        }
    }

    /** Both cards follow the same spring; the departing front crosses behind while faded. */
    private fun applyStackSwap(s: Swap, view: MiniPlayerView) {
        val p = s.spring.value
        val q = p.coerceIn(0f, 1f)
        val front = lerpBox(s.pillFrom, s.pillTo, p)
        view.setMorphFrame(front, front.h / 2f, 1f)
        view.setContentAlpha(1f)
        view.setContentBlur(kotlin.math.sin(Math.PI.toFloat() * q) * swapBlurPx())
        view.alpha = lerp(MiniPlayerGeometry.STACK_BACK_ALPHA, 1f, q)
        val rear = views.smallBoxOnScreen()
        val from = s.ghostFrom
        if (from != null) ghostPill?.let { ghost ->
            val shrunk = lerpBox(from, rear, p)
            val drop = kotlin.math.sin(Math.PI.toFloat() * q) * s.pillTo.h * 0.75f
            val box = CoverMorphMotion.Box(shrunk.x, shrunk.y + drop, shrunk.w, shrunk.h)
            ghost.setMorphFrame(box, box.h / 2f, 1f)
            // Changing draw order with zero opacity avoids a visible jump at the crossing.
            ghost.translationZ = if (q < 0.5f) view.translationZ + views.dp(1f) else 0f
            ghost.alpha = if (q < 0.5f) 1f - MiniCardMorph.smooth(0.15f, 0.45f, q)
                else MiniPlayerGeometry.STACK_BACK_ALPHA *
                    MiniCardMorph.smooth(0.55f, 0.8f, q) *
                    (1f - MiniCardMorph.smooth(GHOST_HANDOFF, 1f, q))
        }
        views.smallIsland?.let { small ->
            small.setShape(rear.w.roundToInt(), rear.h.roundToInt(), 0)
            small.setIconAlpha(1f)
            small.alpha = MiniPlayerGeometry.STACK_BACK_ALPHA *
                MiniCardMorph.smooth(GHOST_HANDOFF, 1f, q)
        }
    }

    /**
     * [keepGhosts]: a switch taking over this one's stand-ins where they are (startSwap), so
     * they are not put away for the frame between.
     */
    fun endSwap(keepGhosts: Boolean = false) {
        val s = swap ?: return
        swap = null
        Gen.unframe(swapFrame)
        views.player?.let {
            // A card morph that has taken the pill since keeps its frame: handed back to rest
            // under it, the pill stood bare in its place with the morph still running - its
            // content cleared by the morph, its glass whole (2026-09-25, `op mini` scene trace).
            // One landing on it has its frame, and hands it back itself (endPillLanding).
            if ((rowState.morph == null || flightState.flight != null) && flightState.pillLandingBox == null) it.endMorph()
            it.setContentAlpha(1f)
            it.setContentBlur(0f)
            if (s.kind == SWAP_STACK) it.alpha = 1f
        }
        if (!keepGhosts) endGhosts()
        if (smallDx != 0f) {
            smallDx = 0f
            rowHost.followShortcuts()
        }
        // A small island the switch left alone may be mid-grow on its own spring: not cut short.
        if (s.smallMode != SMALL_KEPT) views.restoreSmallIslandShape()
        rowHost.schedulePosition()
        flightState.pendingExpand?.let { key ->
            flightState.pendingExpand = null
            if (rowState.selectedIsland == key) noteMorphMotion.expandNote(key)
        }
    }
    // ---- the islands going into hiding

    /** A stand-in for the big island a switch sends into hiding: a pill of its own, under the row. */
    var ghostPill: MiniPlayerView? = null

    /** A stand-in for the small island a switch sends into hiding. */
    var ghostDisc: ShortcutDisc? = null

    /** The small island's offset from its place while it slides out from under the pill. */
    var smallDx = 0f

    /** Where an emerging small island starts: its centre under the pill's end. */
    private fun emergeDx(): Float {
        val d = shortcutDiscs.discDiameter().toFloat()
        return -(d * 0.8f + views.dp(MiniPlayerGeometry.DISC_GAP_DP))
    }

    /** Lowest in the row: under the small island, which is under the pill. */
    private fun ghostIndex(): Int {
        val small = views.smallIsland?.let { views.host.indexOfChild(it) }?.takeIf { it >= 0 }
        val pill = views.player?.let { views.host.indexOfChild(it) }?.takeIf { it >= 0 }
        return small ?: pill ?: views.lockScreenLayerIndex()
    }

    private fun startGhost(key: String, from: CoverMorphMotion.Box): Boolean {
        val view = ghostPill ?: MiniPlayerView(views.context).also {
            ghostPill = it
            views.host.addView(it, ghostIndex(), ViewGroup.LayoutParams(1, 1))
            Gen.ownView(views.host, it)
        }
        val w = from.w.roundToInt().coerceAtLeast(1)
        val h = from.h.roundToInt().coerceAtLeast(1)
        if (view.layoutParams.width != w || view.layoutParams.height != h) {
            view.layoutParams = view.layoutParams.apply { width = w; height = h }
        }
        val music = rowState.controller?.takeIf(sessionSource::isUsable)
        val note = if (key == MUSIC_ISLAND) null
            else LockIslands.notes.firstOrNull { it.key == key } ?: LockIslands.noteFor(key)
        when {
            key == MUSIC_ISLAND && music != null -> binding.bindMusic(view, music, rowState.config)
            note != null -> binding.bindNote(view, note, rowState.config)
            else -> return false
        }
        view.setInteractionsEnabled(false)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        // Laid out now, as the flight is: new, it showed an empty frame until the next pass.
        if (!view.isLaidOut || view.width != w || view.height != h) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        view.beginMorph(layoutOnly = true)
        // Under the small island and the pill: the super island's hidden layer is the lowest.
        view.translationZ = 0f
        view.setMorphFrame(from, from.h / 2f, 1f)
        return true
    }

    /**
     * The island leaving the pill for the small place, this frame: CHANGE_EASE carries it past
     * the circle and back, and it squeezes with its speed as the pill widening does (applySwap)
     * - a touch taller while it narrows fast.
     */
    fun ghostSmallBox(s: Swap): CoverMorphMotion.Box? {
        val from = s.ghostFrom ?: return null
        val to = views.smallBoxOnScreen()
        val g = s.small.value
        val shrunk = lerpBox(from, to, g)
        val narrowing = s.small.velocity * (to.w - from.w)
        val squash = (narrowing / from.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = shrunk.h * (1f - squash)
        return CoverMorphMotion.Box(shrunk.x, shrunk.y + (shrunk.h - h) / 2f, shrunk.w, h)
    }

    private fun applyGhost(s: Swap) {
        if (s.ghostSmall) {
            // BigIslandToSmallIsland, on CHANGE_EASE: the island's own pill shrinks into the
            // small island's circle, its lines going first and its picture staying.
            val view = ghostPill ?: return
            val g = s.small.value
            val box = ghostSmallBox(s) ?: return
            view.setMorphFrame(box, box.h / 2f, 1f)
            // Its second button, there while it was the big island, goes as it shrinks - with
            // the text making room back, the middle island's way (MiniPlayerView.applySecond).
            // Made without it, the stand-in dropped the button the moment the switch began: the
            // stopwatch's left button gone in a frame as the cover was pulled down (2026-09-26).
            view.setSecondProgress(s.ghostSecond * (1f - MiniCardMorph.smooth(0f, 0.5f, g)))
            view.setTextAlpha(if (views.stackedStyle()) 1f else 1f - MiniCardMorph.smooth(0f, 0.5f, g))
            if (views.stackedStyle()) view.setContentBlur(kotlin.math.sin(Math.PI.toFloat() * g.coerceIn(0f, 1f)) * swapBlurPx())
            view.alpha = 1f - MiniCardMorph.smooth(GHOST_HANDOFF, 1f, g)
            return
        }
        if (!s.ghost) return
        val view = ghostPill ?: return
        val from = s.ghostFrom ?: return
        val g = s.ghostBox.value
        val box = lerpBox(from, cutoutBox(s.pillTo), g)
        view.setMorphFrame(box, box.h / 2f, 1f - MiniCardMorph.smooth(0.35f, 0.9f, g))
        val a = s.ghostContent.value.coerceIn(0f, 1f)
        view.setContentAlpha(1f - a)
        view.setContentBlur(a * swapBlurPx())
    }

    private fun startGhostDisc(key: String): Boolean {
        val d = shortcutDiscs.discDiameter()
        val width = views.smallRestWidth()
        val frame = maxOf(shortcutDiscs.discFrame(d), width + views.dp(16f))
        val frameHeight = shortcutDiscs.discFrame(d)
        val disc = ghostDisc ?: ShortcutDisc(views.context).also {
            ghostDisc = it
            views.host.addView(it, ghostIndex(), ViewGroup.LayoutParams(frame, frameHeight))
            Gen.ownView(views.host, it)
        }
        if (disc.layoutParams.width != frame || disc.layoutParams.height != frameHeight) {
            disc.layoutParams = disc.layoutParams.apply { this.width = frame; height = frameHeight }
        }
        disc.dress(views.discMaterialGeneration()) {
            MiniPlayerRuntime.material(it, views.loader, views.notificationMaterial())
        }
        val picture: Any? = if (key == MUSIC_ISLAND) (binding.thumbShown ?: rowState.cachedCover)
            else LockIslands.notes.firstOrNull { it.key == key }?.icon ?: LockIslands.noteFor(key)?.icon
        if (views.stackedStyle()) disc.copyCardTextFrom(views.smallIsland)
        else disc.setCardText(null, null)
        disc.setIconBare(key != MUSIC_ISLAND)
        disc.setIcon(when (picture) {
            is Bitmap -> android.graphics.drawable.BitmapDrawable(views.context.resources, picture)
            is android.graphics.drawable.Drawable -> picture
            else -> null
        })
        disc.setIconHidden(false)
        disc.setShape(width, views.smallRestHeight())
        disc.setIconAlpha(1f)
        disc.alpha = if (views.stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        disc.translationX = views.smallRest[0] - frame / 2f - disc.left
        disc.translationY = views.smallRest[1] - frameHeight / 2f - disc.top
        disc.visibility = View.VISIBLE
        return true
    }

    private fun applyGhostDisc(s: Swap) {
        if (!s.disc) return
        val disc = ghostDisc ?: return
        val d = views.smallRestHeight().toFloat()
        val w = views.smallRestWidth().toFloat()
        val h = s.ghostDisc.value.coerceIn(0f, 1f)
        val start = if (s.discFromSide > 0f) s.discFromSide else w
        val width = lerp(start, w * HIDDEN_SCALE, h).coerceAtLeast(1f).roundToInt()
        val height = lerp(d, d * HIDDEN_SCALE, h).coerceAtLeast(1f).roundToInt()
        disc.setShape(width, height)
        disc.alpha = (1f - h) *
            if (views.stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
    }

    private fun endGhosts() {
        ghostPill?.let {
            it.endMorph()
            it.setContentBlur(0f)
            it.setContentAlpha(1f)
            it.alpha = 1f
            it.translationZ = 0f
            it.visibility = View.GONE
        }
        ghostDisc?.visibility = View.GONE
    }
}
