package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #306: a transcription model given silence returned the user's vocabulary list (comma-joined) as
 * the transcript. All vocabulary here is synthetic.
 */
class VocabularyEchoGuardTest {

    private val terms = listOf(
        "Zorbex", "Quillfarm", "Nimbato", "tessera.io", "Orvane Labs",
        "Plinth", "Kestrel Deck", "Vantro", "Hollowmere", "Dunwick",
    )

    // ---- echoes: must be caught ----------------------------------------------------------------

    @Test fun `the whole list comma-joined with a final period is an echo`() {
        assertTrue(VocabularyEchoGuard.isEcho(terms.joinToString(", ") + ".", terms))
        assertTrue(VocabularyEchoGuard.isEcho(VocabularyTerms.asTranscriptionPrompt(terms), terms))
    }

    @Test fun `a contiguous slice of the list is an echo`() {
        assertTrue(VocabularyEchoGuard.isEcho("Nimbato, tessera.io, Orvane Labs, Plinth", terms))
        assertTrue(VocabularyEchoGuard.isEcho("Plinth, Kestrel Deck, Vantro.", terms))
    }

    @Test fun `a list with terms dropped but order kept is an echo`() {
        assertTrue(VocabularyEchoGuard.isEcho("Zorbex, Nimbato, Plinth, Dunwick", terms))
    }

    @Test fun `shape variations of the list are still echoes`() {
        assertTrue(VocabularyEchoGuard.isEcho("Zorbex Quillfarm Nimbato", terms)) // no commas
        assertTrue(VocabularyEchoGuard.isEcho("Zorbex,Quillfarm,Nimbato", terms)) // no spaces
        assertTrue(VocabularyEchoGuard.isEcho("Zorbex\nQuillfarm\nNimbato", terms)) // newlines
        assertTrue(VocabularyEchoGuard.isEcho("- Zorbex\n- Quillfarm\n- Nimbato", terms)) // bullets
        assertTrue(VocabularyEchoGuard.isEcho("1. Zorbex\n2. Quillfarm\n3. Nimbato", terms)) // numbered
        assertTrue(VocabularyEchoGuard.isEcho("zorbex, QUILLFARM, nimbato", terms)) // case
        assertTrue(VocabularyEchoGuard.isEcho("Zorbex; Quillfarm; Nimbato", terms)) // semicolons
        assertTrue(VocabularyEchoGuard.isEcho("  Orvane   Labs , Plinth,  Kestrel Deck ", terms)) // spacing
    }

    @Test fun `a multi-word term is matched whole, not as fragments`() {
        // "Orvane Labs" and "Kestrel Deck" each count as one item; no stray word is left over.
        assertTrue(VocabularyEchoGuard.isEcho("tessera.io, Orvane Labs, Kestrel Deck", terms))
    }

    @Test fun `prompt text reproduced verbatim is an echo`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(terms)
        assertTrue(VocabularyEchoGuard.isEcho(prompt, terms, prompt))
        // The instruction text alone, with no list at all, is just as much an echo.
        val instructions = prompt.substringBefore(": " + terms.first())
        assertFalse(instructions.contains(terms.first()))
        assertTrue(VocabularyEchoGuard.isEcho(instructions, terms, prompt))
    }

    @Test fun `a long run of prompt text inside other output is an echo`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(terms)
        val run = prompt.split(" ").drop(20).take(12).joinToString(" ")
        assertTrue(VocabularyEchoGuard.isEcho("Okay. $run", terms, prompt))
    }

    // ---- legitimate speech: must pass ------------------------------------------------------------

    @Test fun `dictating a single vocabulary term passes`() {
        for (term in terms) assertFalse(term, VocabularyEchoGuard.isEcho(term, terms))
        assertFalse(VocabularyEchoGuard.isEcho("Zorbex.", terms))
    }

    @Test fun `dictating two vocabulary terms passes`() {
        assertFalse(VocabularyEchoGuard.isEcho("Zorbex, Quillfarm", terms))
        assertFalse(VocabularyEchoGuard.isEcho("Zorbex and Quillfarm", terms))
    }

    @Test fun `an ordinary sentence containing vocabulary words passes`() {
        assertFalse(VocabularyEchoGuard.isEcho("Ask Zorbex to sync with Quillfarm before Friday.", terms, GeminiTranscriberClient.transcribePrompt(terms)))
        assertFalse(VocabularyEchoGuard.isEcho("deploy Nimbato to Plinth and Dunwick tonight", terms))
    }

    @Test fun `a list containing any non-vocabulary word passes`() {
        assertFalse(VocabularyEchoGuard.isEcho("Zorbex, Quillfarm, Nimbato, banana", terms))
        assertFalse(VocabularyEchoGuard.isEcho("milk, Zorbex, Quillfarm, Nimbato", terms))
    }

    @Test fun `terms out of list order are not treated as an echo`() {
        // Documented boundary: only list-ordered runs match, so a person naming three of their
        // own terms in an arbitrary order is not discarded.
        assertFalse(VocabularyEchoGuard.isEcho("Nimbato, Zorbex, Plinth", terms))
    }

    @Test fun `ordinary speech sharing a few words with the prompt passes`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(terms)
        assertFalse(VocabularyEchoGuard.isEcho("please transcribe this audio exactly as spoken", terms, prompt)) // 7 words
        assertFalse(VocabularyEchoGuard.isEcho("I never output a hint unless it matters", terms, prompt))
    }

    @Test fun `empty vocabulary never matches`() {
        assertFalse(VocabularyEchoGuard.isEcho("Zorbex, Quillfarm, Nimbato", emptyList()))
        assertFalse(VocabularyEchoGuard.isEcho("anything at all", emptyList(), "anything at all and then some more words"))
    }

    @Test fun `a vocabulary shorter than the minimum list cannot be list-echoed`() {
        assertFalse(VocabularyEchoGuard.isEcho("Zorbex, Quillfarm", listOf("Zorbex", "Quillfarm")))
    }

    @Test fun `blank or null text never matches`() {
        assertFalse(VocabularyEchoGuard.isEcho(null, terms))
        assertFalse(VocabularyEchoGuard.isEcho("", terms))
        assertFalse(VocabularyEchoGuard.isEcho("   \n", terms))
    }

    // ---- empty-transcript gate ---------------------------------------------------------------------

    @Test fun `an empty transcript without an error is no speech, anything else is not`() {
        assertTrue(isNoSpeechTranscript("", null))
        assertTrue(isNoSpeechTranscript("  ", null))
        assertFalse(isNoSpeechTranscript("", "HTTP 500"))
        assertFalse(isNoSpeechTranscript(null, null))
        assertFalse(isNoSpeechTranscript(null, "timeout"))
        assertFalse(isNoSpeechTranscript("hello", null))
    }

    // ---- prompt hardening ----------------------------------------------------------------------------

    @Test fun `the gemini prompt asks for an empty transcript on silence`() {
        assertTrue(GeminiTranscriberClient.TRANSCRIBE_PROMPT.contains("no speech, return an empty transcript"))
    }

    @Test fun `the gemini prompt frames vocabulary as spelling hints that are never output unless spoken`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(terms)
        assertTrue(prompt.startsWith(GeminiTranscriberClient.TRANSCRIBE_PROMPT))
        assertTrue(prompt, prompt.contains("spelling hints only"))
        assertTrue(prompt, prompt.contains("not part of the audio"))
        assertTrue(prompt, prompt.contains("Never output a hint unless it was actually spoken"))
        assertFalse("the old content-like wording must be gone", prompt.contains("Watch for these"))
        assertTrue(prompt.endsWith(terms.joinToString(", ") + "."))
    }

    // ---- client wire-in: Gemini batch ---------------------------------------------------------------

    private fun geminiBody(text: String, finishReason: String = "STOP") =
        """{"candidates":[{"content":{"parts":[{"text":${org.json.JSONObject.quote(text)}}]},"finishReason":"$finishReason"}]}"""

    @Test fun `gemini batch turns a vocabulary-list answer into an empty transcript`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(terms)
        val result = GeminiTranscriberClient.resultFor(geminiBody(terms.joinToString(", ") + "."), terms, prompt)
        assertEquals("", result.text)
        assertNull(result.error)
        assertTrue(isNoSpeechTranscript(result.text, result.error))
    }

    @Test fun `gemini batch turns a prompt-text answer into an empty transcript`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(terms)
        val result = GeminiTranscriberClient.resultFor(geminiBody(prompt), terms, prompt)
        assertEquals("", result.text)
        assertNull(result.error)
    }

    @Test fun `gemini batch passes real speech and a single vocabulary term through untouched`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(terms)
        assertEquals("Ask Zorbex about Quillfarm.", GeminiTranscriberClient.resultFor(geminiBody("Ask Zorbex about Quillfarm."), terms, prompt).text)
        assertEquals("Plinth", GeminiTranscriberClient.resultFor(geminiBody("Plinth"), terms, prompt).text)
    }

    @Test fun `gemini batch with no vocabulary never blanks anything`() {
        val result = GeminiTranscriberClient.resultFor(geminiBody("Zorbex, Quillfarm, Nimbato"), emptyList(), GeminiTranscriberClient.transcribePrompt(emptyList()))
        assertEquals("Zorbex, Quillfarm, Nimbato", result.text)
    }

    @Test fun `gemini finishing normally with no text is an empty transcript, other finishes stay errors`() {
        val stop = GeminiTranscriberClient.parseResponse("""{"candidates":[{"content":{"role":"model"},"finishReason":"STOP"}]}""")
        assertEquals("", stop.text); assertNull(stop.error)
        val blankPart = GeminiTranscriberClient.parseResponse("""{"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}]}""")
        assertEquals("", blankPart.text); assertNull(blankPart.error)
        val safety = GeminiTranscriberClient.parseResponse("""{"candidates":[{"finishReason":"SAFETY"}]}""")
        assertNull(safety.text); assertEquals("No text content in response", safety.error)
        val noReason = GeminiTranscriberClient.parseResponse("""{"candidates":[{"content":{"parts":[]}}]}""")
        assertNull(noReason.text); assertEquals("No text content in response", noReason.error)
    }

    // ---- client wire-in: OpenAI batch ---------------------------------------------------------------

    @Test fun `openai batch turns a vocabulary-list answer into an empty transcript`() {
        val result = TranscriberClient.resultFor("""{"text":"${terms.joinToString(", ")}."}""", terms)
        assertEquals("", result.text)
        assertNull(result.error)
        assertTrue(isNoSpeechTranscript(result.text, result.error))
    }

    @Test fun `openai batch passes real speech and a single term through untouched`() {
        assertEquals("send it to Zorbex", TranscriberClient.resultFor("""{"text":"send it to Zorbex"}""", terms).text)
        assertEquals("Hollowmere", TranscriberClient.resultFor("""{"text":"Hollowmere"}""", terms).text)
    }

    @Test fun `openai batch errors and empty text are left as they are`() {
        assertEquals("bad key", TranscriberClient.resultFor("""{"error":{"message":"bad key"}}""", terms).error)
        val empty = TranscriberClient.resultFor("""{"text":""}""", terms)
        assertEquals("", empty.text); assertNull(empty.error)
    }

    // ---- client wire-in: Gemini Interactions ----------------------------------------------------------

    @Test fun `interactions client turns a custom-vocabulary answer into an empty transcript`() {
        val echo = GeminiInteractionsTranscriberClient.Result(terms.joinToString(", "), null)
        val guarded = GeminiInteractionsTranscriberClient.guardEcho(echo, terms)
        assertEquals("", guarded.text)
        assertNull(guarded.error)
        val speech = GeminiInteractionsTranscriberClient.Result("Zorbex and Quillfarm shipped", null)
        assertEquals(speech, GeminiInteractionsTranscriberClient.guardEcho(speech, terms))
        val failure = GeminiInteractionsTranscriberClient.Result(null, "boom")
        assertEquals(failure, GeminiInteractionsTranscriberClient.guardEcho(failure, terms))
    }
}
