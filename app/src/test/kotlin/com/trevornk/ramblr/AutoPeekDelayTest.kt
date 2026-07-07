package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoPeekDelayTest {

    @Test fun `defaults to RingPeek's idle timeout in seconds when never set`() {
        assertEquals(4, AutoPeekDelay.secondsOrDefault(FakeSharedPreferences()))
    }

    @Test fun `reflects a previously stored value`() {
        val prefs = FakeSharedPreferences(mutableMapOf(AutoPeekDelay.KEY to 10))
        assertEquals(10, AutoPeekDelay.secondsOrDefault(prefs))
    }

    @Test fun `setSeconds persists and is read back`() {
        val prefs = FakeSharedPreferences()
        AutoPeekDelay.setSeconds(prefs, 8)
        assertEquals(8, AutoPeekDelay.secondsOrDefault(prefs))
    }

    @Test fun `setSeconds clamps below minimum`() {
        val prefs = FakeSharedPreferences()
        AutoPeekDelay.setSeconds(prefs, 0)
        assertEquals(AutoPeekDelay.MIN_SECONDS, AutoPeekDelay.secondsOrDefault(prefs))
    }

    @Test fun `setSeconds clamps above maximum`() {
        val prefs = FakeSharedPreferences()
        AutoPeekDelay.setSeconds(prefs, 999)
        assertEquals(AutoPeekDelay.MAX_SECONDS, AutoPeekDelay.secondsOrDefault(prefs))
    }

    @Test fun `a stored out-of-range value is clamped on read too`() {
        val prefs = FakeSharedPreferences(mutableMapOf(AutoPeekDelay.KEY to 500))
        assertEquals(AutoPeekDelay.MAX_SECONDS, AutoPeekDelay.secondsOrDefault(prefs))
    }

    @Test fun `millisOrDefault converts seconds to millis`() {
        val prefs = FakeSharedPreferences(mutableMapOf(AutoPeekDelay.KEY to 7))
        assertEquals(7_000L, AutoPeekDelay.millisOrDefault(prefs))
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
