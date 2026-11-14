// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover.app
import com.os4.musiccover.island.MiniPlayerConfig
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.FoldUnder
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.os4.musiccover.ui.util.firstCard
import com.os4.musiccover.ui.util.nextCard
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
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
    // Whether a query has come back yet: before that, not answering is not yet true.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey, lost) {
        val reply = ModuleBridge.queryAlive(context)
        asked = true
        alive = reply.alive
        schema = reply.miniConfigSchema
        if (reply.alive && reply.miniConfigSchema >= MiniPlayerConfig.SCHEMA_VERSION)
            configText = reply.miniConfig
    }
    fun push(key: String, value: Any) {
        configText = MiniPlayerConfig.normalizedJson(JSONObject(configText).put(key, value).toString())
        ModuleBridge.setMiniConfig(context, configText)
    }

    val on = config.optBoolean(MiniPlayerConfig.ENABLED)

    PageScaffold(title = stringResource(R.string.mini_page_title), isBlurEnabled = blur,
        onBack = onBack) {
        item(key = "demo") {
            // What the islands do, played on a drawn phone, before the switches that turn them on.
            Card(Modifier.firstCard()) {
                IslandDemo(Modifier.padding(top = 16.dp))
            }
        }
        item(key = "master") {
            Card(Modifier.nextCard()) {
                SwitchPreference(title = stringResource(R.string.mini_enabled),
                    // Why it cannot be moved, as every master switch in the app says it: not
                    // answering at all, or answering from a build older than these settings.
                    summary = when {
                        asked && !alive -> stringResource(R.string.home_status_inactive_hint)
                        alive && !ready -> stringResource(R.string.mini_enabled_update_required)
                        else -> null
                    },
                    checked = on, enabled = ready,
                    onCheckedChange = { push(MiniPlayerConfig.ENABLED, it) })
            }
        }
        item(key = "sections") {
            // Everything else folds away under the master switch (FoldUnder), in three sections
            // as the settings and shade pages have theirs: how the islands look, which
            // notifications they take, and the rest (2026-10-14; one card of eight rows before).
            FoldUnder(on, settled = ready) {
                Column {
                    SmallTitle(text = stringResource(R.string.mini_section_look))
                    Card(Modifier.nextCard()) {
                        WindowDropdownPreference(
                            title = stringResource(R.string.mini_style),
                            items = listOf(stringResource(R.string.mini_style_row), stringResource(R.string.mini_style_stack)),
                            selectedIndex = config.optInt(MiniPlayerConfig.STYLE, MiniPlayerConfig.STYLE_ROW)
                                .coerceIn(MiniPlayerConfig.STYLE_ROW, MiniPlayerConfig.STYLE_STACK),
                            enabled = ready,
                            onSelectedIndexChange = { push(MiniPlayerConfig.STYLE, it) },
                        )
                        // The height slider sizes the island and the shortcut discs together. The
                        // pill's width and artwork radius remain fixed.
                        ValueSlider(
                            title = stringResource(R.string.mini_height),
                            summary = stringResource(R.string.mini_height_summary),
                            value = heightDraft.coerceIn(MiniPlayerConfig.MIN_HEIGHT_DP,
                                MiniPlayerConfig.MAX_HEIGHT_DP),
                            valueRange = MiniPlayerConfig.MIN_HEIGHT_DP..MiniPlayerConfig.MAX_HEIGHT_DP,
                            detent = MiniPlayerConfig.DEFAULT_HEIGHT_DP,
                            label = { "${it.roundToInt()} dp" },
                            enabled = ready,
                            onValueChange = { heightDraft = it },
                            onValueChangeFinished = {
                                push(MiniPlayerConfig.HEIGHT_RADIUS, heightDraft.roundToInt() / 2f)
                            },
                        )
                        // A switch was here and is gone: the islands take the ordinary notification's
                        // colours and soft glass and there is nothing to turn off. See
                        // MiniPlayerConfig.NOTIFICATION_MATERIAL.
                        SwitchPreference(title = stringResource(R.string.mini_widen),
                            checked = config.optBoolean(MiniPlayerConfig.ADAPTIVE_WIDTH),
                            enabled = ready,
                            onCheckedChange = { push(MiniPlayerConfig.ADAPTIVE_WIDTH, it) })
                    }
                    SmallTitle(text = stringResource(R.string.mini_section_notifications))
                    Card(Modifier.nextCard()) {
                        // Above the grouping it switches off: with ordinary notifications staying
                        // in the system list there is nothing for that row to group.
                        SwitchPreference(title = stringResource(R.string.mini_normals_in_stack),
                            checked = config.optBoolean(MiniPlayerConfig.NORMALS_IN_STACK, true),
                            enabled = ready,
                            onCheckedChange = { push(MiniPlayerConfig.NORMALS_IN_STACK, it) })
                        // Only while it can actually work. The module wants ordinary notifications
                        // on the islands and the stacked style, and feeds LockIslands.setGroupByApp
                        // the same three conditions, so outside them a row here could not be moved
                        // and would not say why.
                        FoldUnder(
                            !config.optBoolean(MiniPlayerConfig.NORMALS_IN_STACK, true) &&
                                config.optInt(MiniPlayerConfig.STYLE) == MiniPlayerConfig.STYLE_STACK,
                            settled = ready,
                        ) {
                            SwitchPreference(title = stringResource(R.string.mini_group_by_app),
                                summary = stringResource(R.string.mini_group_by_app_summary),
                                checked = config.optBoolean(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP),
                                enabled = ready,
                                onCheckedChange = { push(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP, it) })
                        }
                    }
                    SmallTitle(text = stringResource(R.string.mini_section_other))
                    Card(Modifier.nextCard()) {
                        // A switch was here and is gone: the shortcuts always go with the
                        // full-screen doze now. See MiniPlayerConfig.HIDE_AOD_SHORTCUTS.
                        // A switch was here and is gone: a long line scrolls once and rests cut.
                        // See MiniPlayerView.scrollOnce.
                        SwitchPreference(title = stringResource(R.string.mini_nav_keep_on),
                            summary = stringResource(R.string.mini_nav_keep_on_summary),
                            checked = config.optBoolean(MiniPlayerConfig.NAV_KEEP_ON),
                            enabled = ready,
                            onCheckedChange = { push(MiniPlayerConfig.NAV_KEEP_ON, it) })
                        // Off puts 勿扰, charging and the like back at the bottom, where the OEM
                        // keeps them and the pill can cover them (DateStatus).
                        SwitchPreference(title = stringResource(R.string.mini_status_at_date),
                            summary = stringResource(R.string.mini_status_at_date_summary),
                            checked = config.optBoolean(MiniPlayerConfig.STATUS_AT_DATE, true),
                            enabled = ready,
                            onCheckedChange = { push(MiniPlayerConfig.STATUS_AT_DATE, it) })
                        SwitchPreference(title = stringResource(R.string.mini_fod_lift),
                            checked = config.optBoolean(MiniPlayerConfig.FOD_LIFT),
                            enabled = ready,
                            onCheckedChange = { push(MiniPlayerConfig.FOD_LIFT, it) })
                    }
                }
            }
        }
    }
}
