package com.os4.musiccover

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The torch's or the camera's material: a circle of the pill's own glass behind the button.
 *
 * It hangs off the window root just before the lock screen's layer, so the button is drawn over
 * it and the shade and the control centre blur it as they blur the button; the pill, right
 * after that layer, is drawn over both. It is the same container and element pair as the pill
 * (MiniPlayerRuntime.material): this frame is the blur container, [element] replays the card's
 * recorded calls. It never takes a touch - the button above it keeps its own.
 */
internal class ShortcutDisc(context: Context) : FrameLayout(context) {
    private val element = ImageView(context)
    private var dressWith: ((ImageView) -> Unit)? = null
    private var dressedAs = -1
    private var shapeW = 0
    private var shapeH = 0
    private var shapeDx = 0

    /**
     * The disc is squeezed by its shape, not by a scale: a frame wider than any squeeze with a
     * rounded rectangle cut out of its middle, as tall and wide as the squeeze makes it. An
     * oval clip under an uneven scale was drawn with jagged edges (filmed 2026-09-24); a
     * rounded rectangle at its own pixels keeps them smooth. Round at rest, a capsule when
     * squeezed either way.
     */
    private val frameShape = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val l = (view.width - shapeW) / 2 + shapeDx
            val t = (view.height - shapeH) / 2
            outline.setRoundRect(l, t, l + shapeW, t + shapeH, min(shapeW, shapeH) / 2f)
        }
    }
    private val elementShape = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) =
            outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) / 2f)
    }

    init {
        outlineProvider = frameShape
        clipToOutline = true
        outlineAmbientShadowColor = Color.TRANSPARENT
        outlineSpotShadowColor = Color.TRANSPARENT
        element.scaleType = ImageView.ScaleType.FIT_XY
        addView(element, LayoutParams(0, 0))
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** The card's material, applied again only when the card has been dressed anew. */
    fun dress(generation: Int, apply: (ImageView) -> Unit) {
        dressWith = apply
        if (generation == dressedAs) return
        dressedAs = generation
        if (shapeW > 1 && shapeH > 1) applyDress()
    }

    /**
     * The disc's shape this frame, in pixels: centred in the frame, or [dx] off centre - a
     * small island leaving the pill's place, still where the pill was.
     */
    /** The width setShape last gave it: what it is drawn as, whatever its frame. */
    val shapeWidth: Int get() = shapeW

    fun setShape(w: Int, h: Int, dx: Int = 0) {
        if (w == shapeW && h == shapeH && dx == shapeDx) return
        val first = shapeW <= 1 || shapeH <= 1
        shapeW = w
        shapeH = h
        shapeDx = dx
        placeElement()
        invalidateOutline()
        element.invalidateOutline()
        // A material applied before there was a size draws nothing; it goes on again now.
        if (first && w > 1 && h > 1) applyDress()
    }

    /**
     * The picture in the round end at the shape's start rather than in its middle: where a
     * pill's artwork sits, for a small island taking over from a flight still wider than round.
     */
    var iconAtStart = false
        set(value) {
            if (field == value) return
            field = value
            placeElement()
        }

    private fun placeElement() {
        val l = (width - shapeW) / 2 + shapeDx
        val t = (height - shapeH) / 2
        element.layout(l, t, l + shapeW, t + shapeH)
        icon?.let {
            // Inset from the shape, round: an app icon or an album cover sits inside the glass.
            val side = (min(shapeW, shapeH) * ICON_SHARE).toInt()
            val il = if (iconAtStart) l + (min(shapeW, shapeH) - side) / 2 else l + (shapeW - side) / 2
            val it0 = (height - side) / 2
            it.layout(il, it0, il + side, it0 + side)
        }
    }

    private var icon: ImageView? = null

    /**
     * The picture as it is, whole and uncut: a focus template's, which the super island draws
     * inside its small island rather than filling the circle with.
     */
    fun setIconBare(bare: Boolean) {
        iconBare = bare
        icon?.let { v ->
            v.clipToOutline = !bare
            v.scaleType = if (bare) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        }
    }

    private var iconBare = false

    /** What a small island shows on its glass: the island's picture, or nothing. */
    fun setIcon(drawable: Drawable?) {
        val view = icon ?: ImageView(context).apply {
            scaleType = if (iconBare) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            clipToOutline = !iconBare
            outlineProvider = elementShape
            icon = this
            addView(this, LayoutParams(0, 0))
        }
        if (view.drawable !== drawable) view.setImageDrawable(drawable)
        view.visibility = if (drawable == null) View.GONE else View.VISIBLE
        view.transitionAlpha = if (iconHidden) 0f else 1f
        placeElement()
    }

    /**
     * The picture out of sight whatever its alpha is doing: a copy of it is flying in from the
     * cover to land here (MiniPlayerController.applyArtBridge).
     */
    fun setIconHidden(hidden: Boolean) {
        iconHidden = hidden
        icon?.transitionAlpha = if (hidden) 0f else 1f
    }

    private var iconHidden = false

    /** For `op mini`: the picture as it stands - shown, alpha, frame, drawable and its bounds. */
    fun iconState(): String = icon?.let { v ->
        val d = v.drawable
        "v=${v.visibility} a=${"%.2f".format(v.alpha)} ${v.left},${v.top} ${v.width}x${v.height} " +
            "d=${d?.javaClass?.simpleName}@${d?.let { Integer.toHexString(System.identityHashCode(it)) }} " +
            "b=${d?.bounds?.toShortString()} i=${d?.intrinsicWidth}x${d?.intrinsicHeight} " +
            "shape=${shapeW}x${shapeH}+$shapeDx"
    } ?: "no icon"

    /** The picture's alpha: it comes in last on a small island forming out of the pill. */
    fun setIconAlpha(alpha: Float) {
        icon?.let { if (kotlin.math.abs(it.alpha - alpha) > 0.002f) it.alpha = alpha }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) =
        placeElement()

    private fun applyDress() {
        dressWith?.invoke(element)
        // The recipe gives the element the card's own corner; the disc's is its own.
        element.outlineProvider = elementShape
        element.clipToOutline = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    companion object {
        /** A small island's picture against its glass; a pill's bare picture against its height. */
        const val ICON_SHARE = 0.62f
    }
}

/**
 * A spring with more give than the morph's (CoverMorphMotion, damping 0.8): it overshoots once
 * and settles, which is what makes a squeeze read as soft rather than as a scale.
 */
internal class Jelly(var response: Float, var damping: Float) {
    var value = 0f
    var velocity = 0f
    var target = 0f

    fun step(dt: Float) {
        if (dt <= 0f) return
        val frequency = 2.0 * Math.PI / max(0.12f, response)
        val damped = frequency * sqrt(1.0 - damping * damping)
        val x = (value - target).toDouble()
        val decay = exp(-damping * frequency * dt)
        val a = x
        val b = (velocity + damping * frequency * x) / damped
        val phase = damped * dt
        val wave = a * kotlin.math.cos(phase) + b * kotlin.math.sin(phase)
        value = (target + decay * wave).toFloat()
        velocity = (decay * (-damping * frequency * wave
            + damped * (-a * kotlin.math.sin(phase) + b * kotlin.math.cos(phase)))).toFloat()
        if (atRest()) {
            value = target
            velocity = 0f
        }
    }

    fun atRest() = abs(value - target) < 0.001f && abs(velocity) < 0.012f
}

/**
 * The torch, the pill and the camera as one row of soft things that push on each other.
 *
 * Everything is measured in the host's pixels at rest - the pill's rest frame with its nudge,
 * or the morph's container as drawn, and each disc on its button's laid-out centre - so the
 * row's own motion (the swipe's zoom, the doze) never reads as pressure. Coming within [gapPx]
 * of a disc presses on it, weighted by how much the two overlap vertically: the disc gives
 * most of it and the pill the rest. The disc first moves out of the way, taking the icon with
 * it, and flattens only a little; once it has moved as far as it goes, the rest flattens it.
 * A morph's container does not give at all. A finger down sinks whatever it is on, as the
 * super island does - the pill to 0.95, a disc to 0.9 - which only opens the gaps round it.
 *
 * How far each one has to give is worked out from where the pill is this frame. Only a disc's
 * place follows that on a spring, a well damped one, so it moves off a beat late and settles
 * back late; whatever it has not moved yet, it takes as flattening, at once - a quick push
 * squashes it, and it rounds out again as it catches up. The first version sprang the place
 * loosely and made up a shortfall by moving it: filmed (2026-09-24), a disc overshot back into
 * the pill, was pushed out, and shook with the pill standing still. The second sprang nothing,
 * and the row moved as if on a rod. The shape can make up the difference; the place cannot.
 *
 * Nothing here moves a view. [onFrame] asks the controller to lay the row out again, which
 * reads [discScaleX] and the rest back; the springs run only while something is off rest.
 */
internal class MiniSqueeze(private val gapPx: Float, private val onFrame: () -> Unit) :
    Choreographer.FrameCallback {

    private val pillPress = Jelly(PRESS_RESPONSE, PRESS_DAMPING)
    private val smallPress = Jelly(PRESS_RESPONSE, PRESS_DAMPING)
    private val press = arrayOf(Jelly(PRESS_RESPONSE, PRESS_DAMPING), Jelly(PRESS_RESPONSE, PRESS_DAMPING))

    /** Where each disc's place is on its way to: its share of the push, sprung. */
    private val placed = arrayOf(Jelly(PLACE_RESPONSE, PLACE_DAMPING), Jelly(PLACE_RESPONSE, PLACE_DAMPING))

    /**
     * Each disc's flattening, sprung loosely - but never less than it has to be this frame, so
     * it can only lag on the way back: let go, it springs past round, wider than tall, and back.
     */
    private val shaped = arrayOf(Jelly(SHAPE_RESPONSE, SHAPE_DAMPING), Jelly(SHAPE_RESPONSE, SHAPE_DAMPING))

    /** This frame's give: shares of each one's own width. */
    private val squash = FloatArray(2)
    private val push = FloatArray(2)
    private val pillGive = FloatArray(2)

    /**
     * The small island against the pill, pushed together by a finger on either: the small
     * island flattened on its pill side (a share of its width, sprung like a disc's shape) and
     * the pill's right end drawn back (pixels).
     */
    private val smallShaped = Jelly(SHAPE_RESPONSE, SHAPE_DAMPING)
    private var smallSquash = 0f
    private var pillBack = 0f

    /** The row's width this frame - the pill's, or the pill and its small island's together. */
    private var rowWidth = 0f
    private var posted = false
    private var lastFrame = 0L

    /** A finger down on, or lifted off, the pill. */
    fun pressPill(down: Boolean) {
        aimPress(pillPress, down)
        kick()
    }

    /** A finger down on, or lifted off, the small island beside the pill. */
    fun pressSmall(down: Boolean) {
        aimPress(smallPress, down)
        kick()
    }

    /** A finger down on, or lifted off, the torch ([side] 0) or the camera (1). */
    fun pressDisc(side: Int, down: Boolean) {
        aimPress(press[side], down)
        kick()
    }

    /**
     * As the super island presses (IslandGestureAnimator): it sinks slowly under the finger on
     * SCALE_EASE and comes back quickly on SHOW_EASE, with a touch of overshoot.
     */
    private fun aimPress(spring: Jelly, down: Boolean) {
        spring.target = if (down) 1f else 0f
        spring.response = if (down) PRESS_DOWN_RESPONSE else PRESS_UP_RESPONSE
        spring.damping = if (down) PRESS_DOWN_DAMPING else PRESS_UP_DAMPING
    }

    /**
     * This frame's row. [pill] is null when there is none on screen; [pillGives] is false while
     * a morph owns the container's shape. The discs are the rest circles, before any press.
     * [small] is the small island beside the pill, where the finger has it: the row's end
     * against the camera, and soft against the pill itself.
     */
    fun setScene(pill: CoverMorphMotion.Box?, pillGives: Boolean,
                 left: CoverMorphMotion.Box?, right: CoverMorphMotion.Box?,
                 small: CoverMorphMotion.Box? = null) {
        val swell = if (pillGives) pillPressScale() else 1f
        val p = pill?.let { scaled(it, swell, swell) }
        val l = left?.let { scaled(it, discSwell(0), discSwell(0)) }
        val r = right?.let { scaled(it, discSwell(1), discSwell(1)) }
        val sm = if (p != null) small?.let { scaled(it, smallSwell(), smallSwell()) } else null
        // With a small island the row runs from the pill's left end to the small island's right.
        val row = if (p != null && sm != null) {
            CoverMorphMotion.Box(p.x, p.y, max(p.w, sm.x + sm.w - p.x), p.h)
        } else p
        val t = targets(row, l, r, gapPx, pillGives, sm)
        rowWidth = row?.w ?: 0f
        // The pill and the small island, pushed together: the small island takes most of it
        // as flattening, the pill's end the rest, as a disc and the pill share a push.
        var flat = 0f
        pillBack = 0f
        if (p != null && sm != null && sm.w > 0f) {
            val into = ramp(p.x + p.w + gapPx - sm.x, gapPx * (1f - CONTACT_SHARE)) *
                verticalOverlap(p, sm)
            if (into > 0f) {
                pillBack = if (pillGives) min(into * (1f - DISC_SHARE), MAX_GIVE * p.w) else 0f
                flat = ((into - pillBack) / sm.w).coerceIn(0f, HARD_SQUASH)
            }
        }
        smallShaped.target = flat
        if (smallShaped.value < flat) {
            smallShaped.value = flat
            if (smallShaped.velocity < 0f) smallShaped.velocity = 0f
        }
        smallSquash = smallShaped.value.coerceIn(-MAX_WOBBLE, HARD_SQUASH)
        pillGive[0] = t[2]
        pillGive[1] = t[3]
        for (side in 0..1) {
            val spring = placed[side]
            spring.target = t[4 + side]
            // All that has to give this frame, place and shape together.
            val retreat = t[4 + side] + t[side]
            // Only once flattening can take no more does the place jump ahead of its spring.
            val moved = max(spring.value, retreat - HARD_SQUASH).coerceIn(0f, HARD_PUSH)
            push[side] = moved
            val need = (retreat - moved).coerceIn(0f, HARD_SQUASH)
            val shape = shaped[side]
            shape.target = need
            if (shape.value < need) {
                shape.value = need
                if (shape.velocity < 0f) shape.velocity = 0f
            }
            squash[side] = shape.value.coerceIn(-MAX_WOBBLE, HARD_SQUASH)
        }
        if (!allAtRest()) kick()
    }

    /** The disc under a finger, as a scale: it sinks, as a small island does. */
    fun discSwell(side: Int) = 1f - (1f - DISC_PRESSED) * press[side].value

    /** The pill under a finger, as a scale: it sinks, as the big island does. */
    private fun pillPressScale() = 1f - (1f - PILL_PRESSED) * pillPress.value

    /** Across the disc: pressed, then flattened against its outer edge. */
    fun discScaleX(side: Int) = discSwell(side) * (1f - squash[side])

    /** Up the disc: pressed, and bulging a little where it is flattened. */
    fun discScaleY(side: Int) = discSwell(side) * (1f + BULGE * squash[side])

    /**
     * The button's icon: pressed with its disc and a little smaller while it is flattened, but
     * never squashed itself - an unevenly scaled glyph read as a different glyph (the camera
     * became a 0) and drew jagged.
     */
    fun iconScale(side: Int) = discSwell(side) * (1f - ICON_GIVE * squash[side])

    /**
     * How far the disc's centre moves away from the pill, as a share of its rest diameter:
     * pushed aside, and further by its flattening, which keeps its outer edge where the push
     * left it.
     */
    fun discShift(side: Int) = discSwell(side) * (push[side] + squash[side] / 2f)

    /** The pill across: pressed by the finger, less what either disc took back. */
    fun pillScaleX() = pillPressScale() * (1f - pillGive[0] - pillGive[1])

    fun pillScaleY() = pillPressScale() *
        (1f + BULGE * 0.5f * (pillGive[0] + pillGive[1]))

    /** The pill's centre, as a share of its width, pushed away from whichever side pressed. */
    fun pillShift() = (pillGive[0] - pillGive[1]) / 2f

    /**
     * What the row gives on [side], in pixels. With a small island the row is wider than the
     * pill: the camera's push takes the small island back by this much and the pill's right end
     * with it - the shares above, of the row, were once applied to the pill alone, which moved
     * the pill off a small island that stayed where the camera was pushing.
     */
    fun rowGivePx(side: Int) = pillGive[side] * rowWidth

    /**
     * The pill across, [widthPx] wide: pressed, less what the row gave on each side and what
     * the small island pushed its right end back by.
     */
    fun pillScaleX(widthPx: Float) = if (widthPx <= 0f) pillScaleX()
        else pillPressScale() * (1f - (rowGivePx(0) + rowGivePx(1) + pillBack) / widthPx)

    /** The pill's centre, in pixels, pushed away from whichever side pressed. */
    fun pillShiftPx() = (rowGivePx(0) - rowGivePx(1) - pillBack) / 2f

    /** The small island under a finger, as a scale: it sinks, as the super island's does. */
    fun smallSwell() = 1f - (1f - DISC_PRESSED) * smallPress.value

    /** The small island's shape across and up, against its diameter: flattened by the pill. */
    fun smallShapeX() = 1f - smallSquash

    fun smallShapeY() = 1f + BULGE * smallSquash

    /**
     * How far the small island's centre moves off the pill, in shares of its diameter: its
     * flattened side is the pill's, so the far side stays where the finger has it.
     */
    fun smallShift() = smallSquash / 2f

    fun atRest() = allAtRest()

    /** Everything back to rest at once: the row is going away, nothing should spring later. */
    fun reset() {
        (listOf(pillPress, smallPress) + press + placed + shaped).forEach {
            it.value = 0f
            it.velocity = 0f
            it.target = 0f
        }
        squash.fill(0f)
        push.fill(0f)
        pillGive.fill(0f)
        smallShaped.value = 0f
        smallShaped.velocity = 0f
        smallShaped.target = 0f
        smallSquash = 0f
        pillBack = 0f
        rowWidth = 0f
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
        lastFrame = 0L
    }

    private fun allAtRest() = pillPress.atRest() && smallPress.atRest() && press.all { it.atRest() } &&
        placed.all { it.atRest() } && shaped.all { it.atRest() } && smallShaped.atRest()

    private fun kick() {
        if (posted) return
        posted = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC squeeze"); try {
        posted = false
        val dt = if (lastFrame == 0L) 1f / 120f
            else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = frameTimeNanos
        pillPress.step(dt)
        smallPress.step(dt)
        press.forEach { it.step(dt) }
        placed.forEach { it.step(dt) }
        shaped.forEach { it.step(dt) }
        smallShaped.step(dt)
        onFrame()
        if (!allAtRest() && !posted) {
            posted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    } finally { android.os.Trace.endSection() } }

    companion object {
        /** Pressed, the pill sinks to this scale and a disc to this (IslandGestureAnimator). */
        const val PILL_PRESSED = 0.95f
        const val DISC_PRESSED = 0.9f

        /** The most a disc moves aside and flattens, shares of its diameter, as pressure gives. */
        const val MAX_PUSH = 0.42f
        const val MAX_SQUASH = 0.25f

        /**
         * A disc moves this many times its part of the pressure - further than it has to, so it
         * reads as getting out of the way - and flattens only once it can move no further.
         */
        const val PUSH_GAIN = 1.4f

        /**
         * Past those, the rest of what keeps the pill off the disc: aside first, then flat.
         * Beyond both the pill rides over it - only a morph's full-width container gets there.
         */
        const val HARD_PUSH = 0.5f
        const val HARD_SQUASH = 0.5f

        /** The most the pill gives on one side, as a share of its width. */
        const val MAX_GIVE = 0.06f

        /** Of the pressure between pill and disc, the disc takes this much. */
        const val DISC_SHARE = 0.6f

        /** A flattened disc bulges up by this share of what it lost across. */
        const val BULGE = 0.35f

        /** Of the gap, this much is left between pill and disc when they are pressed together. */
        const val CONTACT_SHARE = 0.25f

        private const val PRESS_RESPONSE = 0.3f
        private const val PRESS_DAMPING = 0.5f

        /** The island's own press springs: SCALE_EASE going down, SHOW_EASE coming back. */
        private const val PRESS_DOWN_RESPONSE = 1.0f
        private const val PRESS_DOWN_DAMPING = 0.99f
        private const val PRESS_UP_RESPONSE = 0.35f
        private const val PRESS_UP_DAMPING = 0.95f

        /** A disc's place: a beat behind the pill, and a bounce past where it settles. */
        private const val PLACE_RESPONSE = 0.32f
        private const val PLACE_DAMPING = 0.5f

        /** A disc's shape coming back round: quicker than its place, and looser. */
        private const val SHAPE_RESPONSE = 0.24f
        private const val SHAPE_DAMPING = 0.4f

        /** How far past round the shape swings: wider than tall, by this share. */
        const val MAX_WOBBLE = 0.15f

        /** The icon shrinks by this share of its disc's flattening. */
        const val ICON_GIVE = 0.4f

        /**
         * The six shares for one frame: the torch's and the camera's flattening (of their
         * width), the pill's give on its left and right (of its width), then how far the torch
         * and the camera move aside (of their width). The discs come in at their pressed size.
         * [end] is what meets the camera when it is not the pill's own right end: the small
         * island, which a finger can carry up or down off the row's line.
         */
        fun targets(pill: CoverMorphMotion.Box?, left: CoverMorphMotion.Box?,
                    right: CoverMorphMotion.Box?, gap: Float, pillGives: Boolean,
                    end: CoverMorphMotion.Box? = null): FloatArray {
            val out = FloatArray(6)
            if (pill == null) return out
            val share = if (pillGives) DISC_SHARE else 1f
            val soft = gap * (1f - CONTACT_SHARE)
            fun disc(into: Float, disc: CoverMorphMotion.Box, squashAt: Int, pushAt: Int,
                     meets: CoverMorphMotion.Box = pill) {
                val w = disc.w
                val p = ramp(into, soft) * verticalOverlap(meets, disc)
                if (p <= 0f || w <= 0f) return
                val part = p * share
                var moved = min(part * PUSH_GAIN, MAX_PUSH * w)
                var flat = min(max(0f, part - moved), MAX_SQUASH * w)
                val give = if (pillGives) min(p * (1f - share), MAX_GIVE * pill.w) else 0f
                // What the caps held back would bring the pill onto the disc: made up here.
                val short = p - moved - flat - give
                if (short > 0f) {
                    val more = min(short, max(0f, HARD_PUSH * w - moved))
                    moved += more
                    flat = min(flat + short - more, HARD_SQUASH * w)
                }
                out[pushAt] = moved / w
                out[squashAt] = flat / w
                if (pillGives) out[squashAt + 2] = give / pill.w
            }
            left?.let { disc(it.x + it.w + gap - pill.x, it, 0, 4) }
            right?.let {
                val e = end ?: pill
                disc(e.x + e.w + gap - it.x, it, 1, 5, e)
            }
            return out
        }

        /**
         * How much of [into] - how far the pill has come into the gap - the two must give. It
         * starts from nothing and eases in over the first 2 x [soft], so a touch does not kick;
         * from there on it is [into] less [soft], which leaves the contact share of the gap.
         */
        fun ramp(into: Float, soft: Float): Float = when {
            into <= 0f -> 0f
            soft <= 0f -> into
            into < 2f * soft -> into * into / (4f * soft)
            else -> into - soft
        }

        /** How much of the disc's height the pill spans, 0 to 1. */
        fun verticalOverlap(pill: CoverMorphMotion.Box, disc: CoverMorphMotion.Box): Float {
            if (disc.h <= 0f) return 0f
            val top = max(pill.y, disc.y)
            val bottom = min(pill.y + pill.h, disc.y + disc.h)
            return ((bottom - top) / disc.h).coerceIn(0f, 1f)
        }

        private fun scaled(b: CoverMorphMotion.Box, sx: Float, sy: Float): CoverMorphMotion.Box {
            val w = b.w * sx
            val h = b.h * sy
            return CoverMorphMotion.Box(b.cx() - w / 2f, b.cy() - h / 2f, w, h)
        }
    }
}
