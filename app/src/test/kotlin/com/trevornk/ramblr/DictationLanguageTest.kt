package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #290: the dictation-language setting. The behavior that matters most is that a fresh
 *  install (and any bad stored value) sends no language hint at all, which is exactly what
 *  every build before this setting did. */
class DictationLanguageTest {

    @Test fun `never set means auto-detect, no hint`() {
        assertNull(DictationLanguage.languageOrNull(FakeSharedPreferences()))
    }

    @Test fun `setLanguage persists and is read back`() {
        val prefs = FakeSharedPreferences()
        DictationLanguage.setLanguage(prefs, "de")
        assertEquals("de", DictationLanguage.languageOrNull(prefs))
    }

    @Test fun `setting null returns to auto-detect`() {
        val prefs = FakeSharedPreferences()
        DictationLanguage.setLanguage(prefs, "de")
        DictationLanguage.setLanguage(prefs, null)
        assertNull(DictationLanguage.languageOrNull(prefs))
        assertEquals(DictationLanguage.AUTO, prefs.getString(DictationLanguage.KEY, "missing"))
    }

    @Test fun `an unknown stored value is treated as auto, never sent to a provider`() {
        val prefs = FakeSharedPreferences(mutableMapOf(DictationLanguage.KEY to "xx"))
        assertNull(DictationLanguage.languageOrNull(prefs))
    }

    @Test fun `setLanguage refuses an unsupported code and stores auto`() {
        val prefs = FakeSharedPreferences()
        DictationLanguage.setLanguage(prefs, "klingon")
        assertNull(DictationLanguage.languageOrNull(prefs))
    }

    @Test fun `supported codes are unique two-letter ISO-639-1 codes`() {
        assertEquals(DictationLanguage.SUPPORTED.size, DictationLanguage.SUPPORTED.toSet().size)
        DictationLanguage.SUPPORTED.forEach { assertTrue(it, Regex("[a-z]{2}").matches(it)) }
        assertTrue("de" in DictationLanguage.SUPPORTED)
        assertTrue("en" in DictationLanguage.SUPPORTED)
    }

    @Test fun `english name is used for prompts regardless of device locale`() {
        assertEquals("German", DictationLanguage.englishName("de"))
        assertEquals("French", DictationLanguage.englishName("fr"))
    }

    @Test fun `label shows native name when it differs`() {
        assertEquals("German (Deutsch)", DictationLanguage.label("de"))
        assertEquals("English", DictationLanguage.label("en"))
        assertEquals("Auto-detect", DictationLanguage.label(null))
    }

    @Test fun `picker lists every supported language sorted by english name`() {
        val order = DictationLanguage.pickerOrder()
        assertEquals(DictationLanguage.SUPPORTED.toSet(), order.toSet())
        assertEquals(order.map { DictationLanguage.englishName(it) }.sorted(), order.map { DictationLanguage.englishName(it) })
    }

    @Test fun `gemini live gets plain codes for documented languages`() {
        assertEquals("de", DictationLanguage.geminiLiveCode("de"))
        assertEquals("en", DictationLanguage.geminiLiveCode("en"))
    }

    @Test fun `gemini live gets its own spelling where the docs differ`() {
        assertEquals("zh-Hans", DictationLanguage.geminiLiveCode("zh"))
        assertEquals("pt-BR", DictationLanguage.geminiLiveCode("pt"))
    }

    @Test fun `gemini live gets no hint for auto or a code outside the setting`() {
        assertNull(DictationLanguage.geminiLiveCode(null))
        assertNull(DictationLanguage.geminiLiveCode("xx"))
    }

    @Test fun `gemini live uses fil for filipino as its docs spell it`() {
        assertEquals("fil", DictationLanguage.geminiLiveCode("tl"))
    }

    @Test fun `every supported language has a live code the live client accepts`() {
        val clientPattern = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
        DictationLanguage.SUPPORTED.forEach { code ->
            val live = DictationLanguage.geminiLiveCode(code)
            assertFalse("no live code for $code", live.isNullOrBlank())
            assertTrue("$code -> $live", clientPattern.matches(live!!))
        }
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
