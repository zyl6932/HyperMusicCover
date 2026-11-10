package com.os4.musiccover.island

import com.os4.musiccover.CardTouch
import com.os4.musiccover.ClockRoomPolicy
import com.os4.musiccover.Main
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import android.graphics.Matrix
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1), and given only
 * what it uses of the controller (M2, MiniPlayerParts.kt). See the section comments below for
 * what it is.
 */
internal class StackHost(
    private val views: RowViews,
    private val rowState: RowState,
    private val edits: RowEdits,
    private val rowHost: RowHost,
    private val motions: Motions,
    // Made before this part: the motions whose rows it hides, holds and lets go.
    private val cardExchange: CardExchange,
    private val groupMotion: GroupMotion,
    // Made after this part: read when the stack is walked, not when this is built.
    private val noteMorphRef: () -> NoteMorphMotion,
    private val spreadRef: () -> SpreadMotion,
) {
    private val noteMorphMotion get() = noteMorphRef()
    private val spreadMotion get() = spreadRef()

    // ---- a card landing where the stack is settling it

    /**
     * How far down [native] is still to go, on screen: from where the stack has it this frame
     * to where it will have it once [leaving]'s row and the rows already on their way out are
     * gone. The keyguard's stack is laid out from the bottom up, so every row going away under
     * a card moves that card down by the room it took. A card opening while the one it replaces
     * was still in the stack landed above that one's row and was slid down to the bottom by the
     * stack half a second later (filmed 2026-09-25); it lands at the bottom now. The stack's own
     * target (the view state it is animating each row to) is taken, not where it is mid-animation.
     */
    internal fun settleDy(native: View, leaving: Collection<String>): Float { return traced("MC settleDy") {
        val stack = notificationStack() ?: return 0f
        if (native.parent !== stack) return 0f
        val gone = HashSet<View>()
        for (k in leaving) if (k != MUSIC_ISLAND) findRow(k)?.let { (row, top) -> if (row === top) gone += top }
        val kids = ArrayList<View>()
        for (i in 0 until stack.childCount) {
            val c = stack.getChildAt(i)
            if (c.visibility == View.GONE || c.height <= 0) continue
            val row = c.javaClass.name.contains("ExpandableNotificationRow")
            if (!row && c !== native) continue
            kids += c
            // Not the stack island's piled rows while it opens: hidden while they come up out of
            // the pile, they stay. Counted as leaving, the island's card flew 244px low, to where
            // WeChat's row was, and was pulled back up when the pile ended. Going home they go
            // with it: kept, a focus card let out in its place landed a row too high (2026-09-26).
            if (row && c !== native && c in hiddenRows && (c !in groupMotion.pileRows || groupMotion.stackGoingHome())) gone += c
        }
        val top = kids.associateWith { stackTargetY(it) + it.top }
        val nativeTop = top[native] ?: return 0f
        var shift = 0f
        var above = Float.NaN
        val now = android.os.SystemClock.uptimeMillis()
        val recordDiagnostic = now - settleDiagnosticAt >= 100L
        val goneTrace = if (recordDiagnostic) StringBuilder() else null
        // Rows given back early are transient views, not among these: what is left here is only
        // a row the stack has not been told about yet, for the frame or two until it has.
        for (c in kids.sortedBy { top[it] }) {
            val t = top[c] ?: continue
            val bottom = t + stackTargetHeight(c)
            if (c in gone && t > nativeTop && !above.isNaN()) shift += bottom - above
            if (c in gone && goneTrace != null) goneTrace.append(if (t > nativeTop) " v" else " ^").append(t.toInt())
                .append('+').append((bottom - t).toInt()).append("/ty").append(c.translationY.toInt())
            above = bottom
        }
        // The stack's own target, plus the room of the rows it has not been told are going yet.
        val stackTarget = stackTargetY(native)
        // Where a card rests alone at the bottom of the pile, learned while the stack is one.
        if (gone.isEmpty() && shift == 0f) learnBottomLine(kids, top)
        // Never past that bottom: the rows going are counted as if the stack were piled from the
        // bottom up, and in the stack island's list they reach below it - the stopwatch, laid out
        // over the list's three rows on its way up, was sent 244px under its place and pulled back
        // up once they had gone (2026-09-26).
        // In the stack's translations, as stackTarget is: the line is laid-out top plus translation.
        val floor = stackBottomLine.takeIf { shift > 0f && !it.isNaN() }
            ?.let { it - stackTargetHeight(native) - native.top }
        val target = if (floor != null) minOf(stackTarget + shift, floor) else stackTarget + shift
        lastGone = gone.size
        lastStackTarget = stackTarget
        if (recordDiagnostic) {
            settleDiagnosticAt = now
            lastSettle = (if (floor != null && stackTarget + shift > floor) "floor=${floor.toInt()} " else "") +
            "t=${target.toInt()} st=${stackTarget.toInt()}${if (targetRead) "" else "?"} " +
            "sh=${shift.toInt()} gone=${gone.size}$goneTrace kept=${kept.size} " +
            "ty=${native.translationY.toInt()} " +
            "rows=${kids.size} mt=${Main.liveMediaTop().let { if (it.isNaN()) "-" else it.toInt().toString() }}"
        }
        return target - native.translationY
    } }

    /**
     * The bottom of the keyguard stack's pile - where a lone card's bottom rests - in the stack's
     * own translation space; NaN until seen. Read off the rows as they are laid out while the
     * stack is a pile (not the island's list, which runs on below it), nothing leaving.
     */
    private var stackBottomLine = Float.NaN
    private var bottomLineAt = 0L

    private fun learnBottomLine(kids: List<View>, top: Map<View, Float>) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - bottomLineAt < 500L) return
        bottomLineAt = now
        if (stackState() != "STACK") return
        // Laid-out top plus translation, as settleDy's tops are.
        val bottom = kids.mapNotNull { c -> top[c]?.let { it + stackTargetHeight(c) } }.maxOrNull() ?: return
        stackBottomLine = bottom
    }

    /** The last settleDy's count of rows still on their way out. */
    private var lastGone = 0

    /** The stack's own target for the card, as the last settleDy read it. */
    internal var lastStackTarget = 0f

    /** The last settleDy reading, for the landing trace. */
    internal var lastSettle = ""
    private var settleDiagnosticAt = 0L
    private var contentDiagnosticAt = 0L

    /**
     * The translation the stack is taking [v] to: its view state's mYTranslation, which the
     * stack's animator runs translationY to (ViewState.startYTranslationAnimation) - else where
     * it is. Asked by the AOSP names first, it read where the row was every frame (2026-09-25).
     */
    fun stackTargetY(v: View): Float = runCatching {
        val state = Xp.callMethod(v, "getViewState")!!
        (Xp.getObjectField(state, "mYTranslation") as Number).toFloat().also { targetRead = true }
    }.getOrElse {
        targetRead = false
        v.translationY
    }

    /** Whether the last stackTargetY read the stack's own target. */
    private var targetRead = false

    private var contentTopAt = 0L
    private var contentTop = Float.NaN
    /** The same rows as [contentTop], where they are drawn this frame rather than headed. */
    private var contentDrawnTop = Float.NaN

    /**
     * [stackContentTop]'s rows where they are drawn now, on screen: for what has to keep off them
     * frame for frame - the countdown's disc - rather than make room for where they will be.
     */
    fun stackDrawnTop(): Float =
        if (stackContentTop().isNaN()) Float.NaN else contentDrawnTop

    /**
     * See the static stackContentTop: every row and card in the stack, at the stack's own target -
     * but not the rows this controller has hidden. A notification gone into an island keeps its
     * row in the stack, invisible, and the stack lays out from the bottom up: with the stopwatch
     * let out under it, the hidden QQ row stood 258px above the stopwatch's card and the clock
     * shrank for a row nobody could see (filmed 2026-09-26). settleDy skips them the same way.
     * Nor any child not drawn at all - the media card behind the pill is the other one.
     * With every row hidden, there is no notification constraint on the clock's edited position.
     */
    fun stackContentTop(): Float {
        if (!rowState.config.getBoolean(MiniPlayerConfig.ENABLED)) return Float.NaN
        // Asked on every frame of the clock's own animation: read once a frame at most.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - contentTopAt < 6L) return contentTop
        contentTopAt = now
        val stack = notificationStack()
        contentDrawnTop = Float.NaN
        contentTop = if (stack == null || !stack.isAttachedToWindow) Float.NaN else {
            var top = Float.POSITIVE_INFINITY
            var drawnTop = Float.POSITIVE_INFINITY
            var from: View? = null
            var skipped = 0
            val stackY = IntArray(2).also(stack::getLocationOnScreen)[1]
            // Every child weighed, for `op mini`: name:target-on-screen/translation and why skipped.
            val recordDiagnostic = now - contentDiagnosticAt >= 100L
            val seen = if (recordDiagnostic) StringBuilder() else null
            // A card a switch is taking home to its island is the morph's, drawn on its way to the
            // row, not where the stack still has it. The media card going back to the pill for a
            // focus notification was pushed up the stack by the row let out under it, 1700 -> 1425,
            // and the clock shrank for it until it went (probe, 2026-09-26).
            // The same for the music's own morph into the pill: pulled down out of the cover,
            // the exit aimed at the card and then jumped to full size as it went (2026-09-26).
            val retractingMusic = rowState.morph?.toNative == false &&
                (!motions.noteMorphing || noteMorphMotion.noteMorphKey == MUSIC_ISLAND)
            // The retraction is asked before the header, not after it: this runs on every
            // pre-draw, and with no header to reuse transitionHeader() resolves the card's id and
            // walks the whole window - about a quarter of SystemUI's cycles opening and closing
            // the shade with no media session (simpleperf, #138).
            val leaving = cardExchange.exchange?.movers?.values?.filter { it.headedHome }?.map { it.native }.orEmpty() +
                listOfNotNull(if (retractingMusic) views.transitionHeader() else null)
            // The row spreading out as the list or folding back: where its islands start from.
            val spreadRest = spreadMoving()?.let {
                views.player?.takeIf { v -> v.isAttachedToWindow }
                    ?.let { v -> (IntArray(2).also(v::getLocationOnScreen)[1] - stackY).toFloat() }
                    ?: stack.height.toFloat()
            }
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i)
                if (c.visibility == View.GONE || c.height <= 0) continue
                val name = c.javaClass.name
                if (!name.contains("ExpandableNotificationRow") && !name.contains("MediaHeader")) continue
                val t = stackTargetY(c) + c.top
                // One of the spread's cards, hidden or drawn at nothing while its island's morph
                // has it: weighed where the morph is taking it, from its island's place to its own
                // by the spread's progress. Skipped as not drawn, the clock kept its size till the
                // whole list was out and then shrank all at once (the user, 2026-09-30).
                val share = if (spreadRest != null) spreadShare(c) else null
                if (share != null) {
                    val from0 = maxOf(spreadRest!!, t)
                    val at = from0 + (t - from0) * share
                    seen?.let { it.append(' ').append(shortName(c)).append(':').append((stackY + at).toInt()).append('S') }
                    if (at < top) { top = at; from = c }
                    // Drawn by the morph, not the row: where the morph has it is the share's.
                    val drawnAt = from0 + (c.top + c.translationY - from0) * share
                    if (drawnAt < drawnTop) drawnTop = drawnAt
                    continue
                }
                val home = leaving.any { isInside(it, c) }
                if (seen != null) {
                    seen.append(' ').append(shortName(c)).append(':').append((stackY + t).toInt())
                        .append('/').append((stackY + c.top + c.translationY).toInt())
                    if (c in hiddenRows) seen.append('H')
                    if (c.visibility != View.VISIBLE) seen.append('I')
                    if (c.alpha <= 0.01f) seen.append('A')
                    if (pinned?.get() === c) seen.append('P')
                    if (home) seen.append('L')
                }
                // Hidden only while its morph is being made - a tap has sent it out, the row is
                // there, the morph takes it a frame or two later: it is on its way, and the clock
                // gives way to where it is going. Skipped, the clock grew for the frame or two
                // and shrank back on every tap of a run of them (2026-09-26).
                val coming = c in hiddenRows && rowComingOut(c)
                if (coming) seen?.append('C')
                // The media card the pill stands in for is kept INVISIBLE, still laid out (see
                // the setVisibility hook in install): the clock gave way to it too (2026-09-26).
                // Folded into the stack's "N个通知" state, a row is laid out and drawn at nothing
                // (its numStateAlpha): not there for the clock either. Only while the stack says it
                // is folded - opening, the rows fade in from nothing and count from the first frame.
                val folded = NumState.inNumber == true && foldedAway(c)
                if (folded) seen?.append('F')
                if (home || c in hiddenRows && !coming || c.visibility != View.VISIBLE ||
                    c.alpha <= 0.01f && !coming || folded) { skipped++; continue }
                if (t < top) { top = t; from = c }
                val d = c.top + c.translationY
                if (d < drawnTop) drawnTop = d
            }
            contentDrawnTop = ClockRoomPolicy.contentTop(stackY.toFloat(), drawnTop)
            if (seen != null) {
                contentDiagnosticAt = now
                contentTopFrom = (from?.let(::shortName) ?: "-") + " skip=$skipped" +
                (if (motions.exchanging) " X" else "") + (if (rowState.morph != null) " M" else "") + " [" + seen.toString().trim() + "]"
            }
            ClockRoomPolicy.contentTop(stackY.toFloat(), top)
        }
        return contentTop
    }

    /** The spread on its way out or back in (not waiting, not open); null otherwise. */
    private fun spreadMoving(): SpreadMotion.Spread? =
        spreadMotion.spread?.takeIf { it.phase == SPREAD_OPENING || it.phase == SPREAD_CLOSING }

    /**
     * [c], a stack child, is one of the moving spread's cards: its progress, 0 at the islands'
     * end. Not a card out from before while the list opens - that one is where it is.
     */
    private fun spreadShare(c: View): Float? {
        val s = spreadMoving() ?: return null
        val item = s.items.firstOrNull { item ->
            item.native?.let { isInside(it, c) } == true && !(item.wasOut && s.phase != SPREAD_CLOSING)
        } ?: return null
        return if (item.native == null) null else s.progress
    }

    /** A stack row the stack's fold has faded to nothing (its injector's numStateAlpha). */
    private fun foldedAway(c: View): Boolean = runCatching {
        (Xp.getObjectField(Xp.callMethod(c, "getInjector"), "numStateAlpha") as Float) <= 0.01f
    }.getOrDefault(false)

    /**
     * [c], a stack child this controller keeps out of sight, is on its way out as a card: the
     * row a tap or a pull is waiting to morph into, the card a switch is waiting to send up, or
     * one of the stack island's other rows while the island opens.
     */
    private fun rowComingOut(c: View): Boolean {
        fun top(key: String?): View? = when (key) {
            null -> null
            MUSIC_ISLAND -> views.transitionHeader()
            else -> findRow(key)?.second
        }
        if (noteMorphMotion.rowWaitKey != null && top(noteMorphMotion.rowWaitKey) === c) return true
        val pending = cardExchange.exchange?.pending
        if (pending != null && top(pending) === c) return true
        return c in groupMotion.pileRows && !groupMotion.stackGoingHome()
    }

    /** See stackContentSource. */
    var contentTopFrom = "-"
        private set

    internal fun shortName(v: View): String =
        rowKey(v)?.substringAfter('|')?.substringBefore('|')?.substringAfterLast('.') ?: v.javaClass.simpleName

    private fun stackTargetHeight(v: View): Float = runCatching {
        (Xp.getObjectField(Xp.callMethod(v, "getViewState")!!, "height") as Number).toFloat()
    }.getOrElse {
        runCatching { (Xp.callMethod(v, "getActualHeight") as Number).toFloat() }.getOrDefault(v.height.toFloat())
    }.takeIf { it > 0f } ?: v.height.toFloat()

    /**
     * A card that has landed where the stack is settling it stays there, drawn, while the stack
     * catches up: the rows under it go and it moves down to the same place underneath.
     */
    var pinned: WeakReference<View>? = null
    private var pinnedLeaving: Collection<String> = emptyList()
    private var pinnedSince = 0L

    /**
     * [from]: the stack translation the morph let go of it at, when that was not where the stack
     * is settling it - a card come back down out of a turned-round exchange lands where it left.
     */
    internal fun pinCard(native: View, leaving: Collection<String>, from: Float? = null) {
        unpinCard()
        pinned = WeakReference(native)
        pinnedLeaving = leaving
        pinnedSince = android.os.SystemClock.uptimeMillis()
        // Where the morph let go of it, in the stack's translation.
        val settle = native.translationY + settleDy(native, leaving)
        pinY.value = from ?: settle
        pinY.target = pinY.value
        pinY.velocity = 0f
        pinWant = pinY.value
        pinLast = 0L
        pinPrevTarget = lastStackTarget
        pinSteady = 0
        observePin(native)
        // Placed now, in the frame the morph lets go of it; the callback carries on from there.
        pinFrame.doFrame(0L)
    }

    /**
     * The list scrolled from the landed card: the pin lets go, or the card stays where it landed
     * while the rest of the list moves. Not a card frozen for a morph about to take it.
     */
    fun releaseNativeScrollPin() {
        if (pinned?.get() == null || pinnedAt != null) return
        MiniPlayerRuntime.noteTouch("pin let go for a list scroll")
        unpinCard()
    }

    /**
     * The stack island opened out as the lock screen's list, and the list scrolling: the rows'
     * places are the list's from here. A morph still bringing the rows out ends where it was
     * going, the pile comes apart into the list and the pin lets go - kept, each of them held
     * its rows against the scroll.
     */
    fun onNativeListScroll(stack: View) {
        if (stack !== notificationStack() || !LockIslands.isReleased(STACK_ISLAND)) return
        val opening = rowState.morph?.takeIf { noteMorphMotion.noteMorphKey == STACK_ISLAND && it.toNative }
        val moving = cardExchange.exchange?.movers?.get(STACK_ISLAND)?.morph?.takeIf { it.toNative }
        val held = pinned?.get() != null && pinnedAt == null
        if (opening == null && moving == null && groupMotion.piles.isEmpty() && !held) return
        MiniPlayerRuntime.noteTouch("list scroll takes the rows: open=${opening != null} " +
            "move=${moving != null} pile=${groupMotion.piles.size} pin=$held")
        opening?.cancel()
        moving?.cancel()
        if (groupMotion.piles.isNotEmpty()) groupMotion.finishPile(true)
        // Read again: a flight ended here pins its card where it lands (the flight's onSettled).
        if (pinned?.get() != null && pinnedAt == null) unpinCard()
    }

    /** [keep]: the card stays drawn where the pin had it, for a morph taking it this frame. */
    internal fun unpinCard(keep: Boolean = false) {
        val v = pinned?.get()
        pinned = null
        Gen.unframe(pinFrame)
        pinObserved?.get()?.let { Gen.unpreDraw(it, pinPreDraw) }
        pinObserved = null
        if (!keep) v?.setAnimationMatrix(null)
        pinnedDy = 0f
        pinnedAt = null
    }

    /**
     * Holds [v] drawn at the stack translation [at], whatever the stack does with it meanwhile:
     * a card about to come down, for the frames before its morph takes it.
     */
    internal fun freezeCard(v: View, at: Float) {
        unpinCard(keep = true)
        pinned = WeakReference(v)
        pinnedLeaving = emptyList()
        pinnedAt = at
        pinnedSince = android.os.SystemClock.uptimeMillis()
        observePin(v)
        pinFrame.doFrame(0L)
    }

    /**
     * The pin is written again right before the frame is drawn, against the translation the
     * stack has given the row by then. Written from a frame callback alone, it was against the
     * last frame's: the stack's animator moves the row later in the same frame, and while it
     * slid the row 20px a frame toward the card, the card was drawn off by that much and came
     * back - the shake after every switch (2026-09-25), with the held place itself never moving.
     */
    private val pinPreDraw = android.view.ViewTreeObserver.OnPreDrawListener { android.os.Trace.beginSection("MC pinPreDraw"); try {
        val v = pinned?.get()
        if (v != null) {
            val dy = (pinnedAt ?: pinY.value) - v.translationY
            if (kotlin.math.abs(dy - pinnedDy) > 0.01f) {
                pinnedDy = dy
                pinMatrix.setTranslate(0f, dy)
                v.setAnimationMatrix(pinMatrix)
            }
        }
        true
    } finally { android.os.Trace.endSection() } }

    private var pinObserved: WeakReference<View>? = null

    private fun observePin(v: View) {
        pinObserved?.get()?.let { Gen.unpreDraw(it, pinPreDraw) }
        Gen.preDraw(v, pinPreDraw)
        pinObserved = WeakReference(v)
    }

    /**
     * [card] - the media card or a view in it - is held below where the stack has it now: by the
     * pin, or as the card an exchange is taking the music up into. The cover lays its artwork
     * out above the card's top, read on screen, which is the stack's place for it: with the row
     * coming down still in the stack under it, a row higher. Into the cover out of an exchange,
     * the artwork flew to a size for that higher card and grew as the stack caught up (filmed
     * 2026-09-25). Null when it is neither.
     */
    fun cardHeldDy(card: View): Float? {
        pinned?.get()?.takeIf { viewWithin(it, card) }?.let { v ->
            return if (pinnedAt != null) pinnedAt!! - v.translationY else pinY.value - v.translationY
        }
        // The music going up into the cover lands where the stack is settling its card. Coming down
        // out of it - or, turned round, going back up into it - it leaves from and lands at where
        // it was held; the row going up pushes it up in the stack meanwhile, 180px, and the cover
        // was laid out small for that.
        val m = cardExchange.exchange?.movers?.get(MUSIC_ISLAND)?.takeIf { it.morph != null && viewWithin(it.native, card) }
            ?: return null
        return (if (m.fromCard) m.cardAt else m.settleAt) - m.native.translationY
    }

    private fun viewWithin(ancestor: View, v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }

    /** A frozen card's stack translation (freezeCard); null for one landing (pinCard). */
    var pinnedAt: Float? = null

    /** The pin's offset this frame, for a morph taking the card from where it is drawn. */
    var pinnedDy = 0f

    private val pinMatrix = Matrix()

    /**
     * A landed card, held: it goes on its own spring toward where it is to be - the estimate
     * (rows still leaving) until the stack has taken them away and laid the rest out again, then
     * the stack's own target. The estimate can be a few pixels off the stack's final layout; held
     * at it until the hold ran out, the card then jumped those pixels (2026-09-25).
     */
    val pinY = Jelly(PIN_RESPONSE, HIDDEN_DAMPING)
    private var pinWant = 0f
    private var pinLast = 0L

    /**
     * Where the landed card is to be drawn. It landed where the stack settles it (measured the
     * same, 2024 both, 2026-09-25), so it stays there: the frames while the rows leave are not
     * read for it - the stack's children, their targets and the rows going out are not all
     * updated in one frame, and followed frame by frame the card dipped 12px and came back. Only
     * a target the stack has held for a few frames with nothing leaving is taken, and only if it
     * is somewhere else (the stack itself changed meanwhile).
     */
    private fun landingDy(v: View, now: Long): Float {
        settleDy(v, pinnedLeaving)
        val st = lastStackTarget
        if (lastGone == 0 && kotlin.math.abs(st - pinPrevTarget) < 0.5f) pinSteady++ else pinSteady = 0
        pinPrevTarget = st
        if (pinSteady >= PIN_STEADY_FRAMES) pinWant = st
        val dt = if (pinLast == 0L) 1f / 120f else ((now - pinLast) / 1000f).coerceIn(0f, 0.05f)
        pinLast = now
        pinY.target = pinWant
        pinY.step(dt)
        return pinY.value - v.translationY
    }

    private var pinPrevTarget = 0f
    private var pinSteady = 0

    private val pinFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC pinFrame"); try {
            val v = pinned?.get() ?: return
            val at = pinnedAt
            val now = android.os.SystemClock.uptimeMillis()
            val dy = if (at != null) at - v.translationY else landingDy(v, now)
            val waited = now - pinnedSince
            val settled = at == null && kotlin.math.abs(dy) < 0.5f && pinY.atRest() &&
                kotlin.math.abs(pinWant - v.translationY) < 0.5f && pinSteady >= PIN_STEADY_FRAMES
            if (!v.isAttachedToWindow || waited > PIN_MS || settled && waited > PIN_SETTLE_MS) {
                rowHost.trace("pin off ${if (settled) "settled" else if (waited > PIN_MS) "timeout" else "detached"} " +
                    "dy=${"%.1f".format(dy)} $lastSettle")
                unpinCard()
                return
            }
            pinnedDy = dy
            pinMatrix.setTranslate(0f, dy)
            v.setAnimationMatrix(pinMatrix)
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * [key] goes out as its card: the music is chosen as the media card, a notification is
     * given back to the stack. The card is kept out of sight until a morph takes it.
     */
    fun letOut(key: String) { android.os.Trace.beginSection("MC letOut"); try {
        if (key != MUSIC_ISLAND) {
            LockIslands.release(key)
            return
        }
        val token = rowState.controller?.sessionToken ?: return
        MiniPlayerRuntime.chooseNative(token)
        rowHost.updateVisibility()
        views.transitionHeader()?.let(::hideRow)
    } finally { android.os.Trace.endSection() } }

    internal val kept = HashMap<String, Kept>()

    /** [key] - a notification, or the stack island with all of its rows - back into the row now. */
    internal fun takeInEarly(key: String) {
        if (key == MUSIC_ISLAND || !LockIslands.isReleased(key)) return
        val now = android.os.SystemClock.uptimeMillis()
        val rows = StringBuilder()
        for (member in LockIslands.membersOf(key)) {
            val row = findRow(member)?.second ?: continue
            val held = pinned?.get() === row
            kept[member] = kept.values.firstOrNull { it.row === row }
                ?: Kept(row, row.translationY + if (held) pinnedDy else 0f, row.alpha, now)
            MiniPlayerRuntime.holdTransient(row)
            rows.append(' ').append(shortName(row)).append('@').append(row.translationY.toInt())
        }
        rowHost.trace("early in ${groupMotion.shortKey(key)}$rows")
        takeIn(key)
    }

    /** [key]'s kept rows are the stack's again: landed home, or let out once more. */
    internal fun letGo(key: String) {
        val keys = (LockIslands.membersOf(key) + key).filter { it in kept }
        if (keys.isEmpty()) return
        val rows = keys.mapNotNull { kept.remove(it)?.row }.distinct()
        for (row in rows) if (kept.values.none { it.row === row }) MiniPlayerRuntime.releaseTransient(row)
        rowHost.trace("let go ${groupMotion.shortKey(key)} rows=${rows.size}")
    }

    /**
     * [key] was given back early and is turning round: out again. Its rows stay held until the
     * stack has them back as rows - it moves a transient view back itself when it re-adds it. Let
     * go at once, a removal the stack had asked for meanwhile ran then and there: the row left the
     * window under the morph turning it round, and the stack island's other rows jumped out
     * whole where they were (2026-09-26).
     */
    internal fun letOutAgain(key: String) {
        val keys = (LockIslands.membersOf(key) + key).filter { it in kept }
        if (keys.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        for (row in keys.mapNotNull { kept.remove(it)?.row }.distinct()) {
            if (kept.values.none { it.row === row }) reAdding[row] = now
        }
        letOut(key)
        rowHost.trace("out again ${groupMotion.shortKey(key)} rows=${reAdding.size}")
    }

    /** Rows let out again, held until the stack has them back (holdKept). */
    private val reAdding = HashMap<View, Long>()

    /**
     * An island turned round on its way home again is told to the stack only once it has kept
     * to that way for TURN_SETTLE_MS; turned back out meanwhile, nothing is told at all. Every release and recapture rebuilds the stack's whole list (ShadeListBuilder, 5-10ms,
     * and two or three smaller passes after it), and taken at once on every turn, a run of taps
     * mid-switch rebuilt it every 150-300ms: the frames dropped with each (traced 2026-09-26).
     * The first move - a tap, a card let go - still goes at once; landing, the island's last
     * way is told at once too (flushTurn).
     */
    private val turnWanted = HashMap<String, Boolean>()

    private val turnApply = Runnable {
        val due = HashMap(turnWanted)
        turnWanted.clear()
        for ((key, out) in due) tellTurn(key, out)
    }

    fun turnTo(key: String, out: Boolean) {
        // Out again at once: the stack puts its row back with an animation of its own, and told
        // 200ms late, that played mid-way through the card's way up - the stopwatch flashed on
        // a second tap (2026-09-26). At once, the card is still the pill then. Home again is
        // what waits: the row is held where it is drawn meanwhile (holdKept), and a turn out
        // before the wait is up finds it still out, with nothing to tell.
        if (out) {
            turnWanted.remove(key)
            tellTurn(key, true)
            return
        }
        turnWanted[key] = out
        views.handler.removeCallbacks(turnApply)
        views.handler.postDelayed(turnApply, TURN_SETTLE_MS)
        rowHost.trace("turn ${groupMotion.shortKey(key)} ${if (out) "out" else "home"} held")
    }

    /** [key]'s turn not told yet, told now: it has landed, or is being taken over. */
    internal fun flushTurn(key: String) {
        val out = turnWanted.remove(key) ?: return
        tellTurn(key, out)
    }

    private fun tellTurn(key: String, out: Boolean) {
        // Back the way it was before the turns: nothing to tell the stack. But a page behind it
        // may have been taken over meanwhile - the map's island going home, the countdown's out,
        // the map's turned out again before its home was told: its row never went back, and the
        // countdown's home, told later, closed the only page open, down to the plain wallpaper
        // (2026-09-30). Released again, it takes its page back; nothing else happens to a key
        // that is released already.
        if (key != MUSIC_ISLAND && out == LockIslands.isReleased(key)) {
            if (out) LockIslands.release(key)
            return
        }
        if (out) {
            letOutAgain(key)
            if (key != MUSIC_ISLAND && !LockIslands.isReleased(key)) letOut(key)
        } else takeInEarly(key)
    }

    /**
     * The kept rows, each frame before it is drawn: where they were when given back, as they
     * were. The stack animates a removed row away on its own; the morph has it now.
     */
    internal fun holdKept() {
        val now = android.os.SystemClock.uptimeMillis()
        if (reAdding.isNotEmpty()) {
            val it = reAdding.entries.iterator()
            while (it.hasNext()) {
                val (row, since) = it.next()
                val back = (row.parent as? ViewGroup)?.indexOfChild(row)?.let { i -> i >= 0 } == true
                if (back) MiniPlayerRuntime.forgetTransient(row)
                else if (now - since > REEADD_MAX_MS) MiniPlayerRuntime.releaseTransient(row)
                else continue
                rowHost.trace("re-added ${shortName(row)} back=$back")
                it.remove()
            }
        }
        if (kept.isEmpty()) return
        val stale = kept.filter { (_, k) -> !k.row.isAttachedToWindow || now - k.since > KEPT_MAX_MS || !Main.keyguardLocked() }
        for (key in stale.keys) {
            rowHost.trace("kept ${groupMotion.shortKey(key)} dropped: attached=${stale[key]?.row?.isAttachedToWindow}")
            letGo(key)
        }
        for (k in kept.values.distinctBy { System.identityHashCode(it.row) }) {
            if (k.row.translationY != k.ty) k.row.translationY = k.ty
            if (k.row.alpha != k.alpha) k.row.alpha = k.alpha
        }
    }

    /** ...and comes back into the row. */
    internal fun takeIn(key: String) { android.os.Trace.beginSection("MC takeIn"); try {
        if (key == MUSIC_ISLAND) rowState.controller?.sessionToken?.let(MiniPlayerRuntime::chooseMini)
        else LockIslands.recapture(key)
    } finally { android.os.Trace.endSection() } }

    /** A notification taken back in keeps its place until the stack has given it back. */
    fun markReturning(key: String) {
        edits.returning[key] = android.os.SystemClock.uptimeMillis()
        // Asked again once the wait is up, in case the stack never gives it back.
        views.handler.postDelayed({ if (key in edits.returning) rowHost.refresh() }, RETURN_WAIT_MS + 50L)
    }

    /**
     * The super island's expanded state switching, as its plugin runs it (read 2026-09-25): a tap
     * on an island in the row sends it out as its card and brings the one out now back in, and
     * the row reseats around the two (reseat). Every island on its way between its place in the
     * row and its card is a [Mover]: a stand-in view on a MiniCardMorph of its own, on its own
     * spring.
     *
     * The super island takes a tap at any moment - DynamicIslandContentView.canClick does not wait
     * for its animations - gives it to whoever holds the place tapped (performClick, by the
     * place's rest frame), reseats the row at once, and every island turns toward its new end from
     * where it is and as fast as it is going (IslandTransitionExecutor: folme.to from the current
     * values, no start state). So does this: a mover turns round (MiniCardMorph.aim keeps the
     * spring's speed) and its row end springs to its new place; an island at rest in the row moves
     * with a switch of the pill and the small island (startSwap). The pill and the small island
     * are always the seats' islands, out of sight while the island they hold is still on its way
     * to them, and take over from it on its last stretch, as from a flight.
     *
     * The first version was one pair, the one going up leading and the one coming down following
     * it the other way; a tap on the place turned the two round, a tap anywhere else finished the
     * pair where it was headed at once and started the next - a jump on every such tap.
     */
    private var stackRef: WeakReference<View>? = null
    fun releasedRowAt(x: Float, y: Float): String? {
        val xy = IntArray(2)
        for (key in LockIslands.releasedKeys()) {
            // The stack island's rows are all its: the one under the finger leads it home.
            for (member in LockIslands.membersOf(key)) {
                val row = findRow(member)?.first ?: continue
                if (!row.isShown || row.width <= 0) continue
                row.getLocationOnScreen(xy)
                if (x >= xy[0] && x < xy[0] + row.width && y >= xy[1] && y < xy[1] + row.height) {
                    if (key == STACK_ISLAND && noteMorphMotion.noteMorphKey != STACK_ISLAND) edits.stackLeadKey = member
                    else if (LockIslands.isAppGroup(key) && noteMorphMotion.noteMorphKey != key)
                        edits.appLeadKeys[key] = member
                    return key
                }
            }
        }
        return null
    }

    /** The list's own scroll: NSSL keeps it in mOwnScrollY, not in View.scrollY. Null unread. */
    private fun nativeScrollY(): Int? {
        val stack = notificationStack() ?: return null
        return (runCatching { Xp.callMethod(stack, "getOwnScrollY") }.getOrNull() as? Number)?.toInt()
            ?: (runCatching { Xp.getObjectField(stack, "mOwnScrollY") }.getOrNull() as? Number)?.toInt()
    }

    /** Where the lock screen rests the list, as the gesture's DOWN read it. */
    private var restScrollY: Float? = 0f
    private var listTopStrategy: Any? = null
    /** For the touch log: the scroll and rest last compared. */
    var lastScrollRead = "unread"
        private set

    /**
     * The list at its top ([MiniPlayerCollapseGesture.atListTop]). Its rest is 0 except with the
     * stack island opened out as the list, where the lock screen scrolls it down under the clock.
     *
     * Folded natively, the stack island is never "released" (isReleased), so the open list was
     * read against a rest of 0: resting at 220 it counted as scrolled away, the pull down was not
     * the islands', and the stack's own fold ran home in four frames - the card gone in one and
     * the island popping up past its place (#122). Spread out, the rest is where the spread
     * opened it to: the same scroll its progress is measured on.
     */
    fun nativeStackAtTop(readRest: Boolean): Boolean {
        val spreadRest = spreadMotion.spread?.takeIf { it.phase == SPREAD_OPEN || it.phase == SPREAD_CLOSING }
            ?.cardsY?.toFloat()
        if (readRest) restScrollY = spreadRest
            ?: if (LockIslands.isReleased(STACK_ISLAND)) listRestScroll() else 0f
        val scrollY = nativeScrollY()
        lastScrollRead = "own=$scrollY/rest=$restScrollY"
        return MiniPlayerCollapseGesture.atListTop(scrollY, restScrollY)
    }

    /**
     * The LIST's resting scroll, as the lock screen works it out for a tap on its pile
     * (KeyguardNotificationClickToListStateStrategy): the list's origin less the target it
     * places the list's top at. Null when any of it cannot be read.
     */
    private fun listRestScroll(): Float? = runCatching {
        val model = containerModel() ?: return@runCatching null
        val sample = Xp.callMethod(Xp.callMethod(model, "getOnUpdateChildSampleStackInfo"), "getValue")
        val event = Xp.findClass("com.miui.systemui.notification.view.strategy.KeyguardStackStateChangeEvent", views.loader)
            .getField("CLICK_SHOW_LIST").get(null)
        val info = Xp.callMethod(model, "getKeyguardStackStatusInfo", sample, event)
        val focusHeight = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val origin = (Xp.callMethod(model, "positionWithoutMinScrollRange", focusHeight) as Number).toFloat()
        val strategy = listTopStrategy ?: Xp.findClass(
            "com.miui.systemui.notification.view.strategy.KeyguardNotificationClickToListStateStrategy", views.loader)
            .getDeclaredConstructor().newInstance().also { listTopStrategy = it }
        val top = (Xp.callMethod(strategy, "calculateTargetYPosition", info) as Number).toFloat()
        (origin - top).takeIf { it.isFinite() }?.coerceAtLeast(0f)
    }.onFailure { MiniPlayerRuntime.noteTouch("list rest unread: $it") }.getOrNull()

    /** The lock screen's notification stack, found once in the window. */
    fun notificationStack(): ViewGroup? {
        (stackRef?.get() as? ViewGroup)?.takeIf { it.isAttachedToWindow }?.let { return it }
        val queue = ArrayDeque<View>()
        queue.add(views.host)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.endsWith("NotificationStackScrollLayout") && v is ViewGroup) {
                stackRef = WeakReference(v)
                return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** A notification's row in the stack, by its key. */
    fun rowFor(key: String): View? = findRow(key)?.first

    /** [rowFor], for MiniPlayerRuntime.rowOf. */
    fun rowOf(key: String): View? = rowFor(key)

    /** [notificationStack], for MiniPlayerRuntime.stackForProbe. */
    fun stackForProbe(): ViewGroup? = notificationStack()


    fun dumpTree(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 22) return
        sb.append(" ".repeat(depth)).append(v.javaClass.simpleName)
        val id = runCatching { if (v.id > 0) v.resources.getResourceEntryName(v.id) else null }.getOrNull()
        if (id != null) sb.append(" #").append(id)
        sb.append(when (v.visibility) { View.VISIBLE -> ""; View.INVISIBLE -> " INV"; else -> " GONE" })
        if (v.alpha < 1f) sb.append(" a=").append("%.2f".format(v.alpha))
        if (v.transitionAlpha < 1f) sb.append(" ta=").append("%.2f".format(v.transitionAlpha))
        sb.append(' ').append(v.width).append('x').append(v.height)
            .append('@').append(v.left).append(',').append(v.top)
        when (v) {
            is android.widget.Chronometer -> sb.append(" CHRONO base=").append(v.base)
                .append(" down=").append(v.isCountDown).append(" text='").append(v.text).append("'")
            is android.widget.TextView -> sb.append(" '").append(v.text?.toString()?.take(60))
                .append("' sz=").append("%.0f".format(v.textSize)).append(" col=#")
                .append(Integer.toHexString(v.currentTextColor))
            is android.widget.ImageView -> v.drawable.let { d ->
                sb.append(" img=").append(d?.javaClass?.simpleName)
                    .append(' ').append(d?.intrinsicWidth).append('x').append(d?.intrinsicHeight)
            }
            is android.widget.ProgressBar -> sb.append(" progress=").append(v.progress).append('/').append(v.max)
            else -> {}
        }
        if (v.contentDescription != null) sb.append(" cd='").append(v.contentDescription.toString().take(40)).append("'")
        sb.appendLine()
        if (v is ViewGroup) for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, sb)
    }

    /**
     * [key]'s row, and the stack's own row it is in: itself, or the group it is a child of. The
     * system groups an app's notifications by itself (高德's navigation came as two children of
     * an Aggregate_SilentSection summary), and looked for among the stack's rows alone a child
     * was never found: the media card's morph left its row standing, unfolded (2026-09-25).
     */
    internal fun findRow(key: String): Pair<View, View>? {
        if (key == STACK_ISLAND || LockIslands.isAppGroup(key))
            return findRow(rowState.groupLead(key) ?: return null)
        // Given back early and drawn on as a transient view: no longer among the stack's rows.
        kept[key]?.row?.takeIf { it.isAttachedToWindow }?.let { return it to it }
        val stack = notificationStack() ?: return null
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            if (rowKey(child) == key) return child to child
            // One app, one row, as the stack groups them: a notification in a group is found as
            // the group's row, never its own child row. A child is not even drawn with the group
            // folded (the children's container INVISIBLE): three QQ messages grouped, the stack
            // island waited for a child that never came on screen and the tap did nothing, and
            // opened, the rows of one app were piled one by one (2026-09-26, the user's rule).
            for (sub in childRows(child)) if (rowKey(sub) == key) return child to child
        }
        return null
    }

    fun rowKey(row: View): String? = runCatching {
        (Xp.getObjectField(Xp.callMethod(row, "getEntry"), "mSbn") as? android.service.notification.StatusBarNotification)?.key
    }.getOrNull()

    /** A group's rows for its children; none for a row that is not a group. */
    fun childRows(row: View): List<View> = runCatching {
        (Xp.callMethod(row, "getAttachedChildren") as? List<*>)?.filterIsInstance<View>()
    }.getOrNull().orEmpty()

    /**
     * What the stack shows of [key] as a whole: its group's row - the group goes with its last
     * child - unless that group also holds [keep], the row an island is morphing with, which
     * would go with it: then the child's row alone.
     */
    internal fun wholeRowFor(key: String, keep: View?): View? {
        val (row, top) = findRow(key) ?: return null
        if (row === top) return row
        return if (keep != null && isInside(keep, top)) row else top
    }

    internal fun isInside(v: View, ancestor: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }

    /** The first shown view under [root] with one of [names] as its id, breadth first. */
    internal fun findNamed(root: View, names: Set<String>, kind: (View) -> Boolean): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.id != View.NO_ID && v.isShown && kind(v)) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in names) return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** Rows this controller has made invisible (transitionAlpha 0), and what they had. */
    internal val hiddenRows = java.util.WeakHashMap<View, Float>()

    fun hideRow(row: View) {
        if (row in hiddenRows) return
        rowHost.trace("hide ${shortName(row)}")
        hiddenRows[row] = row.transitionAlpha
        row.transitionAlpha = 0f
    }

    fun showRow(row: View) {
        val was = hiddenRows.remove(row) ?: return
        rowHost.trace("show ${shortName(row)} ta=$was")
        row.transitionAlpha = was
    }

    /**
     * A row going back into the row of islands: invisible for as long as the stack takes to
     * animate it away, its alpha given back after that. Not on detach: the stack re-adds a
     * removed row as a transient view for exactly that animation, so the detach comes first
     * and would show it again for it. Given back whatever happens, a row can never be left
     * invisible in a stack that shows it later - the cover's, the unlocked shade's.
     */
    fun hideRowUntilGone(row: View, key: String) {
        hideRow(row)
        views.handler.postDelayed({
            // Released again meanwhile and waiting to be morphed: that morph gives it back.
            // Held for the media card's morph meanwhile: that morph's end gives it back.
            if (noteMorphMotion.rowWaitKey != key && !(noteMorphMotion.noteMorphKey == key && rowState.morph != null) && !groupMotion.rowsHeld()) showRow(row)
        }, ROW_GONE_MS)
    }

    /** A row's alpha as drawn: its own and every ancestor's up to the window. */
    private fun drawnAlpha(row: View): Float {
        var a = 1f
        var v: View? = row
        while (v != null) {
            a *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        return a
    }

    /**
     * Whether a row is there to be landed on: shown, drawn at least half there, and inside the
     * screen above the row of islands. Not the stack's folded state: it read true with the row
     * plainly drawn (alpha 1, 2026-09-25), and the tap went back into its island - it is only
     * logged now.
     */
    /** Every ancestor of [v] not VISIBLE, with its id, up to the window: what isShown tripped on. */
    private fun hiddenAncestors(v: View): String {
        val out = ArrayList<String>()
        var p: View? = v.parent as? View
        while (p != null) {
            if (p.visibility != View.VISIBLE) {
                val id = runCatching { if (p.id > 0) p.resources.getResourceEntryName(p.id) else null }.getOrNull()
                out += p.javaClass.simpleName + (id?.let { "#$it" } ?: "") + "=" + p.visibility
            }
            p = p.parent as? View
        }
        return if (out.isEmpty()) "none(attached=${v.isAttachedToWindow} win=${v.windowVisibility})" else out.joinToString(">")
    }

    /** Why rowOnScreen said no, for the touch log. */
    internal fun offScreenWhy(row: View): String {
        val xy = IntArray(2).also(row::getLocationOnScreen)
        val parent = row.parent as? View
        return "${shortName(row)} v=${row.visibility} shown=${row.isShown} a=${"%.2f".format(row.alpha)} " +
            "ta=${"%.2f".format(row.transitionAlpha)} parentA=${parent?.let { "%.2f".format(drawnAlpha(it)) }} " +
            "y=${xy[1]} h=${row.height} laid=${row.isLaidOut} bottom=${views.player?.restBoxOnScreen()?.y?.toInt()} " +
            "folded=${stackFolded()} lead=${rowState.groupLead(STACK_ISLAND)?.let(groupMotion::shortKey)} hiddenBy=" + hiddenAncestors(row) +
            (findRow(rowState.groupLead(STACK_ISLAND) ?: "")?.second?.takeIf { it !== row }?.let { top ->
                " group: v=${top.visibility} a=${"%.2f".format(top.alpha)} ta=${"%.2f".format(top.transitionAlpha)} " +
                    "h=${top.height} y=${IntArray(2).also(top::getLocationOnScreen)[1]} " +
                    "expanded=${runCatching { Xp.callMethod(top, "isGroupExpanded") }.getOrNull()}"
            } ?: "")
    }

    internal fun rowOnScreen(row: View): Boolean {
        // The row's own alpha is the stack's fade-in (and our hiding): the morph owns it from
        // here. Its ancestors' are whether the stack shows it at all.
        val parent = row.parent as? View ?: return false
        if (!row.isShown || drawnAlpha(parent) < 0.5f) return false
        val xy = IntArray(2).also(row::getLocationOnScreen)
        val bottom = views.player?.restBoxOnScreen()?.y ?: views.host.height.toFloat()
        return xy[1] + row.height > 0 && xy[1] < bottom
    }

    /**
     * The stack folded into its "N notifications" count (KeyguardNotificationState.NUMBER),
     * read as CardTouch.readFoldState reads it but from the stack itself: the media card it starts
     * from is not there with a row of islands showing. Null when it cannot be read.
     */
    private fun stackFolded(): Boolean? = runCatching {
        val stack = notificationStack() ?: return null
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        val model = Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
        Xp.callMethod(Xp.callMethod(model, "isInNumState"), "getValue") == true
    }.getOrNull()

    /** The stack's container model (NotificationContainerViewModel), as stackFolded reads it. */
    private fun containerModel(): Any? = runCatching {
        val stack = notificationStack() ?: return null
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
    }.getOrNull()

    /** The keyguard stack's state - NUMBER, STACK or LIST (KeyguardNotificationState); null unread. */
    private fun stackState(model: Any? = containerModel()): String? = runCatching {
        (Xp.callMethod(Xp.callMethod(model ?: return null, "getCurrentsStackState"), "getValue") as Enum<*>).name
    }.getOrNull()

    /**
     * The stack island's rows laid out as the lock screen's own list, one row an app: the stack
     * asked to do what a tap on its pile does (NotificationRowViewBinder's click on a stacked row:
     * NotificationContainerViewModel._onClicked, read 2026-09-26). The keyguard then moves its
     * notifications' top up to the list's, the clock pressed down to its smallest for them
     * (KeyguardNotificationClickToListStateStrategy), and every row is laid out whole instead of
     * piled at the bottom. The rows' ends are read from where the stack draws them (pileStack,
     * settleDy), so they go to the list as it opens. Asked again for a while if the stack is not
     * a pile yet to be tapped - the rows only just given back - and never once it is a list.
     */
    fun showStackAsList(why: String) {
        listAskedAt = android.os.SystemClock.uptimeMillis()
        listWhy = why
        listClicked = false
        views.handler.removeCallbacks(listAsk)
        listAsk.run()
    }

    /** A card on its way home from the stack that the stack still lays out; null when none. */
    private fun leavingCards(): String? {
        val x = cardExchange.exchange ?: return null
        for (m in x.movers.values) {
            if (!m.headedHome || m.key == STACK_ISLAND || m.key == MUSIC_ISLAND) continue
            // Told to the stack now, if its turn home was waiting: the list waits for it.
            flushTurn(m.key)
            val parent = m.native.parent as? ViewGroup ?: continue
            if (parent.indexOfChild(m.native) >= 0) return shortName(m.native)
        }
        return null
    }

    private var listAskedAt = 0L
    private var listWhy = ""

    /**
     * The stack has been sent its tap for this ask: once, never again. Sent every step while the
     * stack stayed a pile - one notification, which fits whole and has no list to open into - the
     * lock screen took each for a tap of the user's, and buzzed for every one (2026-09-26).
     */
    private var listClicked = false

    /** The stack's own answer to whether a tap on its pile opens the list; null unread. */
    private fun stackWantsList(model: Any): Boolean? = runCatching {
        Xp.callMethod(Xp.callMethod(model, "getShouldNotifClickToList"), "getValue") as? Boolean
    }.getOrNull()

    private val listAsk = object : Runnable {
        override fun run() {
            // Only while the stack island is out, or on its way.
            val out = noteMorphMotion.noteMorphKey == STACK_ISLAND && rowState.morph?.toNative != false ||
                cardExchange.exchange?.let { x -> x.expanded == STACK_ISLAND || x.pending == STACK_ISLAND } == true ||
                !motions.noteMorphing && !motions.exchanging && rowState.expandedKey() == STACK_ISLAND
            if (!out) return
            val model = containerModel() ?: return
            val state = stackState(model)
            val waited = android.os.SystemClock.uptimeMillis() - listAskedAt
            // A card on its way home still laid out in the stack: the list is worked out once,
            // from the rows there as it is asked for (ClickToListStateStrategy), and a stopwatch
            // still among them had the list stop half way up - its card gone, the notifications'
            // top never worked out again (2026-09-26). Asked once that card is out of the layout.
            val leaving = leavingCards()
            when {
                state == "LIST" -> {
                    rowHost.trace("list is up ($listWhy) after ${waited}ms")
                    return
                }
                leaving != null -> if (waited % 100L < LIST_ASK_STEP_MS) rowHost.trace("list waits for $leaving")
                listClicked -> {}
                state == "STACK" && stackWantsList(model) == false -> {
                    // One notification, whole where it is: the stack keeps it so.
                    rowHost.trace("list not wanted ($listWhy)")
                    return
                }
                state == "STACK" -> runCatching {
                    listClicked = true
                    // SystemUI's own Unit, not ours: the module's kotlin is another class loader's,
                    // and whatever collects the click may cast what it is sent. The name put
                    // together here: as a constant in Class.forName, R8 rewrote it to the module's
                    // own obfuscated Unit ("hc2", ClassNotFoundException - 2026-09-26).
                    val unit = Class.forName(listOf("kotlin", "Unit").joinToString("."), false,
                        model.javaClass.classLoader).getField("INSTANCE").get(null)
                    Xp.callMethod(Xp.getObjectField(model, "_onClicked"), "tryEmit", unit)
                    rowHost.trace("list asked ($listWhy) at ${waited}ms")
                }.onFailure { rowHost.trace("list ask failed: $it") }
            }
            if (waited < LIST_ASK_MS) views.handler.postDelayed(this, LIST_ASK_STEP_MS)
            else rowHost.trace("list not up ($listWhy): state=$state")
        }
    }

    /** keyguard_bottom_area above the torch, and keyguard_root_view above that. */
    internal fun findRow(): View? {
        var row: View? = null
        var v: View? = views.left
        while (v != null && v !== views.host) {
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (name == "keyguard_bottom_area" && row == null) row = v
            if (name == "keyguard_root_view") edits.followRoot = WeakReference(v)
            v = v.parent as? View
        }
        return row ?: views.left.parent as? View
    }
}
