package com.os4.musiccover.ui.screen.features

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * Everything the module itself can be told to do. These are not app preferences: each one is a
 * command to the hook inside SystemUI, and the values shown are the ones it reports back.
 */
@Composable
fun FeaturesPageView(
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

    var module by remember { mutableStateOf(ModuleBridge.State()) }
    LaunchedEffect(refreshKey) { module = ModuleBridge.query(context) }

    val enabled = module.alive

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive, scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.tab_features),
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
                        SmallTitle(text = stringResource(R.string.home_now_playing))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                val label = module.trackLabel
                                MiuixText(
                                    text = label.ifEmpty { stringResource(R.string.home_no_track) },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                MiuixText(
                                    modifier = Modifier.padding(top = 4.dp),
                                    text = stringResource(
                                        if (module.cover) R.string.home_cover_on
                                        else R.string.home_cover_off
                                    ),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.settings_features))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        ) {
                            Column {
                                SwitchPreference(
                                    title = stringResource(R.string.home_master),
                                    summary = stringResource(R.string.home_master_summary),
                                    checked = module.auto,
                                    enabled = enabled,
                                    onCheckedChange = {
                                        module = module.copy(auto = it)
                                        ModuleBridge.setAuto(context, it)
                                    },
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.home_apply_now),
                                    summary = stringResource(R.string.home_apply_now_summary),
                                    enabled = enabled,
                                    onClick = {
                                        ModuleBridge.setCover(context, true)
                                        scope.launch { module = ModuleBridge.query(context) }
                                    },
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.home_restore),
                                    summary = stringResource(R.string.home_restore_summary),
                                    enabled = enabled,
                                    onClick = {
                                        ModuleBridge.setCover(context, false)
                                        scope.launch { module = ModuleBridge.query(context) }
                                    },
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.cover_section))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        ) {
                            SliderPreference(
                                title = stringResource(R.string.cover_bias),
                                summary = stringResource(R.string.cover_bias_summary),
                                value = module.bias,
                                onValueChange = {
                                    module = module.copy(bias = it)
                                    ModuleBridge.setBias(context, it)
                                },
                                valueRange = 0f..1f,
                                enabled = enabled,
                            )
                        }

                        SmallTitle(text = stringResource(R.string.clock_section))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        ) {
                            Column {
                                SliderPreference(
                                    title = stringResource(R.string.clock_scale),
                                    summary = stringResource(R.string.clock_scale_summary),
                                    value = module.clockScale,
                                    onValueChange = {
                                        module = module.copy(clockScale = it)
                                        ModuleBridge.setClockScale(context, it)
                                    },
                                    valueRange = 0.1f..1f,
                                    enabled = enabled,
                                )
                                SliderPreference(
                                    title = stringResource(R.string.clock_glass),
                                    summary = stringResource(R.string.clock_glass_summary),
                                    value = module.glassEnd,
                                    onValueChange = {
                                        module = module.copy(glassEnd = it)
                                        ModuleBridge.setGlassEnd(context, it)
                                    },
                                    valueRange = 0f..1f,
                                    enabled = enabled,
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.clock_depth),
                                    summary = stringResource(R.string.clock_depth_summary),
                                    checked = module.hideDepth,
                                    enabled = enabled,
                                    onCheckedChange = {
                                        module = module.copy(hideDepth = it)
                                        ModuleBridge.setHideDepth(context, it)
                                    },
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.advanced_section))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        ) {
                            Column {
                                ArrowPreference(
                                    title = stringResource(R.string.lock_wallpaper),
                                    summary = stringResource(R.string.lock_wallpaper_summary),
                                    enabled = enabled,
                                    onClick = {
                                        ModuleBridge.repairLockWallpaper(context)
                                        Toast.makeText(
                                            context,
                                            R.string.lock_wallpaper_done,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.restart_systemui),
                                    summary = stringResource(R.string.restart_systemui_summary),
                                    onClick = {
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                ModuleBridge.restartSystemUi()
                                            }
                                            Toast.makeText(
                                                context,
                                                if (ok) R.string.restart_done else R.string.restart_failed,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
