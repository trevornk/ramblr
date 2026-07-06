package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMenuPersonaStoreTest {

    @Test fun `default selection is the five built-in personas in display order`() {
        assertEquals(CleanupPersonas.BUILT_IN.map { it.key }, QuickMenuPersonaStore.defaultSelection())
    }

    @Test fun `serialize then deserialize round trips a key list`() {
        val keys = listOf("formal", "custom_abc", "notes")
        assertEquals(keys, QuickMenuPersonaStore.deserialize(QuickMenuPersonaStore.serialize(keys)))
    }

    @Test fun `deserialize returns null for blank or malformed input`() {
        assertEquals(null, QuickMenuPersonaStore.deserialize(null))
        assertEquals(null, QuickMenuPersonaStore.deserialize(""))
        assertEquals(null, QuickMenuPersonaStore.deserialize("not json"))
    }

    @Test fun `MAX_ENTRIES is at least MIN_ENTRIES`() {
        assertTrue(QuickMenuPersonaStore.MAX_ENTRIES >= QuickMenuPersonaStore.MIN_ENTRIES)
    }
}
