// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.os4.musiccover.ui.screen.features.ValueSlider
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import kotlin.math.roundToInt
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
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
    val previewArtwork = remember {
        Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(0xFF634A72.toInt())
            canvas.drawCircle(49f, 26f, 25f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFC6A5.toInt()
            })
            canvas.drawRect(0f, 48f, 72f, 72f, Paint().apply { color = 0xFF2E325A.toInt() })
        }
    }

    PageScaffold(title = "锁屏迷你播放器", isBlurEnabled = blur, onBack = onBack) {
        item {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Column {
                    SwitchPreference(title = "启用锁屏迷你播放器",
                        summary = if (alive) "普通锁屏的底部快捷按钮之间显示" else "等待 SystemUI 模块响应",
                        checked = config.optBoolean(MiniPlayerConfig.ENABLED), enabled = alive,
                        onCheckedChange = { push(MiniPlayerConfig.ENABLED, it) })
                    WindowDropdownPreference(title = "原生媒体卡片", items =
                        listOf("同时显示", "迷你播放器显示时隐藏", "上下滑动切换"),
                        selectedIndex = config.optInt(MiniPlayerConfig.MEDIA_MODE), enabled = alive,
                        onSelectedIndexChange = { push(MiniPlayerConfig.MEDIA_MODE, it) })
                    MiuixText("点按进入音乐封面，左右滑动切歌；点按右侧按钮播放或暂停。滑动切换模式下，在迷你播放器上向上扫展开为原生卡片，在卡片上向下扫收回。",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp)
                }
            }
        }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column {
                    MiuixText("预览", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    Box(Modifier.fillMaxWidth().height(184.dp).padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(24.dp)).background(Color.DarkGray)) {
                        Image(painterResource(R.drawable.sample_cover),
                            "锁屏背景", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        AndroidView(
                            factory = { MiniPlayerView(it) },
                            modifier = Modifier.width(config.optDouble(MiniPlayerConfig.WIDTH, 240.0)
                                .toFloat().coerceAtMost(330f).dp)
                                .align(Alignment.BottomCenter).padding(bottom = 14.dp)
                                .height(MiniPlayerConfig.visibleHeightDp(configText).dp),
                            update = { view ->
                                view.bind("示例歌曲", "示例艺术家", previewArtwork, true, config,
                                    "preview",
                                    { material ->
                                        material.setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                                            setColor(0x9E1F2324.toInt())
                                            cornerRadius = 1000f
                                        })
                                    }, {}, {}, {}, {}, {})
                            },
                        )
                    }
                    MiuixText("材质与媒体通知卡片相同，需在锁屏上查看。",
                        Modifier.padding(16.dp), fontSize = 12.sp)
                }
            }
        }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column {
                    MiuixText("尺寸", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    MiniSlider("宽度", MiniPlayerConfig.WIDTH, config, alive, 160f, 360f, ::push)
                    MiniSlider("高度参数（显示为两倍）", MiniPlayerConfig.HEIGHT_RADIUS,
                        config, alive, 10f, 60f, ::push)
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
                       integer: Boolean = false) {
    ValueSlider(title = title, value = config.optDouble(key).toFloat(),
        valueRange = minimum..maximum, enabled = alive,
        label = { "${it.roundToInt()}" },
        onValueChange = { push(key, if (integer) it.roundToInt() else it.roundToInt().toFloat()) })
}
