package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLiveToggleTest {

    private val prefs = FakeStringPrefs()

    @Test fun `defaults to off when never set`() {
        assertFalse(CloudLiveToggle.isEnabled(prefs))
    }

    /** The default is the whole point of the phase: live is experimental and costs ~1.8x batch,
     *  so a regression flipping this constant to true is a shipped behavior change. */
    @Test fun `the declared default constant is false`() {
        assertEquals(false, CloudLiveToggle.DEFAULT)
    }

    @Test fun `setEnabled persists and is read back`() {
        CloudLiveToggle.setEnabled(prefs, true)
        assertTrue(CloudLiveToggle.isEnabled(prefs))
        CloudLiveToggle.setEnabled(prefs, false)
        assertFalse(CloudLiveToggle.isEnabled(prefs))
    }

    @Test fun `writes under its own key without disturbing the cloud transcription gate`() {
        prefs.edit().putBoolean("use_local", true).apply()
        CloudLiveToggle.setEnabled(prefs, true)
        assertEquals(true, prefs.getBoolean(CloudLiveToggle.KEY, false))
        assertTrue("live toggle must not touch use_local", prefs.getBoolean("use_local", false))
    }
}
