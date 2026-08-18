package com.trevornk.ramblr

/**
 * Outcome of validating one local-cleanup completion (#155). [Valid] means the text may be
 * accepted as cleaned output; [Rejected] means it must be treated as a step failure so the
 * waterfall falls through to the next step (or to raw-text injection) instead of injecting it.
 */
sealed class LocalCleanupValidation {
    object Valid : LocalCleanupValidation()

    /** [detail] is a short, non-sensitive diagnostic for logcat -- it deliberately never
     *  contains the model output or the user's transcript, both of which can hold private
     *  content (the prompt-echo failure this guards against leaks exactly that). */
    data class Rejected(val reason: Reason, val detail: String) : LocalCleanupValidation()

    enum class Reason {
        /** The model regurgitated part of its own system prompt as "cleaned" text. */
        PROMPT_ECHO,

        /** A number the speaker said is gone from, or has a different value in, the output. */
        NUMERIC_DIVERGENCE,

        /** The output is a fraction of the input -- content was dropped, not cleaned. */
        LENGTH_COLLAPSE,
    }
}

/**
 * Sanity-checks what a small on-device model returned before [RealLocalInferenceEngine] reports
 * it as a success (#155).
 *
 * Exists because the entire prior contract for local-cleanup output was `text.isNotBlank()`.
 * With a non-trivial personal vocabulary list interpolated into the system prompt (364 -> 702
 * chars for 22 terms), LFM2.5-350M-Q4_0 was measured producing, deterministically at temp 0:
 *
 *  - "Send four hundred and fifty dollars to the account ending in nine three seven two"
 *    -> "Send $150 to the account ending in ninethreeseventwo"   (the amount silently changed)
 *  - "My address is four one two Selby Road" -> "Selby Road"     (the house number deleted)
 *  - "Call me at five five five one two three four"
 *    -> the system prompt echoed back verbatim, which injects the user's own private
 *       vocabulary terms (emails, family names) into whatever app they dictated into
 *
 * All three are worse than no cleanup at all, and all three are detectable without the model:
 * they are properties of (input, systemPrompt, output). Kept deliberately free of Android and
 * llama.cpp dependencies so every branch is a plain JVM unit test.
 *
 * Thresholds are biased toward ACCEPTING. A false rejection costs one fallback step; an
 * over-eager validator makes local cleanup useless. Every threshold below is set with real
 * headroom against the legitimate-cleanup cases in `LocalCleanupOutputValidatorTest`, not
 * tightened to the smallest value that still passes them.
 */
object LocalCleanupOutputValidator {

    /**
     * Length in normalized characters of the longest shared span between the system prompt and
     * the output that counts as an echo rather than coincidence.
     *
     * 40 chars is roughly six or seven English words. Legitimate cleaned speech does share short
     * spans with an instruction prompt ("return only the cleaned text" is 28 normalized chars,
     * "clean up this speech-to-text transcript" is 38), so anything at or below ~38 must survive;
     * the observed echo failures reproduce hundreds of contiguous characters, not forty. Spans
     * the speaker actually dictated are excluded separately (see [longestSharedSpanLength]'s
     * caller), so this only has to separate "coincidence" from "regurgitation".
     */
    const val PROMPT_ECHO_MIN_SPAN = 40

    /**
     * Minimum surviving fraction of the input's normalized length.
     *
     * Measured against the real cases: "My address is four one two Selby Road" -> "Selby Road"
     * retains 0.36 of the normalized input and must be rejected, while the legitimate
     * "Transfer twelve thousand five hundred dollars tomorrow" -> "Transfer $12,500" retains
     * 0.61 and must pass. 0.5 sits between them with headroom on both sides.
     *
     * The comparison is deliberately unfair in the output's favour: [normalizeForLength] strips
     * filler words, currency words and spoken-numeral verbosity from the INPUT baseline only, so
     * the two transformations a correct cleanup is *supposed* to perform (dropping disfluencies,
     * and "four hundred and fifty dollars" -> "$450") shrink the baseline too and cannot by
     * themselves trip this check.
     */
    const val LENGTH_COLLAPSE_MIN_RATIO = 0.5

    /**
     * Inputs shorter than this (normalized) skip the length check entirely. A handful of words
     * carries too little signal -- "yeah um okay" losing one token is a 33% drop that means
     * nothing -- and short utterances are exactly where legitimate cleanup ratios are noisiest.
     */
    const val LENGTH_COLLAPSE_MIN_INPUT_CHARS = 24

    /**
     * Runs the three checks in severity order and returns the first rejection, or [Valid].
     *
     * A blank [modelOutput] is NOT this function's business: the caller already treats empty
     * output as its own failure, and reporting it here as a length collapse would be a
     * less accurate diagnostic.
     */
    fun validate(
        rawInput: String,
        systemPrompt: String,
        modelOutput: String,
    ): LocalCleanupValidation {
        if (modelOutput.isBlank()) return LocalCleanupValidation.Valid

        checkPromptEcho(rawInput, systemPrompt, modelOutput)?.let { return it }
        checkNumericDivergence(rawInput, modelOutput)?.let { return it }
        checkLengthCollapse(rawInput, modelOutput)?.let { return it }
        return LocalCleanupValidation.Valid
    }

    // --- (a) prompt echo -------------------------------------------------------------------

    /**
     * Rejects output sharing a long contiguous span with the system prompt.
     *
     * Derived from the [systemPrompt] argument rather than matching known prompt wording, so it
     * keeps working when a prompt constant is reworded, when a personal-vocabulary clause is
     * interpolated into it, and for a fine-tuned model that ships its own
     * [Model.localSystemPrompt]. Hardcoding "Watch for these project names" would have covered
     * exactly one prompt of the several this path can send.
     *
     * A span the speaker actually said is not an echo -- if someone dictates a sentence that
     * happens to appear in the prompt, cleaning it up should reproduce it -- so any span also
     * present in [rawInput] is discounted by comparing against the input as well.
     */
    private fun checkPromptEcho(
        rawInput: String,
        systemPrompt: String,
        modelOutput: String,
    ): LocalCleanupValidation.Rejected? {
        val prompt = normalizeForEcho(systemPrompt)
        val output = normalizeForEcho(modelOutput)
        if (prompt.isEmpty() || output.isEmpty()) return null

        val sharedWithPrompt = longestSharedSpanLength(prompt, output)
        if (sharedWithPrompt < PROMPT_ECHO_MIN_SPAN) return null

        // The speaker's own words are not an echo. Only the amount of overlap the prompt
        // contributes *beyond* what the input already explains is evidence of regurgitation.
        val sharedWithInput = longestSharedSpanLength(normalizeForEcho(rawInput), output)
        if (sharedWithPrompt <= sharedWithInput) return null

        return LocalCleanupValidation.Rejected(
            LocalCleanupValidation.Reason.PROMPT_ECHO,
            "output shares a $sharedWithPrompt-char span with the system prompt " +
                "(threshold $PROMPT_ECHO_MIN_SPAN, input explains only $sharedWithInput)",
        )
    }

    /**
     * Longest common substring length, computed with the usual two-row dynamic program so memory
     * stays O(min(n, m)) instead of O(n*m).
     *
     * Both sides are capped at [ECHO_SCAN_CAP] characters purely to bound worst-case cost on a
     * phone: prompts are hundreds of characters and dictations are rarely longer, and an echo
     * long enough to matter always begins well inside that window.
     */
    private fun longestSharedSpanLength(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val left = a.take(ECHO_SCAN_CAP)
        val right = b.take(ECHO_SCAN_CAP)
        var previous = IntArray(right.length + 1)
        var current = IntArray(right.length + 1)
        var best = 0
        for (i in 1..left.length) {
            for (j in 1..right.length) {
                current[j] = if (left[i - 1] == right[j - 1]) previous[j - 1] + 1 else 0
                if (current[j] > best) best = current[j]
            }
            val swap = previous
            previous = current
            current = swap
            java.util.Arrays.fill(current, 0)
        }
        return best
    }

    private const val ECHO_SCAN_CAP = 4_000

    /** Lowercased, with every run of non-alphanumeric characters collapsed to one space, so
     *  punctuation or spacing drift between prompt and echo can't hide the overlap. */
    private fun normalizeForEcho(text: String): String =
        text.lowercase().replace(NON_ALPHANUMERIC, " ").trim().replace(WHITESPACE_RUN, " ")

    // --- (b) numeric divergence ------------------------------------------------------------

    /**
     * Rejects output where a number the speaker said has vanished or changed value.
     *
     * The hard part is that spoken-to-digit conversion is *correct* cleanup: "four hundred and
     * fifty" -> "$450" and "twelve thousand five hundred" -> "$12,500" must both pass, while
     * 450 -> 150 must not. So both sides are reduced to numeric VALUES rather than compared as
     * text: each run of number words in the input yields the set of values it could legitimately
     * be written as, and the output yields every value it contains (digits and words alike). A
     * run is satisfied if the output still contains any one of its candidate values.
     *
     * "Any one of" is the bias-toward-accepting choice: a run like "nine three seven two" is
     * legitimately renderable as 9372 or as the digits 9, 3, 7, 2, and guessing wrong in the
     * strict direction would reject good output.
     */
    private fun checkNumericDivergence(
        rawInput: String,
        modelOutput: String,
    ): LocalCleanupValidation.Rejected? {
        val inputRuns = numericRuns(rawInput)
        if (inputRuns.isEmpty()) return null

        val outputValues = numericRuns(modelOutput).flatten().toSet()
        val missing = inputRuns.firstOrNull { candidates -> candidates.none { it in outputValues } }
            ?: return null

        return LocalCleanupValidation.Rejected(
            LocalCleanupValidation.Reason.NUMERIC_DIVERGENCE,
            "a spoken number is missing or altered in the output " +
                "(${missing.size} accepted rendering(s), none present)",
        )
    }

    /**
     * Every numeric run in [text], each as the set of canonical values it may legitimately be
     * written as. Canonical values are digit strings (leading zeros stripped) rather than Longs,
     * so a 20-digit account number can't overflow and a decimal keeps its exact form.
     */
    private fun numericRuns(text: String): List<Set<String>> {
        val tokens = text.lowercase().split(WHITESPACE_RUN).filter { it.isNotEmpty() }
        val runs = mutableListOf<Set<String>>()
        var index = 0
        while (index < tokens.size) {
            val literal = literalNumberValue(tokens[index])
            if (literal != null) {
                runs += setOf(literal)
                index++
                continue
            }
            val words = mutableListOf<String>()
            var cursor = index
            while (cursor < tokens.size) {
                val word = stripPunctuation(tokens[cursor])
                val isNumberWord = word in NUMBER_WORDS
                // "and" only continues a run ("four hundred and fifty"); it never starts or
                // ends one, so a trailing "and" isn't swallowed into the numeric span.
                val isConnector = word == "and" && words.isNotEmpty() &&
                    cursor + 1 < tokens.size && stripPunctuation(tokens[cursor + 1]) in NUMBER_WORDS
                if (!isNumberWord && !isConnector) break
                if (isNumberWord) words += word
                cursor++
            }
            if (words.isEmpty()) {
                index++
                continue
            }
            wordRunCandidates(words)?.let { runs += it }
            index = cursor
        }
        return runs
    }

    /**
     * Canonical value of a token that is already written numerically -- plain digits, comma
     * grouping, a currency symbol, or trailing punctuation ("$12,500." -> "12500").
     */
    private fun literalNumberValue(token: String): String? {
        val stripped = token.trim { it in CURRENCY_AND_PUNCTUATION }.replace(",", "")
        if (stripped.isEmpty() || stripped.none { it.isDigit() }) return null
        if (!stripped.all { it.isDigit() || it == '.' }) return null
        return canonicalize(stripped)
    }

    /**
     * The values a run of number WORDS may legitimately appear as.
     *
     * Three shapes, because they behave differently:
     *  - contains a scale word ("hundred"/"thousand"/...): a cardinal, and only the cardinal --
     *    "four hundred and fifty" means 450, and 4, 100 or 50 appearing alone is not that number
     *  - all single-digit words: read out digit by digit, so both the concatenation ("nine three
     *    seven two" -> 9372) and the individual digits are legitimate renderings
     *  - otherwise ("twenty five"): the cardinal plus each word's own value
     *
     * Returns null for runs with no defensible reading, and for a bare "one", which is far more
     * often an article or pronoun ("one of the things") than a quantity worth guarding.
     */
    private fun wordRunCandidates(words: List<String>): Set<String>? {
        if (words.size == 1 && words[0] == "one") return null
        val hasScale = words.any { it in SCALE_WORDS }
        if (hasScale) {
            return cardinalValue(words)?.let { setOf(canonicalize(it.toString())) }
        }
        val digitsOnly = words.all { it in SINGLE_DIGIT_WORDS }
        if (digitsOnly) {
            val concatenated = words.joinToString("") { SINGLE_DIGIT_WORDS.getValue(it).toString() }
            return buildSet {
                add(canonicalize(concatenated))
                words.forEach { add(canonicalize(SINGLE_DIGIT_WORDS.getValue(it).toString())) }
            }
        }
        return buildSet {
            cardinalValue(words)?.let { add(canonicalize(it.toString())) }
            words.forEach { word -> NUMBER_WORDS[word]?.let { add(canonicalize(it.toString())) } }
        }.ifEmpty { null }
    }

    /** Standard cardinal accumulation ("twelve thousand five hundred" -> 12500). Null when the
     *  run has no scale/value words at all. */
    private fun cardinalValue(words: List<String>): Long? {
        var total = 0L
        var chunk = 0L
        var sawValue = false
        for (word in words) {
            val value = NUMBER_WORDS[word] ?: continue
            sawValue = true
            when {
                word == "hundred" -> chunk = (if (chunk == 0L) 1L else chunk) * 100L
                word in BIG_SCALE_WORDS -> {
                    total += (if (chunk == 0L) 1L else chunk) * value
                    chunk = 0L
                }
                else -> chunk += value
            }
        }
        return if (sawValue) total + chunk else null
    }

    /** Digit string without leading zeros or a trailing decimal point ("04" -> "4"). */
    private fun canonicalize(digits: String): String {
        val trimmed = digits.trimEnd('.')
        val withoutLeadingZeros = trimmed.trimStart('0')
        return when {
            withoutLeadingZeros.isEmpty() -> "0"
            withoutLeadingZeros.startsWith(".") -> "0$withoutLeadingZeros"
            else -> withoutLeadingZeros
        }
    }

    private fun stripPunctuation(token: String): String = token.trim { !it.isLetterOrDigit() }

    // --- (c) length collapse ---------------------------------------------------------------

    /** Rejects output that kept less than [LENGTH_COLLAPSE_MIN_RATIO] of the input's normalized
     *  length -- the "My address is four one two Selby Road" -> "Selby Road" failure, where the
     *  model dropped content instead of cleaning it. */
    private fun checkLengthCollapse(
        rawInput: String,
        modelOutput: String,
    ): LocalCleanupValidation.Rejected? {
        val baseline = normalizeForLength(rawInput)
        if (baseline.length < LENGTH_COLLAPSE_MIN_INPUT_CHARS) return null
        val output = normalizeForLength(modelOutput)
        val ratio = output.length.toDouble() / baseline.length
        if (ratio >= LENGTH_COLLAPSE_MIN_RATIO) return null
        return LocalCleanupValidation.Rejected(
            LocalCleanupValidation.Reason.LENGTH_COLLAPSE,
            "output kept only ${(ratio * 100).toInt()}% of the input's content length " +
                "(minimum ${(LENGTH_COLLAPSE_MIN_RATIO * 100).toInt()}%)",
        )
    }

    /**
     * Reduces text to comparable "content length": lowercased, punctuation and currency symbols
     * dropped, spoken numerals collapsed to their digit form, and filler words removed.
     *
     * Applied to both sides, but it only ever *shrinks the input baseline* in practice -- a
     * cleaned output has no filler and already uses digits. That asymmetry is deliberate: the
     * two things a correct cleanup does (delete disfluencies, write numbers as digits) are
     * removed from the comparison so they can never look like a collapse.
     */
    internal fun normalizeForLength(text: String): String {
        val tokens = text.lowercase().split(WHITESPACE_RUN).filter { it.isNotEmpty() }
        val kept = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val word = stripPunctuation(tokens[index])
            if (word.isEmpty()) {
                index++
                continue
            }
            if (word in FILLER_WORDS || word in CURRENCY_WORDS) {
                index++
                continue
            }
            if (word in NUMBER_WORDS) {
                var cursor = index
                val words = mutableListOf<String>()
                while (cursor < tokens.size) {
                    val next = stripPunctuation(tokens[cursor])
                    if (next !in NUMBER_WORDS && !(next == "and" && words.isNotEmpty())) break
                    if (next in NUMBER_WORDS) words += next
                    cursor++
                }
                if (words.isNotEmpty()) {
                    kept += wordRunCandidates(words)?.minByOrNull { it.length }
                        ?: words.joinToString("")
                    index = cursor
                    continue
                }
            }
            kept += word.filter { it.isLetterOrDigit() || it == '.' }
            index++
        }
        return kept.joinToString(" ")
    }

    // --- shared vocabulary -----------------------------------------------------------------

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val WHITESPACE_RUN = Regex("\\s+")
    private val CURRENCY_AND_PUNCTUATION = charArrayOf(
        '$', '€', '£', '¥', '.', ',', '!', '?', ':', ';', '"', '\'', '(', ')', '-',
    ).toSet()

    /** Disfluencies a correct cleanup is expected to delete, so they are removed from the length
     *  baseline rather than counted as content the model lost. Generous on purpose: every entry
     *  here only makes the check more permissive. */
    private val FILLER_WORDS = setOf(
        "um", "uh", "erm", "er", "ah", "hmm", "mhm", "eh", "like", "basically", "actually",
        "literally", "sorta", "kinda", "yknow",
    )

    /** Dropped from the length baseline because "four hundred and fifty dollars" -> "$450"
     *  legitimately deletes the currency word along with the numeral spelling. */
    private val CURRENCY_WORDS = setOf(
        "dollar", "dollars", "cent", "cents", "euro", "euros", "pound", "pounds", "buck", "bucks",
    )

    private val SINGLE_DIGIT_WORDS: Map<String, Int> = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )

    private val SCALE_WORDS = setOf("hundred", "thousand", "million", "billion")
    private val BIG_SCALE_WORDS = setOf("thousand", "million", "billion")

    private val NUMBER_WORDS: Map<String, Long> = buildMap {
        SINGLE_DIGIT_WORDS.forEach { (word, value) -> put(word, value.toLong()) }
        put("ten", 10L); put("eleven", 11L); put("twelve", 12L); put("thirteen", 13L)
        put("fourteen", 14L); put("fifteen", 15L); put("sixteen", 16L); put("seventeen", 17L)
        put("eighteen", 18L); put("nineteen", 19L); put("twenty", 20L); put("thirty", 30L)
        put("forty", 40L); put("fifty", 50L); put("sixty", 60L); put("seventy", 70L)
        put("eighty", 80L); put("ninety", 90L)
        put("hundred", 100L); put("thousand", 1_000L); put("million", 1_000_000L)
        put("billion", 1_000_000_000L)
    }
}
