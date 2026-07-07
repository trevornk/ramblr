package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Builds and caches Keystore-backed [EncryptedSharedPreferences] instances for [ApiKeyStore] and
 * [CleanupCredentialStore] (#79).
 *
 * Two problems this exists to fix:
 *  - Both stores used to rebuild the [MasterKey] and call [EncryptedSharedPreferences.create] on
 *    every get/set -- Keystore round-trips and keyset verification on the main thread, five times
 *    per `MainActivity.refresh()`. Instances are now cached per prefs file for the process
 *    lifetime (they are just SharedPreferences wrappers; caching is the documented usage).
 *  - [EncryptedSharedPreferences.create] throws on the well-known corrupted-keyset /
 *    Keystore-unavailable failure modes, and it sat uncaught on the onCreate -> refresh() path:
 *    a permanent crash loop until the user cleared app data. Creation failures now recover by
 *    deleting the corrupt prefs file (its keyset lives inside the same file) and recreating it
 *    empty -- losing stored API keys beats losing the whole app -- and, if the Keystore is so
 *    broken that even a fresh create fails, fall back to a session-only in-memory store so the
 *    app still launches (secrets then simply read as unset until the next process restart).
 */
internal object SecurePrefsFactory {
    private const val TAG = "SecurePrefsFactory"

    private val cache = HashMap<String, SharedPreferences>()

    @Synchronized
    fun getOrCreate(context: Context, fileName: String): SharedPreferences {
        cache[fileName]?.let { return it }
        val appContext = context.applicationContext
        val prefs = try {
            create(appContext, fileName)
        } catch (first: Exception) {
            Log.e(TAG, "Encrypted prefs '$fileName' unusable; deleting corrupt file and recreating empty", first)
            appContext.deleteSharedPreferences(fileName)
            try {
                create(appContext, fileName)
            } catch (second: Exception) {
                Log.e(TAG, "Keystore unusable even after reset for '$fileName'; secrets are session-only in-memory until restart", second)
                TransientPrefs()
            }
        }
        cache[fileName] = prefs
        return prefs
    }

    private fun create(appContext: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/**
 * Last-resort in-memory [SharedPreferences]: keeps the app usable when the Keystore is broken
 * beyond recovery (#79). Values survive only for the current process. Thread-safe via a single
 * internal monitor, matching SharedPreferences' documented thread-safety.
 */
internal class TransientPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()
    private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized override fun getAll(): MutableMap<String, *> = HashMap(values)

    @Synchronized override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    @Synchronized override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    @Synchronized override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    @Synchronized override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    @Synchronized override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    @Synchronized override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners.add(listener)
    }

    @Synchronized override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners.remove(listener)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearFirst = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = REMOVE }
        override fun clear() = apply { clearFirst = true }

        override fun commit(): Boolean {
            val changedKeys: List<String>
            synchronized(this@TransientPrefs) {
                if (clearFirst) values.clear()
                for ((key, value) in pending) {
                    if (value === REMOVE) values.remove(key) else values[key] = value
                }
                changedKeys = pending.keys.toList()
            }
            val toNotify = synchronized(this@TransientPrefs) { listeners.toList() }
            for (listener in toNotify) {
                for (key in changedKeys) listener.onSharedPreferenceChanged(this@TransientPrefs, key)
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private companion object {
        /** Sentinel marking a pending removal, distinguishing it from putString(key, null). */
        val REMOVE = Any()
    }
}
