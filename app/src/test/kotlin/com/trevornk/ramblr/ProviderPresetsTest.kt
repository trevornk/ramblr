package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [ProviderPresets] (#275): the curated Groq/OpenRouter presets and their lookup helpers. */
class ProviderPresetsTest {

    @Test fun `Groq preset is OPENAI-kind with the correct base URL`() {
        assertEquals(ProviderKind.OPENAI, ProviderPresets.GROQ.kind)
        assertEquals("https://api.groq.com/openai/v1", ProviderPresets.GROQ.baseUrl)
        assertEquals("groq", ProviderPresets.GROQ.id)
    }

    @Test fun `OpenRouter preset is OPENAI-kind with the correct base URL`() {
        assertEquals(ProviderKind.OPENAI, ProviderPresets.OPENROUTER.kind)
        assertEquals("https://openrouter.ai/api/v1", ProviderPresets.OPENROUTER.baseUrl)
        assertEquals("openrouter", ProviderPresets.OPENROUTER.id)
    }

    @Test fun `ALL contains exactly Groq and OpenRouter`() {
        assertEquals(setOf("groq", "openrouter"), ProviderPresets.ALL.map { it.id }.toSet())
    }

    @Test fun `forId resolves a known preset id`() {
        assertEquals(ProviderPresets.GROQ, ProviderPresets.forId("groq"))
        assertEquals(ProviderPresets.OPENROUTER, ProviderPresets.forId("openrouter"))
    }

    @Test fun `forId returns null for an unknown or null id`() {
        assertNull(ProviderPresets.forId("bogus"))
        assertNull(ProviderPresets.forId(null))
    }

    @Test fun `displayLabel uses the preset label when the entry has a presetId`() {
        val groqEntry = ProviderChainEntry(ProviderKind.OPENAI, "m", presetId = "groq")
        assertEquals("Groq", ProviderPresets.displayLabel(groqEntry) { "should not be called" })
    }

    @Test fun `displayLabel falls back to the kind label when the entry has no presetId`() {
        val plainEntry = ProviderChainEntry(ProviderKind.OPENAI, "m")
        assertEquals("OpenAI", ProviderPresets.displayLabel(plainEntry) { "OpenAI" })
    }

    @Test fun `every preset is a plain OpenAI-compatible kind, never a kind with its own credential meaning`() {
        // Presets exist specifically because they're OpenAI-compatible hosts reachable via
        // baseUrlOverride -- if a future preset targeted a non-OPENAI kind this assumption
        // (TranscriberClient/PostProcessor's OpenAI wire format) would silently stop applying.
        ProviderPresets.ALL.forEach { preset ->
            assertTrue("${preset.id} must be OPENAI-kind", preset.kind == ProviderKind.OPENAI)
        }
    }
}
