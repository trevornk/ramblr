package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularySuggestionsToggleTest {

    private val prefs = FakeStringPrefs()

    @Test fun `defaults to on when never set`() {
        assertTrue(VocabularySuggestionsToggle.isEnabled(prefs))
    }

    @Test fun `setEnabled persists and is read back`() {
        VocabularySuggestionsToggle.setEnabled(prefs, false)
        assertFalse(VocabularySuggestionsToggle.isEnabled(prefs))
        VocabularySuggestionsToggle.setEnabled(prefs, true)
        assertTrue(VocabularySuggestionsToggle.isEnabled(prefs))
    }

    @Test fun `turning off clears accumulated candidate counters`() {
        VocabularySuggestionStore.recordEvents(
            prefs,
            listOf(VocabularySuggestionExtractor.CandidateEvent("Hetzner", "hetzler", false)),
            vocabularyTerms = emptyList(),
            nowMs = 0,
        )
        assertEquals(1, VocabularySuggestionStore.loadCandidates(prefs).size)

        VocabularySuggestionsToggle.setEnabled(prefs, false)

        assertTrue(VocabularySuggestionStore.loadCandidates(prefs).isEmpty())
        assertNull(prefs.getString(VocabularySuggestionStore.CANDIDATES_KEY, null))
    }

    @Test fun `turning off preserves the dismissed list`() {
        VocabularySuggestionStore.dismiss(prefs, "Hetzner")

        VocabularySuggestionsToggle.setEnabled(prefs, false)

        assertEquals(listOf("Hetzner"), VocabularySuggestionStore.dismissedTerms(prefs))
    }

    @Test fun `turning back on starts from empty counters`() {
        VocabularySuggestionStore.recordEvents(
            prefs,
            listOf(VocabularySuggestionExtractor.CandidateEvent("Hetzner", null, false)),
            vocabularyTerms = emptyList(),
            nowMs = 0,
        )
        VocabularySuggestionsToggle.setEnabled(prefs, false)
        VocabularySuggestionsToggle.setEnabled(prefs, true)
        assertTrue(VocabularySuggestionStore.loadCandidates(prefs).isEmpty())
    }
}
