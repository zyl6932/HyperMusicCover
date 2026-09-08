package com.os4.musiccover.ui.screen.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.BlurredBar
import com.os4.musiccover.ui.util.pageScrollModifiers
import com.os4.musiccover.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** Green when the hook answered, amber while asking, red when nothing came back. */
private val ActiveGreen = Color(0xFF34C759)
private val CheckingAmber = Color(0xFFFF9F0A)
private val InactiveRed = Color(0xFFFF453A)

@Composable
fun HomePageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = isBlurEnabled && backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    var state by remember { mutableStateOf(ModuleBridge.State()) }
    var checking by remember { mutableStateOf(true) }

    suspend fun refresh() {
        checking = true
        state = ModuleBridge.query(context)
        checking = false
    }

    // The module can be enabled, disabled or restarted behind the app's back, so ask again every
    // time this page comes to the front rather than caching the answer from launch.
    LaunchedEffect(refreshKey) { refresh() }

    val info = remember { DeviceInfo.collect(context) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive, scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.app_name),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                    ),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
                ),
            ) {
                item {
                    Column {
                        StatusCard(
                            checking = checking,
                            alive = state.alive,
                            onRetry = { scope.launch { refresh() } },
                        )

                        SmallTitle(text = stringResource(R.string.device_info))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                info.forEach { (label, value) ->
                                    InfoRow(label, value)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    checking: Boolean,
    alive: Boolean,
    onRetry: () -> Unit,
) {
    val accent = when {
        checking -> CheckingAmber
        alive -> ActiveGreen
        else -> InactiveRed
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = accent.copy(alpha = 0.14f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, CircleShape)
                )
                MiuixText(
                    modifier = Modifier.padding(start = 10.dp),
                    text = when {
                        checking -> stringResource(R.string.status_checking)
                        alive -> stringResource(R.string.status_active)
                        else -> stringResource(R.string.status_inactive)
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            if (!checking) {
                MiuixText(
                    modifier = Modifier.padding(top = 8.dp),
                    text = if (alive) {
                        stringResource(R.string.status_active_summary)
                    } else {
                        stringResource(R.string.status_inactive_summary)
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        ArrowPreference(
            title = stringResource(R.string.status_refresh),
            onClick = onRetry,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        MiuixText(
            text = label,
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
        MiuixText(
            modifier = Modifier.padding(start = 16.dp),
            text = value,
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private object DeviceInfo {

    /** ro.* properties are the only place the HyperOS version is written down. */
    private fun prop(key: String): String = try {
        @Suppress("PrivateApi")
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    fun collect(context: android.content.Context): List<Pair<String, String>> {
        val res = context.resources
        val dm = res.displayMetrics
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
        } catch (_: Throwable) {
            "-"
        }
        val hyperOs = prop("ro.mi.os.version.name").ifEmpty { prop("ro.miui.ui.version.name") }
        val build = prop("ro.mi.os.version.incremental").ifEmpty { Build.DISPLAY }

        return buildList {
            add(context.getString(R.string.info_device) to "${Build.MODEL} (${Build.DEVICE})")
            if (hyperOs.isNotEmpty()) add(context.getString(R.string.info_hyperos) to hyperOs)
            add(context.getString(R.string.info_build) to build)
            add(
                context.getString(R.string.info_android)
                        to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            add(
                context.getString(R.string.info_screen)
                        to "${dm.widthPixels}×${dm.heightPixels} · ${dm.densityDpi}dpi"
            )
            add(context.getString(R.string.info_module_version) to version)
        }
    }
}
