package com.trevornk.ramblr

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingVisibilityModeTest {

    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    @Test fun `defaults to always`() {
        assertEquals(RingVisibilityMode.ALWAYS, RingVisibilityMode.read(prefs))
    }

    @Test fun `writing a mode round-trips`() {
        for (mode in RingVisibilityMode.entries) {
            RingVisibilityMode.write(prefs, mode)
            assertEquals(mode, RingVisibilityMode.read(prefs))
        }
    }

    @Test fun `an unknown stored value falls back to always`() {
        prefs.edit().putString(RingVisibilityMode.KEY, "SOMETHING_NEW").apply()
        assertEquals(RingVisibilityMode.ALWAYS, RingVisibilityMode.read(prefs))
    }
}
