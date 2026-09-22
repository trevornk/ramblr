package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * User-configurable thread count for on-device (local) transcription decode via sherpa-onnx
 * (#107), wired into every `numThreads` config field in [LocalTranscriber.detectModelConfig].
 * Deliberately does NOT touch [StreamingTranscriber]'s separately-tuned real-time streaming
 * preview decoder (own hardcoded `numThreads = 2`, different latency/CPU-sharing constraints)
 * -- that value is intentionally out of scope here.
 *
 * The default is hardware-aware following the F-Droid Redmi Note 8T review: its eight cores were
 * left at a flat two batch-decode threads despite 20.6-21.9 seconds before text appeared. Two
 * cores remain reserved for recording/UI work and the default stops at six, while the existing
 * one-to-eight user setting remains authoritative whenever the user has chosen a value. This
 * changes only unset preferences; upgrading never replaces a stored choice.
 */
object LocalTranscriptionThreads {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "local_transcription_threads"
    const val MIN_THREADS = 1
    const val MAX_THREADS = 8
    const val DEFAULT_MAX_THREADS = 6
    private const val RESERVED_SYSTEM_THREADS = 2

    /** Process-wide hardware-derived fallback for direct callers without a preferences instance. */
    val DEFAULT_THREADS = defaultForAvailableProcessors(Runtime.getRuntime().availableProcessors())

    /** Presets surfaced in the settings picker -- the 2/4/6 range #107 asks to A/B. */
    val PRESET_THREADS = listOf(2, 4, 6)

    /** Pure default calculation: leave two cores for audio/UI and never exceed either supported
     * range or the six-thread practical ceiling. */
    fun defaultForAvailableProcessors(availableProcessors: Int): Int =
        (availableProcessors - RESERVED_SYSTEM_THREADS).coerceIn(MIN_THREADS, minOf(MAX_THREADS, DEFAULT_MAX_THREADS))

    /** A stored setting is an explicit user decision and therefore wins over the hardware default. */
    fun threadsOrDefault(
        prefs: SharedPreferences,
        availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    ): Int = if (prefs.contains(KEY)) {
        prefs.getInt(KEY, DEFAULT_THREADS).coerceIn(MIN_THREADS, MAX_THREADS)
    } else {
        defaultForAvailableProcessors(availableProcessors)
    }

    fun setThreads(prefs: SharedPreferences, threads: Int) {
        prefs.edit().putInt(KEY, threads.coerceIn(MIN_THREADS, MAX_THREADS)).apply()
    }

    fun threadsOrDefault(context: Context): Int = threadsOrDefault(prefs(context))

    fun setThreads(context: Context, threads: Int) = setThreads(prefs(context), threads)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
