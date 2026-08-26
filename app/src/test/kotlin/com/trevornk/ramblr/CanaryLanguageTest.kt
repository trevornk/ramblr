package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers #177's settings-backed Canary source language. The two cases that matter most:
 *  [`defaults to en when never set`] (shipping this must be zero behavior change for existing
 *  users -- "en" is exactly what was hardcoded before) and [`an unknown stored value coerces to
 *  en`] (a bad token doesn't degrade Canary output, it collapses it to garbage, so an invalid
 *  pref must never reach sherpa-onnx). */
class CanaryLanguageTest {

    @Test fun `defaults to en when never set`() {
        assertEquals("en", CanaryLanguage.languageOrDefault(FakeSharedPreferences()))
    }

    @Test fun `default constant is exactly the pre-existing hardcoded value`() {
        assertEquals("en", CanaryLanguage.DEFAULT)
    }

    @Test fun `supported languages are exactly the canary-180m-flash set, in order`() {
        assertEquals(listOf("en", "es", "de", "fr"), CanaryLanguage.SUPPORTED)
    }

    @Test fun `setLanguage persists and is read back`() {
        val prefs = FakeSharedPreferences()
        CanaryLanguage.setLanguage(prefs, "de")
        assertEquals("de", CanaryLanguage.languageOrDefault(prefs))
    }

    @Test fun `all four supported values roundtrip`() {
        val prefs = FakeSharedPreferences()
        CanaryLanguage.SUPPORTED.forEach { lang ->
            CanaryLanguage.setLanguage(prefs, lang)
            assertEquals(lang, CanaryLanguage.languageOrDefault(prefs))
        }
    }

    @Test fun `an unknown stored value coerces to en`() {
        val prefs = FakeSharedPreferences(mutableMapOf(CanaryLanguage.KEY to "xx"))
        assertEquals("en", CanaryLanguage.languageOrDefault(prefs))
    }

    @Test fun `setLanguage refuses an unsupported value and stores the default`() {
        val prefs = FakeSharedPreferences()
        CanaryLanguage.setLanguage(prefs, "ja")
        assertEquals("en", CanaryLanguage.languageOrDefault(prefs))
    }

    /** Minimal in-memory [SharedPreferences] fake — enough surface for a string-only store. */
    private class FakeSharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException()
        override fun getInt(key: String?, defValue: Int): Int = throw UnsupportedOperationException()
        override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
        override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw UnsupportedOperationException()
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

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = throw UnsupportedOperationException()

            override fun remove(key: String?): SharedPreferences.Editor = apply { pending.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { values.clear() }

            override fun commit(): Boolean { apply(); return true }
            override fun apply() { values.putAll(pending) }
        }
    }
}
