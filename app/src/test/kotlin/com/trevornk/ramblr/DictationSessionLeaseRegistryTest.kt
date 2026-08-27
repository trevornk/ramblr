package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSessionLeaseRegistryTest {

    @Test
    fun `only one lease can be held at a time`() {
        val registry = InMemoryDictationSessionLeaseRegistry()

        val first = registry.tryAcquire()

        assertNotNull(first)
        assertNull(registry.tryAcquire())
    }

    @Test
    fun `stale lease cannot release a newer generation`() {
        val registry = InMemoryDictationSessionLeaseRegistry()
        val first = registry.tryAcquire()!!
        assertTrue(registry.release(first))
        val second = registry.tryAcquire()!!
        assertNotEquals(first, second)
        assertTrue(second.generation > first.generation)

        assertFalse(registry.release(first))
        assertNull(registry.tryAcquire())
        assertTrue(registry.release(second))
        assertNotNull(registry.tryAcquire())
    }
}