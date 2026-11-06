/*
 * Taken from HyperCeiler (https://github.com/ReChronoRain/HyperCeiler), AGPL-3.0,
 * library/provision/src/main/java/com/sevtinge/hyperceiler/provision/renderengine/GlowPainter.java.
 *
 * Values are the original ones.
 */
package com.os4.musiccover.ui.welcome

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Color
import com.os4.musiccover.R

/**
 * The moving light behind the welcome page, as a shader effect over whatever view it is set on.
 *
 * [createEffect] is called every frame: the layer's effect is what makes the render node redraw,
 * so handing back the same [RenderEffect] would freeze the shader even though the uniforms on it
 * keep changing.
 */
internal class GlowPainter(
    context: Context,
    colors: List<Color> = DefaultColors,
    /** Keep the opening ring, drop the light field: the page is drawn on another shader. */
    ringOnly: Boolean = false,
) {

    private val shader = RuntimeShader(
        context.resources.openRawResource(R.raw.glow).bufferedReader().use { it.readText() }
    )

    init {
        shader.setFloatUniform("uScale2", 0.82f)
        shader.setFloatUniform("uSpeed2", 0.49f)
        shader.setFloatUniform("uColorInMin", 0.3f)
        shader.setFloatUniform("uColorInMax", 1.0f)
        shader.setFloatUniform("uColorOutMin", 0.3f)
        shader.setFloatUniform("uColorOutMax", 0.86f)
        shader.setFloatUniform("uColorMidPoint", 0.47f)
        shader.setFloatUniform("uUseOklab", 1.0f)
        shader.setFloatUniform("uColorBlack", colors[0].rgb())
        shader.setFloatUniform("uColorMid", colors[1].rgb())
        shader.setFloatUniform("uColorWhite", colors[2].rgb())
        shader.setFloatUniform("uScale", 1.3f)
        shader.setFloatUniform("uSpeed", 0.4f)
        shader.setFloatUniform("uBrightnessInMin", 0.25f)
        shader.setFloatUniform("uBrightnessInMax", 1.0f)
        shader.setFloatUniform("uBrightnessOutMin", 0.25f)
        shader.setFloatUniform("uBrightnessOutMax", 1.0f)

        shader.setFloatUniform("uRingOnly", if (ringOnly) 1.0f else 0.0f)
        shader.setFloatUniform("uRingAlpha", 0.55f)
        shader.setFloatUniform("uShowCircle", 1.0f)
        shader.setFloatUniform("uCircleThickness", 0.4f)
        shader.setFloatUniform("uCircleFinalRadius", 1.0f)
        shader.setFloatUniform("uCircleYOffset", 0.1f)
        shader.setFloatUniform("uCircleSpeed", 0.9f)
        shader.setFloatUniform("uCircleColorFreq", 1.0f)
        shader.setFloatUniform("uCircleColorSpeed", 0.0f)
        shader.setFloatUniform("uCircleEasing", 1.4f)
        shader.setFloatUniform("uCircleAnimationOffset", 0.0f)
        shader.setFloatUniform("uMaskDelay", 0.3f)
        shader.setFloatUniform("uMaskThickness", 0.3f)
        shader.setFloatUniform("uCircleScreenBlend", 1.0f)
        shader.setFloatUniform("uCircleAddBlend", 0.04f)
        shader.setFloatUniform("uCircleColorOffset", 0.25f)
        shader.setFloatUniform("uCircleUVDistort", 0.0f)
        shader.setFloatUniform("uColorToDistortWidthRatio", 0.6f)
        shader.setFloatUniform("uDistortStartTime", 0.2f)
        shader.setFloatUniform("uDistortEndTime", 0.3f)
        shader.setFloatUniform("uDistortStart", 0.0f)
        shader.setFloatUniform("uDistortEnd", 1.0f)
        shader.setFloatUniform("uStripeFrequency", 0.0f)
        shader.setFloatUniform("uStripeStrengthX", 0.0f)
        shader.setFloatUniform("uStripeStrengthY", 0.0f)
        shader.setFloatUniform("uStripeUVDistort", 0.0f)
    }

    /** Seconds since the page appeared. */
    fun setAnimTime(value: Float) = shader.setFloatUniform("uTime", value)

    /**
     * The size of the layer the shader is drawn on, in pixels. The welcome page renders it at a
     * fraction of the screen and scales it up, so this is that small size, not the screen's.
     */
    fun setResolution(width: Int, height: Int) =
        shader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())

    /** The ring's centre, as a fraction of the height above the middle. */
    fun setCircleYOffset(value: Float) = shader.setFloatUniform("uCircleYOffset", value)

    /** Whether the opening ring runs; after it, the shader stops calculating it. */
    fun needAdmission(need: Boolean) = shader.setFloatUniform("uShowCircle", if (need) 1.0f else 0.0f)

    fun createEffect(): RenderEffect = RenderEffect.createShaderEffect(shader)

    companion object {
        /** The colours HyperCeiler's own page draws its light with. */
        val DefaultColors = listOf(
            Color(0.961f, 0.157f, 0.157f),
            Color(0.604f, 0.659f, 0.961f),
            Color(0.302f, 0.29f, 0.843f),
        )
    }
}

/** The shader takes its colours as three plain floats, not a packed int. */
private fun Color.rgb() = floatArrayOf(red, green, blue)
