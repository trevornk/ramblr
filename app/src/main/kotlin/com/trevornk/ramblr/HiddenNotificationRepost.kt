package com.trevornk.ramblr

/**
 * Pure decision for #135 (second pass): should the "icon hidden" recovery notification be
 * re-posted right now?
 *
 * ## Why this exists at all
 *
 * The first pass at #135 changed the notification to `setOngoing(true)` on the assumption that
 * ongoing notifications cannot be dismissed. That assumption is wrong on Android 14+, and it was
 * disproved on a real device: the user swiped the notification away and the floating ring became
 * unrecoverable through any on-screen path. `setOngoing` still excludes it from "Clear all" (which
 * is why the device under test had no "Clear all" affecting it), but an individual swipe removes
 * it outright.
 *
 * ## Why not do what the Tesla app does
 *
 * Tesla's phone-key notification looks undismissable, and `dumpsys notification` on the affected
 * device shows the system upgrading its flags rather than the app requesting it:
 *
 * ```text
 * originalFlags=ONLY_ALERT_ONCE|FOREGROUND_SERVICE            <- what Tesla posted
 * flags        =ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE   <- what the system stored
 * ```
 *
 * `NO_CLEAR` is added by NotificationManagerService because that notification is the
 * `startForeground()` notification of a live foreground service (`BLEService`, `isForeground=true`,
 * `types=0x10` = connectedDevice). Tesla never calls `setOngoing`.
 *
 * That flag is nonetheless NOT a way out of this bug. Android 14's "non-dismissible notifications"
 * behavior change explicitly covers foreground-service notifications, and exempts only `CallStyle`,
 * enterprise DPC packages, media notifications, and the Search Selector package -- none of which
 * Ramblr is. Ongoing/FGS notifications are guaranteed non-dismissable in exactly two situations:
 * while the phone is locked, and against "Clear all". An ordinary swipe still removes them.
 *
 * So copying Tesla would mean running a permanent foreground service for the entire time the icon
 * is hidden -- claiming a `dataSync` type Android 15 time-caps (~6h/day) and then force-stops,
 * inviting a Play policy review on the storefront flavor, and taking permanent battery attribution
 * for a service whose only job is notification persistence -- and the notification would STILL be
 * swipeable afterwards. It buys nothing this bug cares about.
 *
 * Self-healing is the only approach that actually survives a swipe. The accessibility service is
 * already running whenever this state matters, so it can simply notice the notification is gone and
 * post it again.
 *
 * ## The rule
 *
 * Re-post only when all three hold:
 *  - the icon is actually hidden ([IconHiddenState]); if it's visible there is nothing to recover
 *    from and a "tap to show it again" notification would be a lie;
 *  - no such notification is currently posted; re-posting over a live one would reorder the shade
 *    and re-alert for no reason;
 *  - the overlay is connected, i.e. there is a real ring being suppressed that a tap can reveal.
 *
 * Checked at natural, low-frequency checkpoints (service connect, unlock) rather than the instant
 * a dismissal is observed. Re-posting immediately on swipe is a fight with the user that the
 * platform's own rate limiter would eventually win anyway; coming back on the next unlock is both
 * well-behaved and sufficient, because the failure being fixed is permanent loss, not brief absence.
 */
fun shouldRepostHiddenNotification(
    iconHidden: Boolean,
    notificationPosted: Boolean,
    overlayConnected: Boolean,
): Boolean = iconHidden && !notificationPosted && overlayConnected
