package com.trevornk.ramblr

import com.trevornk.ramblr.tools.TranscriptionMetrics
import com.trevornk.ramblr.tools.TranscriptionMetrics.Normalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the offline scoring half of the #129 transcription benchmark. Every assertion
 * is on a real computed value (edit counts, rates, percentiles) rather than on a proxy such as
 * "the function returned something non-null" -- the micro-vs-macro test in particular exists to
 * be killed by swapping micro-aggregation for an unweighted mean.
 */
class TranscriptionMetricsTest {

    // ---------------------------------------------------------------- normalization

    @Test fun `normalized mode lowercases collapses whitespace and strips punctuation`() {
        assertEquals(
            "hello world",
            TranscriptionMetrics.normalize("  Hello,   WORLD!!  ", Normalization.NORMALIZED),
        )
    }

    @Test fun `strict mode preserves case and punctuation but collapses whitespace`() {
        assertEquals(
            "Hello, WORLD!!",
            TranscriptionMetrics.normalize("  Hello,   WORLD!!  ", Normalization.STRICT),
        )
    }

    @Test fun `curly apostrophes and unicode dashes fold to ascii in both modes`() {
        assertEquals("don't do it", TranscriptionMetrics.normalize("don\u2019t do it", Normalization.NORMALIZED))
        assertEquals("Don't", TranscriptionMetrics.normalize("Don\u2019t", Normalization.STRICT))
        // en dash, em dash, non-breaking hyphen all fold to '-'
        assertEquals("a-b-c-d", TranscriptionMetrics.normalize("a\u2013b\u2014c\u2011d", Normalization.STRICT))
    }

    @Test fun `nfkc folds compatibility forms and composes accents`() {
        // U+FB01 LATIN SMALL LIGATURE FI -> "fi"
        assertEquals("file", TranscriptionMetrics.normalize("\uFB01le", Normalization.STRICT))
        // Decomposed e + combining acute -> precomposed U+00E9
        assertEquals("caf\u00E9", TranscriptionMetrics.normalize("cafe\u0301", Normalization.STRICT))
        // Full-width digits -> ASCII digits
        assertEquals("123", TranscriptionMetrics.normalize("\uFF11\uFF12\uFF13", Normalization.STRICT))
    }

    @Test fun `normalized mode keeps intra-word apostrophes and does not merge words`() {
        assertEquals("don't stop", TranscriptionMetrics.normalize("Don\u2019t, stop.", Normalization.NORMALIZED))
    }

    // ---------------------------------------------------------------- exact match

    @Test fun `strict exact match is true only for byte-identical-after-nfkc text`() {
        assertTrue(TranscriptionMetrics.strictExactMatch("Hello, world.", "Hello, world."))
        assertFalse(TranscriptionMetrics.strictExactMatch("Hello, world.", "hello world"))
    }

    @Test fun `case-only difference fails strict but passes normalized`() {
        assertFalse(TranscriptionMetrics.strictExactMatch("Hello World", "hello world"))
        assertTrue(TranscriptionMetrics.normalizedExactMatch("Hello World", "hello world"))
        assertEquals(0.0, TranscriptionMetrics.wer("Hello World", "hello world", Normalization.NORMALIZED), 1e-9)
        assertEquals(1.0, TranscriptionMetrics.wer("Hello World", "hello world", Normalization.STRICT), 1e-9)
    }

    @Test fun `punctuation-only difference fails strict but passes normalized`() {
        assertFalse(TranscriptionMetrics.strictExactMatch("hey, there!", "hey there"))
        assertTrue(TranscriptionMetrics.normalizedExactMatch("hey, there!", "hey there"))
        assertEquals(0.0, TranscriptionMetrics.wer("hey, there!", "hey there", Normalization.NORMALIZED), 1e-9)
        // Strict keeps the punctuation attached to the tokens, so both words are substitutions.
        assertEquals(1.0, TranscriptionMetrics.wer("hey, there!", "hey there", Normalization.STRICT), 1e-9)
    }

    // ---------------------------------------------------------------- word edit distance

    @Test fun `identical text has zero edits of every kind`() {
        val edits = TranscriptionMetrics.wordEdits("the quick brown fox", "the quick brown fox")
        assertEquals(0, edits.substitutions)
        assertEquals(0, edits.deletions)
        assertEquals(0, edits.insertions)
        assertEquals(0, edits.total)
        assertEquals(4, edits.referenceLength)
        assertEquals(0.0, TranscriptionMetrics.wer("the quick brown fox", "the quick brown fox"), 1e-9)
    }

    @Test fun `single substitution is counted as exactly one substitution`() {
        val edits = TranscriptionMetrics.wordEdits("the quick brown fox", "the quick green fox")
        assertEquals(1, edits.substitutions)
        assertEquals(0, edits.deletions)
        assertEquals(0, edits.insertions)
        assertEquals(4, edits.referenceLength)
        assertEquals(0.25, TranscriptionMetrics.wer("the quick brown fox", "the quick green fox"), 1e-9)
    }

    @Test fun `single deletion is counted as exactly one deletion`() {
        val edits = TranscriptionMetrics.wordEdits("the quick brown fox", "the quick fox")
        assertEquals(0, edits.substitutions)
        assertEquals(1, edits.deletions)
        assertEquals(0, edits.insertions)
        assertEquals(0.25, TranscriptionMetrics.wer("the quick brown fox", "the quick fox"), 1e-9)
    }

    @Test fun `single insertion is counted as exactly one insertion`() {
        val edits = TranscriptionMetrics.wordEdits("the quick brown fox", "the very quick brown fox")
        assertEquals(0, edits.substitutions)
        assertEquals(0, edits.deletions)
        assertEquals(1, edits.insertions)
        assertEquals(0.25, TranscriptionMetrics.wer("the quick brown fox", "the very quick brown fox"), 1e-9)
    }

    @Test fun `mixed edits are decomposed into each category`() {
        // ref: a b c d   hyp: a x c d e  -> 1 substitution (b->x), 1 insertion (e)
        val edits = TranscriptionMetrics.wordEdits("a b c d", "a x c d e")
        assertEquals(1, edits.substitutions)
        assertEquals(0, edits.deletions)
        assertEquals(1, edits.insertions)
        assertEquals(2, edits.total)
        assertEquals(0.5, TranscriptionMetrics.wer("a b c d", "a x c d e"), 1e-9)
    }

    @Test fun `wer above one is possible when the hypothesis rambles`() {
        // ref is 1 word, hyp is 7 words -> 6 insertions over a 1-word reference = 6.0
        val edits = TranscriptionMetrics.wordEdits("hi", "hi there my friend how are you")
        assertEquals(6, edits.insertions)
        assertEquals(1, edits.referenceLength)
        assertEquals(6.0, TranscriptionMetrics.wer("hi", "hi there my friend how are you"), 1e-9)
    }

    // ---------------------------------------------------------------- char edit distance

    @Test fun `character edits count per-character substitutions insertions and deletions`() {
        val edits = TranscriptionMetrics.charEdits("kitten", "sitting")
        assertEquals(2, edits.substitutions)
        assertEquals(0, edits.deletions)
        assertEquals(1, edits.insertions)
        assertEquals(3, edits.total)
        assertEquals(6, edits.referenceLength)
        assertEquals(3.0 / 6.0, TranscriptionMetrics.cer("kitten", "sitting"), 1e-9)
    }

    @Test fun `cer is zero for identical strings and counts whitespace-normalized characters`() {
        assertEquals(0.0, TranscriptionMetrics.cer("hello world", "hello   world"), 1e-9)
    }

    // ---------------------------------------------------------------- empty reference

    @Test fun `empty reference and empty hypothesis score zero`() {
        assertEquals(0.0, TranscriptionMetrics.wer("", ""), 1e-9)
        assertEquals(0.0, TranscriptionMetrics.cer("", ""), 1e-9)
        assertEquals(0, TranscriptionMetrics.wordEdits("", "").total)
        assertTrue(TranscriptionMetrics.normalizedExactMatch("", "   "))
    }

    @Test fun `empty reference with non-empty hypothesis records insertions and scores one`() {
        val edits = TranscriptionMetrics.wordEdits("", "spurious words here")
        assertEquals(3, edits.insertions)
        assertEquals(0, edits.substitutions)
        assertEquals(0, edits.deletions)
        assertEquals(0, edits.referenceLength)
        // Rate is undefined mathematically (division by zero); the contract pins it at 1.0.
        assertEquals(1.0, TranscriptionMetrics.wer("", "spurious words here"), 1e-9)
        assertEquals(1.0, TranscriptionMetrics.cer("", "abc"), 1e-9)
    }

    @Test fun `non-empty reference with empty hypothesis is all deletions`() {
        val edits = TranscriptionMetrics.wordEdits("one two three", "")
        assertEquals(3, edits.deletions)
        assertEquals(0, edits.insertions)
        assertEquals(1.0, TranscriptionMetrics.wer("one two three", ""), 1e-9)
    }

    // ---------------------------------------------------------------- micro vs macro

    /**
     * The load-bearing test for the aggregation contract. The corpus is deliberately unbalanced:
     * one 1-word clip that is entirely wrong (per-clip WER 1.0) and one 99-word clip with a single
     * error (per-clip WER ~0.0101). An unweighted mean of the per-clip rates gives ~0.505; the
     * correct micro aggregation (sum edits / sum reference words) gives 2/100 = 0.02. Replacing
     * micro with a mean must break this test.
     */
    @Test fun `micro aggregation weights by reference length and differs from the macro mean`() {
        val longRef = (1..99).joinToString(" ") { "word$it" }
        val longHyp = (1..99).joinToString(" ") { if (it == 50) "WRONGTOKEN" else "word$it" }
        val corpus = listOf(
            TranscriptionMetrics.score("alpha", "beta"),
            TranscriptionMetrics.score(longRef, longHyp),
        )

        val agg = TranscriptionMetrics.aggregate(corpus)

        assertEquals(2, agg.clipCount)
        assertEquals(100, agg.totalReferenceWords)
        assertEquals(2, agg.totalWordEdits)
        assertEquals(0.02, agg.microWer, 1e-9)
        assertEquals((1.0 + 1.0 / 99.0) / 2.0, agg.macroWer, 1e-9)
        // The whole point: the two aggregations must not coincide on this corpus.
        assertNotEquals(agg.macroWer, agg.microWer, 1e-6)
        assertTrue("macro must be far larger here, got ${agg.macroWer} vs ${agg.microWer}", agg.macroWer > agg.microWer * 10)
    }

    @Test fun `micro cer is a character-weighted ratio not a mean of per-clip cers`() {
        val corpus = listOf(
            TranscriptionMetrics.score("ab", "xy"),          // 2 char edits over 2 ref chars
            TranscriptionMetrics.score("a".repeat(98), "a".repeat(98)), // 0 edits over 98 ref chars
        )
        val agg = TranscriptionMetrics.aggregate(corpus)
        assertEquals(100, agg.totalReferenceChars)
        assertEquals(2, agg.totalCharEdits)
        assertEquals(0.02, agg.microCer, 1e-9)
        assertEquals(0.5, agg.macroCer, 1e-9)
        assertNotEquals(agg.macroCer, agg.microCer, 1e-6)
    }

    @Test fun `aggregate reports strict and normalized exact match rates separately`() {
        val corpus = listOf(
            TranscriptionMetrics.score("Hello world", "Hello world"),   // strict + normalized
            TranscriptionMetrics.score("Hello world", "hello, world!"), // normalized only
            TranscriptionMetrics.score("Hello world", "goodbye world"), // neither
            TranscriptionMetrics.score("Hello world", "totally different"),
        )
        val agg = TranscriptionMetrics.aggregate(corpus)
        assertEquals(0.25, agg.strictExactMatchRate, 1e-9)
        assertEquals(0.5, agg.normalizedExactMatchRate, 1e-9)
    }

    @Test fun `aggregating an empty corpus yields zeroed stats rather than NaN`() {
        val agg = TranscriptionMetrics.aggregate(emptyList())
        assertEquals(0, agg.clipCount)
        assertEquals(0.0, agg.microWer, 1e-9)
        assertEquals(0.0, agg.macroWer, 1e-9)
        assertEquals(0.0, agg.strictExactMatchRate, 1e-9)
    }

    // ---------------------------------------------------------------- latency percentiles

    @Test fun `percentiles use nearest rank on the sorted sample`() {
        val values = listOf(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L)
        val stats = TranscriptionMetrics.latencyStats(values)
        assertEquals(550.0, stats.meanMs!!, 1e-9)
        // nearest-rank: ceil(0.50 * 10) = 5 -> 5th smallest = 500
        assertEquals(500L, stats.p50Ms)
        // nearest-rank: ceil(0.95 * 10) = 10 -> 10th smallest = 1000
        assertEquals(1000L, stats.p95Ms)
        assertEquals(100L, stats.minMs)
        assertEquals(1000L, stats.maxMs)
        assertEquals(10, stats.count)
    }

    @Test fun `percentiles are order independent`() {
        val shuffled = listOf(900L, 100L, 500L, 300L, 700L)
        val stats = TranscriptionMetrics.latencyStats(shuffled)
        assertEquals(500L, stats.p50Ms) // ceil(0.5*5)=3 -> 3rd smallest of 100,300,500,700,900
        assertEquals(900L, stats.p95Ms) // ceil(0.95*5)=5 -> 5th smallest
        assertEquals(500.0, stats.meanMs!!, 1e-9)
    }

    @Test fun `single sample percentiles equal that sample`() {
        val stats = TranscriptionMetrics.latencyStats(listOf(42L))
        assertEquals(42L, stats.p50Ms)
        assertEquals(42L, stats.p95Ms)
        assertEquals(42.0, stats.meanMs!!, 1e-9)
    }

    @Test fun `empty latency sample is reported as absent rather than zero`() {
        val stats = TranscriptionMetrics.latencyStats(emptyList())
        assertEquals(0, stats.count)
        assertEquals(null, stats.p50Ms)
        assertEquals(null, stats.p95Ms)
        assertEquals(null, stats.meanMs)
    }
}
