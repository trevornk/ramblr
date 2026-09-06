package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetEntryValidationTest {

    @Test fun `blank trigger is rejected`() {
        assertEquals(SnippetValidationError.BLANK_TRIGGER, SnippetEntryValidation.validate("  ", "expansion", emptySet()))
    }

    @Test fun `blank expansion is rejected`() {
        assertEquals(SnippetValidationError.BLANK_EXPANSION, SnippetEntryValidation.validate("trigger", "  ", emptySet()))
    }

    @Test fun `a trigger with only punctuation is rejected`() {
        assertEquals(
            SnippetValidationError.TRIGGER_HAS_NO_WORD_CHARACTERS,
            SnippetEntryValidation.validate("!!!", "expansion", emptySet()),
        )
    }

    @Test fun `a trigger over the character limit is rejected`() {
        val long = "a".repeat(SnippetEntryValidation.MAX_TRIGGER_CHARS + 1)
        assertEquals(SnippetValidationError.TRIGGER_TOO_LONG, SnippetEntryValidation.validate(long, "expansion", emptySet()))
    }

    @Test fun `a trigger over the word limit is rejected`() {
        val words = (1..SnippetEntryValidation.MAX_TRIGGER_WORDS + 1).joinToString(" ") { "word$it" }
        assertEquals(SnippetValidationError.TRIGGER_TOO_MANY_WORDS, SnippetEntryValidation.validate(words, "expansion", emptySet()))
    }

    @Test fun `an expansion over the character limit is rejected`() {
        val long = "a".repeat(SnippetEntryValidation.MAX_EXPANSION_CHARS + 1)
        assertEquals(SnippetValidationError.EXPANSION_TOO_LONG, SnippetEntryValidation.validate("trigger", long, emptySet()))
    }

    @Test fun `a duplicate normalized trigger is rejected`() {
        val existing = setOf(SnippetExpander.normalizeWords("My Address"))
        assertEquals(
            SnippetValidationError.DUPLICATE_TRIGGER,
            SnippetEntryValidation.validate("my address", "123 Main St", existing),
        )
        // Case/punctuation-different spelling of the same normalized trigger is still a duplicate.
        assertEquals(
            SnippetValidationError.DUPLICATE_TRIGGER,
            SnippetEntryValidation.validate("MY, ADDRESS!", "123 Main St", existing),
        )
    }

    @Test fun `a valid non-duplicate pair passes`() {
        val existing = setOf(SnippetExpander.normalizeWords("my address"))
        assertNull(SnippetEntryValidation.validate("my email", "trevor@example.com", existing))
    }

    @Test fun `boundary lengths are accepted, one over is rejected`() {
        val atLimit = "a".repeat(SnippetEntryValidation.MAX_TRIGGER_CHARS)
        assertNull(SnippetEntryValidation.validate(atLimit, "expansion", emptySet()))
        val wordsAtLimit = (1..SnippetEntryValidation.MAX_TRIGGER_WORDS).joinToString(" ") { "w$it" }
        assertNull(SnippetEntryValidation.validate(wordsAtLimit, "expansion", emptySet()))
    }
}
