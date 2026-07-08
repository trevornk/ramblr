package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UrlRedactionTest {

    @Test fun `strips a key-bearing query string from a URL in a message`() {
        val redacted = UrlRedaction.redact(
            "Invalid URL: https://host/v1beta/models/x:generateContent?key=SECRET123",
        )
        assertEquals("Invalid URL: https://host/v1beta/models/x:generateContent?<redacted>", redacted)
        assertFalse(redacted!!.contains("SECRET123"))
    }

    @Test fun `leaves a URL with no query string untouched`() {
        val msg = "Connection failed to https://api.openai.com/v1/chat/completions"
        assertEquals(msg, UrlRedaction.redact(msg))
    }

    @Test fun `passes through a message with no URL`() {
        assertEquals("HTTP 401", UrlRedaction.redact("HTTP 401"))
    }

    @Test fun `null message stays null`() {
        assertNull(UrlRedaction.redact(null))
    }

    @Test fun `redacts every URL when more than one appears`() {
        val redacted = UrlRedaction.redact("a https://h1/p?key=A b https://h2/q?token=B c")
        assertFalse(redacted!!.contains("key=A"))
        assertFalse(redacted.contains("token=B"))
    }
}
