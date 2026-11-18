/*
 * Adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: package renamed, project links and strings replaced,
 * and the pieces this project does not use removed.
 */
package com.os4.musiccover.ui.theme


import android.app.Activity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.os4.musiccover.ui.util.LocalCardGlass
import com.os4.musiccover.ui.util.LocalOs4Ui
import com.os4.musiccover.ui.util.LocalPageBackdrop
import com.os4.musiccover.ui.util.rememberSurfaceBackdrop
import com.os4.musiccover.ui.util.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController


@Composable
fun AppTheme(
    themeMode: ColorSchemeMode = ColorSchemeMode.System,
    /**
     * The user's choice of interface. Whether it is actually drawn also depends on the device:
     * glass needs runtime shaders, and without them the whole app stays on the OS3 screens
     * rather than showing glass bars over plain ones.
     */
    useOs4Ui: Boolean,
    /** Whether OS4 cards draw the bloom frame (edge stroke and shadow). */
    cardGlass: Boolean = true,
    content: @Composable () -> Unit
) {
    // 可用模式: System, Light, Dark, MonetSystem, MonetLight, MonetDark
    val controller = remember(themeMode) { ThemeController(themeMode) }
    // The same test the backdrops use, not the weaker RenderEffect one: OS4 is either wholly
    // drawn or not at all, and the mix of half a glass frame over OS3 content is what this
    // keeps out.
    val os4Ui = remember(useOs4Ui) { useOs4Ui && isRuntimeShaderSupported() }
    MiuixTheme(
        controller = controller,
        content = {
            // enableEdgeToEdge() 的 SystemBarStyle.auto 只跟随系统深浅模式，
            // 应用内手动指定深色/浅色主题时状态栏图标颜色不会同步。
            // 这里按实际应用主题（surface 亮度）动态设置系统栏图标颜色。
            val isDark = isInDarkTheme()
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isDark
                    insetsController.isAppearanceLightNavigationBars = !isDark
                }
            }
            CompositionLocalProvider(
                LocalOs4Ui provides os4Ui,
                LocalCardGlass provides cardGlass,
            ) {
                if (os4Ui) {
                    // The fallback every glass popup samples when nothing nearer published a
                    // recording: the page colour, so a dialog opened beside the page frame is
                    // glass rather than an opaque panel. Pages still override it with their own
                    // recording through PageScaffold.
                    val rootBackdrop = rememberSurfaceBackdrop()
                    CompositionLocalProvider(LocalPageBackdrop provides rootBackdrop) {
                        // An activity-wide host for the glass dialogs and dropdowns. A glass
                        // dialog needs a Miuix Scaffold above the composition that opens it, and
                        // several screens open theirs outside their own page frame (FpQuick's
                        // dialogs sit beside the page, not inside it); without this they went
                        // into a state list nobody rendered, so the button that opened them
                        // looked dead. One host at the root also keeps every popup above the
                        // bottom bar.
                        Scaffold(contentWindowInsets = WindowInsets(0)) { content() }
                    }
                } else {
                    content()
                }
            }
        }
    )
}