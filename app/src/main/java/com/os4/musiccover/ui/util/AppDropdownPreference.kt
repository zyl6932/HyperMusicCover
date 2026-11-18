package com.os4.musiccover.ui.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * The one dropdown row every page uses.
 *
 * miuix ships two of them, and which one is right is not a per-page decision: OS4 draws a
 * glass popup inside the page (the pattern InstallerX-Revived introduced for its settings),
 * OS3 a window. Every option this app offers is plain text - no icons, submenus or disabled
 * rows - which is exactly the case the glass row can carry, so the choice is only the
 * interface style, and the call sites do not have to know either one exists.
 *
 * The glass popup samples [LocalPageBackdrop], the page recording the hosting page publishes;
 * the popup itself is rendered above that recording, so it never samples its own frame.
 */
@Composable
fun AppDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    if (LocalOs4Ui.current) {
        GlassDropdownPreference(
            onSelectedIndexChange = { onSelectedIndexChange?.invoke(it) },
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            backdrop = LocalPageBackdrop.current,
            modifier = modifier,
            enabled = enabled,
            showValue = showValue,
            summary = summary,
            insideMargin = insideMargin,
            onExpandedChange = onExpandedChange,
        )
    } else {
        WindowDropdownPreference(
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
            showValue = showValue,
            insideMargin = insideMargin,
            onExpandedChange = onExpandedChange,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    }
}
