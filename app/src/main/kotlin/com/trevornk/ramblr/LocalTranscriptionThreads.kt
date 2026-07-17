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
 * Defaults to [DEFAULT_THREADS] = 2, the value every call site was already hardcoded to before
 * this setting existed, so shipping this is purely additive: nobody who never opens this setting
 * sees any change in behavior. This is deliberate -- unlike [LlamaCppInference.DEFAULT_NUM_THREADS]
 * (a single value tuned once against real hardware and hardcoded), the right thread count for
 * local STT decode depends on which model + device is in play and is exactly the kind of call
 * Trevor wants to make himself from real on-device usage, not something this change should guess
 * at. Presets are 2 (current default)/4/6, matching the range #107 asks to A/B; existing pipeline
 * timings already captured per-dictation by [BenchmarkLogger] (`transcription` stage
 * `latencyMs`, and `pipeline.stopToDrainMs`/`pipeline.totalMs` from #115) are what he can compare
 * across a setting change, via the benchmark log export already on the Data & Logs screen.
 */
object LocalTranscriptionThreads {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "local_transcription_threads"
    const val DEFAULT_THREADS = 2
    const val MIN_THREADS = 1
    const val MAX_THREADS = 8

    /** Presets surfaced in the settings picker -- the 2 (default)/4/6 range #107 asks to A/B. */
    val PRESET_THREADS = listOf(2, 4, 6)

    fun threadsOrDefault(prefs: SharedPreferences): Int =
        prefs.getInt(KEY, DEFAULT_THREADS).coerceIn(MIN_THREADS, MAX_THREADS)

    fun setThreads(prefs: SharedPreferences, threads: Int) {
        prefs.edit().putInt(KEY, threads.coerceIn(MIN_THREADS, MAX_THREADS)).apply()
    }

    fun threadsOrDefault(context: Context): Int = threadsOrDefault(prefs(context))

    fun setThreads(context: Context, threads: Int) = setThreads(prefs(context), threads)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
