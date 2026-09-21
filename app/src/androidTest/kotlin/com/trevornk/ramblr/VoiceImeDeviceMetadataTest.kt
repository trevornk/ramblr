package com.trevornk.ramblr

import android.view.inputmethod.InputMethodManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Checks the parsed, installed InputMethodInfo rather than source XML alone. */
class VoiceImeDeviceMetadataTest {
    fun compiledVoiceSubtypeIsDiscoverableAndStandalone() {
        val context = ProbeRuntimeContext.targetContext
        val manager = context.getSystemService(InputMethodManager::class.java)
        val info = manager.inputMethodList.single {
            it.packageName == context.packageName && it.serviceInfo.name == "com.trevornk.ramblr.RamblrImeService"
        }
        assertEquals(1, info.subtypeCount)
        val subtype = info.getSubtypeAt(0)
        assertEquals("voice", subtype.mode)
        assertTrue("voice subtype must be locale-neutral", subtype.locale.isEmpty())
        assertFalse("voice subtype must remain a standalone selectable IME", subtype.isAuxiliary)
        assertFalse("voice subtype must not override the user's typing IME", subtype.overridesImplicitlyEnabledSubtype())
    }
}