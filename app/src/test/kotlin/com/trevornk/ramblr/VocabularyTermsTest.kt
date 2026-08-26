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

    // --- inEffect / localOnlyNote (#185, updated for #182 option 2) ---

    @Test
    fun inEffectWhenCloudTranscriptionIsActive() {
        assertTrue(VocabularyTerms.inEffect(cloudTranscriptionActive = true, cloudCleanupActive = false, localCleanupActive = false))
    }

    @Test
    fun inEffectWhenCloudCleanupIsActive() {
        assertTrue(VocabularyTerms.inEffect(cloudTranscriptionActive = false, cloudCleanupActive = true, localCleanupActive = false))
    }

    @Test
    fun inEffectWhenLocalCleanupIsActive() {
        // #182 option 2: local cleanup applies the terms via VocabularyPostCorrector's output
        // post-pass, so the F-Droid-recommended fully-local configuration (local ASR + local
        // cleanup) now genuinely uses the vocabulary -- the exact gap #185's note used to admit.
        assertTrue(VocabularyTerms.inEffect(cloudTranscriptionActive = false, cloudCleanupActive = false, localCleanupActive = true))
    }

    @Test
    fun inEffectWhenEveryPathIsActive() {
        assertTrue(VocabularyTerms.inEffect(cloudTranscriptionActive = true, cloudCleanupActive = true, localCleanupActive = true))
    }

    @Test
    fun notInEffectWithLocalTranscriptionAndNoCleanupAtAll() {
        // The one remaining inert configuration: local ASR (no hotword biasing, #131) with
        // cleanup fully off -- no cleanup stage exists to run the #182 post-pass.
        assertFalse(VocabularyTerms.inEffect(cloudTranscriptionActive = false, cloudCleanupActive = false, localCleanupActive = false))
    }

    @Test
    fun localOnlyNoteIsNullWheneverTermsApply() {
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = true, cloudCleanupActive = false, localCleanupActive = false))
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = false, cloudCleanupActive = true, localCleanupActive = false))
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = false, cloudCleanupActive = false, localCleanupActive = true))
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = true, cloudCleanupActive = true, localCleanupActive = true))
    }

    @Test
    fun localOnlyNoteIsNullInFullyLocalModeWithLocalCleanupActive() {
        // Regression pin for the #182 semantics change: local ASR + working local cleanup used
        // to show the "not used" note; the post-pass makes that claim false, so no note.
        assertNull(VocabularyTerms.localOnlyNote(cloudTranscriptionActive = false, cloudCleanupActive = false, localCleanupActive = true))
    }

    @Test
    fun localOnlyNoteExplainsTheInertSettingWhenNoPathApplies() {
        val note = VocabularyTerms.localOnlyNote(cloudTranscriptionActive = false, cloudCleanupActive = false, localCleanupActive = false)
        assertNotNull(note)
        // The note must say the terms are NOT used -- that's the whole point of #185: the
        // setting must stop misrepresenting itself when nothing applies it. It must also not
        // blame local cleanup anymore -- since #182's post-pass, local cleanup DOES support
        // the vocabulary; only transcription lacks it.
        assertTrue(note!!.contains("not used"))
        assertFalse(note.contains("local cleanup don't"))
    }
}
