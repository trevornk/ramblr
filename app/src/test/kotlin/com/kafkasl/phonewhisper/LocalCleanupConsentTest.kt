package com.kafkasl.phonewhisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCleanupConsentTest {

    @Test fun `prompts when local transcription and network cleanup are both on and unconfirmed`() {
        assertTrue(LocalCleanupConsent.shouldPrompt(useLocal = true, usePostProcessing = true, hasConsented = false, cleanupIsLocalOnly = false))
    }

    @Test fun `does not prompt once consent was already given`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = true, usePostProcessing = true, hasConsented = true, cleanupIsLocalOnly = false))
    }

    @Test fun `does not prompt when cleanup is off`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = true, usePostProcessing = false, hasConsented = false, cleanupIsLocalOnly = false))
    }

    @Test fun `does not prompt when using cloud transcription`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = false, usePostProcessing = true, hasConsented = false, cleanupIsLocalOnly = false))
    }

    @Test fun `does not prompt when neither local nor cleanup is on`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = false, usePostProcessing = false, hasConsented = false, cleanupIsLocalOnly = false))
    }

    // #65: a LOCAL_LLM-only waterfall never sends the transcript off-device, so the off-device
    // consent prompt must not fire for it — previously this combination showed a false
    // "Cleanup sends text off-device" warning and blocked the overlay cleanup toggle.
    @Test fun `does not prompt when cleanup runs fully on-device`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = true, usePostProcessing = true, hasConsented = false, cleanupIsLocalOnly = true))
    }
}
