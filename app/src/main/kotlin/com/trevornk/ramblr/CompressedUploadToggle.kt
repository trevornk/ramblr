package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Off-by-default toggle for compressed (AAC/M4A) cloud transcription uploads (#109). When
 * enabled, [WhisperAccessibilityService.startRecording] composes an [AacEncoderSession] into
 * the same `onChunk` tee [RecordingEngine] already feeds to streaming preview and #108's
 * [SilenceAutoStopSession], so a finished `.m4a` is ready the instant recording stops. Cloud
 * transcription ([TranscriberClient]/[GeminiTranscriberClient]) then uploads that compressed
 * file instead of the raw WAV, which is roughly an order of magnitude smaller -- worthwhile on
 * cellular, irrelevant on Wi-Fi.
 *
 * Off by default (#109's own scope warning): this is a genuine new native-codec lifecycle
 * (MediaCodec encode + MediaMuxer container finalization) sitting in the critical path of every
 * cloud dictation, and the issue's own estimates are explicitly unverified engineering guesses,
 * not measured transcription-quality (WER) results. A default-off, explicit opt-in is the only
 * responsible way to ship this until it's been exercised on real devices against real cellular
 * uploads -- mirrors [SilenceAutoStopToggle]'s reasoning for the same kind of "behavior change
 * that needs real-world validation before going default" call.
 *
 * When off, zero [AacEncoderSession] instantiation and byte-for-byte identical upload behavior
 * to before this feature existed -- the `onChunk` composition in `startRecording` simply omits
 * this consumer, the same way an unconfigured [SilenceAutoStopSession] is omitted today.
 */
object CompressedUploadToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "compressed_upload_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
