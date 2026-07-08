package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [TransientPrefs], the last-resort in-memory fallback [SecurePrefsFactory] hands out
 *  when the Keystore is broken beyond recovery (#79). The factory itself needs a real Context +
 *  Keystore and can't be JVM-tested. */
class SecurePrefsFactoryTest {

    @Test fun `values written through an editor are readable back`() {
        val prefs = TransientPrefs()
        prefs.edit().putString("api_key", "sk-test").putBoolean("flag", true).putInt("n", 7).apply()

        assertEquals("sk-test", prefs.getString("api_key", null))
        assertTrue(prefs.getBoolean("flag", false))
        assertEquals(7, prefs.getInt("n", 0))
    }

    @Test fun `missing keys fall back to defaults`() {
        val prefs = TransientPrefs()
        assertNull(prefs.getString("absent", null))
        assertEquals("d", prefs.getString("absent", "d"))
        assertFalse(prefs.contains("absent"))
    }

    @Test fun `remove deletes the key`() {
        val prefs = TransientPrefs()
        prefs.edit().putString("k", "v").apply()
        prefs.edit().remove("k").apply()
        assertNull(prefs.getString("k", null))
        assertFalse(prefs.contains("k"))
    }

    @Test fun `clear wipes previous values but keeps puts from the same edit`() {
        val prefs = TransientPrefs()
        prefs.edit().putString("old", "v").apply()
        prefs.edit().clear().putString("new", "w").apply()
        assertNull(prefs.getString("old", null))
        assertEquals("w", prefs.getString("new", null))
    }

    @Test fun `commit returns true and applies immediately`() {
        val prefs = TransientPrefs()
        assertTrue(prefs.edit().putString("k", "v").commit())
        assertEquals("v", prefs.getString("k", null))
    }

    @Test fun `change listeners fire for each written key`() {
        val prefs = TransientPrefs()
        val changed = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putString("a", "1").putString("b", "2").apply()
        assertEquals(setOf("a", "b"), changed.toSet())

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("c", "3").apply()
        assertFalse(changed.contains("c"))
    }

    @Test fun `getAll returns a snapshot, not a live view`() {
        val prefs = TransientPrefs()
        prefs.edit().putString("k", "v").apply()
        val snapshot = prefs.all
        prefs.edit().putString("k2", "v2").apply()
        assertEquals(1, snapshot.size)
    }

    // --- SecurePrefsRecovery: only corruption-shaped failures may wipe stored keys (H8) ---

    private class AEADBadTagException(message: String? = null) : Exception(message)
    private class InvalidProtocolBufferException(message: String? = null) : Exception(message)

    @Test fun `a bad-tag decrypt failure is corruption-shaped`() {
        assertTrue(SecurePrefsRecovery.isCorruptionShaped(AEADBadTagException()))
    }

    @Test fun `an unparseable keyset is corruption-shaped`() {
        assertTrue(SecurePrefsRecovery.isCorruptionShaped(InvalidProtocolBufferException()))
    }

    @Test fun `corruption is detected through a wrapping cause chain`() {
        val wrapped = RuntimeException("create failed", AEADBadTagException("bad tag"))
        assertTrue(SecurePrefsRecovery.isCorruptionShaped(wrapped))
    }

    @Test fun `a message hint marks corruption even for a generic exception type`() {
        assertTrue(SecurePrefsRecovery.isCorruptionShaped(IllegalStateException("keyset is corrupt")))
    }

    @Test fun `a transient Keystore failure is NOT corruption-shaped and must not wipe keys`() {
        // The whole point of H8: a busy/just-unlocked Keystore throws a plain exception that must
        // never trigger a wipe of the user's stored API keys.
        assertFalse(SecurePrefsRecovery.isCorruptionShaped(
            IllegalStateException("Keystore operation failed: keystore busy")))
        assertFalse(SecurePrefsRecovery.isCorruptionShaped(RuntimeException("could not connect to keystore")))
    }

    @Test fun `a cyclic cause chain terminates instead of looping`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a -> b -> a -> ...
        assertFalse(SecurePrefsRecovery.isCorruptionShaped(a))
    }
}
