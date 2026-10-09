/*
 * Layout adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: the status card reports whether the hook answered rather than
 * root/immersion state, and the two figures beside it are this module's own.
 */
package com.os4.musiccover.ui.screen.home

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.os4.musiccover.BuildConfig
import com.os4.musiccover.DonateActivity
import com.os4.musiccover.LsposedService
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.component.DropdownItem
import com.os4.musiccover.ui.component.MenuPopupDefaults
import com.os4.musiccover.ui.util.PageScaffold
import com.os4.musiccover.ui.util.isInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@SuppressLint("LocalContext")
@Composable
fun HomePageView(
    isBlurEnabled: Boolean,
    isCurrent: () -> Boolean,
    resumeKey: Int = 0,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val title = stringResource(R.string.tab_home)

    // These used to run on every re-composition, and two of them are not reads of this process:
    // `Settings.Global` is a ContentProvider call into system_server and `getPackageInfo` was a
    // PackageManager call into it as well. None of the answers can change while the app is up, so
    // each is asked for once.
    val unknown = stringResource(R.string.home_unknown)
    val deviceModel = remember(unknown) { Build.MODEL.ifEmpty { unknown } }
    val deviceName = remember(context, deviceModel) {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: deviceModel
    }
    val systemVersion = remember {
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    }
    val loadingText = stringResource(R.string.home_loading)
    var hyperOSVersion by remember { mutableStateOf(loadingText) }
    LaunchedEffect(Unit) {
        hyperOSVersion = withContext(Dispatchers.IO) { SystemVersion.hyperOs(loadingText) }
    }
    // BuildConfig rather than the installed package's metadata: it is the same string for this
    // build and it costs nothing, where the package manager had to be asked every time.
    val moduleVersion = "v" + BuildConfig.VERSION_NAME

    // Where the card starts. This page is thrown away and built again whenever the pager takes it
    // more than one page from where the user is standing (MainActivity's beyondViewportPageCount),
    // so nothing here survives a trip to About - and the state a rebuilt page would otherwise
    // begin from says the module is dead, which is the red card that used to flash before the
    // answer landed. It starts from the last answer instead, in the same frame the query is sent,
    // and the query's own answer replaces it a broadcast later.
    val last = remember { ModuleBridge.lastAnswer() }
    var state by remember { mutableStateOf(last ?: ModuleBridge.State()) }
    var checked by remember { mutableStateOf(last != null) }

    // Not cleared while a query is in flight: there is an answer on screen either way, and
    // blanking the second line to say so would be the flicker this page already refuses to show.
    suspend fun refresh() {
        state = ModuleBridge.query(context)
        checked = true
    }

    // Asked when this page first comes to the front, and again when the app is resumed with it
    // up - KernelSU's home page does the same (HomeViewModel + HomeScreen: hasActivated, then a
    // refresh on resume). Not on every visit: the answer already held is the one the module would
    // give, the card's own tap re-asks, and a module restarted behind the app's back is what the
    // resume is for. Keyed on the answer rather than on the pager: while this page is not the one
    // on screen the effect is not running, so swiping past it does not cost a query into SystemUI.
    val current = isCurrent()
    var activated by remember { mutableStateOf(last != null) }
    LaunchedEffect(current) {
        if (current && !activated) {
            activated = true
            refresh()
        }
    }
    // The first run of this effect is the composition, not a resume - it is there to arm the one
    // after it, which is what KernelSU's own flag of the same name does (initialResumeHandled).
    // Without it the page would ask twice on the way in, once from each effect.
    var resumed by remember { mutableStateOf(false) }
    LaunchedEffect(resumeKey) {
        if (resumed && activated && current) refresh()
        resumed = true
    }

    // Processes still on the build before this one: installing does not reload a module, and
    // the old one goes on running until its process restarts. Asked with the status, and again
    // once LSPosed's service binds, which can be after the page is up.
    val service by LsposedService.service.collectAsState()
    var stale by remember { mutableStateOf(0) }
    // A restart blanks the screen and stops whatever the scoped apps were doing, so it asks.
    // Asked from the status card, which is the whole width of the page and where a scroll starts.
    var confirmRestart by remember { mutableStateOf(false) }
    LaunchedEffect(current, resumeKey, service) {
        if (current) stale = withContext(Dispatchers.IO) { LsposedService.staleCount() }
    }

    PageScaffold(
        title = title,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        actions = { RestartMenu() },
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
                // Processes left on the build before this one. An answer about the module rather
                // than about this app, so it can be true while the module is not answering at
                // all - and then the card says the more urgent of the two things.
                val staleHere = ok && stale > 0
                // The card is one card with three things to say, and two of them are the same
                // news: the module is not answering, or it is answering in some processes as the
                // version before. Both are red, both wear the cross, and only the words and what
                // tapping it does are different.
                val problem = !ok || staleHere
                val statusColor = if (problem) {
                    when {
                        dynamicColor -> MiuixTheme.colorScheme.errorContainer
                        darkTheme -> Color(0xFF3D1C1C)
                        else -> Color(0xFFFDE8E8)
                    }
                } else {
                    when {
                        dynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                        darkTheme -> Color(0xFF1A3825)
                        else -> Color(0xFFDFFAE4)
                    }
                }
                val iconTint = if (problem) {
                    if (dynamicColor) MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
                    else Color(0xFFDC3545)
                } else {
                    if (dynamicColor) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else Color(0xFF36D167)
                }
                val titleText = when {
                    !ok -> stringResource(R.string.home_status_inactive)
                    staleHere -> stringResource(R.string.home_stale_title)
                    else -> stringResource(R.string.home_status_active)
                }
                // When it is working there is nothing to instruct the user about, so
                // the lines say what is running instead; when it is not, the hint is the
                // one thing worth reading.
                //
                // Neither line reacts to a re-check being in flight. Tapping the card
                // used to blank this one to "loading", and since a re-check keeps the
                // previous answer until the new one arrives, that was a flicker showing
                // nothing the previous answer had not already said.
                val lineTwo = when {
                    checked && !ok -> stringResource(R.string.home_status_inactive_hint)
                    staleHere -> stringResource(R.string.home_stale_summary, stale)
                    else -> moduleVersion
                }
                // The card's bottom-left corner names the app, not the layout: there is
                // only one layout, so a line saying which one it is told the reader
                // nothing, while the name is what someone looking at a stranger's lock
                // screen would want to read off it. Null while the module is not live,
                // because then the hint above is the only line worth having.
                val workingMode = when {
                    !ok -> null
                    staleHere -> stringResource(R.string.home_stale_action)
                    else -> stringResource(R.string.app_name)
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
                        // A card naming processes to restart restarts them, and asks first: a
                        // restart blanks the screen and stops whatever the scoped apps were
                        // doing. Every other answer this card gives is a report, and tapping a
                        // report asks the module again.
                        onClick = {
                            if (staleHere) confirmRestart = true else scope.launch { refresh() }
                        },
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
                                    imageVector = if (problem) Icons.Rounded.ErrorOutline
                                    else Icons.Rounded.CheckCircleOutline,
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

        // No heading over this one. The four rows say what they are - a heading reading
        // "device information" over a list of device facts only repeated them - and the gap it
        // used to hold is now the card's own, so the card sits exactly where it always did.
        // The module's own version was a fifth row and is gone: the status card above already
        // carries it, and the same number twice on one page is one of them to read.
        item {
            Card(
                modifier = Modifier
                    .padding(top = 12.dp)
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
                }
            }
        }

        // Last on the page, under the device list. Asking comes after everything the page was
        // opened for, never before it. It is a row rather than a button because everything else
        // the user can tap in this app is a row, and a lone button here would read as a demand.
        //
        // No top padding of its own: the device card above already carries 12dp underneath it.
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                ArrowPreference(
                    title = stringResource(R.string.donate_title),
                    summary = stringResource(R.string.donate_home_summary),
                    startAction = {
                        Icon(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(28.dp),
                            imageVector = Icons.Rounded.Favorite,
                            // Fixed rather than themed: the heart is the whole signal here, and
                            // in the dynamic-colour scheme it would otherwise take whatever
                            // colour the wallpaper happened to give it.
                            tint = Color(0xFFF2545B),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        context.startActivity(Intent(context, DonateActivity::class.java))
                    },
                )
            }
        }
    }

    WindowDialog(
        show = confirmRestart,
        title = stringResource(R.string.restart_stale),
        summary = stringResource(R.string.restart_confirm_summary),
        onDismissRequest = { confirmRestart = false },
    ) {
        val dismiss = LocalDismissState.current
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.update_cancel),
                onClick = { dismiss?.invoke() },
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.restart_menu),
                onClick = {
                    confirmRestart = false
                    scope.launch {
                        val restarted = withContext(Dispatchers.IO) { ModuleBridge.restartStale() }
                        // Cleared rather than asked again on a success: re-reading the moment
                        // the kills return races the processes' own death and would put the card
                        // back up for a restart that has just worked. A refusal is the opposite
                        // case - something is still on the old build, and the page should go on
                        // saying so.
                        stale = if (restarted) 0
                        else withContext(Dispatchers.IO) { LsposedService.staleCount() }
                    }
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
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
 * The popup anchors to its parent, hence the Box around the button, and it is drawn by the
 * root Scaffold, so it covers the window rather than being clipped to this page.
 */
@Composable
private fun RestartMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    val entries = listOf(
        stringResource(R.string.restart_scope) to { ModuleBridge.restartScope(context) },
        stringResource(R.string.restart_systemui) to ModuleBridge::restartSystemUi,
        stringResource(R.string.restart_wallpaper) to ModuleBridge::restartWallpaper,
    )

    Box {
        // holdDownState keeps the button looking pressed while its menu is open, which is
        // the one thing tying the two together now that the popup is drawn by the root.
        IconButton(onClick = { showMenu = true }, holdDownState = showMenu) {
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
            popupPositionProvider = MenuPopupDefaults.MenuPositionProvider,
            alignment = PopupPositionProvider.Align.TopEnd,
            onDismissRequest = { showMenu = false },
        ) {
            ListPopupColumn {
                entries.forEachIndexed { index, entry ->
                    DropdownItem(
                        text = entry.first,
                        optionSize = entries.size,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            // No result is reported. A Toast is not a channel this app has - they
                            // are dropped whenever notifications are off, which is this app's
                            // default - and the restart is its own feedback: the screen blinks and
                            // the lock screen comes back.
                            scope.launch { withContext(Dispatchers.IO) { entry.second() } }
                        },
                    )
                }
            }
        }
    }
}
