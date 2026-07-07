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
        }
    }
}
