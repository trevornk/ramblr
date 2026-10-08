package com.trevornk.ramblr

/**
 * Drops the sentence-final period ASR/cleanup appends to a very short dictation.
 *
 * "Okay." / "Sounds good." / "On my way." read as typed fragments or chat replies, where a trailing
 * full stop looks stiff. Pure and stateless so every dictation host (accessibility service, IME)
 * gets identical behaviour through [DictationRuntime.finalizeForDelivery].
 *
 * Strips exactly one final `.` when ALL of these hold:
 *  - the text has at most [MAX_WORDS] whitespace-separated words,
 *  - it ends in a single `.` (not `..`, `...`, or an ellipsis character),
 *  - the last word is not an abbreviation (see [isAbbreviation]) -- `Ask Dr.` / `at 5 p.m.` /
 *    `from the U.S.` keep their period,
 *  - no earlier word closes a sentence (`Yes. No.` is left alone rather than half-edited),
 *  - something readable remains (a lone `.` is never turned into an empty string).
 *
 * `?` and `!` are never touched, and capitalization is preserved. Trailing whitespace is kept.
 */
object ShortUtterancePunctuation {
    const val MAX_WORDS = 3

    /** Common abbreviations, lowercase and without the period, whose final `.` is part of the
     *  word. Deliberately excludes words that are also plain sentence enders ("no", "may", "sun"). */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "mx", "dr", "prof", "sr", "jr", "st", "mt", "ft",
        "vs", "etc", "inc", "ltd", "co", "corp", "approx", "dept", "est", "fig",
        "gen", "gov", "hon", "lt", "col", "sgt", "capt", "rev", "sen", "rep",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    fun stripTrailingPeriod(text: String): String {
        val body = text.trimEnd()
        if (!body.endsWith('.') || body.endsWith("..")) return text
        val trailing = text.substring(body.length)
        val words = body.trim().split(Regex("\\s+"))
        if (words.size > MAX_WORDS) return text
        if (words.dropLast(1).any { it.endsWithSentenceTerminator() && !isAbbreviation(it) }) return text
        if (isAbbreviation(words.last())) return text
        val stripped = body.dropLast(1)
        if (stripped.none { it.isLetterOrDigit() }) return text
        return stripped + trailing
    }

    private fun String.endsWithSentenceTerminator(): Boolean =
        endsWith('.') || endsWith('?') || endsWith('!') || endsWith('…')

    /** True for a word whose trailing period belongs to it: a known abbreviation, a single-letter
     *  initial ("J."), or a dotted form with letters on both sides of an inner period ("U.S.",
     *  "p.m.", "e.g.", "example.com."). A period between digits ("2.0.") is a version/decimal, not
     *  an abbreviation, so "version 2.0." still loses its closing stop. */
    internal fun isAbbreviation(word: String): Boolean {
        val core = word.trim('"', '\'', '(', ')', '[', ']', '\u201C', '\u201D', '\u2018', '\u2019')
        val noDot = core.removeSuffix(".")
        if (noDot.isEmpty()) return false
        if (noDot.lowercase() in ABBREVIATIONS) return true
        if (noDot.length == 1 && noDot[0].isLetter()) return true
        for (i in 1 until noDot.length - 1) {
            if (noDot[i] == '.' && noDot[i - 1].isLetter() && noDot[i + 1].isLetter()) return true
        }
        return false
    }
}
