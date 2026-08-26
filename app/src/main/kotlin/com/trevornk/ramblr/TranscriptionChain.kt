package com.trevornk.ramblr

/**
 * Pure branch decisions for the transcription provider fall-through walk (#H1). The service's
 * `transcribeApi` is callback- and IO-bound and can't be unit-tested directly, so the two
 * decisions that actually govern fall-through live here where a plain JVM test can pin them:
 *
 *  - [precheck]: before any network/inference call, is this candidate usable at all, or should the
 *    walk skip straight to the next one (a cloud provider with no configured credential, a LOCAL
 *    entry whose model hasn't loaded yet, or a not-yet-implemented transcription provider)?
 *  - [hasNextCandidate]: once a candidate has failed (unusable, HTTP error, timeout, or an empty
 *    transcript), is there another candidate to try, or is the chain exhausted?
 *
 * Before #H1 the service only advanced on a blank credential; a real failure from candidate N
 * reset the whole dictation even when candidate N+1 (Gemini, or the on-device LOCAL floor) was
 * configured and healthy. These functions make "failure on candidate N tries candidate N+1" the
 * pinned, tested contract.
 */
object TranscriptionChain {
    enum class Precheck {
        /** Candidate is usable -- make the transcription call. */
        CALL,
        /** Candidate can't run right now -- advance to the next candidate without a call. */
        SKIP,
    }

    fun precheck(kind: ProviderKind, hasCredential: Boolean, localModelLoaded: Boolean): Precheck =
        when (kind) {
            ProviderKind.OPENAI, ProviderKind.GEMINI -> if (hasCredential) Precheck.CALL else Precheck.SKIP
            ProviderKind.LOCAL -> if (localModelLoaded) Precheck.CALL else Precheck.SKIP
            // Not implemented for transcription; capability filtering should have removed these,
            // so this is defensive only -- always skip.
            ProviderKind.ANTHROPIC, ProviderKind.OMNIROUTE -> Precheck.SKIP
        }

    /** True when a candidate after [index] exists in a chain of [candidateCount] candidates. */
    fun hasNextCandidate(index: Int, candidateCount: Int): Boolean = index + 1 < candidateCount

    /**
     * Whether the PCM recording may be deleted after candidate [candidateIndex]'s outcome (M5
     * audit, 2026-08-26). The PCM is the *only* copy of the user's audio: once it's gone, no
     * later candidate can retry, so it must stay alive for exactly as long as the walk might
     * still need it --
     *
     *  - success: the transcript exists, nothing downstream needs audio -- delete.
     *  - failure with a candidate remaining: the next candidate needs the audio -- keep.
     *  - failure on the last candidate: the chain is exhausted, the dictation is over -- delete.
     *
     * Before M5, `transcribeLocal` deleted the PCM in its `finally` unconditionally, so a LOCAL
     * candidate that failed post-load took the audio down with it and a configured cloud
     * candidate after it in the chain had nothing left to transcribe -- the dictation died with
     * "Local error: ..." instead of falling through. This is the pinned contract that prevents
     * that class of bug for every candidate kind.
     */
    fun shouldDeletePcm(candidateIndex: Int, candidateCount: Int, success: Boolean): Boolean =
        success || !hasNextCandidate(candidateIndex, candidateCount)
}
