package com.trevornk.ramblr

/**
 * Ordering rule for onboarding step 4's "Use on-device" branch (#170).
 *
 * Extracted from [MainActivity.enableOnboardingCleanupLocal] because that branch's bug was purely
 * a *sequencing* one and sequencing is the one part of it that can be unit-tested: the dialogs
 * themselves need an Activity, but "when may the wizard advance, and what may be committed first"
 * is pure logic. Keeping it here means the rule is asserted directly rather than inferred from
 * dialog plumbing.
 *
 * The bug (fdroiddata!42401, reported against 1.0.24): the "Use on-device" branch called
 * `enableOnboardingCleanupLocal(...)` and `showOnboardingStreamingStep()` back to back. For a
 * not-yet-installed model the first call only *starts* the LFM2.5 license dialog and returns
 * immediately, so step 5 -- and from there "Try it out" and FINISH SETUP -- stacked on top of a
 * license prompt the user had not answered yet. The #153 ordering guarantee held (nothing was
 * selected or downloaded on cancel), but the consent gate was visually buried, which is exactly
 * the confusing state #153 existed to remove.
 *
 * The rule, then:
 *  - already installed: no consent needed, commit and advance synchronously (unchanged);
 *  - consent pending: neither commit nor advance -- the wizard waits on the dialog;
 *  - accepted: commit, then advance;
 *  - declined/dismissed: advance *without* committing. Refusing a non-free license must cost the
 *    user nothing but the feature, and stalling the wizard on a dismissed dialog would trade one
 *    bug for a worse dead end.
 */
object OnboardingCleanupLocalFlow {

    /**
     * Runs the "Use on-device" branch.
     *
     * @param isInstalled whether the model is already on disk (no license prompt is shown for it).
     * @param commit persists the local-cleanup selection. Invoked at most once, and only on a path
     *   where the license is satisfied.
     * @param advance shows the next wizard step. Invoked exactly once per [start] call, always
     *   after [commit] when both run, so the wizard can never render step 5 over a live prompt.
     * @param requestConsent shows the license prompt, calling back exactly one of its two lambdas
     *   once the user answers. It must not invoke either synchronously for a model that needs
     *   consent -- that is the asynchronous gap this whole object exists to respect.
     */
    fun start(
        isInstalled: Boolean,
        commit: () -> Unit,
        advance: () -> Unit,
        requestConsent: (onAccepted: () -> Unit, onDeclined: () -> Unit) -> Unit,
    ) {
        if (isInstalled) {
            commit()
            advance()
            return
        }
        // A dialog that reports both "accepted" and "dismissed" (accept, then the dismiss listener
        // firing) must still advance the wizard exactly once -- showing step 5 twice would leave a
        // stale dialog behind the live one, which is the same class of defect as #170 itself.
        var settled = false
        requestConsent(
            {
                if (!settled) {
                    settled = true
                    commit()
                    advance()
                }
            },
            {
                if (!settled) {
                    settled = true
                    advance()
                }
            },
        )
    }
}
