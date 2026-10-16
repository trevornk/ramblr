package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #310: the pure decision behind "should the on-device batch recognizer be preloaded". */
class BatchRecognizerPolicyTest {
    private val cloudOnly = ProviderChain(
        listOf(
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini"),
        ),
    )
    private val withLocal = cloudOnly.withLocalFloor("some-model")

    @Test
    fun `cloud-only chain with use_local off cannot use the batch recognizer`() {
        assertFalse(BatchRecognizerPolicy.canBeUsed(useLocal = false, chain = cloudOnly, allowLocalFallback = true))
    }

    @Test
    fun `use_local on always can, whatever the chain`() {
        assertTrue(BatchRecognizerPolicy.canBeUsed(useLocal = true, chain = cloudOnly, allowLocalFallback = false))
        assertTrue(BatchRecognizerPolicy.canBeUsed(useLocal = true, chain = withLocal, allowLocalFallback = true))
    }

    @Test
    fun `a LOCAL transcription candidate in a cloud-first chain can`() {
        assertTrue(BatchRecognizerPolicy.canBeUsed(useLocal = false, chain = withLocal, allowLocalFallback = true))
    }

    @Test
    fun `turning the local fallback off removes the LOCAL candidate`() {
        assertFalse(BatchRecognizerPolicy.canBeUsed(useLocal = false, chain = withLocal, allowLocalFallback = false))
    }

    @Test
    fun `a LOCAL entry that is disabled or opted out of transcription is not a candidate`() {
        fun chainWith(local: ProviderChainEntry) = ProviderChain(cloudOnly.entries + local)
        val base = ProviderChainEntry(ProviderKind.LOCAL, "some-model")
        assertFalse(BatchRecognizerPolicy.canBeUsed(false, chainWith(base.copy(enabled = false)), true))
        assertFalse(BatchRecognizerPolicy.canBeUsed(false, chainWith(base.copy(useForTranscription = false)), true))
    }

    @Test
    fun `allow_cloud_fallback is irrelevant -- it only applies when use_local is already on`() {
        // Not a parameter at all: cloud-only stays cloud-only however the cloud-retry toggle is set.
        assertFalse(BatchRecognizerPolicy.canBeUsed(useLocal = false, chain = cloudOnly, allowLocalFallback = true))
    }

    @Test
    fun `the preference keys watched by the runtime are exactly the policy inputs`() {
        assertTrue(
            BatchRecognizerPolicy.DEPENDENT_PREF_KEYS ==
                setOf("use_local", "allow_local_fallback", "provider_chain_entries"),
        )
    }
}
