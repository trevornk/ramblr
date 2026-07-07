package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAppearancePrefsTest {

    @Test fun `clampRingSizeDp leaves in-range values untouched`() {
        assertEquals(56, OverlayAppearancePrefs.clampRingSizeDp(56))
        assertEquals(OverlayAppearancePrefs.MIN_RING_DP, OverlayAppearancePrefs.clampRingSizeDp(OverlayAppearancePrefs.MIN_RING_DP))
        assertEquals(OverlayAppearancePrefs.MAX_RING_DP, OverlayAppearancePrefs.clampRingSizeDp(OverlayAppearancePrefs.MAX_RING_DP))
    }

    @Test fun `clampRingSizeDp clamps out-of-range values so the icon can't become untappable or screen-dominating`() {
        assertEquals(OverlayAppearancePrefs.MIN_RING_DP, OverlayAppearancePrefs.clampRingSizeDp(1))
        assertEquals(OverlayAppearancePrefs.MAX_RING_DP, OverlayAppearancePrefs.clampRingSizeDp(10_000))
    }

    @Test fun `load defaults to the pre-#53 fixed look when nothing is stored`() {
        val appearance = OverlayAppearancePrefs.load(FakeSharedPreferences())
        assertEquals(OverlayAppearancePrefs.DEFAULT_RING_DP, appearance.ringSizeDp)
        assertNull(appearance.borderColor)
        assertNull(appearance.fillColor)
        assertNull(appearance.glyphColor)
        assertFalse(appearance.hasCustomIcon)
    }

    @Test fun `setters persist and are read back through load`() {
        val prefs = FakeSharedPreferences()
        OverlayAppearancePrefs.setRingSizeDp(prefs, 80)
        OverlayAppearancePrefs.setBorderColor(prefs, 0xFF112233.toInt())
        OverlayAppearancePrefs.setFillColor(prefs, 0xFF445566.toInt())
        OverlayAppearancePrefs.setGlyphColor(prefs, 0xFF778899.toInt())
        OverlayAppearancePrefs.setHasCustomIcon(prefs, true)

        val appearance = OverlayAppearancePrefs.load(prefs)
        assertEquals(80, appearance.ringSizeDp)
        assertEquals(0xFF112233.toInt(), appearance.borderColor)
        assertEquals(0xFF445566.toInt(), appearance.fillColor)
        assertEquals(0xFF778899.toInt(), appearance.glyphColor)
        assertTrue(appearance.hasCustomIcon)
    }

    @Test fun `setRingSizeDp clamps before persisting`() {
        val prefs = FakeSharedPreferences()
        OverlayAppearancePrefs.setRingSizeDp(prefs, 10_000)
        assertEquals(OverlayAppearancePrefs.MAX_RING_DP, OverlayAppearancePrefs.load(prefs).ringSizeDp)
    }

    @Test fun `setting a color to null clears a previously stored override`() {
        val prefs = FakeSharedPreferences()
        OverlayAppearancePrefs.setBorderColor(prefs, 0xFF112233.toInt())
        OverlayAppearancePrefs.setBorderColor(prefs, null)
        assertNull(OverlayAppearancePrefs.load(prefs).borderColor)
    }

    /** Minimal in-memory [SharedPreferences] fake covering int/boolean storage + removal, which
     *  is all [OverlayAppearancePrefs] needs. */
    private class FakeSharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = throw UnsupportedOperationException()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException()
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
        override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }

            override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
            override fun clear(): SharedPreferences.Editor = apply { values.clear() }

            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removals.forEach { values.remove(it) }
                values.putAll(pending)
            }
        }
    }
}
