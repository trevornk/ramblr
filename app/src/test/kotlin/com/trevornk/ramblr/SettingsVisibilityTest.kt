package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVisibilityTest {

    // --- shouldShowOpenAiKeyRowForTranscription (#93) ---
    // The transcription screen shows its own contextual copy of the key row.

    @Test fun `transcription-scoped row shows only when transcription itself is cloud`() {
        assertTrue(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = false))
        assertFalse(shouldShowOpenAiKeyRowForTranscription(useLocalTranscription = true))
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
