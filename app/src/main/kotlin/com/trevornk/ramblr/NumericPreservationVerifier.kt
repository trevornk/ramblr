package com.trevornk.ramblr

/** Result of exact, ordered semantic numeric preservation after local-model cleanup. */
sealed class NumericPreservation {
    object Valid : NumericPreservation()
    data class Rejected(val detail: String) : NumericPreservation()
}

/**
 * Verifies that local cleanup returned exactly the normalized numeric sequence it received (#155).
 * Formatting is not identity: a ten-digit phone value may gain parentheses, spaces, or hyphens,
 * and a number may come back spelled as a word when it reads as the same value (#325).
 * Values may not change, disappear, duplicate, or reorder. Diagnostics contain counts only, never
 * transcript or model output.
 */
object NumericPreservationVerifier {
    fun verify(
        normalization: SpokenNumberNormalization,
        modelOutput: String,
    ): NumericPreservation {
        val actual = NumericValueExtractor.extract(modelOutput)
        if (actual == normalization.semanticValues) return NumericPreservation.Valid
        // The model may hand a small number back as a word ("3 times" -> "three times", "1st" ->
        // "first"): same value, different spelling. Read the output through the same normalizer
        // the input went through and require the identical ordered values. A changed, dropped,
        // duplicated or reordered number still fails, because it changes the normalized values too.
        if (SpokenNumberNormalizer.normalize(modelOutput).semanticValues == normalization.semanticValues) {
            return NumericPreservation.Valid
        }
        return NumericPreservation.Rejected(
            "numeric sequence diverged (expected ${normalization.semanticValues.size} value(s), " +
                "found ${actual.size})",
        )
    }
}

/** Shared numeric semantics for normalized input and post-model output. */
internal object NumericValueExtractor {
    fun extract(text: String): List<String> =
        VALUE.findAll(text).map { match ->
            // A phone-shaped match is digits only: its separators are formatting, and `.` must not
            // survive the way a decimal point does or `212.555.3476` would never equal the
            // normalized `2125553476`.
            if (isPhone(match.value)) {
                match.value.filter { it.isDigit() }
            } else {
                match.value.filter { it.isDigit() || it == '.' }.trimEnd('.')
            }
        }.toList()

    /**
     * A single dot is far more likely a decimal point than a phone separator, so `3.1415926`
     * keeps its decimal meaning while `212.555.3476` is treated as formatting.
     */
    private fun isPhone(value: String): Boolean {
        if (!PHONE.matches(value)) return false
        return value.count { it == '.' } != 1
    }

    /**
     * Phone separators models actually emit: space, hyphen, or dot, with optional area parens and
     * an optional country code. Covers the 7-digit local form as well as 10-digit NANP, because a
     * punctuation-only reformat must never read as a value change.
     */
    private const val SEP = "[ .-]?"
    private const val PHONE_BODY =
        "(?:\\+?\\d{1,3}$SEP)?(?:\\(\\d{3}\\)$SEP|\\d{3}$SEP)?\\d{3}$SEP\\d{4}"

    private val PHONE = Regex(PHONE_BODY)

    // Phone is the first alternative so `(212) 555-3476` is one semantic value. Other punctuation
    // is deliberately parsed as separate values: `100 minus 25 is 75` must remain a 3-value
    // sequence, while currency grouping and decimals stay one value.
    private val VALUE = Regex(
        "(?<![\\d+(])$PHONE_BODY(?!\\d)|" +
            "(?<![\\p{L}\\d])[$€£¥]?\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:st|nd|rd|th)?(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE,
    )
}
