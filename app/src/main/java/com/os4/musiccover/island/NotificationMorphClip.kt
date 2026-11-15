package com.os4.musiccover.island

import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.os4.musiccover.Xp
import kotlin.math.roundToInt

/** Keep the glass on a rounded-rectangle outline, independently of the row's content clip. */
internal class NotificationMorphClip(private val row: View, private val clipRow: Boolean = false) {
    private abstract class MorphOutline : ViewOutlineProvider() {
        abstract val morphRadius: Float
        abstract val morphHeight: Float
    }
    private var width = -1f
    private var height = -1f
    private var radius = -1f
    private val targets = ArrayList<Target>()

    init {
        if (clipRow) targets += Target(row)
        fun collect(view: View, depth: Int) {
            val name = view.javaClass.name
            if (name.contains("NotificationBackgroundView")) {
                targets += Target(view)
                return
            }
            // A grouped child's glass keeps its own shape inside the parent row's clip.
            // Giving every child's background the summary's outline duplicates the material.
            if (view !== row && name.contains("ExpandableNotificationRow") ||
                name.contains("NotificationContentView") || view !is ViewGroup || depth >= 16) return
            for (i in 0 until view.childCount) collect(view.getChildAt(i), depth + 1)
        }
        collect(row, 0)
    }

    fun apply(width: Float, height: Float, radius: Float) {
        val w = width.coerceAtLeast(0f)
        val h = height.coerceAtLeast(0f)
        val r = radius.coerceIn(0f, minOf(w, h) / 2f)
        val changed = w != this.width || h != this.height || r != this.radius
        this.width = w
        this.height = h
        this.radius = r
        for (target in targets) target.apply(changed)
    }

    fun close() { targets.forEach { it.restore() } }

    private inner class Target(private val view: View) {
        private var nativeOutline = view.outlineProvider
        private var nativeClip = view.clipToOutline
        private var failed = false
        private val matrix = Matrix()
        private val inverse = Matrix()
        private val bounds = RectF()
        private val provider = object : MorphOutline() {
            override val morphRadius: Float get() = radius
            override val morphHeight: Float get() = height
            override fun getOutline(view: View, outline: Outline) {
                // The OEM provider also sets the background RenderNode's actual height.
                // Preserve that work, including its native bottom clipping, on every frame.
                nativeOutline?.getOutline(view, outline)
                if (failed) return
                runCatching {
                    if (width <= 0f || height <= 0f) { outline.setEmpty(); return@runCatching }
                    bounds.set(0f, 0f, width, height)
                    var localRadius = radius
                    if (view !== row) {
                        matrix.reset()
                        view.transformMatrixToGlobal(matrix)
                        if (!matrix.invert(inverse)) return@runCatching
                        matrix.reset()
                        row.transformMatrixToGlobal(matrix)
                        matrix.postConcat(inverse)
                        if (!matrix.rectStaysRect()) return@runCatching
                        matrix.mapRect(bounds)
                        localRadius *= minOf(bounds.width() / width, bounds.height() / height)
                    }
                    // A Path intersection loses the native round rect/radius used by glass.
                    // Keep pixel clipping on the row; the material retains a round-rect shape.
                    outline.setRoundRect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                        bounds.right.roundToInt(), bounds.bottom.roundToInt(), localRadius)
                }.onFailure {
                    failed = true
                    Xp.w("MCMini: notification morph clip unavailable: $it")
                }
            }
        }

        fun apply(changed: Boolean) {
            if (failed) { restore(); return }
            val replaced = view.outlineProvider !== provider
            if (replaced) {
                nativeOutline = view.outlineProvider
                nativeClip = view.clipToOutline
                view.outlineProvider = provider
            } else if (view === row && !view.clipToOutline) nativeClip = false
            // ensureOutlineProvider deliberately leaves native glass backgrounds unclipped.
            // Only the containing row takes an ordinary Android outline clip.
            val unclippedRow = view === row && clipRow && !view.clipToOutline
            if (unclippedRow) view.clipToOutline = true
            if (changed || replaced || unclippedRow || view !== row) view.invalidateOutline()
            if (changed || replaced || unclippedRow) view.invalidate()
        }

        fun restore() {
            if (view.outlineProvider !== provider) return
            view.outlineProvider = nativeOutline
            if (view === row && clipRow) view.clipToOutline = nativeClip
            view.invalidateOutline()
            view.invalidate()
        }
    }

    companion object {
        /** Occlusion masks must follow the rendered capsule corner too, not the native radius. */
        fun radiusOf(view: View): Float? = (view.outlineProvider as? MorphOutline)?.morphRadius
        fun heightOf(view: View): Float? = (view.outlineProvider as? MorphOutline)?.morphHeight
    }
}
