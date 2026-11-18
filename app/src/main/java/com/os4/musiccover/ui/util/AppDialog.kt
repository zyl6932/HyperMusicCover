package com.os4.musiccover.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassDialogDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The dialog every page opens.
 *
 * OS4 draws it as a glass panel inside the page's Scaffold host - the same Compose-hosted
 * route the glass dropdowns take, and the reason the OS4 pages keep the default popup host -
 * while OS3 keeps the dialog window it has always been. Both provide `LocalDismissState`, so
 * the content (buttons included) is untouched by the choice.
 *
 * The glass panel samples [LocalPageBackdrop]: the page, never the dialog's own scrim or
 * panel, both of which are drawn above that recording.
 */
@Composable
fun AppDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (LocalOs4Ui.current) {
        // Exactly the demo's "Glass Component" dialog: the dialog material the library ships
        // and its Middle bloom stroke. GlassDialog's own default stroke is Small, one step
        // quieter than the demo, which is why it is named here instead of left to the default.
        GlassDialog(
            onDismissRequest = { onDismissRequest?.invoke() },
            show = show,
            backdrop = LocalPageBackdrop.current,
            modifier = modifier,
            title = title,
            summary = summary,
            material = GlassDialogDefaults.material(),
            stroke = GlassDemoStroke(),
            content = content,
        )
    } else {
        WindowDialog(
            show = show,
            modifier = modifier,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
            content = content,
        )
    }
}

/**
 * The Middle bloom stroke for the theme, selected the way the glass defaults select theirs
 * (background luminance), so the dialog keeps matching the demo in both colour modes.
 */
@Composable
private fun GlassDemoStroke() = GlassEdgeStrokes.forTheme(
    isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f,
    light = GlassEdgeStrokes.MiddleLight,
    dark = GlassEdgeStrokes.MiddleDark,
)

/** The action roles every dialog button has; mirrors [GlassDialogButtonRole] for OS4. */
enum class AppDialogButtonRole { Normal, Primary, Destructive }

/**
 * A dialog action.
 *
 * The demo's dialog actions are `GlassDialogButton`s - a translucent tonal fill for normal and
 * destructive actions, the theme's primary fill for the confirming one - so OS4 draws the same
 * component. OS3 keeps the `TextButton` it always had, with the same three roles mapped onto
 * miuix's own button colours.
 */
@Composable
fun AppDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: AppDialogButtonRole = AppDialogButtonRole.Normal,
) {
    if (LocalOs4Ui.current) {
        GlassDialogButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            role = when (role) {
                AppDialogButtonRole.Normal -> GlassDialogButtonRole.Normal
                AppDialogButtonRole.Primary -> GlassDialogButtonRole.Primary
                AppDialogButtonRole.Destructive -> GlassDialogButtonRole.Destructive
            },
        ) {
            Text(text = text, style = MiuixTheme.textStyles.button)
        }
    } else {
        TextButton(
            modifier = modifier,
            text = text,
            onClick = onClick,
            enabled = enabled,
            colors = when (role) {
                AppDialogButtonRole.Normal -> ButtonDefaults.textButtonColors()
                AppDialogButtonRole.Primary -> ButtonDefaults.textButtonColorsPrimary()
                AppDialogButtonRole.Destructive ->
                    ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
            },
        )
    }
}
