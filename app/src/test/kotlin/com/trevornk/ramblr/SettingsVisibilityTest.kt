package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVisibilityTest {

    // --- fallbackSectionState (#276): all 4 combinations of (isActiveSide x fallbackAllowed). ---

    @Test fun `active side is always PRIMARY regardless of the fallback toggle`() {
        assertEquals(
            FallbackSectionState.PRIMARY,
            fallbackSectionState(isActiveSide = true, fallbackAllowed = true)
        )
        assertEquals(
            FallbackSectionState.PRIMARY,
            fallbackSectionState(isActiveSide = true, fallbackAllowed = false)
        )
    }

    @Test fun `inactive side is FALLBACK when its fallback toggle is on`() {
        assertEquals(
            FallbackSectionState.FALLBACK,
            fallbackSectionState(isActiveSide = false, fallbackAllowed = true)
        )
    }

    @Test fun `inactive side is HIDDEN when its fallback toggle is off -- pre-#276 behavior`() {
        assertEquals(
            FallbackSectionState.HIDDEN,
            fallbackSectionState(isActiveSide = false, fallbackAllowed = false)
        )
    }

    // --- shouldShowOpenAiKeyRowForTranscription (#93, extended #276) ---
    // The transcription screen shows its own contextual copy of the key row.

    @Test fun `transcription-scoped row shows only when transcription itself is cloud`() {
        assertTrue(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = false))
        assertFalse(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = true))
    }

    @Test fun `transcription-scoped row also shows for local transcription when cloud fallback is allowed (#276)`() {
        assertTrue(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = true, allowCloudFallback = true))
        assertFalse(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = true, allowCloudFallback = false))
    }

    @Test fun `transcription-scoped row stays shown for cloud transcription regardless of the fallback toggle`() {
        assertTrue(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = false, allowCloudFallback = true))
        assertTrue(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = false, allowCloudFallback = false))
    }

    // --- displayedCleanupChoice (#55) ---
    // Governs cleanupLocalGroup's visibility: it must stay reachable (with its download buttons)
    // whenever the Local radio is the one visually selected, even if no model is installed yet --
    // see onSelectSimpleCleanup/refreshSimpleCleanupChoice in MainActivity.

    @Test fun `radio=Local with a model installed displays LOCAL from the persisted choice alone`() {
        assertEquals(
            SimpleCleanupChoice.LOCAL,
            displayedCleanupChoice(persisted = SimpleCleanupChoice.LOCAL, pendingLocalSelection = false)
        )
    }

    @Test fun `radio=Local with no model installed still displays LOCAL via the pending override -- the bug's exact repro`() {
        // Deleting the active local model falls the persisted waterfall back to CLOUD (#51), but a
        // tap on "Local" afterward must still reveal the model list to download from -- it can't
        // stay stuck showing CLOUD just because nothing is installed yet.
        assertEquals(
            SimpleCleanupChoice.LOCAL,
            displayedCleanupChoice(persisted = SimpleCleanupChoice.CLOUD, pendingLocalSelection = true)
        )
    }

    @Test fun `radio=Cloud displays CLOUD regardless of any stale pending flag`() {
        assertEquals(
            SimpleCleanupChoice.CLOUD,
            displayedCleanupChoice(persisted = SimpleCleanupChoice.CLOUD, pendingLocalSelection = false)
        )
    }
}
