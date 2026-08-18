package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for [LocalCleanupOutputValidator] (#155).
 *
 * The four `REAL_` fixtures below are verbatim from the on-device reproduction posted to the
 * issue: LFM2.5-350M-Q4_0, temp 0, two identical trials each, with and without a 22-term personal
 * vocabulary clause interpolated into the system prompt. They are the reason this validator
 * exists, so they are asserted as fixtures rather than paraphrased.
 *
 * The negative controls matter at least as much as the rejections: a false rejection only costs
 * one fallback step, but a validator that rejects good output makes local cleanup useless. Every
 * `is not rejected` test below is a shipped-behaviour guarantee, not a nicety.
 *
 * Mutation-proving: relax any threshold or delete any branch in the validator and the specific
 * tests named in that branch's comment must fail. Evidence recorded in the #155 PR description.
 */
class LocalCleanupOutputValidatorTest {

    // The prompt actually sent in the failing condition: SIMPLE_PROMPT with a 22-term clause.
    private val vocabularyPrompt = LocalCleanupProvider.systemPromptFor(
        LOCAL_CLEANUP_MODEL,
        listOf(
            "Nash-Keller", "Wyatt", "Terelle", "Ramblr", "FastHTML", "Selby", "Mobridge",
            "Hermes", "Trinity", "sherpa-onnx", "llama.cpp",
        ),
    )

    private val plainPrompt = LocalCleanupProvider.systemPromptFor(LOCAL_CLEANUP_MODEL, emptyList())

    private fun assertRejected(
        expected: LocalCleanupValidation.Reason,
        input: String,
        output: String,
        prompt: String = vocabularyPrompt,
    ) {
        val verdict = LocalCleanupOutputValidator.validate(input, prompt, output)
        assertTrue(
            "expected $expected but output was accepted: \"$output\"",
            verdict is LocalCleanupValidation.Rejected,
        )
        assertEquals(expected, (verdict as LocalCleanupValidation.Rejected).reason)
        assertTrue("a rejection must carry a diagnostic detail", verdict.detail.isNotBlank())
    }

    private fun assertAccepted(input: String, output: String, prompt: String = plainPrompt) {
        val verdict = LocalCleanupOutputValidator.validate(input, prompt, output)
        assertTrue(
            "legitimate cleanup was rejected as ${(verdict as? LocalCleanupValidation.Rejected)?.reason}" +
                " (${(verdict as? LocalCleanupValidation.Rejected)?.detail}): \"$output\"",
            verdict is LocalCleanupValidation.Valid,
        )
    }

    // --- the four real on-device failures ----------------------------------------------------

    @Test fun `REAL wrong dollar amount from the vocabulary condition is rejected`() {
        // "Send $450 ..." without the clause (correct) vs "Send $150 ..." with it. A validator
        // that only checked for missing numbers would pass this: 150 IS a number, in a
        // plausible-looking sentence. The value changed, which is the whole point.
        assertRejected(
            LocalCleanupValidation.Reason.NUMERIC_DIVERGENCE,
            "Send four hundred and fifty dollars to the account ending in nine three seven two",
            "Send $150 to the account ending in ninethreeseventwo",
        )
    }

    @Test fun `REAL deleted house number is rejected`() {
        // "My address is four one two Selby Road" -> "Selby Road". Caught by the numeric check
        // (412 vanished) before length collapse even gets a look; both would fire.
        assertRejected(
            LocalCleanupValidation.Reason.NUMERIC_DIVERGENCE,
            "My address is four one two Selby Road",
            "Selby Road",
        )
    }

    @Test fun `REAL system prompt echo is rejected`() {
        // The worst failure of the three: this injects the user's own private vocabulary terms
        // (emails, family names) into whatever app they were dictating into.
        assertRejected(
            LocalCleanupValidation.Reason.PROMPT_ECHO,
            "Call me at five five five one two three four",
            "Watch for these project names and personal vocabulary terms, which speech-to-text " +
                "often mishears: Nash-Keller, Wyatt, Terelle, Ramblr, FastHTML, Selby, Mobridge.",
        )
    }

    @Test fun `REAL control that worked in both conditions is still accepted`() {
        // "Transfer twelve thousand five hundred dollars tomorrow" -> "Transfer $12,500" was
        // correct WITH and WITHOUT the vocabulary clause. If the validator rejects this, it is
        // rejecting the model's good days too.
        assertAccepted(
            "Transfer twelve thousand five hundred dollars tomorrow",
            "Transfer $12,500 tomorrow.",
            prompt = vocabularyPrompt,
        )
    }

    @Test fun `REAL correct output for the wrong-amount case is accepted`() {
        // The no-clause result for the same input the model got wrong above.
        assertAccepted(
            "Send four hundred and fifty dollars to the account ending in nine three seven two",
            "Send $450 to the account ending in nine three seven two.",
        )
    }

    // --- (a) prompt echo ---------------------------------------------------------------------
    // Mutation: delete checkPromptEcho, or raise PROMPT_ECHO_MIN_SPAN above the fixture overlap.

    @Test fun `a verbatim slab of the system prompt is rejected`() {
        assertRejected(
            LocalCleanupValidation.Reason.PROMPT_ECHO,
            "hello there this is a test of the dictation feature",
            PostProcessor.SIMPLE_PROMPT.take(160),
            prompt = plainPrompt,
        )
    }

    @Test fun `echo detection works for a prompt the validator has never seen`() {
        // Derived from the systemPrompt argument, not matched against known wording -- so a
        // reworded prompt, a fine-tuned model's own prompt, or a future prompt all still work.
        val invented = "Du bist ein Werkzeug zur Bereinigung von Transkripten und gibst " +
            "ausschliesslich den bereinigten Text zurueck, ohne Erklaerungen."
        assertRejected(
            LocalCleanupValidation.Reason.PROMPT_ECHO,
            "guten morgen wie geht es dir heute",
            invented,
            prompt = invented,
        )
    }

    @Test fun `the fine-tuned model's own prompt is echo-checked too`() {
        val model = LOCAL_CLEANUP_MODEL_CATALOG.first { it.localSystemPrompt != null }
        val prompt = LocalCleanupProvider.systemPromptFor(model, listOf("Ramblr"))
        assertRejected(
            LocalCleanupValidation.Reason.PROMPT_ECHO,
            "so anyway I was thinking we should ship it on Friday",
            prompt,
            prompt = prompt,
        )
    }

    @Test fun `a short incidental overlap with the prompt is not an echo`() {
        // "obvious speech-to-text errors" is a real substring of SIMPLE_PROMPT (29 normalized
        // chars) and someone can legitimately dictate it. Must stay under the threshold.
        assertAccepted(
            "we should fix the obvious speech-to-text errors in this transcript first",
            "We should fix the obvious speech-to-text errors in this transcript first.",
        )
    }

    @Test fun `words the speaker actually dictated are not treated as an echo`() {
        // Someone dictating the prompt itself should get it cleaned up, not rejected: the span
        // is explained by the input, so it contributes no evidence of regurgitation.
        val spoken = PostProcessor.SIMPLE_PROMPT.take(180)
        assertAccepted(spoken, spoken)
    }

    // --- (b) numeric divergence --------------------------------------------------------------
    // Mutation: delete checkNumericDivergence, or make wordRunCandidates return every part value.

    @Test fun `spoken numerals converting to digits is accepted, not flagged`() {
        assertAccepted("I need four hundred and fifty widgets", "I need 450 widgets.")
        assertAccepted("that will be twelve thousand five hundred", "That will be 12,500.")
        assertAccepted("call extension two five", "Call extension 25.")
    }

    @Test fun `a digit-by-digit sequence may be joined or kept apart`() {
        val input = "the code is nine three seven two"
        assertAccepted(input, "The code is 9372.")
        assertAccepted(input, "The code is nine three seven two.")
        assertAccepted(input, "The code is 9 3 7 2.")
    }

    @Test fun `a changed digit value is rejected`() {
        assertRejected(
            LocalCleanupValidation.Reason.NUMERIC_DIVERGENCE,
            "the code is nine three seven two and the balance is 450 dollars",
            "The code is 9372 and the balance is $460.",
        )
    }

    @Test fun `a vanished number is rejected`() {
        assertRejected(
            LocalCleanupValidation.Reason.NUMERIC_DIVERGENCE,
            "please transfer 4500 dollars into the joint account before Friday afternoon",
            "Please transfer money into the joint account before Friday afternoon.",
        )
    }

    @Test fun `comma grouping and currency symbols compare equal to the plain digits`() {
        assertAccepted(
            "the invoice total came to 12500 dollars this quarter",
            "The invoice total came to $12,500 this quarter.",
        )
    }

    @Test fun `digits already present in the input must survive verbatim`() {
        assertAccepted("meet me at 7 on the 15th of March", "Meet me at 7 on the 15th of March.")
    }

    @Test fun `a bare one is not guarded because it is usually an article`() {
        // "one of the things" losing its "one" to a rewrite must not be a rejection.
        assertAccepted(
            "so one of the things I wanted to mention about the release",
            "One of the things I wanted to mention about the release.",
        )
        assertAccepted(
            "so one of the things I wanted to mention about the release",
            "I wanted to mention something about the release, among other things.",
        )
    }

    @Test fun `a number with no numbers in the input is never a divergence`() {
        assertAccepted(
            "there are no numbers whatsoever in this particular sentence",
            "There are no numbers whatsoever in this particular sentence.",
        )
    }

    @Test fun `leading zeros do not create a false divergence`() {
        assertAccepted("the room is oh four two", "The room is 042.")
    }

    // --- (c) length collapse -----------------------------------------------------------------
    // Mutation: delete checkLengthCollapse, or lower LENGTH_COLLAPSE_MIN_RATIO below 0.36.

    @Test fun `content dropped to a fragment is rejected as a collapse`() {
        // No numbers involved, so this can only be caught by the length check.
        assertRejected(
            LocalCleanupValidation.Reason.LENGTH_COLLAPSE,
            "My address is the big white house on Selby Road just past the roundabout",
            "Selby Road",
        )
    }

    @Test fun `filler-word removal is not a collapse`() {
        assertAccepted(
            "um so like I was basically uh thinking that we should you know actually ship it",
            "I was thinking that we should ship it.",
        )
    }

    @Test fun `heavy but legitimate disfluency cleanup is not a collapse`() {
        assertAccepted(
            "uh um so I I I mean the the thing is um we need to uh get the release out",
            "The thing is, we need to get the release out.",
        )
    }

    @Test fun `a short utterance is exempt from the length check`() {
        // Too little signal to judge; short inputs are where ratios are noisiest.
        assertAccepted("um yeah okay sure", "Yeah.")
    }

    @Test fun `an expanded output is never a collapse`() {
        assertAccepted("gonna ship it", "I am going to ship it tomorrow morning.")
    }

    // --- ordinary cleanup, the overwhelmingly common case ------------------------------------

    @Test fun `punctuation and capitalization cleanup is accepted`() {
        assertAccepted(
            "hey can you send me the report when you get a chance thanks",
            "Hey, can you send me the report when you get a chance? Thanks.",
        )
    }

    @Test fun `identical passthrough output is accepted`() {
        val text = "The quarterly report is ready for review whenever you have a moment."
        assertAccepted(text, text)
    }

    @Test fun `output is accepted under the vocabulary-clause prompt too`() {
        // The failing condition's prompt must not by itself make good output unacceptable.
        assertAccepted(
            "hey can you send me the report when you get a chance thanks",
            "Hey, can you send me the report when you get a chance? Thanks.",
            prompt = vocabularyPrompt,
        )
    }

    @Test fun `blank output is left to the caller's own empty-response handling`() {
        assertTrue(
            LocalCleanupOutputValidator.validate("some input here", plainPrompt, "   ")
                is LocalCleanupValidation.Valid,
        )
    }

    @Test fun `the thresholds keep real headroom on both sides of the length check`() {
        // Documents the numbers the constant's kdoc cites, so a future retune has to face them.
        val collapseRatio = ratio("My address is four one two Selby Road", "Selby Road")
        val controlRatio = ratio(
            "Transfer twelve thousand five hundred dollars tomorrow",
            "Transfer $12,500",
        )
        assertTrue(
            "the rejected collapse ($collapseRatio) must sit below the threshold",
            collapseRatio < LocalCleanupOutputValidator.LENGTH_COLLAPSE_MIN_RATIO,
        )
        assertTrue(
            "the accepted control ($controlRatio) must sit above the threshold",
            controlRatio > LocalCleanupOutputValidator.LENGTH_COLLAPSE_MIN_RATIO,
        )
    }

    private fun ratio(input: String, output: String): Double =
        LocalCleanupOutputValidator.normalizeForLength(output).length.toDouble() /
            LocalCleanupOutputValidator.normalizeForLength(input).length
}
