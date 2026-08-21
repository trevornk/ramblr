package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #170: onboarding must not advance past an unanswered LFM2.5 license prompt.
 *
 * Reported by an F-Droid reviewer on a physical device against 1.0.24 (fdroiddata!42401): step 5
 * and the "Try it out" dialog stacked over the pending consent dialog, so FINISH SETUP was
 * reachable before the license had been answered. The dialogs themselves need an Activity, but
 * the sequencing rule -- which was the whole bug -- is pure, and is asserted here.
 *
 * [FakeConsentPrompt] models the real dialog's defining property: for a model that needs consent
 * it answers *later*, not during [OnboardingCleanupLocalFlow.start]. A test that let it answer
 * synchronously would pass against the original buggy code too and would prove nothing.
 */
class OnboardingCleanupConsentGateTest {

    /** Records what the flow did, in order, so ordering regressions are visible and not inferred. */
    private class Recorder {
        val events = mutableListOf<String>()
        val commit: () -> Unit = { events += "commit" }
        val advance: () -> Unit = { events += "advance" }
    }

    /** A license dialog that, like the real one, only answers when the user answers it. */
    private class FakeConsentPrompt {
        var shown = false
            private set
        private var onAccepted: (() -> Unit)? = null
        private var onDeclined: (() -> Unit)? = null

        val requestConsent: (() -> Unit, () -> Unit) -> Unit = { accepted, declined ->
            shown = true
            onAccepted = accepted
            onDeclined = declined
        }

        fun accept() = checkNotNull(onAccepted) { "consent was never requested" }.invoke()
        fun decline() = checkNotNull(onDeclined) { "consent was never requested" }.invoke()
    }

    // --- Acceptance case 1: the prompt is shown and the wizard waits for it ---

    @Test fun `choosing on-device shows the license prompt and does not advance until it is answered`() {
        val r = Recorder()
        val prompt = FakeConsentPrompt()

        OnboardingCleanupLocalFlow.start(
            isInstalled = false, commit = r.commit, advance = r.advance,
            requestConsent = prompt.requestConsent,
        )

        assertTrue("the license prompt must be shown for a not-yet-installed model", prompt.shown)
        assertEquals(
            "the wizard must not advance to step 5 (nor commit) while consent is pending -- " +
                "that is #170: FINISH SETUP became reachable over an unanswered license dialog",
            emptyList<String>(),
            r.events,
        )
    }

    // --- Acceptance case 2: accept -> enabled, wizard continues ---

    @Test fun `accepting the license commits local cleanup and then advances the wizard`() {
        val r = Recorder()
        val prompt = FakeConsentPrompt()

        OnboardingCleanupLocalFlow.start(
            isInstalled = false, commit = r.commit, advance = r.advance,
            requestConsent = prompt.requestConsent,
        )
        prompt.accept()

        assertEquals(listOf("commit", "advance"), r.events)
    }

    // --- Acceptance case 3: cancel -> nothing selected, but no dead end ---

    @Test fun `declining the license advances the wizard without enabling local cleanup`() {
        val r = Recorder()
        val prompt = FakeConsentPrompt()

        OnboardingCleanupLocalFlow.start(
            isInstalled = false, commit = r.commit, advance = r.advance,
            requestConsent = prompt.requestConsent,
        )
        prompt.decline()

        // #153 must not regress: refusing a non-free license selects nothing and downloads
        // nothing. But the wizard still has to move on -- stalling on a dismissed dialog would
        // trade #170 for a dead-end wizard, which is worse.
        assertEquals(listOf("advance"), r.events)
    }

    // --- Acceptance case 4: already installed -> no prompt, immediate advance ---

    @Test fun `an already-installed model commits and advances immediately with no license prompt`() {
        val r = Recorder()
        val prompt = FakeConsentPrompt()

        OnboardingCleanupLocalFlow.start(
            isInstalled = true, commit = r.commit, advance = r.advance,
            requestConsent = prompt.requestConsent,
        )

        assertEquals(
            "an installed model needs no consent and must keep advancing synchronously",
            listOf("commit", "advance"),
            r.events,
        )
        assertTrue("no license prompt may be shown for an installed model", !prompt.shown)
    }

    // --- Invariants that hold across every path ---

    @Test fun `the wizard advances exactly once even if the prompt reports accept and then dismiss`() {
        // The real AlertDialog's dismiss listener fires after the positive button's, so a naive
        // wiring would advance twice and stack a second step 5 behind the first.
        val r = Recorder()
        val prompt = FakeConsentPrompt()

        OnboardingCleanupLocalFlow.start(
            isInstalled = false, commit = r.commit, advance = r.advance,
            requestConsent = prompt.requestConsent,
        )
        prompt.accept()
        prompt.decline()

        assertEquals(listOf("commit", "advance"), r.events)
    }

    @Test fun `a repeated decline still advances only once`() {
        val r = Recorder()
        val prompt = FakeConsentPrompt()

        OnboardingCleanupLocalFlow.start(
            isInstalled = false, commit = r.commit, advance = r.advance,
            requestConsent = prompt.requestConsent,
        )
        prompt.decline()
        prompt.decline()

        assertEquals(listOf("advance"), r.events)
    }

    @Test fun `commit never runs after advance on any path`() {
        // The wizard's next step reads the cleanup prefs it renders, so committing after
        // advancing would show step 5 built from stale state.
        for (answer in listOf<(FakeConsentPrompt) -> Unit>({ it.accept() }, { it.decline() })) {
            val r = Recorder()
            val prompt = FakeConsentPrompt()
            OnboardingCleanupLocalFlow.start(
                isInstalled = false, commit = r.commit, advance = r.advance,
                requestConsent = prompt.requestConsent,
            )
            answer(prompt)
            val advanceAt = r.events.indexOf("advance")
            val commitAt = r.events.indexOf("commit")
            assertTrue("advance must happen", advanceAt >= 0)
            if (commitAt >= 0) assertTrue("commit must precede advance", commitAt < advanceAt)
        }
    }
}
