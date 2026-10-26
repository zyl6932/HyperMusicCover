package com.os4.musiccover.ui.screen.features

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.os4.musiccover.R
import com.os4.musiccover.app.ModuleBridge
import com.os4.musiccover.lyrics.LocalLyricFormat
import com.os4.musiccover.lyrics.LyricSourcePolicy
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LyricSourceSettings(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    val currentModule by rememberUpdatedState(module)
    var sourcesOpen by remember { mutableStateOf(false) }
    fun update(next: ModuleBridge.State) {
        onChange(next)
        ModuleBridge.setLyricSourcePolicy(context, next)
    }
    BasicComponent(
        title = stringResource(R.string.lyrics_source_lyricon),
        summary = lyriconStatusLabel(module.lyriconState),
    )
    BasicComponent(
        title = stringResource(R.string.lyrics_priority),
        summary = stringResource(R.string.lyrics_priority_summary),
        enabled = enabled,
        onClick = { sourcesOpen = true },
        endActions = {
            IconButton(
                modifier = Modifier.size(48.dp),
                enabled = enabled,
                onClick = { sourcesOpen = true },
            ) {
                Icon(
                    imageVector = MiuixIcons.Sort,
                    contentDescription = stringResource(R.string.lyrics_priority),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
    WindowDropdownPreference(
        title = stringResource(R.string.lyrics_format_priority),
        summary = stringResource(R.string.lyrics_format_summary),
        items = listOf(stringResource(R.string.lyrics_format_ttml), stringResource(R.string.lyrics_format_lrc)),
        selectedIndex = if (module.lyricLocalFormat == LocalLyricFormat.LRC) 1 else 0,
        enabled = enabled,
        onSelectedIndexChange = {
            update(currentModule.copy(lyricLocalFormat = if (it == 1) LocalLyricFormat.LRC else LocalLyricFormat.TTML))
        },
    )
    WindowDialog(
        show = sourcesOpen,
        title = stringResource(R.string.lyrics_priority),
        summary = stringResource(R.string.lyrics_priority_summary),
        onDismissRequest = { sourcesOpen = false },
    ) {
        Column {
            PriorityRows(
                order = if (module.lyricSourceCustom) module.lyricSourceOrder
                    else LyricSourcePolicy.DEFAULT_SOURCES,
                enabled = enabled,
                label = { sourceLabel(it) },
                dimmed = { !module.lyricNetwork && LyricSourcePolicy.network(it) },
                onCommit = { update(currentModule.copy(lyricSourceCustom = true, lyricSourceOrder = it)) },
            )
            TextButton(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                text = stringResource(R.string.lyrics_priority_reset),
                enabled = enabled,
                onClick = {
                    update(currentModule.copy(lyricSourceCustom = false,
                        lyricSourceOrder = LyricSourcePolicy.DEFAULT_SOURCES))
                },
            )
            TextButton(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                text = stringResource(R.string.lyrics_priority_done),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { sourcesOpen = false },
            )
        }
    }

}

@Composable
private fun sourceLabel(source: String): String = stringResource(when (source) {
    "metadata" -> R.string.lyrics_priority_metadata
    "local" -> R.string.lyrics_priority_local
    "lyricon" -> R.string.lyrics_source_lyricon
    "amll" -> R.string.lyrics_source_amll
    "ttmlhub" -> R.string.lyrics_source_ttmlhub
    "qq" -> R.string.lyrics_source_qq
    "netease" -> R.string.lyrics_source_netease
    "kugou" -> R.string.lyrics_source_kugou
    "kuwo" -> R.string.lyrics_source_kuwo
    else -> R.string.lyrics_source_lrclib
})

@Composable
private fun PriorityRows(
    order: String,
    enabled: Boolean,
    label: @Composable (String) -> String,
    onCommit: (String) -> Unit,
    dimmed: (String) -> Boolean = { false },
) {
    var items by remember { mutableStateOf(order.split(',')) }
    val commit by rememberUpdatedState(onCommit)
    val rowHeight = with(LocalDensity.current) { 72.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var active by remember { mutableStateOf<String?>(null) }
    var displacement by remember { mutableFloatStateOf(0f) }
    var original by remember { mutableStateOf(items) }
    val scroll = rememberScrollState()
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    val moveUp = stringResource(R.string.lyrics_priority_up)
    val moveDown = stringResource(R.string.lyrics_priority_down)
    val dragDescription = stringResource(R.string.lyrics_priority_drag)
    val skipped = stringResource(R.string.lyrics_priority_skipped)
    LaunchedEffect(order) {
        if (active == null) items = order.split(',')
    }
    fun move(source: String, target: Int) {
        val index = items.indexOf(source)
        if (index < 0 || target !in items.indices || index == target) return
        items = items.toMutableList().apply { add(target, removeAt(index)) }
        if (active == source) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    fun drag(source: String) {
        val top = (pointerY - viewportTop + scroll.value - grabOffset)
            .coerceIn(0f, items.lastIndex * rowHeight)
        val target = (top / rowHeight).roundToInt().coerceIn(items.indices)
        move(source, target)
        displacement = top - target * rowHeight
    }
    LaunchedEffect(active) {
        val source = active ?: return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val edge = minOf(rowHeight, viewportHeight / 3f)
            if (edge <= 0f) continue
            val relative = pointerY - viewportTop
            val speed = when {
                relative < edge -> -((edge - relative) / edge).coerceIn(0f, 1f)
                relative > viewportHeight - edge ->
                    ((relative - viewportHeight + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f && scroll.scrollBy(speed * rowHeight * 6f * elapsed) != 0f) drag(source)
        }
    }
    Column(Modifier.heightIn(max = 320.dp).onGloballyPositioned {
        viewportTop = it.positionInWindow().y
        viewportHeight = it.size.height.toFloat()
    }.verticalScroll(scroll)) {
        items.forEachIndexed { index, source ->
            key(source) {
                val name = label(source)
                var handle by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val rowColor by animateColorAsState(
                    targetValue = if (active == source) MiuixTheme.colorScheme.secondaryContainer
                        else MiuixTheme.colorScheme.surfaceContainer,
                    label = "priorityRowColor",
                )
                BasicComponent(
                    title = name,
                    summary = if (dimmed(source)) skipped else null,
                    titleColor = BasicComponentDefaults.titleColor(
                        color = if (dimmed(source)) MiuixTheme.colorScheme.onSurfaceVariantSummary
                            else MiuixTheme.colorScheme.onBackground,
                    ),
                    enabled = enabled,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                        .zIndex(if (active == source) 1f else 0f)
                        .offset { IntOffset(0, if (active == source) displacement.roundToInt() else 0) }
                        .squircleSurface(color = rowColor, cornerRadius = 12.dp),
                    startAction = {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.width(24.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    endActions = {
                        Box(
                            Modifier.size(48.dp).onGloballyPositioned { handle = it }.semantics {
                                contentDescription = "$name: $dragDescription"
                                if (enabled) customActions = listOf(
                                    CustomAccessibilityAction(moveUp) {
                                        move(source, items.indexOf(source) - 1)
                                        commit(items.joinToString(","))
                                        true
                                    },
                                    CustomAccessibilityAction(moveDown) {
                                        move(source, items.indexOf(source) + 1)
                                        commit(items.joinToString(","))
                                        true
                                    },
                                )
                            }.pointerInput(source, enabled, rowHeight) {
                                if (!enabled) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { position ->
                                        pointerY = handle?.localToWindow(position)?.y ?: viewportTop
                                        grabOffset = pointerY - viewportTop + scroll.value - items.indexOf(source) * rowHeight
                                        active = source
                                        original = items
                                        displacement = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragCancel = {
                                        items = original
                                        active = null
                                        displacement = 0f
                                    },
                                    onDragEnd = {
                                        active = null
                                        displacement = 0f
                                        commit(items.joinToString(","))
                                    },
                                ) { change, _ ->
                                    change.consume()
                                    pointerY = handle?.localToWindow(change.position)?.y ?: pointerY
                                    drag(source)
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Sort,
                                contentDescription = null,
                                tint = if (active == source) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun lyriconStatusLabel(state: String): String = stringResource(when (state) {
    "active" -> R.string.lyricon_active
    "connected" -> R.string.lyricon_connected
    "disconnected" -> R.string.lyricon_disconnected
    "no service" -> R.string.lyricon_unavailable
    "failed" -> R.string.lyricon_failed
    else -> R.string.lyricon_waiting
})
