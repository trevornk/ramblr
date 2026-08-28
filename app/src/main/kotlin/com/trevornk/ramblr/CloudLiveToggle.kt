package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Opt-in switch for cloud *live* (streaming) transcription (#233 Phase 1).
 *
 * OFF by default, and deliberately so: live transcription streams audio to Gemini for the whole
 * duration of a recording rather than uploading one file at the end, which costs roughly 1.8x the
 * batch price and has had no real-device validation yet. Everything about it is experimental, so
 * it has to be a thing the user reaches for, never a thing that happens to them.
 *
 * The toggle alone does NOT make live transcription run -- [CloudLiveWiring.factoryOrNull] is the
 * single place that decides, and it additionally requires cloud transcription to be selected
 * (`use_local == false`) and a Gemini credential to exist. This object owns only the user's
 * stated intent; it is not a capability check.
 *
 * Same "ramblr" prefs file and object shape as [VocabularySuggestionsToggle] / [RawTextRetryToggle]
 * / the rest of the toggle family.
 */
object CloudLiveToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "cloud_live_transcription_enabled"
    const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
