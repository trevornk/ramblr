package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    // --- transcript trimming (#140) ---
    // A trailing newline in the model's transcript was injected verbatim into the user's field,
    // leaving the cursor several lines below the dictated text (reported against Google Keep).
    // Every other transcription parser in the app already trims; this one was the exception.

    @Test fun `trailing newlines are trimmed off the transcript`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world\n\n\n"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `leading whitespace is trimmed off the transcript`() {
        val r = TranscriberClient.parseResponse("""{"text": "  Hello world"}""")
        assertEquals("Hello world", r.text)
    }

    @Test fun `interior newlines are preserved`() {
        // Only the edges are trimmed -- a genuine multi-line dictation must survive intact.
        val r = TranscriberClient.parseResponse("""{"text": "\nline one\nline two\n"}""")
        assertEquals("line one\nline two", r.text)
    }

    // -- endpoint construction honors the base-URL override (M5) --

    @Test fun `default base url resolves to OpenAI's transcriptions endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            TranscriberClient.transcriptionEndpoint(PostProcessor.DEFAULT_BASE_URL),
        )
    }

    @Test fun `a proxy base url is honored, not hardcoded to api openai com (M5)`() {
        assertEquals(
            "https://proxy.example.com/v1/audio/transcriptions",
            TranscriberClient.transcriptionEndpoint("https://proxy.example.com/v1"),
        )
    }

    @Test fun `a blank base url falls back to the OpenAI default`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            TranscriberClient.transcriptionEndpoint(""),
        )
    }

    // -- #114: vocabulary terms as structured keywords[] vs legacy prompt --

    @Test fun `gpt-transcribe gets one keywords part per term, preserving multi-word terms`() {
        val parts = TranscriberClient.vocabularyFormParts(
            "gpt-transcribe", listOf("Solveit", "Claude Code", "Hetzner"),
        )
        assertEquals(
            listOf("keywords[]" to "Solveit", "keywords[]" to "Claude Code", "keywords[]" to "Hetzner"),
            parts,
        )
    }

    @Test fun `dated gpt-transcribe snapshots still qualify for keywords`() {
        assertTrue(TranscriberClient.supportsKeywords("gpt-transcribe-2026-07-31"))
    }

    @Test fun `gpt-4o-transcribe and whisper-1 keep the legacy comma-joined prompt`() {
        for (model in listOf("gpt-4o-transcribe", "whisper-1")) {
            assertEquals(
                listOf("prompt" to "Solveit, Claude Code"),
                TranscriberClient.vocabularyFormParts(model, listOf("Solveit", "Claude Code")),
            )
        }
    }

    @Test fun `the shipped default model uses structured keywords`() {
        // Guard: if DEFAULT_MODEL ever moves to a family without keywords support,
        // supportsKeywords must be updated in the same change.
        assertTrue(TranscriberClient.supportsKeywords(TranscriberClient.DEFAULT_MODEL))
    }

    @Test fun `no terms yields no vocabulary form parts on any model`() {
        assertEquals(emptyList<Pair<String, String>>(), TranscriberClient.vocabularyFormParts("gpt-transcribe", emptyList()))
        assertEquals(emptyList<Pair<String, String>>(), TranscriberClient.vocabularyFormParts("whisper-1", emptyList()))
    }
}
