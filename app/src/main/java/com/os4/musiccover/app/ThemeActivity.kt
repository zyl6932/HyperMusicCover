package com.os4.musiccover.app
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.os4.musiccover.ui.screen.settings.ThemePageView
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The theme settings, as a screen of its own.
 *
 * The same choice [LicenseActivity] and [CoverActivity] make, for the same reason: a whole
 * screen arriving is the platform's own transition, it brings its own back handling - gesture,
 * button and the predictive-back animation on Android 13+ - and a page swapped in place inside
 * the settings tab had to imitate all three and matched none of them.
 *
 * The one difference from the screens above: they read their settings once and never change
 * them, while this one is a settings screen whose subject is the theme the screen itself is
 * drawn in. So the settings are held as state here, under [AppTheme], and every change is both
 * saved and recomposed - a change you could only see by going back would be a change made
 * blind.
 */
class ThemeActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // The whole settings record rather than the four fields this page edits: `save`
            // writes every key, so a partial copy would put the others back to their defaults.
            var settings by remember { mutableStateOf(AppSettings.load(this)) }
            val currentMode = try {
                ColorSchemeMode.valueOf(settings.themeMode)
            } catch (_: Exception) {
                ColorSchemeMode.System
            }

            fun update(change: (AppSettings) -> AppSettings) {
                settings = change(settings).also { AppSettings.save(this@ThemeActivity, it) }
            }

            AppTheme(
                themeMode = currentMode,
                useOs4Ui = settings.useOs4Ui,
                cardGlass = settings.cardGlass,
            ) {
                ThemePageView(
                    currentMode = currentMode,
                    onModeChange = { mode -> update { it.copy(themeMode = mode.name) } },
                    useOs4Ui = settings.useOs4Ui,
                    os4Supported = isRuntimeShaderSupported(),
                    onUseOs4UiChange = { on -> update { it.copy(useOs4Ui = on) } },
                    cardGlass = settings.cardGlass,
                    onCardGlassChange = { on -> update { it.copy(cardGlass = on) } },
                    isFloatingNavbar = settings.isFloatingNavbar,
                    onFloatingNavbarChange = { on -> update { it.copy(isFloatingNavbar = on) } },
                    isLiquidGlass = settings.isLiquidGlass,
                    onLiquidGlassChange = { on -> update { it.copy(isLiquidGlass = on) } },
                    isBlurEnabled = settings.isBlurEnabled,
                    onBlurEnabledChange = { on -> update { it.copy(isBlurEnabled = on) } },
                    onBack = { finish() },
                )
            }
        }
    }
}
