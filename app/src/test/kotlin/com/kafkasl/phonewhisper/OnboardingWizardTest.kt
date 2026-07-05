package com.kafkasl.phonewhisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingWizardTest {

    @Test fun `shows on a fresh install`() {
        assertTrue(OnboardingWizard.shouldShow(accessibilityEnabled = false, onboardingComplete = false))
    }

    @Test fun `does not show once accessibility is enabled`() {
        assertFalse(OnboardingWizard.shouldShow(accessibilityEnabled = true, onboardingComplete = false))
    }

    @Test fun `does not show once onboarding was completed`() {
        assertFalse(OnboardingWizard.shouldShow(accessibilityEnabled = false, onboardingComplete = true))
    }

    @Test fun `does not show when both signals clear it`() {
        assertFalse(OnboardingWizard.shouldShow(accessibilityEnabled = true, onboardingComplete = true))
    }

    @Test fun `is re-offered after being dismissed mid-way, since dismissing does not mark it complete`() {
        // Dismissing a step never flips onboardingComplete, so the next visit sees the same
        // (false, false) inputs as a fresh install and shows the wizard again.
        assertTrue(OnboardingWizard.shouldShow(accessibilityEnabled = false, onboardingComplete = false))
    }

    // --- isSetupComplete (#52) ---

    @Test fun `not ready if audio permission is missing`() {
        assertFalse(OnboardingWizard.isSetupComplete(
            audioGranted = false, accessibilityEnabled = true,
            transcriptionLocal = true, hasLocalModel = true, hasApiKey = false,
        ))
    }

    @Test fun `not ready if accessibility is disabled`() {
        assertFalse(OnboardingWizard.isSetupComplete(
            audioGranted = true, accessibilityEnabled = false,
            transcriptionLocal = true, hasLocalModel = true, hasApiKey = false,
        ))
    }

    @Test fun `ready with local transcription once a model is installed`() {
        assertTrue(OnboardingWizard.isSetupComplete(
            audioGranted = true, accessibilityEnabled = true,
            transcriptionLocal = true, hasLocalModel = true, hasApiKey = false,
        ))
    }

    @Test fun `not ready with local transcription until a model is installed`() {
        assertFalse(OnboardingWizard.isSetupComplete(
            audioGranted = true, accessibilityEnabled = true,
            transcriptionLocal = true, hasLocalModel = false, hasApiKey = false,
        ))
    }

    @Test fun `ready with cloud transcription once an API key is set`() {
        assertTrue(OnboardingWizard.isSetupComplete(
            audioGranted = true, accessibilityEnabled = true,
            transcriptionLocal = false, hasLocalModel = false, hasApiKey = true,
        ))
    }

    @Test fun `not ready with cloud transcription until an API key is set`() {
        assertFalse(OnboardingWizard.isSetupComplete(
            audioGranted = true, accessibilityEnabled = true,
            transcriptionLocal = false, hasLocalModel = false, hasApiKey = false,
        ))
    }

    @Test fun `an unconfigured Cleanup never blocks ready, since Cleanup is optional`() {
        // A working on-device Cleanup setup has no API key at all -- readiness must not require
        // one just because Cleanup happens to be on, since Cleanup's own local-vs-cloud choice is
        // tracked independently of this pure function's inputs.
        assertTrue(OnboardingWizard.isSetupComplete(
            audioGranted = true, accessibilityEnabled = true,
            transcriptionLocal = true, hasLocalModel = true, hasApiKey = false,
        ))
    }

    // --- shouldAdvance (#52) ---

    @Test fun `does not advance for a returning fully-configured user with nothing forced`() {
        assertFalse(OnboardingWizard.shouldAdvance(
            wizardStarted = false, forced = false, accessibilityEnabled = true, onboardingComplete = true,
        ))
    }

    @Test fun `advances to start the wizard on a fresh install`() {
        assertTrue(OnboardingWizard.shouldAdvance(
            wizardStarted = false, forced = false, accessibilityEnabled = false, onboardingComplete = false,
        ))
    }

    @Test fun `keeps advancing mid-wizard even after accessibility flips on`() {
        // Regression for #52's root cause: turning Accessibility on mid-flow makes shouldShow
        // flip false on its own, which must not strand the wizard before it reaches
        // Transcription/Cleanup/Streaming.
        assertTrue(OnboardingWizard.shouldAdvance(
            wizardStarted = true, forced = false, accessibilityEnabled = true, onboardingComplete = false,
        ))
    }

    @Test fun `does not resume an abandoned mid-wizard session on a later, unforced visit`() {
        // wizardStarted resets to false every fresh Activity instance, so a user who dismissed
        // onboarding before finishing it, with accessibility already granted, isn't auto-nagged
        // again -- they can still explicitly re-enter it (forced = true) via Status or Settings.
        assertFalse(OnboardingWizard.shouldAdvance(
            wizardStarted = false, forced = false, accessibilityEnabled = true, onboardingComplete = false,
        ))
    }

    @Test fun `forced re-entry always advances regardless of prior state`() {
        assertTrue(OnboardingWizard.shouldAdvance(
            wizardStarted = false, forced = true, accessibilityEnabled = true, onboardingComplete = true,
        ))
    }

    @Test fun `a completed setup never re-advances just because the wizard ran earlier this session`() {
        // #98 regression: wizardStarted is an in-memory flag that stays true for the rest of the
        // Activity's lifetime once the wizard has run once -- including across onResume from
        // switching to another app and back, which does NOT recreate the Activity. Before this
        // fix, finishing onboarding (onboardingComplete = true) while wizardStarted was still
        // true from that same session re-triggered the wizard's step logic on every subsequent
        // onResume, sending a fully-set-up user back through Transcription mode selection etc.
        assertFalse(OnboardingWizard.shouldAdvance(
            wizardStarted = true, forced = false, accessibilityEnabled = true, onboardingComplete = true,
        ))
    }
}
