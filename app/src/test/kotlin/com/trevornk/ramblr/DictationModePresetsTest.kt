package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationModePresetsTest {

    @Test fun `custom is never offered as a preset`() {
        assertFalse(DictationMode.CUSTOM in DictationModePresets.OPTIONS)
        assertEquals(listOf(DictationMode.CLOUD, DictationMode.LOCAL, DictationMode.FASTEST), DictationModePresets.OPTIONS)
    }

    @Test fun `summary names where cleanup runs while cleanup is on`() {
        assertEquals("Fastest: speech on this phone, cleanup in the cloud", DictationModePresets.summary(DictationMode.FASTEST))
    }

    @Test fun `summary never promises cleanup while AI cleanup is switched off`() {
        for (mode in DictationMode.entries) {
            val text = DictationModePresets.summary(mode, cleanupOn = false)
            assertTrue(text, text.endsWith("AI cleanup is off"))
            assertFalse(text, text.contains("cleanup in the cloud"))
        }
    }
}
