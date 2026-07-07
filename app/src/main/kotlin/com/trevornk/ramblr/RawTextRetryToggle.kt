package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * On-by-default toggle for whether a successful cleanup injection that actually changed the text
 * offers the "Tap to use raw text" undo bubble (see the `retryRawOffered` gate in
 * [WhisperAccessibilityService]'s injection-feedback logic). Turning this off in Advanced settings
 * means cleanup's normal completion feedback (or nothing, depending on [feedbackDurationMs]) shows
 * instead -- the raw transcript is simply not offered as a one-tap undo. This has no effect on the
 * clipboard-fallback "tap to copy again" bubble, which is a different code path (isFallback) than
 * this one (retryRawOffered). Backed by the same "ramblr" prefs file as [AutoPeekToggle]/
 * [HideIconToggle]/[PerAppPersonaToggle]/[PreviewBeforeInjectToggle]/[DebugVisibilityToggle],
 * following the same pattern.
 */
object RawTextRetryToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "raw_text_retry_enabled"
    private const val DEFAULT = true

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
