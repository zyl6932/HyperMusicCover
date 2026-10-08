// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

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
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

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
    val config = remember(configText) { JSONObject(configText) }
    // See ShadePageView: a setting the module did not take greys the page until it answers again.
    val lost by ModuleBridge.lost.collectAsState()
    LaunchedEffect(lost) { if (lost > 0) alive = false }
    LaunchedEffect(refreshKey, lost) {
        val reply = ModuleBridge.queryAlive(context)
        alive = reply.alive
        if (reply.alive) configText = reply.miniConfig
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
            // The pill's width, its height and the roundness of the thumbnail in it were three
            // sliders and are fixed - see MiniPlayerConfig, which no longer reads them from
            // anything the app sends. What is left is whether it is on, and whether it widens
            // into a switched-off shortcut's place.
            Card(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Column {
                    // The one line the switch still carries, and only while the module is away:
                    // a switch that cannot be moved and does not say why reads as the switch
                    // being broken. Nothing here describes the feature itself any more.
                    SwitchPreference(title = stringResource(R.string.mini_enabled),
                        summary = if (alive) null
                                  else stringResource(R.string.mini_enabled_waiting),
                        checked = config.optBoolean(MiniPlayerConfig.ENABLED), enabled = alive,
                        onCheckedChange = { push(MiniPlayerConfig.ENABLED, it) })
                    SwitchPreference(title = stringResource(R.string.mini_widen),
                        summary = stringResource(R.string.mini_widen_summary),
                        checked = config.optBoolean(MiniPlayerConfig.ADAPTIVE_WIDTH),
                        enabled = alive && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.ADAPTIVE_WIDTH, it) })
                    SwitchPreference(title = stringResource(R.string.mini_nav_keep_on),
                        summary = stringResource(R.string.mini_nav_keep_on_summary),
                        checked = config.optBoolean(MiniPlayerConfig.NAV_KEEP_ON),
                        enabled = alive && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.NAV_KEEP_ON, it) })
                    SwitchPreference(title = stringResource(R.string.mini_fod_lift),
                        summary = stringResource(R.string.mini_fod_lift_summary),
                        checked = config.optBoolean(MiniPlayerConfig.FOD_LIFT),
                        enabled = alive && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.FOD_LIFT, it) })
                }
            }
        }
    }
}
