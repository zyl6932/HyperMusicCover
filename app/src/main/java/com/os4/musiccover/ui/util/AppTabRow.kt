package com.os4.musiccover.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.material.GlassDefaults

/**
 * The tab row every page uses, in whichever interface is on.
 *
 * OS4 draws the segmented glass row. It samples a backdrop that paints only the page's surface
 * colour, not the page recording: the row lives inside the body that recording holds, so
 * sampling the recording would blur a picture of the row into itself. Against the surface the
 * glass still gets its material and rim, which is what the control should look like on a page
 * with no artwork behind it.
 *
 * `contour` picks the OS3 shape the caller had (the lyric page's text/voice switch), so the
 * way back to OS3 is the same control it always was.
 */
@Composable
fun AppTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contour: Boolean = false,
) {
    if (LocalOs4Ui.current) {
        GlassSegmentedTabRow(
            tabs = tabs,
            selectedIndex = selectedTabIndex,
            onSelect = onTabSelected,
            backdrop = rememberSurfaceBackdrop(),
            modifier = modifier,
            style = GlassDefaults.style(isInDarkTheme()),
        )
    } else if (contour) {
        TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = modifier,
        )
    } else {
        TabRow(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = modifier,
        )
    }
}
