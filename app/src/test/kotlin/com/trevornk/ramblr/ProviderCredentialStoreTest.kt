package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for [ProviderCredentialStore]'s pref-key mapping -- the encrypted-prefs
 *  parts need a real Context/Keystore and can't be JVM-tested. */
class ProviderCredentialStoreTest {

    @Test fun `each non-local provider kind maps to its own distinct legacy pref key`() {
        val nonLocal = ProviderKind.values().filter { it != ProviderKind.LOCAL }
        val keys = nonLocal.map { ProviderCredentialStore.legacyPrefKeyFor(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("openai_key", ProviderCredentialStore.legacyPrefKeyFor(ProviderKind.OPENAI))
        assertEquals("anthropic_key", ProviderCredentialStore.legacyPrefKeyFor(ProviderKind.ANTHROPIC))
        assertEquals("gemini_key", ProviderCredentialStore.legacyPrefKeyFor(ProviderKind.GEMINI))
        assertEquals("omniroute_key", ProviderCredentialStore.legacyPrefKeyFor(ProviderKind.OMNIROUTE))
    }

    @Test fun `local has no legacy credential slot`() {
        assertEquals(null, ProviderCredentialStore.legacyPrefKeyFor(ProviderKind.LOCAL))
    }

    @Test fun `two distinct entry ids map to two distinct pref keys (#273)`() {
        val keyA = ProviderCredentialStore.entryPrefKeyFor("entry-a")
        val keyB = ProviderCredentialStore.entryPrefKeyFor("entry-b")
        assertTrue(keyA != null && keyB != null && keyA != keyB)
    }

    @Test fun `a blank entry id has no pref key`() {
        assertEquals(null, ProviderCredentialStore.entryPrefKeyFor(""))
    }

    @Test fun `maskForDisplay is blank for a blank value`() {
        assertEquals("", ProviderCredentialStore.maskForDisplay(""))
    }

    @Test fun `maskForDisplay shows only the last 4 characters for a long value`() {
        assertEquals("***cdef", ProviderCredentialStore.maskForDisplay("sk-abcdef"))
    }

    @Test fun `maskForDisplay does not crash on a short value`() {
        assertEquals("***", ProviderCredentialStore.maskForDisplay("ab"))
    }
}
