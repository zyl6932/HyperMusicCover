package com.os4.musiccover.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.os4.musiccover.CoverActivity
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ShadeActivity
import com.os4.musiccover.MiniPlayerActivity
import com.os4.musiccover.ui.util.PageScaffold
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * The features tab: a card per feature, each opening a screen of its own.
 *
 * The tab lists rather than controls because its two subjects have nothing to do with each
 * other - one is what the lock screen shows, the other is what happens over the desktop - and
 * between them they are long enough that the second would have been buried under the first.
 *
 * Each card starts an Activity, which is what `AboutPage` does to reach the licence list and
 * what `LicenseActivity` therefore already established as the house pattern for a sub-screen.
 * The transition, the back gesture and the predictive-back animation on Android 13+ are the
 * platform's; a sub-page swapped in place here would have had to imitate all three.
 */
@Composable
fun FeaturesPageView(
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    FeatureList(isBlurEnabled, extraBottomPadding) { target ->
        // An Activity, not a page swapped in place - the same thing AboutPage does to reach the
        // licence list, and the reason is the transition: a whole screen arriving is the
        // platform's own animation, it brings its own back handling, and nothing here has to
        // imitate any of it. A sub-page animated inside this one only ever approximates that.
        context.startActivity(Intent(context, target))
    }
}

/**
 * The tab itself: one card per feature, each opening a screen of its own.
 *
 * This used to BE the lock screen page. It became a list when the notification shade arrived,
 * because the two have nothing to do with each other - one is what the lock screen shows, the
 * other is what happens over the desktop - and between them they are long enough that the second
 * would have been buried under the first.
 */
@Composable
private fun FeatureList(
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp,
    onOpen: (Class<*>) -> Unit,
) {
    PageScaffold(
        title = stringResource(R.string.tab_features),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item {
            Column {
                // The 12dp under the bar that every other list page in this app leaves. It was
                // missing on this one card, which put it flush against the title.
                // The order is the user's (2026-09-30): the islands, the cover, the shade.
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.features_mini_title),
                        summary = stringResource(R.string.features_mini_summary),
                        onClick = { onOpen(MiniPlayerActivity::class.java) },
                    )
                }
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.features_cover_title),
                        summary = stringResource(R.string.features_cover_summary),
                        onClick = { onOpen(CoverActivity::class.java) },
                    )
                }
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.features_shade_title),
                        summary = stringResource(R.string.features_shade_summary),
                        onClick = { onOpen(ShadeActivity::class.java) },
                    )
                }
            }
        }
    }
}

/**
 * Everything the module can be told about the lock screen, with what cover mode is played above.
 *
 * These are not app preferences: each one is a command to the hook inside SystemUI, and the
 * values shown are the ones it reports back. The picture above them is CoverDemo, the 趣味拟物
 * style animation that replaced the live composite on 2026-09-30 (user): it plays what cover mode
 * does, and still follows the three settings here that change the look - the style, where a
 * full-screen cover sits, and how small the clock goes.
 *
 * Two decisions about the layout, both departures from KernelSU's colour-palette screen this
 * follows:
 *
 * - **The preview is outside the scrolling area.** KernelSU makes its preview the first item of
 *   the list, which scrolls away; it can afford to because its controls are short. Here the
 *   preview answers the sliders under it, so scrolling it off the screen would defeat the page.
 * - **The tabs swap the controls in place, they do not navigate.** So there is one preview for
 *   the whole page rather than one per group, which is also the rule for the app as a whole:
 *   never two live previews on one screen, because that is two copies of a state to keep in
 *   step for no gain.
 *
 * Deliberately absent are the things that should never need a button. Hiding the wallpaper's
 * subject cut-out and giving the lock screen its own wallpaper are not choices - without them
 * cover mode renders wrong - so the module does both on its own.
 */
@Composable
internal fun CoverPageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var module by remember { mutableStateOf(ModuleBridge.State()) }
    var group by remember { mutableIntStateOf(0) }

    // Re-asked until it answers: this screen is reached straight after "重启全部作用域" as often
    // as not, and a single query then lands before SystemUI has a receiver - leaving every
    // control greyed out and every value at its default for as long as the screen stays open.
    // Asked again whenever the module is seen to go - a setting it did not acknowledge, or the
    // poll below going unanswered - and greyed out until it answers: the values on screen are
    // then only the last ones heard, and a switch moved on them would go nowhere.
    val lost by ModuleBridge.lost.collectAsState()
    LaunchedEffect(lost) { if (lost > 0) module = module.copy(alive = false) }
    LaunchedEffect(refreshKey, lost) { module = ModuleBridge.queryAlive(context) }
    // Polled while the page is open for the three things the rows under the picture read and
    // that change without a settings change: whether the clock style has glass, which route the
    // lyric came from, and the clock's size while nothing has set it. The reply is the old live
    // preview's, asked for without its pictures: this page draws none of them, and each one is
    // a software draw on SystemUI's main thread every poll.
    var artTrack by remember { mutableStateOf("") }
    LaunchedEffect(refreshKey, module.alive) {
        if (!module.alive) return@LaunchedEffect
        while (true) {
            val reply = ModuleBridge.preview(context, artTrack, shortcuts = false, shots = false)
            if (!reply.alive) {
                ModuleBridge.markLost()
                return@LaunchedEffect
            }
            if (!reply.artUnchanged) artTrack = reply.track
            if (reply.clockHasGlass != module.clockHasGlass) {
                module = module.copy(clockHasGlass = reply.clockHasGlass)
            }
            // Which route the words came from changes mid-track - the file answers first and the
            // network lands after it, or the session's own payload turns up last. Empty means a
            // module too old to send it.
            if (reply.lyricSource.isNotEmpty() && reply.lyricSource != module.lyricSource) {
                module = module.copy(lyricSource = reply.lyricSource)
            }
            // Only while the slider has never been moved: the size is then the dp default in
            // the style's terms, and it changes with the style. Once set, the slider owns it.
            if (module.clockSize <= 0f && reply.clockSize > 0f) {
                module = module.copy(clockSize = reply.clockSize)
            }
            delay(SHOT_POLL_MS)
        }
    }

    val enabled = module.alive
    // The cover has one setting of its own, too few for a tab, and it is about the same picture
    // the clock sits on - so the two share one. The lyrics get the third.
    val groups = listOf(
        stringResource(R.string.cover_clock_section),
        stringResource(R.string.card_section),
        stringResource(R.string.lyrics_section),
    )

    PageScaffold(
        title = stringResource(R.string.features_cover_title),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onBack = onBack,
        pinned = {
            Spacer(Modifier.height(8.dp))
            // Framed as the islands' page frames its demonstration: a card, three plays in it.
            Card(Modifier.padding(horizontal = 12.dp)) {
                CoverDemo(
                    coverStyle = module.coverStyle,
                    bias = module.bias,
                    clockSize = module.clockSize,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            TabRow(
                tabs = groups,
                selectedTabIndex = group,
                onTabSelected = { group = it },
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            )
        },
    ) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
            ) {
                when (group) {
                    0 -> Column {
                        CoverGroup(enabled, module) { module = it }
                        ClockGroup(enabled, module) { module = it }
                    }
                    1 -> CardGroup(enabled, module) { module = it }
                    else -> LyricsGroup(enabled, module) { module = it }
                }
            }
        }
    }
}

@Composable
private fun CoverGroup(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    Column {
        WindowDropdownPreference(
            title = stringResource(R.string.cover_style),
            items = listOf(stringResource(R.string.cover_style_full),
                stringResource(R.string.cover_style_card)),
            selectedIndex = module.coverStyle.coerceIn(0, 1),
            enabled = enabled,
            onSelectedIndexChange = {
                onChange(module.copy(coverStyle = it))
                ModuleBridge.setCoverStyle(context, "mode", it.toFloat())
            },
        )
        // The square's size, place and corners were sliders here and are fixed - it fills the room
        // it has, sits in the middle of it, and takes the media card's own corner radius, all of
        // which are the same answer on every device. See CoverCardStyle.
        //
        // The one slider left belongs to the full-screen cover alone, and it is the one that has
        // to stay a slider: where the cover sits vertically is a share of the screen, and the
        // screen is not the same on every phone.
        if (module.coverStyle == 0) {
            ValueSlider(
                title = stringResource(R.string.cover_bias),
                value = module.bias,
                valueRange = 0f..1f,
                enabled = enabled,
                onValueChange = {
                    onChange(module.copy(bias = it))
                    ModuleBridge.setBias(context, it)
                },
            )
        }
    }
}

@Composable
private fun ClockGroup(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    Column {
        // A fraction of the style's own full clock, the one shown with cover mode off. The
        // collapse cannot make a clock bigger than that, so 100% is the top. Cut with the others
        // below on 2026-09-28 and put back on its own two days later (user).
        ValueSlider(
            title = stringResource(R.string.clock_size),
            value = (if (module.clockSize > 0f) module.clockSize else DEFAULT_CLOCK_SIZE)
                .coerceIn(CLOCK_SIZE_MIN, 1f),
            valueRange = CLOCK_SIZE_MIN..1f,
            enabled = enabled,
            label = { "${(it * 100f).roundToInt()}%" },
            onValueChange = {
                val size = (it * 100f).roundToInt() / 100f
                onChange(module.copy(clockSize = size))
                ModuleBridge.setClockSize(context, size)
            },
        )
        // Three rows were here and are gone: how far the date and the clock are moved together,
        // how solid the liquid-glass styles go, and the spring the whole transition runs on. All
        // three are fixed now - the first at the value cover mode has always been tuned to, the
        // second at the OEM's own ramp end, and the spring at the OEM's preset for this
        // transition, which is also what the wallpaper's crossfade is derived from. A fourth, the
        // full-screen AOD keeping cover mode's small clock, is fixed on. See Main and LyricStyle
        // for each value.
        //
        // Not a cover setting and not tied to cover mode: it is the clock the lock screen always
        // has. It sits here because this is the page about the clock, and it is a switch rather
        // than something always on because it changes what the clock looks like - which the
        // other restrictions this module lifts do not.
        //
        // The summary is the switch's own caveat, asked for by the user: the hook is on the
        // clock's isColonShow, so it can only speak for a style that has a colon to show.
        SwitchPreference(
            title = stringResource(R.string.clock_force_colon),
            summary = stringResource(R.string.clock_force_colon_summary),
            checked = module.forceColon,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(forceColon = it))
                ModuleBridge.setForceColon(context, it)
            },
        )
    }
}

@Composable
private fun LyricsGroup(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    // Whether a provider module is installed is a fact about the package list and does not
    // change while the page is open; whether one is working comes from the module.
    val providerInstalled = remember {
        LYRIC_PROVIDERS.any {
            try {
                context.packageManager.getPackageInfo(it, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
    // What this row says, and there are two things it can be saying.
    //
    // The one it says nearly always is WHERE the words on the lock screen came from. The module
    // tries its routes best-first - the session's own lyricInfo, the Lyricon bridge, the song's
    // own file, then two catalogues over the network - and reports which one answered; the row
    // names it. That is a better answer than the one this row used to give, which was whether
    // LyricInfo was installed. A phone with no LyricInfo at all still gets lyrics from its own
    // files and from the network, and a phone that has it still gets most of them elsewhere: the
    // old row warned about a problem that in practice was not one, in red, on a working phone.
    //
    // The other is the one case worth a warning: neither LyricInfo nor a Lyricon central is on
    // the phone, so nothing here reads a player's own lyric and every word has to come from a
    // file or a lookup. Both are checked because they are two different modules doing the same
    // job - LyricInfo hooks eight players, Lyricon takes whatever publishes to it - and either
    // one is enough.
    val missingModule = !providerInstalled && !module.lyriconInstalled
    val source = lyricSourceLabel(module.lyricSource)
    val title = when {
        missingModule -> stringResource(R.string.lyrics_provider_title_missing)
        source == null -> stringResource(R.string.lyrics_source_none)
        else -> stringResource(R.string.lyrics_source, source)
    }
    val summary = if (missingModule) R.string.lyrics_provider_missing
                  else R.string.lyrics_provider_ready
    // Said twice, because once was not enough. The row standing at the top of the group is
    // always there; this is the interruption, and it is only worth interrupting for the one
    // state that asks for something to be done.
    var showNotice by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(PROVIDER_NOTICE_SECONDS) }
    LaunchedEffect(missingModule, module.alive) {
        // Only once the module has answered: before that every field reads as its default, and
        // "there is no provider on this phone" would be the state of one that had simply not
        // been asked yet.
        if (missingModule && module.alive && !LyricsNotice.seen(context)) {
            showNotice = true
        }
    }
    LaunchedEffect(showNotice) {
        if (!showNotice) return@LaunchedEffect
        countdown = PROVIDER_NOTICE_SECONDS
        while (countdown > 0) {
            delay(1_000)
            countdown--
        }
    }
    WindowDialog(
        show = showNotice,
        title = title,
        summary = stringResource(summary),
        // Held for fifteen seconds, and held means held: an outside tap and the back gesture
        // both come through here, so there is no way out of it before the button unlocks. A
        // notice asking for a module to be installed is read by nobody if it can be flicked
        // away, and on most phones this one has already been flicked away once.
        onDismissRequest = {
            if (countdown == 0) {
                showNotice = false
                LyricsNotice.markSeen(context)
            }
        },
    ) {
        val dismiss = LocalDismissState.current
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            text = if (countdown > 0) {
                stringResource(R.string.lyrics_provider_got_it_wait, countdown)
            } else {
                stringResource(R.string.lyrics_provider_got_it)
            },
            enabled = countdown == 0,
            onClick = { dismiss?.invoke() },
        )
    }
    Column {
        // The standing half of the same statement. It never goes away and it never asks to be
        // dismissed, which is what makes it useful on the visit after the notice was flicked
        // away - or on a phone where the module had not answered yet when the notice was due.
        BasicComponent(
            title = title,
            titleColor = BasicComponentDefaults.titleColor(
                color = if (missingModule) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.onBackground,
            ),
            summary = stringResource(summary),
        )
        // The one way the lock screen is asked for the cover instead, now that the lyrics have no
        // switch of their own. A row rather than a summary on one of the switches below: none of
        // them is about the cover, and this is not a description of any of them.
        BasicComponent(title = stringResource(R.string.lyrics_two_finger_tap))
        // The lyrics themselves are not a setting any more: they are drawn whenever the track has
        // a lyric, and the two-finger tap on the lock screen is how the cover is asked for
        // instead. Neither is the band they are drawn in - its height, its place in the room, the
        // margin the lines are held inside and the size and weight of the main line are all fixed
        // now (see LyricStyle). The one thing about where the words go that is still the user's
        // is where they settle between those margins; the switches below are about the words
        // themselves, so all three stay.
        val alignModes = listOf(
            stringResource(R.string.lyrics_align_left),
            stringResource(R.string.lyrics_align_center),
            stringResource(R.string.lyrics_align_right),
        )
        WindowDropdownPreference(
            title = stringResource(R.string.lyrics_align),
            items = alignModes,
            selectedIndex = module.lyricsAlign.coerceIn(0, alignModes.lastIndex),
            enabled = enabled,
            onSelectedIndexChange = {
                onChange(module.copy(lyricsAlign = it))
                ModuleBridge.setLyricsAlign(context, it)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.lyrics_trans),
            checked = module.lyricsTrans,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(lyricsTrans = it))
                ModuleBridge.setLyricsTrans(context, it)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.lyrics_hide_aod),
            summary = stringResource(R.string.lyrics_hide_aod_summary),
            checked = module.lyricsHideAod,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(lyricsHideAod = it))
                ModuleBridge.setLyricsHideAod(context, it)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.lyrics_hdr),
            summary = stringResource(R.string.lyrics_hdr_summary),
            checked = module.lyricsHdr,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(lyricsHdr = it))
                ModuleBridge.setLyricsHdr(context, it)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.lyrics_keep_on),
            checked = module.lyricsKeepOn,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(lyricsKeepOn = it))
                ModuleBridge.setLyricsKeepOn(context, it)
            },
        )
    }
}

/**
 * The media card group. Everything else on this page adjusts something the module was already
 * doing; this is the one place it reaches into the OEM's card, so both switches default to off
 * and both only apply while cover mode is on.
 */
@Composable
private fun CardGroup(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    Column {
        // Two switches were here and are gone: hiding the card's thumbnail, and bringing it back
        // while the lyrics are up. Both are on and neither is a setting - the artwork is the
        // wallpaper in this mode, so the thumbnail is a second copy of it, and the exception that
        // shows it again is what the lyrics being up is for. See Main.sMcHideArt.
        //
        // Requested for a reason of its own: on this card the real play/pause button sits over
        // the fingerprint sensor, so a thumb aiming for it unlocks the phone instead. The title
        // is the one part of the card that is both big and far from the sensor.
        SwitchPreference(
            title = stringResource(R.string.card_title_tap),
            checked = module.mcTitleTap,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(mcTitleTap = it))
                ModuleBridge.setCardTitleTap(context, it)
            },
        )
        // Sits here because that is where it was asked for, but it is not a card setting and does
        // not follow cover mode, unlike the switches above it - which the rows no longer say, so
        // it is only in the module and in this comment. No onCardRestyled(): the preview above
        // draws no fingerprint.
        SwitchPreference(
            title = stringResource(R.string.hide_fingerprint),
            checked = module.hideFingerprint,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(hideFingerprint = it))
                ModuleBridge.setHideFingerprint(context, it)
            },
        )
        // Three states rather than a switch: "off" would have to mean both "stop reserving the
        // space" and "reserve it even with no print enrolled", which are opposite requests.
        //
        // The two directions are named differently on the two sides of the wire: the module and
        // the OEM call it avoidance, because that is what the fingerprint icon makes the
        // notification do - move up - and the hook's own job is to override it. The rows say
        // what the notification does instead, so "sinks" is avoidance switched off: item 1 is
        // the module's fpavoid=1 and item 2 its fpavoid=2. Change the order here and the
        // setting silently means the opposite of what it says.
        val avoidModes = listOf(
            stringResource(R.string.fp_sink_system),
            stringResource(R.string.fp_sink_always),
            stringResource(R.string.fp_sink_never),
        )
        val avoidIndex = module.fpAvoid.coerceIn(0, avoidModes.lastIndex)
        WindowDropdownPreference(
            title = stringResource(R.string.fp_sink),
            items = avoidModes,
            selectedIndex = avoidIndex,
            enabled = enabled,
            onSelectedIndexChange = {
                onChange(module.copy(fpAvoid = it))
                ModuleBridge.setFingerprintAvoid(context, it)
            },
        )
    }
}

/**
 * A slider with its current value printed opposite the title. Without the number there is no way
 * to tell where you have dragged to, which matters here because these values get compared against
 * ones written down in the notes.
 *
 * Shared with ShadePage, which is why it is `internal` rather than private to this file: both
 * pages adjust module settings the same way, and a second slider that looked almost the same was
 * the first thing a reviewer noticed.
 *
 * [detent] is a single value on the track that ticks as it is passed - the app's only one, and it
 * exists because a slider whose default is one number among many is otherwise impossible to find
 * again by hand. It is miuix's own key point rather than a comparison of this frame's value
 * against the last, so the tick is the library's and behaves the way every other miuix slider's
 * does.
 */
@Composable
internal fun ValueSlider(
    title: String,
    summary: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    detent: Float? = null,
    label: (Float) -> String = ::format,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicComponent(
            title = title,
            summary = summary,
            enabled = enabled,
            endActions = {
                MiuixText(
                    text = label(value),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            // Step is what makes a key point produce a tick at all; the default effect only fires
            // at the two ends of the track (SliderHapticEffect.Edge), and those ends keep firing
            // either way, because the edge haptic is played before the key point is looked at.
            hapticEffect = if (detent != null) SliderDefaults.SliderHapticEffect.Step
                           else SliderDefaults.DefaultHapticEffect,
            keyPoints = detent?.let { listOf(it) },
            // The library's magnet would pull the value onto the key point from 2% of the range
            // away, which is a snap rather than a tick, and it would take the values just either
            // side of the detent out of what this slider can be set to. Off, deliberately: the
            // detent is there to be felt, not to stop the finger short.
            magnetThreshold = 0f,
        )
    }
}

/**
 * How often the page re-asks the module for what its rows show. Long enough to be invisible on
 * the battery, short enough that a skipped track catches up before it is worth wondering about.
 */
private const val SHOT_POLL_MS = 3000L

/** The clock size's floor and default, matching CLOCK_SIZE_MIN and DEFAULT_CLOCK_SIZE in Main. */
private const val CLOCK_SIZE_MIN = 0.05f
private const val DEFAULT_CLOCK_SIZE = 0.09f

/** Two decimals, without dragging java.util.Formatter's locale into it. */
private fun format(v: Float): String {
    val hundredths = (v * 100f).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/**
 * Provider modules that write a whole lyric to the media session, where we read it.
 *
 * Both variants of LyricInfo. Declared in the manifest's <queries> as well, or the lookup throws
 * NameNotFound on Android 11 and up whether or not the package is installed.
 */
private val LYRIC_PROVIDERS = listOf(
    "com.lidesheng.lyricinfo",
    "com.lidesheng.lyricinfo.lite",
)

/** How long the provider notice holds its own dismiss button, in seconds. */
private const val PROVIDER_NOTICE_SECONDS = 15

/**
 * The route the module named, as something to read on the row - or null when it named none, or
 * named one this build has never heard of, which is what a newer module's new route looks like
 * from here. A route with no name is shown as "no source" rather than as a raw word: a word out
 * of LockLyrics.srcName is not something to put in front of a user.
 *
 * The names are the app's, not the module's, and deliberately so: "qq" is what the code calls it
 * and "QQ 音乐" is what a person calls it, and the mapping is the kind of thing that belongs on
 * the side that draws the screen.
 */
@Composable
private fun lyricSourceLabel(source: String): String? = when (source) {
    "session" -> stringResource(R.string.lyrics_source_session)
    "lyricon" -> stringResource(R.string.lyrics_source_lyricon)
    "local" -> stringResource(R.string.lyrics_source_local)
    "amll" -> stringResource(R.string.lyrics_source_amll)
    "ttmlhub" -> stringResource(R.string.lyrics_source_ttmlhub)
    "netease" -> stringResource(R.string.lyrics_source_netease)
    "qq" -> stringResource(R.string.lyrics_source_qq)
    "kugou" -> stringResource(R.string.lyrics_source_kugou)
    "kuwo" -> stringResource(R.string.lyrics_source_kuwo)
    "lrclib" -> stringResource(R.string.lyrics_source_lrclib)
    else -> null
}

/**
 * Remembers that the lyric-provider notice has been dismissed.
 *
 * Marked when the notice closes rather than when it opens, so a phone that was locked or a page
 * that was left before the button unlocked has not "been told" - the fifteen seconds it holds
 * for are the point, and cutting them short is not reading it.
 *
 * Belongs to the app rather than the module: it is about what this phone's owner has been told,
 * not about how the lock screen behaves, and it has to survive the module being restarted.
 */
private object LyricsNotice {
    private const val PREFS_NAME = "lyrics_notice"
    private const val KEY_SEEN = "provider_seen"

    fun seen(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_SEEN, false)

    fun markSeen(context: android.content.Context) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SEEN, true) }
    }
}
