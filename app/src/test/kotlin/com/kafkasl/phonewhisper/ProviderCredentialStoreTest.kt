package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for [ProviderCredentialStore]'s pref-key mapping -- the encrypted-prefs
 *  parts need a real Context/Keystore and can't be JVM-tested (same caveat as
 *  [CleanupCredentialStore], see [CleanupCredentialStoreTest]). */
class ProviderCredentialStoreTest {

    @Test fun `each non-local provider kind maps to its own distinct pref key`() {
        val nonLocal = ProviderKind.values().filter { it != ProviderKind.LOCAL }
        val keys = nonLocal.map { ProviderCredentialStore.prefKeyFor(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("openai_key", ProviderCredentialStore.prefKeyFor(ProviderKind.OPENAI))
        assertEquals("anthropic_key", ProviderCredentialStore.prefKeyFor(ProviderKind.ANTHROPIC))
        assertEquals("gemini_key", ProviderCredentialStore.prefKeyFor(ProviderKind.GEMINI))
        assertEquals("omniroute_key", ProviderCredentialStore.prefKeyFor(ProviderKind.OMNIROUTE))
    }

    @Test fun `local has no credential slot`() {
        assertEquals(null, ProviderCredentialStore.prefKeyFor(ProviderKind.LOCAL))
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
