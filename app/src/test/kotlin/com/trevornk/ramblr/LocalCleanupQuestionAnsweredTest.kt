package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the QUESTION_ANSWERED validator check (#189).
 *
 * Every "rejects" case below is a VERBATIM input/output pair measured on-host at temp 0 against the
 * shipped LFM2.5-350M-Q4_0 with the production system prompt -- not invented for the test. The
 * accept cases are the false-positive risks: this check sits in front of every local cleanup, so
 * wrongly rejecting normal speech would push users to the fallback path constantly.
 *
 * Context for why the check exists at all rather than a prompt instruction: adding "do not answer
 * questions" to SIMPLE_PROMPT was measured and did NOT fix it. See the KDoc on
 * `LocalCleanupOutputValidator.checkQuestionAnswered`.
 */
class LocalCleanupQuestionAnsweredTest {

    private val prompt = PostProcessor.SIMPLE_PROMPT

    private fun validate(input: String, output: String) =
        LocalCleanupOutputValidator.validate(input, prompt, output)

    private fun assertRejectedAsAnswer(input: String, output: String) {
        val verdict = validate(input, output)
        assertTrue(
            "expected QUESTION_ANSWERED rejection for input=<$input> output=<$output>, got $verdict",
            verdict is LocalCleanupValidation.Rejected &&
                verdict.reason == LocalCleanupValidation.Reason.QUESTION_ANSWERED,
        )
    }

    private fun assertAccepted(input: String, output: String) {
        val verdict = validate(input, output)
        assertTrue(
            "expected Valid for input=<$input> output=<$output>, got $verdict",
            verdict is LocalCleanupValidation.Valid,
        )
    }

    // --- measured failures that must be rejected -------------------------------------------

    @Test fun `rejects the measured capital-of-france answer`() {
        assertRejectedAsAnswer(
            "can you tell me what the capital of france is",
            "Sure! The capital of France is Paris.",
        )
    }

    @Test fun `rejects the measured invented store-hours answer`() {
        // The worst case: no store, no 5 PM. The model fabricated both and the app would have
        // typed them into the user's text field.
        assertRejectedAsAnswer(
            "hey what time does the store close on sunday",
            "The store closes at 5:00 PM on Sunday.",
        )
    }

    @Test fun `rejects the measured mutex-semaphore explanation`() {
        assertRejectedAsAnswer(
            "whats the difference between a mutex and a semaphore",
            "The difference between a mutex and a semaphore is that a mutex is used to protect a " +
                "shared resource from concurrent access, while a semaphore is used to control " +
                "access to a limited number of resources.",
        )
    }

    @Test fun `rejects the measured first-president answer`() {
        assertRejectedAsAnswer(
            "who was the first president of the united states",
            "The first president of the United States was George Washington.",
        )
    }

    @Test fun `rejects a dictated polite request the model complied with`() {
        // Distinct sub-mode found during end-to-end verification: the transcript is a request
        // ("can you send me the report..."), and the model ACTS on it rather than answering a
        // factual question. Measured output below. Same defect, same rejection.
        assertRejectedAsAnswer(
            "can you send me the report when you get a chance",
            "Sure, here is the report when you get a chance.",
        )
    }

    // --- legitimate cleanups that must NOT be rejected --------------------------------------

    @Test fun `accepts a dictated question cleaned into a question`() {
        // Measured: this is what correct behaviour looks like, and it must survive untouched.
        assertAccepted(
            "um so what do you think we should do about the deploy",
            "um so what do you think we should do about the deploy?",
        )
    }

    @Test fun `accepts a question cleaned up with punctuation and capitalization`() {
        assertAccepted(
            "can you tell me what the capital of france is",
            "Can you tell me what the capital of France is?",
        )
    }

    @Test fun `accepts a non-question transcript that happens to contain a wh-word`() {
        // "what" appears mid-sentence; this is a statement, and the opener test must not fire.
        assertAccepted(
            "the thing is what he said yesterday didnt match the numbers",
            "The thing is, what he said yesterday didn't match the numbers.",
        )
    }

    @Test fun `accepts an ordinary declarative transcript`() {
        assertAccepted(
            "so i talked to sarah yesterday and she said the budget for q3 is around fifty thousand",
            "So I talked to Sarah yesterday, and she said the budget for Q3 is around fifty thousand.",
        )
    }

    @Test fun `accepts a bare command transcript`() {
        // The #175-era prompt-injection guard case: "continue" is an imperative, not a question,
        // so this check must stay out of its way entirely.
        assertAccepted("continue", "continue")
    }

    @Test fun `accepts a rhetorical question kept as a question`() {
        assertAccepted(
            "why do we even keep the legacy endpoint around",
            "Why do we even keep the legacy endpoint around?",
        )
    }

    @Test fun `accepts a question rewritten as a statement using only the speakers words`() {
        // A declarative paraphrase that adds no new content is someone else's failure mode
        // (LENGTH_* / NUMERIC_*), not an answer -- the novel-word condition spares it.
        assertAccepted(
            "is the deploy done yet",
            "The deploy is done yet.",
        )
    }

    // --- interaction with the surrounding machinery -----------------------------------------

    /**
     * Transcripts that OPEN with an interrogative word but are not questions. Condition 1 fires on
     * all of these, so they are held back purely by conditions 2 and 3 -- exactly the false
     * positives that would make local cleanup feel broken, since each would otherwise be discarded
     * and fall back to raw text.
     */
    @Test fun `does not reject declaratives that merely open with an interrogative word`() {
        val cases = listOf(
            "do not forget to lock the back door before you leave tonight" to
                "Do not forget to lock the back door before you leave tonight.",
            "had a really long day today and im exhausted" to
                "Had a really long day today and I'm exhausted.",
            "will call you back after the standup" to
                "Will call you back after the standup.",
            "what a mess that meeting turned into honestly" to
                "What a mess that meeting turned into, honestly.",
            "can you believe how expensive groceries got this year i mean seriously" to
                "Can you believe how expensive groceries got this year? I mean, seriously.",
        )
        for ((input, output) in cases) assertAccepted(input, output)
    }

    @Test fun `blank output is still accepted so the blank path is unchanged`() {
        assertAccepted("what is the capital of france", "")
    }

    @Test fun `rejection detail never contains the transcript or the model output`() {
        val input = "hey what time does the store close on sunday"
        val output = "The store closes at 5:00 PM on Sunday."
        val verdict = validate(input, output) as LocalCleanupValidation.Rejected
        // Same privacy contract the other checks hold: logcat gets a reason, never the content.
        for (word in listOf("store", "sunday", "5:00", "capital")) {
            assertTrue(
                "detail leaked content word '$word': ${verdict.detail}",
                !verdict.detail.contains(word, ignoreCase = true),
            )
        }
    }

    @Test fun `failure notice describes the rejection by effect rather than by enum name`() {
        val summary = CleanupFailureNotice.summarize(
            "All cleanup steps failed: Local cleanup output rejected (QUESTION_ANSWERED)",
        )
        assertEquals("model answered instead of cleaning up", summary)
    }
}
