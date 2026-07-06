package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPersonaStoreTest {

    @Test fun `serialize then deserialize round trips a list of custom personas`() {
        val personas = listOf(
            CleanupPersona(key = "custom_abc123", title = "Slack", subtitle = "Concise for chat", prompt = "Rewrite as a Slack message.", isBuiltIn = false),
            CleanupPersona(key = "custom_def456", title = "Legal", subtitle = "Formal legal tone", prompt = "Rewrite formally.", isBuiltIn = false),
        )

        val parsed = CustomPersonaStore.deserialize(CustomPersonaStore.serialize(personas))

        assertEquals(personas, parsed)
    }

    @Test fun `deserialize returns empty list for blank or malformed input`() {
        assertEquals(emptyList<CleanupPersona>(), CustomPersonaStore.deserialize(null))
        assertEquals(emptyList<CleanupPersona>(), CustomPersonaStore.deserialize(""))
        assertEquals(emptyList<CleanupPersona>(), CustomPersonaStore.deserialize("not json"))
    }

    @Test fun `deserialized custom personas are never marked built-in`() {
        val personas = listOf(
            CleanupPersona(key = "custom_x", title = "X", subtitle = "Y", prompt = "Z", isBuiltIn = false),
        )
        val parsed = CustomPersonaStore.deserialize(CustomPersonaStore.serialize(personas))
        assertTrue(parsed.all { !it.isBuiltIn })
    }

    @Test fun `newKey generates distinct non-blank keys`() {
        val a = CustomPersonaStore.newKey()
        val b = CustomPersonaStore.newKey()
        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        assertTrue(a != b)
    }
}
