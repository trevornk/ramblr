package com.trevornk.ramblr

import android.content.Context

/**
 * Whether the keyboard's style and translate lists can do what they offer, and why not. A style
 * or a language picked with no model to run it used to be inserted as plain recognized text with
 * no word of explanation. Mirrors how [DictationRuntime] resolves cleanup.
 */
internal object KeyboardAiReadiness {
    const val STYLE_BLOCKED = "No AI model for styles yet. Add a cloud provider for cleanup, or download an on-device cleanup model."
    const val TRANSLATION_BLOCKED = "Translation needs a cloud AI provider with a key and cleanup turned on for it."

    private fun prefs(context: Context) = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    /** A cloud provider that may do cleanup and has its key set. */
    fun cloudCleanupReady(context: Context): Boolean =
        CloudFeatureToggle.cleanupEnabled(context) &&
            ProviderChainStore.load(context).capableEntriesFor(needsTranscription = false)
                .any { it.kind != ProviderKind.LOCAL && ProviderCredentialStore.isConfiguredOrLegacy(context, it) }

    /** An on-device cleanup step that would run, with its model downloaded. */
    fun localCleanupReady(context: Context): Boolean {
        val chain = ProviderChainRuntime.effectiveChainForCleanup(
            ProviderChainStore.load(context), CloudFeatureToggle.cleanupEnabled(context), DictationModeToggle.allowLocalFallback(context),
        )
        return ProviderChainRuntime.cleanupWaterfallFor(chain).usesLocalLlm() &&
            LOCAL_CLEANUP_MODEL_CATALOG.any { ModelDownloader.isInstalled(context, it) }
    }

    /** Why a style can't run right now, or null when it can. */
    fun styleBlocker(context: Context): String? =
        if (cloudCleanupReady(context) || localCleanupReady(context)) null else STYLE_BLOCKED

    /** Why translation can't run right now, or null when it can: it needs the cloud (#23 aside,
     *  on-device cleanup models are too small to translate). */
    fun translationBlocker(context: Context): String? =
        if (cloudCleanupReady(context)) null else TRANSLATION_BLOCKED

    /**
     * Translation always sends the recognized text to the cloud, so while speech is recognized
     * on-device it needs the same one-time consent cleanup asks for (#23).
     */
    fun translationNeedsConsent(context: Context): Boolean =
        LocalCleanupConsent.shouldPrompt(
            useLocal = prefs(context).getBoolean("use_local", true),
            usePostProcessing = true,
            hasConsented = prefs(context).getBoolean(KEY_CONSENT, false),
            cleanupIsLocalOnly = false,
        )

    /** Records the consent the keyboard collected; the same flag Settings writes. */
    fun grantConsent(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONSENT, true).apply()
    }

    /** Where the text would go, for the consent question. */
    fun consentHost(context: Context): String = CleanupDestination.consentHost(ProviderChainStore.load(context))

    const val KEY_CONSENT = "local_cleanup_consent_seen"
}
