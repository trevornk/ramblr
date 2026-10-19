package com.trevornk.ramblr

/**
 * Display-only clean-up of Gemini Live *interim* transcripts (`interimInputTranscription`).
 *
 * Each interim is the whole hypothesis so far (cumulative, not a delta), and Gemini already sends it
 * cased and punctuated. What it does not always send is correct spacing: measured against the real
 * endpoint, a seam where an earlier part of the hypothesis was revised intermittently loses its
 * space for a few messages -- `send areport`, `got` + `moved` as `gotmoved`, `schema.Run`,
 * `and thenroll` -- and heals on a later message. The final `inputTranscription` is always correctly
 * spaced, so only the live preview in the field/bubble/IME looked run-together.
 *
 * This repairs those seams for display only. The text that is committed/cleaned/delivered is the
 * final transcript and never passes through here.
 */

/** Tokens (whitespace-delimited) we must never re-space: emails, URLs, bare hosts. */
private fun isAtomicToken(token: String): Boolean =
    token.contains('@') || token.contains("://") || token.startsWith("www.", ignoreCase = true)

// `schema.Run` -> `schema. Run`: a sentence end glued to a capitalized word. Requires two lowercase
// letters before the dot and a capital followed by a lowercase after it, so `a.m.`, `U.S.A`, `Node.js`,
// `Node.JS`, `e.g.` and `3.5` are not mistaken for a sentence break.
private val GLUED_SENTENCE_END = Regex("(?<=\\p{Ll}{2}\\.)(?=\\p{Lu}\\p{Ll})")

// `Really?Yes` -> `Really? Yes`, `great,so` -> `great, so`, `one;two` -> `one; two`. Latin letter after
// the mark only, so CJK/Thai text and digit groups (`1,000`) are untouched.
private val GLUED_AFTER_MARK = Regex("(?<=[\\p{L}\\p{N}][!?,;])(?=[A-Za-z])")

/** Re-spaces the punctuation seams that need no history. Pure and idempotent. */
fun repairInterimSpacing(text: String): String {
    if (text.isEmpty()) return text
    val out = StringBuilder(text.length + 8)
    var i = 0
    while (i < text.length) {
        if (text[i].isWhitespace()) { out.append(text[i]); i++; continue }
        var j = i
        while (j < text.length && !text[j].isWhitespace()) j++
        val token = text.substring(i, j)
        out.append(
            if (isAtomicToken(token)) token
            else token.replace(GLUED_SENTENCE_END, " ").replace(GLUED_AFTER_MARK, " ")
        )
        i = j
    }
    return out.toString()
}

/**
 * One per live attempt (one recording). Remembers the spacing it last showed and carries it forward:
 * a boundary that had whitespace in the previous message keeps it in the next one while the two
 * hypotheses still agree on the characters (ignoring whitespace) up to that point. That heals
 * lowercase run-togethers (`areport`, `gotmoved`) that no punctuation rule can see, without a
 * dictionary and without touching any non-whitespace character. A tail the model genuinely
 * rewrote is shown exactly as sent; only the matching prefix is re-spaced. Whitespace is only ever
 * *added*, so a space the model later restores is never lost. Main-thread only.
 */
class CloudInterimNormalizer {
    private var previous = ""

    fun normalize(text: String): String {
        val repaired = repairInterimSpacing(text)
        val healed = carrySpacingForward(previous, repaired)
        previous = healed
        return healed
    }

    private fun carrySpacingForward(prev: String, cur: String): String {
        if (prev.isEmpty() || cur.isEmpty()) return cur
        // gaps[i] = the whitespace between the i-th and (i+1)-th non-whitespace chars of prev.
        val prevChars = StringBuilder()
        val gaps = ArrayList<String>()
        val gap = StringBuilder()
        for (ch in prev) {
            if (ch.isWhitespace()) { if (prevChars.isNotEmpty()) gap.append(ch); continue }
            if (prevChars.isNotEmpty()) { gaps.add(gap.toString()); gap.setLength(0) }
            prevChars.append(ch)
        }
        // How many leading non-whitespace characters the two messages agree on.
        var agree = 0
        for (ch in cur) {
            if (ch.isWhitespace()) continue
            if (agree >= prevChars.length || prevChars[agree] != ch) break
            agree++
        }
        if (agree < 2) return cur

        val out = StringBuilder(cur.length + 8)
        var index = 0
        var sawSpace = false
        for (ch in cur) {
            if (ch.isWhitespace()) { out.append(ch); sawSpace = true; continue }
            // Boundary between non-whitespace chars index-1 and index, both inside the agreed prefix.
            if (index in 1 until agree && !sawSpace && gaps[index - 1].isNotEmpty()) out.append(gaps[index - 1])
            out.append(ch)
            index++
            sawSpace = false
        }
        return out.toString()
    }
}
