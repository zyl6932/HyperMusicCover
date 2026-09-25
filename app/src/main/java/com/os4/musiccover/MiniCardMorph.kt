package com.os4.musiccover

import android.graphics.Matrix
import android.graphics.Outline
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The mini player and the OEM media card as one container changing shape, both ends drawn live.
 *
 * The version this replaces (custom.8) moved a stand-in: the mini player's material stretched
 * about 1.3x by 3x - filmed rendering solid black - its text as software bitmaps, and the real
 * card only faded in once the spring had fully settled, so the black card sat still for about
 * 0.6s and then turned into glass. Nothing here is captured. The OEM card is scaled uniformly to
 * the container's width and clipped to it, so its material, progress bar and buttons are its own
 * on every frame; the mini player's frame is resized rather than scaled, so its material is never
 * stretched; and the mini player's artwork, title and artist travel onto the card's own and
 * cross over in place. At either end the frame is exactly the live view at rest, which is why
 * finishing hands nothing over and turning round mid-flight needs nothing rebuilt.
 */
internal class MiniCardMorph(
    private val mini: MiniPlayerView,
    private val header: View,
    toNative: Boolean,
    private val listener: Listener,
    /**
     * Where the pill's artwork, title and artist land, and the corners they land in: the media
     * card's by default. A notification island lands on its own row's icon, title and text.
     */
    landing: Landing = Landing.mediaCard(header),
    /**
     * The mini player's end on screen, when it is not the pill's own rest place: a small
     * island's circle, for a notification opening straight out of it; the row's own spring,
     * for a pill still widening or narrowing as the small island goes or comes.
     */
    private val restBox: (() -> CoverMorphMotion.Box?)? = null,
    /** That end is a small island's circle, holding only its picture. */
    private val circle: Boolean = restBox != null,
    /**
     * Down the screen from where the stack has the card now to where it is settling it, in
     * pixels: a row that has just come into the stack lands where it will be once the rows
     * leaving it are gone, not above them for the stack to slide down after.
     */
    private val nativeDy: (() -> Float)? = null,
    /**
     * How much of a circle the mini end is, 0 (a pill) to 1 (a small island's circle), when it
     * changes on the way: a card headed for the small place that the row gives the big one
     * instead. Read every frame; [circle] when there is none.
     */
    private val roundness: (() -> Float)? = null,
) : Choreographer.FrameCallback {
    class Landing(val art: View?, val title: View?, val text: View?, val radius: Float, val artRadius: Float) {
        companion object {
            fun mediaCard(header: View) = Landing(Main.miniPairArt(), Main.miniPairTitle(),
                Main.miniPairArtist(), Main.miniPlayerCardRadius(header), Main.coverMorphThumbnailRadius())
        }
    }

    interface Listener {
        /** Whether the destination can be handed back yet; a scene exit waits for the clock. */
        fun canSettle(morph: MiniCardMorph, toNative: Boolean): Boolean
        fun artBridged(): Boolean
        fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean)

        /** Every frame drawn, with the progress it was drawn at (0 the mini end, 1 the far one). */
        fun onFrame(morph: MiniCardMorph, progress: Float) {}
    }

    /**
     * The OEM card's own values, put back when the morph ends. Its translation and scale are not
     * among them because they are never written: the notification stack rewrites the card's
     * translationY on its own frames (all through a clock collapse), and the first version, which
     * moved the card by that same property, read each of those rewrites as the stack moving the
     * card and chased it - a feedback loop that sent the card off the top of the screen. The
     * morph goes in the animation matrix instead, which only transitions write and which sits
     * on top of whatever the stack has put there.
     */
    private class Saved(v: View) {
        val visibility = v.visibility
        val transitionAlpha = v.transitionAlpha
        val outline: ViewOutlineProvider? = v.outlineProvider
        val clip = v.clipToOutline
    }

    /** One mini player element and, when it has one, the card element it lands on. */
    private class Piece(val view: View, val native: View?, val text: Boolean, val art: Boolean) {
        val baseTx = view.translationX
        val baseTy = view.translationY
        val nativeTransitionAlpha = native?.transitionAlpha ?: 1f
        var paired = false
        /** The blur last put on it and on its card element, in their own pixels across and up. */
        val blur = FloatArray(2)
        val nativeBlur = FloatArray(2)
    }

    /**
     * The super island's content blur as its layers cross (IslandPropertyUpdater: 40px at full,
     * the leaving layer at once, the arriving one 50ms late): here each piece is out of focus by
     * as much as it has faded, and the card's own line comes into focus a little after it
     * comes in. In dp, 40px at this phone's 3x.
     */
    private val blurPx = mini.resources.displayMetrics.density * BLUR_DP

    /** [r] across and up in [v]'s own pixels, [last] what it has now; unchanged within half a pixel. */
    private fun blurTo(v: View, rx: Float, ry: Float, last: FloatArray) {
        val x = if (rx < 0.5f) 0f else min(rx, BLUR_MAX_PX)
        val y = if (ry < 0.5f) 0f else min(ry, BLUR_MAX_PX)
        if ((x == 0f && y == 0f) == (last[0] == 0f && last[1] == 0f) &&
            kotlin.math.abs(x - last[0]) < 0.5f && kotlin.math.abs(y - last[1]) < 0.5f) return
        last[0] = x
        last[1] = y
        v.setRenderEffect(if (x == 0f && y == 0f) null
            else android.graphics.RenderEffect.createBlurEffect(max(x, 0.01f), max(y, 0.01f),
                android.graphics.Shader.TileMode.DECAL))
    }

    private val motion = CoverMorphMotion()
    private val saved = Saved(header)
    private val nativeRadius = landing.radius
    private val nativeArtRadius = landing.artRadius
    private val pieces = listOf(
        Piece(mini.artworkView, landing.art, text = false, art = true),
        Piece(mini.titleView, landing.title, text = true, art = false),
        Piece(mini.artistView, landing.text, text = true, art = false),
        Piece(mini.toggleView, null, text = false, art = false),
        Piece(mini.secondToggleView, null, text = false, art = false),
    )
    private val xy = IntArray(2)
    private val corner = FloatArray(2)
    private val oemMatrix = Matrix()
    private val morphMatrix = Matrix()
    private var clipW = 0f
    private var clipH = 0f
    private var clipR = 0f
    private val clipOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, clipW.roundToInt(), clipH.roundToInt(), clipR)
        }
    }
    private var running = false

    /**
     * The card is put at its box again right before the frame is drawn, against the transform
     * the stack has given it by then: the stack's animator writes translationY later in the same
     * frame than this morph's callback, so a card the stack was still sliding - the one the last
     * switch had just landed, taken by the next one straight away - was drawn off by that
     * frame's step of the slide, and shook for as long as the slide ran.
     */
    private var placedBox: CoverMorphMotion.Box? = null
    private var placedScale = 0f
    private val placedOem = Matrix()
    private var observed: View? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { android.os.Trace.beginSection("MC cardMorphPreDraw"); try {
        val box = placedBox
        if (running && box != null && header.matrix != placedOem) placeHeader(box, placedScale)
        true
    } finally { android.os.Trace.endSection() } }

    private fun observe(on: Boolean) {
        observed?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observed = null
        if (!on) return
        header.viewTreeObserver.addOnPreDrawListener(preDraw)
        observed = header
    }

    /** The pill's artwork as this frame drew it, on screen. */
    private var artDrawn: CoverMorphMotion.Box? = null
    /** The container as this frame drew it, on screen: where a finger can take hold of it. */
    private var boxDrawn: CoverMorphMotion.Box? = null
    private var lastFrame = 0L
    private var startedAt = 0L

    /** While a finger holds it: progress is set, not sprung, and nothing lands. */
    private var dragging = false

    /**
     * The whole container moved toward the other end, in pixels, on top of the progress: the
     * nudge of a small drag that has not opened anything yet. Springs back to 0 on release.
     */
    private val nudge = CoverMorphMotion()
    private val nudgeX = CoverMorphMotion()

    init {
        motion.value = if (toNative) 0f else 1f
        motion.velocity = 0f
        motion.aim(toNative)
    }

    val toNative: Boolean get() = motion.target == 1f
    val progress: Float get() = motion.value

    /** A finger is on it: its progress is the finger's, not its spring's. */
    val held: Boolean get() = dragging

    /** Lays out the first frame now, in the same pass as the switch, so no frame shows either end bare. */
    fun start(): Boolean {
        if (!measure()) return false
        mini.beginMorph()
        running = true
        startedAt = SystemClock.uptimeMillis()
        if (!apply()) {
            restore()
            running = false
            return false
        }
        observe(true)
        Choreographer.getInstance().postFrameCallback(this)
        Xp.log("MCMini: container morph to=${if (toNative) "native" else "mini"} " +
            "paired=${pieces.count { it.paired }}")
        return true
    }

    /** Starts held by a finger: the first frame is the resting end, nudged by nothing yet. */
    fun startDragging(): Boolean {
        dragging = true
        if (start()) return true
        dragging = false
        return false
    }

    /** One finger position: progress (rubber-banded past either end by the caller) and nudge. */
    fun drag(progress: Float, nudgePx: Float, nudgeXPx: Float = 0f) {
        if (!running) return
        motion.value = progress
        motion.velocity = 0f
        nudge.value = nudgePx / NUDGE_UNIT
        nudge.velocity = 0f
        nudgeX.value = nudgeXPx / NUDGE_UNIT
        nudgeX.velocity = 0f
        apply()
    }

    /**
     * Another morph's pose, on its frame: [share] of the way from [from] to the leader's
     * progress and nudge. The islands that go with the music's morph run on its one spring -
     * the same progress, the same overshoot - and differ only by their own two ends. A follower
     * whose row came late joins it over a few frames rather than jumping to it.
     */
    fun follow(leader: MiniCardMorph, share: Float = 1f, from: Float = leader.motion.value,
               invert: Boolean = false) {
        if (!running) return
        // [invert]: the other way on the same spring - one island going up into its card as
        // another comes down out of its own, the super island's expanded switch.
        val led = if (invert) 1f - leader.motion.value else leader.motion.value
        motion.value = lerp(from, led, share)
        motion.velocity = 0f
        nudge.value = if (invert) 0f else leader.nudge.value * share
        nudge.velocity = 0f
        nudgeX.value = if (invert) 0f else leader.nudgeX.value * share
        nudgeX.velocity = 0f
        ledAt = SystemClock.uptimeMillis()
        apply()
    }

    /**
     * When a leader last posed it (follow). Held by no finger but started as dragged, a follower
     * was posed twice a frame - by the leader, and again by its own frame keeping the far end
     * live - two full passes where one is drawn: 2.4ms of every switch frame (traced 2026-09-25).
     */
    private var ledAt = 0L

    /**
     * The finger lets go: both springs take over from where it left them, the progress with
     * the finger's own speed, so a flick carries on and overshoots and a slow lift settles.
     */
    fun release(toNative: Boolean, velocity: Float) {
        if (!running) return
        dragging = false
        motion.aim(toNative)
        // A hard fling handed on whole crossed the rest of the way in one frame: a flight let go
        // at 0.3 was past its end on the next, and its crossfade onto the island happened all at
        // once (2026-09-25). Fast, but over frames.
        motion.velocity = velocity.coerceIn(-MAX_RELEASE_SPEED, MAX_RELEASE_SPEED)
        nudge.target = 0f
        nudgeX.target = 0f
        lastFrame = 0L
        startedAt = SystemClock.uptimeMillis()
    }

    fun artworkBox(): CoverMorphMotion.Box? = if (running) artDrawn else null

    /** The container as this frame drew it, on screen: what presses on the shortcut discs. */
    fun containerBox(): CoverMorphMotion.Box? = if (running) boxDrawn else null

    /** Where it stood when a finger took it: the drag carries on from exactly here. */
    class Grab(val progress: Float, val nudge: Float, val nudgeX: Float)

    /** Moving on its own springs, and the point is on the container as drawn. */
    fun catchableAt(x: Float, y: Float): Boolean {
        if (!running || dragging) return false
        val b = boxDrawn ?: return false
        return x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h
    }

    /** A finger stops it where it is; from here it is dragged, not sprung. */
    fun grab(): Grab? {
        if (!running) return null
        dragging = true
        motion.velocity = 0f
        return Grab(motion.value, nudge.value * NUDGE_UNIT, nudgeX.value * NUDGE_UNIT)
    }

    /** Turns the same container round; the spring keeps its velocity. */
    fun aim(toNative: Boolean) {
        if (!running) return
        motion.aim(toNative)
        startedAt = SystemClock.uptimeMillis()
    }

    /** Ends at the current target at once, for a keyguard going away or a session ending. */
    fun cancel() {
        if (!running) return
        finish(false)
    }

    override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC cardMorph"); try {
        if (!running) return
        // Asleep, bouncer, control centre: the same test as the cover morph's, every frame.
        if (!Main.coverMorphStillEligible()) {
            finish(false)
            return
        }
        if (dragging) {
            // The finger writes the progress; this only keeps the far end live under it. A
            // leader posing it this frame has done that already.
            if (SystemClock.uptimeMillis() - ledAt < LED_FRESH_MS) Choreographer.getInstance().postFrameCallback(this)
            else if (!apply()) finish(false)
            else Choreographer.getInstance().postFrameCallback(this)
            return
        }
        // Stepped by the real gap, as CoverMorphLayer does: holding a slow frame read worse.
        val dt = if (lastFrame == 0L) 1f / 120f
            else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = frameTimeNanos
        motion.step(dt, Main.sClockResponse)
        nudge.step(dt, Main.sClockResponse)
        nudgeX.step(dt, Main.sClockResponse)
        if (!apply()) {
            finish(false)
            return
        }
        val late = SystemClock.uptimeMillis() - startedAt > SETTLE_LIMIT_MS
        if (motion.atRest() && nudge.atRest() && nudgeX.atRest()
            && (late || listener.canSettle(this, toNative))) finish(true)
        else Choreographer.getInstance().postFrameCallback(this)
    } finally { android.os.Trace.endSection() } }

    /**
     * Which pieces have a counterpart to land on, decided once: a card element hidden now (the
     * cover's own artwork setting, say) stays unpaired rather than being picked up mid-flight.
     */
    private fun measure(): Boolean {
        if (!header.isAttachedToWindow || header.width <= 0 || header.height <= 0) return false
        pieces.forEach { piece ->
            val n = piece.native
            piece.paired = n != null && n.isAttachedToWindow && n.visibility == View.VISIBLE
                && n.alpha > 0.01f && n.width > 0 && n.height > 0 && isInside(n, header)
                && (!piece.text || n is TextView && piece.view is TextView)
        }
        return true
    }

    private fun apply(): Boolean {
        val miniRest = traced("MC m.rest") { restBox?.invoke() ?: mini.restBoxOnScreen() } ?: return false
        val nativeRest = traced("MC m.native") { headerRestBox() } ?: return false
        if (miniRest.w <= 0f || nativeRest.w <= 0f) return false
        val c = motion.value.coerceIn(0f, 1f)
        val framed = containerFrame(miniRest, nativeRest, motion.value)
        // The nudge moves the whole container toward the card (up from the pill, down from it).
        val towardNative = if (nativeRest.y <= miniRest.y) -1f else 1f
        val box = CoverMorphMotion.Box(framed.x + nudgeX.value * NUDGE_UNIT,
            framed.y + towardNative * nudge.value * NUDGE_UNIT, framed.w, framed.h)
        val radius = lerp(miniRest.h / 2f, nativeRadius, c)

        // The card, at the container's width and cut to its height. s is in the card's own
        // pixels, before the stack's transform.
        val s = box.w / header.width
        if (header.visibility != View.VISIBLE) header.visibility = View.VISIBLE
        traced("MC m.place") { placeHeader(box, s) }
        clipW = header.width.toFloat()
        clipH = min(header.height.toFloat(), box.h / s)
        clipR = radius / s
        if (header.outlineProvider !== clipOutline) header.outlineProvider = clipOutline
        if (!header.clipToOutline) header.clipToOutline = true
        header.invalidateOutline()
        header.transitionAlpha = saved.transitionAlpha * nativeIn(c)

        // The mini player's frame is the container itself; its material fades last.
        boxDrawn = box
        traced("MC m.frame") { mini.setMorphFrame(box, radius, materialOut(c)) }

        // Its content: first scaled with the container, then onto the card's own elements. From
        // a small island's circle, the pill's layout is not what is at rest there: the circle
        // holds only its picture, centred, and the lines come in out of it as it widens. Laid
        // out as a whole pill in a circle, the flight drew its title and text out over the
        // camera for its last frames home, then snapped to the circle (filmed 2026-09-25).
        val laidW = max(1, mini.layoutParams?.width ?: mini.width).toFloat()
        val round = roundness?.invoke()?.coerceIn(0f, 1f) ?: if (circle) 1f else 0f
        val m = lerp(box.w / miniRest.w, box.w / laidW, round)
        val mix = pieceMix(c)
        val bridged = listener.artBridged()
        android.os.Trace.beginSection("MC m.pieces")
        pieces.forEach { piece ->
            val v = piece.view
            val layoutX = offsetX(v)
            val layoutY = offsetY(v)
            val ax = anchorX(v, piece.text)
            val ay = anchorY(v, piece.text)
            // Where the anchor sits in the container, and at what scale, if it only followed it.
            var tx = (layoutX + piece.baseTx + ax) * m
            var ty = (layoutY + piece.baseTy + ay) * m
            var kx = m
            var ky = m
            if (round > 0f && piece.art) {
                // The small island's picture: its share of the circle, in the middle of it -
                // growing with the container's height as the circle becomes the row.
                val side = box.h * CIRCLE_ICON_SHARE
                kx = lerp(kx, side / max(1, v.width), round)
                ky = lerp(ky, side / max(1, v.height), round)
                tx = lerp(tx, (min(box.w, box.h) - side) / 2f, round)
                ty = lerp(ty, (box.h - side) / 2f, round)
            }
            if (piece.paired) {
                val n = piece.native!!
                val nx = (offsetIn(n, header, true) + anchorX(n, piece.text)) * s
                val ny = (offsetIn(n, header, false) + anchorY(n, piece.text)) * s
                val nky = if (piece.text) (n as TextView).textSize * s / (v as TextView).textSize
                    else n.width * s / max(1, v.width)
                val nkx = if (piece.text) textWidthScale(v as TextView, n as TextView, s, nky)
                    else nky
                tx = lerp(tx, nx, mix)
                ty = lerp(ty, ny, mix)
                kx = lerp(kx, nkx, mix)
                ky = lerp(ky, nky, mix)
                // The card's own line stays hidden until the pill's leaves it: the two faces
                // differ in weight, and both drawn in full read as a doubled title.
                if (piece.text) {
                    val shown = 1f - pairedOut(c)
                    n.transitionAlpha = piece.nativeTransitionAlpha * shown
                    // ...and comes into focus just after, as the super island's arriving layer.
                    if (shown > 0f) {
                        val r = blurPx * (1f - nativeFocus(c)) / max(s, 0.05f)
                        blurTo(n, r, r, piece.nativeBlur)
                    }
                }
            }
            v.pivotX = 0f
            v.pivotY = 0f
            v.scaleX = kx
            v.scaleY = ky
            v.translationX = tx - layoutX - ax * kx
            v.translationY = ty - layoutY - ay * ky
            val asPill = if (piece.paired) pairedOut(c) else earlyOut(c)
            val a = when {
                piece.art && bridged -> 0f
                piece.art -> asPill
                // Out of a circle, only the picture is there at first; the lines join it once
                // the shape has room for them.
                else -> lerp(asPill, if (piece.paired) circleIn(c) * pairedOut(c) else 0f, round)
            }
            v.alpha = a
            // Out of focus as far as it has faded; the cover's own flight has the bridged artwork.
            if (a > 0f || piece.blur[0] == 0f) {
                val r = if (piece.art && bridged) 0f else blurPx * (1f - a)
                blurTo(v, r / max(kx, 0.05f), r / max(ky, 0.05f), piece.blur)
            }
            if (piece.art) {
                artDrawn = CoverMorphMotion.Box(box.x + tx - ax * kx, box.y + ty - ay * ky,
                    v.width * kx, v.height * ky)
                // Round in the card's own corner by the time it lies on the card's artwork.
                val landed = if (piece.paired) nativeArtRadius * s / max(0.01f, kx)
                    else mini.artworkRestRadius()
                // A small island's picture is round.
                val home = lerp(mini.artworkRestRadius(), min(v.width, v.height) / 2f, round)
                mini.setArtworkMorphRadius(lerp(home, landed, mix))
            }
        }
        android.os.Trace.endSection()
        traced("MC m.onFrame") { listener.onFrame(this, c) }
        return true
    }

    private fun finish(completed: Boolean) {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        observe(false)
        placedBox = null
        restore()
        listener.onSettled(this, toNative, completed)
    }

    private fun restore() {
        pieces.forEach { piece ->
            val v = piece.view
            v.scaleX = 1f
            v.scaleY = 1f
            v.resetPivot()
            v.translationX = piece.baseTx
            v.translationY = piece.baseTy
            v.alpha = 1f
            blurTo(v, 0f, 0f, piece.blur)
            if (piece.paired && piece.text) piece.native?.let {
                it.transitionAlpha = piece.nativeTransitionAlpha
                blurTo(it, 0f, 0f, piece.nativeBlur)
            }
        }
        mini.endMorph()
        header.setAnimationMatrix(null)
        if (header.outlineProvider === clipOutline) header.outlineProvider = saved.outline
        header.clipToOutline = saved.clip
        header.transitionAlpha = saved.transitionAlpha
        header.visibility = saved.visibility
    }

    /**
     * Where the stack has the card this frame, on screen: its slot plus the stack's own
     * transform, read live, so a stack that moves mid-morph simply moves the far end.
     */
    private fun headerRestBox(): CoverMorphMotion.Box? {
        if (!header.isAttachedToWindow || header.width <= 0 || header.height <= 0) return null
        val origin = headerOrigin() ?: return null
        oemMatrix.set(header.matrix)
        corner[0] = 0f
        corner[1] = 0f
        oemMatrix.mapPoints(corner)
        val x0 = corner[0]
        val y0 = corner[1]
        corner[0] = header.width.toFloat()
        corner[1] = 0f
        oemMatrix.mapPoints(corner)
        val w = corner[0] - x0
        val dy = nativeDy?.invoke() ?: 0f
        return CoverMorphMotion.Box(origin[0] + x0, origin[1] + y0 + dy,
            w, header.height * (w / header.width))
    }

    /** The card's slot on screen: its parent's origin, less any scroll, plus left and top. */
    private fun headerOrigin(): FloatArray? {
        val parent = header.parent as? View ?: return null
        parent.getLocationOnScreen(xy)
        return floatArrayOf(xy[0] - parent.scrollX + header.left.toFloat(),
            xy[1] - parent.scrollY + header.top.toFloat())
    }

    /**
     * Puts the card's own pixels at the container: p -> box.origin + s * p. The renderer draws
     * a view as slot * animationMatrix * ownTransform, so the animation matrix is that mapping
     * with the stack's transform taken back out - whatever the stack writes next frame is taken
     * out again then.
     */
    private fun placeHeader(box: CoverMorphMotion.Box, s: Float) {
        val origin = headerOrigin() ?: return
        oemMatrix.set(header.matrix)
        placedBox = box
        placedScale = s
        placedOem.set(oemMatrix)
        morphMatrix.setScale(s, s)
        morphMatrix.postTranslate(box.x - origin[0], box.y - origin[1])
        if (!oemMatrix.isIdentity) {
            val inverse = Matrix()
            if (oemMatrix.invert(inverse)) morphMatrix.preConcat(inverse)
        }
        header.setAnimationMatrix(morphMatrix)
    }

    /** Layout position inside the mini player; its own pieces carry no parent transform. */
    private fun offsetX(v: View): Float {
        var x = v.left.toFloat()
        var p = v.parent
        while (p is View && p !== mini) { x += p.left; p = p.parent }
        return x
    }

    private fun offsetY(v: View): Float {
        var y = v.top.toFloat()
        var p = v.parent
        while (p is View && p !== mini) { y += p.top; p = p.parent }
        return y
    }

    /** Position inside the card as drawn at rest, the OEM's own offsets (a centred title) included. */
    private fun offsetIn(v: View, ancestor: View, horizontal: Boolean): Float {
        var sum = 0f
        var cur: View? = v
        while (cur != null && cur !== ancestor) {
            sum += if (horizontal) cur.left + cur.translationX else cur.top + cur.translationY
            cur = cur.parent as? View
        }
        return sum
    }

    private fun isInside(v: View, ancestor: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }

    /** Text lines up by where its first glyph starts and by its baseline; anything else by its corner. */
    private fun anchorX(v: View, text: Boolean): Float {
        if (!text || v !is TextView) return 0f
        val lineLeft = v.layout?.getLineLeft(0) ?: 0f
        return v.totalPaddingLeft + lineLeft - v.scrollX
    }

    /**
     * The horizontal scale that makes the pill's line exactly as long as the card's. The two are
     * set in different weights, so by text size alone the glyphs drift apart along the line - by
     * the last digit, most of a stroke. The height stays on the text size ratio.
     */
    private fun textWidthScale(v: TextView, n: TextView, s: Float, bySize: Float): Float {
        val ratio = widthRatio(v, n)
        if (ratio.isNaN()) return bySize
        return (ratio * s).coerceIn(bySize * 0.8f, bySize * 1.25f)
    }

    /**
     * The card's line over the pill's, measured once per text and paint size: two measureText
     * calls per line every frame, and a string made for each, for a number that does not change
     * while the morph runs. NaN when the two do not hold the same text.
     */
    private fun widthRatio(v: TextView, n: TextView): Float {
        val a = v.text
        val b = n.text
        val cached = widthCache[v]
        if (cached != null && cached.mine === a && cached.theirs === b &&
            cached.mineSize == v.textSize && cached.theirSize == n.textSize) return cached.ratio
        val text = a?.toString().orEmpty()
        var ratio = Float.NaN
        if (text.isNotEmpty() && text == b?.toString()) {
            val mine = v.paint.measureText(text)
            val theirs = n.paint.measureText(text)
            if (mine > 0f && theirs > 0f) ratio = theirs / mine
        }
        widthCache[v] = WidthRatio(a, b, v.textSize, n.textSize, ratio)
        return ratio
    }

    private class WidthRatio(val mine: CharSequence?, val theirs: CharSequence?,
                             val mineSize: Float, val theirSize: Float, val ratio: Float)

    private val widthCache = HashMap<View, WidthRatio>()

    private fun anchorY(v: View, text: Boolean): Float =
        if (text && v is TextView && v.baseline > 0) v.baseline.toFloat() else 0f

    companion object {
        /** The most progress per second a let-go hands the spring. */
        private const val MAX_RELEASE_SPEED = 5f

        /** Past this the destination is handed back even if the scene is still moving. */
        private const val SETTLE_LIMIT_MS = 2200L

        /** A leader's pose this recent stands for the frame's (follow). */
        private const val LED_FRESH_MS = 12L

        /** The nudge spring runs in hundreds of pixels, the scale its thresholds were made for. */
        private const val NUDGE_UNIT = 100f

        /**
         * The container runs straight between the two, unlike the artwork's bowed path: a
         * card-sized shape swinging sideways reads as a slide, not a change of shape. Past
         * either end - a rubber-banded drag, a flick's overshoot - it carries on along the same
         * line; the size only a little, as the artwork's does.
         */
        fun containerFrame(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box,
                           progress: Float): CoverMorphMotion.Box {
            val p = progress.coerceIn(-0.3f, 1.3f)
            val sizeP = progress.coerceIn(-0.025f, 1.025f)
            val cx = lerp(a.cx(), b.cx(), p)
            val w = lerp(a.w, b.w, sizeP)
            val h = lerp(a.h, b.h, sizeP)
            // Held by the top edge: both layouts begin at the top, so their content lines up there.
            val top = lerp(a.y, b.y, p)
            return CoverMorphMotion.Box(cx - w / 2f, top, w, h)
        }

        /** A small island's picture against its circle: ShortcutDisc's ICON_SHARE. */
        const val CIRCLE_ICON_SHARE = 0.62f

        /** Out of a circle, a line comes in once the shape has grown past its first stretch. */
        fun circleIn(p: Float) = smooth(0.12f, 0.4f, p)

        /** The mini player's own controls leave first, before the shape has grown much. */
        fun earlyOut(p: Float) = 1f - smooth(0f, 0.3f, p)

        /** Paired pieces reach the card's elements by the middle and sit there from then on. */
        fun pieceMix(p: Float) = smooth(0f, 0.5f, p)

        /**
         * ...and only fade once they lie exactly over them and the pill's glass has gone from
         * between the two: fading through that glass, the artwork read as dimming, then coming
         * back as the glass cleared.
         */
        fun pairedOut(p: Float) = 1f - smooth(0.85f, 0.93f, p)

        /** The card's own line in focus: as it comes in (pairedOut), a little later. */
        fun nativeFocus(p: Float) = smooth(0.87f, 0.97f, p)

        /** The super island's 40px content blur, at this phone's 3x. */
        const val BLUR_DP = 40f / 3f

        /** A piece scaled far down still gets no more than this, in its own pixels. */
        const val BLUR_MAX_PX = 200f

        /** The card comes in under the mini player's material, which still covers it. */
        fun nativeIn(p: Float) = smooth(0.1f, 0.5f, p)

        /**
         * The mini player's material leaves last and alone. Crossfading the two materials at
         * once would leave both part-transparent in the middle - a dip in the glass.
         */
        fun materialOut(p: Float) = 1f - smooth(0.45f, 0.85f, p)

        fun smooth(start: Float, end: Float, value: Float): Float {
            val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        /**
         * Resistance past a limit: the classic rubber band, 1:1 at first and flattening toward
         * [limit], so the harder the pull the less it gives - never a wall, never a free slide.
         */
        fun rubber(x: Float, limit: Float, give: Float = 0.8f): Float {
            if (x <= 0f || limit <= 0f) return 0f
            return limit * (1f - 1f / (x * give / limit + 1f))
        }
    }
}
