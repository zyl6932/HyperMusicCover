package com.os4.musiccover.island

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp
import com.os4.musiccover.cover.CoverMorphMotion.Box
import java.lang.reflect.Method

/** The spread's ordinary rows, placed after the OEM has applied this frame's transforms. */
internal class SpringSpreadRows(
    private val stack: ViewGroup,
    items: List<SpreadItem>,
    private val island: Box?,
    private val since: Long,
    private val collapsed: Boolean,
    starts: Map<View, Start> = emptyMap(),
    private val opening: Boolean = true,
    private val initialLift: Float = 0f,
    preserved: List<View> = emptyList(),
) {
    data class Start(val box: Box?, val alpha: Float, val collapsed: Boolean)
    /** Render properties bypass PropertiesFrameLayout's logical values, so the OEM keeps its target. */
    private class Property(view: View, name: String) {
        private val get: Method = view.javaClass.getMethod("getSuper$name")
        private val set: Method = view.javaClass.getMethod("setSuper$name", Float::class.javaPrimitiveType)
        private val state = SpringRenderValue((get.invoke(view) as Number).toFloat())
        fun read(view: View): Float = state.read((get.invoke(view) as Number).toFloat())
        fun write(view: View, value: Float) {
            set.invoke(view, value)
            state.written(value)
        }
        fun restore(view: View) { set.invoke(view, read(view)) }
    }

    private class Row(val view: View, val pose: SpringSpreadState, val collapsed: Boolean) {
        val x = Property(view, "TranslationX")
        val y = Property(view, "TranslationY")
        val sx = Property(view, "ScaleX")
        val sy = Property(view, "ScaleY")
        val a = Property(view, "Alpha")
        val ta = Property(view, "TransitionAlpha")
        val alpha = view.transitionAlpha
        var from: Box? = null
        var startAlpha = 1f
        var overridden = false
        val blur = NotificationContentBlur(view)
        val geometry = StackRowGeometry(view)
        val clip = NotificationMorphClip(view, clipRow = true)
        val visibility = SpringRowVisibility(view)

        fun restore() {
            blur.clear()
            clip.close()
            // Each property independently: a missing symbol must not strand the other writes.
            for (property in listOf(x, y, sx, sy, a, ta)) runCatching { property.restore(view) }
            if (overridden) runCatching { Xp.callMethod(view, "onPropertiesUpdate") }
            visibility.close()
        }
    }

    private val items = items.toList()
    private val starts = starts.toMap()
    private val excluded = items.filter { it.morph != null || opening && it.wasOut }.mapNotNull { it.native } + preserved
    private var order = emptyList<View>()
    private val poses = HashMap<View, SpringSpreadState>()
    private val aliases = HashMap<View, View>()
    private val rows = ArrayList<Row>()
    private val failed = HashSet<View>()
    private val xy = IntArray(2)
    private var observing = false
    private var targetOpen = opening
    private var changedAt = since
    private var lift = initialLift

    init {
        collectRows()
    }

    private fun collectRows() {
        val children = (0 until stack.childCount).map { stack.getChildAt(it) }.filter { view ->
            view.javaClass.name.contains("ExpandableNotificationRow") || items.any { item ->
                item.native?.let { inside(it, view) } == true
            }
        }
        // INVISIBLE is a temporary stacking state, not absence. updateDimmingAndVisible
        // hides progress > 3; filtering by VISIBLE dropped precisely those later rows.
        val next = children.filter { it.visibility != View.GONE }
        if (next == order) return
        order = next
        val plan = SpringSpreadPlan.rows(children, { it.visibility == View.GONE }, excluded) { parent, child ->
            inside(child, parent)
        }
        for (item in items) item.native?.let { view ->
            val entry = plan.firstOrNull { inside(view, it.view) }
            if (entry != null) {
                aliases[view] = entry.view
                if (!entry.animate) poses.getOrPut(entry.view) { newPose(entry, hidden = true) }
            }
        }
        for (entry in plan) {
            val view = entry.view
            if (!entry.animate || view in failed || rows.any { it.view === view } || view.animationMatrix != null) continue
            runCatching {
                val folded = runCatching {
                    (Xp.getObjectField(Xp.callMethod(view, "getInjector"), "numStateAlpha") as Number).toFloat() < 0.01f
                }.getOrDefault(false)
                val stackingHidden = runCatching {
                    Xp.getObjectField(Xp.callMethod(view, "getInjector"), "shouldHideDueToStacking") == true
                }.getOrDefault(false)
                val hidden = starts[view]?.collapsed ?: (collapsed || folded || stackingHidden ||
                    items.any { it.native === view && it.seat == SEAT_HIDDEN })
                val row = Row(view, poses.getOrPut(view) { newPose(entry, hidden) }, hidden)
                row.from = starts[view]?.box ?: startBox(row)
                row.startAlpha = starts[view]?.alpha ?: if (hidden) 0f else 1f
                rows += row
            }.onFailure {
                failed += view
                Xp.w("MCMini: spring row unavailable: $it")
            }
        }
    }

    fun owns(view: View?): Boolean = rows.any { it.view === view }

    private fun newPose(entry: SpringSpreadPlan.Entry<View>, hidden: Boolean): SpringSpreadState =
        SpringSpreadState(if (opening) 0f else 1f, entry.response, entry.closingResponse,
            initialAlpha = if (opening && hidden) 0f else 1f,
            initialSharpness = if (opening && hidden) 0f else 1f).also { it.aim(targetOpen) }

    /** Keep the pre-pipeline positions when newly released rows become ready for their morphs. */
    fun startBoxes(): Map<View, Start> = rows.associate { row ->
        row.view to Start(row.from, row.startAlpha, row.collapsed)
    }

    fun pose(view: View?, now: Long): SpringSpreadState? = poses[aliases[view] ?: view]?.also {
        if (since != 0L) it.advance(now)
    }

    fun aim(open: Boolean, now: Long) {
        targetOpen = open
        changedAt = now
        for (pose in poses.values) {
            pose.advance(now, ready = pose.started)
            pose.aim(open)
        }
    }

    fun setLift(value: Float) { lift = value }

    fun start() {
        if (observing) return
        observing = true
        Gen.preDraw(stack, preDraw)
        MorphStackLayer.orderLast(stack)
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (!observing) return@OnPreDrawListener true
        collectRows()
        val now = SystemClock.uptimeMillis()
        for (row in rows.toList()) {
            if (!row.view.isAttachedToWindow || row.view.visibility == View.GONE) {
                rows.remove(row)
                poses.remove(row.view)
                row.restore()
                continue
            }
            runCatching { draw(row, now) }.onFailure {
                rows.remove(row)
                poses.remove(row.view)
                failed += row.view
                row.restore()
                Xp.w("MCMini: spring row released after drawing failed: $it")
            }
        }
        true
    }

    private fun startBox(row: Row): Box? {
        if (!row.collapsed || island == null) return box(row)
        val view = row.view
        return SpringSpreadPlan.foldedBox(island, view.width.toFloat(), height(view))
    }

    private fun draw(row: Row, now: Long) {
        val view = row.view
        val from = row.from ?: startBox(row)?.also { row.from = it } ?: return
        val native = box(row) ?: return
        // Layout may still hide the row by its native stacking depth. Reveal that case before
        // starting its clock, so it actually draws the spring rather than popping in afterward.
        val visible = if (since != 0L) row.visibility.apply() else view.visibility == View.VISIBLE
        if (since != 0L) row.pose.advance(now, ready = visible)
        val alpha = row.pose.opacity.value.coerceIn(0f, 1f)
        val p = row.pose.position.value
        val width = (from.w + (native.w - from.w) * p).coerceAtLeast(1f)
        val x = from.x + (native.x - from.x) * p
        val sourceY = from.y + if (row.collapsed) initialLift - lift else 0f
        val y = sourceY + (native.y - sourceY) * p
        val parent = view.parent as? View ?: return
        parent.getLocationOnScreen(xy)
        val originX = xy[0] - parent.scrollX + view.left.toFloat()
        val originY = xy[1] - parent.scrollY + view.top.toFloat()
        val scaleX = width / view.width
        val scaleY = row.sy.read(view) * width / native.w
        row.a.read(view)
        row.ta.read(view)
        row.overridden = true
        // Native render-node properties keep the notification's wallpaper glass visible.
        // Animation matrices lose that material on the ordinary row (MiniCardMorph #122).
        row.sx.write(view, scaleX)
        row.sy.write(view, scaleY)
        row.x.write(view, x - originX - view.pivotX * (1f - scaleX))
        row.y.write(view, y - originY - view.pivotY * (1f - scaleY))
        // These rows remain visible during the slower closing alpha spring and handoff tail.
        // Fit their glass to a capsule too, not just their rectangular layout bounds.
        if (row.collapsed && p < 1f) {
            row.clip.apply(view.width.toFloat(), StackOcclusion.morphVisibleHeight(row.geometry.height(),
                row.geometry.height(), row.geometry.visibleBottom(), 1f, p),
                SpringSpreadPlan.cornerRadius(view.width.toFloat(), row.geometry.height(), row.geometry.radius(), p))
        } else row.clip.close()
        // Write the final render products immediately. The logical setters only schedule an
        // update for another frame and could briefly expose a newly unfolded notification.
        val state = Xp.callMethod(view, "getViewState")
        val folme = (Xp.getObjectField(Xp.getObjectField(state, "mInjector"), "folmeAlpha") as Number).toFloat()
        val extTransition = (Xp.callMethod(view, "getExtTransitionAlpha") as Number).toFloat()
        row.a.write(view, view.alpha * folme)
        row.ta.write(view, row.alpha * extTransition * alpha)
        row.blur.apply(row.pose.blurPx / minOf(scaleX, scaleY).coerceAtLeast(0.05f))
    }

    fun close() {
        if (observing) Gen.unpreDraw(stack, preDraw)
        observing = false
        for (row in rows.toList()) row.restore()
        rows.clear()
    }

    /** Late-laid-out rows get their settling tail, with a bounded lifetime for arrivals. */
    fun settled(now: Long): Boolean = now - changedAt >= 1600L ||
        poses.values.all { !it.started || it.atRest() || (!targetOpen && it.position.atRest() && it.opacity.value < 0.02f) }

    private fun height(view: View): Float =
        runCatching { (Xp.callMethod(view, "getActualHeight") as Number).toFloat() }
            .getOrDefault(view.height.toFloat()).coerceIn(0f, view.height.coerceAtLeast(0).toFloat())

    private fun box(row: Row): Box? {
        val view = row.view
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val parent = view.parent as? View ?: return null
        parent.getLocationOnScreen(xy)
        val sx = row.sx.read(view)
        val sy = row.sy.read(view)
        if (sx <= 0f || sy <= 0f) return null
        val height = height(view)
        if (height <= 0f) return null
        return Box(xy[0] - parent.scrollX + view.left + row.x.read(view) + view.pivotX * (1f - sx),
            xy[1] - parent.scrollY + view.top + row.y.read(view) + view.pivotY * (1f - sy),
            view.width * sx, height * sy)
    }

    companion object {
        private fun inside(view: View, ancestor: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current === ancestor) return true
                current = current.parent as? View
            }
            return false
        }
    }
}
