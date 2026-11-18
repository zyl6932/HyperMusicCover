package com.os4.musiccover.ui.screen.settings

import com.os4.musiccover.ui.util.nextCard
import com.os4.musiccover.ui.util.AppCard
import com.os4.musiccover.ui.util.firstCard
import com.os4.musiccover.ui.util.FoldUnder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.os4.musiccover.ui.util.AppDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The theme settings, on their own screen instead of in the settings list.
 *
 * KernelSU keeps its settings page to one row that opens a second-level screen holding the whole
 * appearance block, and this follows that: the settings page says "theme" and nothing more, and
 * everything that repaints the app lives here. Two reasons it is worth the extra screen. The
 * list was long enough that the settings page read as a wall, and the switches that follow are
 * not all visible at once - liquid glass only exists when the floating navbar is on, so on the
 * settings page a row appeared or vanished underneath the finger that opened the dropdown above
 * it. Here the dropdown is the last thing that moves.
 *
 * Nothing is read or saved here: the values arrive from the screen that hosts this
 * ([ThemeActivity]), which owns them because it owns the theme this page is drawn in.
 */
@Composable
fun ThemePageView(
    currentMode: ColorSchemeMode,
    onModeChange: (ColorSchemeMode) -> Unit,
    useOs4Ui: Boolean,
    os4Supported: Boolean,
    onUseOs4UiChange: (Boolean) -> Unit,
    cardGlass: Boolean,
    onCardGlassChange: (Boolean) -> Unit,
    isFloatingNavbar: Boolean,
    onFloatingNavbarChange: (Boolean) -> Unit,
    isLiquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    isBlurEnabled: Boolean,
    onBlurEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(
        title = stringResource(R.string.settings_theme),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item(key = "theme") {
            AppCard(
                modifier = Modifier.firstCard()
                    .padding(bottom = 12.dp)
            ) {
                Column {
                    // The whole app's look, and the first thing on the page because everything
                    // under it depends on the answer. Hidden where the device cannot render
                    // glass: an option that would silently do nothing is worse than no option.
                    if (os4Supported) {
                        val styles = listOf(
                            stringResource(R.string.ui_style_os4),
                            stringResource(R.string.ui_style_os3),
                        )
                        AppDropdownPreference(
                            title = stringResource(R.string.ui_style),
                            items = styles,
                            selectedIndex = if (useOs4Ui) 0 else 1,
                            onSelectedIndexChange = { onUseOs4UiChange(it == 0) },
                        )
                    }
                    val modes = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark),
                        stringResource(R.string.theme_monet_system),
                        stringResource(R.string.theme_monet_light),
                        stringResource(R.string.theme_monet_dark)
                    )
                    val modesEnum = listOf(
                        ColorSchemeMode.System,
                        ColorSchemeMode.Light,
                        ColorSchemeMode.Dark,
                        ColorSchemeMode.MonetSystem,
                        ColorSchemeMode.MonetLight,
                        ColorSchemeMode.MonetDark
                    )
                    var expanded by remember { mutableStateOf(false) }
                    val currentIndex =
                        modesEnum.indexOf(currentMode).takeIf { it >= 0 } ?: 0

                    AppDropdownPreference(
                        title = stringResource(R.string.theme_mode),
                        summary = modes[currentIndex],
                        items = modes,
                        selectedIndex = currentIndex,
                        onSelectedIndexChange = { onModeChange(modesEnum[it]) },
                        onExpandedChange = { expanded = it }
                    )

                    // Only means something while OS4 is the interface being drawn.
                    if (os4Supported && useOs4Ui) {
                        SwitchPreference(
                            title = stringResource(R.string.card_glass),
                            summary = stringResource(R.string.card_glass_summary),
                            checked = cardGlass,
                            onCheckedChange = onCardGlassChange,
                        )
                    }

                    // The three switches below only describe the OS3 chrome, which OS4 replaces
                    // wholesale. Folded rather than disabled, and only folded while OS4 is what
                    // is actually drawn - off, they are exactly the switches they always were,
                    // values kept.
                    FoldUnder(!(useOs4Ui && os4Supported)) {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.floating_navbar),
                                summary = stringResource(R.string.floating_navbar_summary),
                                checked = isFloatingNavbar,
                                onCheckedChange = onFloatingNavbarChange
                            )

                            // Glass is a style of the floating bar, so the row for it only exists
                            // while that bar is the one on screen. Expanding rather than appearing
                            // keeps the card's height continuous - the switch below does not jump.
                            FoldUnder(isFloatingNavbar) {
                                SwitchPreference(
                                    title = stringResource(R.string.liquid_glass),
                                    summary = stringResource(R.string.liquid_glass_summary),
                                    checked = isLiquidGlass,
                                    onCheckedChange = onLiquidGlassChange
                                )
                            }

                            SwitchPreference(
                                title = stringResource(R.string.blur_enabled),
                                summary = stringResource(R.string.blur_enabled_summary),
                                checked = isBlurEnabled,
                                onCheckedChange = onBlurEnabledChange
                            )
                        }
                    }
                }
            }
        }
    }
}
