package com.os4.musiccover.app
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.os4.musiccover.ui.screen.about.CreditsPageContent
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The credits, reached from the About page's row under the licence list - the same shape as
 * [LicenseActivity], which is the page it sits beside.
 */
class CreditsActivity : ComponentActivity() {

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

        setContent {
            AppTheme(themeMode = themeMode, useOs4Ui = savedSettings.useOs4Ui, cardGlass = savedSettings.cardGlass) {
                CreditsPageContent(
                    onBack = { finish() },
                    isBlurEnabled = savedSettings.isBlurEnabled,
                )
            }
        }
    }
}
