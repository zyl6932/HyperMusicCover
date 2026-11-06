package com.os4.musiccover.ui.welcome

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * The shader is drawn on a view this fraction of the screen and then scaled back up. Every pixel
 * the shader touches costs the full noise + ring work, and the light is a slow, soft field - at a
 * fifth of the screen the upscale is invisible and the per-frame cost is a twenty-fifth of full
 * screen. This is the same trick HyperCeiler's RenderViewLayout uses.
 */
private const val RENDER_SCALE = 0.2f

/** How much of the glow the white scrim takes out; see where it is applied. */
private const val SCRIM_ALPHA = 0.72f

/**
 * The light behind the welcome page; [circleYOffset] puts the opening ring on the mark.
 *
 * It is a real View rather than a Compose layer because a shader effect can only be set on
 * `android.graphics.RenderEffect` through a view's render node - Compose's own `graphicsLayer`
 * takes its own effect types, which a `RuntimeShader` is not one of.
 */
@Composable
internal fun GlowBackground(
    modifier: Modifier = Modifier,
    circleYOffset: Float = 0f,
    scrim: Color = Color.White.copy(alpha = SCRIM_ALPHA),
    colors: List<Color> = GlowPainter.DefaultColors,
    /** Only the opening ring; the page's own effect background supplies everything else. */
    ringOnly: Boolean = false,
) {
    val context = LocalContext.current
    val painter = remember(colors, ringOnly) { GlowPainter(context, colors, ringOnly) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val smallWidth = (constraints.maxWidth * RENDER_SCALE).roundToInt().coerceAtLeast(1)
        val smallHeight = (constraints.maxHeight * RENDER_SCALE).roundToInt().coerceAtLeast(1)

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { GlowView(it, painter) },
                update = { it.circleYOffset = circleYOffset },
                modifier = Modifier
                    // Outermost: the small view below, blown back up to the screen.
                    .scale(1f / RENDER_SCALE)
                    .size(
                        with(density) { smallWidth.toDp() },
                        with(density) { smallHeight.toDp() },
                    ),
            )
            // HyperCeiler draws its content layout with a MIUI background blur over the shader,
            // which is what turns the dark glow light. There is no equivalent outside MIUI, so
            // the same result is approximated with a white scrim.
            Box(
                Modifier
                    .matchParentSize()
                    .background(scrim)
            )
        }
    }
}

/**
 * Runs the shader on the view's own render node. The clock is the view's, not Compose's: the
 * light is drawn every frame regardless of what the composition is doing, and it stops when the
 * view leaves the window.
 */
private class GlowView(context: Context, private val painter: GlowPainter) : View(context) {

    var circleYOffset: Float = 0f
        set(value) {
            field = value
            painter.setCircleYOffset(value)
        }

    private var lastNanos = 0L
    private var time = 0f

    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            if (lastNanos != 0L) time += (now - lastNanos) / 1e9f
            lastNanos = now
            // A zero-sized layer makes the shader divide by zero - the first frames arrive
            // before this view has been laid out.
            if (width > 0 && height > 0) {
                painter.setAnimTime(time)
                painter.setResolution(width, height)
            }
            // A new effect each frame: setting one is what redraws the node, and the uniforms
            // changing alone would not.
            setRenderEffect(painter.createEffect())
            postOnAnimation(this)
        }
    }

    init {
        // The shader ignores its input, but an empty view is one the renderer may skip.
        setBackgroundColor(BLACK)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNanos = 0L
        postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
    }
}

/** The colour HyperCeiler puts behind its glow view. */
private const val BLACK = -16777216
