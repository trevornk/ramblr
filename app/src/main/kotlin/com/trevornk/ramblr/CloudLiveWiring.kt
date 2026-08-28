package com.trevornk.ramblr

import android.content.Context
import android.util.Log

/**
 * Builds the cloud-live transcription factory for a host, or returns null (#233 Phase 1).
 *
 * The merged seam ([DictationRuntime]'s `cloudLiveFactory`) has been null in every shipped host
 * since it landed, so no user could reach the live path at all. This object is the entire
 * construction/gating layer that closes that gap: it decides whether live transcription is
 * allowed *right now*, and if so builds a configured [GeminiCloudLiveTranscriptionClient]. It
 * adds no behavior of its own -- interim delivery, terminal resolution, and the lossless batch
 * fallback all already live in [DictationRuntime.beginCloudLiveAttempt] behind the null check.
 *
 * Three independent conditions must ALL hold, and each is read from its existing source rather
 * than a new one, so this can never disagree with the rest of the app:
 *
 *  1. [CloudLiveToggle] is on -- the user's explicit, default-OFF opt-in.
 *  2. Cloud transcription is actually selected, i.e. the same `use_local` pref
 *     [DictationRuntime] itself branches on. Live is a *cloud* transcription strategy; offering
 *     it to someone who has chosen on-device would silently contradict that choice.
 *  3. A Gemini credential exists, from [ProviderCredentialStore] -- the exact source the batch
 *     GEMINI branch uses.
 *
 * Returning null is always safe: the host is then constructed exactly as it was before this
 * change, and dictation takes the unchanged batch path.
 */
object CloudLiveWiring {
    private const val TAG = "PhoneWhisper"

    /**
     * The pure gate, split out from [factoryOrNull] so the three-condition policy is unit-testable
     * without a Context, encrypted prefs, or any network object.
     */
    fun isLiveAllowed(
        toggleEnabled: Boolean,
        useLocalTranscription: Boolean,
        geminiKey: String,
    ): Boolean = toggleEnabled && !useLocalTranscription && geminiKey.isNotBlank()

    /**
     * A configured live factory, or null when live transcription is not allowed or cannot be
     * built.
     *
     * Construction failure is deliberately swallowed into null rather than propagated: this runs
     * inside the IME host's onCreate, where a thrown [IllegalArgumentException] from the client's
     * validation would take the whole keyboard down instead of merely disabling an experimental
     * extra. The failure is logged without the credential or any user text.
     */
    fun factoryOrNull(context: Context): CloudLiveTranscriptionSessionFactory? {
        val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        if (!CloudLiveToggle.isEnabled(prefs)) return null
        // Read the existing cloud-vs-local gate exactly as DictationRuntime does; no new pref.
        val useLocal = prefs.getBoolean("use_local", true)
        val apiKey = ProviderCredentialStore.get(context, ProviderKind.GEMINI)
        if (!isLiveAllowed(CloudLiveToggle.isEnabled(prefs), useLocal, apiKey)) return null

        // Same terms the batch transcription path biases on (#26/#114), read from the same key.
        // Truncated to the client's documented ceiling so an oversized personal list downgrades
        // to fewer hints rather than rejecting the factory outright.
        val vocabulary = VocabularyTerms
            .parse(prefs.getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))
            .take(GeminiCloudLiveTranscriptionClient.MAX_CUSTOM_VOCABULARY)

        // languageCodes is left at the client default: the codebase has no configured language
        // source anywhere (the batch Gemini client defaults it the same way), and inventing one
        // here would be a new user-facing setting this phase does not own.
        return try {
            GeminiCloudLiveTranscriptionClient(
                apiKey = apiKey,
                customVocabulary = vocabulary,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cloud-live transcription unavailable: ${e.message}")
            null
        }
    }
}
