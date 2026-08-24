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

        /** The output is a multiple of the input -- the model added text instead of cleaning. */
        LENGTH_EXPANSION,

        /** The speaker dictated a question and the model replied to it instead of cleaning it. */
        QUESTION_ANSWERED,
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
 * they are properties of (input, systemPrompt, output). A fourth check (#181) rejects output that
 * is a multiple of its input, the general form of #179's degenerate repetition loop. Kept
 * deliberately free of Android and llama.cpp dependencies so every branch is a plain JVM unit
 * test.
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
     * Fraction of an answer's content words that must be absent from the transcript before output
     * to a dictated question is treated as an answer rather than a cleanup (#189).
     *
     * Measured against the real cases, with function words excluded from both sides (see
     * [STRUCTURAL_WORDS] -- counting them dilutes the signal badly enough to hide a real answer).
     * Rejected: the capital-of-france answer scores 0.50 novel (sure/paris), the invented
     * store-hours answer 0.67 (closes/5/00/pm), the mutex explanation 0.73. Accepted: a question
     * rewritten as a statement using only the speaker's own words scores 0.00.
     *
     * 0.3 sits in the gap with headroom on both sides. It is the loosest of the three conditions
     * on purpose: conditions 1 and 2 do the real discriminating, and this one only exists to
     * spare a declarative paraphrase that reuses the speaker's vocabulary.
     */
    const val QUESTION_ANSWER_MIN_NOVEL_FRACTION = 0.3

    /**
     * Function words dropped from both sides of the novel-content comparison.
     *
     * Not a general stopword list -- it exists because articles, prepositions, and copulas are
     * shared by literally every English sentence, so leaving them in the denominator makes a
     * short answer look mostly-familiar. "Sure! The capital of France is Paris." against "can you
     * tell me what the capital of france is" scores 0.29 novel with them (below threshold, missed)
     * and 0.50 without them (caught). Only the answer's actual content should count.
     */
    private val STRUCTURAL_WORDS = setOf(
        "the", "a", "an", "of", "is", "are", "was", "were", "be", "been", "to", "in", "on", "at",
        "for", "and", "or", "that", "this", "it", "its", "as", "by", "with", "from", "i", "you",
        "he", "she", "we", "they", "me", "my", "your", "do", "does", "did", "can", "could",
        "would", "will", "should", "what", "who", "when", "where", "why", "how", "which", "not",
        "if", "then", "than", "there", "have", "has", "had", "am",
    )

    /**
     * Words that may precede the actual interrogative in dictated speech, skipped when testing
     * whether a transcript opens as a question. "hey what time does the store close" and "so what
     * do you think" are both questions; without this they would read as declaratives.
     */
    private val LEADING_DISCOURSE_WORDS = setOf(
        "so", "hey", "ok", "okay", "yeah", "yes", "no", "well", "and", "but", "oh", "hi", "hello",
        "alright", "right", "now", "just", "anyway",
    )

    /**
     * Openers that mark a transcript as a question. Wh-words plus the auxiliaries and modals that
     * front a yes/no question ("does the store close...", "can you tell me...").
     *
     * Kept to sentence-initial position only: "the thing is what he said" contains "what" but is
     * not a question, and matching anywhere in the text would reject it.
     */
    private val INTERROGATIVE_OPENERS = setOf(
        "who", "whose", "whom", "what", "whats", "when", "where", "why", "how", "hows", "which",
        "can", "could", "would", "will", "should", "shall", "do", "does", "did", "is", "are",
        "was", "were", "am", "may", "might", "must", "have", "has", "had",
    )

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
     * Maximum multiple of the input's normalized length the output may reach (#181).
     *
     * Cleanup removes filler and fixes punctuation; it never meaningfully lengthens a transcript.
     * Measured over the corpus in `/tmp/qa` using this file's own [normalizeForLength], counting
     * only output the earlier checks ACCEPT (output already rejected for echoing the prompt says
     * nothing about where this threshold belongs):
     *
     *   healthy accepted output, normalized input >= 24 chars, n=8:  0.57x - 1.33x
     *   the #179 degenerate repetition loop (580 chars -> 2197):     3.71x
     *
     * 2.0x sits between them with headroom on both sides: no false positive on any measured
     * healthy output at 1.5x or above, and the loop is caught with margin. Consistent with the
     * rest of this object, the threshold is deliberately loose rather than the tightest value
     * that still passes -- a false rejection costs one fallback step, but an over-eager check
     * makes local cleanup useless.
     *
     * This is the general guard; #179's detector handles only the verbatim-repetition shape of
     * the same problem, and would miss a model that expanded by confabulating novel text.
     */
    const val LENGTH_EXPANSION_MAX_RATIO = 2.0

    /**
     * Inputs shorter than this (normalized) skip the expansion check.
     *
     * Legitimate expansion ratios are naturally high on very short input: "BETA" -> "Beta." and
     * spelling out one mumbled word can double a four-word transcript without anything being
     * wrong. Measured on the same corpus, "How old is Tom?" (14 normalized chars) legitimately
     * cleaned to 29 chars -- a 2.07x ratio that would trip this check without a floor, while
     * every sample at or above 24 chars stayed at or below 1.33x.
     *
     * Deliberately the same value as [LENGTH_COLLAPSE_MIN_INPUT_CHARS]: both checks are unreliable
     * on the same short inputs for the same reason, and one floor is easier to reason about than
     * two. Kept as a separate constant so the two can be tuned independently if the data ever
     * says they should be.
     */
    const val LENGTH_EXPANSION_MIN_INPUT_CHARS = 24

    /**
     * Runs the four checks in severity order and returns the first rejection, or [Valid].
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
        checkQuestionAnswered(rawInput, modelOutput)?.let { return it }
        checkNumericDivergence(rawInput, modelOutput)?.let { return it }
        checkLengthCollapse(rawInput, modelOutput)?.let { return it }
        checkLengthExpansion(rawInput, modelOutput)?.let { return it }
        return LocalCleanupValidation.Valid
    }

    // --- (e) question answered instead of cleaned ------------------------------------------

    /**
     * Rejects output that answers a dictated question rather than cleaning it up (#189).
     *
     * The failure this exists for, measured on-host at temp 0 against the shipped
     * LFM2.5-350M-Q4_0 with the production system prompt:
     *
     *  - "can you tell me what the capital of france is" -> "Sure! The capital of France is Paris."
     *  - "hey what time does the store close on sunday"  -> "The store closes at 5:00 PM on Sunday."
     *
     * The second is the dangerous one: there is no store and no 5 PM. The model invents a fact and
     * the app types it into whatever field the user was dictating into. Note this is NOT specific
     * to the shipped model -- Gemma-3-270M-IT-QAT-Q4_0 does the same thing on the same inputs.
     *
     * Adding "do not answer questions" to the system prompt was tried first and REJECTED as the
     * fix: it left the France case unchanged and, on the store case, merely reworded the invented
     * answer ("...at 5:00 PM on Sundays"). A 350M model cannot be relied on to obey a negative
     * instruction, so the guarantee has to be deterministic and outside the model. Every other
     * prompt constant already carries a no-answer clause and they are kept for the models that do
     * honour it; this check is what makes the behaviour actually guaranteed.
     *
     * Detection is deliberately narrow, because the bias is toward accepting (a false rejection
     * costs one fallback step, but a false acceptance silently corrupts the user's text). All
     * three conditions must hold:
     *
     *  1. The input is interrogative -- it opens with a question word/auxiliary, or ends in "?".
     *  2. The output is NOT interrogative -- a correctly cleaned question stays a question, so
     *     "um so what do you think we should do about the deploy" -> "...about the deploy?" is
     *     untouched by this check, as are rhetorical questions and dictated interview prompts.
     *  3. The output does not simply restate the input -- if the model rewrote the question as a
     *     statement while keeping the speaker's words (a declarative paraphrase), the other
     *     checks own that case; only genuinely novel content counts as an answer.
     */
    private fun checkQuestionAnswered(
        rawInput: String,
        modelOutput: String,
    ): LocalCleanupValidation.Rejected? {
        if (!looksInterrogative(rawInput)) return null
        if (looksInterrogative(modelOutput)) return null

        // Condition 3: an answer introduces words the speaker never said. A pure re-punctuation or
        // reordering of the speaker's own words shares nearly all of its content with the input.
        val inputWords = contentWords(rawInput)
        val outputWords = contentWords(modelOutput)
        if (outputWords.isEmpty()) return null

        val novel = outputWords.filterNot { it in inputWords }
        val novelFraction = novel.size.toDouble() / outputWords.size
        if (novelFraction < QUESTION_ANSWER_MIN_NOVEL_FRACTION) return null

        return LocalCleanupValidation.Rejected(
            LocalCleanupValidation.Reason.QUESTION_ANSWERED,
            "input was a question and the output answers it rather than cleaning it " +
                "(${novel.size}/${outputWords.size} output words absent from the transcript)",
        )
    }

    /**
     * Whether [text] reads as a question: an explicit "?" anywhere, or an opening interrogative.
     *
     * Speech-to-text rarely emits "?" on its own, which is exactly why the leading-word test
     * exists -- the raw transcript of a dictated question usually arrives unpunctuated. Leading
     * fillers and discourse markers are skipped so "um so what do you think" is still recognised.
     *
     * Uses its own plain word split rather than [normalizeForLength], which collapses spoken
     * number runs into digit strings -- correct for length ratios, but it would mangle the
     * invented "5:00 PM" that this check specifically needs to see as novel content.
     */
    private fun looksInterrogative(text: String): Boolean {
        if (text.contains('?')) return true
        val firstMeaningful = plainWords(text).firstOrNull {
            it !in FILLER_WORDS && it !in LEADING_DISCOURSE_WORDS
        } ?: return false
        return firstMeaningful in INTERROGATIVE_OPENERS
    }

    /** Content words only: punctuation stripped, fillers and function words dropped. */
    private fun contentWords(text: String): Set<String> =
        plainWords(text)
            .filterNot {
                it in FILLER_WORDS || it in LEADING_DISCOURSE_WORDS || it in STRUCTURAL_WORDS
            }
            .toSet()

    /** Lowercased alphanumeric tokens, punctuation removed, order preserved. */
    private fun plainWords(text: String): List<String> =
        text.lowercase().split(NON_ALPHANUMERIC).filter { it.isNotBlank() }

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

        val outputValues = (
            numericRuns(modelOutput).flatten() + NumericValueExtractor.extract(modelOutput)
        ).toSet()
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

    // --- (d) length expansion --------------------------------------------------------------

    /**
     * Rejects output that is a multiple of its input (#181) -- the model added text rather than
     * cleaning what was said.
     *
     * The failure this catches is the #179 degenerate loop's general form: 580 characters of
     * input producing 2197 characters of the same clause eleven times passed every other check
     * cleanly. #179's detector stops that specific generation, but it keys on verbatim
     * repetition, so a model that expanded by confabulating -- answering the transcript instead
     * of cleaning it, continuing the speaker's story, appending commentary -- would produce no
     * repeated window and reach the user's text field unchallenged.
     *
     * Deliberately runs LAST. Every other check names a more specific cause, and an over-long
     * echo should be reported as [Reason.PROMPT_ECHO] rather than as an expansion.
     *
     * The normalization asymmetry that protects [checkLengthCollapse] works in this check's
     * favour too but in the opposite direction, and is worth stating: [normalizeForLength] shrinks
     * the input baseline by stripping filler and collapsing spoken numerals, which raises the
     * measured ratio slightly. The threshold is set from ratios measured the same way, so this is
     * already priced in.
     */
    private fun checkLengthExpansion(
        rawInput: String,
        modelOutput: String,
    ): LocalCleanupValidation.Rejected? {
        val baseline = normalizeForLength(rawInput)
        if (baseline.length < LENGTH_EXPANSION_MIN_INPUT_CHARS) return null
        val output = normalizeForLength(modelOutput)
        val ratio = output.length.toDouble() / baseline.length
        if (ratio <= LENGTH_EXPANSION_MAX_RATIO) return null
        return LocalCleanupValidation.Rejected(
            LocalCleanupValidation.Reason.LENGTH_EXPANSION,
            "output is ${String.format(java.util.Locale.ROOT, "%.1f", ratio)}x the input's " +
                "content length (maximum ${LENGTH_EXPANSION_MAX_RATIO}x)",
        )
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
