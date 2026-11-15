package com.os4.musiccover.island

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** ColorOS CapsuleScrollerVertical / ExtStateAnimSinkingParams, traced with dexsql. */
internal object IslandSpringMotion {
    const val DRAG_LIMIT_DP = 40f
    const val DRAG_RESPONSE = 0.1f
    const val BOUNCE = 0.16f
    const val CONTENT_BLUR_PX = 70f

    /** LevelParam uses the midpoint for the second of two rows, otherwise the full range. */
    fun response(index: Int, count: Int, opening: Boolean = true): Float {
        val rank = if (opening || index < 0 || count <= 1) index else count - 1 - index
        val share = when {
            count <= 1 || rank < 0 -> 0f
            count == 2 -> rank.coerceIn(0, 1) * 0.5f
            else -> rank.coerceIn(0, count - 1).toFloat() / (count - 1)
        }
        return 0.35f + 0.1f * share
    }

    /** Unit step, with COUISpringForce's dampingRatio = 1 - bounce. */
    fun progress(seconds: Float, response: Float, bounce: Float = BOUNCE): Float {
        if (seconds <= 0f) return 0f
        val w = 2.0 * Math.PI / response
        val damping = 1.0 - bounce
        val wd = w * sqrt(1.0 - damping * damping)
        val t = seconds.toDouble()
        return (1.0 - exp(-damping * w * t) *
            (cos(wd * t) + damping * w / wd * sin(wd * t))).toFloat()
    }
}

/** The first threshold is latched for handoff; SpringSpreadSwipe owns subsequent reversals. */
internal class IslandSpringPull(initialDp: Float = 0f) {
    var value = initialDp
        private set
    private var velocity = 0f
    private var target = initialDp
    var committed = false
        private set
    var ended = false
        private set

    fun move(pulledDp: Float) {
        if (!ended && !committed) target = pulledDp.coerceAtLeast(0f)
    }

    fun step(seconds: Float) {
        if (seconds <= 0f || committed) return
        // The drag spring is critically damped (bounce 0). The general underdamped solver
        // divides by zero here, so use the critical solution explicitly.
        val w = 2.0 * Math.PI / IslandSpringMotion.DRAG_RESPONSE
        val x = (value - target).toDouble()
        val b = velocity + w * x
        val decay = exp(-w * seconds)
        value = (target + (x + b * seconds) * decay).toFloat()
        velocity = ((velocity - w * b * seconds) * decay).toFloat()
        if (!ended && value >= IslandSpringMotion.DRAG_LIMIT_DP) {
            value = IslandSpringMotion.DRAG_LIMIT_DP
            committed = true
        }
    }

    fun release(upVelocityPx: Float, density: Float, cancelled: Boolean) {
        if (committed || ended) return
        ended = true
        // onVerticalUp predicts 30ms ahead, requires >1000px/s and a 50px projection.
        // This APK uses raw pixels for this fling check, unlike its 40dp held threshold.
        committed = !cancelled && kotlin.math.abs(upVelocityPx) > 1000f &&
            value * density + upVelocityPx * 0.03f >= 50f
        if (!committed) target = 0f
    }

    fun atRest(): Boolean = kotlin.math.abs(value - target) < 0.01f &&
        kotlin.math.abs(velocity) < 0.1f
}

/** Our last render write cannot become the next frame's OEM destination. */
internal class SpringRenderValue(private var native: Float) {
    private var lastWrite = Float.NaN
    fun read(current: Float): Float {
        if (current != lastWrite) native = current
        return native
    }
    fun written(value: Float) { lastWrite = value }
}
