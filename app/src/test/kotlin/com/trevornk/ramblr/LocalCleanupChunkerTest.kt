package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentence splitter + packer behind chunked on-device cleanup (#325). */
class LocalCleanupChunkerTest {
    private val max = 300

    private fun split(text: String, maxChars: Int = max) = LocalCleanupChunker.split(text, maxChars)

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Whatever the split does, no text may be lost or invented. */
    private fun assertLossless(text: String, chunks: List<LocalCleanupChunk>) {
        assertEquals(squash(text), squash(LocalCleanupChunker.join(chunks, chunks.map { it.text })))
    }

    // ---- size derivation ---------------------------------------------------------------------

    @Test fun `production-size prompt is limited by wall clock, not by context or output cap`() {
        assertEquals(LocalCleanupChunker.MAX_CHUNK_CHARS, LocalCleanupChunker.maxChunkChars(PostProcessor.SIMPLE_PROMPT))
    }

    @Test fun `chunk size leaves real headroom under the context and the 512-piece cap`() {
        val prompt = PostProcessor.SIMPLE_PROMPT
        val promptTokens = Math.ceil(prompt.length / 3.0).toInt()
        val inputTokens = Math.ceil(LocalCleanupChunker.maxChunkChars(prompt) / 3.0).toInt()
        val outputTokens = (inputTokens * 1.5).toInt()
        assertTrue(
            "prompt $promptTokens + in $inputTokens + out $outputTokens must be well inside 2048",
            promptTokens + 48 + inputTokens + outputTokens < LlamaCppInference.DEFAULT_CONTEXT_SIZE / 2,
        )
        assertTrue("output $outputTokens must be under half the cap", outputTokens < LlamaCppInference.MAX_RESPONSE_TOKENS / 2)
    }

    @Test fun `a very long system prompt shrinks the chunk but never below the floor`() {
        val huge = "x".repeat(6_000) // ~2000 tokens: more than the whole context
        assertEquals(LocalCleanupChunker.MIN_CHUNK_CHARS, LocalCleanupChunker.maxChunkChars(huge))
        val big = "x".repeat(4_000)
        val size = LocalCleanupChunker.maxChunkChars(big)
        assertTrue(size in LocalCleanupChunker.MIN_CHUNK_CHARS until LocalCleanupChunker.MAX_CHUNK_CHARS)
    }

    @Test fun `needsChunking is false at the limit and true one character over`() {
        val prompt = PostProcessor.SIMPLE_PROMPT
        assertFalse(LocalCleanupChunker.needsChunking("a".repeat(300), prompt))
        assertFalse(LocalCleanupChunker.needsChunking("  " + "a".repeat(300) + "  ", prompt))
        assertTrue(LocalCleanupChunker.needsChunking("a".repeat(301), prompt))
    }

    // ---- basic splitting ---------------------------------------------------------------------

    @Test fun `short text is a single chunk and blank text is none`() {
        assertEquals(listOf(LocalCleanupChunk("Hello there. How are you?", "")), split("  Hello there. How are you?\n"))
        assertTrue(split("  \n ").isEmpty())
    }

    @Test fun `long text splits at sentence boundaries and every chunk fits`() {
        val text = (1..60).joinToString(" ") { "This is sentence number $it of the recording." }
        val chunks = split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= max })
        assertTrue("every chunk ends on a sentence end", chunks.all { it.text.endsWith(".") })
        assertTrue("greedy packing fills chunks", chunks.dropLast(1).all { it.text.length > max - 60 })
        assertLossless(text, chunks)
        assertEquals("", chunks.first().joinBefore)
        assertTrue(chunks.drop(1).all { it.joinBefore == " " })
    }

    @Test fun `a ten minute transcript becomes a few dozen model-sized chunks`() {
        val text = (1..1500).joinToString(" ") { if (it % 14 == 0) "word$it." else "word$it" }
        val chunks = split(text)
        assertTrue(chunks.size in 25..60)
        assertTrue(chunks.all { it.text.length <= max })
        assertLossless(text, chunks)
    }

    // ---- sentence edge cases -----------------------------------------------------------------

    @Test fun `abbreviations do not end a sentence`() {
        val s = LocalCleanupChunker.sentences("I saw Dr. Smith and Mrs. Jones at 5 p.m. yesterday. Then we left.")
        assertEquals(listOf("I saw Dr. Smith and Mrs. Jones at 5 p.m. yesterday.", "Then we left."), s)
    }

    @Test fun `a sentence-final abbreviation keeps the next sentence attached -- the accepted cost`() {
        val s = LocalCleanupChunker.sentences("She works at Acme Inc. Bob works there too. Right.")
        assertEquals(listOf("She works at Acme Inc. Bob works there too.", "Right."), s)
    }

    @Test fun `e g and i e stay inside the sentence`() {
        val s = LocalCleanupChunker.sentences("Use a fruit, e.g. an apple, or a veg, i.e. a carrot. Done.")
        assertEquals(listOf("Use a fruit, e.g. an apple, or a veg, i.e. a carrot.", "Done."), s)
    }

    @Test fun `single initials stay with the surname`() {
        val s = LocalCleanupChunker.sentences("Ask J. Smith about it. He knows.")
        assertEquals(listOf("Ask J. Smith about it.", "He knows."), s)
    }

    @Test fun `decimals and version numbers are not split`() {
        val s = LocalCleanupChunker.sentences("The price is 3.5 dollars and version 2.0.1 shipped. Great news.")
        assertEquals(listOf("The price is 3.5 dollars and version 2.0.1 shipped.", "Great news."), s)
    }

    @Test fun `urls and email addresses are not split`() {
        val s = LocalCleanupChunker.sentences(
            "Go to https://example.com/a.b/c.html for details. Mail trevor@nashkellermedia.com or www.test.org. Thanks.",
        )
        assertEquals(
            listOf(
                "Go to https://example.com/a.b/c.html for details.",
                "Mail trevor@nashkellermedia.com or www.test.org.",
                "Thanks.",
            ),
            s,
        )
    }

    @Test fun `question marks exclamation marks ellipses and closing quotes end sentences`() {
        val s = LocalCleanupChunker.sentences("Really? Yes! Wow… ok then. He said \"stop.\" We stopped.")
        // "Wow… ok" continues in lowercase and the period variant is only decisive for ! ? …
        assertEquals(listOf("Really?", "Yes!", "Wow…", "ok then.", "He said \"stop.\"", "We stopped."), s)
    }

    @Test fun `a lowercase continuation after a period does not split`() {
        val s = LocalCleanupChunker.sentences("We met at 5 p.m. and then went home. It was late.")
        assertEquals(listOf("We met at 5 p.m. and then went home.", "It was late."), s)
    }

    // ---- paragraphs --------------------------------------------------------------------------

    @Test fun `paragraph breaks are hard boundaries and survive the re-join`() {
        val a = (1..8).joinToString(" ") { "Alpha sentence number $it is here." }
        val b = (1..8).joinToString(" ") { "Beta sentence number $it is here." }
        val chunks = split("$a\n\n$b")
        assertTrue(chunks.none { it.text.contains("Alpha") && it.text.contains("Beta") })
        val firstBeta = chunks.first { it.text.startsWith("Beta") }
        assertEquals("\n\n", firstBeta.joinBefore)
        assertTrue(LocalCleanupChunker.join(chunks, chunks.map { it.text }).contains("here.\n\nBeta"))
        assertLossless("$a\n\n$b", chunks)
    }

    // ---- sentence-less / pathological text ---------------------------------------------------

    @Test fun `unpunctuated text with commas splits at clauses`() {
        val text = (1..40).joinToString(", ") { "and then we did thing number $it" }
        val chunks = split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= max })
        assertTrue("clause cuts keep the comma with its clause", chunks.dropLast(1).all { it.text.endsWith(",") })
        assertLossless(text, chunks)
    }

    @Test fun `unpunctuated text with no commas splits at word boundaries`() {
        val text = (1..400).joinToString(" ") { "word$it" }
        val chunks = split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= max })
        assertTrue("no word is cut in half", chunks.all { c -> c.text.split(' ').all { it.matches(Regex("word\\d+")) } })
        assertLossless(text, chunks)
    }

    @Test fun `a single token longer than the limit is cut as a last resort`() {
        val url = "https://example.com/" + "a".repeat(700)
        val chunks = split(url)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.text.length <= max })
        assertEquals(url, chunks.joinToString("") { it.text })
    }

    @Test fun `a hard cut never separates a surrogate pair`() {
        val text = "😀".repeat(400) // 800 UTF-16 units, no spaces
        val chunks = split(text)
        assertTrue(chunks.all { it.text.length <= max })
        assertTrue(chunks.all { c -> c.text.length % 2 == 0 && !Character.isLowSurrogate(c.text.first()) })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test fun `a long sentence followed by short ones keeps the short ones whole`() {
        val long = (1..80).joinToString(" ") { "word$it" } + "."
        val text = "$long Short one. Another short one."
        val chunks = split(text)
        assertTrue(chunks.all { it.text.length <= max })
        assertTrue(chunks.last().text.endsWith("Another short one."))
        assertLossless(text, chunks)
    }

    @Test fun `windows line endings are paragraph breaks too`() {
        val a = (1..8).joinToString(" ") { "Alpha sentence number $it is here." }
        val b = (1..8).joinToString(" ") { "Beta sentence number $it is here." }
        val chunks = split("$a\r\n\r\n$b")
        assertEquals("\n\n", chunks.first { it.text.startsWith("Beta") }.joinBefore)
    }

    // ---- unpunctuated ASR text (Parakeet emits no periods) ----------------------------------------

    @Test fun `without punctuation the cut prefers a capitalised word, as ASR capitalises where a period belonged`() {
        val body = "a".repeat(5)
        val text = (1..12).joinToString(" ") { "$body$body words go here and there Then the next idea starts" }
        val chunks = split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= max })
        assertTrue("chunks after the first start at a capitalised word", chunks.drop(1).all { it.text.first().isUpperCase() })
        assertLossless(text, chunks)
    }

    @Test fun `a spoken number is never split across two chunks`() {
        val prefix = "word ".repeat(56) // 280 chars: the last space before 300 falls inside the number
        val text = prefix + "three hundred forty thousand dollars for the year and then some more filler words follow"
        val chunks = split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks[0].text.trimEnd().endsWith("word"))
        assertTrue(chunks[1].text.startsWith("three hundred forty thousand dollars"))
        assertLossless(text, chunks)
    }

    @Test fun `and inside a number run is not a cut point either`() {
        val prefix = "word ".repeat(56)
        val text = prefix + "four hundred and fifty dollars is what it cost us in total last week okay"
        val chunks = split(text)
        assertTrue(chunks[1].text.startsWith("four hundred and fifty dollars"))
    }

    @Test fun `a lone I is not treated as a sentence start`() {
        val text = (1..60).joinToString(" ") { "so then I said that word$it" }
        val chunks = split(text)
        assertTrue(chunks.all { it.text.length <= max })
        assertLossless(text, chunks)
    }
}
