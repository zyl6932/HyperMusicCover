package com.os4.musiccover.ui.screen.features

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.BlurredBar
import com.os4.musiccover.ui.util.pageScrollModifiers
import com.os4.musiccover.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * Everything the module itself can be told to do, with a picture of the lock screen above it.
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
fun FeaturesPageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = isBlurEnabled && backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    var module by remember { mutableStateOf(ModuleBridge.State()) }
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var shots by remember { mutableStateOf(ModuleBridge.Preview()) }
    var group by remember { mutableIntStateOf(0) }
    // Bumped when a switch changes the card, to re-take the picture without waiting for the tick.
    var shotNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) { module = ModuleBridge.query(context) }
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
                        )
                    )
                }
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
    val groups = listOf(
        stringResource(R.string.cover_section),
        stringResource(R.string.clock_section),
        stringResource(R.string.card_section),
    )

    Scaffold(
        popupHost = { },
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                LockPreview(
                    art = art,
                    bias = module.bias,
                    clockScale = module.clockScale,
                    glassEnd = module.glassEnd,
                    geometry = module.geometry,
                    card = shots.card,
                    cardRadius = shots.cardRadius,
                    artSlot = shots.artSlot,
                    cardHideArt = module.mcHideArt,
                    cardCenterText = module.mcCenterText,
                    clockHour = shots.clockHour,
                    clockMinute = shots.clockMinute,
                    date = shots.date,
                    leftShortcut = shots.left,
                    rightShortcut = shots.right,
                )
                MiuixText(
                    // Say so when the picture is standing in, rather than letting a sample cover
                    // read as the user's own music.
                    text = stringResource(
                        if (art == null && shots.card == null) R.string.preview_note_sample
                        else R.string.preview_note
                    ),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TabRow(
                    tabs = groups,
                    selectedTabIndex = group,
                    onTabSelected = { group = it },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                LazyColumn(
                    overscrollEffect = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        ),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            when (group) {
                                0 -> CoverGroup(enabled, module) { module = it }
                                1 -> ClockGroup(enabled, module) { module = it }
                                else -> CardGroup(enabled, module, { module = it }) {
                                    shotNonce++
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
private fun CoverGroup(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    ValueSlider(
        title = stringResource(R.string.cover_bias),
        summary = stringResource(R.string.cover_bias_summary),
        value = module.bias,
        valueRange = 0f..1f,
        enabled = enabled,
        onValueChange = {
            onChange(module.copy(bias = it))
            ModuleBridge.setBias(context, it)
        },
    )
}

@Composable
private fun ClockGroup(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    Column {
        ValueSlider(
            title = stringResource(R.string.clock_scale),
            summary = stringResource(R.string.clock_scale_summary),
            value = module.clockScale,
            valueRange = 0.1f..1f,
            enabled = enabled,
            onValueChange = {
                onChange(module.copy(clockScale = it))
                ModuleBridge.setClockScale(context, it)
            },
        )
        // Shown inverted. The module stores the OEM's own number, where updateGlassValue(0) is
        // transparent refracting glass and (1) is a solid fill - so as "glass strength" it runs
        // backwards, and dragging right made the effect weaker. The stored value, the adb
        // glassend op and the exported JSON all keep the OEM's meaning; only this slider is
        // flipped.
        ValueSlider(
            title = stringResource(R.string.clock_glass),
            summary = null,
            value = 1f - module.glassEnd,
            valueRange = 0f..1f,
            enabled = enabled,
            onValueChange = {
                val glassEnd = 1f - it
                onChange(module.copy(glassEnd = glassEnd))
                ModuleBridge.setGlassEnd(context, glassEnd)
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
        SwitchPreference(
            title = stringResource(R.string.card_center_text),
            checked = module.mcCenterText,
            enabled = enabled,
            onCheckedChange = {
                onChange(module.copy(mcCenterText = it))
                ModuleBridge.setCardCenterText(context, it)
                onCardRestyled()
            },
        )
        // Sits here because that is where it was asked for, but it is not a card setting and
        // does not follow cover mode - which the summary says, since the two switches above it
        // do. No onCardRestyled(): the preview above draws no fingerprint.
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
        val avoidModes = listOf(
            stringResource(R.string.fp_avoid_system),
            stringResource(R.string.fp_avoid_never),
            stringResource(R.string.fp_avoid_always),
        )
        val avoidIndex = module.fpAvoid.coerceIn(0, avoidModes.lastIndex)
        WindowDropdownPreference(
            title = stringResource(R.string.fp_avoid),
            // The delay is real and would otherwise read as the setting not working, so it is
            // stated where the setting is, not in a release note.
            summary = stringResource(R.string.fp_avoid_summary),
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
 */
@Composable
private fun ValueSlider(
    title: String,
    summary: String?,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicComponent(
            title = title,
            summary = summary,
            enabled = enabled,
            endActions = {
                MiuixText(
                    text = format(value),
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
