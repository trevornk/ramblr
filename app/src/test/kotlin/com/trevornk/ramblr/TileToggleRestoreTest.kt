package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #136: a Quick Settings tile tap must never start a recording the user can't see. See
 * [shouldRestoreIconBeforeToggle]'s kdoc for why this rule is start-only.
 */
class TileToggleRestoreTest {

    @Test fun `restores the icon when starting a recording while hidden -- the whole point of the fix`() {
        assertTrue(
            shouldRestoreIconBeforeToggle(
                state = RecordingStateMachine.State.IDLE,
                hiddenByUser = true,
            )
        )
    }

    @Test fun `does nothing when starting a recording with the icon already visible`() {
        assertFalse(
            shouldRestoreIconBeforeToggle(
                state = RecordingStateMachine.State.IDLE,
                hiddenByUser = false,
            )
        )
    }

    @Test fun `stopping a recording never resurrects an icon the user hid mid-recording`() {
        assertFalse(
            shouldRestoreIconBeforeToggle(
                state = RecordingStateMachine.State.RECORDING,
                hiddenByUser = true,
            )
        )
    }

    @Test fun `stopping a recording with a visible icon leaves it alone`() {
        assertFalse(
            shouldRestoreIconBeforeToggle(
                state = RecordingStateMachine.State.RECORDING,
                hiddenByUser = false,
            )
        )
    }

    @Test fun `TRANSCRIBING never restores, since onTap is a no-op in that state`() {
        assertFalse(
            shouldRestoreIconBeforeToggle(
                state = RecordingStateMachine.State.TRANSCRIBING,
                hiddenByUser = true,
            )
        )
    }

    @Test fun `a disconnected accessibility service never restores -- there is no overlay to reveal`() {
        // null == WhisperAccessibilityService.currentRecordingState() with no live instance. The
        // tile deep-links to Settings in this case rather than toggling, so this is belt-and-braces.
        assertFalse(shouldRestoreIconBeforeToggle(state = null, hiddenByUser = true))
    }
}
