package com.os4.musiccover.island

import com.os4.musiccover.CoverMorphMotion
import com.os4.musiccover.Main
import com.os4.musiccover.Gen
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1), and given only
 * what it uses of the controller (M2, MiniPlayerParts.kt). See the section comments below for
 * what it is.
 */
internal class ShortcutDiscs(
    private val views: RowViews,
    private val rowState: RowState,
    private val edits: RowEdits,
    private val rowHost: RowHost,
    private val flightState: FlightState,
    private val motions: Motions,
    // Made before this part: the motions whose views the discs make way for.
    private val groupMotion: GroupMotion,
    private val cardExchange: CardExchange,
    /** Made after this part: read when the discs are coloured, not when this is built. */
    private val bindingRef: () -> MiniPlayerBinding,
) {
    private val binding get() = bindingRef()

    // ------------------------------------------------------------ the shortcut discs

    val discs = arrayOfNulls<ShortcutDisc>(2)

    /** The lock screen is locked with the mini player on: the discs go behind the buttons. updateVisibility. */
    var discsWanted = false

    /**
     * There is a row - an island, or one out as its row - and so something for the full-screen
     * AOD to keep the buttons for (holdButtonsThroughDoze). With none, the doze takes the buttons
     * and their discs out as it always has.
     */
    var rowWanted = false

    val squeeze = MiniSqueeze(views.dp(MiniPlayerGeometry.DISC_GAP_DP).toFloat()) { onSqueezeFrame() }
    private val buttonSqueeze = arrayOf(Matrix(), Matrix())
    private val buttonSqueezeSet = BooleanArray(2)
    /** What each button's matrix was last written as: a write invalidates the button, so only a change is written. */
    private val buttonSqueezeLast = arrayOf(FloatArray(9), FloatArray(9))
    private val buttonSqueezeValues = FloatArray(9)
    private val discPoint = FloatArray(2)
    private val discUnit = FloatArray(4)
    private val discRest = FloatArray(2)

    fun button(side: Int) = if (side == 0) views.left else views.right

    /** A disc is a circle as tall as the pill. */
    fun discDiameter(): Int = views.dp(MiniPlayerConfig.visibleHeightDp(rowState.config.toString()))

    /** The disc's frame: room for its widest swelling and squeeze around the circle. */
    fun discFrame(d: Int): Int = (d * DISC_FRAME).toInt()

    private fun onSqueezeFrame() {
        if (views.player?.visibility == View.VISIBLE) rowHost.followShortcuts()
        updateDiscs()
    }

    /** A finger down on, or off, the pill ([side] PRESS_PILL) or a disc. */
    fun press(side: Int, down: Boolean) {
        when (side) {
            MiniPlayerRuntime.PRESS_PILL -> squeeze.pressPill(down)
            MiniPlayerRuntime.PRESS_SMALL -> squeeze.pressSmall(down)
            else -> squeeze.pressDisc(side, down)
        }
    }

    /**
     * Which disc's button a point is on, if its disc is showing: the icon, or the
     * shortcut_view_*_layout around it, which is what takes the tap.
     */
    fun discAt(x: Float, y: Float): Int? = (0..1).firstOrNull {
        if (discs[it]?.visibility != View.VISIBLE) return@firstOrNull false
        val icon = button(it)
        val frame = (icon.parent as? View)?.takeIf { p ->
            runCatching { p.resources.getResourceEntryName(p.id) }.getOrNull()?.endsWith("_layout") == true
        }
        views.onButton(icon, x, y) || frame != null && views.onButton(frame, x, y)
    }

    /**
     * Each disc on its button as the button is drawn - its centre, zoom and fade through
     * everything the OEM animates it with (the swipe away, the doze) - then flattened or swollen
     * by the squeeze. The button takes the same squeeze on top of its own motion through its
     * animation matrix, which the OEM does not write and drawnCentre does not read.
     */
    fun updateDiscs() {
        // First, so that the discs below see the buttons as they will be drawn.
        holdButtonsThroughDoze()
        binding.applyInk()
        val d = discDiameter()
        val frame = discFrame(d)
        views.followHost.reset()
        views.host.transformMatrixToGlobal(views.followHost)
        val parent = discParent()
        discToScreen.reset()
        parent.transformMatrixToGlobal(discToScreen)
        val placed = views.followHost.invert(views.hostInverse) && discToScreen.invert(discInverse)
        for (side in 0..1) {
            val button = button(side)
            val shown = placed && discsWanted && button.isShown && button.width > 0 && button.height > 0
            var disc = discs[side]
            if (!shown) {
                if (disc != null && disc.visibility != View.GONE) disc.visibility = View.GONE
                clearButtonSqueeze(side)
                continue
            }
            if (disc == null) {
                disc = ShortcutDisc(views.context)
                discs[side] = disc
            }
            if (!discInPlace(disc, parent)) {
                (disc.parent as? ViewGroup)?.removeView(disc)
                parent.addView(disc, views.layerSlot(parent), ViewGroup.LayoutParams(frame, frame))
                Gen.ownView(parent, disc)
            }
            if (disc.layoutParams.width != frame || disc.layoutParams.height != frame)
                views.resizeSmallIsland(disc, frame, frame)
            disc.dress(views.discMaterialGeneration()) {
                MiniPlayerRuntime.material(it, views.loader, views.notificationMaterial())
            }
            if (disc.visibility != View.VISIBLE) disc.visibility = View.VISIBLE
        }
        // The squeeze from this frame's row, then the discs from the squeeze - in that order, or
        // the discs are a frame behind the pill that is pushing them.
        squeezeScene(d.toFloat())
        for (side in 0..1) {
            val button = button(side)
            val disc = discs[side]?.takeIf { it.visibility == View.VISIBLE } ?: continue
            // In the disc's own parent's pixels: whatever that parent is doing - the doze's zoom
            // on keyguard_root_view - the disc is already drawn with.
            if (!views.drawnCentre(button, discPoint, discInverse)) continue
            val zoom = drawnZoom(button, discInverse)
            val outward = if (side == 0) -1f else 1f
            val cx = discPoint[0] + outward * squeeze.discShift(side) * d * zoom
            // The doze's "gone" is judged on the fade on screen; what is written is the part
            // below the parent, which applies the rest itself.
            val fade = if (unheldInDoze(chainFade(button)) == 0f) 0f
                else chainFade(button, disc.parent as? View ?: views.host)
            // Placed and zoomed with the button; its squeeze is its shape, at its own pixels.
            var tx = cx - frame / 2f - disc.left
            var ty = discPoint[1] - frame / 2f - disc.top
            // At rest on whole pixels, as the pill (MiniPlayerView.rest): the glass is drawn on
            // the pixel grid and the outline clip is not, so a disc left on a half pixel had its
            // rim cut into steps on one side - a button centre at x.5 on a 1280px screen
            // (warsaw, 2026-09-30), while the pill beside it on whole pixels was smooth.
            if (kotlin.math.abs(zoom - 1f) < 0.0005f && squeeze.discShift(side) == 0f) {
                tx = kotlin.math.round(tx)
                ty = kotlin.math.round(ty)
                if (disc.translationX != tx) disc.translationX = tx
                if (disc.translationY != ty) disc.translationY = ty
            }
            setIfChanged(disc, tx, ty, zoom, zoom, fade)
            disc.setShape((d * squeeze.discScaleX(side)).roundToInt().coerceIn(1, frame),
                (d * squeeze.discScaleY(side)).roundToInt().coerceIn(1, frame))
            squeezeButton(side, button, d)
        }
    }

    // ---- the torch and the camera through the doze

    /**
     * The doze does not put the torch and the camera away: it leaves them shown and fades
     * their chain out to a hundredth (`op mini` discs trace, 2026-09-25). The row keeps them:
     * every frame of the doze, before it is drawn, each view from a button's image up to the
     * keyguard's root is put back to full alpha over whatever the doze's animation wrote there
     * this frame - so the buttons are the lock screen's own, drawn as ever, and their discs
     * simply stay on them. The first version drew the glyphs itself onto the discs instead:
     * too big, the torch's half clear, and a flash at every hand-over.
     *
     * After the wake it lets go only once the lock screen's own alpha has been back at full
     * for a moment: it can show a button whole for a frame and then start fading it in from
     * nothing, and let go on that frame the buttons would drop with it.
     *
     * None of that runs in the full-screen doze any more (2026-10-09): 息屏时隐藏锁屏快捷功能 is
     * always on, so the shortcuts are left to the doze's own fade rather than held at full - the
     * torch and the camera dim to a hundredth with the rest of the chain, and the discs follow
     * them down (unheldInDoze). The row this holds for is the one the doze leaves alone.
     */
    var holdButtons = false
    private var holdSince = 0L
    private var backSince = 0L

    private fun holdButtonsThroughDoze() {
        if (rowState.config.optBoolean(MiniPlayerConfig.HIDE_AOD_SHORTCUTS) &&
            MiniPlayerScene.fullScreenAodActive) return
        val root = views.followRoot?.get()
        val now = android.os.SystemClock.uptimeMillis()
        if (MiniPlayerScene.fullScreenAodActive && rowWanted && root != null) {
            holdButtons = true
            holdSince = now
        }
        if (!holdButtons) return
        // Any other AOD is the OEM's: its fade is let through (unheldInDoze).
        if (MiniPlayerScene.customAodActive) {
            holdButtons = false
            backSince = 0L
            return
        }
        // The pad up with the hold still on, put up by something other than a swipe (letGoOnDrag).
        if (!MiniPlayerScene.aodActive && Main.bouncerShown()) {
            letGoOfButtons("bouncer")
            return
        }
        // The lower of the two chains as the doze left it this frame, before it is put back.
        var natural = 1f
        for (side in 0..1) {
            var v: View? = findImage(button(side)) ?: button(side)
            while (v != null && v !== root && v !== views.host) {
                natural = min(natural, v.alpha * v.transitionAlpha)
                if (v.alpha < 1f) v.alpha = 1f
                if (v.transitionAlpha < 1f) v.transitionAlpha = 1f
                v = v.parent as? View
            }
        }
        if (MiniPlayerScene.aodActive) {
            backSince = 0L
            return
        }
        if (natural < 0.99f) backSince = 0L
        else if (backSince == 0L) backSince = now
        if (backSince != 0L && now - backSince > HOLD_SETTLE_MS || now - holdSince > HOLD_MAX_MS ||
            !discsWanted) {
            holdButtons = false
            backSince = 0L
        }
    }

    /**
     * The swipe up within the wake's hold: it let go only once the buttons had been back at full
     * for a while, or two seconds on - and while the swipe faded them it never was, so every
     * frame it wrote them back to 1 and the pill stayed on the held branch, deaf to the row's
     * fade. The torch, the camera and the pill stood over the PIN pad on the first swipe after
     * the wake, and were gone on the next (reported 2026-10-07). A drag past the slop, not the
     * DOWN: a double tap to wake lands in the wake's fade-in, and letting go there drops the
     * buttons to it. True once past the slop, whether or not there was a hold.
     */
    fun letGoOnDrag(dx: Float, dy: Float): Boolean {
        if (kotlin.math.hypot(dx, dy) <= ViewConfiguration.get(views.context).scaledTouchSlop) return false
        letGoOfButtons("swipe")
        return true
    }

    /**
     * The lock screen is leaving: the buttons are the OEM's again, and the pill follows the row's
     * fade from this frame rather than waiting for it to come back to full - it will not, until
     * the pad is put away.
     */
    private fun letGoOfButtons(why: String) {
        if (MiniPlayerScene.aodActive || !holdButtons && !rowState.rowHeldOff) return
        holdButtons = false
        backSince = 0L
        edits.rowHeldOff = false
        MiniPlayerRuntime.noteTouch("buttons let go: $why")
    }

    /**
     * The row at rest, in host pixels, for the squeeze: the pill's rest frame with its nudge
     * (or a morph's container as drawn) and each showing disc on its button's laid-out centre.
     */
    private fun squeezeScene(d: Float) {
        fun disc(side: Int): CoverMorphMotion.Box? {
            if (discs[side]?.visibility != View.VISIBLE) return null
            views.restCentre(button(side), discRest)
            return CoverMorphMotion.Box(discRest[0] - d / 2f, discRest[1] - d / 2f, d, d)
        }
        val hostXY = IntArray(2).also(views.host::getLocationOnScreen)
        // A flight's morph is the flight's, and the small island's view in a group is not the
        // pill's: the row pressing on the discs is still the pill's.
        val running = groupMotion.pillMorph()
        val view = views.player
        val pill: CoverMorphMotion.Box? = when {
            running != null -> running.containerBox()?.let {
                CoverMorphMotion.Box(it.x - hostXY[0], it.y - hostXY[1], it.w, it.h)
            }
            view != null && view.visibility == View.VISIBLE -> view.restBoxOnScreen()?.let {
                CoverMorphMotion.Box(it.x - hostXY[0] + view.nudgeX, it.y - hostXY[1] + view.nudgeY,
                    it.w, it.h)
            }
            else -> null
        }
        // The small island at rest where the finger has it; the row is worked out from the two.
        // Only one that has settled counts: one still appearing or leaving sits in the place the
        // pill is taking over from it, and by design the two overlap there - read as a press, the
        // pill's give snapped to its 6% cap in a single frame every time a pull reached the row's
        // end (measured 2026-09-29, `op mini` islandDrag: sq 0.999 -> 0.934 in 6ms, five pulls
        // running). The list's own idiom for a small island really there (showing).
        // The pill only meets it while it gives (setScene); a morph in the pill's place does not.
        val small = views.smallIsland?.takeIf { !views.stackedStyle() && it.visibility == View.VISIBLE && !motions.swapping &&
            flightState.landingBox == null && (!rowState.smallGrowing || views.smallGrow.atRest()) }
            ?.let { CoverMorphMotion.Box(views.smallRest[0] - d / 2f + views.smallNudgeX(),
                views.smallRest[1] - d / 2f + views.smallNudgeY(), d, d) }
        // Not the pill's, but no less in the way: a notification's island on its way up out of
        // the row or down into it - a flight, the other half of a group, an exchange's movers -
        // swept through the discs, the pill and the small island without touching them (reported
        // 2026-09-30). It pushes each away from the place it leaves or comes home to, and not
        // the one whose place that is: that one is its own.
        squeezePushers.clear()
        fun mover(m: MiniCardMorph?) {
            if (m == null || m === running) return
            val it = m.containerBox() ?: return
            val box = CoverMorphMotion.Box(it.x - hostXY[0], it.y - hostXY[1], it.w, it.h)
            val endX = m.miniEndBox()?.cx()?.minus(hostXY[0]) ?: box.cx()
            fun away(b: CoverMorphMotion.Box?) = b != null && (endX < b.x || endX > b.x + b.w)
            squeezePushers += MiniSqueeze.Pusher(box, endX, away(pill), away(small))
        }
        mover(rowState.morph)
        mover(groupMotion.group?.follower)
        cardExchange.exchange?.movers?.values?.forEach { if (it.morph !== rowState.morph) mover(it.morph) }
        squeeze.setScene(pill, running == null, disc(0), disc(1), small, squeezePushers)
    }

    private val squeezePushers = ArrayList<MiniSqueeze.Pusher>(4)

    /**
     * The button rides on its disc in its own slot: moved with it, and swollen or shrunk about
     * its drawn centre there. Both count - a disc only pushed aside, not flattened, left its
     * icon behind when only the scale was checked.
     */
    private fun squeezeButton(side: Int, button: View, d: Int) {
        val k = squeeze.iconScale(side) *
            (rowState.configuredHeightDp / MiniPlayerConfig.DEFAULT_HEIGHT_DP)
        val outward = if (side == 0) -1f else 1f
        // The disc's shift in the button's own pixels: the button's own scale, not its row's.
        val shift = outward * squeeze.discShift(side) * d * button.scaleX
        if (kotlin.math.abs(k - 1f) < 0.0005f && kotlin.math.abs(shift) < 0.25f) {
            clearButtonSqueeze(side)
            return
        }
        val m = buttonSqueeze[side]
        m.setScale(k, k, button.translationX + button.width / 2f,
            button.translationY + button.height / 2f)
        m.postTranslate(shift, 0f)
        // A disc held flattened at rest is the same matrix every frame; written each time, it
        // redrew the lock screen every vsync with nothing moving.
        m.getValues(buttonSqueezeValues)
        if (buttonSqueezeSet[side] && buttonSqueezeValues.contentEquals(buttonSqueezeLast[side])) return
        System.arraycopy(buttonSqueezeValues, 0, buttonSqueezeLast[side], 0, 9)
        button.setAnimationMatrix(m)
        buttonSqueezeSet[side] = true
    }

    private fun clearButtonSqueeze(side: Int) {
        if (!buttonSqueezeSet[side]) return
        buttonSqueezeSet[side] = false
        button(side).setAnimationMatrix(null)
    }

    /** How much a view is scaled on screen, every ancestor's transform included. */
    fun drawnZoom(v: View, inverse: Matrix = views.hostInverse): Float {
        views.scratch.reset()
        v.transformMatrixToGlobal(views.scratch)
        views.scratch.postConcat(inverse)
        discUnit[0] = 0f
        discUnit[1] = 0f
        discUnit[2] = 1f
        discUnit[3] = 0f
        views.scratch.mapPoints(discUnit)
        return kotlin.math.hypot(discUnit[2] - discUnit[0], discUnit[3] - discUnit[1])
    }

    /** A view's fade on screen: its own and every ancestor's, up to the host. */
    /**
     * A doze that does not hold the buttons (holdButtonsThroughDoze) - any AOD but the
     * full-screen one, or that one with no row - takes the pill, the small island and the discs
     * out on the lock screen's own fade, with the buttons. That fade ends near a hundredth, not
     * at nothing; the rest is taken to nothing, so no glass is left on the AOD. Their islands
     * stay as they are - hiding the pill instead let every island's notification back into the
     * stack for the doze and out again at the wake.
     */
    fun unheldInDoze(fade: Float): Float =
        if (MiniPlayerScene.aodActive && !holdButtons && fade < DOZE_GONE) 0f else fade

    private fun chainFade(v: View, upTo: View = views.host): Float {
        var fade = 1f
        var cur: View? = v
        while (cur != null && cur !== upTo && cur !== views.host) {
            fade *= cur.alpha * cur.transitionAlpha
            cur = cur.parent as? View
        }
        return fade
    }

    /** Only real changes: each write invalidates, and a disc at rest must not redraw. */
    private fun setIfChanged(v: View, tx: Float, ty: Float, sx: Float, sy: Float, alpha: Float) {
        if (kotlin.math.abs(v.translationX - tx) > 0.25f) v.translationX = tx
        if (kotlin.math.abs(v.translationY - ty) > 0.25f) v.translationY = ty
        if (kotlin.math.abs(v.scaleX - sx) > 0.0005f) v.scaleX = sx
        if (kotlin.math.abs(v.scaleY - sy) > 0.0005f) v.scaleY = sy
        if (kotlin.math.abs(v.alpha - alpha) > 0.002f) v.alpha = alpha
    }

    fun removeDiscs() {
        squeeze.reset()
        for (side in 0..1) {
            clearButtonSqueeze(side)
            discs[side]?.let { d -> runCatching { (d.parent as? ViewGroup)?.removeView(d) } }
            discs[side] = null
        }
    }
    fun shortcutGeometry(): FloatArray? {
        if (views.host.width <= 0 || views.left.width <= 0 || views.right.width <= 0) return null
        val l = views.restCentre(views.left)
        val r = views.restCentre(views.right)
        val li = iconSize(views.left)
        val ri = iconSize(views.right)
        return floatArrayOf(views.host.width.toFloat(), l[0], l[1], li[0], li[1], r[0], r[1], ri[0], ri[1])
    }

    /**
     * The glyph inside a shortcut button, as drawn: its image fitted into the padded box, then
     * only the part that has ink. The drawable's own box carries a wide transparent margin -
     * taken whole, the preview drew the icons several times too big. 0 if there is none.
     */
    private fun iconSize(v: View): FloatArray {
        val image = findImage(v) ?: return floatArrayOf(0f, 0f)
        val w = (image.width - image.paddingLeft - image.paddingRight).toFloat()
        val h = (image.height - image.paddingTop - image.paddingBottom).toFloat()
        val d = image.drawable
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (w <= 0f || h <= 0f || iw <= 0f || ih <= 0f) return floatArrayOf(0f, 0f)
        val k = min(w / iw, h / ih)
        return runCatching { inkSize(d, (iw * k).toInt(), (ih * k).toInt()) }.getOrNull()
            ?: floatArrayOf(0f, 0f)
    }

    /** The opaque extent of a drawable drawn at this size, in pixels. */
    private fun inkSize(d: android.graphics.drawable.Drawable, w: Int, h: Int): FloatArray? {
        if (w <= 0 || h <= 0) return null
        val copy = d.constantState?.newDrawable(views.context.resources)?.mutate() ?: return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            copy.setBounds(0, 0, w, h)
            copy.draw(android.graphics.Canvas(bitmap))
            val row = IntArray(w)
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if ((row[x] ushr 24) < INK_ALPHA) continue
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < minX || maxY < minY) return null
            return floatArrayOf((maxX - minX + 1).toFloat(), (maxY - minY + 1).toFloat())
        } finally {
            bitmap.recycle()
        }
    }

    /** A pixel this opaque counts as the glyph's ink. */
    private val INK_ALPHA = 40

    private fun findImage(v: View): ImageView? {
        if (v is ImageView && v.drawable != null && v.visibility == View.VISIBLE) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) findImage(v.getChildAt(i))?.let { return it }
        return null
    }

    // Moved here from the controller, their only user (M2).
    val discToScreen = Matrix()
    val discInverse = Matrix()

    /**
     * The discs' parent: keyguard_root_view, not the window root, when the buttons are in it.
     *
     * Under the buttons is not enough - the lock screen's depth cut-out (the wallpaper's subject,
     * deducted_image_view in keyguard_panel_view) is drawn in keyguard_root_view too, after
     * everything before that view in the root and before the buttons' keyguard_bottom_area. A disc
     * in the root, right before keyguard_root_view, was drawn under the subject: wherever the
     * subject reached the torch or the camera, that part of the disc was gone and the icon sat on
     * the picture (#18, measured: the disc's pixels over the subject equal the subject's). Right
     * before the buttons' own branch in keyguard_root_view it is over the subject and under the
     * icon, and otherwise stacked exactly as the buttons are, the shade's blur included.
     */
    fun discParent(): ViewGroup {
        val root = views.followRoot?.get() as? ViewGroup ?: return views.host
        var v: View? = views.left
        while (v != null && v !== views.host) {
            if (v === root) return root
            v = v.parent as? View
        }
        return views.host
    }

    /** Right before the buttons' branch in [parent], with nothing but the other disc between. */
    fun discInPlace(disc: View, parent: ViewGroup): Boolean {
        if (disc.parent !== parent) return false
        val slot = views.layerSlot(parent)
        val at = parent.indexOfChild(disc)
        if (at < 0 || at >= slot) return false
        for (i in at + 1 until slot) if (parent.getChildAt(i) !is ShortcutDisc) return false
        return true
    }
}
