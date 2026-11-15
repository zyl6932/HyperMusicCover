// SPDX-License-Identifier: AGPL-3.0-or-later
package com.os4.musiccover.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.SettingSpec
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.FoldUnder
import com.os4.musiccover.ui.util.PageScaffold
import com.os4.musiccover.ui.util.firstCard
import com.os4.musiccover.ui.util.nextCard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 锁屏状态小组件设置：总开关 + 大卡（天气/电量）+ 小圆片（闹钟/日出日落/湿度/AQI/体感/风向/
 * 步数/站立）+ 歌词存在时隐藏。布局由模块侧裁剪：最多 2 大；2 大时不放小片，1 大时最多 2 小。
 */
class WidgetActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.load(this)
        val theme = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
            .getOrDefault(ColorSchemeMode.System)
        setContent {
            AppTheme(themeMode = theme) { WidgetPage(settings.isBlurEnabled, resumes, ::finish) }
        }
    }

    private var resumes by mutableIntStateOf(0)
    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        if (resumedOnce) resumes++ else resumedOnce = true
    }
}

@Composable
private fun WidgetPage(blur: Boolean, refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.initialState()) }
    var asked by remember { mutableStateOf(false) }

    val lost by ModuleBridge.lost.collectAsState()
    LaunchedEffect(lost) { if (lost > 0) module = ModuleBridge.initialState() }
    LaunchedEffect(refreshKey) {
        module = ModuleBridge.queryAlive(context)
        asked = true
    }

    val on = module.widgetOn
    val inactive = asked && !module.alive

    fun send(
        spec: SettingSpec,
        op: String,
        on: Boolean,
        update: ModuleBridge.State.(Boolean) -> ModuleBridge.State,
    ) {
        ModuleBridge.setWidget(context, spec, op, on)
        module = module.update(on)
    }

    PageScaffold(
        title = stringResource(R.string.widget_page_title),
        isBlurEnabled = blur,
        onBack = onBack,
    ) {
        item(key = "master") {
            Card(Modifier.firstCard()) {
                SwitchPreference(
                    title = stringResource(R.string.widget_enabled),
                    summary = if (inactive) stringResource(R.string.home_status_inactive_hint)
                        else stringResource(R.string.widget_rule_summary),
                    checked = module.widgetOn,
                    enabled = module.alive,
                    onCheckedChange = { v ->
                        send(SettingSpec.WIDGET_ENABLED, "widgeton", v) { copy(widgetOn = it) }
                    },
                )
            }
        }
        item(key = "preview") {
            WidgetPreview(module)
        }
        item(key = "body") {
            FoldUnder(on, settled = module.alive) {
                Column {
                    SmallTitle(text = stringResource(R.string.widget_section_big))
                    Card(Modifier.nextCard()) {
                        SwitchPreference(
                            title = stringResource(R.string.widget_weather),
                            summary = stringResource(R.string.widget_weather_summary),
                            checked = module.widgetWeather,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_WEATHER, "widgetweather", v) {
                                    copy(widgetWeather = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_battery),
                            summary = stringResource(R.string.widget_battery_summary),
                            checked = module.widgetBattery,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_BATTERY, "widgetbattery", v) {
                                    copy(widgetBattery = it)
                                }
                            },
                        )
                    }

                    SmallTitle(text = stringResource(R.string.widget_section_small))
                    Card(Modifier.nextCard()) {
                        SwitchPreference(
                            title = stringResource(R.string.widget_alarm),
                            checked = module.widgetAlarm,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_ALARM, "widgetalarm", v) {
                                    copy(widgetAlarm = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_sun),
                            checked = module.widgetSun,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_SUN, "widgetsun", v) {
                                    copy(widgetSun = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_humidity),
                            checked = module.widgetHumidity,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_HUMIDITY, "widgethumidity", v) {
                                    copy(widgetHumidity = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_aqi),
                            checked = module.widgetAqi,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_AQI, "widgetaqi", v) {
                                    copy(widgetAqi = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_feels),
                            checked = module.widgetFeels,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_FEELS, "widgetfeels", v) {
                                    copy(widgetFeels = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_wind),
                            checked = module.widgetWind,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_WIND, "widgetwind", v) { copy(widgetWind = it) }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_steps),
                            checked = module.widgetSteps,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_STEPS, "widgetsteps", v) {
                                    copy(widgetSteps = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_stand),
                            checked = module.widgetStand,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_STAND, "widgetstand", v) {
                                    copy(widgetStand = it)
                                }
                            },
                        )
                    }

                    SmallTitle(text = stringResource(R.string.widget_section_other))
                    Card(Modifier.nextCard()) {
                        SwitchPreference(
                            title = stringResource(R.string.widget_hide_lyrics),
                            summary = stringResource(R.string.widget_hide_lyrics_summary),
                            checked = module.widgetHideLyrics,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_HIDE_LYRICS, "widgethidemusic", v) {
                                    copy(widgetHideLyrics = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_weather_bg),
                            summary = stringResource(R.string.widget_weather_bg_summary),
                            checked = module.widgetWeatherBg,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_WEATHER_BG, "widgetweatherbg", v) {
                                    copy(widgetWeatherBg = it)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.widget_battery_bg),
                            summary = stringResource(R.string.widget_battery_bg_summary),
                            checked = module.widgetBatteryBg,
                            enabled = module.alive,
                            onCheckedChange = { v ->
                                send(SettingSpec.WIDGET_BATTERY_BG, "widgetbatterybg", v) {
                                    copy(widgetBatteryBg = it)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 预览条目：固定示意数值，按真实组件排版画卡片，与模块侧同一套裁剪规则。 */
private data class PreviewItem(
    val key: String,
    val big: Boolean,
    val on: Boolean,
    val primary: String,
    val secondary: String?,
    val tertiary: String?,
    val bar: Boolean,
)

/** 锁屏上的实际排布预览：大卡与小片在同一行并排。 */
@Composable
private fun WidgetPreview(state: ModuleBridge.State) {
    val all = listOf(
        PreviewItem("weather", true, state.widgetWeather, "22° 多云", "芙蓉区", "高12° 低2°", false),
        PreviewItem("battery", true, state.widgetBattery, "68%", "Xiaomi 15", null, true),
        PreviewItem("sun", false, state.widgetSun, "06:30", "日出", null, false),
        PreviewItem("steps", false, state.widgetSteps, "6240", "步数", null, false),
        PreviewItem("humidity", false, state.widgetHumidity, "58%", "湿度", null, false),
        PreviewItem("aqi", false, state.widgetAqi, "42", "AQI", null, false),
        PreviewItem("feels", false, state.widgetFeels, "24°", "体感", null, false),
        PreviewItem("wind", false, state.widgetWind, "东北风3级", "风向", null, false),
        PreviewItem("stand", false, state.widgetStand, "8", "站立", null, false),
        PreviewItem("alarm", false, state.widgetAlarm, "07:10", "闹钟", null, false),
    ).filter { it.on }
    val big = all.filter { it.big }.take(2)
    val small = if (big.size == 2) emptyList() else all.filter { !it.big }.take(2)
    val items = big + small

    Card(Modifier.firstCard()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (items.isEmpty()) {
                MiuixText(
                    text = stringResource(R.string.widget_preview_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { item ->
                        if (item.big) BigPill(item) else Disc(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigPill(item: PreviewItem) {
    Box(
        modifier = Modifier
            .width(151.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.key) {
                    "weather" -> Box(
                        Modifier.width(18.dp).height(18.dp)
                            .clip(CircleShape).background(Color.White.copy(alpha = 0.55f)),
                    )
                    "battery" -> Box(
                        Modifier.width(12.dp).height(18.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.width(5.dp))
                MiuixText(text = item.primary, color = MiuixTheme.colorScheme.onSurface)
            }
            item.secondary?.let {
                MiuixText(text = it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (item.bar) {
                Box(
                    Modifier.fillMaxWidth().height(10.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = 0.28f)),
                ) {
                    Box(
                        Modifier.width(60.dp).height(10.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.White),
                    )
                }
            } else {
                item.tertiary?.let {
                    MiuixText(text = it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun Disc(item: PreviewItem) {
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            item.secondary?.let {
                MiuixText(text = it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            MiuixText(text = item.primary, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}
