package com.os4.musiccover.app
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.os4.musiccover.ui.screen.features.CoverPageView
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The lock screen cover's settings, as a screen of its own.
 *
 * A screen rather than a page swapped in place, which is the same choice [LicenseActivity] makes
 * and for the same reason: a whole screen arriving is the platform's own transition, it brings
 * its own back handling - gesture, button and the predictive-back animation on Android 13+ - and
 * the sub-page inside FeaturesPage that this replaces had to imitate all three and matched none
 * of them.
 *
 * Nothing is passed in. Like the licence list, this reads the app's own settings itself, so the
 * caller only has to name the class.
 */
class CoverActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val savedSettings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(savedSettings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }
        val isBlurEnabled = savedSettings.isBlurEnabled

        setContent {
            AppTheme(themeMode = themeMode, useOs4Ui = savedSettings.useOs4Ui, cardGlass = savedSettings.cardGlass) {
                CoverPageView(
                    isBlurEnabled = isBlurEnabled,
                    refreshKey = resumes,
                    onBack = { finish() },
                )
            }
        }
    }

    // The refresh key: bumped every time the screen comes back to the front, not the first time.
    // SystemUI can restart while this screen is in the background - from another app, or by
    // crashing - and a screen that kept its answer from before would show values the module no
    // longer holds, with switches that still look like they work.
    private var resumes by mutableIntStateOf(0)
    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        if (resumedOnce) resumes++ else resumedOnce = true
    }
}
