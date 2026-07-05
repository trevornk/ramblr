package com.kafkasl.phonewhisper

/**
 * Strips chat-control special-token markers from message content before it reaches the local
 * cleanup model (#78).
 *
 * The native side tokenizes the fully rendered chat template with `parse_special=true`
 * (LLMInference.cpp, vendored verbatim) -- required for the template's own scaffolding markers,
 * but it applies equally to the message content embedded in the same string. The curated local
 * cleanup model (LFM2.5-350M -- see [LOCAL_CLEANUP_MODEL_CATALOG]) uses ChatML-style
 * `<|marker|>` special tokens, so a transcript containing literal `<|im_end|>` /
 * `<|im_start|>system` / `<|endoftext|>` text would become real control tokens: the user message
 * could close itself and forge a fake system/assistant turn.
 *
 * Removing anything shaped like a `<|...|>` marker from content neutralizes that without touching
 * the vendored native code. Dictated speech never legitimately produces such sequences, so
 * dropping them loses nothing real.
 */
object SpecialTokenSanitizer {

    /** `<|` + a marker body (no nested angle brackets or pipes, bounded length) + `|>`. The bound
     *  keeps a pathological "<|" + megabytes + "|>" input from turning removal quadratic. */
    private val SPECIAL_TOKEN_SHAPE = Regex("""<\|[^<>|]{0,64}\|>""")

    /**
     * Returns [text] with every `<|...|>`-shaped sequence removed. Runs to a fixpoint so removals
     * can't splice surrounding characters into a fresh marker (e.g. `<<|x|>|im_end<|y|>|>` must
     * not collapse into a live `<|im_end|>`).
     */
    fun sanitize(text: String): String {
        var current = text
        while (true) {
            val next = SPECIAL_TOKEN_SHAPE.replace(current, "")
            if (next == current) return current
            current = next
        }
    }
}
