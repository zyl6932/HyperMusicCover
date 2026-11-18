package com.os4.musiccover.app
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.os4.musiccover.ui.screen.about.AboutPageContent
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The About page, as a screen of its own.
 *
 * It used to be the fourth tab of the pager. The settings page now has a row for it instead, and
 * the same reason the theme screen is an Activity applies: a whole screen arriving is the
 * platform's own transition, it brings its own back handling - gesture, button and the
 * predictive-back animation on Android 13+ - and a page swapped in place had to imitate all three.
 */
class AboutActivity : ComponentActivity() {

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
                // Nothing behind this screen in the pager any more, so the page is always the
                // one current: that flag only ever told the pager's off-screen copies to stop
                // animating the background shader, and a separate Activity has no such copies.
                AboutPageContent(
                    onBack = { finish() },
                    openLicensePage = {
                        startActivity(Intent(this@AboutActivity, LicenseActivity::class.java))
                    },
                    openCreditsPage = {
                        startActivity(Intent(this@AboutActivity, CreditsActivity::class.java))
                    },
                    isBlurEnabled = isBlurEnabled,
                )
            }
        }
    }
}
