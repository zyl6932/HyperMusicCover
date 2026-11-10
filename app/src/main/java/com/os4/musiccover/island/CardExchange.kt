package com.os4.musiccover.island

import com.os4.musiccover.CoverMorphMotion
import com.os4.musiccover.Main
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import android.view.Choreographer
import android.view.View
import kotlin.math.abs

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1); `c` is the
 * controller. See the section comments below for what it is.
 */
internal class CardExchange(private val c: MiniPlayerController) {

    /**
     * The super island's expanded switch, as ClickEventCoordinator and its state handlers run it
     * (read from the plugin, 2026-09-25). [up] - in the big place if [fromPill], else the small
     * one - goes out as its card, and [down], out now, comes back in. Tapped in the big place,
     * that place is filled first (BigIslandStateHandler.handleFillInState): the small island
     * moves up into it and the next hidden one into the small place. Tapped in the small place,
     * the next hidden one fills that. Then [down] comes in at the big place
     * (handleReplacedState): if it takes the place from what is there, that one moves down to
     * the small place's turn; else [down] tries the small place itself, and whatever loses that
     * goes into hiding. So the card coming down does not simply land where the tapped island
     * was: with three, the third island can change places with it. With nothing out - the one
     * that was is on its way home already - only the place [up] leaves is filled.
     */
    private fun reseat(up: String, down: String?, fromPill: Boolean): Seats {
        val big0 = c.selectedIsland
        val small0 = c.smallKey
        val keys = c.islandKeys
        val at = keys.indexOf(big0).coerceAtLeast(0)
        // The hidden ones, next first: after the small island round the row.
        val hidden = (keys.drop(at) + keys.take(at))
            .filter { it != big0 && it != small0 && it != up && it != down }.toMutableList()
        var big: String?
        var small: String?
        if (fromPill) {
            big = small0?.takeIf { it != up && it != down } ?: hidden.removeFirstOrNull()
            small = hidden.removeFirstOrNull()
        } else {
            big = big0?.takeIf { it != up && it != down }
            small = hidden.removeFirstOrNull()
        }
        fun intoSmall(key: String) {
            val s = small
            when {
                s == null -> small = key
                c.takesPlace(s, key, big = false) -> { hidden.add(0, s); small = key }
                else -> hidden.add(0, key)
            }
        }
        val b = big
        when {
            down == null -> {}
            b == null -> big = down
            c.takesPlace(b, down, big = true) -> { big = down; intoSmall(b) }
            else -> intoSmall(down)
        }
        return Seats(big, small)
    }
    class Mover(val key: String, val view: MiniPlayerView, val native: View) {
        var morph: MiniCardMorph? = null
        /** Its place in the row, from the seats: where it lands once it is headed home. */
        var place = LAND_PILL
        /** The row end on screen: springs from where it was to a new place, on CHANGE_EASE. */
        var endFrom: CoverMorphMotion.Box? = null
        var endTo: CoverMorphMotion.Box? = null
        val endT = Jelly(SWAP_RESPONSE, SWAP_DAMPING).apply { value = 1f; target = 1f }
        /** 0 a pill, 1 the small island's circle: the row end's shape, on the same spring. */
        val round = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
        var endLast = 0L
        /** Came down out of its card: the card is held where it left from and, turned round, lands there. */
        var fromCard = false
        var cardAt = 0f
        /** The stack translation its card is landing at going up, as its morph last read it. */
        var settleAt = 0f
        /** Its card's end is the cover, not the media card chosen. */
        var cover = false
        /** The place's island has taken over from it on its last stretch home. */
        var landed = false
        /** The place it left the row from, for a pull down to bring it back there. */
        var origin = LAND_HIDDEN
        /** What its last trace line was about (moverFrame). */
        var traced = ""
        /** The stack translation it is held at on its way home; null to take it where it is drawn. */
        var homeAt: Float? = null
        /** The offset its card's end had last frame, so a turn starts from where it is drawn. */
        var lastDy = 0f
        val headedHome: Boolean get() = morph?.toNative == false
    }

    class Switch {
        val movers = LinkedHashMap<String, Mover>()
        /** The island out as its card, or on its way there: the last one tapped. */
        var expanded: String? = null
        /** The row as the last tap reseated it. */
        var seats: Seats? = null
        /** A tapped island whose card is being laid out; nothing moves for it till it is. */
        var pending: String? = null
        var pendingPill = false
        var pendingCover = false
        var waitSince = 0L
        fun has(key: String) = key == expanded || key in movers || key == pending
        fun keys(): List<String> = listOfNotNull(expanded) + movers.keys + listOfNotNull(pending)
        fun homeKeys(): List<String> = movers.values.filter { it.headedHome }.map { it.key }
    }

    var exchange: Switch? = null

    /** The island a place holds is still on its way to it: the place is out of sight till it lands. */
    private fun switchHolds(key: String?): Boolean {
        val m = exchange?.movers?.get(key ?: return false) ?: return false
        return m.headedHome && !m.landed
    }

    internal fun exchangeHoldsPill(): Boolean = exchange?.let { switchHolds(it.seats?.big) } == true

    internal fun exchangeHoldsSmall(): Boolean = exchange?.let { switchHolds(it.seats?.small) } == true

    fun exchangeRunning(): Boolean = exchange != null

    /**
     * Busy for an exchange - one island out as its card, the expanded one home: the motions that
     * keep it from starting (M0, the subset as it was).
     */
    private fun busyForExchange(): Boolean = exchange != null || c.morph != null || c.noteMorphMotion.noteMorphKey != null || c.noteMorphMotion.flight != null || c.groupMotion.group != null

    /** [up] - the pill's island or the small one - goes out in place of the one out now. */
    fun startExchange(up: String, toCover: Boolean = false): Boolean { android.os.Trace.beginSection("MC startExchange"); try {
        val down = c.expandedKey()
        val busy = busyForExchange()
        if (down == null || down == up || busy) {
            MiniPlayerRuntime.noteTouch("exchange refused: out=${down?.takeLast(6)} busy=$busy")
            return false
        }
        val pill = c.player?.takeIf { it.visibility == View.VISIBLE } ?: return false
        val inPill = c.selectedIsland == up
        if (!inPill && c.smallKey != up) return false
        c.swapMotion.endSwap()
        c.endRow()
        c.resetIslandDrag()
        c.clearSmallNudge()
        pill.clearNudge()
        val x = Switch()
        x.expanded = down
        x.seats = Seats(c.selectedIsland, c.smallKey)
        exchange = x
        requestUp(x, up, inPill, toCover && up == MUSIC_ISLAND)
        return true
    } finally { android.os.Trace.endSection() } }

    /**
     * [key] - in the big place if [fromPill], else the small one - asked out as its card. On its
     * way home still, its card is out yet and it turns round now. Else its card is let out, and
     * the row reseats and everything moves once that card is laid out (switchWait): meanwhile the
     * island stays drawn where it is and the rest goes on as it was.
     */
    internal fun requestUp(x: Switch, key: String, fromPill: Boolean, cover: Boolean) {
        if (x.movers[key]?.morph != null) {
            if (!applyUp(x, key, fromPill, cover)) MiniPlayerRuntime.noteTouch("switch $key: no morph")
            return
        }
        // The one out resting in its card is held where it is drawn while the stack makes room
        // for the one coming out: the card the last switch pinned where the stack is settling
        // it, let go, jumped back up to wherever the stack had got to (2026-09-25).
        x.expanded?.takeIf { it !in x.movers }?.let { out ->
            c.nativeFor(out)?.let { native ->
                c.stackHost.freezeCard(native, native.translationY + if (c.stackHost.pinned?.get() === native) c.stackHost.pinnedDy else 0f)
            }
            // Back into the row now, not once it has landed: the stack lays out the card coming
            // out without it, and that card's place is the stack's own.
            c.stackHost.takeInEarly(out)
        }
        x.pending = key
        x.pendingPill = fromPill
        x.pendingCover = cover
        x.waitSince = android.os.SystemClock.uptimeMillis()
        if (cover) {
            // Into the cover the card is shown by the scene, not chosen: out from under the
            // pill's suppression for the morph, and out of sight until the morph takes it.
            c.updateNativeSuppression(false)
            c.transitionHeader()?.let(c.stackHost::hideRow)
        } else {
            c.stackHost.letOut(key)
            if (key == STACK_ISLAND) c.stackHost.showStackAsList("switch")
        }
        c.refresh()
        c.trace("switch ask ${key.takeLast(6)} pill=$fromPill out=${x.expanded?.takeLast(6)} " + c.smallState())
        MiniPlayerRuntime.noteTouch("switch ask ${key.takeLast(6)} pill=$fromPill out=${x.expanded?.takeLast(6)}")
        Gen.unframe(switchWait)
        Gen.frame(switchWait)
    }

    /** Frame by frame until the card asked for is laid out: it has only just been let out. */
    internal val switchWait = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC exchangeWait"); try {
            val x = exchange ?: return
            val key = x.pending ?: return
            val native = traced("MC x.findUp") { c.nativeFor(key) }
            native?.let(c.stackHost::hideRow)
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) c.groupMotion.pileGroup(key, 0f, null)
            val out = x.expanded
            // A row given back early is a transient view: detached and re-attached as one, it has
            // lost its laid-out flag and is never laid out again, and the switch waited for it
            // until it gave up - the stack island's rows gone, nothing opened (2026-09-26).
            val outReady = out == null || out in x.movers || c.nativeFor(out)?.let {
                it.isAttachedToWindow && (it.isLaidOut || c.stackHost.kept.values.any { k -> k.row === it }) && it.width > 0
            } == true
            if (native != null && traced("MC x.ready") { c.groupMotion.rowReady(native) } && outReady) {
                x.pending = null
                if (!traced("MC x.begin") { applyUp(x, key, x.pendingPill, x.pendingCover) }) abandonUp(x, key, "no morph")
                return
            }
            if (android.os.SystemClock.uptimeMillis() - x.waitSince > ROW_WAIT_MS) {
                abandonUp(x, key, when {
                    native == null -> "no card"
                    !outReady -> "card out not ready: " + (out?.let(c::nativeFor)?.let { "attached=${it.isAttachedToWindow} " +
                        "laid=${it.isLaidOut} w=${it.width} kept=${c.stackHost.kept.values.any { k -> k.row === it }}" } ?: "none")
                    else -> "card not on screen: " + c.stackHost.offScreenWhy(native)
                })
                return
            }
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    private fun placeOf(key: String, seats: Seats): Int = when (key) {
        seats.big -> LAND_PILL
        seats.small -> LAND_SMALL
        else -> LAND_HIDDEN
    }

    /** A place's rest frame on screen now; hiding is the super island's cutout, mid-row. */
    private fun placeBoxNow(place: Int): CoverMorphMotion.Box? = when (place) {
        LAND_PILL -> c.player?.restBoxOnScreen()
        LAND_SMALL -> c.smallBoxOnScreen()
        else -> c.player?.restBoxOnScreen()?.let(c.swapMotion::cutoutBox)
    }

    /** [m]'s row end this frame, its end and shape springs stepped by the real gap. */
    private fun moverEnd(m: Mover): CoverMorphMotion.Box? {
        val now = System.nanoTime()
        if (m.endLast != 0L) {
            val dt = ((now - m.endLast) / 1e9f).coerceIn(0f, 0.05f)
            m.endT.step(dt)
            m.round.step(dt)
        }
        m.endLast = now
        val to = m.endTo ?: return m.endFrom
        val from = m.endFrom ?: return to
        return lerpBox(from, to, m.endT.value)
    }

    /**
     * [m]'s row end heads for [place]: from where it is drawn now, on CHANGE_EASE, unless
     * [snap] (a mover just made there). The shape follows on the same spring.
     */
    private fun aimEnd(m: Mover, place: Int, snap: Boolean = false, at: CoverMorphMotion.Box? = null) {
        val current = if (snap) null else moverEnd(m)
        m.place = place
        m.endTo = at ?: placeBoxNow(place)
        m.endFrom = current
        m.endT.value = if (current == null) 1f else 0f
        m.endT.velocity = 0f
        m.endT.target = 1f
        val round = if (place == LAND_HIDDEN || place == LAND_SMALL && !c.stackedStyle()) 1f else 0f
        if (snap) {
            m.round.value = round
            m.round.velocity = 0f
        }
        m.round.target = round
    }

    /**
     * The card's end of [m]'s morph, by the way it is headed now. Up: where the stack puts the
     * card, read live - the ones going home are out of its layout already (takeInEarly). Home:
     * held where it was drawn when it turned. Fixed by the way it was made, a card sent home and
     * turned back up flew to where it had left from, and was pulled up from there to the stack's
     * place for it once it landed (2026-09-26).
     */
    private fun moverDy(x: Switch, m: Mover): Float {
        val native = m.native
        // Asked while its morph is being made, it goes the way it was made.
        val dy = if (m.morph?.toNative ?: !m.fromCard) {
            m.homeAt = null
            c.stackHost.settleDy(native, x.homeKeys()).also { m.settleAt = native.translationY + it }
        } else {
            val at = m.homeAt ?: (native.translationY + m.lastDy).also { m.homeAt = it }
            at - native.translationY
        }
        m.lastDy = dy
        return dy
    }

    /** [m] is not landing in its place after all: the place's island has its own frame back. */
    internal fun unland(m: Mover) {
        if (!m.landed) return
        m.landed = false
        if (m.place == LAND_PILL) c.noteMorphMotion.endPillLanding()
        else if (c.landingBox != null) {
            c.landingBox = null
            c.smallIsland?.iconAtStart = false
            c.restoreSmallIslandShape()
        }
        m.view.alpha = 1f
    }

    private fun moverListener(m: Mover) = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = m.key == MUSIC_ISLAND && c.artBridged
        override fun onFrame(morph: MiniCardMorph, progress: Float) {
            if (m.key == STACK_ISLAND || LockIslands.isAppGroup(m.key))
                c.groupMotion.pileGroup(m.key, progress, morph.miniEndBox())
            moverFrame(m)
        }
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (m.key == STACK_ISLAND || LockIslands.isAppGroup(m.key)) c.groupMotion.endPile(toNative)
            moverSettled(m, toNative, completed)
        }
    }

    /**
     * [key] the one asked out now, the one out till now coming back in: the row reseated, and
     * every island on its way to its new end.
     */
    private fun applyUp(x: Switch, key: String, fromPill: Boolean, cover: Boolean): Boolean {
        // Nothing out: the one that was is on its way home already (adoptNote).
        val out = x.expanded
        val oldBig = c.selectedIsland
        val oldSmall = c.smallKey
        // The pill as drawn now: an island still growing into it is taken from where it has got to.
        val pillDrawn = c.swapMotion.swap?.takeIf { !it.holdPill }?.let { lerpBox(it.pillFrom, it.pillTo, it.spring.value) }
        // The small island asked for, still shrinking into its place as a stand-in of itself (a
        // collapse onto the pill sends the pill's island there so): it goes up from the stand-in,
        // which goes - left, it drew the island a second time under its own way up.
        val ghostDrawn = c.swapMotion.swap?.takeIf { !fromPill && it.smallMode == SMALL_FROM_GHOST && it.ghostKey == key &&
            it.ghostFrom != null }?.let(c.swapMotion::ghostSmallBox)
        if (ghostDrawn != null) c.swapMotion.endSwap()
        val seats = reseat(key, out, fromPill)
        // The one asked for: turned round on its way home, or up out of its place.
        val up = x.movers[key]
        if (up != null) {
            unland(up)
            c.stackHost.turnTo(key, true)
            up.morph?.aim(true)
            if (up.cover) Main.miniPlayerTurnScene(true)
        } else {
            val native = c.nativeFor(key) ?: return false
            val view = c.prepareSmallView(key, pooled = true) ?: return false
            val m = Mover(key, view, native)
            m.origin = if (fromPill) LAND_PILL else LAND_SMALL
            m.cover = cover
            aimEnd(m, m.origin, snap = true, at = if (fromPill) pillDrawn ?: c.player?.restBoxOnScreen() else ghostDrawn)
            // Out of sight while it waited; the morph saves the card's own alpha.
            c.stackHost.showRow(native)
            m.settleAt = native.translationY
            val morph = traced("MC x.upMorph") {
                MiniCardMorph(view, native, true, moverListener(m), c.noteMorphMotion.landingFor(key, native),
                    { moverEnd(m) }, !fromPill, nativeDy = { moverDy(x, m) }, roundness = { m.round.value })
            }
            m.morph = morph
            x.movers[key] = m
            if (!traced("MC x.upStart") { morph.start() }) {
                x.movers.remove(key)
                c.recycleSmallView(view)
                return false
            }
        }
        if (out != null && !sendDown(x, key, out, seats)) return true
        // Everyone else on the way home: to its new place, from where it is.
        for (m in x.movers.values) {
            if (m.key == key || m.key == out || !m.headedHome) continue
            val place = placeOf(m.key, seats)
            if (place != m.place) {
                unland(m)
                aimEnd(m, place)
            }
        }
        x.expanded = key
        x.seats = seats
        seats.big?.let { c.selectedIsland = it }
        c.snapSmallOnce = true
        c.refresh()
        if (key == STACK_ISLAND) c.stackHost.showStackAsList("switched out")
        traced("MC x.seat") { seatAtRest(x, oldBig, oldSmall, pillDrawn) }
        // What comes down into the pill comes down as the pill will be.
        for (m in x.movers.values) if (m.headedHome && m.place == LAND_PILL) c.fitToPill(m.view, seats.small != null)
        traced("MC x.after") {
            orderMovers(x)
            // The music is drawn by its view from here, not by the place it left.
            c.applyArtBridge()
            // A place whose island is on its way to it goes out of sight under it this frame.
            c.updateVisibility()
            c.followShortcuts()
        }
        val plan = "seats=${seats.big?.takeLast(6)}/${seats.small?.takeLast(6)} " +
            x.movers.values.joinToString(" ") { moverState(it) }
        c.trace("switch up=${key.takeLast(6)} down=${out?.takeLast(6)} pill=$fromPill $plan " + c.smallState())
        MiniPlayerRuntime.noteTouch("switch up=${key.takeLast(6)} down=${out?.takeLast(6)} pill=$fromPill $plan")
        return true
    }

    /**
     * [out], the one out till now, on its way to its new place: turned round on its way up, or
     * down out of its card. False when it has no card to come down from: the switch is given up.
     */
    private fun sendDown(x: Switch, key: String, out: String, seats: Seats): Boolean {
        // The one out: turned round on its way up, or down out of its card, to its new place.
        val outPlace = placeOf(out, seats)
        // Into hiding, with no place in the row: the small island pulses (ExpandedToHidden).
        if (outPlace == LAND_HIDDEN) c.startSmallPulse(seats.small)
        val down = x.movers[out]
        if (down != null) {
            down.homeAt = null
            down.morph?.aim(false)
            c.stackHost.turnTo(out, false)
            if (down.cover) Main.miniPlayerTurnScene(false)
            aimEnd(down, outPlace)
        } else {
            val native = c.nativeFor(out)
            val view = native?.let { c.prepareSmallView(out, pooled = true) }
            if (native == null || view == null) {
                view?.let(c::recycleSmallView)
                abandonMover(x, key)
                return false
            }
            val m = Mover(out, view, native)
            m.fromCard = true
            m.cover = out == MUSIC_ISLAND && Main.coverModeOn()
            // Leaves from where it is drawn and stays there: the row going up into the stack moves
            // it on the stack's side (the stack is laid out from the bottom up), and the pin may
            // still be holding it lower than the stack has it.
            val frozen = c.stackHost.pinnedAt?.takeIf { c.stackHost.pinned?.get() === native }
                ?: (native.translationY + if (c.stackHost.pinned?.get() === native) c.stackHost.pinnedDy else 0f)
            if (c.stackHost.pinned?.get() === native) c.stackHost.unpinCard(keep = true)
            m.cardAt = frozen
            m.homeAt = frozen
            aimEnd(m, outPlace, snap = true)
            val morph = traced("MC x.downMorph") {
                MiniCardMorph(view, native, false, moverListener(m), c.noteMorphMotion.landingFor(out, native),
                    { moverEnd(m) }, outPlace != LAND_PILL, nativeDy = { moverDy(x, m) }, roundness = { m.round.value })
            }
            m.morph = morph
            x.movers[out] = m
            if (!traced("MC x.downStart") { morph.start() }) {
                x.movers.remove(out)
                c.recycleSmallView(view)
                abandonMover(x, key)
                return false
            }
            // Made anew on its way home - its last morph had already ended, as a turn a moment
            // after a landing does: back into the row now too, or its rows stayed in the stack
            // and the card coming out was laid out above them (2026-09-26).
            c.stackHost.takeInEarly(out)
            // Out of the cover: the music coming down is the cover's card, and the cover goes with it.
            if (m.cover) Main.miniPlayerLeaveCover()
        }
        return true
    }

    fun moverState(m: Mover): String = "${m.key.takeLast(6)}:" +
        (if (m.morph?.toNative == true) "card" else "p${m.place}") + (if (m.landed) "L" else "") +
        "@${"%.2f".format(m.morph?.progress ?: -1f)}"

    /**
     * The islands at rest in the row take their new places as the movers go (seatOthers of the
     * first version): the small island grows into the big place (SmallIslandToBigIsland); the big
     * one shrinks into the small place as itself (BigIslandToSmallIsland, SMALL_FROM_GHOST), or
     * into hiding with the next one out of it (BigIslandToHidden, HiddenToSmallIsland). A place a
     * mover is on its way to is its: out of sight, and the switch leaves it alone. An island still
     * growing into the pill goes on from where it has got to.
     */
    private fun seatAtRest(x: Switch, oldBig: String?, oldSmall: String?, pillDrawn: CoverMorphMotion.Box?) {
        val seats = x.seats ?: return
        val movers = x.movers
        val holdPill = seats.big != null && seats.big in movers
        val holdSmall = seats.small != null && seats.small in movers
        val bigRest = oldBig?.takeIf { it !in movers }
        val smallRest = oldSmall?.takeIf { it !in movers }
        val bigMoves = !holdPill && seats.big != bigRest
        val intoSmall = bigRest != null && seats.small == bigRest
        val bigHides = bigRest != null && bigRest != seats.big && bigRest != seats.small
        val smallChanges = !holdSmall && seats.small != smallRest
        val stillGrowing = !holdPill && pillDrawn != null
        if (!bigMoves && !intoSmall && !bigHides && !smallChanges && !stillGrowing) return
        c.swapMotion.startSwap(oldBig, if (intoSmall) null else oldSmall, kind = if (bigHides) SWAP_NEXT else SWAP_PAIR,
            intoSmall = intoSmall, holdPill = holdPill, holdSmall = holdSmall,
            pillFrom = if (!bigMoves && !holdPill) pillDrawn else null, pillStays = !bigMoves && !holdPill)
    }

    /**
     * Each in its destination's layer, as the super island stacks its islands' backgrounds - big
     * 3, expanded 2, small 1, hidden 0: one on its way to the big place over everything; one
     * going up to its card over the small island but under an island in the pill; one on its way
     * to the small place or into hiding under both. The pill and the small island are at 0, the
     * stand-ins added under them.
     */
    private fun orderMovers(x: Switch) {
        val pillShown = !exchangeHoldsPill()
        for (m in x.movers.values) {
            val z = when {
                !m.headedHome -> if (pillShown) 0f else 1f
                m.place == LAND_PILL -> 2f
                else -> -1f
            }
            if (m.view.translationZ != z) m.view.translationZ = z
        }
    }

    /** Every frame of [m]: on its last stretch home, its place's island takes over from it. */
    private fun moverFrame(m: Mover) {
        val x = exchange ?: return
        if (x.movers[m.key] !== m) return
        val morph = m.morph ?: return
        // 1 at its card, 0 in its place.
        val p = morph.progress
        val home = !morph.toNative
        // Into hiding, nothing takes over from it: it fades out on the cutout.
        val hiding = home && m.place == LAND_HIDDEN
        val holder = when (m.place) {
            LAND_PILL -> x.seats?.big
            LAND_SMALL -> x.seats?.small
            else -> null
        }
        // Only once its end has got to the place: the place's island is at rest there.
        val landing = home && !hiding && p < FLIGHT_HANDOFF && holder == m.key && m.endT.atRest()
        if (landing != m.landed) {
            if (landing) {
                m.landed = true
                c.snapSmallOnce = true
            } else unland(m)
            c.refresh()
            orderMovers(x)
            c.followShortcuts()
        }
        if (m.landed) morph.containerBox()?.let { if (m.place == LAND_PILL) c.noteMorphMotion.landOnPill(it) else c.noteMorphMotion.landOn(it) }
        val a = if (m.landed || hiding) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, p)
            else if (m.place == LAND_SMALL && c.stackedStyle()) lerp(MiniPlayerGeometry.STACK_BACK_ALPHA, 1f, p)
            else 1f
        if (kotlin.math.abs(m.view.alpha - a) > 0.002f) m.view.alpha = a
        traced("MC xTrace") {
            // Only as it changes: a tenth of the way, a turn, the stack's target moving. Every
            // frame of two movers filled the whole trace with one switch (2026-09-26).
            val b = morph.containerBox()
            val mark = "${m.key}${morph.toNative}${(p * 10).toInt()}|" + c.stackHost.lastStackTarget.toInt() / 3
            if (mark != m.traced) {
                m.traced = mark
                c.trace("x ${moverState(m)} box=${b?.cx()?.toInt()},${b?.y?.toInt()} " +
                    "${b?.w?.toInt()}x${b?.h?.toInt()} end=${"%.2f".format(m.endT.value)} " +
                    "at=${m.cardAt.toInt()} dty=${m.native.translationY.toInt()} " + c.stackHost.lastSettle)
            }
        }
    }

    /** [m] is at an end: out as its card, or home in the row. */
    private fun moverSettled(m: Mover, toNative: Boolean, completed: Boolean) {
        val x = exchange ?: return
        if (x.movers[m.key] !== m) return
        c.stackHost.flushTurn(m.key)
        x.movers.remove(m.key)
        unland(m)
        c.recycleSmallView(m.view)
        val key = m.key
        if (toNative) {
            // Pulled down again, it comes back where it went from.
            when (m.origin) {
                LAND_PILL -> { c.noteMorphMotion.releasedFromPill += key; c.noteMorphMotion.releasedFromSmall -= key }
                LAND_SMALL -> { c.noteMorphMotion.releasedFromSmall += key; c.noteMorphMotion.releasedFromPill -= key }
            }
            // Held where the stack is settling it until the stack has taken the others away.
            // Turned round, the card that came back up lands where it left, the rows that went
            // up on their way back out of the stack under it: held the same (2026-09-25).
            // Where the stack has it: the rows going home are out of its layout already.
            c.stackHost.pinCard(m.native, x.homeKeys())
        } else {
            c.noteMorphMotion.releasedFromPill -= key
            c.noteMorphMotion.releasedFromSmall -= key
            if (key != MUSIC_ISLAND) {
                c.stackHost.rowFor(key)?.let { c.stackHost.hideRowUntilGone(it, key) }
                c.stackHost.markReturning(key)
            } else c.transitionHeader()?.let(c.stackHost::showRow)
            c.stackHost.takeIn(key)
            c.stackHost.letGo(key)
        }
        c.trace("switch settled ${key.takeLast(6)} ${if (toNative) "card" else "home"} completed=$completed " +
            "left=${x.movers.size}" + if (completed) "" else " why=" + m.morph?.lastCancel)
        c.snapSmallOnce = true
        c.refresh()
        orderMovers(x)
        if (x.movers.isEmpty() && x.pending == null) endSwitch(x)
    }

    /** Everyone is at an end: the row is the seats'. */
    internal fun endSwitch(x: Switch) {
        if (exchange !== x) return
        exchange = null
        c.noteMorphMotion.endPillLanding()
        x.seats?.let { s ->
            s.big?.let { c.selectedIsland = it }
            c.preferredSmall = s.small
        }
        c.snapSmallOnce = true
        c.refresh()
        c.trace("exchange end " + c.smallState())
    }

    /**
     * A tap on the row while a switch runs, the super island's way: it goes to whoever holds the
     * place tapped - landed or still on its way there - and that island goes out as its card,
     * the one out (still going up, or resting in its card) coming back in, all from where they
     * are. Only a card still being laid out for the last tap keeps the next one waiting.
     */
    fun tapDuringExchange(small: Boolean) {
        val x = exchange ?: return
        if (x.pending != null) {
            MiniPlayerRuntime.noteTouch("exchange tap: still waiting")
            return
        }
        val seats = x.seats ?: return
        val key = (if (small) seats.small else seats.big) ?: return
        if (key == x.expanded) return
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            if (!c.spreadMotion.openSingleApp("tap during a switch")) c.spreadMotion.openSpread("tap during a switch")
            return
        }
        c.trace("switch tap ${key.takeLast(6)} small=$small")
        val wasOut = x.movers[key]?.morph != null
        requestUp(x, key, fromPill = !small, cover = key == MUSIC_ISLAND)
        // The music opens the cover from any place, as from the pill (tapIsland).
        if (key == MUSIC_ISLAND && !wasOut) {
            MiniPlayerRuntime.forgetRestoreScene()
            Main.miniPlayerEnterCover()
        }
    }

    /** What a tap on an island does: the music opens the cover, a notification its row. */
    internal fun tapIsland(key: String) {
        if (key == MUSIC_ISLAND) openCover() else c.noteMorphMotion.expandNote(key)
    }

    /**
     * The music into its cover. With another island out as its card, that one comes down into
     * the music's place as the music goes up - one card at a time; it stood there beside the
     * cover's card before (2026-09-25).
     */
    fun openCover() {
        MiniPlayerRuntime.forgetRestoreScene()
        // A notification still on its way - opening, or springing back out after a pull too
        // short to collapse it - is not "out" yet to expandedKey, and the cover came up over it
        // with the timer's card left standing in the stack under the media card (filmed
        // 2026-09-26). Taken into a switch as it is, it turns home while the music goes up, as
        // the super island does it: the next version waited for it to land and only then opened
        // the cover, one after the other.
        val moving = c.noteMorphMotion.noteMorphKey?.takeIf { it != MUSIC_ISLAND }
        when {
            moving != null && c.morph != null -> {
                val m = c.morph!!
                c.trace("cover takes ${moving.takeLast(6)} in: p=${"%.2f".format(m.progress)} toRow=${m.toNative} " +
                    "out=${c.flightOut} drag=${c.noteDragMotion.noteDrag != null} " + c.smallState())
                val inPill = c.selectedIsland == MUSIC_ISLAND
                val x = if (inPill || c.smallKey == MUSIC_ISLAND) adoptNote(moving) else null
                if (x == null) {
                    MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} moving, not taken in")
                    return
                }
                requestUp(x, MUSIC_ISLAND, inPill, cover = true)
                MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} taken in, music up")
            }
            moving != null && c.noteMorphMotion.rowWaitKey == moving -> {
                c.noteDragMotion.noteDrag = null
                c.noteMorphMotion.abandonNoteMorph(moving)
                MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} not opened")
            }
            c.expandedKey()?.let { it != MUSIC_ISLAND } == true -> startExchange(MUSIC_ISLAND, toCover = true)
        }
        Main.miniPlayerEnterCover()
    }

    /**
     * No card to go up into: the one asked for stays in the row, the one out stays out, and
     * whatever was already moving goes on.
     */
    private fun abandonUp(x: Switch, key: String, why: String) {
        if (exchange !== x) return
        x.pending = null
        Xp.log("MCIsland: switch $key not run: $why")
        MiniPlayerRuntime.noteTouch("exchange $why")
        abandonMover(x, key)
    }

    private fun abandonMover(x: Switch, key: String) {
        c.stackHost.flushTurn(key)
        x.expanded?.let(c.stackHost::flushTurn)
        // It was given back for the other's sake: out again as it was.
        x.expanded?.takeIf { it !in x.movers }?.let(c.stackHost::letOutAgain)
        // The card that was to come down stays where it was held, while the row that was let out
        // for the other goes back out of the stack under it.
        x.expanded?.takeIf { it !in x.movers }?.let { out ->
            val held = c.stackHost.pinned?.get()
            val at = c.stackHost.pinnedAt
            if (held != null && at != null && held === c.nativeFor(out)) c.stackHost.pinCard(held, listOf(key), from = at)
            else c.stackHost.unpinCard()
        }
        x.movers.remove(key)?.let { m ->
            m.morph?.cancel()
            c.recycleSmallView(m.view)
        }
        if (key != MUSIC_ISLAND) c.stackHost.rowFor(key)?.let { c.stackHost.hideRowUntilGone(it, key) }
        else c.transitionHeader()?.let(c.stackHost::showRow)
        c.stackHost.takeIn(key)
        if (key != MUSIC_ISLAND) c.stackHost.markReturning(key)
        c.refresh()
        if (x.movers.isEmpty() && x.pending == null) {
            exchange = null
            c.refresh()
        }
    }

    /**
     * Busy for a running morph taken over as a notification's flight: the motions that keep it from
     * starting (M0, the subset as it was).
     */
    private fun busyForAdoptNote(): Boolean = exchange != null || c.noteMorphMotion.rowWaitKey != null

    /**
     * The notification opening or folding back as the flight, made a [Mover] of a new switch as
     * it is: its morph handed over (MiniCardMorph.handTo) at its progress and speed, the flight
     * its stand-in, its row end where the flight was headed. Out on its way up, it is the switch's
     * island out - the next tap turns it home. On its way home, nothing is out. Null with no
     * flight to take.
     */
    private fun adoptNote(key: String): Switch? {
        val running = c.morph ?: return null
        val view = c.noteMorphMotion.flight ?: return null
        if (busyForAdoptNote()) return null
        val native = running.card
        c.stackHost.flushTurn(key)
        // Let go by no finger yet: from here the switch moves it, on its spring.
        if (running.held) running.release(running.toNative, 0f)
        c.noteDragMotion.noteDrag = null
        // Pulled and not let go, it was not out yet: its home is given up now, as a tap's is.
        if (running.toNative) c.noteMorphMotion.setFlightOut(true) else c.stackHost.takeInEarly(key)
        val home = if (c.noteMorphMotion.flightHome == HOME_PILL) LAND_PILL else LAND_SMALL
        val end = if (home == LAND_PILL) c.player?.restBoxOnScreen() else c.smallBoxOnScreen()
        // The flight's landing - the pill or the small island in its shape - is the switch's to
        // make again (moverFrame), once the seats have it.
        c.noteMorphMotion.endPillLanding()
        if (c.landingBox != null) {
            c.landingBox = null
            c.smallIsland?.iconAtStart = false
            c.restoreSmallIslandShape()
        }
        val x = Switch()
        val m = Mover(key, view, native)
        m.origin = home
        m.fromCard = !running.toNative
        m.lastDy = if (running.toNative) c.stackHost.settleDy(native, emptyList()) else 0f
        m.settleAt = native.translationY + m.lastDy
        aimEnd(m, home, snap = true, at = end)
        m.morph = running
        running.handTo(moverListener(m), { moverEnd(m) }, { moverDy(x, m) }, { m.round.value })
        x.movers[key] = m
        x.expanded = key.takeIf { running.toNative }
        x.seats = Seats(c.selectedIsland, c.smallKey)
        if (!running.toNative && home == LAND_PILL) c.fitToPill(view, c.smallKey != null)
        // Nothing is the flight's any more.
        c.morph = null
        c.noteMorphMotion.noteMorphKey = null
        c.noteMorphMotion.flight = null
        c.noteMorphMotion.flightFromSmall = false
        c.noteMorphMotion.flightHome = HOME_SMALL
        c.flightOut = false
        c.flightLanding = false
        exchange = x
        c.endRow()
        c.resetIslandDrag()
        c.clearSmallNudge()
        c.player?.clearNudge()
        c.trace("adopt ${key.takeLast(6)} ${moverState(m)} seats=${x.seats?.big?.takeLast(6)}/" +
            "${x.seats?.small?.takeLast(6)} " + c.smallState())
        c.refresh()
        orderMovers(x)
        c.updateVisibility()
        c.followShortcuts()
        return x
    }
}
