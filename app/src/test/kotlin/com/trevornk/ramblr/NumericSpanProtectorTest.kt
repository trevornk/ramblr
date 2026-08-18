package com.trevornk.ramblr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for [NumericSpanProtector] and [protectedLocalCleanupOutcome] (#155).
 *
 * The three `ISSUE_` fixtures are verbatim from the issue body's measured failure list -- the
 * exact utterances both local models corrupt today. They are the reason this class exists, so
 * they are asserted as fixtures rather than paraphrased.
 *
 * The `EVAL_` tests run the checked-in reference transcripts in
 * `app/src/test/resources/eval_samples/` (real ASR output; 12 of 23 contain spelled-out numbers)
 * through a full mask -> identity-model -> restore round trip and assert the text comes back
 * byte-identical. Those are the strongest negative control available: nothing in the corpus,
 * numeric or not, may be perturbed by this feature.
 *
 * Assertions are on parsed structures ([NumericMasking.spans], [NumericSpanProtector.detectSpans],
 * [ProtectedCleanupOutcome]), never on substring checks against a masked string.
 *
 * Mutation-proving: disable masking, disable restoration, or relax the unmatched-sentinel policy
 * and the specific tests named in each section's comment must fail. Evidence recorded in the #155
 * PR description.
 */
class NumericSpanProtectorTest {

    private val plainPrompt: String =
        LocalCleanupProvider.systemPromptFor(LOCAL_CLEANUP_MODEL, emptyList())

    /** The original substrings the protector lifted out, in order. */
    private fun originals(text: String): List<String> =
        NumericSpanProtector.mask(text).spans.map { it.original }

    /** A model that returns the masked text unchanged -- the ideal round trip. */
    private fun roundTrip(text: String): NumericRestoration {
        val masking = NumericSpanProtector.mask(text)
        return NumericSpanProtector.restore(masking, masking.maskedText)
    }

    private fun restoredText(restoration: NumericRestoration): String {
        assertTrue(
            "expected a successful restoration but got $restoration",
            restoration is NumericRestoration.Restored,
        )
        return (restoration as NumericRestoration.Restored).text
    }

    // --- the three verbatim failures from the issue body --------------------------------------
    // Mutation: make detectSpans return emptyList(), or drop any of the decimal / percent /
    // digit-run branches, and these three fail.

    @Test fun `ISSUE decimal million with currency is protected as one span`() {
        // "one point two million dollars" -> "$1200M" today: a 1000x overstatement.
        val text = "the round was one point two million dollars in total"
        assertEquals(listOf("one point two million dollars"), originals(text))
        assertEquals(text, restoredText(roundTrip(text)))
    }

    @Test fun `ISSUE spoken percentage is protected as one span`() {
        // "we saw like a twenty three percent increase" -> "We saw a 203% increase" today.
        val text = "we saw like a twenty three percent increase"
        assertEquals(listOf("twenty three percent"), originals(text))
        assertEquals(text, restoredText(roundTrip(text)))
    }

    @Test fun `ISSUE spoken phone number is protected as one span`() {
        // "five five five one two three four five six seven" -> "551234567" today: a dropped digit.
        val text = "call me at five five five one two three four five six seven"
        assertEquals(listOf("five five five one two three four five six seven"), originals(text))
        assertEquals(text, restoredText(roundTrip(text)))
    }

    // --- detection coverage -------------------------------------------------------------------
    // Mutation: remove an entry from NUMBER_WORDS / ORDINAL_WORDS / TRAILING_WORDS, or make
    // classify() ignore digits, and the matching case below fails.

    @Test fun `digit runs currency and percentages are detected`() {
        assertEquals(listOf("2026"), originals("ship it in 2026"))
        assertEquals(listOf("\$12,500"), originals("transfer \$12,500 tomorrow"))
        assertEquals(listOf("50%"), originals("margin is 50% now"))
        assertEquals(listOf("3.14"), originals("pi is about 3.14 roughly"))
    }

    @Test fun `times dates and ordinals are detected`() {
        assertEquals(listOf("3:30"), originals("meet at 3:30 downstairs"))
        assertEquals(listOf("12/03/2026"), originals("due 12/03/2026 latest"))
        assertEquals(listOf("August eighteenth"), originals("due August eighteenth latest"))
        assertEquals(listOf("Aug 18, 2026"), originals("due Aug 18, 2026 latest"))
        assertEquals(listOf("1st"), originals("the 1st attempt failed"))
        assertEquals(listOf("twenty first"), originals("the twenty first attempt failed"))
        assertEquals(listOf("four thirty"), originals("actually at four thirty instead"))
    }

    @Test fun `hyphenated and connector-joined cardinals stay one span`() {
        assertEquals(listOf("twenty-three"), originals("about twenty-three people came"))
        assertEquals(
            listOf("four hundred and fifty dollars"),
            originals("send four hundred and fifty dollars over"),
        )
        assertEquals(listOf("five oh five"), originals("area code five oh five here"))
    }

    @Test fun `a trailing connector is not swallowed into the span`() {
        // "and" only continues a span when a number follows it; "point" likewise.
        assertEquals(listOf("three"), originals("we need three and then some rest"))
        assertEquals(listOf("two"), originals("that is my two point exactly here"))
    }

    @Test fun `surrounding punctuation stays outside the span so the model can repunctuate`() {
        assertEquals(listOf("twenty three percent"), originals("it rose, twenty three percent."))
    }

    @Test fun `detected spans are ordered and non-overlapping`() {
        val text = "first item rose twenty three percent then cost four hundred dollars later"
        val spans = NumericSpanProtector.detectSpans(text)
        assertEquals(3, spans.size)
        spans.zipWithNext().forEach { (a, b) ->
            assertTrue("spans must be ordered and non-overlapping: $a then $b", a.last <= b.first)
        }
        assertEquals(
            listOf("first", "twenty three percent", "four hundred dollars"),
            spans.map { text.substring(it.first, it.last) },
        )
    }

    // --- sentinels ----------------------------------------------------------------------------
    // Mutation: change sentinelFor to emit digits or punctuation and these fail.

    @Test fun `sentinels are letters only and contain no digits or markup`() {
        val text = "call five five five one two three four and send \$12,500 by 3:30"
        val masking = NumericSpanProtector.mask(text)
        assertEquals(3, masking.spans.size)
        masking.spans.forEach { span ->
            assertTrue(
                "sentinel must be prefix + letters only: ${span.sentinel}",
                span.sentinel.matches(Regex("ZQX[A-Z]+")),
            )
        }
        assertFalse(
            "masked text must contain no digits at all",
            masking.maskedText.any { it.isDigit() },
        )
        assertEquals(
            "sentinels must be distinct",
            masking.spans.size,
            masking.spans.map { it.sentinel }.toSet().size,
        )
    }

    @Test fun `sentinel indices are bijective base 26`() {
        assertEquals("ZQXA", NumericSpanProtector.sentinelFor(0))
        assertEquals("ZQXZ", NumericSpanProtector.sentinelFor(25))
        assertEquals("ZQXAA", NumericSpanProtector.sentinelFor(26))
        assertEquals("ZQXAB", NumericSpanProtector.sentinelFor(27))
        val generated = (0 until 60).map { NumericSpanProtector.sentinelFor(it) }
        assertEquals("every index must yield a distinct sentinel", 60, generated.toSet().size)
    }

    @Test fun `a preexisting sentinel-shaped word cannot be substituted as a number`() {
        val text = "the ZQXA part number needs twenty three units"
        val masking = NumericSpanProtector.mask(text)
        assertEquals(listOf("twenty three"), masking.spans.map { it.original })
        assertEquals("ZQXB", masking.spans.single().sentinel)
        assertTrue(
            NumericSpanProtector.restore(masking, masking.maskedText) is NumericRestoration.Failed,
        )
    }

    // --- restoration and the degradation policy -----------------------------------------------
    // Mutation: make restore() return the model output unchanged, or return Restored on a
    // missing/unknown sentinel, and these fail.

    @Test fun `restoration is verbatim through a realistic cleanup of the surrounding prose`() {
        val text = "so um we saw like a twenty three percent increase i think"
        val masking = NumericSpanProtector.mask(text)
        val sentinel = masking.spans.single().sentinel
        val modelOutput = "We saw a $sentinel increase."
        assertEquals("We saw a twenty three percent increase.", restoredText(NumericSpanProtector.restore(masking, modelOutput)))
    }

    @Test fun `a lowercased sentinel still resolves`() {
        val masking = NumericSpanProtector.mask("send four hundred dollars today")
        val restoration = NumericSpanProtector.restore(masking, "Send zqxa today.")
        assertEquals("Send four hundred dollars today.", restoredText(restoration))
    }

    @Test fun `a duplicated sentinel restores the span twice rather than failing`() {
        val masking = NumericSpanProtector.mask("send four hundred dollars today")
        val sentinel = masking.spans.single().sentinel
        val restoration = NumericSpanProtector.restore(masking, "Send $sentinel, yes $sentinel, today.")
        assertEquals(
            "Send four hundred dollars, yes four hundred dollars, today.",
            restoredText(restoration),
        )
    }

    @Test fun `a sentinel glued to trailing letters resolves by longest valid prefix`() {
        val masking = NumericSpanProtector.mask("send four hundred dollars today")
        val sentinel = masking.spans.single().sentinel
        val restoration = NumericSpanProtector.restore(masking, "Send ${sentinel}ish today.")
        assertEquals("Send four hundred dollarsish today.", restoredText(restoration))
    }

    @Test fun `a dropped sentinel fails restoration instead of guessing a position`() {
        val masking = NumericSpanProtector.mask("send four hundred dollars to the account today")
        val restoration = NumericSpanProtector.restore(masking, "Send to the account today.")
        assertTrue(
            "a dropped number must fail the cleanup, not be re-inserted at a guess",
            restoration is NumericRestoration.Failed,
        )
        assertTrue((restoration as NumericRestoration.Failed).reason.isNotBlank())
    }

    @Test fun `an unknown sentinel fails restoration`() {
        val masking = NumericSpanProtector.mask("send four hundred dollars today")
        val restoration = NumericSpanProtector.restore(masking, "Send ZQXQQQ today.")
        assertTrue(
            "a hallucinated sentinel has no correct substitution",
            restoration is NumericRestoration.Failed,
        )
    }

    @Test fun `restoration of a partially dropped multi-span output fails`() {
        val masking = NumericSpanProtector.mask("call five five five one two three four about the \$12,500")
        assertEquals(2, masking.spans.size)
        val restoration = NumericSpanProtector.restore(
            masking,
            "Call ${masking.spans[0].sentinel} about it.",
        )
        assertTrue(restoration is NumericRestoration.Failed)
    }

    @Test fun `restoration never throws on adversarial model output`() {
        val masking = NumericSpanProtector.mask("send four hundred dollars today")
        val hostile = listOf("", "   ", "ZQX", "zqx zqxzzzzz ZQXA", "ZQXA".repeat(50), "<ZQXA/>")
        hostile.forEach { output ->
            // The assertion is that this returns a verdict at all rather than throwing.
            assertNotNull("restore must not throw on: \"$output\"", NumericSpanProtector.restore(masking, output))
        }
    }

    // --- negative controls: no numbers means no change ----------------------------------------
    // Mutation: make mask() always allocate a masking, or make restore() rewrite when spans are
    // empty, and these fail.

    @Test fun `text with no numbers is masked to the identical instance`() {
        val text = "text sarah back about dinner because the pasta thing needs olive oil"
        val masking = NumericSpanProtector.mask(text)
        assertTrue(masking.isEmpty)
        assertSame("no-number text must not even be rebuilt", text, masking.maskedText)
    }

    @Test fun `restore is a pass-through when nothing was masked`() {
        val masking = NumericSpanProtector.mask("where is paris")
        val cleaned = "Where is Paris?"
        assertEquals(cleaned, restoredText(NumericSpanProtector.restore(masking, cleaned)))
    }

    // --- real reference transcripts -----------------------------------------------------------

    private fun evalSamples(): List<Pair<String, String>> {
        val dir = File("src/test/resources/eval_samples")
            .takeIf { it.isDirectory }
            ?: File("app/src/test/resources/eval_samples")
        assertTrue("eval_samples fixtures not found at ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f: File -> f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()
        assertTrue("expected the checked-in reference transcripts", files.size >= 20)
        return files.map { it.name to it.readText() }
    }

    @Test fun `EVAL every reference transcript survives a mask and restore round trip byte-identically`() {
        evalSamples().forEach { (name, text) ->
            assertEquals("round trip changed $name", text, restoredText(roundTrip(text)))
        }
    }

    @Test fun `EVAL number-free reference transcripts are not masked at all`() {
        // Chosen because they contain no numeral, ordinal or digit at all; if the detector starts
        // firing on ordinary prose it shows up here first.
        val numberFree = setOf(
            "edge_very_short_01.txt",
            "quick_note_04.txt",
            "rambling_brainstorm_04.txt",
        )
        val checked = evalSamples().filter { (name, _) -> name in numberFree }
        assertEquals("fixture names drifted", numberFree.size, checked.size)
        checked.forEach { (name, text) ->
            val masking = NumericSpanProtector.mask(text)
            assertTrue("$name should have no numeric span but got ${masking.spans}", masking.isEmpty)
            assertSame(text, masking.maskedText)
        }
    }

    @Test fun `EVAL number-bearing reference transcripts do produce spans`() {
        val expected = mapOf(
            "quick_note_02.txt" to listOf("five"),
            "quick_note_03.txt" to listOf("seven", "six"),
            "self_correction_02.txt" to listOf("three o'clock", "four thirty", "second", "one"),
            "self_correction_04.txt" to listOf("twenty dollars", "twenty five"),
            "rambling_brainstorm_03.txt" to listOf("six hundred", "ten", "ten"),
        )
        val samples = evalSamples().toMap()
        expected.forEach { (name, spans) ->
            val text = samples[name] ?: error("missing fixture $name")
            assertEquals("spans in $name", spans, NumericSpanProtector.mask(text).spans.map { it.original })
        }
    }

    // --- interaction with LocalCleanupOutputValidator ------------------------------------------
    // Mutation: validate the MASKED input/output instead of the restored pair inside
    // protectedLocalCleanupOutcome and these fail.

    @Test fun `restored output is validated against the original transcript and accepted`() {
        val raw = "so um we saw like a twenty three percent increase i think this quarter"
        val masking = NumericSpanProtector.mask(raw)
        val modelOutput = "We saw a ${masking.spans.single().sentinel} increase this quarter."
        val outcome = protectedLocalCleanupOutcome(raw, plainPrompt, masking, modelOutput)
        assertEquals(
            ProtectedCleanupOutcome.Accepted("We saw a twenty three percent increase this quarter."),
            outcome,
        )
    }

    @Test fun `masking does not break the LENGTH_COLLAPSE check on a real collapse`() {
        // The pre-#155 failure: "My address is four one two Selby Road" -> "Selby Road". Because
        // restoration happens BEFORE validation, the validator still sees the full original
        // transcript and the full restored output, so LENGTH_COLLAPSE still fires. Validating
        // masked-vs-masked would have hidden this.
        val raw = "My address is four one two Selby Road near the old post office"
        val masking = NumericSpanProtector.mask(raw)
        val outcome = protectedLocalCleanupOutcome(raw, plainPrompt, masking, masking.spans.single().sentinel)
        assertTrue("a real collapse must still be rejected", outcome is ProtectedCleanupOutcome.Rejected)
        assertEquals(
            LocalCleanupValidation.Reason.LENGTH_COLLAPSE.name,
            (outcome as ProtectedCleanupOutcome.Rejected).label,
        )
    }

    @Test fun `the length ratio the validator sees is computed on restored not masked text`() {
        // Directly asserts the ratio, so a future refactor that validates masked text is caught
        // by an arithmetic fact rather than by a message string.
        val raw = "transfer twelve thousand five hundred dollars tomorrow to the savings account"
        val masking = NumericSpanProtector.mask(raw)
        val restored = restoredText(NumericSpanProtector.restore(masking, masking.maskedText))
        val maskedRatio = ratio(raw, masking.maskedText)
        val restoredRatio = ratio(raw, restored)
        assertEquals("a full round trip is length-neutral", 1.0, restoredRatio, 1e-9)
        assertTrue(
            "masked text is materially shorter, which is why it must not be what is validated " +
                "(masked ratio $maskedRatio)",
            maskedRatio < restoredRatio,
        )
        assertEquals(
            ProtectedCleanupOutcome.Accepted(restored),
            protectedLocalCleanupOutcome(raw, plainPrompt, masking, masking.maskedText),
        )
    }

    private fun ratio(input: String, output: String): Double =
        LocalCleanupOutputValidator.normalizeForLength(output).length.toDouble() /
            LocalCleanupOutputValidator.normalizeForLength(input).length

    @Test fun `a failed restoration is reported as a rejection so the waterfall falls through`() {
        val raw = "send four hundred and fifty dollars to the account ending in nine three seven two"
        val masking = NumericSpanProtector.mask(raw)
        val outcome = protectedLocalCleanupOutcome(raw, plainPrompt, masking, "Send to the account.")
        assertTrue(outcome is ProtectedCleanupOutcome.Rejected)
        assertEquals(
            ProtectedCleanupOutcome.Rejected.NUMERIC_RESTORATION,
            (outcome as ProtectedCleanupOutcome.Rejected).label,
        )
        assertTrue("a rejection must carry a diagnostic detail", outcome.detail.isNotBlank())
    }

    @Test fun `NEGATIVE CONTROL correct cleanup of number-free text is still accepted unchanged`() {
        val raw = "so i keep going back and forth on whether the clipboard fallback should be silent"
        val masking = NumericSpanProtector.mask(raw)
        val cleaned = "I keep going back and forth on whether the clipboard fallback should be silent."
        assertEquals(
            ProtectedCleanupOutcome.Accepted(cleaned),
            protectedLocalCleanupOutcome(raw, plainPrompt, masking, cleaned),
        )
    }

    @Test fun `NEGATIVE CONTROL prompt echo is still caught on the protected path`() {
        val raw = "call me at five five five one two three four"
        val masking = NumericSpanProtector.mask(raw)
        val outcome = protectedLocalCleanupOutcome(raw, plainPrompt, masking, plainPrompt)
        assertTrue(outcome is ProtectedCleanupOutcome.Rejected)
    }
}
