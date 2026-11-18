package com.os4.musiccover.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassShadows

/**
 * The card every settings page is built from.
 *
 * OS4 draws the same card as a glass panel: the miuix [Card] is still the thing that clips the
 * content, holds the content colour and pads it - its own fill is made translucent, and the
 * panel (edge light, shadow, fallback fill) is drawn behind it by wrapping it. OS3 is
 * untouched: the same Card, with the same colours.
 *
 * [LocalCardGlass] can turn the panel off - the miuix demo's cards have no rim or shadow, and
 * the theme page exposes that as a switch. Off is the plain [Card] with its own fill.
 *
 * The panel deliberately samples no backdrop. The page backdrop records the cards themselves,
 * so a card sampling it would blur a picture of itself and ring against the previous frame.
 * What reads as glass here is instead the edge stroke, the shadow, and the translucent fill,
 * through which the page's own background shows.
 *
 * Only the plain card is wrapped. The clickable overload (HomePage's status card) keeps its
 * own colour and press feedback and is not routed through here.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalOs4Ui.current || !LocalCardGlass.current) {
        Card(
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = insideMargin,
            colors = colors,
            content = content,
        )
        return
    }
    val shape = remember(cornerRadius) { GlassShape(cornerRadius) }
    val isDark = isInDarkTheme()
    val fallbackShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        modifier = modifier.glassPanel(
            backdrop = null,
            shape = shape,
            style = GlassDefaults.style(isDark),
            stroke = GlassEdgeStrokes.forTheme(isDark),
            shadow = GlassShadows.Regular,
            // The card colour itself, not a translucent copy of it: OS3's cards are opaque,
            // and a see-through fill darkened them over the About page's gradient.
            fallback = Modifier.background(colors.color, fallbackShape),
        ),
    ) {
        Card(
            cornerRadius = cornerRadius,
            insideMargin = insideMargin,
            colors = colors.copy(color = Color.Transparent),
            content = content,
        )
    }
}
