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
 *
 * ## Why first use is not early enough (#169)
 *
 * [shouldFetch] alone still leaves one guaranteed-bad take. Because the fetch is asynchronous,
 * *the very dictation that triggers it* always decodes unsegmented — on a fresh install that is
 * every new user's first long take, measured by an F-Droid reviewer at 9.3 s and 665 MB RSS for
 * 47 s of audio on a 4 GB device, versus 325 ms once the model is present. First use is not a
 * rare edge; it is universal, and it lands hardest on exactly the low-RAM devices #132 was filed
 * for.
 *
 * [shouldPrefetchForLocalMode] closes that window by moving provisioning earlier, to the moment
 * the user *chooses* on-device transcription (onboarding, or the Transcription settings toggle).
 * The download then overlaps setup — while the user is still granting permissions and picking an
 * ASR model, which already involves a far larger download — instead of racing their first
 * dictation. [shouldFetch] deliberately stays as the backstop for anyone who selected local mode
 * while offline, or upgraded from a build that predates this.
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

    /**
     * True when selecting on-device transcription should provision the VAD model up front (#169),
     * so the first dictation already has it and decodes segmented.
     *
     * Deliberately does *not* take a `downloadInFlight` argument, unlike [shouldFetch]. The call
     * sites are UI callbacks on the main thread, where reading WorkManager state means a blocking
     * `.get()` that [WhisperAccessibilityService.vadDownloadStateFor] explicitly documents as
     * background-thread-only. `ExistingWorkPolicy.KEEP` already collapses duplicate enqueues, so
     * an installed-only check is both sufficient and the cheap one — a filesystem stat rather
     * than a cross-process query.
     *
     * [localTranscriptionSelected] gates the whole thing: a cloud-only user should never pay for
     * a download they will never use.
     */
    fun shouldPrefetchForLocalMode(
        localTranscriptionSelected: Boolean,
        modelInstalled: Boolean,
    ): Boolean = localTranscriptionSelected && !modelInstalled
}
