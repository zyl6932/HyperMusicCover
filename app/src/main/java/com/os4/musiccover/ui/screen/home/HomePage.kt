/*
 * Layout adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: the status card reports whether the hook answered rather than
 * root/immersion state, and the two figures beside it are this module's own.
 */
package com.os4.musiccover.ui.screen.home

import android.annotation.SuppressLint
import android.widget.Toast
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
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
import com.os4.musiccover.ui.util.isInDarkTheme
import com.os4.musiccover.ui.util.pageScrollModifiers
import com.os4.musiccover.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@SuppressLint("LocalContext")
@Composable
fun HomePageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val title = stringResource(R.string.tab_home)

    val deviceModel = Build.MODEL.ifEmpty { stringResource(R.string.home_unknown) }
    val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?: deviceModel
    val systemVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    val loadingText = stringResource(R.string.home_loading)
    var hyperOSVersion by remember { mutableStateOf(loadingText) }
    LaunchedEffect(Unit) {
        hyperOSVersion = withContext(Dispatchers.IO) { SystemVersion.hyperOs(loadingText) }
    }
    val moduleVersion = "v" + try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.1"
    } catch (_: Exception) {
        "0.0.1"
    }

    var state by remember { mutableStateOf(ModuleBridge.State()) }
    var checked by remember { mutableStateOf(false) }

    suspend fun refresh() {
        checked = false
        state = ModuleBridge.query(context)
        checked = true
    }

    // The module can be enabled, disabled or restarted behind the app's back, so ask again every
    // time this page comes to the front rather than caching the answer from launch.
    LaunchedEffect(refreshKey) { refresh() }

    val backdrop = rememberBlurBackdrop()
    val blurActive = isBlurEnabled && backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive, scrollBehavior) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = { RestartMenu() },
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
                    bottom = innerPadding.calculateBottomPadding() + extraBottomPadding
                )
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                    ) {
                        val darkTheme = isInDarkTheme()
                        val dynamicColor = MiuixTheme.isDynamicColor
                        val ok = state.alive
                        val statusColor = if (ok) {
                            when {
                                dynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                                darkTheme -> Color(0xFF1A3825)
                                else -> Color(0xFFDFFAE4)
                            }
                        } else {
                            when {
                                dynamicColor -> MiuixTheme.colorScheme.errorContainer
                                darkTheme -> Color(0xFF3D1C1C)
                                else -> Color(0xFFFDE8E8)
                            }
                        }
                        val iconTint = if (ok) {
                            if (dynamicColor) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                            else Color(0xFF36D167)
                        } else {
                            if (dynamicColor) MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
                            else Color(0xFFDC3545)
                        }
                        val titleText = if (ok) {
                            stringResource(R.string.home_status_active)
                        } else {
                            stringResource(R.string.home_status_inactive)
                        }
                        // When it is working there is nothing to instruct the user about,
                        // so the two lines say what is running instead. When it is not, the
                        // second line is the one thing worth reading.
                        val lineTwo = when {
                            !checked -> stringResource(R.string.home_loading)
                            ok -> moduleVersion
                            else -> stringResource(R.string.home_status_inactive_hint)
                        }
                        // Hard-coded until there is a second layout to switch to; the
                        // album-card style is the planned one, and this is where it gets chosen.
                        val workingMode = if (checked && ok) {
                            stringResource(R.string.home_mode_fullscreen)
                        } else {
                            null
                        }

                        // Laid out the way KernelSU's HomeMiuix card is: three boxes stacked
                        // on top of each other rather than three lines in a column. The title and
                        // version sit top-left, the working mode is pinned bottom-left, and the
                        // oversized icon bleeds off the bottom-right corner - the mode and the
                        // icon balance each other diagonally, and the empty middle is what stops
                        // it looking cramped. Stacking all three as a column instead put them in
                        // a huddle at the top with the icon crowding them.
                        //
                        // The height comes from Row(IntrinsicSize.Min): the boxes inside all
                        // fillMaxSize and so contribute none of their own, and without this the
                        // card collapses to the height of the text.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.defaultColors(color = statusColor),
                                onClick = { scope.launch { refresh() } },
                                showIndication = true,
                                pressFeedbackType = PressFeedbackType.Tilt
                            ) {
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .offset(27.dp, 31.dp),
                                        contentAlignment = Alignment.BottomEnd
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(110.dp),
                                            imageVector = if (ok) Icons.Rounded.CheckCircleOutline
                                            else Icons.Rounded.ErrorOutline,
                                            tint = iconTint,
                                            contentDescription = null
                                        )
                                    }
                                    if (workingMode != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp, 10.dp),
                                            contentAlignment = Alignment.BottomStart,
                                        ) {
                                            MiuixText(
                                                text = workingMode,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp, 14.dp),
                                        contentAlignment = Alignment.TopStart,
                                    ) {
                                        Column {
                                            MiuixText(
                                                text = titleText,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Spacer(Modifier.height(1.dp))
                                            MiuixText(
                                                text = lineTwo,
                                                fontSize = 15.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    }
                }

                item {
                    SmallTitle(
                        text = stringResource(R.string.home_device_info),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        Column {
                            BasicComponent(
                                title = stringResource(R.string.home_device_name),
                                summary = deviceName,
                            )
                            BasicComponent(
                                title = stringResource(R.string.home_device_model),
                                summary = deviceModel,
                            )
                            BasicComponent(
                                title = stringResource(R.string.home_hyperos_version),
                                summary = hyperOSVersion,
                            )
                            BasicComponent(
                                title = stringResource(R.string.home_android_version),
                                summary = systemVersion,
                            )
                            BasicComponent(
                                title = stringResource(R.string.home_module_version),
                                summary = moduleVersion,
                            )
                        }
                    }
                }
            }
        }
    }
}

private object SystemVersion {
    /** ro.* properties are the only place the HyperOS version is written down. */
    private fun prop(key: String): String = try {
        @Suppress("PrivateApi")
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    /**
     * The incremental alone: it already starts with the release name (OS4.0.0.35.XPBCNXM), so
     * printing the name in front of it just stutters - "OS4.0 · OS4.0.0.35.XPBCNXM".
     */
    fun hyperOs(fallback: String): String {
        val incremental = prop("ro.mi.os.version.incremental").ifEmpty { Build.DISPLAY }
        if (incremental.isNotEmpty()) return incremental
        val name = prop("ro.mi.os.version.name").ifEmpty { prop("ro.miui.ui.version.name") }
        return name.ifEmpty { fallback }
    }
}

/**
 * The two processes this module lives in, restartable from the one place a user would look for
 * them. They are not settings, so they do not belong on a settings page - they are the "have you
 * tried turning it off and on again" of an Xposed module, which is why they sit in the top bar
 * the way KernelSU puts its reboot menu there.
 *
 * The popup anchors to its parent, hence the Box around the button, and it renders inside this
 * page's Scaffold rather than the root one, whose popup host the app deliberately empties.
 */
@Composable
private fun RestartMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    val entries = listOf(
        stringResource(R.string.restart_systemui) to ModuleBridge::restartSystemUi,
        stringResource(R.string.restart_wallpaper) to ModuleBridge::restartWallpaper,
    )

    Box {
        IconButton(onClick = { showMenu = true }) {
            // Refresh, not RestartAlt: RestartAlt is drawn as two subpaths that stop short of
            // each other at the bottom centre, and at 24dp that gap reads as a piece missing
            // out of the icon rather than as part of the glyph. Refresh is one closed path.
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.restart_menu),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
        OverlayListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
            renderInRootScaffold = false,
        ) {
            ListPopupColumn {
                entries.forEachIndexed { index, entry ->
                    DropdownImpl(
                        text = entry.first,
                        optionSize = entries.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { entry.second() }
                                Toast.makeText(
                                    context,
                                    if (ok) R.string.restart_done else R.string.restart_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }
    }
}
