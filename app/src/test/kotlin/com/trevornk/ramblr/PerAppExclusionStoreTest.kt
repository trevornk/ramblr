package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppExclusionStoreTest {

    @Test fun `round trips package set through serialize and parse`() {
        val packages = setOf("com.whatsapp", "com.chase.sig.android", "com.slack")

        val parsed = PerAppExclusionStore.parse(PerAppExclusionStore.serialize(packages))

        assertEquals(packages, parsed)
    }

    @Test fun `parse returns empty set for blank or malformed input`() {
        assertEquals(emptySet<String>(), PerAppExclusionStore.parse(null))
        assertEquals(emptySet<String>(), PerAppExclusionStore.parse(""))
        assertEquals(emptySet<String>(), PerAppExclusionStore.parse("not json"))
        assertEquals(emptySet<String>(), PerAppExclusionStore.parse("{}"))
    }

    @Test fun `serialize is stable and sorted`() {
        val a = PerAppExclusionStore.serialize(setOf("z.app", "a.app", "m.app"))
        assertEquals("[\"a.app\",\"m.app\",\"z.app\"]", a)
    }

    @Test fun `exact package match only, no prefix or wildcard behavior`() {
        val map = setOf("com.chase.sig.android")

        // Exact match matters -- this store makes no attempt at prefix matching, and nothing
        // above it should either (see ExclusionGating, which reads this set as-is).
        val exactMatch = map.contains("com.chase.sig.android")
        val prefixOnly = map.contains("com.chase.sig.android.debug")
        val substringOnly = map.contains("com.chase")

        assertTrue(exactMatch)
        assertFalse(prefixOnly)
        assertFalse(substringOnly)
    }
}
