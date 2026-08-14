package com.trevornk.ramblr

/**
 * Pure decision for #136: should a Quick Settings tile toggle un-hide the floating icon before
 * handing off to [WhisperAccessibilityService.onTap]?
 *
 * Extracted rather than inlined because the surrounding call site is Android-bound
 * (SharedPreferences, WindowManager, NotificationManager) and this project doesn't use
 * Robolectric -- the same reason [DownloadNotifications]' throttling logic lives as a pure
 * function with its own tests. This is the actual rule worth testing.
 *
 * The rule: un-hide only when a tile tap is about to START a recording that would otherwise be
 * invisible. [IconHiddenState] holds both overlay windows at alpha=0f, so a recording begun in
 * that state gives the user no on-screen indication that the mic is live -- a privacy problem,
 * not merely a cosmetic one.
 *
 * Deliberately scoped to [RecordingStateMachine.State.IDLE]:
 *  - RECORDING -> the tile is stopping, and the icon was necessarily visible when it started;
 *    if the user hid it mid-recording, resurrecting it on stop would override an explicit choice.
 *  - TRANSCRIBING -> [WhisperAccessibilityService.onTap] is a no-op in this state, so there is no
 *    recording to make visible.
 *  - null (accessibility service not connected) -> never reached; the tile deep-links to Settings
 *    instead, and there is no live overlay to reveal.
 */
fun shouldRestoreIconBeforeToggle(
    state: RecordingStateMachine.State?,
    hiddenByUser: Boolean,
): Boolean = state == RecordingStateMachine.State.IDLE && hiddenByUser
