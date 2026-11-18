package com.os4.musiccover.app
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.os4.musiccover.ui.screen.features.ShadePageView
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The notification shade's settings, as a screen of its own.
 *
 * See [CoverActivity] for why this is an Activity rather than a page inside the features tab;
 * the reasoning is [LicenseActivity]'s and it is the transition that decides it.
 */
class ShadeActivity : ComponentActivity() {

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
                ShadePageView(
                    isBlurEnabled = isBlurEnabled,
                    refreshKey = resumes,
                    onBack = { finish() },
                )
            }
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
