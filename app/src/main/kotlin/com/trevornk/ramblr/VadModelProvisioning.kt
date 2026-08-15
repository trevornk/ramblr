package com.trevornk.ramblr

/**
 * Decides whether a local transcription should provision the Silero VAD model (#139).
 *
 * ## Why this exists
 *
 * [WhisperAccessibilityService.transcribeLocal] picks its decode strategy purely on whether
 * `silero_vad.onnx` is on disk: present means [LocalTranscriber.transcribeSegmented] (the #132
 * OOM fix, which bounds peak memory to the longest utterance), absent means a single-shot
 * unsegmented native decode of the whole recording — the 1.98 GB allocation that got Ramblr and
 * 11 other apps killed by lowmemorykiller on a 4 GB device in #132.
 *
 * Before #139 the only code path that ever downloaded that model was enabling silence auto-stop
 * (#108) in Behavior settings, an unrelated opt-in convenience feature. Anyone doing local
 * transcription who never opened that toggle had no `vad_models/` directory at all, so the #132
 * fix was permanently inert for them — invisible on a high-RAM device, and a lost dictation on a
 * low-RAM one. Gating a memory-safety fix behind an unrelated UX toggle was the bug.
 *
 * ## What this does not change
 *
 * The unsegmented fallback stays exactly as it was. Provisioning is asynchronous (WorkManager,
 * network-constrained), so the take that triggers the fetch still decodes unsegmented, as does
 * every take while the device is offline or the download is failing. Making a missing model fatal
 * would trade a rare OOM for "local transcription is broken", which is strictly worse. This only
 * ensures the model eventually arrives for the users who actually need it.
 */
object VadModelProvisioning {

    /**
     * True when a local transcription should enqueue the VAD download.
     *
     * [downloadInFlight] is not a correctness requirement — [ModelDownloadWorker.enqueue] uses
     * unique work with `ExistingWorkPolicy.KEEP`, so a duplicate enqueue is already collapsed by
     * WorkManager — but short-circuiting avoids hitting WorkManager on every single dictation
     * during the download window.
     */
    fun shouldFetch(modelInstalled: Boolean, downloadInFlight: Boolean): Boolean =
        !modelInstalled && !downloadInFlight
}
