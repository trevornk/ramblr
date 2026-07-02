package com.kafkasl.phonewhisper

import java.util.concurrent.atomic.AtomicInteger

/**
 * Generation counter guarding the transcription pipeline against stale completions. Starting a
 * new transcription, cancelling one, or a watchdog firing all supersede the current token;
 * callbacks (network response, local-transcription result, watchdog check) carrying an old token
 * are then known-stale and must not mutate UI state or inject text. See #20.
 */
class TranscriptionGuard {
    private val generation = AtomicInteger(0)

    /** Starts a new guarded operation; returns its token. */
    fun start(): Int = generation.incrementAndGet()

    /** True if [token] is still the active generation, i.e. its callback/watchdog should act. */
    fun isCurrent(token: Int): Boolean = generation.get() == token

    /** Supersedes the current token so any in-flight callback/watchdog for it becomes stale. */
    fun cancel() {
        generation.incrementAndGet()
    }
}
