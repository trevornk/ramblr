package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTextRetryToggleTest {

    @Test fun `defaults to on when never set`() {
        assertTrue(RawTextRetryToggle.isEnabled(FakeSharedPreferences()))
    }

    @Test fun `reflects a previously stored false value`() {
        val prefs = FakeSharedPreferences(mutableMapOf(RawTextRetryToggle.KEY to false))
        assertFalse(RawTextRetryToggle.isEnabled(prefs))
    }

    @Test fun `setEnabled persists false and is read back`() {
        val prefs = FakeSharedPreferences()
        RawTextRetryToggle.setEnabled(prefs, false)
        assertFalse(RawTextRetryToggle.isEnabled(prefs))
    }

    @Test fun `setEnabled persists true and is read back`() {
        val prefs = FakeSharedPreferences(mutableMapOf(RawTextRetryToggle.KEY to false))
        RawTextRetryToggle.setEnabled(prefs, true)
        assertTrue(RawTextRetryToggle.isEnabled(prefs))
    }

    /** Minimal in-memory [SharedPreferences] fake — enough surface for a boolean-only store. */
    private class FakeSharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = throw UnsupportedOperationException()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException()
        override fun getInt(key: String?, defValue: Int): Int = throw UnsupportedOperationException()
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

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { pending[key!!] = value }

            override fun remove(key: String?): SharedPreferences.Editor = apply { pending.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { values.clear() }

            override fun commit(): Boolean { apply(); return true }
            override fun apply() { values.putAll(pending) }
        }
    }
}
