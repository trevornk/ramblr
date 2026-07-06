package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * On-by-default toggle for whether the long-press style menu's "Hide icon" row (Feature B) is
 * offered at all. Turning this off in Advanced settings removes the menu row entirely -- it does
 * NOT force-restore an icon that's already hidden (see [IconHiddenState] for that separate,
 * always-available concern); it only stops new hides from being started via the long-press menu.
 * Backed by the same "phonewhisper" prefs file as [PerAppPersonaToggle]/[PreviewBeforeInjectToggle]
 * /[DebugVisibilityToggle], following the same pattern.
 */
object HideIconToggle {
    private const val PREFS_NAME = "phonewhisper"
    const val KEY = "hide_icon_menu_enabled"
    private const val DEFAULT = true

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
