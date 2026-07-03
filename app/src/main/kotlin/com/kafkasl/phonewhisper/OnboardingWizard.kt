package com.kafkasl.phonewhisper

/**
 * Decision logic for the first-run wizard from #6. Gated on two independent signals so a
 * completed setup never nags a returning user even if they later flip Accessibility off
 * temporarily, while an unfinished setup keeps getting re-offered on every visit until either
 * signal clears it.
 */
object OnboardingWizard {
    fun shouldShow(accessibilityEnabled: Boolean, onboardingComplete: Boolean): Boolean =
        !accessibilityEnabled && !onboardingComplete
}
