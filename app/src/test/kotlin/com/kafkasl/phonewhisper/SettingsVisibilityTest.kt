package com.kafkasl.phonewhisper

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
}
