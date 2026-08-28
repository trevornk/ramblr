package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The #233 Phase 1 gate: [CloudLiveWiring] is the only thing standing between a user and the
 * merged cloud-live seam, so every one of its three conditions is asserted independently and in
 * combination. Nothing here touches the network -- a factory is only ever *constructed*, and
 * [CloudLiveTranscriptionSessionFactory.create] opens no socket until `connect()` is called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLiveWiringTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // SecurePrefsFactory caches its EncryptedSharedPreferences per name for the whole process,
        // so a credential written by an earlier test survives Robolectric's per-test app reset.
        // Clear both stores explicitly rather than relying on a fresh-install starting state.
        prefs().edit().clear().apply()
        ProviderKind.values().forEach { ProviderCredentialStore.clear(app, it) }
    }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private fun setUseLocal(useLocal: Boolean) {
        prefs().edit().putBoolean("use_local", useLocal).apply()
    }

    /** All three conditions satisfied: opted in, cloud transcription selected, Gemini key set. */
    private fun configureFullyEnabled() {
        CloudLiveToggle.setEnabled(app, true)
        setUseLocal(false)
        ProviderCredentialStore.set(app, ProviderKind.GEMINI, "test-gemini-key")
    }

    // --- factoryOrNull: the three gating conditions ---

    @Test fun `null on a default install where nothing has been opted into`() {
        assertNull(CloudLiveWiring.factoryOrNull(app))
    }

    @Test fun `null when the toggle is off even with cloud selected and a key present`() {
        configureFullyEnabled()
        CloudLiveToggle.setEnabled(app, false)
        assertNull(CloudLiveWiring.factoryOrNull(app))
    }

    @Test fun `null when the toggle is on but transcription is on-device`() {
        configureFullyEnabled()
        setUseLocal(true)
        assertNull(CloudLiveWiring.factoryOrNull(app))
    }

    /** use_local is absent, not false, on a fresh install -- the default must read as local. */
    @Test fun `null when the toggle is on and use_local has never been written`() {
        CloudLiveToggle.setEnabled(app, true)
        ProviderCredentialStore.set(app, ProviderKind.GEMINI, "test-gemini-key")
        prefs().edit().remove("use_local").apply()
        assertNull(CloudLiveWiring.factoryOrNull(app))
    }

    @Test fun `null when opted in and cloud selected but the Gemini credential is blank`() {
        configureFullyEnabled()
        ProviderCredentialStore.set(app, ProviderKind.GEMINI, "")
        assertNull(CloudLiveWiring.factoryOrNull(app))
    }

    @Test fun `null when opted in and cloud selected but the Gemini credential was cleared`() {
        configureFullyEnabled()
        ProviderCredentialStore.clear(app, ProviderKind.GEMINI)
        assertNull(CloudLiveWiring.factoryOrNull(app))
    }

    /** A key for some *other* provider is not a Gemini key -- live is Gemini-only in Phase 1. */
    @Test fun `null when only a non-Gemini credential is configured`() {
        CloudLiveToggle.setEnabled(app, true)
        setUseLocal(false)
        ProviderCredentialStore.set(app, ProviderKind.OPENAI, "test-openai-key")
        assertNull(CloudLiveWiring.factoryOrNull(app))
    }

    @Test fun `non-null only when all three conditions hold`() {
        configureFullyEnabled()
        assertNotNull(CloudLiveWiring.factoryOrNull(app))
    }

    @Test fun `the constructed factory produces a session`() {
        configureFullyEnabled()
        val factory = CloudLiveWiring.factoryOrNull(app)
        assertNotNull(factory)
        val session = factory!!.create(object : CloudLiveTranscriptionListener {
            override fun onInterim(text: String, timing: CloudLiveTiming) = Unit
            override fun onTerminal(result: CloudLiveTerminal) = Unit
        })
        assertNotNull(session)
        // Never connected, so this opens nothing; close stays idempotent per the seam's contract.
        session.close()
        session.close()
    }

    @Test fun `the factory is built against the production Gemini live client`() {
        configureFullyEnabled()
        val factory = CloudLiveWiring.factoryOrNull(app)
        assertTrue(factory is GeminiCloudLiveTranscriptionClient)
        // Production endpoint only -- the test-endpoint escape hatch must never be wired on.
        assertFalse((factory as GeminiCloudLiveTranscriptionClient).allowTestEndpoint)
    }

    /** An oversized personal vocabulary must degrade to fewer hints, not fail the whole factory
     *  (the client rejects more than MAX_CUSTOM_VOCABULARY terms outright). */
    @Test fun `an oversized custom vocabulary is truncated rather than disabling live`() {
        configureFullyEnabled()
        val terms = (1..GeminiCloudLiveTranscriptionClient.MAX_CUSTOM_VOCABULARY + 50).map { "term-$it" }
        prefs().edit().putString("custom_vocabulary_terms", VocabularyTerms.serialize(terms)).apply()
        assertNotNull(CloudLiveWiring.factoryOrNull(app))
    }

    @Test fun `an empty custom vocabulary still yields a factory`() {
        configureFullyEnabled()
        prefs().edit().putString("custom_vocabulary_terms", "").apply()
        assertNotNull(CloudLiveWiring.factoryOrNull(app))
    }

    // --- isLiveAllowed: the pure policy ---

    @Test fun `isLiveAllowed requires all three inputs`() {
        assertTrue(CloudLiveWiring.isLiveAllowed(true, useLocalTranscription = false, geminiKey = "k"))
        assertFalse(CloudLiveWiring.isLiveAllowed(false, useLocalTranscription = false, geminiKey = "k"))
        assertFalse(CloudLiveWiring.isLiveAllowed(true, useLocalTranscription = true, geminiKey = "k"))
        assertFalse(CloudLiveWiring.isLiveAllowed(true, useLocalTranscription = false, geminiKey = ""))
        assertFalse(CloudLiveWiring.isLiveAllowed(true, useLocalTranscription = false, geminiKey = "   "))
    }

    // --- the settings row's wording tracks the same gate ---

    @Test fun `the subtitle names the specific blocker instead of a generic message`() {
        assertTrue(cloudLiveSubtitleText(false, useLocalTranscription = false, hasGeminiKey = true).startsWith("Off"))
        assertTrue(cloudLiveSubtitleText(true, useLocalTranscription = true, hasGeminiKey = true).contains("on-device"))
        assertTrue(cloudLiveSubtitleText(true, useLocalTranscription = false, hasGeminiKey = false).contains("Gemini key"))
        assertTrue(cloudLiveSubtitleText(true, useLocalTranscription = false, hasGeminiKey = true).startsWith("On —"))
    }

    /** The row must not claim live is running in any state where the wiring would return null. */
    @Test fun `the subtitle only reads as fully on when isLiveAllowed agrees`() {
        val states = listOf(true, false)
        for (enabled in states) for (useLocal in states) for (hasKey in states) {
            val subtitle = cloudLiveSubtitleText(enabled, useLocal, hasKey)
            val allowed = CloudLiveWiring.isLiveAllowed(enabled, useLocal, if (hasKey) "k" else "")
            assertEquals("enabled=$enabled useLocal=$useLocal hasKey=$hasKey", allowed, subtitle.startsWith("On —"))
        }
    }
}
