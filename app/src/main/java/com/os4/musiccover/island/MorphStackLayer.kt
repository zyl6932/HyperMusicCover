package com.os4.musiccover.island

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import com.os4.musiccover.cover.CoverMorphMotion.Box
import java.util.IdentityHashMap

/** A root-hosted island must still be occluded as the notification row it represents. */
internal class MorphStackLayer private constructor(
    private val mini: MiniPlayerView,
    private val row: View,
    private val group: Group,
) {
    private var frame: Box? = null
    private var radius = 0f
    private var scale = 1f
    private var progress = 0f
    private var stackedRole = ROLE_NONE
    private var registered = false
    private var renderedZ: RowRenderProperty? = null

    fun setStackedRole(role: Int) {
        stackedRole = role
        if (role != ROLE_NONE) renderedZ = runCatching { RowRenderProperty(row, "TranslationZ") }.getOrNull()
    }

    fun update(box: Box, radius: Float, scale: Float, progress: Float) {
        frame = box
        this.radius = radius
        this.scale = scale
        this.progress = progress
        if (!registered) {
            registered = true
            group.add(this)
        }
    }

    fun close() {
        mini.clearMorphStackClip()
        restoreZ()
        group.remove(this)
        registered = false
        frame = null
    }

    private fun restoreZ() { renderedZ?.let { runCatching { it.restore() } } }

    private fun nativeZ(native: StackRowGeometry): Float =
        renderedZ?.let { row.elevation + it.read() } ?: native.z()

    private fun shape(native: StackRowGeometry): StackOcclusion.Shape? = frame?.let { box ->
        StackOcclusion.Shape(Box(box.x, box.y, box.w,
            StackOcclusion.morphVisibleHeight(box.h, native.height(), native.visibleBottom(), scale, progress)), radius)
    }

    private class Group(private val stack: ViewGroup) {
        private val layers = LinkedHashSet<MorphStackLayer>()
        private val geometry = IdentityHashMap<View, StackRowGeometry>()
        private var observed = false
        private val xy = IntArray(2)
        private val rect = RectF()

        fun add(layer: MorphStackLayer) {
            layers += layer
            orderLast()
        }

        fun remove(layer: MorphStackLayer) {
            layers -= layer
            if (layers.isNotEmpty()) return
            if (observed) Gen.unpreDraw(stack, preDraw)
            observed = false
            geometry.clear()
            groups.remove(stack)
        }

        fun orderLast() {
            if (layers.isEmpty()) return
            if (observed) Gen.unpreDraw(stack, preDraw)
            Gen.preDraw(stack, preDraw)
            observed = true
        }

        private val preDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observed) runCatching { place() }.onFailure {
                    // A changed OEM symbol must not keep a stale mask on an island at rest.
                    layers.toList().forEach { it.mini.clearMorphStackClip(); it.restoreZ() }
                    Xp.w("MCMini: stack occlusion unavailable: $it")
                    if (observed) Gen.unpreDraw(stack, this)
                    observed = false
                }
                return true
            }
        }

        private fun place() {
            val active = layers.toList()
            val byRow = active.associateBy { it.row }
            val children = (0 until stack.childCount).map { stack.getChildAt(it) }
            geometry.keys.retainAll(children.toSet())
            val rows = ArrayList<StackOcclusion.Row<View>>()
            for ((index, view) in children.withIndex()) {
                if (view.visibility == View.GONE || view.width <= 0 || view.height <= 0) continue
                if (view !in byRow && !view.javaClass.name.contains("ExpandableNotificationRow") &&
                    !view.javaClass.name.contains("MediaHeader")) continue
                val native = geometry.getOrPut(view) { StackRowGeometry(view) }
                val layer = byRow[view]
                val shape = layer?.shape(native) ?: nativeShape(view, native)
                val visible = if (layer != null)
                    layer.mini.isShown && layer.mini.alpha * layer.mini.transitionAlpha > 0.01f
                else view.isShown && native.alpha() > 0.01f
                rows += StackOcclusion.Row(view, layer?.nativeZ(native) ?: native.z(), index, shape, visible)
            }
            val highest = rows.maxOfOrNull { it.z } ?: 0f
            val ranked = rows.map { row ->
                val layer = byRow[row.key] ?: return@map row
                val home = when (layer.stackedRole) {
                    ROLE_FRONT -> highest + 2f
                    ROLE_REAR -> highest + 1f
                    else -> null
                }
                val z = StackOcclusion.morphZ(row.z, home, layer.progress)
                // Only a temporary render override on participating rows; the OEM injector,
                // Folme state and ordinary-row Z remain their own. Restore on every exit.
                layer.renderedZ?.write(z - row.key.elevation)
                row.copy(z = z)
            }
            val activeSorted = ranked.filter { it.key in byRow }.sortedWith(
                compareBy<StackOcclusion.Row<View>> { it.z }.thenBy { it.index })
            for (layer in active) {
                val own = ranked.firstOrNull { it.key === layer.row }
                val frame = layer.frame
                if (own == null || frame == null) {
                    layer.mini.clearMorphStackClip()
                    continue
                }
                // The root overlay and the native content use exactly the same order/masks,
                // including while ordinary notifications are being driven by spring rows.
                layer.mini.translationZ = MORPH_Z + activeSorted.indexOf(own).coerceAtLeast(0)
                layer.mini.setMorphStackClip(frame, own.shape.box.h, StackOcclusion.covers(own, ranked))
            }
        }

        private fun nativeShape(view: View, native: StackRowGeometry): StackOcclusion.Shape {
            val parent = view.parent as? View ?: return StackOcclusion.Shape(Box(0f, 0f, 0f, 0f), 0f)
            parent.getLocationOnScreen(xy)
            val bounds = view.clipBounds
            val top = bounds?.top?.toFloat()?.coerceAtLeast(0f) ?: 0f
            val bottom = minOf(NotificationMorphClip.heightOf(view) ?: native.visibleBottom(),
                bounds?.bottom?.toFloat() ?: Float.MAX_VALUE)
            if (bottom <= top) return StackOcclusion.Shape(Box(0f, 0f, 0f, 0f), 0f)
            rect.set(0f, top, view.width.toFloat(), bottom)
            view.matrix.mapRect(rect)
            view.animationMatrix?.mapRect(rect)
            val sx = rect.width() / view.width
            val sy = rect.height() / (bottom - top)
            val box = Box(xy[0] - parent.scrollX + view.left + rect.left,
                xy[1] - parent.scrollY + view.top + rect.top, rect.width(), rect.height())
            return StackOcclusion.Shape(box, (NotificationMorphClip.radiusOf(view) ?: native.radius()) * minOf(sx, sy))
        }
    }

    companion object {
        const val ROLE_NONE = 0
        const val ROLE_FRONT = 1
        const val ROLE_REAR = 2

        private val groups = IdentityHashMap<ViewGroup, Group>()

        fun forRow(mini: MiniPlayerView, header: View): MorphStackLayer? {
            var row = header
            var parent = row.parent as? ViewGroup
            while (parent != null) {
                if (parent.javaClass.name.endsWith("NotificationStackScrollLayout")) {
                    val stack = parent
                    return MorphStackLayer(mini, row, groups.getOrPut(stack) { Group(stack) })
                }
                row = parent
                parent = row.parent as? ViewGroup
            }
            return null
        }

        /** Called only when a geometry writer is installed, not every animation frame. */
        fun orderLast(stack: View) { groups[stack]?.orderLast() }

        fun orderLastFor(header: View) {
            var parent = header.parent as? View
            while (parent != null) {
                if (parent.javaClass.name.endsWith("NotificationStackScrollLayout")) {
                    orderLast(parent)
                    return
                }
                parent = parent.parent as? View
            }
        }
    }
}
