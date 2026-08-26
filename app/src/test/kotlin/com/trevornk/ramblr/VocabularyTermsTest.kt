package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyTermsTest {

    @Test
    fun parseNullOrBlankReturnsEmptyList() {
        assertEquals(emptyList<String>(), VocabularyTerms.parse(null))
        assertEquals(emptyList<String>(), VocabularyTerms.parse(""))
        assertEquals(emptyList<String>(), VocabularyTerms.parse("   \n  \n"))
    }

    @Test
    fun parseSplitsOnNewlinesAndTrims() {
        assertEquals(
            listOf("FastHTML", "OmniRoute", "nbdev"),
            VocabularyTerms.parse("  FastHTML \n OmniRoute\nnbdev  ")
        )
    }

    @Test
    fun parseDropsBlankLines() {
        assertEquals(
            listOf("FastHTML", "nbdev"),
            VocabularyTerms.parse("FastHTML\n\n   \nnbdev\n")
        )
    }

    @Test
    fun parseDedupesCaseInsensitivelyKeepingFirstSeen() {
        assertEquals(
            listOf("FastHTML", "nbdev"),
            VocabularyTerms.parse("FastHTML\nnbdev\nfasthtml\nNBDEV")
        )
    }

    @Test
    fun serializeJoinsWithNewlines() {
        assertEquals("FastHTML\nOmniRoute", VocabularyTerms.serialize(listOf("FastHTML", "OmniRoute")))
    }

    @Test
    fun serializeEmptyListIsEmptyString() {
        assertEquals("", VocabularyTerms.serialize(emptyList()))
    }

    @Test
    fun parseAndSerializeRoundTrip() {
        val terms = listOf("Solveit", "fast.ai", "Answer.AI")
        assertEquals(terms, VocabularyTerms.parse(VocabularyTerms.serialize(terms)))
    }

    @Test
    fun defaultsMatchThePreviouslyHardcodedTermList() {
        assertEquals(
            listOf("Solveit", "fast.ai", "Answer.AI", "nbdev", "fastcore", "FastHTML", "Pi", "Codex", "Claude Code", "Hetzner"),
            VocabularyTerms.DEFAULTS
        )
    }

    @Test
    fun defaultSerializedParsesBackToDefaults() {
        assertEquals(VocabularyTerms.DEFAULTS, VocabularyTerms.parse(VocabularyTerms.DEFAULT_SERIALIZED))
    }

    @Test
    fun defaultSerializedContainsEveryDefaultTerm() {
        for (term in VocabularyTerms.DEFAULTS) {
            assertTrue(VocabularyTerms.DEFAULT_SERIALIZED.contains(term))
        }
    }

    // --- inEffect / localOnlyNote (#185) ---

    @Test
    fun inEffectWhenCloudTranscriptionIsActive() {
        assertTrue(VocabularyTerms.inEffect(cloudTranscriptionActive = true, cloudCleanupActive = false))
    }

    @Test
    fun inEffectWhenCloudCleanupIsActive() {
        assertTrue(VocabularyTerms.inEffect(cloudTranscriptionActive = false, cloudCleanupActive = true))
    }

    @Test
    fun inEffectWhenBothCloudPathsAreActive() {
        assertTrue(VocabularyTerms.inEffect(cloudTranscriptionActive = true, cloudCleanupActive = true))
    }

    @Test
    fun notInEffectInFullyLocalMode() {
        // The F-Droid-recommended privacy configuration: local ASR + local cleanup. After #182
        // (local cleanup no longer receives the terms) and #131 (local ASR hotword biasing not
        // planned), no path applies the vocabulary here.
        assertFalse(VocabularyTerms.inEffect(cloudTranscriptionActive = false, cloudCleanupActive = false))
    }

    @Test
    fun localOnlyNoteIsNullWheneverTermsApply() {
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = true, cloudCleanupActive = false))
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = false, cloudCleanupActive = true))
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = true, cloudCleanupActive = true))
    }

    @Test
    fun localOnlyNoteExplainsTheInertSettingInFullyLocalMode() {
        val note = VocabularyTerms.localOnlyNote(cloudTranscriptionActive = false, cloudCleanupActive = false)
        assertNotNull(note)
        // The note must say the terms are NOT used -- that's the whole point of #185: the
        // setting must stop misrepresenting itself in local-only mode.
        assertTrue(note!!.contains("not used"))
    }
}
