package com.os4.musiccover.island

import com.os4.musiccover.cover.CoverMorphMotion.Box

/** Native row order in the list, with temporary island-seat ordering only near home. */
internal object StackOcclusion {
    data class Shape(val box: Box, val radius: Float)
    data class Row<T>(val key: T, val z: Float, val index: Int, val shape: Shape, val visible: Boolean)

    fun <T> above(other: Row<T>, row: Row<T>): Boolean = other.z > row.z ||
        other.z == row.z && other.index > row.index

    fun overlaps(a: Box, b: Box): Boolean = a.w > 0f && a.h > 0f && b.w > 0f && b.h > 0f &&
        a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    fun <T> covers(row: Row<T>, all: List<Row<T>>): List<Shape> = all.asSequence()
        .filter { it.key != row.key && it.visible && above(it, row) && overlaps(row.shape.box, it.shape.box) }
        .map { it.shape }.toList()

    /** ExpandableViewInjector.stackingOutlineProvider: the OEM's two clips are alternatives. */
    fun visibleBottom(actual: Float, bottom: Float, stackingBottom: Float, minimum: Float): Float =
        maxOf(minimum, (actual - maxOf(bottom, stackingBottom)).coerceAtLeast(0f))
            .coerceIn(0f, actual.coerceAtLeast(0f))

    /** Native order in the list, the current front/rear seats as the shapes converge home. */
    fun morphZ(nativeZ: Float, homeZ: Float?, progress: Float): Float =
        if (homeZ == null) nativeZ else homeZ + (nativeZ - homeZ) * MiniCardMorph.smooth(0.2f, 0.75f, progress)

    /** Retain full geometry for the flight, while its drawn outline respects the native clip. */
    fun morphVisibleHeight(frameHeight: Float, rowHeight: Float, nativeBottom: Float,
                           scale: Float, progress: Float): Float =
        (frameHeight - (rowHeight - nativeBottom).coerceAtLeast(0f) * scale * progress.coerceIn(0f, 1f))
            .coerceIn(0f, frameHeight.coerceAtLeast(0f))

    /** The full container in row coordinates; native pixel bounds are intersected separately. */
    fun morphClipHeight(frameHeight: Float, rowHeight: Float, nativeBottom: Float,
                        scale: Float, progress: Float): Float =
        if (scale <= 0f) 0f else morphVisibleHeight(frameHeight, rowHeight, nativeBottom, scale, progress) / scale
}
