package com.os4.musiccover.island

import com.os4.musiccover.CoverMorphMotion
import com.os4.musiccover.Main
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1); `c` is the
 * controller. See the section comments below for what it is.
 */
internal class NoteMorphMotion(private val c: MiniPlayerController) {

    // ------------------------------------------------------------ notifications out to their rows and back

    /**
     * The notification island on its way out into its row in the stack, or back in from it. The
     * pill keeps showing it until the morph lands, whatever the row's islands have become.
     */
    var noteMorphKey: String? = null

    /** A small island tapped: it takes the pill first, and opens once it has. */
    var pendingExpand: String? = null

    var rowWaitKey: String? = null
    var rowWaitSince = 0L
    var appListAsked = false

    /**
     * Busy for a notification island out to its row: the motions that keep it from starting (M0,
     * the subset as it was).
     */
    private fun busyForExpandNote(): Boolean = noteMorphKey != null || c.morph != null || c.exchange != null

    /**
     * A tap on a notification island opens it into its own row, as the music island opens into
     * the media card: the stack is given the notification back, and once its row is laid out
     * the island becomes that row - its picture, title and text landing on the row's own.
     *
     * The pill's own island goes out as the pill. A small island goes out as itself: a second
     * pill, the flight, bound to its notification, starts as the small island's circle and grows
     * straight into the row, the pill beside it never changing. The first version switched the
     * small island into the pill and opened it from there - two motions where one was asked for.
     */
    fun expandNote(key: String) { android.os.Trace.beginSection("MC expandNote"); try {
        // Folded natively (LockIslands.nativeStack), the stack island opens as the stack's own
        // list, ColorOS's way: the island leaves the row and the rows come out of the fold where
        // they are. No flight: the rows were never out of the stack for one to land on.
        if (c.spreadMotion.spread != null) return
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            if (!c.spreadMotion.openSingleApp("tap")) c.spreadMotion.openSpread("tap")
            return
        }
        // Pulled down and on its way home still: a tap on its place - its logical place, the
        // pill or the small island it is landing in - turns it round from where it is, as a tap
        // turns a switch (the super island's canClick waits for nothing). It used to do nothing
        // until the flight had landed.
        if (noteMorphKey == key && flight != null && c.morph?.toNative == false) {
            c.noteDragMotion.noteDrag = null
            c.noteDragMotion.releaseNote(true, 0f)
            MiniPlayerRuntime.noteTouch("open: ${key.takeLast(6)} turned out on its way home")
            return
        }
        if (busyForExpandNote()) return
        // Every layout shares one expanded card: the previous island returns as this one opens.
        if (c.expandedKey()?.let { it != key } == true) {
            c.startExchange(key)
            return
        }
        val view = c.player
        if (view == null || view.visibility != View.VISIBLE) {
            if (key != MUSIC_ISLAND) LockIslands.release(key)
            return
        }
        val fromSmall = c.selectedIsland != key
        // Out of the pill as out of the small island: a flight of its own, so the island beside
        // it can take the pill the moment it leaves. Going as the pill itself, the pill was the
        // morph's until it had landed on the row, and only then did the next island take the
        // pill - in one jump, from the small island's circle to the whole pill (filmed 2026-09-25).
        if (fromSmall && key != c.smallKey || prepareFlight(key) == null) {
            if (key != MUSIC_ISLAND) LockIslands.release(key)
            return
        }
        c.swapMotion.endSwap()
        // Opened, the stack island leads with its newest notification, the one it shows.
        if (key == STACK_ISLAND) c.stackLeadKey = null
        else if (LockIslands.isAppGroup(key)) c.appLeadKeys.remove(key)
        noteMorphKey = key
        flightFromSmall = fromSmall
        flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
        c.flightOut = false
        c.letOut(key)
        // The list asked for now, while the rows are still being laid out: the card flies
        // straight to its place in the list. Asked once the morph had begun, it landed in the
        // pile first and the list opened after (2026-09-26).
        if (key == STACK_ISLAND) c.showStackAsList("open")
        else if (LockIslands.isAppGroup(key)) NumState.goTo("LIST", why = "app group open")
        rowWaitKey = key
        rowWaitSince = android.os.SystemClock.uptimeMillis()
        appListAsked = false
        Gen.frame(rowWait)
    } finally { android.os.Trace.endSection() } }

    /** Frame by frame until the released notification's row is in the stack and laid out. */
    val rowWait = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC rowWait"); try {
            val key = rowWaitKey ?: return
            // The stack island's other rows stay out of sight, piled, till the morph moves them.
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) c.groupMotion.pileGroup(key, 0f, null)
            val row = c.nativeFor(key)
            // The stack fades a returned row in on its own: seen before the morph took it, it
            // stood there whole, went, and came back as the morph's end (filmed 2026-09-25).
            if (row != null) {
                c.hideRow(row)
                if (LockIslands.isAppGroup(key) && !appListAsked && row.isLaidOut) {
                    appListAsked = true
                    NumState.goTo("LIST", why = "app group rows ready")
                }
            }
            val flightReady = flight?.let { it.isLaidOut && it.width > 1 } ?: true
            if (row != null && row.isAttachedToWindow && row.isLaidOut && row.height > 0 &&
                row.width > 0 && flightReady && c.rowOnScreen(row)) {
                rowWaitKey = null
                startNoteMorph(key, row, toRow = true)
                return
            }
            if (android.os.SystemClock.uptimeMillis() - rowWaitSince > ROW_WAIT_MS) {
                // No row to land on in time - none laid out, or one the stack keeps out of
                // sight: folded into its count, or faded out. Opened without a morph, the
                // island went into a stack that did not show it, and was simply gone. It
                // comes back into the row instead, where it was.
                val why = when {
                    row == null -> "no row"
                    !c.rowOnScreen(row) -> "row hidden: " + c.offScreenWhy(row)
                    else -> "row not laid out"
                }
                Xp.log("MCIsland: $key not opened: $why")
                MiniPlayerRuntime.noteTouch("open $why")
                rowWaitKey = null
                c.noteDragMotion.noteDrag = null
                abandonNoteMorph(key)
                return
            }
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * Busy for a row pulled back into its island (a spread is asked first, on its own): the motions
     * that keep it from starting (M0, the subset as it was).
     */
    private fun busyForCollapseRow(): Boolean = noteMorphKey != null || c.morph != null || c.exchange != null

    /**
     * A swipe down on a notification's row that came out of the row of islands: it goes back in,
     * the row becoming the pill, as the media card folds back into it. It comes in as the
     * flight and lands on the pill's place; only then does the pill take it, so nothing in the
     * pill jumps while the row is still on its way.
     */
    fun collapseRow(key: String, startY: Float): Boolean { android.os.Trace.beginSection("MC collapseRow"); try {
        // Out as the list, a pull down is the stack's own, which takes the whole row home.
        if (c.spreadMotion.spread != null) return false
        if (busyForCollapseRow()) {
            MiniPlayerRuntime.noteTouch("collapse refused: noteMorph=${noteMorphKey != null} " +
                "morph=${c.morph != null} exchange=${c.exchange != null}")
            return false
        }
        val row = c.nativeFor(key) ?: run {
            MiniPlayerRuntime.noteTouch("collapse refused: no row")
            return false
        }
        // Nothing left in the row - the only island was let out - and the notification coming
        // back is all of it: the pill. It used to just go back in there with no motion at all.
        val rowEmpty = c.player?.visibility != View.VISIBLE
        val flight = prepareFlight(key) ?: run {
            if (rowEmpty) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                c.takeIn(key)
                return true
            }
            MiniPlayerRuntime.noteTouch("collapse refused: no flight")
            return false
        }
        c.swapMotion.endSwap()
        noteMorphKey = key
        flightFromSmall = false
        c.flightOut = false
        // Back where it came out of: the pill it was in, or the small island. The music, out
        // as its card since before any pull, comes back into the pill - the super island's
        // expanded island folds into its big one.
        flightHome = if (rowEmpty || key in releasedFromPill ||
            key == MUSIC_ISLAND && key !in releasedFromSmall) HOME_PILL else HOME_SMALL
        MiniPlayerRuntime.noteTouch("collapse home=${if (flightHome == HOME_PILL) "pill" else "small"} " +
            "empty=$rowEmpty")
        if (rowEmpty) {
            // The row comes back with this notification in the pill - out of sight under the
            // flight until it lands there.
            c.selectedIsland = key
            c.refresh()
            c.position()
            if (c.player?.visibility != View.VISIBLE || c.player?.restBoxOnScreen() == null) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                noteMorphKey = null
                c.noteDragMotion.noteDrag = null
                dropFlight()
                c.takeIn(key)
                c.refresh()
                return true
            }
        } else if (flightHome == HOME_PILL) {
            // The pill is its again: whatever took it goes back to being the small island,
            // forming out of the pill's place while the row comes down onto it.
            val oldBig = c.selectedIsland
            val oldSmall = c.smallKey
            c.selectedIsland = key
            c.refresh()
            c.swapMotion.startSwap(oldBig, oldSmall, intoSmall = true)
        }
        c.noteDragMotion.noteDrag = NoteDragMotion.NoteDrag(key, startY, opening = false,
            MiniPlayerRuntime.dragThresholdPx(c.density()), MiniPlayerRuntime.dragNudgePx(c.density()))
        // The row laid out with this notification as its small island: the pill gives it room
        // on its spring while the row comes down, as the super island's big island does.
        c.refresh()
        c.position()
        c.trace("collapse target=${c.smallBoxOnScreen().cx().toInt()} " + c.smallState())
        if (flight.isLaidOut && flight.width > 1) return startNoteMorph(key, row, toRow = false)
        // Laid out on the next frame at the earliest; the morph waits for it.
        flight.post { if (noteMorphKey == key && c.morph == null) startNoteMorph(key, row, toRow = false) }
        return true
    } finally { android.os.Trace.endSection() } }

    /** The flight: a pill of its own, bound to [key]'s notification, laid out at the pill's size. */
    var flight: MiniPlayerView? = null
    var flightFromSmall = false

    /** Where the flight's island lives when it is in the row: the pill, or the small island. */
    var flightHome = HOME_SMALL

    /** Notifications let out of the pill itself: pulled down, their row goes back there. */
    val releasedFromPill = HashSet<String>()

    /** ...and islands let out of the small place, for the music, which otherwise goes to the pill. */
    val releasedFromSmall = HashSet<String>()

    /** The pill under a flight landing on it, taking the flight's shape; null otherwise. */
    var pillLandingBox: CoverMorphMotion.Box? = null

    /** The pill's place is a flight's - out of it and not let go, or coming home to it. */
    internal fun pillHeld(): Boolean = flight != null && flightHome == HOME_PILL &&
        c.selectedIsland == noteMorphKey && pillLandingBox == null &&
        // Pulled up, the pill itself follows the finger until the row is there to morph into.
        (c.morph != null || rowWaitKey == null)

    /**
     * The flight has left its home for good ([out]) or is headed back to it. Home is the small
     * island's place: the next island comes up there, or the flight's shrinks away for it. Home
     * is the pill: the island beside it takes the pill, growing out of its circle on the super
     * island's CHANGE_EASE (SmallIslandToBigIsland); coming back, that island shrinks into the
     * small place again (BigIslandToSmallIsland) while the flight lands on the pill.
     */
    fun setFlightOut(out: Boolean) {
        val key = noteMorphKey
        if (flight == null || key == null || out == c.flightOut) return
        c.flightOut = out
        if (flightHome != HOME_PILL) {
            if (out) {
                releasedFromSmall += key
                releasedFromPill -= key
            }
            c.refresh()
            return
        }
        if (out) {
            releasedFromPill += key
            releasedFromSmall -= key
            val oldSmall = c.smallKey
            if (c.selectedIsland == key) {
                c.selectedIsland = oldSmall ?: c.islandKeys.firstOrNull { it != key }
                c.refresh()
                c.swapMotion.startSwap(key, oldSmall)
            } else c.refresh()
        } else if (c.selectedIsland != key) {
            val oldBig = c.selectedIsland
            val oldSmall = c.smallKey
            c.selectedIsland = key
            c.refresh()
            c.swapMotion.startSwap(oldBig, oldSmall, intoSmall = true)
        }
    }

    fun prepareFlight(key: String): MiniPlayerView? { android.os.Trace.beginSection("MC prepareFlight"); try {
        val music = if (key == MUSIC_ISLAND) c.controller?.takeIf(c::isUsable) ?: return null else null
        val note = if (music == null) LockIslands.noteFor(key) ?: return null else null
        val pill = c.player ?: return null
        // One kept from an earlier flight or switch when there is one: made new, a flight cost
        // 16ms on the touch that starts it - its material put on, its lines laid out for the
        // first time (traced 2026-09-25).
        val view = flight ?: c.takeSpare()?.also { flight = it } ?: MiniPlayerView(c.context).also {
            flight = it
            // Under the pill in the host; a morph lifts it above everything with translationZ.
            c.host.addView(it, c.lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
            Gen.ownView(c.host, it)
        }
        val w = pill.layoutParams.width.coerceAtLeast(1)
        val h = pill.layoutParams.height.coerceAtLeast(1)
        if (view.layoutParams.width != w || view.layoutParams.height != h) {
            view.layoutParams = view.layoutParams.apply { width = w; height = h }
        }
        traced("MC pf.bind") { if (music != null) c.bindMusic(view, music, c.config) else c.bindNote(view, note!!, c.config) }
        // Laid out here and now, not on the next pass: a flight is a new view each time, and
        // its morph waited for that pass - the row stood still under a finger already pulling
        // it down, and the flight then jumped to where the finger had got to (filmed 2026-09-25).
        if (!view.isLaidOut || view.isLayoutRequested || view.width != w || view.height != h) {
            traced("MC pf.layout") {
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
            }
        }
        // Out of sight until its morph puts it where it starts.
        view.visibility = View.VISIBLE
        view.alpha = 0f
        return view
    } finally { android.os.Trace.endSection() } }

    internal fun dropFlight() {
        flight?.let(c::recycleSmallView)
        flight = null
        flightFromSmall = false
        flightHome = HOME_SMALL
        c.flightOut = false
        c.flightLanding = false
        endPillLanding()
        if (c.landingBox != null) {
            c.landingBox = null
            c.smallIsland?.iconAtStart = false
            c.restoreSmallIslandShape()
        }
    }

    /** Where an island's picture, title and text land on a notification's row, and its corners. */
    internal fun rowLanding(row: View) = MiniCardMorph.Landing(
        // A focus template's own picture before any icon: searched in one pass, the header's
        // small app icon came first in the tree and the picture flew to it. From PR #15.
        c.findNamed(row, FOCUS_ART_NAMES) { it.width > 0 && it.height > 0 }
            ?: c.findNamed(row, ICON_NAMES) { it is ImageView },
        c.findNamed(row, TITLE_NAMES) { it is TextView },
        c.findNamed(row, TEXT_NAMES) { it is TextView },
        Main.notificationRowRadius(row),
        c.dp(8f).toFloat(),
    )

    /** Where [key]'s island lands on its card: the media card's own elements, or its row's. */
    fun landingFor(key: String, native: View) =
        if (key == MUSIC_ISLAND) MiniCardMorph.Landing.mediaCard(native) else rowLanding(native)

    internal fun startNoteMorph(key: String, row: View, toRow: Boolean): Boolean {
        val useFlight = flight != null
        val view = (if (useFlight) flight else c.player) ?: return false
        val landing = landingFor(key, row)
        // The flight's end, both ways: its home - the small island's circle, or the pill.
        val home = flightHome
        val restBox: (() -> CoverMorphMotion.Box?)? = when {
            !useFlight -> null
            home == HOME_PILL -> { { c.player?.restBoxOnScreen() } }
            else -> { { c.smallBoxOnScreen() } }
        }
        view.alpha = if (useFlight && home == HOME_SMALL && c.stackedStyle())
            MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        if (!useFlight) c.endRow()
        // A focus notification's second button as the pill has it at the flight's home end: the
        // big island's. The flight never set it and went without - out of the pill the button
        // vanished at once, and landing back the pill grew it in anew: a blink each way (2026-09-25).
        if (useFlight) view.setSecondShown(home == HOME_PILL && c.smallKey == null, animate = false)
        // Kept out of sight while it waited (rowWait); the morph saves the row's own alpha
        // and fades it in from there, in this same frame.
        c.showRow(row)
        // Opening, it goes to where the stack is settling its card, rows on their way out gone.
        if (toRow) {
            c.unpinCard()
            }
        val next = MiniCardMorph(view, row, toRow, noteMorphListener(key), landing, restBox,
            circle = useFlight && home == HOME_SMALL && !c.stackedStyle(),
            nativeDy = if (toRow) { { c.settleDy(row, emptyList()) } } else null)
        c.morph = next
        c.noteDragMotion.noteSpan = c.noteDragMotion.noteDragSpan(row, useFlight && home == HOME_SMALL)
        val drag = c.noteDragMotion.noteDrag?.takeIf { it.key == key }
        if (useFlight && toRow && home == HOME_SMALL) {
            // The flight is the small island from here: one hands over to the other in place.
            c.smallIsland?.visibility = View.GONE
            c.smallGrowing = false
            Gen.unframe(c.smallGrowFrame)
        }
        if (drag != null) drag.span = c.noteDragMotion.noteSpan
        // The island's own nudge is the morph's from here, as the pill's is handed to the card's.
        if (drag != null && drag.opening) {
            if (flightFromSmall) c.clearSmallNudge() else c.player?.clearNudge()
        }
        val started = if (drag != null) next.startDragging() else next.start()
        if (started && toRow && key == STACK_ISLAND) c.showStackAsList("open")
        if (!started) {
            c.morph = null
            noteMorphKey = null
            c.noteDragMotion.noteDrag = null
            dropFlight()
            if (!toRow) c.takeIn(key)
            c.refresh()
            return false
        }
        if (drag != null) {
            if (drag.ended) {
                // The finger already let go, committed: it opens from where the pull left it.
                c.noteDragMotion.noteDrag = null
                val (p, nudge) = c.noteDragMotion.notePose(drag)
                next.drag(p, nudge, drag.nudgeX)
                next.release(drag.toRow, c.noteDragMotion.noteVelocity(drag))
                if (!drag.toRow) c.takeInEarly(key)
            } else c.noteDragMotion.applyNoteDrag()
        }
        // The small place, the pill's width and the small island's presence follow the flight:
        // freed (the next island comes up, or the pill takes the room back) or held (what is
        // there shrinks away for the flight coming in). A tap, or a pull already let go past
        // the threshold, is gone for good and frees its home at once.
        if (useFlight) {
            c.refresh()
            if (toRow && (drag == null || drag.ended && drag.toRow)) setFlightOut(true)
        }
        // The pill it left goes out of sight this frame, not the next.
        if (home == HOME_PILL) c.followShortcuts()
        return true
    }

    private fun noteMorphListener(key: String) = object : MiniCardMorph.Listener {
        /** The music out of a scene lands once the cover has let go of the lock screen. */
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) =
            key != MUSIC_ISLAND || toNative || !c.morphScene || !Main.coverSceneActive()

        override fun artBridged() = key == MUSIC_ISLAND && c.artBridged

        override fun onFrame(morph: MiniCardMorph, progress: Float) {
            if ((key == STACK_ISLAND || LockIslands.isAppGroup(key)) &&
                c.morph === morph) c.groupMotion.pileGroup(key, progress, morph.miniEndBox())
            val f = flight ?: return
            if (c.morph !== morph) return
            val stackedSmall = c.stackedStyle() && flightHome == HOME_SMALL
            val stackedAlpha = MiniPlayerGeometry.STACK_BACK_ALPHA +
                (1f - MiniPlayerGeometry.STACK_BACK_ALPHA) * progress
            if (c.flightOut) {
                if (stackedSmall) f.alpha = stackedAlpha
                return
            }
            if (progress > 0f) c.restFrames = 0 else c.restFrames++
            if (!morph.toNative && c.restFrames <= 10) morph.containerBox()?.let { b ->
                c.trace("f c=${"%.3f".format(progress)} box=${b.cx().toInt()},${b.w.toInt()}x${b.h.toInt()} " +
                    "fa=${"%.2f".format(f.alpha)} land=${c.flightLanding} " + c.smallState())
            }
            val landing = !morph.toNative && progress < FLIGHT_HANDOFF
            if (landing != c.flightLanding) {
                c.flightLanding = landing
                // Shown at once, whole, with its own picture: it is what the flight becomes.
                if (landing) c.snapSmallOnce = true
                c.scheduleRefresh()
            }
            val box = if (landing) morph.containerBox() else null
            if (flightHome == HOME_PILL) {
                if (box != null) landOnPill(box) else endPillLanding()
            } else if (box != null) landOn(box)
            else if (c.landingBox != null) {
                // Turned back out: the small island goes back to waiting in its place.
                c.landingBox = null
                c.smallIsland?.iconAtStart = false
                c.restoreSmallIslandShape()
            }
            val a = if (landing) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, progress)
                else if (stackedSmall) stackedAlpha else 1f
            if (kotlin.math.abs(f.alpha - a) > 0.002f) f.alpha = a
        }
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            c.flushTurn(key)
            c.trace("settled toRow=$toNative completed=$completed " +
                (if (completed) "" else "why=${morph.lastCancel} ") + c.smallState())
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) c.groupMotion.endPile(toNative)
            if (key == MUSIC_ISLAND) c.morphScene = false
            if (c.morph === morph) c.morph = null
            c.noteDragMotion.noteDrag = null
            val flew = flight != null
            val home = flightHome
            val oldSmall = c.smallKey
            noteMorphKey = null
            c.flightOut = false
            dropFlight()
            if (toNative) {
                // Out in the stack. From the small island the next one has already come up in
                // its place (updateSmallIsland); from the pill, the pill takes the next island,
                // growing back out of its end, and the small island stays as it is unless the
                // pill took it.
                // A flight - out of the small island, or a collapse let go back to its row -
                // leaves the pill as it is.
                if (!flew) c.selectedIsland = null
                c.refresh()
                if (!flew) c.swapMotion.startSwap(null, oldSmall)
                // Held where it landed until the stack has it there too.
                c.nativeFor(key)?.let { c.pinCard(it, emptyList()) }
            } else {
                // Back in the row as the small island it landed as: the stack lets go of its row.
                // The morph has just put the row's alpha back, and the stack takes most of a
                // second to animate a removed row away: it stood there again, whole, after the
                // flight had landed (filmed 2026-09-25). Hidden until it is gone.
                // The music's card is put away by the pill being chosen again (updateVisibility).
                if (key != MUSIC_ISLAND) {
                    c.rowFor(key)?.let { c.hideRowUntilGone(it, key) }
                    // The stack gives it back a run of the pipeline later: until then the row
                    // keeps its place, or the small island showed the next one for a frame or two
                    // and then grew this one anew - the icon blinked on landing (2026-09-25).
                    c.markReturning(key)
                }
                c.takeIn(key)
                c.letGo(key)
                releasedFromPill -= key
                releasedFromSmall -= key
                if (flew && home == HOME_SMALL) {
                    c.preferredSmall = key
                    c.snapSmallOnce = true
                } else c.selectedIsland = key
                c.refresh()
            }
        }
    }

    /**
     * The pill under a flight landing on it: shown, with the notification it is taking back,
     * in the flight's shape frame by frame - the overshoot included - while the flight fades
     * off it, as the small island takes a flight landing on its circle.
     */
    internal fun landOnPill(box: CoverMorphMotion.Box) {
        val view = c.player ?: return
        val first = pillLandingBox == null
        pillLandingBox = box
        if (!view.inMorph()) view.beginMorph(layoutOnly = true)
        view.setMorphFrame(box, box.h / 2f, 1f)
        // Whole on every frame it is landed on, not just the first: whatever was bringing its
        // content in while it was held (the switch that gave it back) is not done with it yet.
        view.setContentAlpha(1f)
        view.setContentBlur(0f)
        // Its second button as the flight landing on it has it: there, not growing in anew.
        if (first) view.setSecondShown(c.smallKey == null, animate = false)
        if (first) c.followShortcuts()
    }

    /** The flight has landed, or turned back: the pill's frame is its own again. */
    internal fun endPillLanding() {
        if (pillLandingBox == null) return
        pillLandingBox = null
        // Anything else sizing the pill goes on from here; nothing else, its rest frame.
        if (c.swapMotion.swap == null && !c.rowAnimating) c.player?.endMorph()
        c.followShortcuts()
    }

    /** The small island takes the flight's shape this frame: room for it, then placed on it. */
    internal fun landOn(box: CoverMorphMotion.Box) {
        val small = c.smallIsland ?: return
        val pad = c.dp(16f)
        val w = maxOf(c.shortcutDiscs.discFrame(c.shortcutDiscs.discDiameter()), (box.w + pad).roundToInt())
        val h = maxOf(c.shortcutDiscs.discFrame(c.shortcutDiscs.discDiameter()), (box.h + pad).roundToInt())
        // Only ever widened while landing: the shape shrinks inside the room it was given.
        if (c.landingBox == null || small.layoutParams.width < w || small.layoutParams.height < h) {
            c.resizeSmallIsland(small, maxOf(small.layoutParams.width, w),
                maxOf(small.layoutParams.height, h))
            c.smallWide = true
        }
        c.landingBox = box
        small.iconAtStart = true
        c.placeSmallIsland(null, small.transitionAlpha)
    }

    /** A notification that set out and did not open: back in the row as it was. */
    fun abandonNoteMorph(key: String) {
        c.flushTurn(key)
        if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) c.groupMotion.endPile(toRow = false)
        // Whatever the finger had pulled goes home.
        c.player?.springNudgeBack(0f, 0f)
        c.springSmallNudgeBack(0f, 0f)
        if (key != MUSIC_ISLAND) c.rowFor(key)?.let { c.hideRowUntilGone(it, key) }
        else c.transitionHeader()?.let(c::showRow)
        rowWaitKey = null
        noteMorphKey = null
        dropFlight()
        c.takeIn(key)
        c.letGo(key)
        c.refresh()
    }
}
