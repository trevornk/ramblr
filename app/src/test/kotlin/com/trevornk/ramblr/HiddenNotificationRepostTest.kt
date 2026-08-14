package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #135 (second pass): the "icon hidden" recovery notification must come back on its own after a
 * swipe-dismissal, because `setOngoing(true)` does not prevent dismissal on Android 14+ and a lost
 * notification previously left the floating ring unrecoverable. See
 * [shouldRepostHiddenNotification]'s kdoc for the mechanism and for why Ramblr does not copy the
 * foreground-service approach that makes the Tesla app's notification truly undismissable.
 */
class HiddenNotificationRepostTest {

    @Test fun `reposts when the icon is hidden and the user swiped the notification away`() {
        assertTrue(
            shouldRepostHiddenNotification(
                iconHidden = true,
                notificationPosted = false,
                overlayConnected = true,
            )
        )
    }

    @Test fun `does not repost while the notification is still in the shade`() {
        assertFalse(
            shouldRepostHiddenNotification(
                iconHidden = true,
                notificationPosted = true,
                overlayConnected = true,
            )
        )
    }

    @Test fun `never posts a recovery notification for an icon that is already visible`() {
        assertFalse(
            shouldRepostHiddenNotification(
                iconHidden = false,
                notificationPosted = false,
                overlayConnected = true,
            )
        )
    }

    @Test fun `does not resurrect a notification the user dismissed after restoring the icon`() {
        // The ordinary end of the hidden state: icon restored, notification cancelled. Nothing
        // here should ever put it back -- that would be a notification about a ring that is
        // plainly visible on screen.
        assertFalse(
            shouldRepostHiddenNotification(
                iconHidden = false,
                notificationPosted = true,
                overlayConnected = true,
            )
        )
    }

    @Test fun `stays silent when the overlay is not connected -- there is no ring to restore`() {
        // Accessibility service off: tapping the notification could not reveal anything, so the
        // notification would be an empty promise. The service's own connect path re-posts once
        // the overlay is actually back.
        assertFalse(
            shouldRepostHiddenNotification(
                iconHidden = true,
                notificationPosted = false,
                overlayConnected = false,
            )
        )
    }

    @Test fun `a disconnected service does not repost even when everything else looks wrong`() {
        assertFalse(
            shouldRepostHiddenNotification(
                iconHidden = true,
                notificationPosted = true,
                overlayConnected = false,
            )
        )
    }
}
