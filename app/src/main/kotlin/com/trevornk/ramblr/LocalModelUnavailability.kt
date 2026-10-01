package com.trevornk.ramblr

import android.content.ComponentCallbacks2

/**
 * #280: which trim levels actually warrant dropping the native transcription recognizers.
 *
 * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] is a visibility signal, not memory pressure: it
 * fires whenever this process's activities leave the screen. Releasing the recognizers on it
 * made the next dictation race a cold reload and fail with a misleading "still downloading".
 */
object TranscriberTrimPolicy {
    fun shouldReleaseTranscribers(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
}

/** #280: honest reason the on-device transcriber is unavailable for this dictation. */
object LocalModelUnavailability {
    const val DOWNLOADING = "Local model still downloading — try again once it finishes"
    const val NOT_INSTALLED = "No on-device model installed — choose one in Ramblr's Transcription settings"
    const val FAILED_TO_LOAD = "On-device model failed to load — try again, or re-select it in Transcription settings"

    fun message(installed: Boolean, downloadInFlight: Boolean): String = when {
        installed -> FAILED_TO_LOAD
        downloadInFlight -> DOWNLOADING
        else -> NOT_INSTALLED
    }
}
