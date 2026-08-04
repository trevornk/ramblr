package com.trevornk.ramblr

/**
 * Joins per-segment transcription results (#132) back into one dictation string.
 *
 * Kept separate from [SpeechSegmenter] so the text-assembly rules are unit-testable on their own:
 * a segmented decode produces one result per speech segment, and silence-only segments or a
 * model that returns nothing for a segment must not introduce stray whitespace into the text the
 * user gets injected.
 */
object SegmentedTranscript {
    /** Joins [segments] with single spaces, dropping blank/whitespace-only results. */
    fun join(segments: List<String>): String =
        segments.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}
