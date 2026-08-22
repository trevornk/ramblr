package com.trevornk.ramblr

/**
 * Builds the floating-bubble notice shown when cleanup fails and Ramblr injects the raw
 * transcript instead (#175).
 *
 * The bubble itself already existed -- `injectText(feedback = "Cleanup failed (...)")` has been
 * wired since #98 -- so #175's premise that the fall-through is *silent* is wrong. What was
 * actually broken is what the bubble said:
 *
 *  1. **It claimed the clipboard held the raw text when usually it didn't.** The message was
 *     hardcoded as "raw copied to clipboard" at the call site, which runs *before* the injection
 *     method is known. For a DIRECT (ACTION_SET_TEXT) injection -- the common case --
 *     [clipboardClearActionFor] wipes the clipboard immediately, so the user was told to paste
 *     from a clipboard that no longer had it. This is exactly the #118 bug class, which fixed the
 *     same false clipboard claim for the *success* message and left the failure message behind.
 *
 *  2. **It leaked raw executor diagnostics into a floating overlay.** The reason string was
 *     `result.error` verbatim, which is built for logcat: nested prefixes like
 *     `"All cleanup steps failed: Local cleanup output rejected (PROMPT_ECHO)"`, or -- for a cloud
 *     step -- up to 200 characters of the provider's own error body via
 *     `CleanupWaterfallExecutor.errorDetail`. Measured, a local PROMPT_ECHO produced a 112-char
 *     bubble; a verbose provider envelope would be far longer, in a WRAP_CONTENT pill with no
 *     `maxLines`, anchored beside the icon.
 *
 * So this resolves the message at the injection seam, where `method` is known, and maps the
 * internal reason onto a short plain-language phrase.
 *
 * The phrasing deliberately describes *what the model did* ("echoed its instructions") rather than
 * naming the validator constant (`PROMPT_ECHO`). #175 asks whether the reason should be visible at
 * all; it should, because these failures are currently indistinguishable from cleanup that ran and
 * changed nothing -- but the internal enum name is for the issue tracker, not the user.
 *
 * Content safety: every branch returns a fixed literal. No transcript, prompt, model output, or
 * provider body ever reaches the bubble -- the same boundary [LocalCleanupOutputValidator] holds
 * when it logs reason/detail but never the text.
 */
object CleanupFailureNotice {

    /** Fallback when the executor gave no usable error at all. */
    const val UNKNOWN_REASON = "unknown error"

    /**
     * Maps a raw [CleanupWaterfallExecutor] error string onto a short user-facing phrase.
     *
     * Matched against the *whole* string rather than an exact equality, because the executor
     * wraps the terminal step's message in an `"All cleanup steps failed: "` prefix (and cloud
     * steps prepend `"HTTP <code>: "`), so the distinguishing token appears mid-string.
     */
    fun summarize(error: String?): String {
        val raw = error?.trim().orEmpty()
        if (raw.isEmpty()) return UNKNOWN_REASON
        return when {
            // Validator rejections (#155/#181). Described by effect, not by enum name.
            raw.contains("PROMPT_ECHO") -> "model repeated its instructions"
            raw.contains("LENGTH_COLLAPSE") -> "model dropped most of the text"
            raw.contains("LENGTH_EXPANSION") -> "model added text you didn't say"
            raw.contains("NUMERIC_DIVERGENCE") -> "model changed a number"
            // Local engine outcomes.
            // Model-missing is the one failure the user can actually act on (re-download in
            // Settings), so it must not fall through to UNKNOWN_REASON. Two distinct strings
            // reach here: the executor's own pre-flight ("not downloaded", when the pref names a
            // model with no resolved path) and LlamaCppInference's FileNotFoundException ("not
            // found at $modelPath", when the pref resolves but the file is gone underneath it).
            // Both collapse to one phrase -- and critically, to a *fixed literal*: the second
            // string carries the full `/data/user/0/...` path, which must never reach a floating
            // overlay pill.
            raw.contains("model not found") || raw.contains("model not downloaded") ->
                "cleanup model isn't installed"
            raw.contains("timed out") -> "model timed out"
            raw.contains("empty response") -> "model returned nothing"
            raw.contains("exceeded time budget") -> "cleanup took too long"
            // Cloud step. Deliberately keeps the status code (actionable: 401 means a bad key)
            // and discards the provider's error body, which is unbounded prose.
            else -> httpStatusIn(raw)?.let { "server error $it" } ?: UNKNOWN_REASON
        }
    }

    /** Extracts the `NNN` from an `"HTTP NNN: ..."` step failure, if this was one. */
    private fun httpStatusIn(raw: String): String? =
        Regex("HTTP (\\d{3})").find(raw)?.groupValues?.get(1)

    /**
     * The full bubble text for a failed cleanup that fell through to raw text.
     *
     * [method] decides the clipboard clause, and it must, because it decides the clipboard's
     * actual contents:
     *  - [InjectMethod.DIRECT] -- clipboard wiped immediately after injection, so promising a
     *    copy would be a lie.
     *  - [InjectMethod.FROM_CLIPBOARD] -- the copy is cleared after a short grace delay, so it is
     *    not something to send the user to either.
     *  - [InjectMethod.NONE] -- nothing was injected and the clipboard *is* the delivery path, so
     *    it is the one case worth mentioning. The injection seam already appends its own
     *    "· tap to copy again" affordance for this case, so this stays terse to avoid saying it
     *    twice.
     */
    fun messageFor(method: InjectMethod, error: String?): String {
        val reason = summarize(error)
        return when (method) {
            InjectMethod.NONE -> "Cleanup failed ($reason)"
            else -> "Cleanup failed ($reason) — inserted raw text"
        }
    }
}
