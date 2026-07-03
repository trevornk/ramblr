package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * Global on/off gate for cleanup post-processing (#34), independent of *how* cleanup runs once
 * enabled (the waterfall step config from #32). Backed by the same "phonewhisper" prefs file and
 * "use_post_processing" key MainActivity's Settings switch already reads/writes, so flipping it
 * from the overlay badge, a Settings switch, or any other affordance all stay in sync automatically.
 */
object PostProcessingToggle {
    private const val PREFS_NAME = "phonewhisper"
    const val KEY = "use_post_processing"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    /** Pure gating decision: given the toggle state, should this transcript be routed through
     *  cleanup at all, or go straight to raw-transcript injection? */
    fun shouldRunCleanup(enabled: Boolean): Boolean = enabled

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
