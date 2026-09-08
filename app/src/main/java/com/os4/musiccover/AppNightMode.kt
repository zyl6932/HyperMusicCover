package com.os4.musiccover

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * Makes the system resolve this app's `-night` resources by the theme the user picked *inside*
 * the app rather than by the system's own setting.
 *
 * This exists for the splash screen. `windowSplashScreenBackground` is a theme attribute, and the
 * theme is resolved by the system before a line of our code runs - so with the app forced to
 * Light while the system is in dark mode (a scheduled dark theme, say), the splash came up black
 * in front of a light app. Nothing the activity does can fix that after the fact; the only lever
 * is the configuration the theme is resolved against, and this is the API for it.
 *
 * Setting it changes the app's configuration, which recreates the activity, so it is applied only
 * when the value actually changes. The mode is persisted by the system per application, so the
 * next cold start - splash included - already has it.
 */
object AppNightMode {

    private const val PREFS_NAME = "launch_background"
    private const val KEY_APPLIED = "night_mode"

    /**
     * MODE_NIGHT_AUTO is the way back to no override: the framework maps everything other than
     * YES and NO onto UI_MODE_NIGHT_UNDEFINED, which is "whatever the system says".
     */
    private fun nightModeFor(themeMode: String): Int = when (themeMode) {
        ColorSchemeMode.Light.name, ColorSchemeMode.MonetLight.name -> UiModeManager.MODE_NIGHT_NO
        ColorSchemeMode.Dark.name, ColorSchemeMode.MonetDark.name -> UiModeManager.MODE_NIGHT_YES
        else -> UiModeManager.MODE_NIGHT_AUTO
    }

    fun apply(context: Context, themeMode: String) {
        val wanted = nightModeFor(themeMode)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_APPLIED, Int.MIN_VALUE) == wanted) return
        try {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(wanted)
            prefs.edit { putInt(KEY_APPLIED, wanted) }
        } catch (_: Throwable) {
            // An OEM that refuses the call leaves the splash following the system, which is
            // where it was before this existed - not worth failing a launch over.
        }
    }
}
