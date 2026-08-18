package com.trevornk.ramblr

/**
 * One numeric span lifted out of a transcript before local cleanup (#155).
 *
 * [original] is the exact substring of the raw transcript, byte-for-byte, including any internal
 * punctuation or casing. [sentinel] is the opaque token that stood in its place while the model
 * saw the text.
 */
data class ProtectedNumericSpan(val sentinel: String, val original: String)

/**
 * The result of [NumericSpanProtector.mask].
 *
 * [maskedText] is what should be sent to the model. When [spans] is empty, [maskedText] is
 * reference-identical to the input and [NumericSpanProtector.restore] is a pure pass-through --
 * text with no numbers must never be perturbed by this feature.
 */
data class NumericMasking(
    val originalText: String,
    val maskedText: String,
    val spans: List<ProtectedNumericSpan>,
) {
    val isEmpty: Boolean get() = spans.isEmpty()
}

/**
 * Outcome of putting the protected spans back after cleanup.
 *
 * [Failed] is not an error condition to be swallowed: it means the model returned text this code
 * cannot reassemble without guessing, and the caller must treat local cleanup as a failed step so
 * the waterfall falls through to the next step or to raw-text injection. Raw text is unpolished
 * but numerically correct, which is the whole point of #155.
 */
sealed class NumericRestoration {
    data class Restored(val text: String) : NumericRestoration()
    data class Failed(val reason: String) : NumericRestoration()
}

/** Verdict on one protected local-cleanup round trip. See [protectedLocalCleanupOutcome]. */
sealed class ProtectedCleanupOutcome {
    data class Accepted(val text: String) : ProtectedCleanupOutcome()

    /** [label] is the short failure category surfaced in the step-failure message;
     *  [detail] is a longer, still non-sensitive diagnostic for logcat -- like
     *  [LocalCleanupValidation.Rejected.detail] neither ever contains transcript or model text. */
    data class Rejected(val label: String, val detail: String) : ProtectedCleanupOutcome() {
        companion object {
            /** [label] used when the model mangled the sentinels rather than the prose. */
            const val NUMERIC_RESTORATION = "NUMERIC_RESTORATION"
        }
    }
}

/**
 * The post-inference half of the protected local-cleanup path (#155): restore the numeric spans
 * [NumericSpanProtector.mask] took out, then run the existing [LocalCleanupOutputValidator]
 * against the ORIGINAL transcript and the RESTORED output.
 *
 * Ordering matters, and this function exists so that ordering lives in one pure, unit-testable
 * place instead of inside `RealLocalInferenceEngine`, which the JVM suite cannot construct:
 *
 *  - restoring *before* validating is what keeps [LocalCleanupOutputValidator] meaningful.
 *    Validating masked-vs-masked would neuter [LocalCleanupValidation.Reason.NUMERIC_DIVERGENCE]
 *    entirely (masked text contains no numbers, so the check returns early) and would compare a
 *    length ratio between two texts whose numeric content had been replaced by short sentinels.
 *  - validating against [rawInput] rather than the masked input keeps the length baseline
 *    exactly what the validator's thresholds were measured against.
 *
 * A restoration failure is reported as a rejection, not an exception: the caller must treat it as
 * this one step failing so the waterfall falls through to raw text.
 */
fun protectedLocalCleanupOutcome(
    rawInput: String,
    systemPrompt: String,
    masking: NumericMasking,
    modelOutput: String,
): ProtectedCleanupOutcome =
    when (val restoration = NumericSpanProtector.restore(masking, modelOutput)) {
        is NumericRestoration.Failed ->
            ProtectedCleanupOutcome.Rejected(
                ProtectedCleanupOutcome.Rejected.NUMERIC_RESTORATION,
                restoration.reason,
            )
        is NumericRestoration.Restored ->
            when (val verdict = LocalCleanupOutputValidator.validate(rawInput, systemPrompt, restoration.text)) {
                is LocalCleanupValidation.Valid -> ProtectedCleanupOutcome.Accepted(restoration.text)
                is LocalCleanupValidation.Rejected ->
                    ProtectedCleanupOutcome.Rejected(verdict.reason.name, verdict.detail)
            }
    }

/**
 * Keeps spoken numbers away from the local cleanup model (#155).
 *
 * ## Why protect rather than normalize
 *
 * The measured failure on the 69-case corpus is not that the models fail to *find* numbers, it is
 * that they perform spoken-numeral -> digit conversion badly: 24.6% corruption for LFM2.5-350M,
 * 47.8% for mumble-cleanup, with order-of-magnitude errors (`one point two million dollars` ->
 * `$1200M`) and dropped digits in phone numbers. Masking removes that operation from the model's
 * reach entirely. It cannot invent a wrong value: the worst case is that the user gets
 * `twenty three percent` spelled out -- visibly unconverted rather than silently wrong.
 *
 * A rule-based spoken-numeral -> digit converter would additionally have to *decide* the correct
 * rendering (is it `23%`, `23 percent`, or `twenty-three percent`?), which changes user-visible
 * output and carries its own correctness surface. This class deliberately does neither.
 *
 * ## Sentinel choice
 *
 * Sentinels are `ZQX` followed by a bijective base-26 letter suffix: `ZQXA`, `ZQXB`, ... `ZQXZ`,
 * `ZQXAA`, ... Every property of that shape is load-bearing for a 0.5B model asked to "fix
 * punctuation, capitalization, and obvious speech-to-text errors":
 *
 *  - **Letters only, no digits.** An index like `NUM1` reintroduces exactly the token class the
 *    model mishandles; it will happily respace, comma-group or renumber it. The index is encoded
 *    in letters so there is no digit anywhere in the masked text.
 *  - **No punctuation and no brackets.** `{{1}}`, `<n1>` and `[1]` all read as markup, and a
 *    model told to fix punctuation treats markup as punctuation to normalize, translate to the
 *    surrounding markup dialect, or drop. `ZQXA` is a bare word to the tokenizer.
 *  - **No whitespace.** A multi-token sentinel can be split across a line wrap or have a
 *    "missing" space inserted into it.
 *  - **`ZQX` does not occur in English** (nor in any of the 23 reference transcripts), which
 *    minimizes collisions with dictated content. The bare word also avoids the numeric and markup
 *    token classes these small models demonstrably mangle. It is not assumed to survive: the
 *    restoration checks below reject missing or unknown sentinels and fall back to raw text.
 *  - **Case-insensitive on the way back.** Models do lowercase or title-case mid-sentence words;
 *    [restore] matches case-insensitively so a returned `Zqxa` still resolves.
 *
 * ## Degradation policy (exact)
 *
 * [restore] never throws and never emits a value the speaker did not say. Given the model's
 * output:
 *
 *  1. **Every recognized sentinel occurrence is replaced by its span verbatim.** If the model
 *     duplicated a sentinel, the number appears twice. That is a prose defect of the same kind
 *     the model can already introduce with ordinary words, and it is strictly preferable to
 *     inventing a value.
 *  2. **A sentinel glued to trailing letters** (`ZQXAdollars`) resolves by longest valid prefix,
 *     so only the sentinel part is replaced and the stray letters are left alone.
 *  3. **A missing sentinel fails the restoration** -- [NumericRestoration.Failed]. The model
 *     dropped a number outright; re-inserting it at a guessed position would produce a sentence
 *     that reads as authoritative and is wrong ("Send to the account $450"). Falling through to
 *     raw text is the honest outcome.
 *  4. **An unknown sentinel fails the restoration.** `ZQXQQ` when only two spans exist means the
 *     model hallucinated a token; there is nothing correct to substitute.
 *  5. **No spans at all** -> the model output is returned byte-identical, with no scanning.
 *
 * Kept free of Android and llama.cpp imports so every branch above is a plain JVM unit test, in
 * the same style as [LocalCleanupOutputValidator].
 */
object NumericSpanProtector {

    /** The fixed, non-English prefix every sentinel starts with. See the class kdoc. */
    const val SENTINEL_PREFIX = "ZQX"

    /** Matches a sentinel-shaped token anywhere in text, case-insensitively. The suffix is greedy
     *  on purpose; [resolveSentinel] then takes the longest *valid* prefix of it, which is what
     *  makes `ZQXAdollars` degrade gracefully instead of failing. */
    private val SENTINEL_PATTERN = Regex("${SENTINEL_PREFIX}[a-z]+", RegexOption.IGNORE_CASE)

    /**
     * Replaces every numeric span in [text] with a sentinel.
     *
     * Returns an empty masking (input unchanged) when [text] contains no numeric span. A raw
     * transcript that already contains a sentinel-shaped word is still masked, but that word is
     * skipped when assigning sentinels. If it survives in model output, [restore] recognizes it
     * as unknown and safely fails the local step rather than substituting the wrong number.
     */
    fun mask(text: String): NumericMasking {
        val spans = detectSpans(text)
        if (spans.isEmpty()) return NumericMasking(text, text, emptyList())

        val builder = StringBuilder(text.length)
        val protected = mutableListOf<ProtectedNumericSpan>()
        var cursor = 0
        var sentinelIndex = 0
        spans.forEach { range ->
            var sentinel = sentinelFor(sentinelIndex++)
            while (text.contains(sentinel, ignoreCase = true)) {
                sentinel = sentinelFor(sentinelIndex++)
            }
            builder.append(text, cursor, range.first)
            builder.append(sentinel)
            protected += ProtectedNumericSpan(sentinel, text.substring(range.first, range.last))
            cursor = range.last
        }
        builder.append(text, cursor, text.length)
        return NumericMasking(text, builder.toString(), protected)
    }

    /**
     * Puts the protected spans back into [modelOutput]. See the class kdoc for the exact policy;
     * in short, verbatim substitution, and a drop or a hallucinated sentinel fails the whole
     * cleanup rather than guessing.
     */
    fun restore(masking: NumericMasking, modelOutput: String): NumericRestoration {
        if (masking.isEmpty) return NumericRestoration.Restored(modelOutput)

        val bySentinel: Map<String, String> =
            masking.spans.associate { it.sentinel.lowercase() to it.original }
        val seen = mutableSetOf<String>()
        val builder = StringBuilder(modelOutput.length)
        var cursor = 0

        for (match in SENTINEL_PATTERN.findAll(modelOutput)) {
            val resolved = resolveSentinel(match.value, bySentinel)
                ?: return NumericRestoration.Failed(
                    "model returned an unrecognized sentinel-shaped token",
                )
            builder.append(modelOutput, cursor, match.range.first)
            builder.append(resolved.original)
            seen += resolved.key
            // Only the matched prefix was a sentinel; anything the model glued onto its tail
            // stays in the output untouched.
            cursor = match.range.first + resolved.consumedLength
        }
        builder.append(modelOutput, cursor, modelOutput.length)

        val missing = bySentinel.keys.count { it !in seen }
        if (missing > 0) {
            return NumericRestoration.Failed("$missing of ${bySentinel.size} protected number(s) missing from output")
        }
        return NumericRestoration.Restored(builder.toString())
    }

    /** A resolved sentinel occurrence: which span it maps to, and how many characters of the
     *  greedy match actually belonged to the sentinel. */
    private data class ResolvedSentinel(val key: String, val original: String, val consumedLength: Int)

    private fun resolveSentinel(matched: String, bySentinel: Map<String, String>): ResolvedSentinel? {
        val lowered = matched.lowercase()
        for (length in lowered.length downTo SENTINEL_PREFIX.length + 1) {
            val candidate = lowered.substring(0, length)
            val original = bySentinel[candidate] ?: continue
            return ResolvedSentinel(candidate, original, length)
        }
        return null
    }

    /** `0 -> ZQXA`, `25 -> ZQXZ`, `26 -> ZQXAA`. Bijective base-26 so no index maps to an empty
     *  suffix and every sentinel is a distinct letter word. */
    internal fun sentinelFor(index: Int): String {
        require(index >= 0) { "sentinel index must be non-negative" }
        val suffix = StringBuilder()
        var n = index
        while (true) {
            suffix.append('A' + (n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return SENTINEL_PREFIX + suffix.reverse()
    }

    // --- detection -------------------------------------------------------------------------

    /**
     * Half-open `[first, last)` character ranges of every numeric span in [text], in order and
     * non-overlapping.
     *
     * Exposed internally so tests can assert on parsed spans rather than on masked-string
     * proxies.
     */
    internal fun detectSpans(text: String): List<IntRange2> {
        val tokens = tokenize(text)
        val spans = mutableListOf<IntRange2>()
        var index = 0
        while (index < tokens.size) {
            val startsWithMonth = tokens[index].kind == TokenKind.DATE_MONTH &&
                index + 1 < tokens.size && tokens[index + 1].kind.startsSpan
            if (!tokens[index].kind.startsSpan && !startsWithMonth) {
                index++
                continue
            }
            var end = index
            var cursor = index + 1
            while (cursor < tokens.size) {
                val kind = tokens[cursor].kind
                when {
                    kind.startsSpan -> {
                        end = cursor
                        cursor++
                    }
                    // A connector only stays inside a span if a number actually follows it, so a
                    // trailing "and" or a sentence-final "point" is never swallowed.
                    kind == TokenKind.CONNECTOR &&
                        cursor + 1 < tokens.size && tokens[cursor + 1].kind.startsSpan -> cursor++
                    kind == TokenKind.TRAILING -> {
                        end = cursor
                        cursor++
                    }
                    else -> break
                }
            }
            spans += IntRange2(tokens[index].coreStart, tokens[end].coreEnd)
            index = end + 1
        }
        return spans
    }

    /** A half-open character range. Named to avoid confusion with Kotlin's inclusive [IntRange]. */
    internal data class IntRange2(val first: Int, val last: Int)

    private enum class TokenKind(val startsSpan: Boolean) {
        /** Contains a digit: a digit run, `$12,500`, `50%`, `3:30`, `12/03/2026`, `1st`, `2pm`. */
        LITERAL(true),

        /** Spelled-out cardinal or ordinal, possibly hyphenated (`twenty-three`). */
        NUMBER_WORD(true),

        /** Joins two numeric tokens: `and`, `point`, `oh`, `dot`. Never starts or ends a span. */
        CONNECTOR(false),

        /** Attaches to the end of a span: `percent`, `dollars`, `o'clock`, `pm`. */
        TRAILING(false),

        /** Starts a date only when immediately followed by a numeric token (`August 18th`). */
        DATE_MONTH(false),

        OTHER(false),
    }

    private data class Token(val coreStart: Int, val coreEnd: Int, val kind: TokenKind)

    /**
     * Splits [text] on whitespace and classifies each token by its "core" -- the token with
     * surrounding punctuation stripped. Span offsets use the core, so a sentence-final period
     * stays outside the mask and the model can still repunctuate around it.
     */
    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            var coreStart = i
            var coreEnd = end
            while (coreStart < coreEnd && text[coreStart] in TRIMMABLE) coreStart++
            while (coreEnd > coreStart && text[coreEnd - 1] in TRIMMABLE) coreEnd--
            if (coreStart < coreEnd) {
                tokens += Token(coreStart, coreEnd, classify(text.substring(coreStart, coreEnd)))
            }
            i = end
        }
        return tokens
    }

    private fun classify(core: String): TokenKind {
        if (core.any { it.isDigit() }) return TokenKind.LITERAL
        val lowered = core.lowercase()
        if (lowered in CONNECTOR_WORDS) return TokenKind.CONNECTOR
        if (lowered in TRAILING_WORDS) return TokenKind.TRAILING
        if (lowered in DATE_MONTHS) return TokenKind.DATE_MONTH
        val parts = lowered.split('-').filter { it.isNotEmpty() }
        if (parts.isNotEmpty() && parts.all { it in NUMBER_WORDS || it in ORDINAL_WORDS }) {
            return TokenKind.NUMBER_WORD
        }
        return TokenKind.OTHER
    }

    /** Punctuation stripped off a token's edges before classification. Currency symbols and `%`
     *  are deliberately absent: they are part of the number, not decoration around it. */
    private val TRIMMABLE: Set<Char> = charArrayOf(
        '.', ',', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']', '{', '}', '\u2014', '\u2019',
    ).toSet()

    /** `point` and `dot` carry decimals (`one point two million`); `oh` is a spoken zero inside a
     *  digit run (`five oh five`); `and` joins cardinal parts (`four hundred and fifty`). */
    private val CONNECTOR_WORDS: Set<String> = setOf("and", "point", "dot", "oh")

    /** Units that belong to the number and must be masked with it, so the model is never invited
     *  to rewrite `ZQXA percent` as `ZQXA%` or `ZQXA dollars` as `$ZQXA`. */
    private val TRAILING_WORDS: Set<String> = setOf(
        "percent", "percentage", "%",
        "dollar", "dollars", "cent", "cents", "euro", "euros", "pound", "pounds",
        "buck", "bucks", "pence", "penny",
        "o'clock", "oclock", "am", "pm", "a.m", "p.m",
    )

    private val DATE_MONTHS: Set<String> = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    private val NUMBER_WORDS: Set<String> = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "billion", "trillion",
        "hundreds", "thousands", "millions", "billions",
    )

    private val ORDINAL_WORDS: Set<String> = setOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth", "thirtieth", "fortieth",
        "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth", "hundredth",
        "thousandth", "millionth",
    )
}
