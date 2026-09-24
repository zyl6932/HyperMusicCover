package com.os4.musiccover

import android.graphics.Matrix
import android.graphics.Outline
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
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
) : Choreographer.FrameCallback {
    interface Listener {
        /** Whether the destination can be handed back yet; a scene exit waits for the clock. */
        fun canSettle(morph: MiniCardMorph, toNative: Boolean): Boolean
        fun artBridged(): Boolean
        fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean)
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
    }

    private val motion = CoverMorphMotion()
    private val saved = Saved(header)
    private val nativeRadius = Main.miniPlayerCardRadius(header)
    private val nativeArtRadius = Main.coverMorphThumbnailRadius()
    private val pieces = listOf(
        Piece(mini.artworkView, Main.miniPairArt(), text = false, art = true),
        Piece(mini.titleView, Main.miniPairTitle(), text = true, art = false),
        Piece(mini.artistView, Main.miniPairArtist(), text = true, art = false),
        Piece(mini.toggleView, null, text = false, art = false),
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
     * The finger lets go: both springs take over from where it left them, the progress with
     * the finger's own speed, so a flick carries on and overshoots and a slow lift settles.
     */
    fun release(toNative: Boolean, velocity: Float) {
        if (!running) return
        dragging = false
        motion.aim(toNative)
        motion.velocity = velocity
        nudge.target = 0f
        nudgeX.target = 0f
        lastFrame = 0L
        startedAt = SystemClock.uptimeMillis()
    }

    fun artworkBox(): CoverMorphMotion.Box? = if (running) artDrawn else null

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

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        // Asleep, bouncer, control centre: the same test as the cover morph's, every frame.
        if (!Main.coverMorphStillEligible()) {
            finish(false)
            return
        }
        if (dragging) {
            // The finger writes the progress; this only keeps the far end live under it.
            if (!apply()) finish(false)
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
    }

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
        val miniRest = mini.restBoxOnScreen() ?: return false
        val nativeRest = headerRestBox() ?: return false
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
        placeHeader(box, s)
        clipW = header.width.toFloat()
        clipH = min(header.height.toFloat(), box.h / s)
        clipR = radius / s
        if (header.outlineProvider !== clipOutline) header.outlineProvider = clipOutline
        if (!header.clipToOutline) header.clipToOutline = true
        header.invalidateOutline()
        header.transitionAlpha = saved.transitionAlpha * nativeIn(c)

        // The mini player's frame is the container itself; its material fades last.
        boxDrawn = box
        mini.setMorphFrame(box, radius, materialOut(c))

        // Its content: first scaled with the container, then onto the card's own elements.
        val m = box.w / miniRest.w
        val mix = pieceMix(c)
        val bridged = listener.artBridged()
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
                if (piece.text) n.transitionAlpha = piece.nativeTransitionAlpha * (1f - pairedOut(c))
            }
            v.pivotX = 0f
            v.pivotY = 0f
            v.scaleX = kx
            v.scaleY = ky
            v.translationX = tx - layoutX - ax * kx
            v.translationY = ty - layoutY - ay * ky
            v.alpha = when {
                piece.art && bridged -> 0f
                piece.paired -> pairedOut(c)
                else -> earlyOut(c)
            }
            if (piece.art) {
                artDrawn = CoverMorphMotion.Box(box.x + tx - ax * kx, box.y + ty - ay * ky,
                    v.width * kx, v.height * ky)
                // Round in the card's own corner by the time it lies on the card's artwork.
                val landed = if (piece.paired) nativeArtRadius * s / max(0.01f, kx)
                    else mini.artworkRestRadius()
                mini.setArtworkMorphRadius(lerp(mini.artworkRestRadius(), landed, mix))
            }
        }
        return true
    }

    private fun finish(completed: Boolean) {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
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
            if (piece.paired && piece.text) piece.native?.transitionAlpha = piece.nativeTransitionAlpha
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
        return CoverMorphMotion.Box(origin[0] + x0, origin[1] + y0,
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
        val text = v.text?.toString().orEmpty()
        if (text.isEmpty() || text != n.text?.toString()) return bySize
        val mine = v.paint.measureText(text)
        val theirs = n.paint.measureText(text)
        if (mine <= 0f || theirs <= 0f) return bySize
        return (theirs * s / mine).coerceIn(bySize * 0.8f, bySize * 1.25f)
    }

    private fun anchorY(v: View, text: Boolean): Float =
        if (text && v is TextView && v.baseline > 0) v.baseline.toFloat() else 0f

    companion object {
        /** Past this the destination is handed back even if the scene is still moving. */
        private const val SETTLE_LIMIT_MS = 2200L

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
