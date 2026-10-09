// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.screen.features.IslandDemo
import com.os4.musiccover.ui.screen.features.ValueSlider
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import kotlin.math.roundToInt

class MiniPlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.load(this)
        val theme = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
            .getOrDefault(ColorSchemeMode.System)
        setContent {
            AppTheme(themeMode = theme) { MiniPlayerPage(settings.isBlurEnabled, resumes, ::finish) }
        }
    }

    // See CoverActivity: asked again each time the screen comes back to the front.
    private var resumes by mutableIntStateOf(0)
    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        if (resumedOnce) resumes++ else resumedOnce = true
    }
}

@Composable
private fun MiniPlayerPage(blur: Boolean, refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var configText by remember { mutableStateOf(MiniPlayerConfig.defaultJson()) }
    var alive by remember { mutableStateOf(false) }
    var schema by remember { mutableIntStateOf(0) }
    val ready = alive && schema >= MiniPlayerConfig.SCHEMA_VERSION
    val config = remember(configText) { JSONObject(configText) }
    val savedHeight = (config.optDouble(MiniPlayerConfig.HEIGHT_RADIUS,
        MiniPlayerConfig.DEFAULT_HEIGHT_DP.toDouble() / 2.0) * 2.0).toFloat()
    var heightDraft by remember { mutableStateOf(savedHeight) }
    LaunchedEffect(savedHeight) { heightDraft = savedHeight }
    // See ShadePageView: a setting the module did not take greys the page until it answers again.
    val lost by ModuleBridge.lost.collectAsState()
    LaunchedEffect(lost) { if (lost > 0) alive = false }
    LaunchedEffect(refreshKey, lost) {
        val reply = ModuleBridge.queryAlive(context)
        alive = reply.alive
        schema = reply.miniConfigSchema
        if (reply.alive && reply.miniConfigSchema >= MiniPlayerConfig.SCHEMA_VERSION)
            configText = reply.miniConfig
    }
    fun push(key: String, value: Any) {
        configText = MiniPlayerConfig.normalizedJson(JSONObject(configText).put(key, value).toString())
        ModuleBridge.setMiniConfig(context, configText)
    }

    PageScaffold(title = stringResource(R.string.mini_page_title), isBlurEnabled = blur,
        onBack = onBack) {
        item {
            // What the islands do, played on a drawn phone, before the switches that turn them on.
            Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                IslandDemo(Modifier.padding(top = 16.dp))
            }
        }
        item {
            // The height slider sizes the island and the shortcut discs together. The pill's
            // width and artwork radius remain fixed.
            Card(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Column {
                    SwitchPreference(title = stringResource(R.string.mini_enabled),
                        summary = when {
                            !alive -> stringResource(R.string.mini_enabled_waiting)
                            !ready -> stringResource(R.string.mini_enabled_update_required)
                            else -> null
                        },
                        checked = config.optBoolean(MiniPlayerConfig.ENABLED), enabled = ready,
                        onCheckedChange = { push(MiniPlayerConfig.ENABLED, it) })
                    WindowDropdownPreference(
                        title = stringResource(R.string.mini_style),
                        items = listOf(stringResource(R.string.mini_style_row), stringResource(R.string.mini_style_stack)),
                        selectedIndex = config.optInt(MiniPlayerConfig.STYLE, MiniPlayerConfig.STYLE_ROW)
                            .coerceIn(MiniPlayerConfig.STYLE_ROW, MiniPlayerConfig.STYLE_STACK),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onSelectedIndexChange = { push(MiniPlayerConfig.STYLE, it) },
                    )
                    // Above the grouping it switches off: with ordinary notifications staying in
                    // the system list there is nothing for that row to group, and the expression
                    // below greys it out for exactly as long as this one is on.
                    SwitchPreference(title = stringResource(R.string.mini_normals_in_stack),
                        checked = config.optBoolean(MiniPlayerConfig.NORMALS_IN_STACK, true),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.NORMALS_IN_STACK, it) })
                    // Only while it can actually work. The module wants ordinary notifications on
                    // the islands and the stacked style, and feeds LockIslands.setGroupByApp the
                    // same three conditions, so outside them a row here could not be moved and
                    // would not say why - which is the whole of what the user would see. It
                    // expands in under the switch above rather than appearing, so the card's
                    // height stays continuous and the rows below do not jump (as the theme page's
                    // glass row does).
                    AnimatedVisibility(
                        visible = ready && config.optBoolean(MiniPlayerConfig.ENABLED) &&
                            !config.optBoolean(MiniPlayerConfig.NORMALS_IN_STACK, true) &&
                            config.optInt(MiniPlayerConfig.STYLE) == MiniPlayerConfig.STYLE_STACK,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        SwitchPreference(title = stringResource(R.string.mini_group_by_app),
                            summary = stringResource(R.string.mini_group_by_app_summary),
                            checked = config.optBoolean(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP),
                            enabled = ready,
                            onCheckedChange = { push(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP, it) })
                    }
                    ValueSlider(
                        title = stringResource(R.string.mini_height),
                        summary = stringResource(R.string.mini_height_summary),
                        value = heightDraft.coerceIn(MiniPlayerConfig.MIN_HEIGHT_DP,
                            MiniPlayerConfig.MAX_HEIGHT_DP),
                        valueRange = MiniPlayerConfig.MIN_HEIGHT_DP..MiniPlayerConfig.MAX_HEIGHT_DP,
                        detent = MiniPlayerConfig.DEFAULT_HEIGHT_DP,
                        label = { "${it.roundToInt()} dp" },
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onValueChange = { heightDraft = it },
                        onValueChangeFinished = {
                            push(MiniPlayerConfig.HEIGHT_RADIUS, heightDraft.roundToInt() / 2f)
                        },
                    )
                    // A switch was here and is gone: the islands take the ordinary notification's
                    // colours and soft glass and there is nothing to turn off. See
                    // MiniPlayerConfig.NOTIFICATION_MATERIAL.
                    SwitchPreference(title = stringResource(R.string.mini_widen),
                        summary = stringResource(R.string.mini_widen_summary),
                        checked = config.optBoolean(MiniPlayerConfig.ADAPTIVE_WIDTH),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.ADAPTIVE_WIDTH, it) })
                    // A switch was here and is gone: the shortcuts always go with the full-screen
                    // doze now. See MiniPlayerConfig.HIDE_AOD_SHORTCUTS.
                    SwitchPreference(title = stringResource(R.string.mini_reduce_aod_updates),
                        summary = stringResource(R.string.mini_reduce_aod_updates_summary),
                        checked = config.optBoolean(MiniPlayerConfig.REDUCE_AOD_UPDATES),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.REDUCE_AOD_UPDATES, it) })
                    SwitchPreference(title = stringResource(R.string.mini_marquee),
                        summary = stringResource(R.string.mini_marquee_summary),
                        checked = config.optBoolean(MiniPlayerConfig.MARQUEE, true),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.MARQUEE, it) })
                    SwitchPreference(title = stringResource(R.string.mini_nav_keep_on),
                        summary = stringResource(R.string.mini_nav_keep_on_summary),
                        checked = config.optBoolean(MiniPlayerConfig.NAV_KEEP_ON),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.NAV_KEEP_ON, it) })
                    // No 状态显示在日期后面 row: the placement is fixed with the islands on
                    // (MiniPlayerRuntime.statusAtDate), so there is nothing left to switch.
                    SwitchPreference(title = stringResource(R.string.mini_fod_lift),
                        summary = stringResource(R.string.mini_fod_lift_summary),
                        checked = config.optBoolean(MiniPlayerConfig.FOD_LIFT),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.FOD_LIFT, it) })
                }
            }
        }
    }
}
