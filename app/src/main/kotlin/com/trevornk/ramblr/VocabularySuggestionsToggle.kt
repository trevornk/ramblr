package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Master on/off switch for smart vocabulary suggestions (#216). On by default: the feature is
 * pure on-device counting with an explicit-accept UI, so it's safe to have running, and it's
 * useless if nobody discovers the toggle first.
 *
 * Turning it OFF is a privacy statement, so [setEnabled] (false) also clears every accumulated
 * candidate counter via [VocabularySuggestionStore.clearCandidates] — off means nothing is
 * retained, not just nothing is shown. The dismissed-suggestions list survives, because it is
 * the user's own explicit decisions rather than collected telemetry.
 *
 * Same "ramblr" prefs file and object shape as [RawTextRetryToggle] / [AutoPeekToggle] / the
 * rest of the toggle family.
 */
object VocabularySuggestionsToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "vocab_suggestions_enabled"
    private const val DEFAULT = true

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        if (!enabled) VocabularySuggestionStore.clearCandidates(prefs)
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
