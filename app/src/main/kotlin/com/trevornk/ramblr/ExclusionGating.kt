package com.trevornk.ramblr

/**
 * #256: pure decision layer for per-app behavior exclusions -- kept Android-free so every branch
 * is a plain JVM unit test, following the same pattern as [HideIconToggle]/[shouldRestoreIconBeforeToggle]
 * and [overlayShouldBeVisible].
 *
 * **What "exclusion" actually buys you here, stated plainly because the issue's own body warns
 * getting this wrong is worse than not shipping:** these are behavioral opt-outs applied at the
 * points Ramblr already gates today (ring visibility, `onTap`/`requestToggleRecording`, final
 * injection) -- not a change to what the accessibility service is bound to or listens for. See
 * [WhisperAccessibilityService.onAccessibilityEvent] (still an empty override) and PRIVACY.md's
 * "Accessibility Service" section: this feature adds zero new event subscriptions and zero
 * continuous foreground-tracking. Every function below is evaluated *on demand*, at the exact
 * moment an existing call site would otherwise have acted, using whatever foreground-package read
 * is already available there -- never from a background poll or listener this feature would add.
 *
 * **The one criterion this file cannot deliver on:** the issue's "ring hidden vs visible-but-
 * inert" open question was decided as "hidden" (see [ringHiddenForExclusion]), but only for the
 * instant [WhisperAccessibilityService.applyOverlayVisibility] happens to run (app foreground
 * change, screen on/off, keyguard, or one of the other few triggers it already has). There is no
 * accessibility-window-changed subscription behind this — adding one would be exactly the silent
 * privacy-contract change the task forbids (continuous tree awareness where today there is none).
 * So the ring can visibly lag a real foreground switch into an excluded app by however long it
 * takes for one of the *existing* triggers to fire next, and nothing in this feature closes that
 * gap. Any UI copy describing this must not claim instant hide; if a caller needs "hides the
 * instant you switch apps" as a hard requirement, that requires broadening the privacy contract
 * (an accessibility-window-changed subscription), which is a decision for the issue owner to make
 * explicitly, not something an implementation should introduce quietly.
 */
object ExclusionGating {
    /**
     * Whether a NEW recording should be blocked because the current foreground app is excluded.
     * Scoped to [RecordingStateMachine.State.IDLE] on purpose, mirroring
     * [shouldRestoreIconBeforeToggle]'s reasoning: RECORDING/TRANSCRIBING means a dictation is
     * already in flight, and stop/cancel must stay reachable regardless of exclusion -- a user
     * mid-recording who then gets treated as "can't stop this" because the foreground app just
     * became excluded (e.g. an app switch mid-utterance) would be strictly worse than doing
     * nothing. Blocking only applies to the tap/toggle that would *start* a brand-new one.
     */
    fun shouldBlockNewRecording(state: RecordingStateMachine.State?, excluded: Boolean): Boolean =
        excluded && state == RecordingStateMachine.State.IDLE

    /**
     * Whether the final transcript should be withheld from the destination it would otherwise be
     * written into. Deliberately independent of recording state: this only ever runs at the
     * injection call site, once a dictation has already produced text to deliver, and the
     * exclusion list's whole point is "never land text in this app" regardless of how the
     * dictation that produced it got started (ring, tile, or a stale in-flight one that outlived
     * an app switch).
     */
    fun shouldSuppressInsertion(excluded: Boolean): Boolean = excluded

    /**
     * Whether the floating ring should be force-hidden for exclusion, reusing
     * [WhisperAccessibilityService.applyOverlayVisibility]'s existing alpha+touchable mechanism
     * (never a new View.GONE path -- see that function's Pixel Fold comment for why).
     *
     * [packageName] must be a *positively known* foreground identity (i.e.
     * `rootInActiveWindow?.packageName`, non-null) -- an unknown identity (null, the root query
     * failed or returned no package) is never treated as excluded. This is the honest fail-open
     * choice the task asks for explicitly: guessing "unknown == excluded" would occasionally hide
     * the ring in ordinary, non-sensitive apps for no real privacy benefit, which is a worse
     * failure mode than occasionally leaving it visible one tick longer over an excluded app while
     * the identity read is momentarily unavailable.
     */
    fun ringHiddenForExclusion(packageName: String?, exclusions: Set<String>): Boolean =
        packageName != null && packageName in exclusions
}
