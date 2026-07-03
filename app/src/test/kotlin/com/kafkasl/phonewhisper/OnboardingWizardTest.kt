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
}
