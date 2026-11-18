package com.os4.musiccover.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.blur.LayerBackdrop

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
val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
