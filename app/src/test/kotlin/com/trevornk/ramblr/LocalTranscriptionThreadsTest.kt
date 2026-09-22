package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers #107's settings-backed local-transcription thread count. The runtime default is now
 *  deliberately hardware-aware for the F-Droid Redmi Note 8T review: it uses spare CPU capacity
 *  without taking every core, while a stored user choice remains authoritative. */
class LocalTranscriptionThreadsTest {

    @Test fun `derived default leaves two cores free on an eight-core device`() {
        assertEquals(6, LocalTranscriptionThreads.defaultForAvailableProcessors(8))
    }

    @Test fun `derived default clamps at the minimum on a single-core device`() {
        assertEquals(LocalTranscriptionThreads.MIN_THREADS, LocalTranscriptionThreads.defaultForAvailableProcessors(1))
    }

    @Test fun `derived default clamps at the sensible ceiling on a many-core device`() {
        assertEquals(LocalTranscriptionThreads.DEFAULT_MAX_THREADS, LocalTranscriptionThreads.defaultForAvailableProcessors(64))
    }

    @Test fun `never-set preference derives from available processors`() {
        assertEquals(6, LocalTranscriptionThreads.threadsOrDefault(FakeSharedPreferences(), availableProcessors = 8))
    }

    @Test fun `stored user choice wins over hardware-derived default`() {
        val prefs = FakeSharedPreferences(mutableMapOf(LocalTranscriptionThreads.KEY to 2))
        assertEquals(2, LocalTranscriptionThreads.threadsOrDefault(prefs, availableProcessors = 8))
    }

    @Test fun `runtime default delegates to the pure derivation`() {
        assertEquals(
            LocalTranscriptionThreads.defaultForAvailableProcessors(Runtime.getRuntime().availableProcessors()),
            LocalTranscriptionThreads.DEFAULT_THREADS,
        )
    }

    @Test fun `presets are exactly 2, 4, 6 in that order`() {
        assertEquals(listOf(2, 4, 6), LocalTranscriptionThreads.PRESET_THREADS)
    }

    @Test fun `reflects a previously stored value`() {
        val prefs = FakeSharedPreferences(mutableMapOf(LocalTranscriptionThreads.KEY to 4))
        assertEquals(4, LocalTranscriptionThreads.threadsOrDefault(prefs))
    }

    @Test fun `setThreads persists and is read back`() {
        val prefs = FakeSharedPreferences()
        LocalTranscriptionThreads.setThreads(prefs, 6)
        assertEquals(6, LocalTranscriptionThreads.threadsOrDefault(prefs))
    }

    @Test fun `setThreads clamps below minimum`() {
        val prefs = FakeSharedPreferences()
        LocalTranscriptionThreads.setThreads(prefs, 0)
        assertEquals(LocalTranscriptionThreads.MIN_THREADS, LocalTranscriptionThreads.threadsOrDefault(prefs))
    }

    @Test fun `setThreads clamps above maximum`() {
        val prefs = FakeSharedPreferences()
        LocalTranscriptionThreads.setThreads(prefs, 999)
        assertEquals(LocalTranscriptionThreads.MAX_THREADS, LocalTranscriptionThreads.threadsOrDefault(prefs))
    }

    @Test fun `a stored out-of-range value is clamped on read too`() {
        val prefs = FakeSharedPreferences(mutableMapOf(LocalTranscriptionThreads.KEY to 500))
        assertEquals(LocalTranscriptionThreads.MAX_THREADS, LocalTranscriptionThreads.threadsOrDefault(prefs))
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
