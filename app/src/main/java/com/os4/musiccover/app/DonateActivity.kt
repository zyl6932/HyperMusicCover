package com.os4.musiccover.app
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.os4.musiccover.ui.screen.donate.DonatePageContent
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The payment codes, reached from the home page.
 *
 * A screen of its own, like [LicenseActivity] and [CoverActivity]: the platform supplies the
 * transition and the back handling, and nothing here has to imitate either.
 */
class DonateActivity : ComponentActivity() {

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
                DonatePageContent(
                    onBack = { finish() },
                    isBlurEnabled = savedSettings.isBlurEnabled,
                )
            }
        }
    }
}
