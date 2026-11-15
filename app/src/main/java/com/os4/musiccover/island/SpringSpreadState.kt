package com.os4.musiccover.island

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** A live spring, so changing the destination does not restart position or velocity. */
internal class SpreadSpring(initial: Float, var response: Float, private val bounce: Float) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial

    fun step(dt: Float) {
        if (dt <= 0f || atRest()) return
        val w = 2.0 * Math.PI / response.coerceAtLeast(0.01f)
        val damping = 1.0 - bounce
        val x = (value - target).toDouble()
        val decay = exp(-damping * w * dt)
        if (bounce == 0f) {
            val b = velocity + w * x
            value = (target + (x + b * dt) * decay).toFloat()
            velocity = ((velocity - w * b * dt) * decay).toFloat()
        } else {
            val wd = w * sqrt(1.0 - damping * damping)
            val b = (velocity + damping * w * x) / wd
            val phase = wd * dt
            val wave = x * cos(phase) + b * sin(phase)
            value = (target + decay * wave).toFloat()
            velocity = (decay * (-damping * w * wave + wd * (-x * sin(phase) + b * cos(phase)))).toFloat()
        }
        if (atRest()) snap(target)
    }

    fun atRest(): Boolean = abs(value - target) < 0.001f && abs(velocity) < 0.012f
    fun snap(value: Float) { this.value = value; target = value; velocity = 0f }
}

/** Independent ColorOS geometry, opacity and focus springs, all reversible in place. */
internal class SpringSpreadState(
    initial: Float,
    private val openingResponse: Float = 0.35f,
    private val closingResponse: Float = 0.35f,
    initialAlpha: Float = initial,
    initialSharpness: Float = initial,
) {
    val position = SpreadSpring(initial, openingResponse, IslandSpringMotion.BOUNCE)
    val opacity = SpreadSpring(initialAlpha, 0.3f, 0.11f)
    val sharpness = SpreadSpring(initialSharpness, 0.3f, 0.11f)
    private var lastAt: Long? = null
    val started: Boolean get() = lastAt != null
    val blurPx: Float get() = IslandSpringMotion.CONTENT_BLUR_PX * (1f - sharpness.value.coerceIn(0f, 1f))

    fun aim(open: Boolean) {
        position.target = if (open) 1f else 0f
        position.response = if (open) openingResponse else closingResponse
        opacity.target = position.target
        opacity.response = if (open) 0.3f else 0.9f
        sharpness.target = position.target
        sharpness.response = if (open) 0.3f else 0.4f
    }

    fun advance(now: Long, ready: Boolean = true) {
        if (!started && !ready) return
        val last = lastAt
        lastAt = now
        if (last == null) return
        val dt = ((now - last) / 1000f).coerceIn(0f, 0.05f)
        position.step(dt)
        opacity.step(dt)
        sharpness.step(dt)
    }

    fun atRest(): Boolean = position.atRest() && opacity.atRest() && sharpness.atRest()
}

/** Content crosses during the flight, independent of geometry settling or glass handoff. */
internal object SpringMorphContent {
    fun islandAlpha(sharpness: Float): Float = 1f - MiniCardMorph.smooth(0.15f, 0.7f, sharpness)
    fun islandBlur(sharpness: Float): Float = IslandSpringMotion.CONTENT_BLUR_PX * sharpness.coerceIn(0f, 1f)
    fun rowBlur(sharpness: Float, scale: Float): Float =
        IslandSpringMotion.CONTENT_BLUR_PX * (1f - sharpness.coerceIn(0f, 1f)) / scale.coerceAtLeast(0.05f)
}

/** ExtStateTouchHandlerEx: accumulate one direction, trigger again after 100px of reversal. */
internal class SpringSpreadSwipe(startY: Float, open: Boolean) {
    private var lastY = startY
    private var distance = 0f
    var targetOpen = open
        private set

    fun move(y: Float): Boolean? {
        val delta = y - lastY
        lastY = y
        if (delta == 0f) return null
        distance = if (distance * delta <= 0f) delta else distance + delta
        if (abs(distance) < 100f) return null
        val open = distance < 0f
        distance = 0f
        return change(open)
    }

    fun release(velocityY: Float, cancelled: Boolean): Boolean? =
        if (cancelled || abs(velocityY) <= 1000f) null else change(velocityY < 0f)

    private fun change(open: Boolean): Boolean? {
        if (targetOpen == open) return null
        targetOpen = open
        return open
    }
}
