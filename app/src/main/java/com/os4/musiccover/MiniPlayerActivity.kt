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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.screen.features.ValueSlider
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import kotlin.math.roundToInt
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText

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
        setContent { AppTheme(themeMode = theme) { MiniPlayerPage(settings.isBlurEnabled, ::finish) } }
    }
}

@Composable
private fun MiniPlayerPage(blur: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var configText by remember { mutableStateOf(MiniPlayerConfig.defaultJson()) }
    var alive by remember { mutableStateOf(false) }
    val config = remember(configText) { JSONObject(configText) }
    LaunchedEffect(Unit) {
        val reply = ModuleBridge.queryAlive(context)
        alive = reply.alive
        if (reply.alive) configText = reply.miniConfig
    }
    fun push(key: String, value: Any) {
        configText = MiniPlayerConfig.normalizedJson(JSONObject(configText).put(key, value).toString())
        ModuleBridge.setMiniConfig(context, configText)
    }

    PageScaffold(title = "锁屏超级岛", isBlurEnabled = blur, onBack = onBack) {
        item {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Column {
                    SwitchPreference(title = "启用锁屏超级岛",
                        summary = if (alive) "普通锁屏的底部快捷按钮之间显示" else "等待 SystemUI 模块响应",
                        checked = config.optBoolean(MiniPlayerConfig.ENABLED), enabled = alive,
                        onCheckedChange = { push(MiniPlayerConfig.ENABLED, it) })
                }
            }
        }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column {
                    MiuixText("尺寸", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    MiniSlider("宽度", MiniPlayerConfig.WIDTH, config, alive, 160f, 360f, ::push)
                    // Stored as the shortcut radius it was ported with; shown as the height it makes.
                    MiniSlider("高度", MiniPlayerConfig.HEIGHT_RADIUS, config, alive, 24f, 60f, ::push,
                        label = { "${MiniPlayerGeometry.heightDp(it.roundToInt().toFloat()).roundToInt()}" })
                    MiniSlider("封面圆角", MiniPlayerConfig.ART_RADIUS,
                        config, alive, 0f, 60f, ::push)
                }
            }
        }
    }
}

@Composable
private fun MiniSlider(title: String, key: String, config: JSONObject, alive: Boolean,
                       minimum: Float, maximum: Float, push: (String, Any) -> Unit,
                       integer: Boolean = false,
                       label: (Float) -> String = { "${it.roundToInt()}" }) {
    ValueSlider(title = title, value = config.optDouble(key).toFloat(),
        valueRange = minimum..maximum, enabled = alive,
        label = label,
        onValueChange = { push(key, if (integer) it.roundToInt() else it.roundToInt().toFloat()) })
}
