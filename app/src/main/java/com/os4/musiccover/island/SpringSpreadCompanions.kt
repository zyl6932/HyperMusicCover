package com.os4.musiccover.island

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.os4.musiccover.Gen
import com.os4.musiccover.Xp

/** Position-only followers for native cards which remain outside the row of islands. */
internal class SpringSpreadCompanions(
    private val stack: ViewGroup,
    candidates: List<View>,
) {
    private class Row(val view: View, parentY: Float) {
        val y = RowRenderProperty(view, "TranslationY")
        val geometry = StackRowGeometry(view)
        val state = Xp.callMethod(view, "getViewState")
        val scale = view.javaClass.getMethod("getSuperScaleY")
        fun scaleY() = (scale.invoke(view) as Number).toFloat()
        fun height() = geometry.height() * scaleY()
        fun pivot() = view.top + view.pivotY * (1f - scaleY())
        val position = SpringCompanionPosition(parentY + pivot() + y.read() + height())
        var held = false
        var drawnTop = Float.NaN

        fun restore() {
            if (!held) return
            held = false
            runCatching { y.restore() }
        }
    }

    private val xy = IntArray(2)
    private fun parentY(): Float {
        stack.getLocationOnScreen(xy)
        return xy[1] - stack.scrollY.toFloat()
    }

    private val rows = candidates.distinct().mapNotNull { view ->
        runCatching { Row(view, parentY()) }.onFailure { Xp.w("MCMini: spread companion unavailable: $it") }.getOrNull()
    }.toMutableList()
    private var progress = 0f
    private var observing = false
    private var handingBack = false
    private var waitingForList = true
    val views: List<View> get() = rows.map { it.view }
    fun drawnTop(view: View): Float? = rows.firstOrNull { it.view === view && it.held }
        ?.drawnTop?.takeIf { it.isFinite() }

    /** Called before the pipeline/scroll changes, retaining the current folded placement. */
    fun start() { observe() }

    /** A new opening can take over a pending fold handoff without restoring the old OEM Y. */
    fun restart(candidates: List<View>) {
        for (row in rows.toList()) if (row.view !in candidates) {
            row.restore()
            rows.remove(row)
        }
        for (view in candidates.distinct()) if (rows.none { it.view === view }) {
            runCatching { Row(view, parentY()) }.onSuccess { rows += it }
                .onFailure { Xp.w("MCMini: spread companion unavailable: $it") }
        }
        progress = 0f
        waitingForList = true
        handingBack = false
        observe()
    }

    fun pose(progress: Float) {
        this.progress = progress
        waitingForList = false
        handingBack = false
        observe()
    }

    /** Keep this small map of folded endpoints while the open list itself scrolls normally. */
    fun pause() {
        if (observing) Gen.unpreDraw(stack, preDraw)
        observing = false
        for (row in rows) row.restore()
    }

    /** Bounded by SpreadMotion's fold handoff timer; release each row once the OEM catches up. */
    fun handBack() {
        progress = 0f
        handingBack = true
        waitingForList = false
        observe()
    }

    fun close() { pause(); rows.clear() }

    private fun observe() {
        if (observing || rows.isEmpty()) return
        observing = true
        Gen.preDraw(stack, preDraw)
        MorphStackLayer.orderLast(stack)
    }

    private val preDraw = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val parentY = parentY()
            for (row in rows.toList()) {
                if (!row.view.isAttachedToWindow || row.view.parent !== stack || row.view.visibility == View.GONE) {
                    row.restore()
                    rows.remove(row)
                    continue
                }
                runCatching {
                    val native = row.y.read()
                    val pivot = row.pivot()
                    val height = row.height()
                    if (handingBack && row.position.reachedHome(parentY + pivot + native, height)) {
                        row.restore()
                        rows.remove(row)
                    } else {
                        // ViewState holds the destination, before Folme's extra rendered offset.
                        // Following that offset would add a second easing after our spring.
                        val target = if (waitingForList) native else runCatching {
                            (Xp.getObjectField(row.state, "mYTranslation") as Number).toFloat()
                        }.getOrDefault(native)
                        val top = row.position.top(parentY + pivot + target, height, progress)
                        row.held = true
                        row.drawnTop = top - parentY
                        row.y.write(top - parentY - pivot)
                    }
                }.onFailure {
                    row.restore()
                    rows.remove(row)
                    Xp.w("MCMini: spread companion released: $it")
                }
            }
            if (rows.isEmpty() && observing) {
                Gen.unpreDraw(stack, this)
                observing = false
            }
            return true
        }
    }
}
