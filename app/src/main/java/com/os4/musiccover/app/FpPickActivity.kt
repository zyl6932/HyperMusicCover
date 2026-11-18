package com.os4.musiccover.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.os4.musiccover.R
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import com.os4.musiccover.ui.util.AppCard
import com.os4.musiccover.ui.util.firstCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 指纹快捷功能's pickers, each a screen of its own for the same reason [CoverActivity] is: the
 * platform's transition in and out, and its back handling with it. They answer with
 * [EXTRA_ENTRY], the item's JSON as FpQuickActivity stores it.
 *
 * The page itself is InstallerX Revived's MiuixApplyPage: miuix's InputField for the search, one
 * lazy item per row shaped as segments of a card, icons loaded as their rows come into view.
 */
internal const val EXTRA_ENTRY = "entry"
private const val EXTRA_SHORTCUT = "shortcut"
private const val EXTRA_PKG = "pkg"
private const val EXTRA_LABEL = "label"

/** The launchable apps; with [EXTRA_SHORTCUT], an app is a step towards one of its shortcuts. */
class FpAppPickActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context, forShortcut: Boolean): Intent =
            Intent(context, FpAppPickActivity::class.java).putExtra(EXTRA_SHORTCUT, forShortcut)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    // The shortcut chosen one screen further in comes back through here and goes straight on out.
    private val shortcut = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && it.data != null) {
            setResult(Activity.RESULT_OK, it.data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.load(this)
        val theme = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
            .getOrDefault(ColorSchemeMode.System)
        val forShortcut = intent.getBooleanExtra(EXTRA_SHORTCUT, false)
        setContent {
            AppTheme(themeMode = theme, useOs4Ui = settings.useOs4Ui, cardGlass = settings.cardGlass) {
                AppPicker(settings.isBlurEnabled, forShortcut, ::finish) { app ->
                    if (forShortcut) {
                        shortcut.launch(Intent(this, FpShortcutPickActivity::class.java)
                            .putExtra(EXTRA_PKG, app.pkg).putExtra(EXTRA_LABEL, app.label))
                    } else {
                        answer(JSONObject().put("t", "app").put("pkg", app.pkg).put("label", app.label))
                    }
                }
            }
        }
    }
}

/** One app's launcher shortcuts, asked of SystemUI. */
class FpShortcutPickActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.load(this)
        val theme = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
            .getOrDefault(ColorSchemeMode.System)
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val appLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        setContent {
            AppTheme(themeMode = theme, useOs4Ui = settings.useOs4Ui, cardGlass = settings.cardGlass) {
                ShortcutPicker(settings.isBlurEnabled, pkg, ::finish) { sc ->
                    answer(JSONObject().put("t", "shortcut").put("pkg", pkg).put("id", sc.id)
                        .put("label", sc.label).put("app", appLabel))
                }
            }
        }
    }
}

private fun Activity.answer(entry: JSONObject) {
    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ENTRY, entry.toString()))
    finish()
}

internal fun appIcon(context: Context, pkg: String): Bitmap? = runCatching {
    context.packageManager.getApplicationIcon(pkg).toBitmap(120, 120)
}.getOrNull()

/** A launchable app for the picker; its icon is loaded when its row comes into view. */
private class AppEntry(val pkg: String, val label: String)

/** A segment of one card drawn as separate lazy items, as MiuixApplyPage shapes its rows. */
private fun segmentShape(index: Int, size: Int): RoundedCornerShape {
    val r = CardDefaults.CornerRadius
    return when {
        size == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r, bottomStart = 0.dp, bottomEnd = 0.dp)
        index == size - 1 -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/** MiuixApplyPage's row: 40dp icon, title4 label, the package under it. */
@Composable
private fun PickerRow(
    modifier: Modifier,
    shape: RoundedCornerShape,
    icon: ImageBitmap?,
    title: String,
    summary: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardDefaults.defaultColors().color)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // miuix's own press feedback (its theme's indication) where InstallerX has
                // material3's ripple, which this app does not carry.
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            else Box(Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.title4)
                if (summary != null) {
                    Text(summary, style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }
        }
    }
}

/** Loading, as MiuixApplyPage shows it: the spinner and a word, centred. */
@Composable
private fun Loading() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InfiniteProgressIndicator()
        Text(stringResource(R.string.fpquick_loading), style = MiuixTheme.textStyles.main)
    }
}

private val placement = spring(stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold)

@Composable
private fun AppPicker(blur: Boolean, forShortcut: Boolean, onBack: () -> Unit, onPick: (AppEntry) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    // For 「App 快捷方式」 only the apps that have one (the user, 2026-10-21); SystemUI knows which.
    var unanswered by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val icons = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(Unit) {
        val having = if (forShortcut) ModuleBridge.fpQuickShortcutApps(context) else null
        if (forShortcut && having == null) {
            unanswered = true
            return@LaunchedEffect
        }
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { having == null || it.activityInfo.packageName in having }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }
    PageScaffold(title = stringResource(R.string.fpquick_pick_app), isBlurEnabled = blur, onBack = onBack) {
        item(key = "search") {
            InputField(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp),
                query = query,
                onQueryChange = { query = it },
                label = stringResource(R.string.fpquick_search),
                expanded = false,
                onExpandedChange = {},
                onSearch = {})
        }
        val list = apps
        if (unanswered) {
            item(key = "none") {
                AppCard(Modifier.firstCard()) {
                    BasicComponent(title = stringResource(R.string.fpquick_no_answer))
                }
            }
        } else if (list == null) {
            item(key = "loading") { Loading() }
        } else {
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) list else list.filter { q in it.label.lowercase() || q in it.pkg }
            itemsIndexed(shown, key = { _, app -> app.pkg }, contentType = { _, _ -> "app" }) { index, app ->
                // Loaded as the row comes into view, as MiuixApplyPage does.
                LaunchedEffect(app.pkg) {
                    if (app.pkg !in icons) {
                        withContext(Dispatchers.IO) { appIcon(context, app.pkg) }
                            ?.let { icons[app.pkg] = it.asImageBitmap() }
                    }
                }
                PickerRow(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .zIndex(-index.toFloat())
                        .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placement),
                    shape = segmentShape(index, shown.size),
                    icon = icons[app.pkg],
                    title = app.label,
                    summary = app.pkg,
                    onClick = { onPick(app) })
            }
            item(key = "end") { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ShortcutPicker(
    blur: Boolean,
    pkg: String,
    onBack: () -> Unit,
    onPick: (ModuleBridge.FpShortcut) -> Unit,
) {
    val context = LocalContext.current
    val state by produceState<Pair<Boolean, List<ModuleBridge.FpShortcut>?>>(false to null, pkg) {
        value = true to ModuleBridge.fpQuickShortcuts(context, pkg)
    }
    val (asked, shortcuts) = state
    PageScaffold(title = stringResource(R.string.fpquick_pick_shortcut), isBlurEnabled = blur, onBack = onBack) {
        when {
            !asked -> item(key = "loading") { Loading() }
            shortcuts == null || shortcuts.isEmpty() -> item(key = "none") {
                AppCard(Modifier.firstCard()) {
                    BasicComponent(title = stringResource(
                        if (shortcuts == null) R.string.fpquick_no_answer else R.string.fpquick_no_shortcut))
                }
            }
            else -> {
                item(key = "top") { Spacer(Modifier.height(12.dp)) }
                itemsIndexed(shortcuts, key = { _, sc -> sc.id }) { index, sc ->
                    PickerRow(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        shape = segmentShape(index, shortcuts.size),
                        icon = remember(sc.id) { sc.icon?.asImageBitmap() },
                        title = sc.label,
                        summary = null,
                        onClick = { onPick(sc) })
                }
            }
        }
    }
}
