package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Legacy, pre-provider-chain OpenAI key storage. Nothing live reads or writes this anymore --
 * every current UI (CloudProviderActivity, MainActivity's onboarding) and every runtime call site
 * (WhisperAccessibilityService, PostProcessor) reads/writes [ProviderCredentialStore] exclusively.
 *
 * This object survives purely as [ProviderChainMigration]'s legacy input: [getApiKey] (called
 * once, by [ProviderChainMigration.migrate]) still transparently migrates any pre-#95 plaintext
 * value out of the old "ramblr" prefs file into this object's own encrypted store and seeds it
 * into [ProviderCredentialStore.OPENAI], so a user who set their OpenAI key before the
 * provider-chain unification doesn't lose it. Once [ProviderChainMigration.isMigrated] is true,
 * this object is never touched again.
 *
 * A previous version of this store had a live [setApiKey] entry point that mirrored writes into
 * [ProviderCredentialStore] as a stopgap while two call sites still read this store directly.
 * Both of those call sites (BaseSettingsActivity's dead promptApiKey/apiKeyRowSubtitleText, and
 * MainActivity's onboarding) have since been removed/unified onto [ProviderCredentialStore]
 * directly, so the mirror-write path was deleted too -- keeping it would have left two ways to
 * write "the OpenAI key" with no live caller ever exercising one of them again.
 */
object ApiKeyStore {
    private const val KEY_API_KEY = "api_key"
    private const val LEGACY_PREFS_NAME = "ramblr"
    private const val SECURE_PREFS_NAME = "ramblr_secure"

    fun getApiKey(context: Context): String {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val securePrefs = securePrefs(context)
        return migrateLegacyKey(legacyPrefs, securePrefs) ?: securePrefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun maskForDisplay(apiKey: String): String = when {
        apiKey.isBlank() -> ""
        apiKey.length > 4 -> "sk-...${apiKey.takeLast(4)}"
        else -> "sk-...***"
    }

    /** Moves a legacy plaintext key into secure storage and wipes the plaintext copy. Returns the migrated value, if any. */
    internal fun migrateLegacyKey(legacyPrefs: SharedPreferences, securePrefs: SharedPreferences): String? {
        val legacyKey = legacyPrefs.getString(KEY_API_KEY, null)
        if (legacyKey.isNullOrBlank()) return null
        securePrefs.edit().putString(KEY_API_KEY, legacyKey).apply()
        legacyPrefs.edit().remove(KEY_API_KEY).apply()
        return legacyKey
    }

    /** Cached, crash-loop-proof encrypted prefs -- see [SecurePrefsFactory] (#79). */
    private fun securePrefs(context: Context): SharedPreferences =
        SecurePrefsFactory.getOrCreate(context, SECURE_PREFS_NAME)
}
