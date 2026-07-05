package com.kafkasl.phonewhisper

/**
 * Decision logic for the one-time consent dialog shown when local (on-device) transcription and
 * *network* cleanup are combined: cleanup that talks to a cloud provider sends the transcript
 * off-device, which silently breaks the "local = nothing leaves my phone" expectation unless the
 * user is told once. See #23.
 *
 * A LOCAL_LLM-only waterfall (#37) never sends a byte anywhere, so it must not trigger this
 * prompt (#65) — before that input existed, a fully-on-device configuration showed a false
 * "Cleanup sends text off-device" warning in Settings and blocked the overlay style menu's
 * cleanup toggle behind consent for egress that would never happen.
 */
object LocalCleanupConsent {
    fun shouldPrompt(
        useLocal: Boolean,
        usePostProcessing: Boolean,
        hasConsented: Boolean,
        cleanupIsLocalOnly: Boolean,
    ): Boolean = useLocal && usePostProcessing && !hasConsented && !cleanupIsLocalOnly
}
