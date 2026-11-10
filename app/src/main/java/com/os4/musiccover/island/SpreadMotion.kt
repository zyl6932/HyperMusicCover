package com.os4.musiccover.island

import com.os4.musiccover.ClockRoom
import com.os4.musiccover.Main
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1); `c` is the
 * controller. See the section comments below for what it is.
 */
internal class SpreadMotion(private val c: MiniPlayerController) {

    // ------------------------------------------------------------ the whole row out as the stack's list

    /**
     * The stack island opened ColorOS's way (2026-09-30, the user's call). Opened on its own -
     * the ordinary notifications out of the fold, the other islands left in the row - a card
     * then opened from one of those came up over the list. ColorOS's list state has no capsules
     * at all: so here the whole row goes out as the lock screen's own cards while the stack is its
     * list. The music goes up into the media card, each focus island into its row, the islands
     * with no place in the row fade in with the list, and the stack island's own place fades as
     * its rows come out of the fold. Pulled down, the list folds back and every card comes down
     * into its own place - the old "each to its own island" (Group), for the whole row.
     *
     * One progress runs all of it: where the stack's scroll is between the islands' end (the
     * folded stack, NUMBER) and the cards' end (the list), read every frame. Opening, that is the
     * stack's own scroll to its list; closing, the finger's pull and the stack's own settling
     * after it (NumState's hands-up keeps it from resting half way). So the finger, the list and
     * every island move as one and turn as one, with no spring of their own to disagree.
     */

    class Spread(val items: List<SpreadItem>, val stackSeat: Int, val stacked: Boolean) {
        var phase = SPREAD_WAIT
        var since = 0L
        /** The stack's scroll at the islands' end and at the cards' end of this motion. */
        var islandsY = 0
        var cardsY = 0
        /** Frames the scroll has stood still, nothing moving it. */
        var still = 0
        var lastY = Int.MIN_VALUE
        var progress = 0f
        var rawProgress = 0f
        /** The stacked cards may need to catch up when the OEM scroll jumps to its end. */
        var visualAt = 0L
        /** Stuck between the two, the stack was sent to the nearer end once. */
        var nudged = false
        /** The stack's measured focus rows before the spread's came in (NumState.focusCount). */
        var focusBefore: Int? = null
        /** Open, the list's place was read again and the stack sent there once. */
        var reaimed = false
        /** The stack's own drag was on it last frame; and the let-go already sent it home. */
        var wasDragged = false
        var letGoSent = false
        /** Pulled open by a finger still on the island: it scrolls the stack (followFinger). */
        var finger: SpreadFinger? = null
        var collapseFinger: SpreadCollapseFinger? = null
        /** The screen went off or into the doze while it was open; and when it came back on. */
        var slept = false
        var wokeAt = 0L
        /** NumState.inNumber as watchOpen last read it, set when the list opened: a fold is its change to true. */
        var lastInNumber: Boolean? = null
        var lastFrameAt = 0L
        /** The progress the clock was last asked to follow (ClockRoom.roomForRows). */
        var clockP = Float.NaN
    }

    var spread: Spread? = null

    /**
     * The list was open when the phone was unlocked: it opens again on the next lock screen, once
     * the row is up with the stack island in it ([reopenKeptList]). Folded, it is not kept.
     */
    private var listKept = false
    private var listKeptShownAt = 0L

    fun reopenKeptList(shown: Boolean) {
        if (!listKept) return
        val now = android.os.SystemClock.uptimeMillis()
        val awake = !MiniPlayerScene.aodActive && Main.screenOnCached()
        if (!shown || !awake || spread != null || c.player?.visibility != View.VISIBLE) {
            listKeptShownAt = 0L
            return
        }
        if (STACK_ISLAND in c.islandKeys && LockIslands.foldsNatively()) {
            listKept = false
            listKeptShownAt = 0L
            c.handler.post { if (spread == null) openSpread("kept over the unlock") }
            return
        }
        // Up without the stack island - its notifications went meanwhile: nothing to open.
        if (listKeptShownAt == 0L) listKeptShownAt = now
        else if (now - listKeptShownAt > LIST_KEPT_WAIT_MS) listKept = false
    }

    fun spreadCollapseAt(x: Float, y: Float): Boolean {
        val s = spread ?: return false
        if (s.phase != SPREAD_OPEN && s.phase != SPREAD_CLOSING) return false
        val stack = c.stackHost.notificationStack() ?: return false
        val rect = android.graphics.Rect()
        return (0 until stack.childCount).any { i ->
            val row = stack.getChildAt(i)
            (row.javaClass.name.contains("ExpandableNotificationRow") ||
                s.items.any { it.native === row }) && row.isShown && row.alpha > 0f &&
                row.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt())
        }
    }

    fun beginSpreadCollapse(startY: Float): Boolean {
        val s = spread ?: return false
        if (s.phase != SPREAD_OPEN && s.phase != SPREAD_CLOSING) return false
        if (s.phase == SPREAD_OPEN) {
            s.islandsY = NumState.scrollTo("NUMBER") ?: s.islandsY
            startSpreadMorphs(s, toCards = false)
            s.phase = SPREAD_CLOSING
        }
        s.collapseFinger = SpreadCollapseFinger(startY, s.progress, collapseRange(s))
        s.since = android.os.SystemClock.uptimeMillis()
        s.visualAt = s.since
        s.still = 0
        s.nudged = false
        s.letGoSent = true
        spreadTrace("spread collapse owned by islands")
        return true
    }

    /**
     * How far a pull down takes the list home. Stacked, the PR's 220dp. Plain, from the stack
     * island's card to its place in the row: the card stays under the finger. Left to the
     * stack's own drag, a short pull down was the stack's fold, which ran home on its own spring
     * and left the finger behind (#73, filmed 2026-10-14).
     */
    private fun collapseRange(s: Spread): Float {
        val fallback = c.dp(220f).toFloat()
        if (s.stacked) return fallback
        val pill = c.player?.restBoxOnScreen() ?: return fallback
        val row = (s.items.firstOrNull { it.lead } ?: s.items.firstOrNull { it.native != null })
            ?.native?.takeIf { it.isAttachedToWindow } ?: return fallback
        val xy = IntArray(2).also(row::getLocationOnScreen)
        return kotlin.math.abs(pill.y - xy[1]).coerceAtLeast(c.dp(120f).toFloat())
    }

    fun moveSpreadCollapse(y: Float) {
        val s = spread ?: return
        val finger = s.collapseFinger?.takeIf { !it.ended } ?: return
        finger.y = y
        val p = finger.progress()
        NumState.setScroll((s.islandsY + (s.cardsY - s.islandsY) * p).roundToInt())
    }

    fun endSpreadCollapse(velocityY: Float, cancelled: Boolean) {
        val s = spread ?: return
        val finger = s.collapseFinger?.takeIf { !it.ended } ?: return
        finger.target = if (MiniPlayerStackGesture.commits(
                finger.y - finger.startY, velocityY, c.density(), cancelled)) 0f else 1f
        // From the finger's last place and speed: down the screen is toward the islands (0).
        finger.motion.value = finger.progress()
        finger.motion.velocity = if (cancelled || finger.range <= 0f) 0f
            else (-velocityY / finger.range).coerceIn(-COLLAPSE_RELEASE_SPEED, COLLAPSE_RELEASE_SPEED)
        finger.motion.target = finger.target
        finger.ended = true
        s.still = 0
        NumState.goTo(if (finger.target == 0f) "NUMBER" else "LIST", why = "island collapse released")
    }

    /** For `op mini`. */
    fun describeSpread(): String = spread?.let { s ->
        "spread ph=${s.phase} p=${"%.2f".format(s.rawProgress)}/${"%.2f".format(s.progress)} " +
            "y=${s.lastY} ${s.islandsY}->${s.cardsY} " +
            "stack=${s.stackSeat} focus=${s.focusBefore}->${NumState.focusCount()} items=" + s.items.joinToString(",") {
                "${it.key.takeLast(6)}:${it.seat}${if (it.morph?.active == true) "m" else ""}" +
                    "${if (it.native != null) "n" else ""}${if (it.wasOut) "o" else ""}"
            }
    } ?: "spread none"

    private val spreadListener = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = false
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {}
    }

    /**
     * Busy for a lone stacked card's list, from a pull: the motions that keep it from starting (M0,
     * the subset as it was).
     */
    private fun busyForStackSpread(): Boolean = spread != null ||
        c.morph != null || c.cardExchange.exchange != null || c.noteMorphMotion.noteMorphKey != null || c.groupMotion.group != null ||
        c.noteMorphMotion.flight != null

    /** Even a lone stacked card uses the list whose downward pull returns it to its island. */
    fun beginStackSpread(startY: Float): Boolean {
        if (!c.stackedStyle() || c.islandKeys.isEmpty() || busyForStackSpread() ||
            c.player?.visibility != View.VISIBLE) return false
        // The music alone, and no notification in the stack either: there is no list to spread
        // out to. The stack read its fold again a moment after the lift, the spread folded with
        // it, and the card went back into the pill by itself every time (Xiaomi 13, 2026-10-14).
        // Pulled up as the row style pulls it: into the card, and the card chosen.
        if (c.islandKeys.all { it == MUSIC_ISLAND } && !stackHasRows()) return false
        openSpread("stack swipe", SpreadFinger(startY))
        return spread != null
    }

    /** Any notification row in the lock screen's stack, folded away or not - the music's aside. */
    private fun stackHasRows(): Boolean {
        val stack = c.stackHost.notificationStack() ?: return true
        for (i in 0 until stack.childCount) {
            val row = stack.getChildAt(i)
            if (!row.javaClass.name.contains("ExpandableNotificationRow")) continue
            val media = runCatching {
                val entry = Xp.callMethod(row, "getEntry")
                (Xp.getObjectField(entry, "mSbn") as? android.service.notification.StatusBarNotification)
                    ?.notification?.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true
            }.getOrDefault(false)
            if (!media) return true
        }
        return false
    }

    /**
     * The stack island tapped with one app's notifications in it: that app's newest one opened,
     * as a tap on its own row opens it - the row's click (NotificationClicker), which wakes,
     * asks the keyguard to go first where it has to, and starts the notification's intent. A
     * spread out to a list of one app's notifications was a step on the way to the same place.
     * False when there is more than one app, nothing to open, or no row to click: then it opens.
     */
    fun openSingleApp(why: String): Boolean {
        val key = LockIslands.stackSingleAppLead() ?: return false
        val stack = c.stackHost.notificationStack() ?: return false
        var row: View? = null
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            // Its own row, never its group's: the group's click expands the group instead.
            row = if (c.stackHost.rowKey(child) == key) child else c.stackHost.childRows(child).firstOrNull { c.stackHost.rowKey(it) == key }
            if (row != null) break
        }
        if (row == null || !row.hasOnClickListeners()) {
            MiniPlayerRuntime.noteTouch("open app ($why): no row for ${key.takeLast(6)}, spread instead")
            return false
        }
        MiniPlayerRuntime.noteTouch("open app ($why): ${key.substringAfter('|').substringBefore('|')}")
        // The row's own listener (ExpandableNotificationRowInjector$1) only posts the click to a
        // flow, whose collector dropped it for a row folded away: tapped, nothing happened
        // (2026-10-07). NotificationClicker, which the flow ends in, is the injector's
        // clickListener (NotificationRowBinderImpl): called straight, as the flow would.
        val clicker = runCatching {
            Xp.getObjectField(Xp.callMethod(row, "getInjector"), "clickListener") as? View.OnClickListener
        }.getOrNull()
        if (clicker != null) clicker.onClick(row) else row.performClick()
        return true
    }

    /**
     * Busy for the whole row out as the stack's list (a spread already out is asked first, on its
     * own): the motions that keep it from starting (M0, the subset as it was).
     */
    private fun busyForSpread(): Boolean = c.morph != null || c.cardExchange.exchange != null || c.noteMorphMotion.noteMorphKey != null || c.groupMotion.group != null ||
        c.noteMorphMotion.flight != null

    /**
     * The stack island tapped or pulled open: the whole row out. With another motion under way -
     * a card out, a switch, a flight - only the stack opens, as it did before (openStack).
     */
    fun openSpread(why: String, finger: SpreadFinger? = null) { android.os.Trace.beginSection("MC openSpread"); try {
        if (spread != null) return
        val busy = busyForSpread()
        if (busy || c.player?.visibility != View.VISIBLE) {
            MiniPlayerRuntime.noteTouch("spread refused ($why): busy=$busy; the stack alone")
            LockIslands.openStack(why)
            return
        }
        c.swapMotion.endSwap()
        c.endRow()
        c.resetIslandDrag()
        c.clearSmallNudge()
        val big = c.selectedIsland
        val small = c.smallKey
        fun seatOf(key: String) = when (key) {
            big -> SEAT_BIG
            small -> SEAT_SMALL
            else -> SEAT_HIDDEN
        }
        // A page up behind the lock screen (高德's map, a countdown) goes: the list opens on the
        // plain lock screen, the page's notification one of its cards (the user, 2026-09-30).
        LockIslands.closeSceneForList()
        // Out as their cards already: in the list as they are, and home with the rest when it
        // folds - into no place of the row's, fading with the list as the placeless ones do.
        val out = LockIslands.releasedKeys().filter {
            it != STACK_ISLAND && it !in c.islandKeys && c.stackHost.rowFor(it) != null
        }
        val items = c.islandKeys.filter { it != STACK_ISLAND }.flatMap { key ->
            listOf(SpreadItem(key, seatOf(key))) +
                if (LockIslands.isAppGroup(key)) LockIslands.membersOf(key)
                    .map { SpreadItem(it, SEAT_HIDDEN) } else emptyList()
        } + out.map { SpreadItem(it, SEAT_HIDDEN, wasOut = true) }
        val stackSeat = if (STACK_ISLAND in c.islandKeys) seatOf(STACK_ISLAND) else SEAT_NONE
        // The stack island goes out as the others do, into its newest notification's row as the
        // list opens. Without it the island only faded where it stood while the rows came out
        // of the fold (#73): the one island in the row with no way out.
        val lead = if (stackSeat == SEAT_BIG || stackSeat == SEAT_SMALL)
            listOf(SpreadItem(STACK_ISLAND, stackSeat)) else emptyList()
        val s = Spread(lead + items, stackSeat, c.stackedStyle())
        s.since = android.os.SystemClock.uptimeMillis()
        // Read before the spread's rows come in (setSpread runs the pipeline on the next frame).
        s.focusBefore = NumState.focusCount()
        s.finger = finger
        spread = s
        // A card held where it landed goes with the list from here.
        c.stackHost.releaseNativeScrollPin()
        c.player?.setInteractionsEnabled(false)
        // Every focus notification's row into the stack, out of sight till its morph takes it.
        LockIslands.setSpread(true)
        spreadTrace("spread asked ($why): " + describeSpread())
        MiniPlayerRuntime.noteTouch("spread ($why) items=${items.size} stack=${s.stackSeat}")
        Gen.unframe(spreadFrame)
        Gen.frame(spreadFrame)
    } finally { android.os.Trace.endSection() } }

    val spreadFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC spread"); try {
            val s = spread ?: return
            if (c.destroyed || !Main.keyguardLocked() || MiniPlayerScene.keyguardGoingAway) {
                // Open, it opens again on the next lock screen (the user, 2026-10-24).
                listKept = s.phase == SPREAD_OPEN && s.progress >= 0.5f
                endSpread(folded = s.progress < 0.5f, why = "lock screen gone")
                return
            }
            // No frames for a while: the screen was off.
            val frameAt = android.os.SystemClock.uptimeMillis()
            if (s.lastFrameAt != 0L && frameAt - s.lastFrameAt > SPREAD_SLEEP_GAP_MS) s.slept = true
            s.lastFrameAt = frameAt
            when (s.phase) {
                SPREAD_WAIT -> waitSpread(s)
                SPREAD_OPEN -> watchOpen(s)
                else -> moveSpread(s)
            }
            if (spread === s) Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * The focus rows coming into the stack: once they are all laid out - or ROW_WAIT_MS has
     * passed - the stack is asked for its list. Not before: the list's place is worked out once,
     * from the rows there when it is asked for (keyguard-list-state), and asked early it left
     * them out and the cards came up over the list.
     */
    private fun waitSpread(s: Spread) {
        var ready = true
        var coming = 0
        for (item in s.items) {
            // A card out already is in the list as it is.
            if (item.key == MUSIC_ISLAND || item.wasOut) continue
            if (LockIslands.noteFor(item.key)?.focus == true) coming++
            val row = c.stackHost.rowFor(item.key)
            if (row == null || !row.isAttachedToWindow || !row.isLaidOut || row.width <= 0 || row.height <= 0) {
                ready = false
                continue
            }
            c.stackHost.hideRow(row)
        }
        // Laid out is not yet measured by the stack: its sample (the list's place is worked out
        // from it) counts them a layout pass later. Asked in between, the list's place left them
        // out, and the stack came to rest as its pile - the list one time, the pile the next
        // (the user, 2026-09-30). The sample's count taking them is also the stack's own answer
        // to them coming in (its flows are synchronous): the list asked after that is not undone.
        val measured = s.focusBefore?.let { before -> NumState.focusCount()?.let { it >= before + coming } } ?: true
        val waited = android.os.SystemClock.uptimeMillis() - s.since
        if ((!ready || !measured) && waited < ROW_WAIT_MS) return
        // Let go before the rows were in, short of opening: nothing opened.
        val finger = s.finger
        if (finger != null && finger.ended && !finger.toList) {
            endSpread(folded = true, why = "let go before the list")
            return
        }
        if (finger?.ended == true) s.finger = null
        val held = s.finger
        s.islandsY = NumState.scrollTo("NUMBER") ?: NumState.position() ?: 0
        // As far as the stack really scrolls: aimed past it, the spread stood short of its end.
        s.cardsY = NumState.listScroll() ?: s.islandsY
        startSpreadMorphs(s, toCards = true)
        s.phase = SPREAD_OPENING
        s.since = android.os.SystemClock.uptimeMillis()
        s.visualAt = s.since
        s.still = 0
        s.lastY = Int.MIN_VALUE
        // Under a finger the stack goes where it takes it (followFinger), from where it is now.
        LockIslands.openStack("spread", scroll = held == null)
        if (held != null) {
            // From the pill to the row as it is now, and on by the scroll still to come: the
            // row rides the stack's scroll up to its place in the list.
            held.span = if (s.stacked) 0f
                else collapseRange(s) + kotlin.math.abs(s.cardsY - s.islandsY)
            // Started from where the pill was lifted to, not from its rest: the lift is already
            // part of the way out.
            held.base = held.pulled() - if (s.stacked) 0f else held.lift
        }
        // A short or unavailable scroll range can read as fully open on this first frame.
        // The visible islands still begin at their folded pose and catch up over real frames.
        s.rawProgress = if (held != null && held.span > 0f) {
            followFinger(s, held)
            fingerProgress(held)
        } else spreadProgress(s, NumState.position() ?: s.islandsY)
        applySpread(s, if (s.stacked) 0f else s.rawProgress)
        spreadTrace("spread out after ${waited}ms ready=$ready measured=$measured: " + describeSpread())
    }

    /**
     * Every island with a place in the row sets out for its card, held at [toCards]'s start and
     * posed by the progress from then on. One with no card, or no place, fades where it is.
     */
    private fun startSpreadMorphs(s: Spread, toCards: Boolean) {
        endLeadTail()
        val pill = c.player
        val claimedRows = HashSet<View>()
        // Closing, the row is drawn again: its places fade in as the cards come down.
        if (!toCards) pill?.visibility = View.VISIBLE
        for (item in s.items) {
            val native = c.nativeFor(item.key)
            // An app's several notification keys can resolve to one OEM group row. Only its
            // group island morphs that shared row; a second morph would fight its matrix.
            if (native != null && !claimedRows.add(native)) continue
            item.native = native
            if (native == null) continue
            // Out as its card already: it stays as it is while the list opens round it.
            if (toCards && item.wasOut) continue
            if (item.key == MUSIC_ISLAND) {
                // The media card is the spread's: the OEM may show it (hardSuppressionActive).
                c.updateNativeSuppression(false)
                c.ensureNativeHeaderVisible()
            }
            if (item.seat == SEAT_HIDDEN && (!s.stacked || item.wasOut)) {
                c.stackHost.hideRow(native)
                continue
            }
            // Out of sight while it waited: the morph saves the row's own alpha, not ours.
            c.stackHost.showRow(native)
            val small = item.seat != SEAT_BIG
            val view = if (small) c.prepareSmallView(item.key, pooled = true) else pill
            if (view == null) {
                c.stackHost.hideRow(native)
                continue
            }
            view.visibility = View.VISIBLE
            // The morph measures the pill at rest; the finger's lift is in its progress
            // (waitSpread), so it is handed over rather than drawn twice.
            if (!small) view.clearNudge()
            view.alpha = if (s.stacked) when (item.seat) {
                SEAT_SMALL -> MiniPlayerGeometry.STACK_BACK_ALPHA
                SEAT_HIDDEN -> 0f
                else -> 1f
            } else 1f
            val m = MiniCardMorph(view, native, toCards, spreadListener, c.noteMorphMotion.landingFor(item.key, native),
                restBox = if (small) { { c.smallBoxOnScreen() } } else null,
                circle = small && !s.stacked,
                holdMaterial = item.lead && !s.stacked)
            if (!m.startDragging()) {
                if (small) c.recycleSmallView(view)
                if (item.lead) {
                    // No morph: the island fades in its place, as before, and the row is the
                    // stack's own to unfold - not faded by the item's own path as well.
                    c.stackHost.showRow(native)
                    item.native = null
                } else c.stackHost.hideRow(native)
                continue
            }
            item.view = view
            item.standIn = small
            item.morph = m
            if (item.lead) holdLeadThroughFold(native)
            // The stand-in is the small island's place from here, in its very shape.
            if (item.seat == SEAT_SMALL) c.smallIsland?.visibility = View.GONE
        }
    }

    /** 0 at the islands' end, 1 at the cards'. */
    private fun spreadProgress(s: Spread, y: Int): Float {
        val span = (s.cardsY - s.islandsY).toFloat()
        if (s.stacked && kotlin.math.abs(span) < c.dp(72f)) {
            s.finger?.takeIf { !it.ended && !it.base.isNaN() }?.let { finger ->
                return ((finger.pulled() - finger.base) / c.dp(220f)).coerceIn(0f, 1f)
            }
        }
        if (kotlin.math.abs(span) < 1f) return if (s.phase == SPREAD_CLOSING) 0f else 1f
        return ((y - s.islandsY) / span).coerceIn(0f, 1f)
    }

    /** Bound abrupt list-scroll updates while leaving a held finger directly in control. */
    private fun visibleSpreadProgress(s: Spread, target: Float): Float {
        if (!s.stacked) return target
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = (now - s.visualAt).coerceIn(0L, 50L).toFloat()
        s.visualAt = now
        if (s.finger?.ended == false || s.collapseFinger?.ended == false) return target
        if (elapsed <= 0f) return s.progress
        val eased = (target - s.progress) * (1f - kotlin.math.exp(-elapsed / 80f))
        val maxStep = elapsed / 220f
        return (s.progress + eased.coerceIn(-maxStep, maxStep)).coerceIn(0f, 1f)
    }

    private fun applySpread(s: Spread, p: Float) {
        s.progress = p
        var hiddenTurn = 0
        for (item in s.items) {
            val itemProgress = if (s.stacked && item.seat == SEAT_HIDDEN && !item.wasOut) {
                hiddenTurn++
                val delay = (hiddenTurn * 0.075f).coerceAtMost(0.225f)
                ((p - delay) / (1f - delay)).coerceIn(0f, 1f)
            } else p
            val m = item.morph
            if (m != null) {
                m.led(itemProgress)
                if (s.stacked && item.standIn) item.view?.alpha = when (item.seat) {
                    SEAT_SMALL -> MiniPlayerGeometry.STACK_BACK_ALPHA +
                        (1f - MiniPlayerGeometry.STACK_BACK_ALPHA) * itemProgress
                    SEAT_HIDDEN -> itemProgress
                    else -> 1f
                }
                continue
            }
            // Out as its card from before: whole while the list opens, fading only as it folds.
            if (item.wasOut && s.phase != SPREAD_CLOSING) continue
            // No morph: its card fades in with the list, its place fades out, and back.
            item.native?.takeIf { it.isAttachedToWindow }?.let {
                if (kotlin.math.abs(it.transitionAlpha - itemProgress) > 0.002f)
                    it.transitionAlpha = itemProgress
            }
            fadeSeat(item.seat, 1f - itemProgress)
        }
        // Morphing, the stack island's place is the morph's view: fading it too fought the morph.
        if (s.items.none { it.lead && it.morph != null }) fadeSeat(s.stackSeat, 1f - p)
    }

    /**
     * The stack island's row under its morph, whole. An ordinary row is multiplied by the fold's
     * numStateAlpha (ExpandableViewBase.onPropertiesUpdate: extAlpha = folmeAlpha x it), 0 while
     * the stack reads NUMBER and springing to 1 only once it reads LIST - most of the way out. The
     * morph handed over to a row not there yet: the pill's glass gone, the row's not come, the
     * icon alone in the air for a few frames (#73, filmed 2026-10-14). Focus rows and the media
     * card are not the fold's, which is why theirs never did this. Written in pre-draw, after the
     * fold's own write for the frame; given back to the fold's state when the morph is over.
     */
    private var leadHeld: Pair<View, ViewTreeObserver.OnPreDrawListener>? = null

    /** The pill still fading out on the lead row after an opening landed (MiniCardMorph.handOff). */
    private var leadTail: MiniCardMorph? = null

    /** Whatever of the landing's fade is left, over now: the pill is wanted for something else. */
    fun endLeadTail() {
        val m = leadTail ?: return
        leadTail = null
        m.cancel()
    }

    /**
     * When the open list asked for the hold to go ([letLeadGoWhenSettled]); 0 while the spread
     * still needs it. The stack's own fade of the row to 1 starts when it reads LIST, and a
     * quick pull can land before it is done: let go then, the fade would have the row part
     * see-through for its last frames. A guard (#122): measured on a slow pull the fade had
     * reached 1 before the landing, and the see-through frame filmed then was the glass, not
     * this - but nothing holds a quick one.
     */
    private var leadLetGoAt = 0L

    fun holdLeadThroughFold(row: View?) {
        val held = leadHeld
        if (held != null && held.first === row) {
            leadLetGoAt = 0L
            return
        }
        leadLetGoAt = 0L
        if (held != null) {
            Gen.unpreDraw(held.first, held.second)
            setFoldAlpha(held.first, if (NumState.inNumber == true) 0f else 1f)
        }
        leadHeld = null
        if (row == null) return
        val l = ViewTreeObserver.OnPreDrawListener {
            setFoldAlpha(row, 1f)
            val since = leadLetGoAt
            if (since != 0L) {
                val waited = android.os.SystemClock.uptimeMillis() - since
                if (waited > LEAD_LET_GO_MAX_MS || NumState.inNumber == false && !NumState.busy()) {
                    // Not from inside the observer's own dispatch.
                    Gen.post { if (leadHeld?.first === row && leadLetGoAt != 0L) holdLeadThroughFold(null) }
                }
            }
            true
        }
        Gen.preDraw(row, l)
        leadHeld = row to l
        setFoldAlpha(row, 1f)
    }

    /** The list is open: the row is the stack's again once its own fade has reached it. */
    private fun letLeadGoWhenSettled() {
        if (leadHeld == null) return
        if (leadLetGoAt == 0L) leadLetGoAt = android.os.SystemClock.uptimeMillis()
    }

    private fun setFoldAlpha(row: View, a: Float) {
        runCatching {
            val injector = Xp.callMethod(row, "getInjector")
            if (Xp.getObjectField(injector, "numStateAlpha") as Float == a) return
            Xp.setObjectField(injector, "numStateAlpha", a)
            val folme = Xp.getObjectField(Xp.getObjectField(Xp.callMethod(row, "getViewState"),
                "mInjector"), "folmeAlpha") as Float
            Xp.callMethod(row, "setExtAlpha", folme * a)
        }
    }

    private fun fadeSeat(seat: Int, a: Float) {
        val v: View = when (seat) {
            SEAT_BIG -> c.player
            SEAT_SMALL -> c.smallIsland
            else -> null
        } ?: return
        val alpha = if (seat == SEAT_SMALL && c.stackedStyle())
            a * MiniPlayerGeometry.STACK_BACK_ALPHA else a
        if (alpha > 0f && v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        if (kotlin.math.abs(v.alpha - alpha) > 0.002f) v.alpha = alpha
    }

    /** Opening or closing: every island on the stack's scroll, until the stack rests at an end. */
    private fun moveSpread(s: Spread) {
        s.finger?.takeIf { !it.ended && s.phase == SPREAD_OPENING }?.let { followFinger(s, it) }
        val y = NumState.position() ?: return
        s.collapseFinger?.step()
        val p = s.collapseFinger?.progress() ?: spreadProgress(s, y)
        s.rawProgress = p
        if (s.items.any { it.morph?.active == false }) {
            // The lock screen's guard ended a morph (asleep, say): straight to the nearer end.
            endSpread(folded = p < 0.5f, why = "a morph was ended")
            return
        }
        applySpread(s, visibleSpreadProgress(s, p))
        followClock(s)
        if (y != s.lastY) spreadFrameLog(s, y, p)
        // Held by the finger, it rests where the finger does; the let-go sends it to an end.
        if (s.finger?.ended == false || s.collapseFinger?.ended == false) {
            s.lastY = y
            s.still = 0
            return
        }
        // At an end and the stack already reading that state, no finger on it: done now. The fold
        // overshoots - the scroll ran to -275 and took 700ms coming back to 0 - and waiting for it
        // to stand still held the media card out for all of it after the islands had landed
        // (the user's "pause" on a pull down, 2026-09-30). The finger by the stack's own drag
        // alone: the overshoot is a pull past the scroll too (os= in the frame log), not a finger.
        if (!stackDragged()) {
            if (s.phase == SPREAD_CLOSING && p <= 0.02f && s.progress <= 0.005f &&
                NumState.inNumber == true) {
                endSpread(folded = true, why = "folded")
                return
            }
            if (s.phase == SPREAD_OPENING && p >= 0.995f && s.progress >= 0.995f &&
                NumState.state() == "LIST") {
                finishOpen(s, y)
                return
            }
        }
        // Let go on the way home: sent there now. A fling never reaches the hands-up (NumState),
        // and a fling let go at 0.67 came to rest as the pile (filmed and logged 2026-09-30).
        val dragged = stackDragged()
        if (s.phase == SPREAD_CLOSING && s.wasDragged && !dragged && p < SPREAD_FOLD_BELOW && !s.letGoSent) {
            s.letGoSent = true
            NumState.goTo("NUMBER", why = "spread let go folding at ${"%.2f".format(p)}")
        }
        s.wasDragged = dragged
        // Still by the stack's own drag, not its pull: a fling left the pull at 44 for good, and
        // counted as a finger the spread never came to rest and stood half folded for 10s.
        // A let-go still springing home is moving: counted still, it was sent to the fold as
        // "stuck" three frames in, wherever the spring was taking it.
        val moving = s.collapseFinger?.let { !it.ended || it.settling() }
            ?: (y != s.lastY || NumState.busy() || dragged)
        s.still = if (moving) 0 else s.still + 1
        s.lastY = y
        // The native scroll can finish in one frame. Keep drawing the morph until its visible
        // progress reaches that end instead of handing all cards to SystemUI at once.
        if (s.stacked && kotlin.math.abs(s.progress - p) > 0.005f) {
            s.still = 0
            return
        }
        if (s.still < SPREAD_STILL_FRAMES) return
        val late = android.os.SystemClock.uptimeMillis() - s.since > SPREAD_GIVE_UP_MS
        when {
            p >= 0.98f -> finishOpen(s, y)
            p <= 0.02f -> endSpread(folded = true, why = "folded")
            // Pulled toward home and come to rest short of it: home, whichever end is nearer. Sent
            // to the nearer one, a pull let go at 0.57 was sent back out to the list (filmed
            // 2026-09-30).
            s.phase == SPREAD_CLOSING -> when {
                !s.nudged -> {
                    s.nudged = true
                    s.still = 0
                    NumState.goTo("NUMBER", why = "spread closing stuck at ${"%.2f".format(p)}")
                }
                late -> endSpread(folded = true, why = "gave up closing")
            }
            // Opening and come to rest a good way out, the stack no longer folded: the list ends
            // here - it goes no further than this, whatever its place was worked out to be.
            p >= SPREAD_OPEN_AT && NumState.inNumber != true -> {
                spreadTrace("spread opened short at ${"%.2f".format(p)} ($y of ${s.cardsY})")
                finishOpen(s, y)
            }
            !s.nudged -> {
                // At rest near the fold - a scroll that never came.
                s.nudged = true
                s.still = 0
                NumState.goTo("LIST", why = "spread stuck at ${"%.2f".format(p)}")
            }
            late -> endSpread(folded = true, why = "gave up")
        }
    }

    /** The spread's own events, in `op mini` and in `op numstate`'s frames with its frames. */
    private fun spreadTrace(what: String) {
        c.trace(what)
        NumState.frame(what.take(160))
    }

    /** One frame of the spread moving, for `op numstate` (frames=). */
    private fun spreadFrameLog(s: Spread, y: Int, p: Float) {
        val phase = when (s.phase) { SPREAD_OPENING -> "o"; SPREAD_CLOSING -> "c"; SPREAD_OPEN -> "O"; else -> "w" }
        NumState.frame("$phase y=$y sy=${NumState.scrollY()} os=${NumState.overScrollParts()} " +
            "p=${"%.2f".format(p)}/${"%.2f".format(s.progress)} ${s.islandsY}->${s.cardsY} d=${if (stackDragged()) 1 else 0}" +
            "${if (NumState.busy()) "b" else ""} st=${NumState.state()?.take(1)}" +
            (s.finger?.let { f -> " f=${f.pulled().toInt()}${if (f.ended) "e" else ""}" } ?: ""))
    }

    /**
     * The clock asked again whenever the cards have moved: past the stack's fold threshold the
     * stack says there is nothing on the lock screen and stops telling the clock anything, so
     * its size would stand wherever that left it. ClockRoom.roomForRows answers with this
     * spread's progress (spreadClockProgress).
     */
    private fun followClock(s: Spread) {
        if (!s.clockP.isNaN() && kotlin.math.abs(s.progress - s.clockP) < CLOCK_FOLLOW_STEP) return
        s.clockP = s.progress
        com.os4.musiccover.ClockRoom.reassertClockRoom()
    }

    /**
     * The visible progress of a spread on its way between the islands (0) and the list (1), for
     * the clock to follow: null when none is moving. Folding, the stack reads NUMBER 15% of the
     * way home and tells the clock its notifications are gone - the clock grew to full size in
     * four frames with the card still over the list, and shrank back if the finger did (#122).
     */
    fun spreadClockProgress(): Float? = spread
        ?.takeIf { it.phase == SPREAD_OPENING || it.phase == SPREAD_CLOSING }?.progress

    /** The spread is open as the list: what the stack tells the clock now is the list's place. */
    fun spreadListShown(): Boolean = spread?.phase == SPREAD_OPEN

    /** For NumState's hands-up: the row spread out is being pulled home. */
    fun spreadFolding(): Boolean {
        val s = spread ?: return false
        return s.phase == SPREAD_CLOSING && s.rawProgress < SPREAD_FOLD_BELOW
    }

    /** The stack scrolled out as far as the finger has pulled past where it started to follow. */
    private fun followFinger(s: Spread, f: SpreadFinger) {
        if (f.base.isNaN()) return
        val span = s.cardsY - s.islandsY
        val range = kotlin.math.abs(span).toFloat()
        // Plain: the finger's share of the way on screen, as that share of the scroll.
        val d = if (f.span > 0f) fingerProgress(f) * range
            else (f.pulled() - f.base).coerceAtLeast(0f).coerceAtMost(range)
        NumState.setScroll(s.islandsY + (if (span >= 0) d else -d).toInt())
    }

    /** How far out the finger has pulled the lead card, by its distance on screen. */
    private fun fingerProgress(f: SpreadFinger): Float =
        ((f.pulled() - f.base) / f.span).coerceIn(0f, 1f)

    /** The finger on a pulled-open spread moved (the pill's route, as noteDragMove). */
    fun spreadFingerMove(y: Float): Boolean {
        val f = spread?.finger?.takeIf { !it.ended } ?: return false
        f.y = y
        return true
    }

    /**
     * The finger let go of a pulled-open spread: flung, the way it was flung; else open once it
     * is a third of the way out, or still waiting for the rows (the pull was past the threshold).
     * The stack is sent to that end on its own spring, and the spread follows it there.
     */
    fun spreadFingerEnd(velocityY: Float, cancelled: Boolean): Boolean {
        val s = spread ?: return false
        val f = s.finger?.takeIf { !it.ended } ?: return false
        f.ended = true
        val fling = SPREAD_FLING_DP * c.density()
        f.toList = !cancelled && when {
            velocityY < -fling -> true
            velocityY > fling -> false
            s.phase == SPREAD_WAIT -> true
            else -> s.progress >= SPREAD_OPEN_AT
        }
        spreadTrace("spread let go p=${"%.2f".format(s.progress)} v=${velocityY.toInt()} list=${f.toList}")
        // Still waiting for the rows: waitSpread takes the decision when they are in.
        if (s.phase != SPREAD_OPENING) return true
        s.finger = null
        s.still = 0
        s.nudged = false
        NumState.goTo(if (f.toList) "LIST" else "NUMBER", why = "spread let go")
        return true
    }

    /** The list is open: the cards are the lock screen's own, the row's views put away. */
    private fun finishOpen(s: Spread, y: Int) {
        s.collapseFinger = null
        letLeadGoWhenSettled()
        for (item in s.items) {
            val m = item.morph
            if (m != null && item.lead && !s.stacked && m.active) {
                // The pill fades out on the row, which draws its own glass again from now
                // (MiniCardMorph.handOff); put away once it has gone.
                endLeadTail()
                leadTail = m
                m.handOff {
                    if (leadTail === m) leadTail = null
                    if (spread?.phase == SPREAD_OPEN) {
                        c.player?.visibility = View.INVISIBLE
                        c.player?.alpha = 1f
                    }
                }
            } else m?.cancel()
            item.morph = null
            item.view?.let { if (item.standIn) c.recycleSmallView(it) }
            item.view = null
            item.standIn = false
            item.native?.let(c.stackHost::showRow)
        }
        if (leadTail == null) {
            c.player?.visibility = View.INVISIBLE
            c.player?.alpha = 1f
        }
        c.smallIsland?.visibility = View.GONE
        c.smallIsland?.alpha = if (c.stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        if (s.items.any { it.key == MUSIC_ISLAND }) c.ensureNativeHeaderVisible()
        s.phase = SPREAD_OPEN
        s.cardsY = y
        s.still = 0
        s.lastInNumber = NumState.inNumber
        // A wake while it is open wakes to the list, not to the folded stack (watchOpen).
        NumState.wakeAsList(true)
        // The list's place read again, from the stack as it is now: asked from a sample still
        // short of the rows that came in, it was short of the list (the stack's pile). The cards
        // are the stack's own from here, so the rest of the way is its scroll alone.
        val list = NumState.listScroll()
        val state = NumState.state()
        if (!s.reaimed && list != null && kotlin.math.abs(list - y) > c.dp(2f) && state != "LIST") {
            s.reaimed = true
            NumState.goTo("LIST", why = "spread re-aimed ($state at $y, list at $list)")
        }
        spreadTrace("spread open ($state): " + describeSpread())
    }

    /** The stack's own finger on it (NotificationStackScrollLayout.mIsBeingDragged). */
    private fun stackDragged(): Boolean = runCatching {
        Xp.getObjectField(c.stackHost.notificationStack() ?: return false, "mIsBeingDragged") == true
    }.getOrDefault(false)

    /**
     * The list open: a finger pulling it down toward the fold starts every card home, and the
     * stack folding by itself (a wake to its default state) brings them home at once. Anything
     * else moving it - a notification coming or going - is the list's own: its rest follows.
     */
    private fun watchOpen(s: Spread) {
        // The list is kept over the doze (the user, 2026-10-24): the full-screen AOD scrolls the
        // stack to its count and the wake sends it to its default, and either folded it read as
        // the stack folding by itself - every card went home whenever the screen had gone off.
        // Asleep it is left be; woken, the stack goes back to the list if it is not there.
        val now = android.os.SystemClock.uptimeMillis()
        if (MiniPlayerScene.aodActive || !Main.screenOnCached()) {
            s.slept = true
            s.wokeAt = 0L
            return
        }
        if (s.slept) {
            s.slept = false
            s.wokeAt = now
            if (NumState.inNumber == true) NumState.goTo("LIST", why = "list kept over the doze")
            spreadTrace("spread woke: " + describeSpread())
        }
        if (s.wokeAt != 0L) {
            if (now - s.wokeAt < SPREAD_WAKE_MS && !listPulled() &&
                (NumState.inNumber == true || NumState.busy())) return
            s.wokeAt = 0L
        }
        val y = NumState.position() ?: return
        val islandsY = NumState.scrollTo("NUMBER") ?: return
        s.lastY = y
        val toward = if (s.cardsY >= islandsY) s.cardsY - y else y - s.cardsY
        // The stack folding by itself is its change from the list to the fold (exitNumState then
        // enterNumState), not its being folded. With no ordinary row in it - the row all focus
        // islands and the music - it never leaves the fold for a list: read as a level, every
        // spread went home 10ms after it had opened (three super islands, the user, 2026-11-10).
        // Not read asleep or in the wake's window above, so the one before the doze stays the base.
        val inNumber = NumState.inNumber
        val folded = s.lastInNumber == false && inNumber == true
        s.lastInNumber = inNumber
        if (folded || listPulled() && toward > c.dp(SPREAD_PULL_DP)) {
            s.islandsY = islandsY
            startSpreadMorphs(s, toCards = false)
            s.phase = SPREAD_CLOSING
            s.since = android.os.SystemClock.uptimeMillis()
            s.visualAt = s.since
            s.still = 0
            s.nudged = false
            s.wasDragged = stackDragged()
            s.letGoSent = false
            s.lastY = Int.MIN_VALUE
            s.rawProgress = spreadProgress(s, y)
            applySpread(s, if (s.stacked) 1f else s.rawProgress)
            spreadTrace("spread closing (${if (folded) "folded" else "pulled"}): " + describeSpread())
            return
        }
        if (!listPulled() && !NumState.busy()) s.cardsY = y
    }

    /**
     * A finger on the list: the stack's own drag, or its pull past the scroll - the keyguard's
     * pull down toward the fold is the latter alone, the scroll not moving till the let-go.
     */
    private fun listPulled(): Boolean = stackDragged() || kotlin.math.abs(NumState.overScroll()) > 0.5f

    /**
     * Over: [folded] the islands are back in their places and the focus rows go back out of the
     * stack, out of sight while it takes them away; else the cards stay as they are.
     */
    fun endSpread(folded: Boolean, why: String) {
        val s = spread ?: return
        endLeadTail()
        spread = null
        Gen.unframe(spreadFrame)
        holdLeadThroughFold(null)
        for (item in s.items) {
            item.morph?.cancel()
            item.morph = null
            item.view?.let { if (item.standIn) c.recycleSmallView(it) }
            item.view = null
            val native = item.native
            if (folded && item.wasOut) {
                // Folded with the rest: an island again. The stack gives it back a pipeline run
                // later; till then it keeps its place in the row (returning).
                native?.let { c.stackHost.hideRowUntilGone(it, item.key); it.transitionAlpha = 0f }
                LockIslands.recapture(item.key)
                c.stackHost.markReturning(item.key)
                c.noteMorphMotion.releasedFromPill -= item.key
                c.noteMorphMotion.releasedFromSmall -= item.key
                continue
            }
            if (native == null) continue
            // Folded natively the stack island's row never leaves the stack: the fold hides it.
            val stays = item.key == MUSIC_ISLAND || item.lead && LockIslands.foldsNatively()
            if (folded && !stays) c.stackHost.hideRowUntilGone(native, item.key)
            else c.stackHost.showRow(native)
        }
        c.player?.alpha = 1f
        if (folded) c.player?.visibility = View.VISIBLE
        // Let go before the rows were in: the pill was held where the finger lifted it.
        c.player?.springNudgeBack(0f, 0f)
        c.smallIsland?.alpha = if (c.stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        // The small island's place as the fold left it: whoever came down into it stays. Nobody
        // did, and a card out from before came home: that one comes up there, growing in (the
        // row's own appearing) - after the pill, next in the row, it would have been left out.
        val cameHome = if (folded) s.items.firstOrNull { it.wasOut }?.key else null
        val smallTaken = s.stackSeat == SEAT_SMALL || s.items.any { it.seat == SEAT_SMALL }
        if (cameHome != null) c.preferredSmall = if (smallTaken) c.smallKey else cameHome
        c.snapSmallOnce = smallTaken || cameHome == null
        spreadTrace("spread end ($why) folded=$folded")
        MiniPlayerRuntime.noteTouch("spread end ($why) folded=$folded")
        if (folded) listKept = false
        NumState.wakeAsList(false)
        LockIslands.setSpread(false, folded)
        // Folded home but the stack left at its pile (a let-go it read as the pile): folded too.
        if (folded) NumState.foldIfPiled("spread folded")
        // The row as it is now: the media card put away again, the small island in its place.
        c.refresh()
        c.schedulePosition()
    }
}
