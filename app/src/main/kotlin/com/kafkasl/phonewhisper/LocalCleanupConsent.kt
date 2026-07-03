package com.kafkasl.phonewhisper

/**
 * Decision logic for the one-time consent dialog shown when local (on-device) transcription and
 * cleanup are both enabled. Cleanup always sends the transcript to [PostProcessor.DESTINATION_HOST],
 * which silently breaks the "local = nothing leaves my phone" expectation unless the user is told
 * once. See #23.
 */
object LocalCleanupConsent {
    fun shouldPrompt(useLocal: Boolean, usePostProcessing: Boolean, hasConsented: Boolean): Boolean =
        useLocal && usePostProcessing && !hasConsented
}
