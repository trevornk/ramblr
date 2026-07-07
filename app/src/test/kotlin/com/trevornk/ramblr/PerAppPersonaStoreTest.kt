package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PerAppPersonaStoreTest {

    @Test fun `round trips package persona map through serialize and parse`() {
        val map = mapOf(
            "com.whatsapp" to "casual",
            "com.google.android.gm" to "formal",
            "com.slack" to "notes",
        )

        val parsed = PerAppPersonaStore.parse(PerAppPersonaStore.serialize(map))

        assertEquals(map, parsed)
    }

    @Test fun `parse returns empty map for blank or malformed input`() {
        assertEquals(emptyMap<String, String>(), PerAppPersonaStore.parse(null))
        assertEquals(emptyMap<String, String>(), PerAppPersonaStore.parse(""))
        assertEquals(emptyMap<String, String>(), PerAppPersonaStore.parse("not json"))
    }

    @Test fun `resolvePersona uses remembered persona for foreground package`() {
        val map = mapOf("com.whatsapp" to "casual")

        val resolved = PerAppPersonaStore.resolvePersona(map, "com.whatsapp", CleanupPersonas.FORMAL)

        assertSame(CleanupPersonas.CASUAL, resolved)
    }

    @Test fun `resolvePersona falls back to global persona when package has no memory`() {
        val map = mapOf("com.whatsapp" to "casual")

        val resolved = PerAppPersonaStore.resolvePersona(map, "com.google.android.gm", CleanupPersonas.NOTES)

        assertSame(CleanupPersonas.NOTES, resolved)
    }

    @Test fun `resolvePersona falls back to global persona when package is unavailable`() {
        val map = mapOf("com.whatsapp" to "casual")

        assertSame(CleanupPersonas.SMART, PerAppPersonaStore.resolvePersona(map, null, CleanupPersonas.SMART))
        assertSame(CleanupPersonas.SMART, PerAppPersonaStore.resolvePersona(map, "", CleanupPersonas.SMART))
    }

    @Test fun `resolvePersona falls back to default for stale unknown persona key`() {
        val map = mapOf("com.whatsapp" to "retired-style")

        val resolved = PerAppPersonaStore.resolvePersona(map, "com.whatsapp", CleanupPersonas.NOTES)

        assertSame(CleanupPersonas.DEFAULT, resolved)
    }
}
