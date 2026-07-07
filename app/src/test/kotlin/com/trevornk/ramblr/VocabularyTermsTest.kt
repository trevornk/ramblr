package com.trevornk.ramblr

import org.junit.Assert.assertEquals
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
}
