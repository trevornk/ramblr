package com.trevornk.ramblr

/**
 * Whether the on-device batch recognizer ([LocalTranscriber]) is *preloaded* (#310). It is a
 * ~0.8-1.1 GB native resident for the larger models, so a setup that is not on-device-first must
 * not pay for it at accessibility-service connect / IME show.
 *
 * Only `use_local == true` preloads. A [ProviderKind.LOCAL] entry in a cloud-first provider chain
 * is deliberately *not* a reason: it is only reached once every cloud candidate has failed
 * (typically offline), and with the 5-minute idle unload (#307) a preload would only cover the
 * first minutes after a connect while holding ~1 GB the whole time. Such a fallback loads on
 * demand instead, via [DictationRuntime.awaitLocalTranscriber] (#280), which bypasses this gate
 * (it calls `initLocalModelLocked` directly): the cost is a cold load on the first fallback,
 * never a failed dictation. `allow_cloud_fallback` and `allow_local_fallback` are irrelevant here.
 */
internal object BatchRecognizerPolicy {
    /** Preference keys the answer depends on; a change to any of them re-evaluates the preload. */
    val DEPENDENT_PREF_KEYS: Set<String> = setOf("use_local")

    fun canBeUsed(useLocal: Boolean): Boolean = useLocal
}
