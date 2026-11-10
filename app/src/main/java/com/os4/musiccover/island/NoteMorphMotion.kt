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
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1), and given only
 * what it uses of the controller (M2, MiniPlayerParts.kt). See the section comments below for
 * what it is.
 */
internal class NoteMorphMotion(
    private val views: RowViews,
    private val rowState: RowState,
    private val edits: RowEdits,
    private val rowHost: RowHost,
    private val flightState: FlightState,
    private val motions: Motions,
    // Made before this part.
    private val sessionSource: SessionSource,
    private val swapMotion: SwapMotion,
    private val groupMotion: GroupMotion,
    private val cardExchange: CardExchange,
    private val stackHost: StackHost,
    private val spreadMotion: SpreadMotion,
    // Made after this part: read while a notification morphs, not when this is built.
    private val noteDragRef: () -> NoteDragMotion,
    private val discsRef: () -> ShortcutDiscs,
    private val bindingRef: () -> MiniPlayerBinding,
) {
    private val noteDragMotion get() = noteDragRef()
    private val shortcutDiscs get() = discsRef()
    private val binding get() = bindingRef()

    // ------------------------------------------------------------ notifications out to their rows and back

    /**
     * Busy for a notification island out to its row: the motions that keep it from starting (M0,
     * the subset as it was).
     */
    private fun busyForExpandNote(): Boolean = flightState.noteMorphKey != null || rowState.morph != null || motions.exchanging

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
        if (spreadMotion.spread != null) return
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            if (!spreadMotion.openSingleApp("tap")) spreadMotion.openSpread("tap")
            return
        }
        // Pulled down and on its way home still: a tap on its place - its logical place, the
        // pill or the small island it is landing in - turns it round from where it is, as a tap
        // turns a switch (the super island's canClick waits for nothing). It used to do nothing
        // until the flight had landed.
        if (flightState.noteMorphKey == key && flightState.flight != null && rowState.morph?.toNative == false) {
            noteDragMotion.noteDrag = null
            noteDragMotion.releaseNote(true, 0f)
            MiniPlayerRuntime.noteTouch("open: ${key.takeLast(6)} turned out on its way home")
            return
        }
        if (busyForExpandNote()) return
        // Every layout shares one expanded card: the previous island returns as this one opens.
        if (rowState.expandedKey()?.let { it != key } == true) {
            cardExchange.startExchange(key)
            return
        }
        val view = views.player
        if (view == null || view.visibility != View.VISIBLE) {
            if (key != MUSIC_ISLAND) LockIslands.release(key)
            return
        }
        val fromSmall = rowState.selectedIsland != key
        // Out of the pill as out of the small island: a flight of its own, so the island beside
        // it can take the pill the moment it leaves. Going as the pill itself, the pill was the
        // morph's until it had landed on the row, and only then did the next island take the
        // pill - in one jump, from the small island's circle to the whole pill (filmed 2026-09-25).
        if (fromSmall && key != rowState.smallKey || prepareFlight(key) == null) {
            if (key != MUSIC_ISLAND) LockIslands.release(key)
            return
        }
        swapMotion.endSwap()
        // Opened, the stack island leads with its newest notification, the one it shows.
        if (key == STACK_ISLAND) edits.stackLeadKey = null
        else if (LockIslands.isAppGroup(key)) edits.appLeadKeys.remove(key)
        flightState.noteMorphKey = key
        flightState.flightFromSmall = fromSmall
        flightState.flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
        flightState.flightOut = false
        stackHost.letOut(key)
        // The list asked for now, while the rows are still being laid out: the card flies
        // straight to its place in the list. Asked once the morph had begun, it landed in the
        // pile first and the list opened after (2026-09-26).
        if (key == STACK_ISLAND) stackHost.showStackAsList("open")
        else if (LockIslands.isAppGroup(key)) NumState.goTo("LIST", why = "app group open")
        flightState.rowWaitKey = key
        flightState.rowWaitSince = android.os.SystemClock.uptimeMillis()
        flightState.appListAsked = false
        Gen.frame(rowWait)
    } finally { android.os.Trace.endSection() } }

    /** Frame by frame until the released notification's row is in the stack and laid out. */
    val rowWait = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC rowWait"); try {
            val key = flightState.rowWaitKey ?: return
            // The stack island's other rows stay out of sight, piled, till the morph moves them.
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) groupMotion.pileGroup(key, 0f, null)
            val row = rowState.nativeFor(key)
            // The stack fades a returned row in on its own: seen before the morph took it, it
            // stood there whole, went, and came back as the morph's end (filmed 2026-09-25).
            if (row != null) {
                stackHost.hideRow(row)
                if (LockIslands.isAppGroup(key) && !flightState.appListAsked && row.isLaidOut) {
                    flightState.appListAsked = true
                    NumState.goTo("LIST", why = "app group rows ready")
                }
            }
            val flightReady = flightState.flight?.let { it.isLaidOut && it.width > 1 } ?: true
            if (row != null && row.isAttachedToWindow && row.isLaidOut && row.height > 0 &&
                row.width > 0 && flightReady && stackHost.rowOnScreen(row)) {
                flightState.rowWaitKey = null
                startNoteMorph(key, row, toRow = true)
                return
            }
            if (android.os.SystemClock.uptimeMillis() - flightState.rowWaitSince > ROW_WAIT_MS) {
                // No row to land on in time - none laid out, or one the stack keeps out of
                // sight: folded into its count, or faded out. Opened without a morph, the
                // island went into a stack that did not show it, and was simply gone. It
                // comes back into the row instead, where it was.
                val why = when {
                    row == null -> "no row"
                    !stackHost.rowOnScreen(row) -> "row hidden: " + stackHost.offScreenWhy(row)
                    else -> "row not laid out"
                }
                Xp.log("MCIsland: $key not opened: $why")
                MiniPlayerRuntime.noteTouch("open $why")
                flightState.rowWaitKey = null
                noteDragMotion.noteDrag = null
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
    private fun busyForCollapseRow(): Boolean = flightState.noteMorphKey != null || rowState.morph != null || motions.exchanging

    /**
     * A swipe down on a notification's row that came out of the row of islands: it goes back in,
     * the row becoming the pill, as the media card folds back into it. It comes in as the
     * flight and lands on the pill's place; only then does the pill take it, so nothing in the
     * pill jumps while the row is still on its way.
     */
    fun collapseRow(key: String, startY: Float): Boolean { android.os.Trace.beginSection("MC collapseRow"); try {
        // Out as the list, a pull down is the stack's own, which takes the whole row home.
        if (spreadMotion.spread != null) return false
        if (busyForCollapseRow()) {
            MiniPlayerRuntime.noteTouch("collapse refused: noteMorph=${flightState.noteMorphKey != null} " +
                "morph=${rowState.morph != null} exchange=${motions.exchanging}")
            return false
        }
        val row = rowState.nativeFor(key) ?: run {
            MiniPlayerRuntime.noteTouch("collapse refused: no row")
            return false
        }
        // Nothing left in the row - the only island was let out - and the notification coming
        // back is all of it: the pill. It used to just go back in there with no motion at all.
        val rowEmpty = views.player?.visibility != View.VISIBLE
        val flight = prepareFlight(key) ?: run {
            if (rowEmpty) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                stackHost.takeIn(key)
                return true
            }
            MiniPlayerRuntime.noteTouch("collapse refused: no flight")
            return false
        }
        swapMotion.endSwap()
        flightState.noteMorphKey = key
        flightState.flightFromSmall = false
        flightState.flightOut = false
        // Back where it came out of: the pill it was in, or the small island. The music, out
        // as its card since before any pull, comes back into the pill - the super island's
        // expanded island folds into its big one.
        flightState.flightHome = if (rowEmpty || key in flightState.releasedFromPill ||
            key == MUSIC_ISLAND && key !in flightState.releasedFromSmall) HOME_PILL else HOME_SMALL
        MiniPlayerRuntime.noteTouch("collapse home=${if (flightState.flightHome == HOME_PILL) "pill" else "small"} " +
            "empty=$rowEmpty")
        if (rowEmpty) {
            // The row comes back with this notification in the pill - out of sight under the
            // flight until it lands there.
            edits.selectedIsland = key
            rowHost.refresh()
            rowHost.position()
            if (views.player?.visibility != View.VISIBLE || views.player?.restBoxOnScreen() == null) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                flightState.noteMorphKey = null
                noteDragMotion.noteDrag = null
                dropFlight()
                stackHost.takeIn(key)
                rowHost.refresh()
                return true
            }
        } else if (flightState.flightHome == HOME_PILL) {
            // The pill is its again: whatever took it goes back to being the small island,
            // forming out of the pill's place while the row comes down onto it.
            val oldBig = rowState.selectedIsland
            val oldSmall = rowState.smallKey
            edits.selectedIsland = key
            rowHost.refresh()
            swapMotion.startSwap(oldBig, oldSmall, intoSmall = true)
        }
        noteDragMotion.noteDrag = NoteDragMotion.NoteDrag(key, startY, opening = false,
            MiniPlayerRuntime.dragThresholdPx(views.density()), MiniPlayerRuntime.dragNudgePx(views.density()))
        // The row laid out with this notification as its small island: the pill gives it room
        // on its spring while the row comes down, as the super island's big island does.
        rowHost.refresh()
        rowHost.position()
        rowHost.trace("collapse target=${views.smallBoxOnScreen().cx().toInt()} " + rowState.smallState())
        if (flight.isLaidOut && flight.width > 1) return startNoteMorph(key, row, toRow = false)
        // Laid out on the next frame at the earliest; the morph waits for it.
        flight.post { if (flightState.noteMorphKey == key && rowState.morph == null) startNoteMorph(key, row, toRow = false) }
        return true
    } finally { android.os.Trace.endSection() } }

    /** The pill's place is a flight's - out of it and not let go, or coming home to it. */
    internal fun pillHeld(): Boolean = flightState.flight != null && flightState.flightHome == HOME_PILL &&
        rowState.selectedIsland == flightState.noteMorphKey && flightState.pillLandingBox == null &&
        // Pulled up, the pill itself follows the finger until the row is there to morph into.
        (rowState.morph != null || flightState.rowWaitKey == null)

    /**
     * The flight has left its home for good ([out]) or is headed back to it. Home is the small
     * island's place: the next island comes up there, or the flight's shrinks away for it. Home
     * is the pill: the island beside it takes the pill, growing out of its circle on the super
     * island's CHANGE_EASE (SmallIslandToBigIsland); coming back, that island shrinks into the
     * small place again (BigIslandToSmallIsland) while the flight lands on the pill.
     */
    fun setFlightOut(out: Boolean) {
        val key = flightState.noteMorphKey
        if (flightState.flight == null || key == null || out == flightState.flightOut) return
        flightState.flightOut = out
        if (flightState.flightHome != HOME_PILL) {
            if (out) {
                flightState.releasedFromSmall += key
                flightState.releasedFromPill -= key
            }
            rowHost.refresh()
            return
        }
        if (out) {
            flightState.releasedFromPill += key
            flightState.releasedFromSmall -= key
            val oldSmall = rowState.smallKey
            if (rowState.selectedIsland == key) {
                edits.selectedIsland = oldSmall ?: rowState.islandKeys.firstOrNull { it != key }
                rowHost.refresh()
                swapMotion.startSwap(key, oldSmall)
            } else rowHost.refresh()
        } else if (rowState.selectedIsland != key) {
            val oldBig = rowState.selectedIsland
            val oldSmall = rowState.smallKey
            edits.selectedIsland = key
            rowHost.refresh()
            swapMotion.startSwap(oldBig, oldSmall, intoSmall = true)
        }
    }

    fun prepareFlight(key: String): MiniPlayerView? { android.os.Trace.beginSection("MC prepareFlight"); try {
        val music = if (key == MUSIC_ISLAND) rowState.controller?.takeIf(sessionSource::isUsable) ?: return null else null
        val note = if (music == null) LockIslands.noteFor(key) ?: return null else null
        val pill = views.player ?: return null
        // One kept from an earlier flight or switch when there is one: made new, a flight cost
        // 16ms on the touch that starts it - its material put on, its lines laid out for the
        // first time (traced 2026-09-25).
        val view = flightState.flight ?: views.takeSpare()?.also { flightState.flight = it } ?: MiniPlayerView(views.context).also {
            flightState.flight = it
            // Under the pill in the host; a morph lifts it above everything with translationZ.
            views.host.addView(it, views.lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
            Gen.ownView(views.host, it)
        }
        val w = pill.layoutParams.width.coerceAtLeast(1)
        val h = pill.layoutParams.height.coerceAtLeast(1)
        if (view.layoutParams.width != w || view.layoutParams.height != h) {
            view.layoutParams = view.layoutParams.apply { width = w; height = h }
        }
        traced("MC pf.bind") { if (music != null) binding.bindMusic(view, music, rowState.config) else binding.bindNote(view, note!!, rowState.config) }
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
        flightState.flight?.let(views::recycleSmallView)
        flightState.flight = null
        flightState.flightFromSmall = false
        flightState.flightHome = HOME_SMALL
        flightState.flightOut = false
        flightState.flightLanding = false
        endPillLanding()
        if (flightState.landingBox != null) {
            flightState.landingBox = null
            views.smallIsland?.iconAtStart = false
            views.restoreSmallIslandShape()
        }
    }

    /** Where an island's picture, title and text land on a notification's row, and its corners. */
    internal fun rowLanding(row: View) = MiniCardMorph.Landing(
        // A focus template's own picture before any icon: searched in one pass, the header's
        // small app icon came first in the tree and the picture flew to it. From PR #15.
        stackHost.findNamed(row, FOCUS_ART_NAMES) { it.width > 0 && it.height > 0 }
            ?: stackHost.findNamed(row, ICON_NAMES) { it is ImageView },
        stackHost.findNamed(row, TITLE_NAMES) { it is TextView },
        stackHost.findNamed(row, TEXT_NAMES) { it is TextView },
        Main.notificationRowRadius(row),
        views.dp(8f).toFloat(),
    )

    /** Where [key]'s island lands on its card: the media card's own elements, or its row's. */
    fun landingFor(key: String, native: View) =
        if (key == MUSIC_ISLAND) MiniCardMorph.Landing.mediaCard(native) else rowLanding(native)

    internal fun startNoteMorph(key: String, row: View, toRow: Boolean): Boolean {
        val useFlight = flightState.flight != null
        val view = (if (useFlight) flightState.flight else views.player) ?: return false
        val landing = landingFor(key, row)
        // The flight's end, both ways: its home - the small island's circle, or the pill.
        val home = flightState.flightHome
        val restBox: (() -> CoverMorphMotion.Box?)? = when {
            !useFlight -> null
            home == HOME_PILL -> { { views.player?.restBoxOnScreen() } }
            else -> { { views.smallBoxOnScreen() } }
        }
        view.alpha = if (useFlight && home == HOME_SMALL && views.stackedStyle())
            MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        if (!useFlight) rowHost.endRow()
        // A focus notification's second button as the pill has it at the flight's home end: the
        // big island's. The flight never set it and went without - out of the pill the button
        // vanished at once, and landing back the pill grew it in anew: a blink each way (2026-09-25).
        if (useFlight) view.setSecondShown(home == HOME_PILL && rowState.smallKey == null, animate = false)
        // Kept out of sight while it waited (rowWait); the morph saves the row's own alpha
        // and fades it in from there, in this same frame.
        stackHost.showRow(row)
        // Opening, it goes to where the stack is settling its card, rows on their way out gone.
        if (toRow) {
            stackHost.unpinCard()
            }
        val next = MiniCardMorph(view, row, toRow, noteMorphListener(key), landing, restBox,
            circle = useFlight && home == HOME_SMALL && !views.stackedStyle(),
            nativeDy = if (toRow) { { stackHost.settleDy(row, emptyList()) } } else null)
        edits.morph = next
        noteDragMotion.noteSpan = noteDragMotion.noteDragSpan(row, useFlight && home == HOME_SMALL)
        val drag = noteDragMotion.noteDrag?.takeIf { it.key == key }
        if (useFlight && toRow && home == HOME_SMALL) {
            // The flight is the small island from here: one hands over to the other in place.
            views.smallIsland?.visibility = View.GONE
            edits.smallGrowing = false
            Gen.unframe(views.smallGrowFrame)
        }
        if (drag != null) drag.span = noteDragMotion.noteSpan
        // The island's own nudge is the morph's from here, as the pill's is handed to the card's.
        if (drag != null && drag.opening) {
            if (flightState.flightFromSmall) views.clearSmallNudge() else views.player?.clearNudge()
        }
        val started = if (drag != null) next.startDragging() else next.start()
        if (started && toRow && key == STACK_ISLAND) stackHost.showStackAsList("open")
        if (!started) {
            edits.morph = null
            flightState.noteMorphKey = null
            noteDragMotion.noteDrag = null
            dropFlight()
            if (!toRow) stackHost.takeIn(key)
            rowHost.refresh()
            return false
        }
        if (drag != null) {
            if (drag.ended) {
                // The finger already let go, committed: it opens from where the pull left it.
                noteDragMotion.noteDrag = null
                val (p, nudge) = noteDragMotion.notePose(drag)
                next.drag(p, nudge, drag.nudgeX)
                next.release(drag.toRow, noteDragMotion.noteVelocity(drag))
                if (!drag.toRow) stackHost.takeInEarly(key)
            } else noteDragMotion.applyNoteDrag()
        }
        // The small place, the pill's width and the small island's presence follow the flight:
        // freed (the next island comes up, or the pill takes the room back) or held (what is
        // there shrinks away for the flight coming in). A tap, or a pull already let go past
        // the threshold, is gone for good and frees its home at once.
        if (useFlight) {
            rowHost.refresh()
            if (toRow && (drag == null || drag.ended && drag.toRow)) setFlightOut(true)
        }
        // The pill it left goes out of sight this frame, not the next.
        if (home == HOME_PILL) rowHost.followShortcuts()
        return true
    }

    private fun noteMorphListener(key: String) = object : MiniCardMorph.Listener {
        /** The music out of a scene lands once the cover has let go of the lock screen. */
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) =
            key != MUSIC_ISLAND || toNative || !rowState.morphScene || !Main.coverSceneActive()

        override fun artBridged() = key == MUSIC_ISLAND && rowState.artBridged

        override fun onFrame(morph: MiniCardMorph, progress: Float) {
            if ((key == STACK_ISLAND || LockIslands.isAppGroup(key)) &&
                rowState.morph === morph) groupMotion.pileGroup(key, progress, morph.miniEndBox())
            val f = flightState.flight ?: return
            if (rowState.morph !== morph) return
            val stackedSmall = views.stackedStyle() && flightState.flightHome == HOME_SMALL
            val stackedAlpha = MiniPlayerGeometry.STACK_BACK_ALPHA +
                (1f - MiniPlayerGeometry.STACK_BACK_ALPHA) * progress
            if (flightState.flightOut) {
                if (stackedSmall) f.alpha = stackedAlpha
                return
            }
            if (progress > 0f) restFrames = 0 else restFrames++
            if (!morph.toNative && restFrames <= 10) morph.containerBox()?.let { b ->
                rowHost.trace("f c=${"%.3f".format(progress)} box=${b.cx().toInt()},${b.w.toInt()}x${b.h.toInt()} " +
                    "fa=${"%.2f".format(f.alpha)} land=${flightState.flightLanding} " + rowState.smallState())
            }
            val landing = !morph.toNative && progress < FLIGHT_HANDOFF
            if (landing != flightState.flightLanding) {
                flightState.flightLanding = landing
                // Shown at once, whole, with its own picture: it is what the flight becomes.
                if (landing) edits.snapSmallOnce = true
                rowHost.scheduleRefresh()
            }
            val box = if (landing) morph.containerBox() else null
            if (flightState.flightHome == HOME_PILL) {
                if (box != null) landOnPill(box) else endPillLanding()
            } else if (box != null) landOn(box)
            else if (flightState.landingBox != null) {
                // Turned back out: the small island goes back to waiting in its place.
                flightState.landingBox = null
                views.smallIsland?.iconAtStart = false
                views.restoreSmallIslandShape()
            }
            val a = if (landing) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, progress)
                else if (stackedSmall) stackedAlpha else 1f
            if (kotlin.math.abs(f.alpha - a) > 0.002f) f.alpha = a
        }
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            stackHost.flushTurn(key)
            rowHost.trace("settled toRow=$toNative completed=$completed " +
                (if (completed) "" else "why=${morph.lastCancel} ") + rowState.smallState())
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) groupMotion.endPile(toNative)
            if (key == MUSIC_ISLAND) edits.morphScene = false
            if (rowState.morph === morph) edits.morph = null
            noteDragMotion.noteDrag = null
            val flew = flightState.flight != null
            val home = flightState.flightHome
            val oldSmall = rowState.smallKey
            flightState.noteMorphKey = null
            flightState.flightOut = false
            dropFlight()
            if (toNative) {
                // Out in the stack. From the small island the next one has already come up in
                // its place (updateSmallIsland); from the pill, the pill takes the next island,
                // growing back out of its end, and the small island stays as it is unless the
                // pill took it.
                // A flight - out of the small island, or a collapse let go back to its row -
                // leaves the pill as it is.
                if (!flew) edits.selectedIsland = null
                rowHost.refresh()
                if (!flew) swapMotion.startSwap(null, oldSmall)
                // Held where it landed until the stack has it there too.
                rowState.nativeFor(key)?.let { stackHost.pinCard(it, emptyList()) }
            } else {
                // Back in the row as the small island it landed as: the stack lets go of its row.
                // The morph has just put the row's alpha back, and the stack takes most of a
                // second to animate a removed row away: it stood there again, whole, after the
                // flight had landed (filmed 2026-09-25). Hidden until it is gone.
                // The music's card is put away by the pill being chosen again (updateVisibility).
                if (key != MUSIC_ISLAND) {
                    stackHost.rowFor(key)?.let { stackHost.hideRowUntilGone(it, key) }
                    // The stack gives it back a run of the pipeline later: until then the row
                    // keeps its place, or the small island showed the next one for a frame or two
                    // and then grew this one anew - the icon blinked on landing (2026-09-25).
                    stackHost.markReturning(key)
                }
                stackHost.takeIn(key)
                stackHost.letGo(key)
                flightState.releasedFromPill -= key
                flightState.releasedFromSmall -= key
                if (flew && home == HOME_SMALL) {
                    edits.preferredSmall = key
                    edits.snapSmallOnce = true
                } else edits.selectedIsland = key
                rowHost.refresh()
            }
        }
    }

    /**
     * The pill under a flight landing on it: shown, with the notification it is taking back,
     * in the flight's shape frame by frame - the overshoot included - while the flight fades
     * off it, as the small island takes a flight landing on its circle.
     */
    internal fun landOnPill(box: CoverMorphMotion.Box) {
        val view = views.player ?: return
        val first = flightState.pillLandingBox == null
        flightState.pillLandingBox = box
        if (!view.inMorph()) view.beginMorph(layoutOnly = true)
        view.setMorphFrame(box, box.h / 2f, 1f)
        // Whole on every frame it is landed on, not just the first: whatever was bringing its
        // content in while it was held (the switch that gave it back) is not done with it yet.
        view.setContentAlpha(1f)
        view.setContentBlur(0f)
        // Its second button as the flight landing on it has it: there, not growing in anew.
        if (first) view.setSecondShown(rowState.smallKey == null, animate = false)
        if (first) rowHost.followShortcuts()
    }

    /** The flight has landed, or turned back: the pill's frame is its own again. */
    internal fun endPillLanding() {
        if (flightState.pillLandingBox == null) return
        flightState.pillLandingBox = null
        // Anything else sizing the pill goes on from here; nothing else, its rest frame.
        if (swapMotion.swap == null && !rowState.rowAnimating) views.player?.endMorph()
        rowHost.followShortcuts()
    }

    /** The small island takes the flight's shape this frame: room for it, then placed on it. */
    internal fun landOn(box: CoverMorphMotion.Box) {
        val small = views.smallIsland ?: return
        val pad = views.dp(16f)
        val w = maxOf(shortcutDiscs.discFrame(shortcutDiscs.discDiameter()), (box.w + pad).roundToInt())
        val h = maxOf(shortcutDiscs.discFrame(shortcutDiscs.discDiameter()), (box.h + pad).roundToInt())
        // Only ever widened while landing: the shape shrinks inside the room it was given.
        if (flightState.landingBox == null || small.layoutParams.width < w || small.layoutParams.height < h) {
            views.resizeSmallIsland(small, maxOf(small.layoutParams.width, w),
                maxOf(small.layoutParams.height, h))
            edits.smallWide = true
        }
        flightState.landingBox = box
        small.iconAtStart = true
        views.placeSmallIsland(null, small.transitionAlpha)
    }

    /** A notification that set out and did not open: back in the row as it was. */
    fun abandonNoteMorph(key: String) {
        stackHost.flushTurn(key)
        if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) groupMotion.endPile(toRow = false)
        // Whatever the finger had pulled goes home.
        views.player?.springNudgeBack(0f, 0f)
        views.springSmallNudgeBack(0f, 0f)
        if (key != MUSIC_ISLAND) stackHost.rowFor(key)?.let { stackHost.hideRowUntilGone(it, key) }
        else views.transitionHeader()?.let(stackHost::showRow)
        flightState.rowWaitKey = null
        flightState.noteMorphKey = null
        dropFlight()
        stackHost.takeIn(key)
        stackHost.letGo(key)
        rowHost.refresh()
    }

    /** Frames the running notification morph has spent at its island's end. */
    // Moved here from the controller, its only user (M2).
    internal var restFrames = 0
}
