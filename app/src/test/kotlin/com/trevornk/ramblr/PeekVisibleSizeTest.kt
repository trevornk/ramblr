package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class PeekVisibleSizeTest {

    @Test fun `defaults to RingPeek's peek-visible size when never set`() {
        assertEquals(20, PeekVisibleSize.dpOrDefault(FakeSharedPreferences()))
    }

    @Test fun `reflects a previously stored value`() {
        val prefs = FakeSharedPreferences(mutableMapOf(PeekVisibleSize.KEY to 30))
        assertEquals(30, PeekVisibleSize.dpOrDefault(prefs))
    }

    @Test fun `setDp persists and is read back`() {
        val prefs = FakeSharedPreferences()
        PeekVisibleSize.setDp(prefs, 24)
        assertEquals(24, PeekVisibleSize.dpOrDefault(prefs))
    }

    @Test fun `setDp clamps below minimum`() {
        val prefs = FakeSharedPreferences()
        PeekVisibleSize.setDp(prefs, 1)
        assertEquals(PeekVisibleSize.MIN_DP, PeekVisibleSize.dpOrDefault(prefs))
    }

    @Test fun `setDp clamps above maximum`() {
        val prefs = FakeSharedPreferences()
        PeekVisibleSize.setDp(prefs, 999)
        assertEquals(PeekVisibleSize.MAX_DP, PeekVisibleSize.dpOrDefault(prefs))
    }

    @Test fun `a stored out-of-range value is clamped on read too`() {
        val prefs = FakeSharedPreferences(mutableMapOf(PeekVisibleSize.KEY to 500))
        assertEquals(PeekVisibleSize.MAX_DP, PeekVisibleSize.dpOrDefault(prefs))
    }

    /** Minimal in-memory [SharedPreferences] fake — enough surface for an int-only store. */
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

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { pending[key!!] = value }
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
