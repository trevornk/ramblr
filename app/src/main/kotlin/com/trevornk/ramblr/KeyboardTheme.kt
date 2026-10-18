package com.trevornk.ramblr

import android.content.Context
import android.content.res.Configuration

/** Light/dark choice for the voice keyboard panel, independent of the rest of the app. */
internal enum class KeyboardTheme(val key: String, val title: String) {
    SYSTEM("system", "Match system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    /**
     * The configuration delta that forces this theme's night mode onto the panel's theme wrapper,
     * or null to follow the system. Only uiMode is set: a bare Configuration() defaults fontScale
     * to 1, which would silently undo the user's font size, so it is cleared to "undefined".
     */
    fun nightOverride(): Configuration? {
        val night = when (this) {
            SYSTEM -> return null
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        return Configuration().apply {
            fontScale = 0f
            uiMode = night
        }
    }

    companion object {
        const val KEY = "keyboard_theme"

        fun current(context: Context): KeyboardTheme {
            val stored = prefs(context).getString(KEY, null)
            return entries.firstOrNull { it.key == stored } ?: SYSTEM
        }

        fun set(context: Context, theme: KeyboardTheme) {
            prefs(context).edit().putString(KEY, theme.key).apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
    }
}
