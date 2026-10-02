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

    PageScaffold(title = "锁屏超级岛", isBlurEnabled = blur, onBack = onBack) {
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
                    SwitchPreference(title = "启用锁屏超级岛",
                        summary = when {
                            !alive -> "等待 SystemUI 模块响应"
                            !ready -> "请在首页重启 SystemUI，载入新版模块后才能保存这些选项"
                            else -> "普通锁屏的底部快捷按钮之间显示"
                        },
                        checked = config.optBoolean(MiniPlayerConfig.ENABLED), enabled = ready,
                        onCheckedChange = { push(MiniPlayerConfig.ENABLED, it) })
                    WindowDropdownPreference(
                        title = "锁屏岛样式",
                        items = listOf("并排样式", "堆叠样式"),
                        selectedIndex = config.optInt(MiniPlayerConfig.STYLE, MiniPlayerConfig.STYLE_ROW)
                            .coerceIn(MiniPlayerConfig.STYLE_ROW, MiniPlayerConfig.STYLE_STACK),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onSelectedIndexChange = { push(MiniPlayerConfig.STYLE, it) },
                    )
                    SwitchPreference(title = "按应用堆叠通知",
                        summary = "同一应用合成一层；点击展开该应用，上滑展开全部",
                        checked = config.optBoolean(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED) &&
                            config.optInt(MiniPlayerConfig.STYLE) == MiniPlayerConfig.STYLE_STACK,
                        onCheckedChange = { push(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP, it) })
                    SwitchPreference(title = "媒体通知默认收起为锁屏岛",
                        summary = "新媒体出现时保持锁屏岛，点击可展开封面",
                        checked = config.optBoolean(MiniPlayerConfig.MEDIA_COLLAPSED_DEFAULT),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.MEDIA_COLLAPSED_DEFAULT, it) })
                    SwitchPreference(title = "背景展开时下沉通知并隐藏指纹",
                        summary = "展开音乐封面、地图或计时器等背景时生效；收起后恢复原有布局",
                        checked = config.optBoolean(MiniPlayerConfig.SINK_WITH_EXPANDED_BACKGROUND),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.SINK_WITH_EXPANDED_BACKGROUND, it) })
                    ValueSlider(
                        title = "锁屏岛与快捷方式大小",
                        summary = "调整锁屏岛高度和两侧按钮直径",
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
                    SwitchPreference(title = "统一通知卡片材质",
                        summary = "使用锁屏通知卡片的颜色与玻璃参数",
                        checked = config.optBoolean(MiniPlayerConfig.NOTIFICATION_MATERIAL),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.NOTIFICATION_MATERIAL, it) })
                    SwitchPreference(title = "快捷方式关闭时加宽",
                        summary = "手电筒或相机在系统设置里关掉后，超级岛占用空出的位置",
                        checked = config.optBoolean(MiniPlayerConfig.ADAPTIVE_WIDTH),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.ADAPTIVE_WIDTH, it) })
                    SwitchPreference(title = "息屏时隐藏两侧快捷方式",
                        summary = "全屏息屏显示时隐藏手电筒、相机及其柔光玻璃",
                        checked = config.optBoolean(MiniPlayerConfig.HIDE_AOD_SHORTCUTS),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.HIDE_AOD_SHORTCUTS, it) })
                    SwitchPreference(title = "锁屏岛文字跑马灯",
                        summary = "关闭后长标题和副标题以省略号截断",
                        checked = config.optBoolean(MiniPlayerConfig.MARQUEE, true),
                        enabled = ready && config.optBoolean(MiniPlayerConfig.ENABLED),
                        onCheckedChange = { push(MiniPlayerConfig.MARQUEE, it) })
                }
            }
        }
    }
}
