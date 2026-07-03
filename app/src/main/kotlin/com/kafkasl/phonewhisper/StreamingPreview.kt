package com.kafkasl.phonewhisper

/**
 * Whether a new partial hypothesis from the streaming recognizer should be pushed into the
 * focused field right now (#29). Injecting on every audio chunk would hammer the target app's
 * input field and feel janky, so updates are throttled to at most one per [minIntervalMs] --
 * except the very first partial of a recording ([previousText] null), which fires immediately so
 * live preview doesn't feel laggy at speech onset.
 */
fun shouldInjectPartial(
    text: String,
    previousText: String?,
    lastInjectedAtMs: Long,
    nowMs: Long,
    minIntervalMs: Long
): Boolean {
    if (text.isBlank() || text == previousText) return false
    if (previousText == null) return true
    return nowMs - lastInjectedAtMs >= minIntervalMs
}

/**
 * Computes a text field's new contents when the streaming preview's last partial ([previousLength]
 * chars starting at [insertionStart]) is replaced by [newText] -- a plain substring swap, not an
 * append, since each streaming update revises the whole utterance hypothesis so far rather than
 * just adding to it (a streaming recognizer can and does rewrite earlier words as more audio
 * arrives). [insertionStart] and the replaced span are clamped to [current]'s bounds so a field
 * that changed out from under the session (e.g. an unrelated external edit) degrades to inserting
 * at the nearest valid point instead of throwing.
 */
fun replacePartialInField(current: String, insertionStart: Int, previousLength: Int, newText: String): String {
    val start = insertionStart.coerceIn(0, current.length)
    val end = (start + previousLength).coerceIn(start, current.length)
    return current.replaceRange(start, end, newText)
}

/**
 * Whether the streaming live-preview path should be active: both the explicit opt-in setting and
 * a fully-installed streaming model are required, checked fresh at load time so a model deleted
 * after being enabled just silently falls back to no preview (#29's "cleanly disabled" acceptance
 * criterion) instead of crashing on a missing file. The offline/cloud batch path is never gated by
 * this -- it's independent and always available regardless of the outcome here.
 */
fun shouldUseStreamingPreview(settingEnabled: Boolean, streamingModelInstalled: Boolean): Boolean =
    settingEnabled && streamingModelInstalled
