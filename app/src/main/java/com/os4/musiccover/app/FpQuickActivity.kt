package com.os4.musiccover.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import top.yukonga.miuix.kmp.icon.extended.Link
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import com.os4.musiccover.R
import com.os4.musiccover.ui.screen.features.ValueSlider
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.FoldUnder
import com.os4.musiccover.ui.util.AppCard
import com.os4.musiccover.ui.util.PageScaffold
import com.os4.musiccover.ui.util.firstCard
import com.os4.musiccover.ui.util.nextCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import com.os4.musiccover.ui.util.LocalOs4Ui
import com.os4.musiccover.ui.util.LocalPageBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.os4.musiccover.ui.util.AppDialog
import com.os4.musiccover.ui.util.AppDialogButton
import com.os4.musiccover.ui.util.AppDialogButtonRole

/**
 * 指纹快捷功能: the menu HyperOS opens when the finger stays on the sensor after unlocking, with
 * its timing and its items. The module side is FingerprintQuick; this page edits the one JSON
 * config it reads, and the system's own switch through SystemUI.
 *
 * The item list and the app picker follow InstallerX Revived (2026-10-21, the user's choice):
 * miuix itself has no list editing. Long-press drag to reorder is its DraggableManagedList, the
 * delete pill, its confirmation and the add button its miuix MiuixManagedPackagesWidget, the
 * picker its MiuixApplyPage.
 *
 * See [CoverActivity] for why this is an Activity rather than a page inside the features tab.
 */
class FpQuickActivity : ComponentActivity() {

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
            AppTheme(themeMode = theme, useOs4Ui = settings.useOs4Ui) { FpQuickPage(settings.isBlurEnabled, resumes, ::finish) }
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

/** The honeycomb's room: FingerprintQuick.MAX_ITEMS. */
private const val MAX_ITEMS = 37

/** What the OEM's own 500 + 600 come to; FingerprintQuick.DEFAULT_TOTAL_MS. */
private const val DEFAULT_TOTAL = 1100
private const val MAX_TOTAL = 1500

/** The OEM's domestic list, what "no items" means to the module. */
private val DEFAULT_NATIVE = listOf(1001, 1002, 1003, 1004, 1005)
private val ALL_NATIVE = (1001..1008).toList()
private val ACTIONS = listOf("torch", "camera", "screenshot", "recorder")

private const val SYSUI = "com.android.systemui"

/** The app each OEM item opens, for its icon here. */
private fun nativePackage(id: Int): String? = when (id) {
    1001, 1002 -> "com.tencent.mm"
    1003 -> "com.miui.voiceassist"
    1004, 1005 -> "com.eg.android.AlipayGphone"
    else -> null
}

private fun nativeLabel(id: Int): Int = when (id) {
    1001 -> R.string.fpquick_n1001
    1002 -> R.string.fpquick_n1002
    1003 -> R.string.fpquick_n1003
    1004 -> R.string.fpquick_n1004
    1005 -> R.string.fpquick_n1005
    1006 -> R.string.fpquick_n1006
    1007 -> R.string.fpquick_n1007
    else -> R.string.fpquick_n1008
}

private fun actionLabel(a: String): Int = when (a) {
    "torch" -> R.string.fpquick_a_torch
    "camera" -> R.string.fpquick_a_camera
    "screenshot" -> R.string.fpquick_a_screenshot
    else -> R.string.fpquick_a_recorder
}

/**
 * The page's copy of FingerprintQuick's config. [on] is 「启用指纹快捷功能自定义」 - off leaves
 * the OEM's menu as it ships. [items] holds one entry per honeycomb place, in the module's
 * CELLS order, `{"t":"empty"}` for a place left open; null is the OEM's own list.
 */
private data class FpConfig(
    val on: Boolean,
    val total: Int,
    val move: Boolean,
    val items: List<JSONObject>?,
    /** 人脸解锁后按住指纹也弹出; FingerprintQuick.sFaceToo. */
    val face: Boolean = true,
) {
    /** Every place up to the last one used, empty ones included. */
    val slots: List<JSONObject> get() = items ?: DEFAULT_NATIVE.map { native(it) }

    /** The places that hold something, with where they are. */
    val filled: List<Pair<Int, JSONObject>>
        get() = slots.mapIndexedNotNull { i, e -> if (isEmpty(e)) null else i to e }

    /** The same config with place [i] holding [entry], or opened when it is null. */
    fun withSlot(i: Int, entry: JSONObject?): FpConfig {
        val list = slots.toMutableList()
        while (list.size <= i) list.add(empty())
        list[i] = entry ?: empty()
        while (list.isNotEmpty() && isEmpty(list.last())) list.removeAt(list.lastIndex)
        return copy(items = list)
    }

    /** The first open place, for the floating add button; null when all 37 are taken. */
    fun firstOpen(): Int? = (0 until MAX_ITEMS).firstOrNull { it >= slots.size || isEmpty(slots[it]) }

    fun json(): String = JSONObject().apply {
        put("on", on)
        put("total", total)
        put("move", move)
        put("face", face)
        items?.let { put("items", JSONArray(it)) }
    }.toString()

    companion object {
        fun parse(raw: String): FpConfig = runCatching {
            val o = JSONObject(raw)
            val a = o.optJSONArray("items")
            FpConfig(o.optBoolean("on", true), o.optInt("total", DEFAULT_TOTAL), o.optBoolean("move", false),
                a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } },
                o.optBoolean("face", true))
        }.getOrDefault(FpConfig(false, DEFAULT_TOTAL, false, null))
    }
}

private fun native(id: Int) = JSONObject().put("t", "native").put("id", id)
private fun empty() = JSONObject().put("t", "empty")
private fun isEmpty(e: JSONObject) = e.optString("t") == "empty"

/**
 * The module's CELLS, worked out the same way: the sensor's place, then ring by ring, each from
 * the top down and out from the middle. {x in pitches, row, ring}.
 */
private val CELLS: List<FloatArray> = buildList {
    for (q in -3..3) for (row in -3..3) {
        val ring = maxOf(kotlin.math.abs(q), kotlin.math.abs(row), kotlin.math.abs(q + row))
        if (ring <= 3) add(floatArrayOf(q + row / 2f, row.toFloat(), ring.toFloat()))
    }
}.sortedWith(compareBy<FloatArray>({ it[2] }, { it[1] }, { kotlin.math.abs(it[0]) }, { it[0] }))

/**
 * Which add dialog is up. FULL says the menu has no room; RESET asks before 恢复默认; SLOT is
 * what to do with a place in the layout that already holds something.
 */
private enum class AddDialog { NONE, TYPE, NATIVE, URL, ACTION, FULL, RESET, SLOT }

@Composable
private fun FpQuickPage(blur: Boolean, refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    var asked by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(FpConfig(false, DEFAULT_TOTAL, false, null)) }
    // The place an add is for: one tapped in the layout, or null for the first open one.
    var target by remember { mutableStateOf<Int?>(null) }
    // The slider's position while dragging; sent when the finger lifts.
    var dragTotal by remember { mutableFloatStateOf(-1f) }
    var dialog by remember { mutableStateOf(AddDialog.NONE) }
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
        if (module.alive) config = FpConfig.parse(module.fpQuick)
        asked = true
    }

    fun save(next: FpConfig) {
        config = next
        ModuleBridge.setFpQuick(context, next.json())
    }

    fun add(entry: JSONObject) {
        val at = target ?: config.firstOpen() ?: return
        save(config.withSlot(at, entry))
        target = null
    }

    // The pickers are screens of their own (FpPickActivity) and answer with the item to add.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(EXTRA_ENTRY)?.takeIf { r.resultCode == Activity.RESULT_OK }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(::add)
    }

    val usable = module.alive && module.fpQuickOk
    val editing = usable && config.on
    PageScaffold(title = stringResource(R.string.features_fpquick_title), isBlurEnabled = blur,
        onBack = onBack,
        // 恢复默认 as a page action in the bar, the way miuix's own example puts one there
        // (PullToRefreshPage's refresh), drawn with the home page's restart glyph.
        actions = {
            if (editing) {
                val can = config.items != null
                if (LocalOs4Ui.current) {
                    GlassIconButton(onClick = { if (can) dialog = AddDialog.RESET }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            stringResource(R.string.fpquick_reset),
                            modifier = Modifier.size(24.dp),
                            tint = if (can) MiuixTheme.colorScheme.onSurface
                            else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                        )
                    }
                } else {
                    IconButton(enabled = can, onClick = { dialog = AddDialog.RESET }) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.fpquick_reset),
                            tint = if (can) MiuixTheme.colorScheme.onBackground
                                   else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
                    }
                }
            }
        },
        // 添加 as the page's main action: miuix's docs, FloatingActionButton bound to the
        // Scaffold. Not greyed when full (the user's call): it says why instead.
        floatingActionButton = {
            if (editing) {
                val openAdd = {
                    target = null
                    dialog = if (config.firstOpen() == null) AddDialog.FULL else AddDialog.TYPE
                }
                if (LocalOs4Ui.current) {
                    GlassFloatingActionButton(
                        modifier = Modifier.navigationBarsPadding(),
                        onClick = openAdd,
                        // Safe to sample: the button is in the Scaffold's own slot, outside the
                        // page body the backdrop records.
                        backdrop = LocalPageBackdrop.current,
                    ) {
                        Icon(
                            MiuixIcons.Add,
                            stringResource(R.string.fpquick_add_button),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    FloatingActionButton(
                        modifier = Modifier.navigationBarsPadding(),
                        onClick = openAdd,
                    ) {
                        Icon(MiuixIcons.Add, stringResource(R.string.fpquick_add_button), tint = Color.White)
                    }
                }
            }
        },
        // Every dialog this page opens is rendered inside the page's backdrop scope, so the
        // glass samples the page rather than the theme's flat surface fallback.
        dialogs = {
            AddDialogs(dialog, config.filled.map { it.second },
                onDismiss = { dialog = AddDialog.NONE },
                onPick = { dialog = it },
                onApp = { pick.launch(FpAppPickActivity.intent(context, it)) }, onAdd = { add(it) },
                onReset = { save(config.copy(items = null)) })

            // A place that holds something: change what is there, or open it again.
            val slotEntry = target?.let { config.slots.getOrNull(it) }?.takeIf { !isEmpty(it) }
            AppDialog(show = dialog == AddDialog.SLOT && slotEntry != null,
                title = slotEntry?.let { itemInfo(it).title }.orEmpty(),
                onDismissRequest = { dialog = AddDialog.NONE }) {
                Column {
                    BasicComponent(title = stringResource(R.string.fpquick_slot_replace),
                        onClick = { dialog = AddDialog.TYPE })
                    BasicComponent(title = stringResource(R.string.fpquick_slot_remove),
                        onClick = {
                            target?.let { save(config.withSlot(it, null)) }
                            target = null
                            dialog = AddDialog.NONE
                        })
                }
            }
        }) {
        item(key = "switch") {
            AppCard(Modifier.firstCard()) {
                // Only an answer can put this on (ShadePageView says why).
                SwitchPreference(title = stringResource(R.string.fpquick_enabled),
                    summary = when {
                        asked && !module.alive -> stringResource(R.string.home_status_inactive_hint)
                        module.alive && !module.fpQuickOk -> stringResource(R.string.fpquick_unsupported)
                        else -> null
                    },
                    checked = editing, enabled = usable,
                    onCheckedChange = { save(config.copy(on = it)) })
                FoldUnder(editing, settled = module.alive) {
                    Column {
                        val shown = if (dragTotal >= 0f) dragTotal else config.total.toFloat()
                        ValueSlider(
                            title = stringResource(R.string.fpquick_time),
                            value = shown,
                            valueRange = 0f..MAX_TOTAL.toFloat(),
                            enabled = true,
                            detent = DEFAULT_TOTAL.toFloat(),
                            label = { "${it.toInt()} ms" },
                            onValueChange = { dragTotal = (Math.round(it / 50f) * 50).toFloat() },
                            onValueChangeFinished = {
                                if (dragTotal >= 0f) save(config.copy(total = dragTotal.toInt()))
                                dragTotal = -1f
                            })
                        SwitchPreference(title = stringResource(R.string.fpquick_move),
                            checked = config.move,
                            onCheckedChange = { save(config.copy(move = it)) })
                        SwitchPreference(title = stringResource(R.string.fpquick_face),
                            checked = config.face,
                            onCheckedChange = { save(config.copy(face = it)) })
                    }
                }
            }
        }
        item(key = "layout") {
            FoldUnder(editing, settled = module.alive) {
                Column {
                    SmallTitle(text = stringResource(R.string.fpquick_layout))
                    AppCard(Modifier.nextCard()) {
                        HiveEditor(config.slots, onTap = { i ->
                            target = i
                            dialog = if (i < config.slots.size && !isEmpty(config.slots[i])) {
                                AddDialog.SLOT
                            } else {
                                AddDialog.TYPE
                            }
                        })
                    }
                }
            }
        }
        item(key = "items") {
            FoldUnder(editing, settled = module.alive) {
                Column {
                    SmallTitle(text = stringResource(R.string.fpquick_items))
                    AppCard(Modifier.nextCard()) {
                        val filled = config.filled
                        ItemList(filled.map { it.second },
                            // A new order of the same items into the same places.
                            onMove = { list ->
                                var next = config
                                filled.forEachIndexed { k, (slot, _) -> next = next.withSlot(slot, list[k]) }
                                save(next)
                            },
                            onRemove = { k -> save(config.withSlot(filled[k].first, null)) })
                    }
                }
            }
        }
        // Room for the last row above the floating button.
        item(key = "fab") { Spacer(Modifier.height(88.dp)) }
    }

}

/**
 * The honeycomb as the menu shows it, and driven the same way (the user, 2026-10-21: Apple
 * Watch's drag in the preview too): the lens sits in the middle of the face, where the
 * fingerprint is on the phone; the finger drags the whole grid, which glides on after a fling
 * and springs back from past its edge; the icon in the middle is the chosen one, grown with its
 * disc behind it. The lens is FingerprintQuick.focus's - window, falloff and sizes all in
 * pitches - so what this shows is what the phone does.
 *
 * Tapping an icon, where it is drawn now, is how a place is edited: an open place adds, a full
 * one asks what to do with it. A small one at the edge can be dragged in first.
 */
@Composable
private fun HiveEditor(slots: List<JSONObject>, onTap: (Int) -> Unit) {
    val ring = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    val face = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val discColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val scope = rememberCoroutineScope()
    val ax = remember { Animatable(0f) }
    val ay = remember { Animatable(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp).aspectRatio(1f).clip(CircleShape)) {
        val density = LocalDensity.current
        val facePx = with(density) { maxWidth.toPx() }
        // Sized so the lens's outermost reach (window + falloff) fills most of the face.
        val pitch = facePx / 2f * 0.9f / (40f / 62f + 1.3f)
        val rowH = pitch * 0.866f
        val window = pitch * 40f / 62f
        val falloff = pitch * 1.3f
        val iconPx = pitch * 52f / 62f
        val discPx = pitch * 33f / 62f * 2f
        val limit = pitch * 3f
        val catchR = pitch / 1.732f
        val half = facePx / 2f

        /** Where cell [c] is drawn, relative to the middle, and at what size. */
        fun lens(c: FloatArray, ox: Float, oy: Float): FloatArray {
            val px = c[0] * pitch + ox
            val py = c[1] * rowH + oy
            val d = kotlin.math.hypot(px, py)
            if (d <= window) return floatArrayOf(px, py, 1f, d)
            val out = (d - window) / falloff
            val size = kotlin.math.exp(-out)
            val k = (window + falloff * (1 - kotlin.math.exp(-out))) / d
            return floatArrayOf(px * k, py * k, size, d)
        }

        fun rubber(v: Float): Float {
            val a = kotlin.math.abs(v)
            if (a <= limit) return v
            val over = a - limit
            val m = limit * 0.35f
            return kotlin.math.sign(v) * (limit + m * (1f - 1f / (over / m + 1f)))
        }

        // The face hugs the honeycomb (the user, 2026-10-21): its radius is the furthest any
        // icon's edge reaches at rest, plus a margin. The lens never takes an icon further out
        // than that, however the grid is dragged.
        val faceR = CELLS.maxOf { c ->
            val l = lens(c, 0f, 0f)
            kotlin.math.hypot(l[0], l[1]) + iconPx * l[2] / 2f
        } + with(density) { 6.dp.toPx() }
        val ox = rubber(ax.value)
        val oy = rubber(ay.value)
        val placed = CELLS.map { lens(it, ox, oy) }
        val chosen = placed.indices.filter { placed[it][3] < catchR }.minByOrNull { placed[it][3] }
        // A tick as each new icon is chosen, the phone's own tick on the menu (the user,
        // 2026-10-21). Not for the first one, which is chosen just by the page opening.
        val view = LocalView.current
        var lastChosen by remember { mutableStateOf<Int?>(-1) }
        LaunchedEffect(chosen) {
            if (lastChosen != -1 && chosen != null && chosen != lastChosen) {
                view.performHapticFeedback(
                    if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
                    else HapticFeedbackConstants.CLOCK_TICK)
            }
            lastChosen = chosen
        }

        val tracker = remember { VelocityTracker() }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val x0 = rubber(ax.value)
                        val y0 = rubber(ay.value)
                        val hit = CELLS.indices.minByOrNull { i ->
                            val l = lens(CELLS[i], x0, y0)
                            kotlin.math.hypot(tap.x - half - l[0], tap.y - half - l[1]) / maxOf(l[2], 0.35f)
                        }
                        if (hit != null) {
                            val l = lens(CELLS[hit], x0, y0)
                            val reach = maxOf(iconPx * l[2] / 2f, iconPx * 0.2f)
                            if (kotlin.math.hypot(tap.x - half - l[0], tap.y - half - l[1]) <= reach) onTap(hit)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            scope.launch { ax.stop(); ay.stop() }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { ax.snapTo(ax.value + amount.x) }
                            scope.launch { ay.snapTo(ay.value + amount.y) }
                        },
                        onDragEnd = {
                            // The watch's glide: on with the fling's speed, settling where it
                            // would have stopped, and springing back to the edge from past it.
                            val v = tracker.calculateVelocity()
                            for ((a, vel) in listOf(ax to v.x, ay to v.y)) {
                                val start = rubber(a.value)
                                val glide = (start + vel * 0.18f).coerceIn(-limit, limit)
                                scope.launch {
                                    a.snapTo(start)
                                    a.animateTo(glide, spring(dampingRatio = 0.86f, stiffness = 180f),
                                        initialVelocity = vel)
                                }
                            }
                        },
                        onDragCancel = {
                            for (a in listOf(ax, ay)) {
                                val back = rubber(a.value).coerceIn(-limit, limit)
                                scope.launch { a.animateTo(back, spring(dampingRatio = 0.86f, stiffness = 180f)) }
                            }
                        })
                }
        ) {
            // The watch face.
            Canvas(Modifier.fillMaxSize()) { drawCircle(face, radius = faceR) }
            // Each icon's grow, sprung rather than switched (the user, 2026-10-21: it was
            // abrupt): the chosen one eases up to 1.15 with a little overshoot, the one it took
            // over from eases back, and the disc behind fades with the grow.
            val grows = CELLS.indices.map { i ->
                animateFloatAsState(if (i == chosen) 1f else 0f,
                    spring(dampingRatio = 0.55f, stiffness = 420f), label = "grow$i")
            }
            Canvas(Modifier.fillMaxSize()) {
                grows.forEachIndexed { i, g ->
                    val t = g.value.coerceIn(0f, 1f)
                    if (t > 0.01f) {
                        val l = placed[i]
                        drawCircle(discColor.copy(alpha = discColor.alpha * t),
                            radius = discPx / 2f * (0.85f + 0.15f * g.value),
                            center = Offset(half + l[0], half + l[1]))
                    }
                }
            }
            CELLS.indices.forEach { i ->
                val l = placed[i]
                val e = slots.getOrNull(i)?.takeIf { !isEmpty(it) }
                val scale = l[2] * (1f + 0.15f * grows[i].value)
                val fade = ((l[2] - 0.08f) / 0.17f).coerceIn(0f, 1f)
                val sizeDp = with(density) { iconPx.toDp() }
                Box(
                    Modifier
                        .offset { IntOffset((half + l[0] - iconPx / 2f).toInt(), (half + l[1] - iconPx / 2f).toInt()) }
                        .size(sizeDp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = fade
                        }
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val info = e?.let { itemInfo(it) }
                    if (info?.icon != null) {
                        Image(info.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else if (info?.vector != null) {
                        GlyphChip(info.vector, Modifier.fillMaxSize())
                    } else {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 1.5.dp.toPx() / maxOf(scale, 0.3f)
                            drawCircle(ring, radius = size.minDimension / 2f - stroke,
                                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
                        }
                    }
                }
            }
        }
    }
}

/** What one item shows: its name, what kind of thing it is, and its picture. */
private class ItemInfo(
    val title: String,
    val summary: String,
    val icon: Bitmap?,
    /** For a kind with no picture of its own (a link): a miuix glyph on a chip. */
    val vector: ImageVector? = null,
)

@Composable
private fun itemInfo(e: JSONObject): ItemInfo {
    val context = LocalContext.current
    val key = e.toString()
    return when (e.optString("t")) {
        "native" -> {
            val id = e.optInt("id")
            ItemInfo(stringResource(nativeLabel(id)), stringResource(R.string.fpquick_type_native),
                remember(key) { nativeIcon(context, id) })
        }
        "app" -> {
            val pkg = e.optString("pkg")
            ItemInfo(e.optString("label").ifEmpty { pkg }, stringResource(R.string.fpquick_type_app),
                remember(key) { appIcon(context, pkg) })
        }
        "shortcut" -> {
            val pkg = e.optString("pkg")
            ItemInfo(e.optString("label").ifEmpty { e.optString("id") }, e.optString("app").ifEmpty { pkg },
                remember(key) { appIcon(context, pkg) })
        }
        "url" -> ItemInfo(e.optString("label").ifEmpty { e.optString("url") }, e.optString("url"), null,
            MiuixIcons.Link)
        else -> {
            val a = e.optString("a")
            ItemInfo(stringResource(actionLabel(a)), stringResource(R.string.fpquick_type_action),
                remember(key) { actionIcon(context, a) })
        }
    }
}

/**
 * The items, long-press dragged to reorder - InstallerX Revived's DraggableManagedList, its
 * gesture and its arithmetic, on miuix rows: the dragged row follows the finger, the rows it
 * passes slide a row's height the other way, and the order is only saved on the drop.
 */
@Composable
private fun ItemList(items: List<JSONObject>, onMove: (List<JSONObject>) -> Unit, onRemove: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var confirm by remember { mutableStateOf<Int?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(items) }
    // Sync with the config, except for the one update our own drop caused.
    LaunchedEffect(items) {
        if (waiting) waiting = false else local = items
    }
    var dragged by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(0f) }
    val target by remember(rowPx, local.size) {
        derivedStateOf {
            val from = dragged
            if (from == null || rowPx <= 0f) null
            else (from + (dragY / rowPx + if (dragY > 0) 0.5f else -0.5f).toInt()).coerceIn(0, local.lastIndex)
        }
    }
    val rowColor = CardDefaults.defaultColors().color

    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(local.size) {
                if (local.size < 2) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (rowPx > 0) {
                            val index = (offset.y / rowPx).toInt()
                            if (index in local.indices) {
                                dragged = index
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragY += amount.y
                    },
                    onDragEnd = {
                        val from = dragged
                        val to = target
                        if (from != null && to != null && from != to) {
                            val list = local.toMutableList()
                            list.add(to, list.removeAt(from))
                            local = list
                            waiting = true
                            onMove(list)
                        }
                        dragged = null
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragged = null
                        dragY = 0f
                    })
            }
    ) {
        val seen = HashMap<String, Int>()
        local.forEachIndexed { index, e ->
            val text = e.toString()
            val n = seen.merge(text, 1, Int::plus) ?: 1
            key("$text#$n") {
                val isDragged = index == dragged
                val from = dragged
                val to = target
                val shift = when {
                    isDragged -> dragY
                    from != null && to != null && from < to && index in (from + 1)..to -> -rowPx
                    from != null && to != null && from > to && index in to until from -> rowPx
                    else -> 0f
                }
                val animated by animateFloatAsState(shift, label = "drag")
                val y = if (isDragged) dragY else if (dragged == null) 0f else animated
                val info = itemInfo(e)
                Box(
                    Modifier
                        .onGloballyPositioned { if (rowPx == 0f) rowPx = it.size.height.toFloat() }
                        .zIndex(if (isDragged || y != 0f) 10f else 0f)
                        .graphicsLayer {
                            translationY = y
                            val s = if (isDragged) 1.03f else 1f
                            scaleX = s
                            scaleY = s
                        }
                        .background(rowColor)
                ) {
                    BasicComponent(
                        title = info.title,
                        summary = info.summary,
                        startAction = { ItemIcon(info.icon, info.vector) },
                        endActions = { Pill(stringResource(R.string.fpquick_delete)) { confirm = index } })
                }
            }
        }
    }

    // InstallerX's MiuixDeleteNamedPackageConfirmationDialog.
    val pending = confirm
    AppDialog(show = pending != null, title = stringResource(R.string.fpquick_delete_confirm),
        onDismissRequest = { confirm = null }) {
        val name = pending?.let { local.getOrNull(it) }?.let { itemInfo(it).title }.orEmpty()
        Column {
            Text(stringResource(R.string.fpquick_delete_confirm_desc, name))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppDialogButton(modifier = Modifier.weight(1f),
                    text = stringResource(R.string.update_cancel),
                    onClick = { confirm = null })
                Spacer(Modifier.width(8.dp))
                AppDialogButton(modifier = Modifier.weight(1f),
                    text = stringResource(R.string.fpquick_delete),
                    role = AppDialogButtonRole.Destructive,
                    onClick = {
                        pending?.let(onRemove)
                        confirm = null
                    })
            }
        }
    }
}

/** InstallerX's pill: the row's one action, primary on a faint primary container. */
@Composable
private fun Pill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MiuixTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ItemIcon(icon: Bitmap?, vector: ImageVector? = null) {
    val mod = Modifier.padding(end = 12.dp).size(40.dp)
    when {
        icon != null -> Image(icon.asImageBitmap(), contentDescription = null, modifier = mod)
        vector != null -> GlyphChip(vector, mod)
        else -> Box(mod)
    }
}

/** A miuix glyph on the same dark chip FingerprintQuick.Chip puts SystemUI's glyphs on. */
@Composable
private fun GlyphChip(vector: ImageVector, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(Color(0xFF48484A)), contentAlignment = Alignment.Center) {
        Icon(vector, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(0.55f))
    }
}

/**
 * The OEM's own picture for each of its items, out of SystemUI - the one the menu shows -
 * rather than the app it opens, which 扫一扫, 搜索 and 添加日程 do not have.
 */
private fun nativeIcon(context: Context, id: Int): Bitmap? = runCatching {
    val name = when (id) {
        1001 -> "gxzw_quick_open_wechat_pay"
        1002 -> "gxzw_quick_open_wechat_scan"
        1003 -> if (runCatching {
                context.packageManager.getApplicationInfo("com.miui.voiceassist",
                    android.content.pm.PackageManager.GET_META_DATA).metaData?.getBoolean("is_super_ai")
            }.getOrNull() == true) "gxzw_quick_open_xiaoai_super" else "gxzw_quick_open_xiaoai"
        1004 -> "gxzw_quick_open_alipay_pay"
        1005 -> "gxzw_quick_open_alipay_scan"
        1006 -> "gxzw_quick_open_qr_code"
        1007 -> "gxzw_quick_open_search"
        else -> "gxzw_quick_open_add_event"
    }
    val res = context.packageManager.getResourcesForApplication(SYSUI)
    val rid = res.getIdentifier(name, "drawable", SYSUI)
    if (rid == 0) return null
    res.getDrawable(rid, null).toBitmap(120, 120)
}.getOrNull() ?: nativePackage(id)?.let { appIcon(context, it) }

/** The same pictures FingerprintQuick puts on the menu for each action. */
private fun actionIcon(context: Context, a: String): Bitmap? = when (a) {
    "camera" -> appIcon(context, "com.android.camera") ?: sysuiChip(context, "ic_camera")
    "recorder" -> appIcon(context, "com.android.soundrecorder")
    "torch" -> sysuiChip(context, "miui_keyguard_left_view_torchlight")
    else -> sysuiChip(context, "ic_qs_screenshot")
}

/** One of SystemUI's own glyphs on a chip, as FingerprintQuick.Chip draws it. */
private fun sysuiChip(context: Context, name: String): Bitmap? = runCatching {
    val res = context.packageManager.getResourcesForApplication(SYSUI)
    val id = res.getIdentifier(name, "drawable", SYSUI)
    if (id == 0) return null
    val glyph: Drawable = res.getDrawable(id, null).mutate()
    glyph.setTint(0xFFFFFFFF.toInt())
    val px = 120
    val b = createBitmap(px, px)
    val canvas = Canvas(b)
    canvas.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF48484A.toInt() })
    val inset = px / 4
    glyph.setBounds(inset, inset, px - inset, px - inset)
    glyph.draw(canvas)
    b
}.getOrNull()

/** The dialogs of 添加: the kind first, then the kind's own choice where it fits in one. */
@Composable
private fun AddDialogs(
    dialog: AddDialog,
    shown: List<JSONObject>,
    onDismiss: () -> Unit,
    onPick: (AddDialog) -> Unit,
    onApp: (forShortcut: Boolean) -> Unit,
    onAdd: (JSONObject) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    // 已满: the floating button stays live and says this rather than going grey.
    AppDialog(show = dialog == AddDialog.FULL, title = stringResource(R.string.fpquick_full_title),
        summary = stringResource(R.string.fpquick_full_desc, MAX_ITEMS), onDismissRequest = onDismiss) {
        AppDialogButton(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.fpquick_ok),
            onClick = onDismiss, role = AppDialogButtonRole.Primary)
    }
    AppDialog(show = dialog == AddDialog.RESET, title = stringResource(R.string.fpquick_reset),
        summary = stringResource(R.string.fpquick_reset_desc), onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppDialogButton(modifier = Modifier.weight(1f), text = stringResource(R.string.update_cancel),
                onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            AppDialogButton(modifier = Modifier.weight(1f), text = stringResource(R.string.fpquick_reset_do),
                role = AppDialogButtonRole.Destructive,
                onClick = { onDismiss(); onReset() })
        }
    }
    AppDialog(show = dialog == AddDialog.TYPE, title = stringResource(R.string.fpquick_add),
        onDismissRequest = onDismiss) {
        Column {
            BasicComponent(title = stringResource(R.string.fpquick_type_native),
                onClick = { onPick(AddDialog.NATIVE) })
            BasicComponent(title = stringResource(R.string.fpquick_type_app),
                onClick = { onDismiss(); onApp(false) })
            BasicComponent(title = stringResource(R.string.fpquick_type_shortcut),
                onClick = { onDismiss(); onApp(true) })
            BasicComponent(title = stringResource(R.string.fpquick_type_url),
                onClick = { onPick(AddDialog.URL) })
            BasicComponent(title = stringResource(R.string.fpquick_type_action),
                onClick = { onPick(AddDialog.ACTION) })
        }
    }

    val have = shown.filter { it.optString("t") == "native" }.map { it.optInt("id") }.toSet()
    val left = ALL_NATIVE.filter { it !in have }
    AppDialog(show = dialog == AddDialog.NATIVE, title = stringResource(R.string.fpquick_type_native),
        summary = if (left.isEmpty()) stringResource(R.string.fpquick_no_native) else null,
        onDismissRequest = onDismiss) {
        Column {
            for (id in left) {
                BasicComponent(title = stringResource(nativeLabel(id)),
                    startAction = { ItemIcon(remember(id) { nativeIcon(context, id) }) },
                    onClick = { onDismiss(); onAdd(native(id)) })
            }
        }
    }

    AppDialog(show = dialog == AddDialog.ACTION, title = stringResource(R.string.fpquick_type_action),
        onDismissRequest = onDismiss) {
        Column {
            for (a in ACTIONS) {
                BasicComponent(title = stringResource(actionLabel(a)),
                    startAction = { ItemIcon(remember(a) { actionIcon(context, a) }) },
                    onClick = { onDismiss(); onAdd(JSONObject().put("t", "action").put("a", a)) })
            }
        }
    }

    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AppDialog(show = dialog == AddDialog.URL, title = stringResource(R.string.fpquick_url_title),
        onDismissRequest = onDismiss) {
        val dismiss = LocalDismissState.current
        Column {
            TextField(value = url, onValueChange = { url = it },
                label = stringResource(R.string.fpquick_url), useLabelAsPlaceholder = true,
                modifier = Modifier.padding(bottom = 12.dp))
            TextField(value = label, onValueChange = { label = it },
                label = stringResource(R.string.fpquick_url_label), useLabelAsPlaceholder = true,
                modifier = Modifier.padding(bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                AppDialogButton(modifier = Modifier.weight(1f),
                    text = stringResource(R.string.update_cancel),
                    onClick = { dismiss?.invoke() })
                Spacer(Modifier.width(20.dp))
                AppDialogButton(modifier = Modifier.weight(1f),
                    text = stringResource(R.string.fpquick_url_add),
                    enabled = url.isNotBlank(),
                    onClick = {
                        val e = JSONObject().put("t", "url").put("url", url.trim())
                        if (label.isNotBlank()) e.put("label", label.trim())
                        url = ""
                        label = ""
                        onDismiss()
                        onAdd(e)
                    },
                    role = AppDialogButtonRole.Primary)
            }
        }
    }
}
