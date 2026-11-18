package com.os4.musiccover.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.glass.GlassDialog
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
        GlassDialog(
            onDismissRequest = { onDismissRequest?.invoke() },
            show = show,
            backdrop = LocalPageBackdrop.current,
            modifier = modifier,
            title = title,
            summary = summary,
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
