package com.kafkasl.phonewhisper

/**
 * Whether the shared OpenAI API key row should be shown at all (#49). The key is read by two
 * independent consumers -- cloud transcription, and the "Cloud" simple cleanup choice (the LEGACY
 * waterfall step) -- so it can't collapse under either feature's own Local/Cloud toggle alone: it
 * stays visible whenever *either* consumer actually needs it, and only hides when neither does.
 */
fun shouldShowOpenAiKeyRow(
    useLocalTranscription: Boolean,
    cleanupEnabled: Boolean,
    cleanupChoice: SimpleCleanupChoice
): Boolean {
    if (!useLocalTranscription) return true
    return cleanupEnabled && cleanupChoice != SimpleCleanupChoice.LOCAL
}
