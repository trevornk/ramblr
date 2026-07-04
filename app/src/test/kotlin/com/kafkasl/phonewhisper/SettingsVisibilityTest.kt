package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVisibilityTest {

    @Test fun `cloud transcription always needs the key regardless of cleanup`() {
        assertTrue(shouldShowOpenAiKeyRow(useLocalTranscription = false, cleanupEnabled = false, cleanupChoice = SimpleCleanupChoice.LOCAL))
        assertTrue(shouldShowOpenAiKeyRow(useLocalTranscription = false, cleanupEnabled = true, cleanupChoice = SimpleCleanupChoice.LOCAL))
    }

    @Test fun `local transcription with cleanup off hides the key`() {
        assertFalse(shouldShowOpenAiKeyRow(useLocalTranscription = true, cleanupEnabled = false, cleanupChoice = SimpleCleanupChoice.LOCAL))
    }

    @Test fun `local transcription with local cleanup hides the key`() {
        assertFalse(shouldShowOpenAiKeyRow(useLocalTranscription = true, cleanupEnabled = true, cleanupChoice = SimpleCleanupChoice.LOCAL))
    }

    @Test fun `local transcription with cloud cleanup shows the key`() {
        assertTrue(shouldShowOpenAiKeyRow(useLocalTranscription = true, cleanupEnabled = true, cleanupChoice = SimpleCleanupChoice.CLOUD))
    }

    @Test fun `local transcription with custom cleanup waterfall shows the key`() {
        assertTrue(shouldShowOpenAiKeyRow(useLocalTranscription = true, cleanupEnabled = true, cleanupChoice = SimpleCleanupChoice.CUSTOM))
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
