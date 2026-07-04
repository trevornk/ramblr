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

    /**
     * Whether [MainActivity] should render the next wizard step right now (#52). Once the wizard
     * has already started this session ([wizardStarted]), steps keep advancing regardless of
     * [shouldShow] flipping false mid-flow -- e.g. turning Accessibility on sends the user back
     * from system Settings with accessibility now enabled, which alone satisfies [shouldShow]'s
     * "stop nagging" signal and would otherwise silently strand the wizard right after the
     * accessibility step, before it ever reaches Transcription/Cleanup/Streaming. [forced] is for
     * explicit re-entry (the Status row, or a "Redo setup walkthrough" Settings entry) and always
     * proceeds, even for a fully-configured returning user [shouldShow] would otherwise refuse.
     */
    fun shouldAdvance(
        wizardStarted: Boolean,
        forced: Boolean,
        accessibilityEnabled: Boolean,
        onboardingComplete: Boolean,
    ): Boolean = forced || wizardStarted || shouldShow(accessibilityEnabled, onboardingComplete)

    /**
     * Whether the app is in a genuinely usable state (#52): permissions granted and Transcription
     * -- the one *required* pipeline stage -- configured. Cleanup and Streaming preview are both
     * optional opt-ins (#38/#29) and deliberately never gate this, even mid-download, so this
     * matches Settings' own required-vs-optional tiers instead of the Status row insisting on a
     * fully-configured Cleanup (previously `postReady = !usePostProcessing || hasApiKey` in
     * MainActivity.refresh(), which also mis-scored a fully-working on-device Cleanup choice as
     * "not ready" just because no cloud key was ever entered).
     */
    fun isSetupComplete(
        audioGranted: Boolean,
        accessibilityEnabled: Boolean,
        transcriptionLocal: Boolean,
        hasLocalModel: Boolean,
        hasApiKey: Boolean,
    ): Boolean {
        val transcriptionReady = if (transcriptionLocal) hasLocalModel else hasApiKey
        return audioGranted && accessibilityEnabled && transcriptionReady
    }
}
