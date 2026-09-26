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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.PhotoCamera
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.os4.musiccover.ui.screen.features.ValueSlider
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
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
    var shortcuts by remember { mutableStateOf<FloatArray?>(null) }
    val config = remember(configText) { JSONObject(configText) }
    LaunchedEffect(Unit) {
        val reply = ModuleBridge.queryAlive(context)
        alive = reply.alive
        if (reply.alive) {
            configText = reply.miniConfig
            shortcuts = reply.miniShortcuts
        }
    }
    val density = LocalDensity.current.density
    val screenWidthPx = context.resources.displayMetrics.widthPixels.toFloat()
    val layout = remember(configText, shortcuts, density) {
        previewLayout(config, shortcuts, density, screenWidthPx)
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
                        // The torch and camera where the lock screen has them, relative to the pill,
                        // each on a disc as tall as the pill.
                        val pillMid = PREVIEW_BOTTOM_DP + layout.pillHeight / 2f
                        ShortcutDisc(layout.torch, layout.pillHeight, pillMid,
                            Modifier.align(Alignment.BottomCenter))
                        ShortcutDisc(layout.camera, layout.pillHeight, pillMid,
                            Modifier.align(Alignment.BottomCenter))
                        ShortcutIcon(Icons.Rounded.FlashlightOn, "手电筒", layout.torch, pillMid,
                            Modifier.align(Alignment.BottomCenter))
                        ShortcutIcon(Icons.Rounded.PhotoCamera, "相机", layout.camera, pillMid,
                            Modifier.align(Alignment.BottomCenter))
                        AndroidView(
                            factory = { MiniPlayerView(it) },
                            modifier = Modifier.width(layout.pillWidth.dp)
                                .align(Alignment.BottomCenter).padding(bottom = PREVIEW_BOTTOM_DP.dp)
                                .height(layout.pillHeight.dp),
                            update = { view ->
                                view.bind("示例歌曲", "示例艺术家", previewArtwork, true, config,
                                    "preview",
                                    { material ->
                                        material.setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                                            setColor(PREVIEW_MATERIAL.toInt())
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

/** The pill's gap to the preview's bottom edge. */
private const val PREVIEW_BOTTOM_DP = 14f

/** Where a shortcut icon sits against the pill's centre, and how big it is drawn, in dp. */
private class IconPlace(val dx: Float, val dy: Float, val size: Float)

private class PreviewLayout(val pillWidth: Float, val pillHeight: Float,
                            val torch: IconPlace, val camera: IconPlace)

/**
 * The pill and the shortcut icons as the lock screen lays them out: the module's own reading of
 * the torch and camera when it has one, sized by the same rule the pill is. Without one, the
 * proportions filmed on a 1200px HyperOS 3 lock screen.
 */
private fun previewLayout(config: JSONObject, shortcuts: FloatArray?, density: Float,
                          screenWidthPx: Float): PreviewLayout {
    val height = MiniPlayerConfig.visibleHeightDp(config.toString())
    val requestedPx = (config.optDouble(MiniPlayerConfig.WIDTH, 221.0).toFloat() * density).roundToInt()
    val s = shortcuts
    // The pill clears a disc as tall as itself on each button, as on the lock screen.
    fun cleared(widthPx: Int, cx: Float, leftCx: Float, rightCx: Float) =
        MiniPlayerGeometry.clearOfDiscsPx(widthPx, cx, leftCx, rightCx, height * density,
            MiniPlayerGeometry.DISC_GAP_DP * density,
            (MiniPlayerGeometry.MIN_PILL_DP * density).roundToInt())
    if (s == null) {
        val dxPx = screenWidthPx * FALLBACK_ICON_OFFSET
        val cx = screenWidthPx / 2f
        val widthPx = cleared(min(requestedPx, (screenWidthPx * .64f).toInt()), cx, cx - dxPx, cx + dxPx)
        val dx = dxPx / density
        return PreviewLayout(widthPx / density, height, IconPlace(-dx, 0f, FALLBACK_ICON_DP),
            IconPlace(dx, 0f, FALLBACK_ICON_DP))
    }
    val hostWidth = s[0].roundToInt()
    val cx = (s[1] + s[5]) / 2f
    val cy = (s[2] + s[6]) / 2f
    val widthPx = cleared(MiniPlayerGeometry.widthPx(min(requestedPx, (hostWidth * .64f).toInt()),
        hostWidth, cx, (12f * density).roundToInt()), cx, s[1], s[5])
    // The module reports the OEM glyph's ink; a Material icon's glyph spans 20 of its 24 units.
    fun place(i: Int): IconPlace {
        val ink = max(s[i + 2], s[i + 3])
        return IconPlace((s[i] - cx) / density, (s[i + 1] - cy) / density,
            if (ink > 0f) ink / density * 24f / 20f else FALLBACK_ICON_DP)
    }
    return PreviewLayout(widthPx / density, height, place(1), place(5))
}

/** The fallback's icon centres, either side of the pill's, as a share of the screen width. */
private const val FALLBACK_ICON_OFFSET = 0.358f
private const val FALLBACK_ICON_DP = 27f

/** The disc behind a shortcut, in the preview's stand-in for the card's material. */
@Composable
private fun ShortcutDisc(place: IconPlace, diameter: Float, pillMid: Float, modifier: Modifier) {
    Box(modifier
        .offset(x = place.dx.dp, y = -(pillMid - place.dy - diameter / 2f).dp)
        .size(diameter.dp)
        .clip(CircleShape)
        .background(Color(PREVIEW_MATERIAL)))
}

/** The preview's material: the pill's plain fill, as MiniPlayerView gets it here. */
private const val PREVIEW_MATERIAL = 0x9E1F2324

@Composable
private fun ShortcutIcon(image: ImageVector, description: String, place: IconPlace,
                         pillMid: Float, modifier: Modifier) {
    Icon(imageVector = image, contentDescription = description,
        tint = Color.White.copy(alpha = 0.8f),
        modifier = modifier
            .offset(x = place.dx.dp, y = -(pillMid - place.dy - place.size / 2f).dp)
            .size(place.size.dp))
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
