package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #310: the pure decision behind "should the on-device batch recognizer be preloaded". */
class BatchRecognizerPolicyTest {
    @Test
    fun `use_local on preloads`() {
        assertTrue(BatchRecognizerPolicy.canBeUsed(useLocal = true))
    }

    @Test
    fun `use_local off does not preload -- a LOCAL fallback candidate loads on demand instead`() {
        assertFalse(BatchRecognizerPolicy.canBeUsed(useLocal = false))
    }

    @Test
    fun `use_local is the only preference the runtime watches`() {
        assertEquals(setOf("use_local"), BatchRecognizerPolicy.DEPENDENT_PREF_KEYS)
    }
}
