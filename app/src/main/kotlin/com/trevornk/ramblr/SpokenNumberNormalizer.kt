package com.trevornk.ramblr

import java.math.BigDecimal

/** Deterministic text and the ordered numeric values introduced into it for local cleanup. */
data class SpokenNumberNormalization(
    val text: String,
    val semanticValues: List<String>,
)

/**
 * Conservative spoken-number to digit normalization for the local cleanup path (#155).
 *
 * This is intentionally pure Kotlin with no model or Android dependency. Ambiguous quantifiers
 * (half, quarter, dozen) are not vocabulary, and a bare "one" is left alone because it is commonly
 * an article or pronoun. Renderings are plain ungrouped digits; units remain words. A decimal with
 * a multiplier is expanded (`one point two million` -> `1200000`) to avoid ambiguous model tokens.
 */
object SpokenNumberNormalizer {
    fun normalize(text: String): SpokenNumberNormalization {
        val tokens = tokenize(text)
        val replacements = mutableListOf<Replacement>()
        var index = 0
        while (index < tokens.size) {
            val monthOrdinal = parseMonthOrdinal(tokens, index, text)
            if (monthOrdinal != null) {
                replacements += monthOrdinal
                index = monthOrdinal.nextToken
                continue
            }
            if (tokens[index].word !in NUMBER_OR_ORDINAL_WORDS) {
                index++
                continue
            }
            // A run with no confident rendering is skipped whole. Re-scanning inside it would
            // convert a fragment of an expression we just declined to read ("twenty twenty
            // dollar bills"), which is exactly the silent corruption this pass exists to avoid.
            val runEnd = runEndToken(tokens, index, text)
            val parsed = parseNumberRun(tokens, index, text)
            if (parsed != null) replacements += parsed
            index = parsed?.nextToken ?: (runEnd + 1)
        }
        if (replacements.isEmpty()) {
            return SpokenNumberNormalization(text, NumericValueExtractor.extract(text))
        }

        val output = StringBuilder(text.length)
        var position = 0
        replacements.forEach { replacement ->
            output.append(text, position, replacement.start)
            output.append(replacement.value)
            position = replacement.end
        }
        output.append(text, position, text.length)
        val normalizedText = output.toString()
        return SpokenNumberNormalization(
            normalizedText,
            NumericValueExtractor.extract(normalizedText),
        )
    }

    private fun parseMonthOrdinal(tokens: List<Token>, index: Int, text: String): Replacement? {
        if (tokens[index].word !in MONTHS || index + 1 >= tokens.size) return null
        val run = collectOrdinalRun(tokens, index + 1, text) ?: return null
        val rendered = renderOrdinal(run.words) ?: return null
        return Replacement(tokens[index + 1].start, tokens[run.endToken].end, rendered, run.endToken + 1)
    }

    /** Collect through the first ordinal and stop, so a following spoken year is a separate run. */
    private fun collectOrdinalRun(tokens: List<Token>, start: Int, text: String): Run? {
        val words = mutableListOf<String>()
        var cursor = start
        while (cursor < tokens.size) {
            if (cursor > start && text.substring(tokens[cursor - 1].end, tokens[cursor].start).any { !it.isWhitespace() }) return null
            val word = tokens[cursor].word
            if (word == "and" && words.isNotEmpty()) {
                words += word
            } else if (word in NUMBER_OR_ORDINAL_WORDS) {
                words += word
                if (word in ORDINALS) return Run(words, cursor)
            } else {
                return null
            }
            cursor++
        }
        return null
    }

    private fun runEndToken(tokens: List<Token>, index: Int, text: String): Int =
        collectRun(tokens, index, text)?.endToken ?: index

    private fun parseNumberRun(tokens: List<Token>, index: Int, text: String): Replacement? {
        val run = collectRun(tokens, index, text) ?: return null
        val previous = tokens.getOrNull(index - 1)?.word
        val next = tokens.getOrNull(run.endToken + 1)?.word
        val rendered = renderTime(run.words, previous, next)
            ?: renderDecimal(run.words)
            ?: renderOrdinal(run.words)
            ?: renderSpokenYear(run.words, previous)
            ?: renderCardinal(run.words, next)
            ?: return null
        return Replacement(tokens[index].start, tokens[run.endToken].end, rendered, run.endToken + 1)
    }

    private fun collectRun(tokens: List<Token>, start: Int, text: String): Run? {
        val words = mutableListOf<String>()
        var cursor = start
        var end = start - 1
        while (cursor < tokens.size) {
            // A run may only span whitespace. Punctuation ends it, so "one, two" and "one. Two"
            // stay two numbers instead of merging into 12.
            if (cursor > start && text.substring(tokens[cursor - 1].end, tokens[cursor].start).any { !it.isWhitespace() }) break
            val word = tokens[cursor].word
            val numeric = word in NUMBER_OR_ORDINAL_WORDS || word == "point" || word == "dot"
            // "and" continues only a scale compound ("four hundred and fifty"); "one and two"
            // is two separate quantities.
            val connector = word == "and" && words.any { it in BIG_SCALES || it == "hundred" } &&
                cursor + 1 < tokens.size && tokens[cursor + 1].word in NUMBER_OR_ORDINAL_WORDS
            if (!numeric && !connector) break
            words += word
            end = cursor
            cursor++
        }
        return if (end >= start) Run(words, end) else null
    }

    /**
     * Clock times in the shapes ASR actually produces: `four thirty`, `four thirty five`,
     * `twelve oh five`. Only fires with explicit time context ("at"/"around"/"by") or an
     * am/pm/o'clock suffix, so a bare "four thirty five" stays a cardinal.
     */
    private fun renderTime(words: List<String>, previous: String?, next: String?): String? {
        if (words.size !in 2..3 || (previous !in TIME_CONTEXT && next !in TIME_SUFFIXES)) return null
        val hour = VALUES[words[0]] ?: return null
        if (hour !in 1..12) return null
        val minute = when (words.size) {
            2 -> VALUES[words[1]] ?: return null
            // "oh five" -> 5; "thirty five" -> 35. Anything else is not a clock reading.
            else -> {
                val first = VALUES[words[1]] ?: return null
                val second = VALUES[words[2]] ?: return null
                when {
                    words[1] in ZERO_WORDS && second in 1..9 -> second
                    first in TENS_WORDS_VALUES && second in 1..9 -> first + second
                    else -> return null
                }
            }
        }
        if (minute !in 0..59) return null
        return "$hour:${minute.toString().padStart(2, '0')}"
    }

    private fun renderDecimal(words: List<String>): String? {
        val point = words.indexOfFirst { it == "point" || it == "dot" }
        if (point <= 0 || point == words.lastIndex) return null
        val multiplierWord = words.last().takeIf { it in BIG_SCALES }
        val fractionEnd = if (multiplierWord == null) words.size else words.lastIndex
        val fraction = words.subList(point + 1, fractionEnd)
        if (fraction.isEmpty() || fraction.any { it !in DIGITS }) return null
        val whole = cardinalValue(words.subList(0, point).filterNot { it == "and" }) ?: return null
        var value = BigDecimal("$whole.${fraction.joinToString("") { DIGITS.getValue(it).toString() }}")
        if (multiplierWord != null) value = value.multiply(BigDecimal(BIG_SCALES.getValue(multiplierWord)))
        return value.stripTrailingZeros().toPlainString()
    }

    private fun renderOrdinal(words: List<String>): String? {
        val last = words.lastOrNull() ?: return null
        val ordinalValue = ORDINALS[last] ?: return null
        val cardinalWords = words.dropLast(1).filterNot { it == "and" }
        val prefix = if (cardinalWords.isEmpty()) null else cardinalValue(cardinalWords) ?: return null
        // Scale ordinals multiply ("two hundredth" = 200th); the rest add ("twenty first" = 21st).
        val value = when {
            prefix == null -> ordinalValue
            last in SCALE_ORDINALS -> prefix * ordinalValue
            else -> prefix + ordinalValue
        }
        val suffix = when (value % 100) {
            11L, 12L, 13L -> "th"
            else -> when (value % 10) { 1L -> "st"; 2L -> "nd"; 3L -> "rd"; else -> "th" }
        }
        return "$value$suffix"
    }

    /**
     * Common ASR year form: `twenty twenty six` -> 2026, `nineteen eighty four` -> 1984.
     * Requires date context ("in 2026", "August eighteenth 2026") because `twenty twenty dollar
     * bills` is two quantities, not a year. A two-word cardinal such as `twenty one` stays 21.
     */
    private fun renderSpokenYear(words: List<String>, previous: String?): String? {
        if (previous == null) return null
        if (previous !in YEAR_CONTEXT && previous !in ORDINALS && previous !in MONTHS) return null
        val century = when (words.firstOrNull()) {
            "nineteen" -> 1900L
            "twenty" -> 2000L
            else -> return null
        }
        val remainderWords = words.drop(1).filterNot { it == "and" }
        if (remainderWords.isEmpty()) return null
        val looksLikeYear = remainderWords.size >= 2 || (VALUES[remainderWords.first()] ?: 0L) >= 10L
        if (!looksLikeYear) return null
        val remainder = if (remainderWords.all { it in DIGITS }) {
            remainderWords.joinToString("") { DIGITS.getValue(it).toString() }.toLongOrNull()
        } else {
            cardinalValue(remainderWords)
        } ?: return null
        if (remainder !in 0L..99L) return null
        return (century + remainder).toString()
    }

    private fun renderCardinal(words: List<String>, next: String? = null): String? {
        val cardinalWords = words.filterNot { it == "and" }
        if (cardinalWords.isEmpty()) return null
        if (cardinalWords == listOf("one") && next !in UNAMBIGUOUS_ONE_UNITS) return null
        if (cardinalWords.all { it in DIGITS } && cardinalWords.size >= 2) {
            return cardinalWords.joinToString("") { DIGITS.getValue(it).toString() }
        }
        return cardinalValue(cardinalWords)?.toString()
    }

    /**
     * Standard cardinal accumulation ("twelve thousand five hundred" -> 12500).
     *
     * Consecutive value words must strictly decrease ("twenty three" = 23), so a non-cardinal
     * sequence such as "twenty twenty" is rejected rather than silently summed to 40.
     */
    private fun cardinalValue(words: List<String>): Long? {
        if (words.isEmpty() || words.any { it !in VALUES }) return null
        var total = 0L
        var chunk = 0L
        var lastAdded: Long? = null
        words.forEach { word ->
            when {
                word == "hundred" -> {
                    chunk = (if (chunk == 0L) 1L else chunk) * 100L
                    lastAdded = null
                }
                word in BIG_SCALES -> {
                    total += (if (chunk == 0L) 1L else chunk) * BIG_SCALES.getValue(word)
                    chunk = 0L
                    lastAdded = null
                }
                else -> {
                    val value = VALUES.getValue(word)
                    val previous = lastAdded
                    if (previous != null && value >= previous) return null
                    chunk += value
                    lastAdded = value
                }
            }
        }
        return total + chunk
    }

    private fun tokenize(text: String): List<Token> = TOKEN.findAll(text).mapNotNull { match ->
        val value = match.value
        val leading = value.indexOfFirst { it.isLetterOrDigit() }.takeIf { it >= 0 } ?: return@mapNotNull null
        val trailing = value.indexOfLast { it.isLetterOrDigit() }
        Token(match.range.first + leading, match.range.first + trailing + 1, value.substring(leading, trailing + 1).lowercase())
    }.toList()

    private data class Token(val start: Int, val end: Int, val word: String)
    private data class Run(val words: List<String>, val endToken: Int)
    private data class Replacement(val start: Int, val end: Int, val value: String, val nextToken: Int)

    private val TOKEN = Regex("\\S+")
    private val DIGITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val BIG_SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val VALUES: Map<String, Long> = buildMap {
        DIGITS.forEach { (word, value) -> put(word, value.toLong()) }
        put("ten", 10); put("eleven", 11); put("twelve", 12); put("thirteen", 13)
        put("fourteen", 14); put("fifteen", 15); put("sixteen", 16); put("seventeen", 17)
        put("eighteen", 18); put("nineteen", 19); put("twenty", 20); put("thirty", 30)
        put("forty", 40); put("fifty", 50); put("sixty", 60); put("seventy", 70)
        put("eighty", 80); put("ninety", 90); put("hundred", 100)
        BIG_SCALES.forEach { (word, value) -> put(word, value) }
    }
    private val ORDINALS = mapOf(
        "first" to 1L, "second" to 2L, "third" to 3L, "fourth" to 4L, "fifth" to 5L,
        "sixth" to 6L, "seventh" to 7L, "eighth" to 8L, "ninth" to 9L, "tenth" to 10L,
        "eleventh" to 11L, "twelfth" to 12L, "thirteenth" to 13L, "fourteenth" to 14L,
        "fifteenth" to 15L, "sixteenth" to 16L, "seventeenth" to 17L, "eighteenth" to 18L,
        "nineteenth" to 19L, "twentieth" to 20L, "thirtieth" to 30L, "fortieth" to 40L,
        "fiftieth" to 50L, "sixtieth" to 60L, "seventieth" to 70L, "eightieth" to 80L,
        "ninetieth" to 90L, "hundredth" to 100L, "thousandth" to 1_000L,
    )
    private val NUMBER_OR_ORDINAL_WORDS = VALUES.keys + ORDINALS.keys
    private val SCALE_ORDINALS = setOf("hundredth", "thousandth")
    private val ZERO_WORDS = setOf("oh", "zero")
    private val TENS_WORDS_VALUES = setOf(20L, 30L, 40L, 50L)
    private val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
        "sep", "sept", "oct", "nov", "dec",
    )
    private val TIME_CONTEXT = setOf("at", "around", "by")
    private val YEAR_CONTEXT = setOf("in", "since", "of", "year", "from", "until", "till", "by")
    private val TIME_SUFFIXES = setOf("am", "pm", "oclock")
    private val UNAMBIGUOUS_ONE_UNITS = setOf(
        "dollar", "dollars", "euro", "euros", "pound", "pounds", "yen",
        "percent", "percentage", "percentages",
    )
}
