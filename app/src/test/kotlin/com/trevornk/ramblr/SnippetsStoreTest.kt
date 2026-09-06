package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [SnippetsStore] (CRUD/serialization) and [SnippetsToggle] (opt-in gate), #248.
 * Uses [FakeStringPrefs] (shared internal fake, defined alongside VocabularySuggestionStoreTest)
 * rather than Robolectric, since these stores only need the plain string+boolean prefs surface.
 */
class SnippetsStoreTest {

    private val prefs = FakeStringPrefs()

    @Test fun `serialize then deserialize round-trips a list of snippets`() {
        val entries = listOf(
            SnippetEntry(key = "snippet_a1", trigger = "my address", expansion = "123 Main St"),
            SnippetEntry(key = "snippet_b2", trigger = "my email", expansion = "trevor@example.com"),
        )
        val parsed = SnippetsStore.deserialize(SnippetsStore.serialize(entries))
        assertEquals(entries, parsed)
    }

    @Test fun `deserialize returns empty list for blank or malformed input`() {
        assertEquals(emptyList<SnippetEntry>(), SnippetsStore.deserialize(null))
        assertEquals(emptyList<SnippetEntry>(), SnippetsStore.deserialize(""))
        assertEquals(emptyList<SnippetEntry>(), SnippetsStore.deserialize("not json"))
    }

    @Test fun `load defaults to empty list when nothing stored`() {
        assertTrue(SnippetsStore.load(prefs).isEmpty())
    }

    @Test fun `save then load round-trips through real prefs surface`() {
        val entries = listOf(SnippetEntry(key = "k1", trigger = "hi", expansion = "hello there"))
        SnippetsStore.save(prefs, entries)
        assertEquals(entries, SnippetsStore.load(prefs))
    }

    @Test fun `newKey generates distinct non-blank keys`() {
        val a = SnippetsStore.newKey()
        val b = SnippetsStore.newKey()
        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        assertTrue(a != b)
    }

    // --- toggle -----------------------------------------------------------------------------

    @Test fun `snippets are off by default`() {
        assertFalse(SnippetsToggle.isEnabled(prefs))
    }

    @Test fun `setEnabled persists and is read back`() {
        SnippetsToggle.setEnabled(prefs, true)
        assertTrue(SnippetsToggle.isEnabled(prefs))
        SnippetsToggle.setEnabled(prefs, false)
        assertFalse(SnippetsToggle.isEnabled(prefs))
    }
}
