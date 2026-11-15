package com.os4.musiccover.island

import com.os4.musiccover.cover.CoverMorphMotion.Box

/** Row membership and folded geometry must not depend on which three rows the OEM draws. */
internal object SpringSpreadPlan {
    data class Entry<T>(val view: T, val response: Float, val closingResponse: Float, val animate: Boolean)

    fun <T> rows(children: List<T>, gone: (T) -> Boolean, owned: List<T>,
                 contains: (T, T) -> Boolean): List<Entry<T>> {
        val present = children.filterNot(gone)
        return present.mapIndexed { index, view ->
            Entry(view, IslandSpringMotion.response(index, present.size),
                IslandSpringMotion.response(index, present.size, opening = false),
                owned.none { contains(view, it) })
        }
    }

    /** Fit the entire card into the actual capsule, keeping text and glass uniformly scaled. */
    fun foldedBox(island: Box, width: Float, height: Float): Box? {
        if (width <= 0f || height <= 0f || island.w <= 0f || island.h <= 0f) return null
        val scale = minOf(island.w / width, island.h / height, 1f)
        val w = width * scale
        val h = height * scale
        return Box(island.cx() - w / 2f, island.cy() - h / 2f, w, h)
    }

    /** A uniformly fitted row must also become a capsule, or its rectangle protrudes at the corners. */
    fun cornerRadius(width: Float, height: Float, nativeRadius: Float, progress: Float): Float {
        val capsule = minOf(width, height).coerceAtLeast(0f) / 2f
        val native = nativeRadius.coerceIn(0f, capsule)
        return capsule + (native - capsule) * progress.coerceIn(0f, 1f)
    }

    /** Keep the touched island's home reachable while the visible cards are moving away. */
    fun catchesOrigin(origin: Box, lift: Float, x: Float, y: Float): Boolean =
        x >= origin.x && x < origin.x + origin.w &&
            y >= origin.y && y < origin.y + origin.h + lift.coerceAtLeast(0f)

}
