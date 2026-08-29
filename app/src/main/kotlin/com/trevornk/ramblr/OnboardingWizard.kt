package com.trevornk.ramblr

enum class OnboardingSetupMode { FLOATING_BUTTON, VOICE_KEYBOARD }

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
     *
     * [onboardingComplete] is checked FIRST and short-circuits everything else except [forced]
     * (#98 bug fix): [wizardStarted] is an in-memory flag that, before this fix, never reset once
     * true for the rest of the Activity's lifetime -- including across onResume from switching
     * to another app and back, which does NOT recreate the Activity. That meant finishing
     * onboarding once (setting [onboardingComplete] true in prefs, but leaving the in-memory
     * [wizardStarted] flag true) still re-triggered the wizard's step logic on every subsequent
     * onResume, landing back on a step like Transcription mode selection even though setup was
     * genuinely already done. A completed setup must never re-advance except via an explicit
     * [forced] re-entry.
     */
    fun shouldAdvance(
        wizardStarted: Boolean,
        forced: Boolean,
        accessibilityEnabled: Boolean,
        onboardingComplete: Boolean,
    ): Boolean = forced || (!onboardingComplete && (wizardStarted || shouldShow(accessibilityEnabled, onboardingComplete)))

    /**
     * Whether the app is in a genuinely usable state (#52): permissions granted and Transcription
     * -- the one *required* pipeline stage -- configured. Cleanup and Streaming preview are both
     * optional opt-ins (#38/#29) and deliberately never gate this, even mid-download, so this
     * matches Settings' own required-vs-optional tiers instead of the Status row insisting on a
     * fully-configured Cleanup (previously `postReady = !usePostProcessing || hasApiKey` in
     * MainActivity.refresh(), which also mis-scored a fully-working on-device Cleanup choice as
     * "not ready" just because no cloud key was ever entered).
     *
     * Invocation readiness asks whether ANY invocation route is live, not whether the one picked
     * during setup is (#238). [setupMode] is a stored string written once at first run, while
     * [accessibilityEnabled] and [imeEnabled] are read live from the OS every refresh, so gating
     * on the stored value lets them drift apart: disable the IME after a keyboard setup and the
     * app reports "not ready" while a working accessibility service sits right there. The two
     * routes are independent OS registrations -- an accessibility component in
     * `enabled_accessibility_services` and an IME in `enabled_input_methods` -- and coexist, so
     * either one being live means the user can actually dictate. This is the same class of bug
     * as the Cleanup mis-scoring above: a stored preference standing in for observable state.
     *
     * [setupMode] is retained for the wizard's own step sequencing; it is deliberately not an
     * input here.
     */
    fun isSetupComplete(
        audioGranted: Boolean,
        accessibilityEnabled: Boolean,
        transcriptionLocal: Boolean,
        hasLocalModel: Boolean,
        hasApiKey: Boolean,
        setupMode: OnboardingSetupMode = OnboardingSetupMode.FLOATING_BUTTON,
        imeEnabled: Boolean = false,
    ): Boolean {
        val transcriptionReady = if (transcriptionLocal) hasLocalModel else hasApiKey
        val invocationReady = accessibilityEnabled || imeEnabled
        return audioGranted && invocationReady && transcriptionReady
    }

    /**
     * Whether the selected transcription path has a usable local model. Checking the selected
     * archive prevents an unrelated installed model from making Settings claim the app is ready.
     */
    fun isTranscriptionModelReady(
        transcriptionLocal: Boolean,
        selectedModel: String,
        knownModels: Collection<String>,
        installedModels: Collection<String>,
    ): Boolean = !transcriptionLocal || selectedModel in knownModels && selectedModel in installedModels
}
