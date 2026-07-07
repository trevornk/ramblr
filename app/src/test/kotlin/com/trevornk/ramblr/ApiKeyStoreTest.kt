package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyStoreTest {

    @Test fun `migrates a legacy plaintext key into secure storage and wipes the plaintext copy`() {
        val legacy = FakeSharedPreferences(mutableMapOf("api_key" to "sk-legacy1234"))
        val secure = FakeSharedPreferences()

        val migrated = ApiKeyStore.migrateLegacyKey(legacy, secure)

        assertEquals("sk-legacy1234", migrated)
        assertEquals("sk-legacy1234", secure.getString("api_key", null))
        assertNull(legacy.getString("api_key", null))
    }

    @Test fun `does nothing when there is no legacy key to migrate`() {
        val legacy = FakeSharedPreferences()
        val secure = FakeSharedPreferences()

        val migrated = ApiKeyStore.migrateLegacyKey(legacy, secure)

        assertNull(migrated)
        assertTrue(secure.getAll().isEmpty())
    }

    @Test fun `does not migrate a blank legacy key`() {
        val legacy = FakeSharedPreferences(mutableMapOf("api_key" to "   "))
        val secure = FakeSharedPreferences()

        val migrated = ApiKeyStore.migrateLegacyKey(legacy, secure)

        assertNull(migrated)
        assertEquals("   ", legacy.getString("api_key", null)) // left untouched
        assertTrue(secure.getAll().isEmpty())
    }

    @Test fun `does not clobber an already-migrated secure key when legacy value is absent`() {
        val legacy = FakeSharedPreferences()
        val secure = FakeSharedPreferences(mutableMapOf("api_key" to "sk-alreadysecure"))

        val migrated = ApiKeyStore.migrateLegacyKey(legacy, secure)

        assertNull(migrated)
        assertEquals("sk-alreadysecure", secure.getString("api_key", null))
    }

    @Test fun `masks all but the last four characters`() {
        assertEquals("sk-...6789", ApiKeyStore.maskForDisplay("sk-abcdef123456789"))
        assertEquals("sk-...***", ApiKeyStore.maskForDisplay("abcd"))
        assertEquals("", ApiKeyStore.maskForDisplay(""))
    }

    /** Minimal in-memory [SharedPreferences] fake — enough surface for [ApiKeyStore]'s migration logic. */
    private class FakeSharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

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
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { pending[key!!] = value }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                throw UnsupportedOperationException()

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = throw UnsupportedOperationException()

            override fun remove(key: String?): SharedPreferences.Editor = apply { removals.add(key!!) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean { apply(); return true }

            override fun apply() {
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(pending)
            }
        }
    }
}
