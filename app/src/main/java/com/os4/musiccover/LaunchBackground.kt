package com.os4.musiccover

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit

/**
 * The colour the window is painted with before Compose has drawn anything.
 *
 * A cold start shows the system splash, and after the splash goes away there is a gap until the
 * first composition reaches the screen. Whatever `android:windowBackground` says fills that gap,
 * and the platform's Material.Light default made it a white flash.
 *
 * A static resource cannot get this right on its own: the palette also depends on the theme mode
 * the user picked *inside* the app (which can disagree with the system) and, for the Monet modes,
 * on the wallpaper. So the app remembers the surface colour it actually drew with, keyed by the
 * mode and the system's own light/dark setting, and paints the next launch with it. The static
 * colour in themes.xml is only ever seen on the very first launch of a given combination.
 */
object LaunchBackground {

    private const val PREFS_NAME = "launch_background"

    private fun key(themeMode: String, systemDark: Boolean): String =
        themeMode + "/" + (if (systemDark) "night" else "day")

    private fun systemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** The remembered colour for this mode, or null when nothing has been drawn with it yet. */
    fun peek(context: Context, themeMode: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val k = key(themeMode, systemDark(context))
        return if (prefs.contains(k)) prefs.getInt(k, 0) else null
    }

    /** Records what the UI is really drawing on, so the next launch can start from it. */
    fun remember(context: Context, themeMode: String, color: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val k = key(themeMode, systemDark(context))
        if (prefs.contains(k) && prefs.getInt(k, 0) == color) return
        prefs.edit { putInt(k, color) }
    }
}
