package com.os4.musiccover.ui.screen.settings

import com.os4.musiccover.ui.util.nextCard
import com.os4.musiccover.ui.util.AppCard
import com.os4.musiccover.ui.util.firstCard
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.os4.musiccover.BuildConfig
import com.os4.musiccover.LauncherIcon
import com.os4.musiccover.app.AboutActivity
import com.os4.musiccover.app.DonateActivity
import com.os4.musiccover.app.LocaleHelper
import com.os4.musiccover.LogLevel
import com.os4.musiccover.app.LsposedService
import com.os4.musiccover.R
import com.os4.musiccover.app.SettingsBackup
import com.os4.musiccover.app.ThemeActivity
import com.os4.musiccover.updater.UpdateCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.os4.musiccover.ui.util.AppDropdownPreference
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun SettingsPageView(
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    onShowWelcome: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            // Suspends: the module's own parameters come back over a broadcast, so an export
            // cannot be assembled synchronously in the picker's callback.
            scope.launch {
                try {
                    val json = SettingsBackup.export(context)
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(json.toByteArray())
                    }
                    Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val stream = context.contentResolver.openInputStream(it)
                val reader = BufferedReader(InputStreamReader(stream))
                val json = reader.readText()
                reader.close()
                stream?.close()
                SettingsBackup.import(context, json)
                Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
                activity?.recreate()
            } catch (_: Exception) {
                Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    PageScaffold(
        title = stringResource(R.string.tab_settings),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "settings") {
            Column {
                SmallTitle(text = stringResource(R.string.settings_interface))
                AppCard(
                    modifier = Modifier.nextCard()
                ) {
                    // Everything that repaints the app - theme mode, the floating bar, its glass,
                    // the blur - is on the screen this opens. What is left here is the one
                    // interface setting that is not about how the app looks but about whether it
                    // can be found at all.
                    //
                    // A screen of its own, not a page swapped in place: the platform then supplies
                    // the transition and the back handling, including the predictive-back
                    // animation, which a page in here would have to imitate.
                    ArrowPreference(
                        title = stringResource(R.string.settings_theme),
                        summary = stringResource(R.string.settings_theme_summary),
                        onClick = {
                            context.startActivity(Intent(context, ThemeActivity::class.java))
                        }
                    )

                    // The welcome page is shown by itself only on a first launch; this is how it
                    // is seen again. It does not touch welcomeShown, so it never becomes a
                    // second first launch.
                    ArrowPreference(
                        title = stringResource(R.string.welcome_show_again),
                        summary = stringResource(R.string.welcome_show_again_summary),
                        onClick = onShowWelcome,
                    )

                    var iconHidden by remember { mutableStateOf(LauncherIcon.isHidden(context)) }
                    SwitchPreference(
                        title = stringResource(R.string.hide_launcher_icon),
                        summary = stringResource(R.string.hide_launcher_icon_summary),
                        checked = iconHidden,
                        onCheckedChange = {
                            LauncherIcon.setHidden(context, it)
                            iconHidden = it
                        }
                    )
                }

                SmallTitle(text = stringResource(R.string.settings_language))
                AppCard(
                    modifier = Modifier.nextCard()
                ) {
                    val languageNames = listOf(
                        stringResource(R.string.language_default),
                        stringResource(R.string.language_zh_cn),
                        stringResource(R.string.language_en)
                    )
                    val languageValues = listOf(
                        LocaleHelper.Language.SYSTEM,
                        LocaleHelper.Language.ZH_CN,
                        LocaleHelper.Language.EN
                    )
                    val savedLanguage = LocaleHelper.getSavedLanguage(context)
                    val langCurrentIndex =
                        languageValues.indexOf(savedLanguage).takeIf { it >= 0 } ?: 0
                    var langExpanded by remember { mutableStateOf(false) }

                    AppDropdownPreference(
                        title = stringResource(R.string.settings_language),
                        summary = languageNames[langCurrentIndex],
                        items = languageNames,
                        selectedIndex = langCurrentIndex,
                        onSelectedIndexChange = {
                            LocaleHelper.setLanguage(context, languageValues[it])
                            activity?.recreate()
                        },
                        onExpandedChange = { langExpanded = it }
                    )
                }

                // For the user filing a report rather than for everyday use, so it comes after
                // everything that changes the app and before the data rows only for grouping's
                // sake. Every process the module is in follows it at once (LsposedService).
                SmallTitle(text = stringResource(R.string.settings_debug))
                AppCard(
                    modifier = Modifier.nextCard()
                ) {
                    val levelNames = listOf(
                        stringResource(R.string.log_level_normal),
                        stringResource(R.string.log_level_verbose),
                    )
                    val levelValues = listOf(LogLevel.NORMAL, LogLevel.VERBOSE)
                    var level by remember { mutableStateOf(LsposedService.logLevel(context)) }
                    AppDropdownPreference(
                        title = stringResource(R.string.log_level),
                        summary = stringResource(R.string.log_level_summary),
                        items = levelNames,
                        selectedIndex = levelValues.indexOf(level).coerceAtLeast(0),
                        onSelectedIndexChange = {
                            level = levelValues[it]
                            LsposedService.setLogLevel(context, level)
                        },
                    )
                }

                SmallTitle(text = stringResource(R.string.settings_data))
                AppCard(
                    modifier = Modifier.nextCard()
                ) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.export_settings),
                            summary = stringResource(R.string.export_settings_summary),
                            onClick = { exportLauncher.launch("HyperMusicCover_settings.json") }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.import_settings),
                            summary = stringResource(R.string.import_settings_summary),
                            onClick = { importLauncher.launch("application/json") }
                        )
                    }
                }

                // The About page's old tab, as a row: the version is what most people came here
                // to read, and when there is a release waiting that is worth saying on the way in.
                // A screen of its own, like the theme screen above.
                SmallTitle(text = stringResource(R.string.tab_about))
                AppCard(
                    modifier = Modifier.nextCard()
                ) {
                    // The check runs once when the app starts (MainActivity); this row only picks
                    // up the answer. `UpdateCheck.result` is a plain volatile field, not Compose
                    // state, so an answer that lands after this page composed would never show -
                    // hence the poll. Bounded, and it also stops as soon as a check has been
                    // recorded either way (`isFresh`), so a page left open with no network does
                    // not poll for the life of the process.
                    var update by remember { mutableStateOf(UpdateCheck.result) }
                    LaunchedEffect(Unit) {
                        repeat(60) {
                            if (update != null || UpdateCheck.isFresh()) return@LaunchedEffect
                            delay(500)
                            update = UpdateCheck.result
                        }
                    }
                    val summary = update?.versionName
                        ?.let { stringResource(R.string.update_available, it) }
                        ?: BuildConfig.VERSION_NAME

                    ArrowPreference(
                        title = stringResource(R.string.app_name),
                        summary = summary,
                        // Same colour the About page's own "update available" line uses, so the
                        // two readings of the same fact look alike; the version number on its
                        // own keeps the secondary colour every other summary has.
                        summaryColor = BasicComponentDefaults.summaryColor(
                            color = if (update != null) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        ),
                        onClick = {
                            context.startActivity(Intent(context, AboutActivity::class.java))
                        }
                    )
                    // The donation row used to be the last card on the home page. It sits with
                    // the About row now, one card rather than two: both are about the app itself
                    // rather than about anything it does.
                    ArrowPreference(
                        title = stringResource(R.string.donate_title),
                        summary = stringResource(R.string.donate_home_summary),
                        startAction = {
                            Icon(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(28.dp),
                                imageVector = Icons.Rounded.Favorite,
                                // Fixed rather than themed: the heart is the whole signal here,
                                // and in the dynamic-colour scheme it would otherwise take
                                // whatever colour the wallpaper happened to give it.
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
    }
}
