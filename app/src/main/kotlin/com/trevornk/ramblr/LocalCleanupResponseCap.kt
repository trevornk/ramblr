package com.trevornk.ramblr

/**
 * How many tokens ([LlamaCompletionAccumulator] "pieces") a local cleanup completion may generate,
 * as a function of the text it was given (#325).
 *
 * Cleanup returns about as much text as it receives, and [LocalCleanupOutputValidator] rejects
 * anything over [LocalCleanupOutputValidator.LENGTH_EXPANSION_MAX_RATIO] x the input anyway. A
 * flat 512-token ceiling therefore let a runaway generation (a model that never emits its
 * end-of-turn token and is not yet repeating verbatim enough for the loop detector) run until the
 * chunk's wall-clock deadline: a 124-character chunk ran the full 60 s on a Pixel 10a, to produce
 * output that was going to be thrown away. Bounding the response by the input stops that in
 * seconds and the chunk falls back to its raw text like any other failed chunk.
 *
 * The bound is `chars / CHARS_PER_TOKEN_FLOOR + HEADROOM_TOKENS`, never below [MIN_TOKENS] and
 * never above [LlamaCppInference.MAX_RESPONSE_TOKENS]. 1.5 characters per token is deliberately
 * pessimistic: English prose is ~4 for the Qwen/LFM tokenizers, so a healthy 300-character chunk
 * needs ~75 tokens against a cap of 224 (about 3x), while text full of digits or rare names
 * (1-3 characters per token) still fits.
 */
object LocalCleanupResponseCap {
    const val CHARS_PER_TOKEN_FLOOR = 1.5
    const val HEADROOM_TOKENS = 24
    const val MIN_TOKENS = 64

    fun maxTokensFor(inputChars: Int): Int =
        (inputChars / CHARS_PER_TOKEN_FLOOR).toInt()
            .plus(HEADROOM_TOKENS)
            .coerceIn(MIN_TOKENS, LlamaCppInference.MAX_RESPONSE_TOKENS)
}
