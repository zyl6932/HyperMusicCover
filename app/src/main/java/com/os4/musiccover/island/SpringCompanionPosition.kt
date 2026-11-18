package com.os4.musiccover.island

internal object SpringCompanionPlan {
    fun keepsCard(visible: Boolean, ownedByIsland: Boolean, media: Boolean, focus: Boolean): Boolean =
        visible && !ownedByIsland && (media || focus)
}

/** A card kept outside the islands moves with the spread, retaining its content and size. */
internal class SpringCompanionPosition(private val foldedBottom: Float) {
    fun top(nativeListTop: Float, height: Float, progress: Float): Float {
        val home = foldedBottom - height
        return home + (nativeListTop - home) * progress.coerceIn(-0.025f, 1.025f)
    }

    fun reachedHome(nativeTop: Float, height: Float): Boolean =
        kotlin.math.abs(nativeTop - (foldedBottom - height)) <= 1f
}
