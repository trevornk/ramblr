package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #294: a language change must reload the recognizer, but only when Canary is what's loaded. */
class LocalModelReloadTest {
    private val canary = "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"
    private val parakeet = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"

    @Test fun `selected Canary model is active`() =
        assertTrue(LocalModelReload.activeModelIsCanary(canary, listOf(parakeet, canary)))

    @Test fun `selected non-Canary model is not`() =
        assertFalse(LocalModelReload.activeModelIsCanary(parakeet, listOf(parakeet, canary)))

    @Test fun `blank selection auto-detects the first installed model`() {
        assertTrue(LocalModelReload.activeModelIsCanary("", listOf(canary, parakeet)))
        assertFalse(LocalModelReload.activeModelIsCanary("", listOf(parakeet, canary)))
    }

    @Test fun `blank selection with nothing installed is not Canary`() =
        assertFalse(LocalModelReload.activeModelIsCanary("", emptyList()))
}
