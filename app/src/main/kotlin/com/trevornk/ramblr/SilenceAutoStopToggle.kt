package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Off-by-default toggle for silence-based auto-stop during dictation (#108, mode 1 only --
 * the "timed auto-mode / hands-free session" mode 2 from the same issue is explicitly out of
 * scope here). When enabled, [RecordingEngine]'s recording pipeline feeds each PCM chunk to a
 * Silero VAD model (see [com.k2fsa.sherpa.onnx.Vad]) and auto-triggers the same stop path a
 * manual tap would once trailing silence exceeds [SilenceAutoStopThreshold]'s configured seconds.
 *
 * Off by default (#108): this is a genuine behavior change to how recording ends, not a purely
 * cosmetic setting like [AutoPeekToggle], so it must be explicitly opted into. Enabling it also
 * triggers a one-time ~1-2MB model download (see [ModelDownloader]/[SileroVadModelDownload]) --
 * another reason this can't default to on.
 *
 * Backed by the same "ramblr" prefs file as the other Advanced/Behavior toggles, following the
 * same pattern as [AutoPeekToggle].
 */
object SilenceAutoStopToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "silence_auto_stop_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
