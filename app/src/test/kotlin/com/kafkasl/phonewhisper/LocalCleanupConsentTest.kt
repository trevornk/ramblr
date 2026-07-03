package com.kafkasl.phonewhisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCleanupConsentTest {

    @Test fun `prompts when local and cleanup are both on and unconfirmed`() {
        assertTrue(LocalCleanupConsent.shouldPrompt(useLocal = true, usePostProcessing = true, hasConsented = false))
    }

    @Test fun `does not prompt once consent was already given`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = true, usePostProcessing = true, hasConsented = true))
    }

    @Test fun `does not prompt when cleanup is off`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = true, usePostProcessing = false, hasConsented = false))
    }

    @Test fun `does not prompt when using cloud transcription`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = false, usePostProcessing = true, hasConsented = false))
    }

    @Test fun `does not prompt when neither local nor cleanup is on`() {
        assertFalse(LocalCleanupConsent.shouldPrompt(useLocal = false, usePostProcessing = false, hasConsented = false))
    }
}
