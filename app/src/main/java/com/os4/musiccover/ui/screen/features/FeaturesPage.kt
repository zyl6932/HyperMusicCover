package com.os4.musiccover.ui.screen.features

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
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
 * Everything the module can be told about the lock screen, with a picture of it above.
 *
 * These are not app preferences: each one is a command to the hook inside SystemUI, and the
 * values shown are the ones it reports back. The preview is drawn from the same values, so a
 * drag can be judged here instead of by locking the phone after every change.
 *
 * Two decisions about the layout, both departures from KernelSU's colour-palette screen this
 * follows:
 *
 * - **The preview is outside the scrolling area.** KernelSU makes its preview the first item of
 *   the list, which scrolls away; it can afford to because its controls are short. Here the
 *   preview is the thing being adjusted, so scrolling it off the screen would defeat the page.
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
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var shots by remember { mutableStateOf(ModuleBridge.Preview()) }
    var group by remember { mutableIntStateOf(0) }
    // Bumped when a switch changes the card, to re-take the picture without waiting for the tick.
    var shotNonce by remember { mutableIntStateOf(0) }

    // Re-asked until it answers: this screen is reached straight after "重启全部作用域" as often
    // as not, and a single query then lands before SystemUI has a receiver - leaving every
    // control greyed out and every value at its default for as long as the screen stays open.
    LaunchedEffect(refreshKey) { module = ModuleBridge.queryAlive(context) }
    // The pictures are asked for on their own and polled rather than fetched once: skipping a
    // track with this page open would otherwise leave the preview showing the previous album,
    // and the card's seek bar would sit still. The poll stays small because the module answers
    // "same song" instead of resending the artwork, and it deliberately does NOT re-read the
    // settings - a query landing mid-drag would snap a slider back to whatever the module had
    // applied a moment ago.
    var artTrack by remember { mutableStateOf("") }
    LaunchedEffect(refreshKey, module.alive, shotNonce) {
        if (!module.alive) {
            art = null
            shots = ModuleBridge.Preview()
            return@LaunchedEffect
        }
        // After a switch, the module needs a frame to re-style the card before it is worth
        // photographing.
        if (shotNonce > 0) delay(SETTLE_MS)
        while (true) {
            // The shortcuts never change, so they are fetched once and then carried forward.
            val want = shots.left == null || shots.right == null
            val reply = ModuleBridge.preview(context, artTrack, want)
            if (!reply.artUnchanged) {
                art = reply.art
                artTrack = reply.track
            }
            // The clock's geometry rides along with the pictures. Merged into the state rather
            // than replacing it: a poll must never write back bias or the two clock values,
            // which the user may be dragging at this very moment.
            // Not inside the geometry block above: that one only runs when the clock could
            // be measured, and this has to reach the settings page on a style whose clock it
            // could not be measured on - which is exactly when the slider looks wrong.
            if (reply.clockHasGlass != module.clockHasGlass) {
                module = module.copy(clockHasGlass = reply.clockHasGlass)
            }
            reply.clockGeometry?.let { g ->
                if (g != clockGeometryOf(module)) {
                    module = module.copy(
                        geometry = module.geometry.copy(
                            clockW = g.clockW,
                            clockH = g.clockH,
                            clockY = g.clockY,
                            clockPad = g.clockPad,
                            clockX = g.clockX,
                            clockPivotX = g.clockPivotX,
                            clockFull = g.clockFull,
                        )
                    )
                }
            }
            // Only while the slider has never been moved: the size is then the dp default in
            // the style's terms, and it changes with the style. Once set, the slider owns it.
            if (module.clockSize <= 0f && reply.clockSize > 0f) {
                module = module.copy(clockSize = reply.clockSize)
            }
            shots = ModuleBridge.Preview(
                card = reply.card ?: shots.card,
                cardRadius = if (reply.cardRadius > 0f) reply.cardRadius else shots.cardRadius,
                // Carried straight through, null included: no slot means the artwork is hidden.
                artSlot = reply.artSlot,
                clockHour = reply.clockHour ?: shots.clockHour,
                clockMinute = reply.clockMinute ?: shots.clockMinute,
                date = reply.date ?: shots.date,
                left = reply.left ?: shots.left,
                right = reply.right ?: shots.right,
            )
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
            Spacer(Modifier.height(24.dp))
            LockPreview(
                art = art,
                bias = module.bias,
                coverStyle = module.coverStyle,
                coverCardFill = module.coverCardFill,
                coverCardPos = module.coverCardPos,
                coverCardCorner = module.coverCardCorner,
                clockHeightDp = module.clockHeightDp,
                clockSize = module.clockSize,
                clockOffsetDp = module.clockOffsetDp,
                glassEnd = module.glassEnd,
                geometry = module.geometry,
                card = shots.card,
                cardRadius = shots.cardRadius,
                artSlot = shots.artSlot,
                cardHideArt = module.mcHideArt,
                clockHour = shots.clockHour,
                clockMinute = shots.clockMinute,
                date = shots.date,
                leftShortcut = shots.left,
                rightShortcut = shots.right,
            )
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
                    1 -> CardGroup(enabled, module, { module = it }) {
                        shotNonce++
                    }
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
        } else {
            ValueSlider(
                title = stringResource(R.string.cover_card_size),
                value = module.coverCardFill.coerceIn(0.4f, 1f),
                valueRange = 0.4f..1f,
                enabled = enabled,
                label = { "${(it * 100).roundToInt()}%" },
                onValueChange = {
                    val value = (it * 100).roundToInt() / 100f
                    onChange(module.copy(coverCardFill = value))
                    ModuleBridge.setCoverStyle(context, "fill", value)
                },
            )
            val top = stringResource(R.string.cover_card_pos_top)
            val centre = stringResource(R.string.cover_card_pos_centre)
            val bottom = stringResource(R.string.cover_card_pos_bottom)
            ValueSlider(
                title = stringResource(R.string.cover_card_pos),
                value = module.coverCardPos.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                // A card that fills its room has no height left to move in.
                enabled = enabled && module.coverCardFill < 1f,
                detent = 0.5f,
                label = {
                    when ((it * 100).roundToInt()) {
                        0 -> top
                        50 -> centre
                        100 -> bottom
                        else -> "${(it * 100).roundToInt()}%"
                    }
                },
                onValueChange = {
                    val value = (it * 100).roundToInt() / 100f
                    onChange(module.copy(coverCardPos = value))
                    ModuleBridge.setCoverStyle(context, "pos", value)
                },
            )
            val round = stringResource(R.string.cover_card_corner_round)
            ValueSlider(
                title = stringResource(R.string.cover_card_corner),
                value = module.coverCardCorner.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                enabled = enabled,
                // The corner it had before it was a setting.
                detent = 0.12f,
                label = {
                    val percent = (it * 100).roundToInt()
                    if (percent == 100) round else "$percent%"
                },
                onValueChange = {
                    val value = (it * 100).roundToInt() / 100f
                    onChange(module.copy(coverCardCorner = value))
                    ModuleBridge.setCoverStyle(context, "corner", value)
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
        // Where the date and the clock sit, moved as one block from where cover mode puts them.
        ValueSlider(
            title = stringResource(R.string.clock_height),
            value = module.clockOffsetDp.coerceIn(CLOCK_OFFSET_MIN_DP, CLOCK_OFFSET_MAX_DP),
            valueRange = CLOCK_OFFSET_MIN_DP..CLOCK_OFFSET_MAX_DP,
            enabled = enabled,
            label = { "${it.roundToInt()} dp" },
            onValueChange = {
                // Whole dp: a fraction of one is invisible, and the number reads cleaner.
                val dp = it.roundToInt().toFloat()
                onChange(module.copy(clockOffsetDp = dp))
                ModuleBridge.setClockOffset(context, dp)
            },
        )
        // A fraction of the style's own full clock, the one shown with cover mode off. The
        // collapse cannot make a clock bigger than that, so 100% is the top.
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
        // Not a cover setting either, in the same way the colon switch below is not: it is about
        // the clock the lock screen is showing when the display goes off. About the FULL-SCREEN
        // always-on display only - the plain AOD is left as the system draws it, and the row no
        // longer says which of the two it means, so it is worth saying here. Nothing to
        // re-apply: the module reads it when the screen falls asleep.
        SwitchPreference(
            title = stringResource(R.string.clock_aod_small),
            checked = module.aodSmall,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(aodSmall = it))
                ModuleBridge.setAodSmall(context, it)
            },
        )
        // The spring the whole transition runs on. The number is miuix's response time in
        // seconds and it is not flipped, because the label is a description of feel rather than
        // of the unit: dragging right slows the spring down, and a slower spring with the same
        // damping ratio is the one that reads as heavier. Zeta is fixed at 0.88 on the module
        // side and is not on this slider.
        ValueSlider(
            title = stringResource(R.string.clock_response),
            value = module.clockResponse.coerceIn(CLOCK_RESPONSE_MIN, CLOCK_RESPONSE_MAX),
            valueRange = CLOCK_RESPONSE_MIN..CLOCK_RESPONSE_MAX,
            enabled = enabled,
            // The one detent in the app: this slider's own default, and the value every note
            // about this transition quotes. Nothing is printed for it - the tick is the only
            // mark, and it is there so the default can be found again without reading the
            // number off the row.
            detent = DEFAULT_CLOCK_RESPONSE,
            onValueChange = {
                onChange(module.copy(clockResponse = it))
                ModuleBridge.setClockResponse(context, it)
            },
        )
        // Shown inverted. The module stores the OEM's own number, where updateGlassValue(0) is
        // transparent refracting glass and (1) is a solid fill - so as "glass strength" it runs
        // backwards, and dragging right made the effect weaker. The stored value, the adb
        // glassend op and the exported JSON all keep the OEM's meaning; only this slider is
        // flipped.
        // Off on the styles whose clock has no glass to morph. The morph is
        // AllInOneBase.updateGlassValue(float) - the OEM's own ramp from refracting glass to a
        // solid fill - and the rhombus, doodle, oriental and magazine clocks have no such thing:
        // vector digits, bitmaps and plain text, so there is nothing for this slider to move.
        // It used to say so in a summary; this page carries none now, by the user's choice.
        val glassAvailable = module.clockHasGlass
        ValueSlider(
            title = stringResource(R.string.clock_glass),
            value = 1f - module.glassEnd,
            valueRange = 0f..1f,
            enabled = enabled && glassAvailable,
            onValueChange = {
                val glassEnd = 1f - it
                onChange(module.copy(glassEnd = glassEnd))
                ModuleBridge.setGlassEnd(context, glassEnd)
            },
        )
        // Not a cover setting and not tied to cover mode: it is the clock the lock screen always
        // has. It sits here because this is the page about the clock, and it is a switch rather
        // than something always on because it changes what the clock looks like - which the
        // other restrictions this module lifts do not.
        SwitchPreference(
            title = stringResource(R.string.clock_force_colon),
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
    // Three states rather than two, and the difference between the last two is the one worth
    // showing: installed is not the same question as working. LyricInfo is an LSPosed module,
    // and one that is installed but not enabled - or enabled without the player in its scope -
    // writes nothing while still sitting in the package list. The package manager cannot tell
    // those apart; whether a session has carried its lyric is what does, and only the module
    // knows that.
    //
    // Which player is playing is the other half of the question, and it is the half that was
    // missing at first. A session lyric is only ever going to appear for a player LyricInfo
    // knows (see LYRICINFO_PLAYERS); on any other one the lyrics come from the network instead
    // and "no session lyric" is the normal state of a working phone. Reporting that as "not
    // working" blames the module for something it never claimed to do - and it is not a corner
    // case, it is every song on Apple Music.
    val provider = when {
        !providerInstalled -> ProviderNotice.Missing
        module.sessionLyric -> ProviderNotice.Ready
        module.player in LYRICINFO_PLAYERS -> ProviderNotice.Inactive
        else -> ProviderNotice.Ready
    }
    // Said twice, because once was not enough. The row standing at the top of the group is
    // always there; this is the interruption, and it is only worth interrupting for the two
    // states that need something done about them.
    var showNotice by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(PROVIDER_NOTICE_SECONDS) }
    LaunchedEffect(provider, module.alive) {
        // Only once the module has answered: before that every field reads as its default, and
        // "no lyric has ever arrived" would be the state of a phone that had simply not been
        // asked yet.
        if (provider != ProviderNotice.Ready && module.alive && !LyricsNotice.seen(context)) {
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
        title = stringResource(provider.title),
        summary = stringResource(provider.summary),
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
            title = stringResource(provider.title),
            titleColor = BasicComponentDefaults.titleColor(
                color = if (provider.warning) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.onBackground,
            ),
            summary = stringResource(provider.summary),
        )
        SwitchPreference(
            title = stringResource(R.string.lock_lyrics),
            summary = stringResource(R.string.lock_lyrics_summary),
            checked = module.lyrics,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(lyrics = it))
                ModuleBridge.setLyrics(context, it)
            },
        )
        // The same two shares as the square card: how much of the room between the clock and
        // the media card the band takes, and where it sits in the rest.
        ValueSlider(
            title = stringResource(R.string.lyric_band_fill),
            summary = stringResource(R.string.lyric_band_fill_summary),
            value = module.lyricFill.coerceIn(0.4f, 1f),
            valueRange = 0.4f..1f,
            enabled = enabled && module.lyrics,
            label = { "${(it * 100).roundToInt()}%" },
            onValueChange = {
                val value = (it * 100).roundToInt() / 100f
                onChange(module.copy(lyricFill = value))
                ModuleBridge.setLyricStyle(context, "fill", value)
            },
        )
        val top = stringResource(R.string.cover_card_pos_top)
        val centre = stringResource(R.string.cover_card_pos_centre)
        val bottom = stringResource(R.string.cover_card_pos_bottom)
        ValueSlider(
            title = stringResource(R.string.lyric_band_pos),
            summary = stringResource(R.string.lyric_band_pos_summary),
            value = module.lyricPos.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            // A band that fills its room has no height left to move in.
            enabled = enabled && module.lyrics && module.lyricFill < 1f,
            detent = 0.5f,
            label = {
                when ((it * 100).roundToInt()) {
                    0 -> top
                    50 -> centre
                    100 -> bottom
                    else -> "${(it * 100).roundToInt()}%"
                }
            },
            onValueChange = {
                val value = (it * 100).roundToInt() / 100f
                onChange(module.copy(lyricPos = value))
                ModuleBridge.setLyricStyle(context, "pos", value)
            },
        )
        ValueSlider(
            title = stringResource(R.string.lyric_horizontal_margin),
            summary = stringResource(R.string.lyric_horizontal_margin_summary),
            value = module.lyricSideDp.coerceIn(0f, 64f),
            valueRange = 0f..64f,
            enabled = enabled && module.lyrics,
            label = { "${it.roundToInt()} dp" },
            onValueChange = {
                val value = it.roundToInt().toFloat()
                onChange(module.copy(lyricSideDp = value))
                ModuleBridge.setLyricStyle(context, "side", value)
            },
        )
        ValueSlider(
            title = stringResource(R.string.lyric_font_size),
            summary = stringResource(R.string.lyric_font_size_summary),
            value = module.lyricSizeSp.coerceIn(18f, 36f),
            valueRange = 18f..36f,
            enabled = enabled && module.lyrics,
            label = { "${it.roundToInt()} sp" },
            onValueChange = {
                val value = it.roundToInt().toFloat()
                onChange(module.copy(lyricSizeSp = value))
                ModuleBridge.setLyricStyle(context, "size", value)
            },
        )
        ValueSlider(
            title = stringResource(R.string.lyric_font_weight),
            summary = stringResource(R.string.lyric_font_weight_summary),
            value = module.lyricWeight.coerceIn(300, 700).toFloat(),
            // MiSans VF, the lock screen's font, has a weight axis that ends at 700.
            valueRange = 300f..700f,
            enabled = enabled && module.lyrics,
            // The number shown is the number sent: both go through lyricWeightOf.
            label = { "${lyricWeightOf(it)}" },
            onValueChange = {
                val value = lyricWeightOf(it)
                onChange(module.copy(lyricWeight = value))
                ModuleBridge.setLyricStyle(context, "weight", value.toFloat())
            },
        )
        SwitchPreference(
            title = stringResource(R.string.lyrics_trans),
            checked = module.lyricsTrans,
            enabled = enabled && module.lyrics,
            onCheckedChange = {
                onChange(module.copy(lyricsTrans = it))
                ModuleBridge.setLyricsTrans(context, it)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.lyrics_hdr),
            checked = module.lyricsHdr,
            enabled = enabled && module.lyrics,
            onCheckedChange = {
                onChange(module.copy(lyricsHdr = it))
                ModuleBridge.setLyricsHdr(context, it)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.lyrics_keep_on),
            checked = module.lyricsKeepOn,
            enabled = enabled && module.lyrics,
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
    onCardRestyled: () -> Unit,
) {
    val context = LocalContext.current
    Column {
        SwitchPreference(
            title = stringResource(R.string.card_hide_art),
            checked = module.mcHideArt,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(mcHideArt = it))
                ModuleBridge.setCardHideArt(context, it)
                onCardRestyled()
            },
        )
        // Unfolds directly under the switch it belongs to, inside the same card: it is not a
        // fourth media card setting, it is the exception to the one above, and it only exists
        // while that one is on.
        AnimatedVisibility(
            visible = module.mcHideArt,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SwitchPreference(
                title = stringResource(R.string.card_art_in_lyrics),
                checked = module.mcArtInLyrics,
                enabled = enabled,
                onCheckedChange = { on ->
                    onChange(module.copy(mcArtInLyrics = on))
                    ModuleBridge.setCardArtInLyrics(context, on)
                    onCardRestyled()
                },
            )
        }
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

/** The date-and-clock offset's range in dp, matching CLOCK_OFFSET_MIN/MAX_DP in the module. */
private const val CLOCK_OFFSET_MIN_DP = -60f
private const val CLOCK_OFFSET_MAX_DP = 300f

/** The clock size's floor, matching CLOCK_SIZE_MIN in the module. */
private const val CLOCK_SIZE_MIN = 0.05f
/** Where the size thumb sits before the module has said what the clock is at. */
private const val DEFAULT_CLOCK_SIZE = 0.3f

/**
 * The clock transition's spring response, in seconds, matching CLOCK_RESPONSE_MIN/MAX in the
 * module. 0.18 is the fastest this transition has ever shipped and 0.60 is the slow end of what
 * still reads as one movement.
 */
private const val CLOCK_RESPONSE_MIN = 0.18f
private const val CLOCK_RESPONSE_MAX = 0.60f

/**
 * What the module ships with, and the one detent on any slider here: `EASE_COVER[1]` in Main.java,
 * the response the cover itself moves on. Named rather than written at the call site because the
 * detent and the default have to be the same number for either of them to be worth anything.
 */
private const val DEFAULT_CLOCK_RESPONSE = 0.38f

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

/** The clock half of a state's geometry, for comparing against a freshly reported one. */
private fun clockGeometryOf(state: ModuleBridge.State) = ModuleBridge.Geometry(
    clockW = state.geometry.clockW,
    clockH = state.geometry.clockH,
    clockY = state.geometry.clockY,
    clockPad = state.geometry.clockPad,
    clockX = state.geometry.clockX,
    clockPivotX = state.geometry.clockPivotX,
    clockFull = state.geometry.clockFull,
)

/**
 * How often the preview re-asks for its pictures. Long enough to be invisible on the battery,
 * short enough that a skipped track catches up before it is worth wondering about.
 */
private const val SHOT_POLL_MS = 3000L

/** Time for the module's per-frame guard to restyle the card before it is photographed. */
private const val SETTLE_MS = 200L

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

/**
 * The players LyricInfo can write for, read out of its own APK's dex.
 *
 * It hooks a player's internals and republishes what it finds as `lyricInfo` on the media
 * session, so it only covers the players it was written for - and Apple Music is not one of
 * them. That is what the lyrics state on this page turns on: whether a session carries a lyric
 * says nothing about the module unless the player on screen is one the module claims.
 *
 * From `pm path com.lidesheng.lyricinfo`, pulled and grepped out of `classes.dex`; the vendor
 * class names around them (`com.salt.music.service.MusicController`,
 * `com.luna.biz.playing.player.remote.control.*`) are the hook targets themselves. Both
 * soda-music names are here because the app has shipped under both - `com.luna.music` and
 * `com.ikunshare.music.mobile`.
 */
private val LYRICINFO_PLAYERS = setOf(
    "com.netease.cloudmusic",
    "com.tencent.qqmusic",
    "com.kugou.android",
    "com.miui.player",
    "com.salt.music",
    "com.luna.music",
    "com.ikunshare.music.mobile",
    "com.hihonor.cloudmusic",
)

/** How long the provider notice holds its own dismiss button, in seconds. */
private const val PROVIDER_NOTICE_SECONDS = 15

/**
 * What the page says about the lyric provider module.
 *
 * Three states rather than two, because "installed" is not the question that matters: LyricInfo
 * is an LSPosed module, and one that is installed but not enabled - or enabled without the
 * player in its scope - writes nothing while still sitting in the package list. Both of the
 * first two are asking for something to be done, which is what [warning] is for; the third is
 * only saying what the module does and does not cover.
 */
private enum class ProviderNotice(
    val title: Int,
    val summary: Int,
    val warning: Boolean,
) {
    /** Not in the package list at all. */
    Missing(R.string.lyrics_provider_title_missing, R.string.lyrics_provider_missing, true),
    /** Installed, but no session has carried its lyric since SystemUI started. */
    Inactive(R.string.lyrics_provider_title_inactive, R.string.lyrics_provider_inactive, true),
    /** Installed and writing. Apps outside its scope can still miss, which is worth saying. */
    Ready(R.string.lyrics_provider_title_ready, R.string.lyrics_provider_ready, false),
}

/** A slider position as a font weight: the nearest hundred, within what MiSans VF can draw. */
private fun lyricWeightOf(position: Float): Int =
    ((position / 100f).roundToInt() * 100).coerceIn(300, 700)

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
