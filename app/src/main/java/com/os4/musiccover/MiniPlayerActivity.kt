// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.screen.features.ValueSlider
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import kotlin.math.roundToInt
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    fun pushMaterial(section: String, key: String, value: Any) {
        val nested = JSONObject(configText).getJSONObject(section)
        nested.put(key, value)
        push(section, nested)
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
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MaterialControls("锁屏岛与迷你播放器背景", MiniPlayerConfig.ISLAND_MATERIAL,
                    config.getJSONObject(MiniPlayerConfig.ISLAND_MATERIAL), alive, ::pushMaterial)
            }
        }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column {
                    MiuixText("锁屏快捷按钮背景", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    SwitchPreference(title = "跟随锁屏岛材质",
                        summary = "手电筒和相机按钮使用锁屏岛的模式与参数",
                        checked = config.optBoolean(MiniPlayerConfig.SHORTCUT_FOLLOW_ISLAND, true),
                        enabled = alive,
                        onCheckedChange = { push(MiniPlayerConfig.SHORTCUT_FOLLOW_ISLAND, it) })
                    if (!config.optBoolean(MiniPlayerConfig.SHORTCUT_FOLLOW_ISLAND, true)) {
                        MaterialControls(null, MiniPlayerConfig.SHORTCUT_MATERIAL,
                            config.getJSONObject(MiniPlayerConfig.SHORTCUT_MATERIAL), alive, ::pushMaterial)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialControls(title: String?, section: String, config: JSONObject, alive: Boolean,
                             push: (String, String, Any) -> Unit) {
    Column {
        if (title != null) MiuixText(title, Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
        val modes = listOf("系统自动", "纯色", "高级材质", "柔光玻璃")
        val mode = config.optInt("mode").coerceIn(0, 3)
        WindowDropdownPreference(title = "背景材质", summary = modes[mode], items = modes,
            selectedIndex = mode, enabled = alive,
            onSelectedIndexChange = { push(section, "mode", it) })
        when (mode) {
            MiniMaterialStyle.PURE -> ColorInput("背景颜色", section, "pureColor",
                config.optInt("pureColor"), alive, push)
            MiniMaterialStyle.ADVANCED -> {
                ColorInput("混色颜色", section, "advancedColor", config.optInt("advancedColor"), alive, push)
                MaterialSlider("不透明度", section, "advancedOpacity", config, alive, 0f, 100f, "%", push)
                MaterialSlider("背景模糊度", section, "advancedBlur", config, alive, 0f, 40f, "", push)
                SwitchPreference(title = "显示高光", checked = config.optBoolean("advancedHighlight"),
                    enabled = alive, onCheckedChange = { push(section, "advancedHighlight", it) })
            }
            MiniMaterialStyle.SOFT_GLASS -> {
                ColorInput("混色颜色", section, "softColor", config.optInt("softColor"), alive, push)
                MaterialSlider("不透明度", section, "softOpacity", config, alive, 0f, 100f, "%", push)
                MaterialSlider("背景模糊度", section, "softBackdropBlur", config, alive, 0f, 40f, "", push)
                MaterialSlider("Glass 模糊度", section, "softGlassBlur", config, alive, 0f, 40f, "", push)
                val value = config.optDouble("softLuminance").toFloat()
                ValueSlider(title = "柔光强度", value = value, valueRange = 0f..0.4f,
                    enabled = alive, label = { "%.2f".format(it) },
                    onValueChange = { push(section, "softLuminance", (it * 100).roundToInt() / 100f) })
            }
        }
    }
}

@Composable
private fun MaterialSlider(title: String, section: String, key: String, config: JSONObject,
                           alive: Boolean, min: Float, max: Float, suffix: String,
                           push: (String, String, Any) -> Unit) {
    ValueSlider(title = title, value = config.optInt(key).toFloat(), valueRange = min..max,
        enabled = alive, label = { "${it.roundToInt()}$suffix" },
        onValueChange = { push(section, key, it.roundToInt()) })
}

@Composable
private fun ColorInput(title: String, section: String, key: String, saved: Int, alive: Boolean,
                       push: (String, String, Any) -> Unit) {
    var input by remember(section, key, saved) { mutableStateOf("#%08X".format(saved)) }
    val valid = input.matches(Regex("#?[0-9a-fA-F]{8}"))
    val preview = if (valid) input.removePrefix("#").toLong(16).toInt() else saved
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        MiuixText(title)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(Modifier.size(36.dp).border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                .padding(2.dp).background(Color(preview), RoundedCornerShape(4.dp)))
            BasicTextField(value = input, singleLine = true, enabled = alive,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp),
                onValueChange = { next ->
                    if (next.length > 9) return@BasicTextField
                    input = next
                    if (next.matches(Regex("#?[0-9a-fA-F]{8}")))
                        push(section, key, next.removePrefix("#").toLong(16).toInt())
                })
        }
        MiuixText("ARGB 格式：#AARRGGBB", Modifier.padding(top = 4.dp))
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
