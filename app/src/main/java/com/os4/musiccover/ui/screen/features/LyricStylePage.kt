package com.os4.musiccover.ui.screen.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.app.ModuleBridge
import com.os4.musiccover.lyrics.HdrDisplay
import com.os4.musiccover.lyrics.LyricAppearance
import com.os4.musiccover.ui.util.PageScaffold
import com.os4.musiccover.ui.util.firstCard
import com.os4.musiccover.ui.util.nextCard
import kotlin.math.roundToInt
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 歌词外观: how the lock screen's lyrics look (PR #103).
 *
 * Two cards (user, 2026-10-23). The first holds what applies to every line - alignment, the
 * translation and romanisation under it, the HDR highlight. The second is the words themselves,
 * with a switch between the lead and the backing vocal over one set of rows - size, colour,
 * opacity - so each voice's settings sit together and the two read as the same thing twice.
 *
 * No preview: every change goes to the lock screen as it is made, except the sizes, which are
 * sent when the finger lifts - a size lays every line out again, and doing that per frame of a
 * drag makes the lock screen jump. Restoring the defaults is in the top bar, as the home page's
 * restart is, behind a confirmation.
 */
@Composable
internal fun LyricStylePageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    // Asked and re-asked as the other module pages are: see ShadePageView.
    var asked by remember { mutableStateOf(false) }
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
    val enabled = module.alive
    val appearance = remember(module.lyricAppearance) { LyricAppearance.parse(module.lyricAppearance) }

    // One field or several of the appearance, written through LyricAppearance so what is shown
    // is what the module will hold.
    fun setAppearance(vararg values: Pair<String, Any>) {
        val json = JSONObject(LyricAppearance.parse(module.lyricAppearance).toJson())
        for ((k, v) in values) json.put(k, v)
        val next = LyricAppearance.parse(json.toString()).toJson()
        module = module.copy(lyricAppearance = next)
        ModuleBridge.setLyricAppearance(context, next)
    }

    // Everything on this page back to what the module ships with.
    fun reset() {
        val json = LyricAppearance.DEFAULT.toJson()
        module = module.copy(
            lyricsAlign = 0,
            lyricSizeSp = DEFAULT_SIZE,
            lyricBgSizeSp = DEFAULT_BG_SIZE,
            lyricsTrans = true,
            lyricsRoma = false,
            lyricsHdr = false,
            lyricAppearance = json,
        )
        ModuleBridge.setLyricsAlign(context, 0)
        ModuleBridge.setLyricStyle(context, "size", DEFAULT_SIZE)
        ModuleBridge.setLyricStyle(context, "bgsize", DEFAULT_BG_SIZE)
        ModuleBridge.setLyricsTrans(context, true)
        ModuleBridge.setLyricsRoma(context, false)
        ModuleBridge.setLyricsHdr(context, false)
        ModuleBridge.setLyricAppearance(context, json)
    }
    var confirmReset by remember { mutableStateOf(false) }

    // 0 the lead, 1 the backing vocal: which voice the second card's rows are for.
    var voice by remember { mutableIntStateOf(0) }
    // Which voice the colour picker is open for - "main", "bg" - or null when it is shut. The
    // mode only becomes CUSTOM when a colour is chosen, so cancelling leaves it as it was.
    var picking by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(Color.White) }

    PageScaffold(
        title = stringResource(R.string.lyrics_style_title),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onBack = onBack,
        actions = {
            IconButton(onClick = { if (enabled) confirmReset = true }, holdDownState = confirmReset) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.lyrics_style_reset),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        item(key = "general") {
            Column {
                // The module not answering is said once, above everything it greys out.
                if (asked && !module.alive) {
                    Card(modifier = Modifier.firstCard()) {
                        BasicComponent(title = stringResource(R.string.home_status_inactive_hint))
                    }
                }
                SmallTitle(text = stringResource(R.string.lyrics_style_general))
                Card(modifier = Modifier.nextCard()) {
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
                            module = module.copy(lyricsAlign = it)
                            ModuleBridge.setLyricsAlign(context, it)
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.lyrics_trans),
                        checked = module.lyricsTrans,
                        enabled = enabled,
                        onCheckedChange = {
                            module = module.copy(lyricsTrans = it)
                            ModuleBridge.setLyricsTrans(context, it)
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.lyrics_roma),
                        checked = module.lyricsRoma,
                        enabled = enabled,
                        onCheckedChange = {
                            module = module.copy(lyricsRoma = it)
                            ModuleBridge.setLyricsRoma(context, it)
                        },
                    )
                    // A screen with no HDR headroom gets nothing from the highlight and would still
                    // pay for its window; SystemUI ignores the switch there, and it is shown off here.
                    val hdrScreen = remember { HdrDisplay.supported(context) }
                    SwitchPreference(
                        title = stringResource(R.string.lyrics_hdr),
                        summary = stringResource(
                            if (hdrScreen) R.string.lyrics_hdr_summary else R.string.lyrics_hdr_unsupported
                        ),
                        checked = module.lyricsHdr && hdrScreen,
                        enabled = enabled && hdrScreen,
                        onCheckedChange = {
                            module = module.copy(lyricsHdr = it)
                            ModuleBridge.setLyricsHdr(context, it)
                        },
                    )
                }
            }
        }
        item(key = "voices") {
            val backing = voice == 1
            val role = if (backing) "bg" else "main"
            val mode = if (backing) appearance.bgMode else appearance.mainMode
            val rgb = if (backing) appearance.bgColor else appearance.mainColor
            val opacity = if (backing) appearance.bgOpacity else appearance.mainOpacity
            Column {
                SmallTitle(text = stringResource(R.string.lyrics_style_text))
                Card(modifier = Modifier.nextCard()) {
                    TabRowWithContour(
                        tabs = listOf(
                            stringResource(R.string.lyrics_style_main),
                            stringResource(R.string.lyrics_style_backing),
                        ),
                        selectedTabIndex = voice,
                        onTabSelected = { voice = it },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    if (backing) {
                        ValueSlider(
                            title = stringResource(R.string.lyrics_size),
                            value = module.lyricBgSizeSp,
                            valueRange = 10f..30f,
                            enabled = enabled,
                            detent = DEFAULT_BG_SIZE,
                            label = { "${it.roundToInt()} sp" },
                            onValueChange = { module = module.copy(lyricBgSizeSp = it.roundToInt().toFloat()) },
                            onValueChangeFinished = {
                                ModuleBridge.setLyricStyle(context, "bgsize", module.lyricBgSizeSp)
                            },
                        )
                    } else {
                        ValueSlider(
                            title = stringResource(R.string.lyrics_size),
                            value = module.lyricSizeSp,
                            valueRange = 18f..36f,
                            enabled = enabled,
                            detent = DEFAULT_SIZE,
                            label = { "${it.roundToInt()} sp" },
                            onValueChange = { module = module.copy(lyricSizeSp = it.roundToInt().toFloat()) },
                            onValueChangeFinished = {
                                ModuleBridge.setLyricStyle(context, "size", module.lyricSizeSp)
                            },
                        )
                    }
                    // The dropdown, and beside it the custom colour as a swatch that reopens the
                    // picker. miuix's dropdown row takes nothing at its end, so the swatch is its
                    // neighbour rather than part of it.
                    val custom = mode == LyricAppearance.CUSTOM
                    val openPicker = {
                        draft = Color(rgb).copy(alpha = opacity)
                        picking = role
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WindowDropdownPreference(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.lyrics_color),
                            summary = if (mode == LyricAppearance.COVER) {
                                stringResource(R.string.lyrics_color_cover_summary)
                            } else {
                                null
                            },
                            items = listOf(
                                stringResource(R.string.lyrics_color_official),
                                stringResource(R.string.lyrics_color_cover),
                                stringResource(R.string.lyrics_color_custom),
                                stringResource(R.string.lyrics_color_system),
                            ),
                            selectedIndex = mode,
                            enabled = enabled,
                            insideMargin = if (custom) PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp)
                                           else PaddingValues(16.dp),
                            onSelectedIndexChange = {
                                if (it == LyricAppearance.CUSTOM) openPicker()
                                else setAppearance("${role}Mode" to it)
                            },
                        )
                        if (custom) {
                            Box(
                                Modifier
                                    .padding(end = 16.dp)
                                    .size(24.dp)
                                    .background(Color(rgb), CircleShape)
                                    .clickable(enabled = enabled) { openPicker() },
                            )
                        }
                    }
                    ValueSlider(
                        title = stringResource(R.string.lyrics_opacity),
                        value = opacity * 100f,
                        valueRange = 0f..100f,
                        enabled = enabled,
                        detent = (if (backing) DEFAULT_BG_OPACITY else 1f) * 100f,
                        label = { "${it.roundToInt()}%" },
                        onValueChange = { setAppearance("${role}Opacity" to it.roundToInt() / 100.0) },
                    )
                }
            }
        }
    }

    // Asked first: one tap in the corner would otherwise undo every choice on the page.
    WindowDialog(
        show = confirmReset,
        title = stringResource(R.string.lyrics_style_reset),
        summary = stringResource(R.string.lyrics_style_reset_summary),
        onDismissRequest = { confirmReset = false },
    ) {
        Row {
            TextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.rules_cancel),
                onClick = { confirmReset = false },
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.lyrics_style_reset),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    reset()
                    confirmReset = false
                },
            )
        }
    }

    WindowDialog(
        show = picking != null,
        title = stringResource(R.string.lyrics_color_custom),
        onDismissRequest = { picking = null },
    ) {
        Column {
            // miuix's picker always has an alpha bar; it is the same value as the opacity row,
            // opened at it and written back to it.
            ColorPicker(color = draft, onColorChanged = { draft = it })
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.rules_cancel),
                    onClick = { picking = null },
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.lyrics_style_done),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        picking?.let {
                            setAppearance(
                                "${it}Mode" to LyricAppearance.CUSTOM,
                                "${it}Color" to draft.copy(alpha = 1f).toArgb(),
                                "${it}Opacity" to (draft.alpha * 100f).roundToInt() / 100.0,
                            )
                        }
                        picking = null
                    },
                )
            }
        }
    }
}

/** The sizes the module ships with: LyricStyle.DEFAULT. */
private const val DEFAULT_SIZE = 25f
private const val DEFAULT_BG_SIZE = 17f
/** LyricAppearance.DEFAULT's backing vocal; the lead's is 1. */
private const val DEFAULT_BG_OPACITY = 0.72f
