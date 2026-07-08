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
}
