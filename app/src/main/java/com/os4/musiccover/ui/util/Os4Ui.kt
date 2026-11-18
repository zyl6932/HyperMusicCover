package com.os4.musiccover.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Whether the glass (OS4) interface is the one being drawn.
 *
 * Set once by [com.os4.musiccover.ui.theme.AppTheme] from the user's setting, already folded
 * together with the device's ability to render it - so a page only has to ask this, never
 * `isRenderEffectSupported()` as well, and a device without runtime shaders never sees half of
 * one interface and half of the other.
 */
val LocalOs4Ui = staticCompositionLocalOf { false }

/**
 * The backdrop glass surfaces on the current page sample.
 *
 * A page records its own content into it ([PageScaffold]), or inherits the one the root screen
 * recorded across its pages (MainActivity). Anything hosted outside that recording - dropdowns,
 * dialogs - reads it from here; sampling the wrong layer would either show the popup itself or
 * come back empty.
 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Whether OS4's cards draw their bloom frame: the edge stroke and the shadow.
 *
 * The miuix demo draws cards without either; this app adds them, and the theme page lets the
 * user turn them off again. It only decides the frame - the card's fill and the rest of the
 * glass interface do not change.
 */
val LocalCardGlass = staticCompositionLocalOf { true }

/**
 * A backdrop that paints nothing but the page's surface colour.
 *
 * For glass that cannot sample a real recording without sampling itself - a tab row inside the
 * recorded page body - and for glass whose call site sits outside any page recording at all:
 * a dialog opened beside the page frame. It still gives the glass pipeline a body to colour and
 * a rim to light, instead of falling back to an opaque panel.
 */
private class SurfaceBackdrop(private val color: Color) : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Float,
    ) {
        drawRect(color)
    }
}

@Composable
fun rememberSurfaceBackdrop(): Backdrop {
    val surface = MiuixTheme.colorScheme.surface
    return remember(surface) { SurfaceBackdrop(surface) }
}
