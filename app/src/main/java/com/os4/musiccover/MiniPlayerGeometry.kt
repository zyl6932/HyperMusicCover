package com.os4.musiccover

import kotlin.math.min

/** Keeps the pill inside the shortcut host even if an OEM layout moves a shortcut off centre. */
internal object MiniPlayerGeometry {
    fun heightDp(radiusDp: Float): Float = (radiusDp.coerceIn(10f, 60f) * 2f).coerceAtLeast(48f)

    fun widthPx(requestedPx: Int, hostWidth: Int, centerX: Float, marginPx: Int): Int {
        if (hostWidth <= 0) return 0
        val margin = marginPx.coerceIn(0, (hostWidth - 1) / 2)
        val center = centerX.coerceIn(margin.toFloat(), (hostWidth - margin).toFloat())
        val symmetricRoom = (2f * min(center, hostWidth - center) - margin).toInt()
        return min(requestedPx, min(hostWidth - 2 * margin, symmetricRoom)).coerceAtLeast(1)
    }
}
