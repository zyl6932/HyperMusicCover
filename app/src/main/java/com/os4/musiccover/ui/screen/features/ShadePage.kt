package com.os4.musiccover.ui.screen.features

import com.os4.musiccover.ui.util.nextCard
import com.os4.musiccover.ui.util.AppCard
import com.os4.musiccover.ui.util.firstCard
import com.os4.musiccover.ui.util.FoldUnder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.os4.musiccover.app.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.os4.musiccover.ui.util.AppDropdownPreference

/**
 * The notification shade's settings: the album cover as a moving background behind the shade.
 *
 * Its own screen rather than a section of the lock screen's, because it is a separate feature
 * with a separate failure surface. See [ShadeActivity][com.os4.musiccover.ShadeActivity] for why
 * it is a screen and not a page.
 *
 * The frame is [PageScaffold]'s. Every value is an Int on the wire and the module clamps it.
 */
@Composable
internal fun ShadePageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    // Whether the module has had its chance to answer. Until it has, the page says nothing about
    // it: "not loaded" is as wrong a thing to show a phone that is still starting SystemUI as
    // "on" is to show one where the feature is off.
    var asked by remember { mutableStateOf(false) }
    // A setting the module did not acknowledge means it went away under this page - SystemUI
    // restarting while the page stayed open. What the page shows is then only what it last
    // heard, so it greys out and asks again; the setting itself is kept and sent on its return.
    val lost by ModuleBridge.lost.collectAsState()
    LaunchedEffect(lost) {
        if (lost > 0) {
            module = module.copy(alive = false)
            asked = false
        }
    }
    LaunchedEffect(refreshKey, lost) {
        module = ModuleBridge.queryAlive(context)
        asked = true
    }
    // Only an answer can put this switch on. The key is missing in two cases - nothing answered,
    // and a module older than the setting - and reading the module's ship default for BOTH is
    // what made a timed-out query report the feature as on: the query after a scope restart lands
    // before SystemUI has a receiver, and the page then showed 流光 enabled and greyed out, with
    // no way to turn off something that was already off. The gate on `module.alive` is what keeps
    // an unanswered query out of it; the fallback below is the module's ship default, which is
    // off now, so a key that is missing for any other reason reads as off too.
    val master = module.alive && (module.shade["enabled"] ?: 0) != 0

    // Local first, then the module: its answer arrives a broadcast later.
    val push: (String, Int) -> Unit = { key, value ->
        module = module.copy(shade = module.shade + (key to value))
        ModuleBridge.setShade(context, key, value)
    }

    PageScaffold(
        title = stringResource(R.string.features_shade_title),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onBack = onBack,
    ) {
        item(key = "master") {
            Column {
                AppCard(modifier = Modifier.firstCard()) {
                    SwitchPreference(
                        title = stringResource(R.string.shade_enabled),
                        // Said here rather than left to a greyed-out page: a switch that cannot
                        // be moved and does not say why is the whole of what the user sees when
                        // the module is not loaded, and it reads as the switch being broken.
                        summary = if (asked && !module.alive) {
                            stringResource(R.string.home_status_inactive_hint)
                        } else {
                            null
                        },
                        checked = master,
                        enabled = module.alive,
                        onCheckedChange = { push("enabled", if (it) 1 else 0) },
                    )
                }

                // Folds away under the master switch rather than greying out (FoldUnder); still
                // only once the module has answered, so entering the page plays nothing.
                FoldUnder(master, settled = module.alive) {
                    Column {
                        SmallTitle(text = stringResource(R.string.shade_section_behaviour))
                        AppCard(modifier = Modifier.nextCard()) {
                            // Index is the module's mode: 0 hands the shade back, 1 keeps the last cover.
                            val modes = listOf(
                                stringResource(R.string.shade_mode_restore),
                                stringResource(R.string.shade_mode_keep),
                            )
                            AppDropdownPreference(
                                title = stringResource(R.string.shade_mode),
                                items = modes,
                                selectedIndex = (module.shade["mode"] ?: 0).coerceIn(0, modes.lastIndex),
                                enabled = module.alive,
                                onSelectedIndexChange = { push("mode", it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
