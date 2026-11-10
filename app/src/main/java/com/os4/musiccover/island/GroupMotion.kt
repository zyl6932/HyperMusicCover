package com.os4.musiccover.island

import com.os4.musiccover.CoverMorphMotion
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import android.graphics.Matrix
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1); `c` is the
 * controller. See the section comments below for what it is.
 */
internal class GroupMotion(private val c: MiniPlayerController) {

    // ---- the row and the lock screen's cards, into each other together

    /**
     * The media card's morph with the row: the card and the notification rows that are the
     * row's islands change into those islands together, each into its own place, and back.
     * Pulled down, the card becomes the music's island - the pill, or the small island - while
     * the notification that is the row's other island comes down out of its row into its place
     * at the same time; pulled up, the reverse. One spring moves them all, the music's: the
     * other morph follows its progress frame by frame (MiniCardMorph.follow), so the two differ
     * only by their ends - a small island's circle, a card's size. The islands with no place
     * in the row (a third one on) have no island to become: their rows go as the stack folds
     * its notifications away (foldRows), and come back as it unfolds them.
     *
     * The first version laid the row out as the pill alone for the morph: the card landed as
     * the whole pill, and only then did the small island come out from under its end and
     * narrow it, while the rows went into their islands on the stack's own animation - one
     * motion after another, where each was to go home at once (2026-09-25).
     */
    class Group(val musicSmall: Boolean, val down: Boolean) {
        /** The view drawing the small island's place through the morph: the music's, or [other]'s. */
        var smallView: MiniPlayerView? = null
        /** The notification in the row's other place, when there is one. */
        var other: String? = null
        /** ...and that place is the small island's. */
        var otherSmall = false
        /** [other]'s morph with its row, once the row is there. */
        var follower: MiniCardMorph? = null
        /** It set out after the music's, from [joinFrom], and comes up to it over GROUP_JOIN_MS. */
        var joinedAt = 0L
        var joinFrom = 0f
        /** Since when [other]'s row has been waited for. */
        var waitSince = 0L
        /** [other] has no row to go to or come from: its island comes and goes in its place. */
        var inPlace = false
        /** The real small island is under the view landing on it, taking over from it. */
        var smallLanding = false

        /**
         * The rows with no place in the row, as the stack's own fold moves them: down with the
         * finger (FOLD_PULL_RANGE_DP, the stack's friction), back up on the fold's release
         * spring, and out or in on its alpha spring (NormalNotificationsVisibleAnimatorImpl:
         * FolmeEase -2, 1.0, 0.2) once the lift has decided.
         */
        val foldDy = Jelly(FOLD_BACK_RESPONSE, HIDDEN_DAMPING)
        val foldAlpha = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING)
        /** How far into the pile they are, 0 laid out to 1 piled (stackRows). */
        val foldK = Jelly(FOLD_BACK_RESPONSE, HIDDEN_DAMPING)
        var foldLast = 0L
        /** Where the card stood when the finger took it, on screen: what the pull is measured from. */
        var cardTop = Float.NaN
        /** Rows moved by the fold, to be given their own place back. */
        val folded = java.util.WeakHashMap<View, Boolean>()
    }

    var group: Group? = null

    /**
     * A finger is pulling the music up and has not reached the threshold yet: the stack is
     * given the rows now, out of sight, so they are laid out by the time the morph wants them.
     */
    private var rowsAnticipated = false

    /** The stack may still show the rows for a moment after it was asked to leave them out. */
    private var rowsHeldUntil = 0L

    /**
     * The row's islands are the stack's rows for now: the stack is not to leave them out. Never
     * any more: with one island out as its card the others stay in the row, the music's morph
     * into its card or the cover included, and their rows stay out of the stack.
     */
    internal fun rowsHeld(): Boolean = false

    /** A scene's exit is bringing the music down into the row: it has its place there again. */
    internal var musicComingDown = false

    /** The morph drawing the pill: the media card's, or in a group whichever has the pill. */
    fun pillMorph(): MiniCardMorph? {
        val g = group ?: return c.morph?.takeIf { c.noteMorphMotion.flight == null }
        return if (g.musicSmall) g.follower?.takeIf { !g.otherSmall } else c.morph
    }

    /** Whether the small island shows while a group runs; null with no group running. */
    internal fun groupShowsSmall(): Boolean? {
        val g = group ?: return null
        val drawnByView = g.musicSmall || g.otherSmall && g.follower != null
        return if (drawnByView) g.smallLanding else g.otherSmall
    }

    /** The small island [selected] would have beside it, as updateSmallIsland picks it. */
    private fun smallAfter(keys: List<String>, selected: String): String? {
        if (keys.size < 2) return null
        c.preferredSmall?.takeIf { it in keys && it != selected }?.let { return it }
        if (c.stackedStyle()) return MiniPlayerStackGesture.nextKey(keys, selected)
        if (c.firstAlone(keys, selected)) return null
        return keys.getOrNull(keys.indexOf(selected).coerceAtLeast(0) + 1)
    }

    /**
     * Sets the row up for the media card's morph and makes the music's morph, not started yet:
     * [toNative] the music goes up into the card, else the card comes down into the row.
     */
    /** Why prepareGroup gave none, read after it: the parts of its state that refuse one. */
    internal fun groupRefusal(toNative: Boolean): String =
        "usable=${c.controller?.let(c::isUsable)} note=${c.noteMorphMotion.noteMorphKey != null} flight=${c.noteMorphMotion.flight != null} " +
            "xchg=${c.cardExchange.exchange != null} sel=${c.selectedIsland?.takeLast(6)} small=${c.smallKey?.takeLast(6)} " +
            "keys=${c.islandKeys.joinToString(",") { it.takeLast(6) }} toNative=$toNative"

    internal fun prepareGroup(native: View, toNative: Boolean): MiniCardMorph? {
        musicComingDown = !toNative
        try {
            return prepareGroupUnsafe(native, toNative)
        } finally {
            musicComingDown = false
        }
    }

    /**
     * Busy for a group - the row and the lock screen's cards moving as one: the motions that keep
     * it from starting (M0, the subset as it was).
     */
    private fun busyForGroup(): Boolean = c.noteMorphMotion.noteMorphKey != null || c.noteMorphMotion.flight != null || c.cardExchange.exchange != null

    private fun prepareGroupUnsafe(native: View, toNative: Boolean): MiniCardMorph? {
        val view = c.player ?: return null
        if (c.controller?.takeIf(c::isUsable) == null || busyForGroup()) return null
        if (toNative && c.selectedIsland != MUSIC_ISLAND && c.smallKey != MUSIC_ISLAND) return null
        c.swapMotion.endSwap()
        c.endRow()
        c.resetIslandDrag()
        c.clearSmallNudge()
        if (!toNative) {
            // Down: the music goes back where it was - the pill, or the small island - unless
            // that is out of sight now (the row moved on while the card was up), or the pill
            // would hold a notification with no row on screen to come down out of.
            // Out as its card with other islands in the row, the music was not in it: the pill
            // just chosen again (a scene's exit) puts it back first.
            if (MUSIC_ISLAND !in c.islandKeys) c.refresh()
            val keys = c.islandKeys
            if (MUSIC_ISLAND !in keys) return null
            val selected = c.selectedIsland?.takeIf { it in keys } ?: MUSIC_ISLAND
            c.selectedIsland = if (selected != MUSIC_ISLAND && smallAfter(keys, selected) == MUSIC_ISLAND &&
                c.stackHost.rowFor(selected)?.let(::rowReady) == true) selected else MUSIC_ISLAND
        }
        val g = Group(musicSmall = c.selectedIsland != MUSIC_ISLAND, down = !toNative)
        // Coming down the other rows are there, whole; going up they come out of the fold.
        g.foldAlpha.value = if (toNative) 0f else 1f
        g.foldAlpha.target = g.foldAlpha.value
        // Going up they come out of the pile.
        g.foldK.value = if (toNative) 1f else 0f
        g.foldK.target = g.foldK.value
        group = g
        rowsAnticipated = false
        view.visibility = View.VISIBLE
        c.updateNativeSuppression(false)
        c.refresh()
        c.position()
        // The small place keeps what it holds till the morph is over, whatever comes in meanwhile.
        c.preferredSmall = c.smallKey
        // The other islands stay in the row: none goes into its row with the music any more.
        g.other = null
        g.otherSmall = g.other != null && !g.musicSmall
        g.waitSince = android.os.SystemClock.uptimeMillis()
        val lead = if (g.musicSmall) c.prepareSmallView(MUSIC_ISLAND) else view
        if (lead == null) {
            group = null
            return null
        }
        if (g.musicSmall) {
            // The view is the small island from here, in its very place.
            g.smallView = lead
            c.smallIsland?.visibility = View.GONE
        } else {
            // followShortcuts leaves the pill's fade alone while this morph draws it, so it kept
            // whatever was last written before the cover - once 0, and the card came down into
            // nothing and the pill turned up only once the morph had ended (2026-10-20). Set here,
            // from the binding refresh() has just made.
            val want = if (c.pillUnbound) 0f else 1f
            if (kotlin.math.abs(view.transitionAlpha - want) > 0.002f) {
                c.trace("group pill fade ${"%.2f".format(view.transitionAlpha)} -> $want")
                view.transitionAlpha = want
            }
        }
        val next = MiniCardMorph(lead, native, toNative, c.morphListener,
            restBox = if (g.musicSmall) { { c.smallBoxOnScreen() } } else null,
            circle = g.musicSmall && !c.stackedStyle())
        // The other island's row, if the stack has it laid out already: coming down it always
        // has; going up, only if the pull was seen coming (rowsAnticipated). Otherwise the music
        // sets out alone and the other island joins it the moment its row is there (groupFrame).
        g.other?.let { key ->
            val row = c.stackHost.rowFor(key)?.takeIf(::rowReady)
            if (row != null) {
                if (!startFollower(g, row, fromIsland = toNative, joining = false)) g.inPlace = true
            } else if (!toNative) g.inPlace = true
        }
        c.trace("group ${if (toNative) "up" else "down"} musicSmall=${g.musicSmall} " +
            "other=${g.other?.takeLast(6)} small=${g.otherSmall} follower=${g.follower != null} " +
            "inPlace=${g.inPlace} " + c.smallState())
        return next
    }

    /** A notification's row, laid out and on screen above the row of islands. */
    internal fun rowReady(row: View): Boolean = row.isAttachedToWindow && row.isLaidOut &&
        row.width > 0 && row.height > 0 && c.stackHost.rowOnScreen(row)

    /**
     * The other island goes with the music's morph from here, between its place and [row]:
     * [fromIsland] it starts at its island (going up), else at the row (coming down).
     * [joining] the music's morph is already under way: it comes up to it over a few frames.
     */
    private fun startFollower(g: Group, row: View, fromIsland: Boolean, joining: Boolean): Boolean {
        val key = g.other ?: return false
        val view = (if (g.otherSmall) c.prepareSmallView(key) else c.player) ?: return false
        // Out of sight while it waited (holdRows): the morph saves the row's own alpha.
        c.stackHost.showRow(row)
        val f = MiniCardMorph(view, row, fromIsland, followerListener, c.noteMorphMotion.rowLanding(row),
            restBox = if (g.otherSmall) { { c.smallBoxOnScreen() } } else null,
            circle = g.otherSmall && !c.stackedStyle())
        if (!f.startDragging()) {
            if (g.otherSmall) runCatching { c.host.removeView(view) }
            c.stackHost.hideRow(row)
            return false
        }
        if (g.otherSmall) {
            g.smallView = view
            c.smallIsland?.visibility = View.GONE
        }
        g.follower = f
        g.joinFrom = f.progress
        g.joinedAt = if (joining) android.os.SystemClock.uptimeMillis() else 0L
        c.trace("group follower ${key.takeLast(6)} joining=$joining from=${"%.2f".format(g.joinFrom)}")
        return true
    }

    /** A follower ends with the music's morph: the group finishes it, never its own springs. */
    private val followerListener = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = false
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {}
    }

    /** Every frame of the music's morph: the rest of the row moves with it. */
    internal fun groupFrame(lead: MiniCardMorph) {
        val g = group ?: return
        if (c.morph !== lead) return
        val now = android.os.SystemClock.uptimeMillis()
        val other = g.other
        // Going up, the other island's row may come a few frames after the music set out. Coming
        // back before it has, the island never left its place and needs none.
        if (other != null && g.follower == null && !g.inPlace && lead.toNative) {
            val row = traced("MC g.findRow") { c.stackHost.rowFor(other) }
            if (row != null && rowReady(row)) {
                if (!traced("MC g.startFollower") { startFollower(g, row, fromIsland = true, joining = true) }) g.inPlace = true
            } else if (now - g.waitSince > ROW_WAIT_MS) {
                Xp.log("MCIsland: $other has no row for the card's morph; in place")
                g.inPlace = true
            }
        }
        g.follower?.let { f ->
            val share = if (g.joinedAt == 0L) 1f
                else MiniCardMorph.smooth(0f, 1f, (now - g.joinedAt) / GROUP_JOIN_MS.toFloat())
            if (share >= 1f) g.joinedAt = 0L
            traced("MC g.follow") { f.follow(lead, share, g.joinFrom) }
        }
        traced("MC g.fold") { foldRows(g, lead, now) }
        // The small place: the view drawing it hands over to the real small island on its last
        // stretch home, as a flight does (FLIGHT_HANDOFF), overshoot and all.
        val smallMorph = if (g.musicSmall) lead else g.follower?.takeIf { g.otherSmall }
        val view = g.smallView
        if (smallMorph != null && view != null) {
            val p = smallMorph.progress
            val landing = !lead.toNative && p < FLIGHT_HANDOFF
            if (landing) smallMorph.containerBox()?.let(::landSmall)
            else if (g.smallLanding) unlandSmall()
            g.smallLanding = landing
            val a = if (landing) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, p) else 1f
            if (kotlin.math.abs(view.alpha - a) > 0.002f) view.alpha = a
        }
        if (g.inPlace && other != null) {
            // No row: the island grows out of its place as the card comes down and shrinks
            // away there as the music goes up, on the same progress.
            val v = (1f - lead.progress).coerceIn(0f, 1f)
            if (g.otherSmall) {
                val small = c.smallIsland
                if (small != null && c.smallKey == other) {
                    if (small.visibility != View.VISIBLE) small.visibility = View.VISIBLE
                    c.smallGrow.value = v
                    c.applySmallGrow()
                }
            } else c.player?.let { if (kotlin.math.abs(it.alpha - v) > 0.002f) it.alpha = v }
        }
    }

    /** The real small island in the shape and place of the view landing on it, this frame. */
    private fun landSmall(box: CoverMorphMotion.Box) {
        val small = c.smallIsland ?: return
        if (small.visibility != View.VISIBLE) {
            small.visibility = View.VISIBLE
            small.alpha = 1f
        }
        c.noteMorphMotion.landOn(box)
    }

    /** Turned back before landing: the view has the small place to itself again. */
    private fun unlandSmall() {
        if (c.landingBox != null) {
            c.landingBox = null
            c.smallIsland?.iconAtStart = false
            c.restoreSmallIslandShape()
        }
        c.smallIsland?.visibility = View.GONE
    }

    /**
     * The music's morph is over, [toNative] at the card: the rows are the stack's again, the
     * islands' views are done. At the row: the islands are in their places, and the stack is
     * let leave their rows out - out of sight until it has taken them away.
     */
    internal fun endGroup(toNative: Boolean) {
        val g = group ?: return
        group = null
        g.follower?.cancel()
        g.smallView?.let { runCatching { c.host.removeView(it) } }
        c.landingBox = null
        c.smallIsland?.iconAtStart = false
        if (g.inPlace) {
            c.player?.alpha = 1f
            c.smallGrow.value = 1f
            c.smallGrow.target = 1f
        }
        c.restoreSmallIslandShape()
        for (row in g.folded.keys) row.setAnimationMatrix(null)
        for (key in c.rowKeys()) {
            val (row, top) = c.stackHost.findRow(key) ?: continue
            if (toNative) {
                c.stackHost.showRow(row)
                c.stackHost.showRow(top)
            } else c.stackHost.hideRowUntilGone(top, key)
        }
        if (!toNative) {
            c.snapSmallOnce = true
            rowsHeldUntil = android.os.SystemClock.uptimeMillis() + ROW_GONE_MS
        } else rowsHeldUntil = 0L
        c.trace("group end at=${if (toNative) "card" else "row"} " + c.smallState())
    }

    /**
     * Before every frame is drawn, the rows the stack has for the row's islands while it has
     * been asked to keep them (rowsHeld): one whose island is still waiting for it stays out
     * of sight; one with no place in the row fades with the card - out as the card comes down,
     * in as the music goes up; one being morphed is its morph's. Just after, whatever the stack
     * still shows of them for a moment is not drawn.
     */
    internal fun holdRows() {
        val g = group
        val holding = rowsHeld()
        if (!holding && android.os.SystemClock.uptimeMillis() > rowsHeldUntil) return
        // The row the other island is morphing with: a group holding it is not folded whole.
        val keep = g?.other?.let(c.stackHost::rowFor)
        val done = HashSet<View>()
        val pile = ArrayList<View>()
        for (key in c.rowKeys()) {
            if (g != null && key == g.other && g.follower != null) continue
            val row = (if (g != null && key == g.other) keep else c.stackHost.wholeRowFor(key, keep)) ?: continue
            // Another notification of the app the morph is taking: the same row, which is the morph's.
            if (row === keep && (g == null || key != g.other)) continue
            if (!done.add(row)) continue
            if (!holding) {
                if (row !in c.stackHost.hiddenRows) c.stackHost.hideRowUntilGone(row, key)
                continue
            }
            c.stackHost.hideRow(row)
            val folds = g != null && key != g.other
            val ta = if (folds) (c.stackHost.hiddenRows[row] ?: 1f) * g!!.foldAlpha.value.coerceIn(0f, 1f) else 0f
            if (kotlin.math.abs(row.transitionAlpha - ta) > 0.002f) row.transitionAlpha = ta
            if (folds) pile += row
        }
        if (g != null && pile.isNotEmpty()) stackRows(g, pile)
    }

    /**
     * The rows with no place in the row, piled as the stack piles its cards
     * (NotificationStackingCalculator): the lowest stays in front and shrinks toward 0.934; each
     * one above it slides down behind it, shrinking on through 0.85 to 0.8 the deeper it goes,
     * its top showing notif_stacking_height_2 (12dp), then _1 more (32dp), over the front
     * card. All of it [Group.foldK] of the way, and down by the finger's pull on top. Each is
     * scaled about its own bottom, in the animation matrix over whatever the stack has put there.
     */
    private fun stackRows(g: Group, rows: List<View>) {
        val k = g.foldK.value.coerceIn(0f, 1.2f)
        val xy = IntArray(2)
        // Where the stack has each, on screen, by its parent: the matrix here is not in it.
        val tops = rows.associateWith { row ->
            (row.parent as? View)?.getLocationOnScreen(xy)
            xy[1] + row.top + row.translationY
        }
        val order = rows.sortedBy { tops[it] }
        val frontTop = tops[order.last()] ?: return
        val h1 = stackingHeight(1)
        val h2 = stackingHeight(2)
        for ((i, row) in order.withIndex()) {
            val depth = order.size - 1 - i
            val h = row.height.toFloat()
            val scale: Float
            var dy = g.foldDy.value
            if (depth == 0) {
                scale = firstStackedScale(k)
            } else {
                scale = otherStackedScale(k * (depth + 1))
                val peek = h2 + if (depth >= 2) h1 else 0f
                val top = tops[row] ?: continue
                // Its top, drawn at its scale about its bottom, peeking over the front card.
                val to = frontTop - peek - top - h * (1f - scale)
                dy += to * k.coerceAtMost(1f)
            }
            val m = Matrix()
            m.setScale(scale, scale, row.width / 2f, row.translationY + h)
            m.postTranslate(0f, dy)
            row.setAnimationMatrix(m)
            g.folded[row] = true
        }
    }

    private fun stackingHeight(which: Int): Float {
        val res = c.context.resources
        val id = res.getIdentifier("notif_stacking_height_" + which, "dimen", "com.android.systemui")
        return if (id != 0) runCatching { res.getDimension(id) }.getOrNull() ?: 0f
            else c.dp(if (which == 1) 32f else 12f).toFloat()
    }

    /** NotificationStackingCalculator.calculateScaleForFirstStackedCard. */
    private fun firstStackedScale(p: Float): Float = when {
        p < 1f -> c.lerp(1f, 0.934f, p)
        p < 1.5f -> c.lerp(0.934f, 0.85f, ((p - 1f) / 0.5f).coerceIn(0f, 1f))
        else -> c.lerp(0.85f, 0.8f, ((p - 1.5f) / 1.5f).coerceIn(0f, 1f))
    }.coerceAtLeast(0.8f)

    /** The stack island's other rows the pile has moved: their matrix and alpha are ours. */
    internal val pileRows = HashSet<View>()

    internal val piles = HashMap<View, PileRow>()
    private var pileLast = 0L
    private var pileKey = STACK_ISLAND

    /** Where the island was, the last frame the lead drew: the rows go home under it after the lead has landed. */
    private var pileFrom: CoverMorphMotion.Box? = null

    /** The lead's progress the rows follow once it has landed, while they settle (pileSettle). */
    private var pileAt = 0f

    /** When the rows were left to settle on their own (pileSettle), which gives up past PILE_SETTLE_MAX_MS. */
    private var pileSettleSince = 0L

    /**
     * The stack island's other rows as its lead goes out to its row or comes home: each springs
     * out from under the island to where the stack draws it, or back, one after another - as
     * ColorOS lays its notifications out of its capsule (reference filmed 2026-09-26). Each row
     * follows the lead's [progress] a turn later than the one before it, on a spring of its own:
     * out, the nearest comes first; home, the farthest goes first. Turned round - by a finger, a
     * tap, another island - the lead turns and every row turns with it from where it is, at its
     * speed. [from] is the island's end of the lead's morph this frame: the pill, a narrower pill
     * or the small island's circle, whichever the island is.
     *
     * The end is where the stack itself draws the row, pile and all (superTranslationY and
     * superScale, on its render node): the matrix is only the way there from the island. The
     * version before scrolled the rows through the stack's own calculator, followed the lead's
     * progress with none of their own, and came up alone at the card's place before the card.
     */
    internal fun pileGroup(key: String, progress: Float, from: CoverMorphMotion.Box?) = traced("MC pileLookup") {
        pileKey = key
        Gen.unframe(pileSettle)
        if (from != null) pileFrom = from
        pileAt = progress
        val bucket = (progress * 10f).toInt()
        if (bucket != pileTraceBucket) pileTraceNext = true
        pileTraceBucket = bucket
        observePiles(true)
        // Asked on every touch of a pull, several times a frame: the rows are looked for once a
        // frame at most - each look walks the stack's rows by reflection (2026-09-26, frames
        // dropped pulling the stack island down).
        val now = android.os.SystemClock.uptimeMillis()
        if (now - pileFoundAt < PILE_FIND_MS && !pileTraceNext) return@traced
        pileFoundAt = now
        val lead = c.groupLead(key) ?: return@traced
        val members = LockIslands.membersOf(key)
        val stack = c.stackHost.notificationStack() ?: return@traced
        val children = (0 until stack.childCount).map { stack.getChildAt(it) }
            .filter { it.javaClass.name.contains("ExpandableNotificationRow") }
        val index = MiniPlayerRowIndex.build(children, c.stackHost::rowKey, c.stackHost::childRows,
            c.stackHost.kept.filterValues { it.row.isAttachedToWindow }.mapValues { it.value.row })
        val keep = index[lead] ?: return@traced
        val rows = members.asSequence().filter { it != lead }
            .mapNotNull { index[it] }
            .filter { it !== keep && !c.stackHost.isInside(keep, it) }.distinct().toList()
        if (pileTraceNext) c.trace("pile p=${"%.2f".format(progress)} lead=${shortKey(lead)} keep=${c.stackHost.shortName(keep)}" +
            "@${(keep.translationY).toInt()} members=${members.joinToString(",") { shortKey(it) }} " +
            "rows=${rows.joinToString(",") { c.stackHost.shortName(it) + "@" + it.translationY.toInt() }} " +
            "missing=${members.filter { it != lead && it !in index }.joinToString(",") { shortKey(it) }}")
        if (rows.isEmpty()) return@traced
        // Their turns: the nearest to the lead in the stack first.
        val at = c.stackHost.stackTargetY(keep) + keep.top
        rows.map { it to kotlin.math.abs(c.stackHost.stackTargetY(it) + it.top - at) }
            .sortedBy { it.second }.forEachIndexed { i, (row, _) ->
            val pile = piles.getOrPut(row) {
                // First seen where the lead is: nothing moves at once.
                PileRow(row).also { it.out.value = pileTarget(progress, i + 1) }
            }
            pile.turn = i + 1
        }
    }

    private var pileFoundAt = 0L
    private var pileTraceNext = false
    internal var pileMoving = false

    /**
     * The rows are sprung and placed right before the frame is drawn, not on the lead's frame
     * callback: the stack writes where it draws a row - superTranslationY, superScale - later in
     * the same frame than that callback, so a row read there was placed against the stack's last
     * frame. As the stack laid the pile out again with the lead taken home (0.960 -> 0.885, 9px
     * down over 200ms), the row shook all the way (2026-09-26) - the same as the card's (see
     * MiniCardMorph.preDraw).
     */
    private val pilePreDraw = ViewTreeObserver.OnPreDrawListener {
        android.os.Trace.beginSection("MC pilePreDraw")
        try { pileMoving = stepPiles() } finally { android.os.Trace.endSection() }
        true
    }

    private var pileObserved: View? = null

    private fun observePiles(on: Boolean) {
        if (on && pileObserved?.isAttachedToWindow == true) return
        pileObserved?.let { Gen.unpreDraw(it, pilePreDraw) }
        pileObserved = null
        if (!on) return
        val stack = c.stackHost.notificationStack() ?: return
        Gen.preDraw(stack, pilePreDraw)
        pileObserved = stack
    }

    /** Where row [turn] is headed while the lead is at [progress]: behind it by its turn. */
    private fun pileTarget(progress: Float, turn: Int): Float {
        val lag = (turn * PILE_STAGGER).coerceAtMost(PILE_STAGGER_MAX)
        return ((progress - lag) / (1f - lag)).coerceAtLeast(0f)
    }

    /** Every row's spring a frame on, toward its turn behind the lead, and drawn there. */
    private fun stepPiles(): Boolean {
        val traceIt = pileTraceNext
        pileTraceNext = false
        val now = System.nanoTime()
        val dt = if (pileLast == 0L) 1f / 120f else ((now - pileLast) / 1e9f).coerceIn(0f, 0.05f)
        pileLast = now
        var moving = false
        val xy = IntArray(2)
        val it = piles.values.iterator()
        while (it.hasNext()) {
            val pile = it.next()
            val row = pile.row
            if (!row.isAttachedToWindow) {
                it.remove()
                continue
            }
            c.stackHost.hideRow(row)
            pileRows += row
            pile.out.target = pileTarget(pileAt, pile.turn)
            pile.out.step(dt)
            if (!pile.out.atRest()) moving = true
            val u = pile.out.value
            val base = c.stackHost.hiddenRows[row] ?: 1f
            val ta = base * MiniCardMorph.smooth(PILE_IN_FROM, PILE_IN_TO, u)
            if (kotlin.math.abs(row.transitionAlpha - ta) > 0.002f) row.transitionAlpha = ta
            // Where the stack draws it: its own place and scale in the pile, on its render node.
            val ty = runCatching { (Xp.callMethod(row, "getSuperTranslationY") as Number).toFloat() }
                .getOrDefault(row.translationY)
            val s = runCatching { (Xp.callMethod(row, "getSuperScaleY") as Number).toFloat() }
                .getOrDefault(row.scaleY).takeIf { it > 0.01f } ?: 1f
            // Its top left before the matrix, in the matrix's space (the parent's, at left/top).
            val mx = row.pivotX * (1f - s)
            val my = ty + row.pivotY * (1f - s)
            (row.parent as? View)?.getLocationOnScreen(xy) ?: continue
            val w = row.width * s
            val h = rowHeight(row) * s
            val native = CoverMorphMotion.Box(xy[0] + row.left + mx, xy[1] + row.top + my, w, h)
            val island = pileFrom
            val m = pile.matrix
            m.reset()
            if (island != null && u < 1f - 0.0005f || u > 1f + 0.0005f) {
                // Out from under the island at its width, centred on it.
                val k0 = if (island != null && w > 0f) island.w / w else 1f
                val start = if (island != null) CoverMorphMotion.Box(island.cx() - w * k0 / 2f,
                    island.cy() - h * k0 / 2f, w * k0, h * k0) else native
                val box = c.lerpBox(start, native, u)
                val k = if (w > 0f) box.w / w else 1f
                m.setScale(k, k, mx, my)
                m.postTranslate(box.x - native.x, box.y - native.y)
            }
            row.setAnimationMatrix(m)
            if (traceIt) c.trace("  pile ${c.stackHost.shortName(row)} #${pile.turn} u=${"%.2f".format(u)}>" +
                "${"%.2f".format(pile.out.target)} ta=${"%.2f".format(ta)} at=${native.y.toInt()} " +
                "s=${"%.3f".format(s)} from=${island?.let { "${it.cx().toInt()},${it.cy().toInt()} w${it.w.toInt()}" }}")
        }
        return moving
    }

    /** The lead has landed out in its row and the rows are still on their way: they go on on their own. */
    internal val pileSettle = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pileMoving && piles.values.all { it.out.value == 1f }) {
                finishPile(true)
                return
            }
            // The rows are stepped only in the stack's own pre-draw. A stack that no longer
            // draws - the lock screen gone, rebuilt, this runtime destroyed - never steps them
            // home, and this asked for a frame every vsync for as long as SystemUI lived.
            val stack = pileObserved
            if (c.destroyed || stack?.isShown != true ||
                android.os.SystemClock.uptimeMillis() - pileSettleSince > PILE_SETTLE_MAX_MS) {
                finishPile(true)
                return
            }
            // Stepped and placed before the frame is drawn (pilePreDraw): this only asks for the frame.
            observePiles(true)
            pileObserved?.invalidate()
            Gen.frame(this)
        }
    }

    /** The stack island is on its way back into the row: by a switch, or a morph of its own. */
    internal fun stackGoingHome(): Boolean =
        c.cardExchange.exchange?.movers?.get(pileKey)?.headedHome == true ||
            c.noteMorphMotion.noteMorphKey == pileKey && c.morph?.toNative == false

    /** The last tenth of a pile's progress traced, so a pile is traced ten times, not every frame. */
    private var pileTraceBucket = -1

    internal fun shortKey(key: String): String = key.substringAfter('|').substringBefore('|').substringAfterLast('.')

    private fun rowHeight(row: View): Float =
        runCatching { (Xp.callMethod(row, "getActualHeight") as Number).toFloat() }.getOrNull()
            ?.takeIf { it > 0f } ?: row.height.toFloat()

    /**
     * The stack island's lead has landed: out in its row ([toRow]) the rows still on their way
     * go on to theirs, a turn behind; home, whatever is left of them goes with it.
     */
    internal fun endPile(toRow: Boolean) {
        c.trace("pile end toRow=$toRow rows=${pileRows.joinToString(",") { c.stackHost.shortName(it) }}")
        pileTraceBucket = -1
        pileAt = if (toRow) 1f else 0f
        if (toRow && piles.values.any { !it.out.atRest() || it.out.value != 1f }) {
            pileMoving = true
            pileSettleSince = android.os.SystemClock.uptimeMillis()
            Gen.unframe(pileSettle)
            Gen.frame(pileSettle)
            return
        }
        finishPile(toRow)
    }

    internal fun finishPile(toRow: Boolean) {
        Gen.unframe(pileSettle)
        observePiles(false)
        pileMoving = false
        pileFoundAt = 0L
        for (row in pileRows) {
            row.setAnimationMatrix(null)
            if (toRow) c.stackHost.showRow(row)
            else {
                row.transitionAlpha = 0f
                c.stackHost.hideRowUntilGone(row, pileKey)
            }
        }
        pileRows.clear()
        piles.clear()
        pileLast = 0L
        pileFrom = null
        if (!toRow) {
            if (pileKey == STACK_ISLAND) c.stackLeadKey = null else c.appLeadKeys.remove(pileKey)
        }
    }

    /** NotificationStackingCalculator.calculateScaleForOtherStackedCards. */
    private fun otherStackedScale(p: Float): Float = when {
        p < 1f -> c.lerp(1f, 0.934f, p)
        p < 2f -> c.lerp(0.934f, 0.85f, p - 1f)
        else -> c.lerp(0.85f, 0.8f, (p - 2f).coerceAtMost(1f))
    }.coerceAtLeast(0.8f)

    /**
     * The rows with no place in the row, this frame, as the stack's fold moves them. Under the
     * finger coming down they follow the card's pull with the stack's own friction, whole; let
     * go, they spring back and go out on the fold's alpha spring if the card is headed for the
     * row, stay if it goes back up. Going up they come in on that spring once the music is on
     * its way into the card for good.
     */
    private fun foldRows(g: Group, lead: MiniCardMorph, now: Long) {
        val dt = if (g.foldLast == 0L) 1f / 120f else ((now - g.foldLast) / 1000f).coerceIn(0f, 0.05f)
        g.foldLast = now
        val box = lead.containerBox()
        if (g.down && g.cardTop.isNaN() && box != null) g.cardTop = box.y
        if (lead.held) {
            if (g.down && box != null) {
                val range = c.dp(FOLD_PULL_RANGE_DP).toFloat()
                val t = ((box.y - g.cardTop) / range).coerceIn(0f, 1f)
                g.foldDy.value = (t * t * t / 3f - t * t + t) * range
                g.foldDy.target = g.foldDy.value
                g.foldDy.velocity = 0f
                // The pile gathers with the pull, as far as the finger has taken it.
                g.foldK.value = t
                g.foldK.target = t
                g.foldK.velocity = 0f
            }
            return
        }
        g.foldDy.target = 0f
        g.foldAlpha.target = if (lead.toNative) 1f else 0f
        g.foldK.target = if (lead.toNative) 0f else 1f
        g.foldDy.step(dt)
        g.foldAlpha.step(dt)
        g.foldK.step(dt)
    }

    /** A finger has started pulling the music up ([small]: out of the small island). */
    fun anticipateRows(small: Boolean) {
        if (rowsAnticipated || group != null || !c.canDrag(fromNative = false, small = small)) return
        if (LockIslands.notes.isEmpty()) return
        rowsAnticipated = true
        c.updateVisibility()
    }

    /** ...and let go without opening anything: the stack leaves the rows out again. */
    fun stopAnticipating() {
        if (!rowsAnticipated) return
        rowsAnticipated = false
        if (group != null) return
        rowsHeldUntil = android.os.SystemClock.uptimeMillis() + ROW_GONE_MS
        for (key in c.rowKeys()) c.stackHost.findRow(key)?.let { c.stackHost.hideRowUntilGone(it.second, key) }
        c.updateVisibility()
    }
}
