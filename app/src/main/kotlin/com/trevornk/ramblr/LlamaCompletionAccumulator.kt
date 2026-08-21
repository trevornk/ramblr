package com.trevornk.ramblr

/**
 * Pure accumulation logic for [LlamaCppInference.complete]'s generation loop (#60), pulled out so
 * the cap behavior is directly unit-testable against a fake piece supplier -- [LlamaCppInference]
 * itself can't be exercised in a plain JVM test (its companion `init` block loads a native
 * library that isn't present outside a real device build; see that class's kdoc). This mirrors
 * how [CleanupWaterfallPlanner]/[LocalInferenceEngine] keep pure/fakeable logic separate from
 * their JNI- or I/O-bound callers.
 */
object LlamaCompletionAccumulator {
    /** Tail window compared for verbatim repetition; see [isDegenerateLoop] for the tuning data. */
    const val LOOP_WINDOW_CHARS = 48

    /** How many non-overlapping occurrences of that window constitute a generation cycle. */
    const val LOOP_REPEATS = 3

    /**
     * Repeatedly calls [nextPiece] and appends its result until it returns [endOfGeneration], up
     * to [maxPieces] pieces. Throws [IllegalStateException] if the cap is hit first -- callers
     * (see [LlamaCppInference.complete]) are expected to run their own native teardown (e.g.
     * `stopCompletion`) before propagating that exception, since this function has no knowledge
     * of the native handle.
     *
     * Exists because completion previously ran unbounded until the model emitted its own
     * end-of-generation token, with the only backstop being the native context-size overflow
     * check (which only fires once the entire context window, thousands of tokens, is consumed).
     * A model that rambles or never emits an end-of-generation token could run for minutes of
     * real on-device CPU inference with no way out except the 400s transcription watchdog --
     * this makes that fail fast instead.
     */
    fun accumulate(
        maxPieces: Int,
        endOfGeneration: String,
        deadlineAtMs: Long = Long.MAX_VALUE,
        nowMs: () -> Long = System::currentTimeMillis,
        isCancelled: () -> Boolean = { false },
        loopWindowChars: Int = LOOP_WINDOW_CHARS,
        loopRepeats: Int = LOOP_REPEATS,
        nextPiece: () -> String,
    ): String {
        val response = StringBuilder()
        var pieceCount = 0
        while (true) {
            // Wall-clock and cancellation bounds (#83): the piece cap bounds the loop count, but
            // a slow model can take seconds per piece, blocking the waterfall far past its hard
            // cap with no way for the user's cancel to stop the CPU burn. Checked before every
            // piece; a tripped bound throws the same way the cap does, so it falls through the
            // waterfall like any other local failure.
            if (isCancelled()) {
                throw IllegalStateException("Local completion cancelled")
            }
            if (nowMs() > deadlineAtMs) {
                throw IllegalStateException("Local completion exceeded its wall-clock deadline")
            }
            val piece = nextPiece()
            if (piece == endOfGeneration) {
                return response.toString()
            }
            response.append(piece)
            pieceCount++
            if (pieceCount >= maxPieces) {
                throw IllegalStateException(
                    "Local model exceeded max response length ($maxPieces pieces) without emitting an end-of-generation token"
                )
            }
            // Degenerate-repetition bound (#179): a model can fall into a cycle it never escapes
            // and generate until the piece cap, burning seconds of on-device CPU to produce text
            // the validator will reject anyway. The sampler chain (min_p -> temp -> dist) carries
            // no repetition penalty, and at temperature 0.0 sampling is effectively greedy, which
            // makes such a cycle perfectly stable -- nothing perturbs it. Detecting the loop here
            // rather than adding a penalty to the sampler keeps *healthy* generations bit-for-bit
            // unchanged: a penalty alters token choice on every completion, this only ever fires
            // after text has already repeated verbatim.
            if (isDegenerateLoop(response, loopWindowChars, loopRepeats)) {
                throw IllegalStateException(
                    "Local model generation collapsed into a repeating loop " +
                        "(last $loopWindowChars characters repeated $loopRepeats times)"
                )
            }
        }
    }

    /**
     * True when the final [windowChars] characters of [text] already occur at least [repeats]
     * times within it -- the signature of a generation cycle.
     *
     * Window and repeat count were tuned against real model output rather than picked by feel
     * (#179): across every transcript of 60+ characters in a real dictation history, the healthy
     * cleaned outputs peak at 225 characters and no (window, repeats) pair at or above 32/2 fires
     * on any of them, nor on the raw transcripts themselves (the natural-repetition control --
     * dictation genuinely repeats phrases, and cleanup must preserve what the speaker said). The
     * defaults sit well clear of that boundary: the degenerate case trips at 683 characters, ~3x
     * the longest legitimate output and well short of the 2197 characters it would otherwise
     * produce. Cleanup should never expand its input, so a response long enough to contain three
     * copies of a 48-character window is already outside intended behavior.
     *
     * Guarded so a window longer than the text (or a non-positive setting, which disables the
     * check) can never index out of bounds.
     */
    private fun isDegenerateLoop(text: CharSequence, windowChars: Int, repeats: Int): Boolean {
        if (windowChars <= 0 || repeats < 2 || text.length < windowChars * repeats) {
            return false
        }
        val window = text.subSequence(text.length - windowChars, text.length).toString()
        var found = 0
        var from = 0
        while (true) {
            val at = text.indexOf(window, from)
            if (at < 0) {
                return false
            }
            found++
            if (found >= repeats) {
                return true
            }
            // Non-overlapping: two halves of one long run shouldn't count as a cycle.
            from = at + windowChars
        }
    }
}
