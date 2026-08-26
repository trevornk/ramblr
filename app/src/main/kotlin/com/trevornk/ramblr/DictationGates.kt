package com.trevornk.ramblr

/**
 * Cheap pre-pipeline gates for dictations that cannot possibly produce useful text (M3, #192).
 *
 * Both are pure functions living outside [WhisperAccessibilityService] so they're directly
 * unit-testable (same extraction pattern as [resolveLateRecording]/[TranscriptionChain]): the
 * service decides *where* to apply them, this file owns *what* they mean.
 */

/** Recordings shorter than this cannot contain usable speech: a tap-tap dictation with ~0.2s of
 *  near-silence previously still paid for a full local decode or cloud upload only to produce a
 *  blank transcript (M3a). 300ms is comfortably below any real one-word utterance. */
const val MIN_RECORDING_DURATION_MS = 300L

/**
 * True when a PCM recording of [pcmByteCount] bytes is under [MIN_RECORDING_DURATION_MS] of
 * audio. Duration is derived from the byte count of the raw capture format --
 * [bytesPerSampleFrame] defaults to 16-bit mono ([RecordingEngine]'s fixed
 * `ENCODING_PCM_16BIT`/`CHANNEL_IN_MONO` configuration), i.e. 32 bytes per millisecond at 16kHz.
 */
fun isBelowMinimumDuration(
    pcmByteCount: Long,
    sampleRateHz: Int,
    bytesPerSampleFrame: Int = 2,
): Boolean {
    val bytesPerMs = sampleRateHz.toLong() * bytesPerSampleFrame / 1000L
    return pcmByteCount < MIN_RECORDING_DURATION_MS * bytesPerMs
}

/**
 * True when a non-blank transcript is content-free junk that must skip the cleanup waterfall and
 * be injected raw instead (M3b): ASR hallucinations like "." or "…" pass `isNotBlank()` but have
 * nothing for a cleanup model to improve, so running the full waterfall (network calls,
 * LOCAL_LLM load) on them is pure waste. Junk means: no letter or digit anywhere, or fewer than
 * 2 non-whitespace-trimmed chars. Deliberately conservative -- any transcript with real content
 * ("ok", "a 5") is untouched.
 */
fun isJunkTranscript(transcript: String): Boolean {
    val trimmed = transcript.trim()
    return trimmed.length < 2 || trimmed.none { it.isLetterOrDigit() }
}
