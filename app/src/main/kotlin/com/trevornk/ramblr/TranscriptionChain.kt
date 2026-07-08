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
}
