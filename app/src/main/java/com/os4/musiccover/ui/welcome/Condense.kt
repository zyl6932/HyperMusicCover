/*
 * Taken from Oolong (https://github.com/HuanCheng65/Oolong), GPL-3.0,
 * app/src/main/java/oolong/client/ui/widgets/compose/Condense.kt.
 *
 * Changes in HyperMusicCover: the reduce-motion check reads the platform's animator scale
 * instead of Oolong's own composition local.
 */
package com.os4.musiccover.ui.welcome

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * Comes into focus: from blurred, a little lower and smaller, and transparent, to sharp as
 * [progress] goes from 0 to 1. Blur is drawn from Android 12; before, it only fades and rises.
 * The layer is drawn with room around the content, which the blur spreads into instead of being
 * cut off square at the content's edges; the room takes no space in the layout.
 */
@Composable
fun Modifier.condense(
    progress: () -> Float,
    blur: Dp,
    rise: Dp = 0.dp,
    scale: Float = 1f,
    fadeFaster: Float = 1f,
): Modifier {
    // With the system's animations switched off, motion would be the only thing left, so only
    // the fade runs.
    val animatorsOn = remember { ValueAnimator.areAnimatorsEnabled() }
    if (!animatorsOn) {
        return graphicsLayer { alpha = (progress() * fadeFaster).coerceIn(0f, 1f) }
    }
    return condensing(progress, blur, rise, scale, fadeFaster)
}

private fun Modifier.condensing(
    progress: () -> Float,
    blur: Dp,
    rise: Dp,
    scale: Float,
    fadeFaster: Float,
): Modifier = this
    .layout { measurable, constraints ->
        val room = (blur * 2).roundToPx()
        val placeable = measurable.measure(constraints.offset(room * 2, room * 2))
        layout(
            (placeable.width - room * 2).coerceAtLeast(0),
            (placeable.height - room * 2).coerceAtLeast(0),
        ) {
            placeable.place(-room, -room)
        }
    }
    .graphicsLayer {
        val p = progress()
        alpha = (p * fadeFaster).coerceIn(0f, 1f)
        translationY = (1f - p) * rise.toPx()
        val s = lerp(scale, 1f, p)
        scaleX = s
        scaleY = s
        // The radius is in pixels of the layer, not dp, which is what BlurEffect expects.
        val radius = (1f - p.coerceAtMost(1f)) * blur.toPx()
        renderEffect = if (radius > .5f) BlurEffect(radius, radius, TileMode.Decal) else null
    }
    .padding(blur * 2)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
