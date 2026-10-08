package com.os4.musiccover

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.screen.features.TransitDemo
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 高德's bus and subway trip - its island (AmapTransitIsland) and its lock screen page
 * (AmapTransitScene) - as a screen of its own, with the one switch for both.
 *
 * See [CoverActivity] for why this is an Activity rather than a page inside the features tab.
 */
class TransitActivity : ComponentActivity() {

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
            AppTheme(themeMode = theme) { TransitPage(settings.isBlurEnabled, resumes, ::finish) }
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
private fun TransitPage(blur: Boolean, refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    var asked by remember { mutableStateOf(false) }
    // 取餐码云端名单那一行：正在同步，和上一次失败的原因（成功了就清掉，日期自己会变）。
    var syncing by remember { mutableStateOf(false) }
    var cloudSaid by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // See ShadePageView: a setting the module did not take greys the page until it answers again.
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

    PageScaffold(title = stringResource(R.string.features_transit_title), isBlurEnabled = blur,
        onBack = onBack) {
        item {
            // What the trip's island does, played on a drawn phone, before the switch (as the
            // islands' own page has it).
            Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                TransitDemo(Modifier.padding(top = 16.dp))
            }
        }
        item {
            // 通勤这一节的抬头，和这一节的第一张卡：「启用高德公交地铁」。这个开关管的是整节
            // （逐站岛和通勤学习都在它下面），所以它落在抬头之下、学习通勤路线之上。
            SmallTitle(text = stringResource(R.string.commute_title),
                modifier = Modifier.padding(top = 12.dp))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                // Only an answer can put this on (ShadePageView says why).
                SwitchPreference(title = stringResource(R.string.transit_enabled),
                    summary = if (asked && !module.alive) {
                        stringResource(R.string.home_status_inactive_hint)
                    } else {
                        null
                    },
                    checked = module.alive && module.transit, enabled = module.alive,
                    onCheckedChange = {
                        module = module.copy(transit = it)
                        ModuleBridge.setTransit(context, it)
                    })
            }
        }
        item {
            CommuteSection(refreshKey)
        }
        item {
            SmallTitle(text = stringResource(R.string.pickup_title), modifier = Modifier.padding(top = 12.dp))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(title = stringResource(R.string.pickup_enabled),
                    checked = module.alive && module.pickup, enabled = module.alive,
                    onCheckedChange = {
                        module = module.copy(pickup = it)
                        ModuleBridge.setPickup(context, it)
                    })
            }
        }
        item {
            // 云端那一份是 ColorOS 自己的微信小程序名单（93 条），比随包的规则 XML 新也比它全；
            // 拉一次要过网络，所以给一个手动按钮，副标题显示当前这份的更新日期。
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.pickup_cloud_refresh),
                    summary = when {
                        syncing -> stringResource(R.string.pickup_cloud_syncing)
                        cloudSaid.isNotEmpty() -> cloudSaid
                        module.rulesDate.isNotEmpty() -> stringResource(
                            R.string.pickup_cloud_date, module.rulesDate, module.rulesCount)
                        else -> stringResource(R.string.pickup_cloud_never)
                    },
                    // 整行不可点：刷新这件事只有右边那个按钮能做，免得误触（也免得看着像能点）。
                    endActions = {
                        IconButton(
                            enabled = module.alive && !syncing,
                            onClick = {
                                syncing = true
                                scope.launch {
                                    val said = ModuleBridge.refreshPickupRules(context)
                                    syncing = false
                                    // 成功时模块的日期已经变了，重新问一次就显示出来；失败才留在
                                    // 副标题上——悄悄失败比多一行字更糟。
                                    cloudSaid = if (said.startsWith("已更新")) "" else said
                                    module = ModuleBridge.queryAlive(context)
                                }
                            }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.pickup_cloud_refresh),
                                tint = if (module.alive && !syncing) {
                                    MiuixTheme.colorScheme.onBackground
                                } else {
                                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                                })
                        }
                    })
            }
        }
    }
}

/**
 * The subway commutes the ride-code island learns (MetroCommute, in 小爱建议): whether it learns,
 * what it has learned, and forgetting it. Asked of 小爱建议 itself, which keeps them.
 */
@Composable
private fun CommuteSection(refreshKey: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ModuleBridge.Commute()) }
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey) {
        state = ModuleBridge.commute(context)
        asked = true
    }
    Card(Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(title = stringResource(R.string.commute_learn),
            summary = if (asked && !state.reached) {
                stringResource(R.string.commute_unreached)
            } else {
                stringResource(R.string.commute_learn_summary)
            },
            checked = state.reached && state.learning, enabled = state.reached,
            onCheckedChange = { on ->
                state = state.copy(learning = on)
                scope.launch { state = ModuleBridge.commute(context, "learn", on) }
            })
        for (r in state.routes) {
            val via = r.changes.joinToString("、")
            BasicComponent(title = r.start + " → " + r.end,
                summary = if (via.isEmpty()) {
                    stringResource(R.string.commute_route_direct, r.trips)
                } else {
                    stringResource(R.string.commute_route_via, via, r.trips)
                })
        }
        if (state.reached && state.trips > 0) {
            BasicComponent(title = stringResource(R.string.commute_forget),
                summary = stringResource(R.string.commute_forget_summary, state.trips),
                onClick = { scope.launch { state = ModuleBridge.commute(context, "forget") } })
        }
    }
}
