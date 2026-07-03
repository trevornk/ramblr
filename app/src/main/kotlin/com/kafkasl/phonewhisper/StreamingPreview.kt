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

/**
 * Formats a raw partial hypothesis for display only (#42). The bundled streaming model
 * (`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, see [StreamingTranscriber]) is trained
 * purely on LibriSpeech, whose recipes commonly use an uppercase-only, unpunctuated token
 * vocabulary -- so raw partials come back as unbroken ALL CAPS. This lowercases everything, then
 * capitalizes the first letter of the string and of each sentence following ". "/"! "/"? ". Purely
 * a display transform for the live-preview string: never re-fed into the recognizer, never applied
 * to the final batch-injected transcript. Kept allocation-light since it runs on every throttled
 * partial update.
 */
fun smartCapitalize(text: String): String {
    if (text.isEmpty()) return text
    val chars = text.lowercase().toCharArray()
    var capitalizeNext = true
    for (i in chars.indices) {
        val c = chars[i]
        if (capitalizeNext && c.isLetter()) {
            chars[i] = c.uppercaseChar()
            capitalizeNext = false
        }
        if ((c == '.' || c == '!' || c == '?') && i + 1 < chars.size && chars[i + 1] == ' ') {
            capitalizeNext = true
        }
    }
    return String(chars)
}

/**
 * A streaming-preview session's tracked span (#29), stripped of its Android/node dependency so the
 * final-injection handoff decision (#45) can be unit tested in isolation. [WhisperAccessibilityService]
 * builds one of these from its own (node-holding) `StreamingPreviewSession` right before reconciling.
 */
data class StreamingSpan(val insertionStart: Int, val previousLength: Int)

/**
 * What a field the streaming-preview session (#29) was tracking should become now that the final
 * batch transcript is ready (#45). Returns null when [session] is null -- streaming preview never
 * ran (or never injected a partial) this recording, so there's nothing to reconcile and the final
 * injection's existing selection-based path must run completely unmodified.
 *
 * When [isFinalInjectionTarget] is true, the node in question is the exact one the final text is
 * about to land in, so its tracked span is closed out with [finalText] -- replacing it outright
 * (see [replacePartialInField]) rather than leaving the final injection to compute an independent,
 * selection-based insertion point that has no knowledge the span exists. This is what guarantees the
 * streaming leftover never survives concatenated alongside the final text.
 *
 * When false, the final injection is landing in a *different* node (focus moved after recording
 * stopped) -- so this node's tracked span is instead reverted (replaced with nothing) so it isn't
 * left silently orphaned in a field nobody is about to overwrite.
 */
fun reconcileStreamingSpan(
    current: String,
    session: StreamingSpan?,
    finalText: String,
    isFinalInjectionTarget: Boolean
): String? {
    if (session == null) return null
    val replacement = if (isFinalInjectionTarget) finalText else ""
    return replacePartialInField(current, session.insertionStart, session.previousLength, replacement)
}

/**
 * Decides where the very first partial of a recording should be inserted (#42). Many Android
 * EditText/keyboard implementations don't reliably report selection state via
 * `AccessibilityNodeInfo` until a real selection-changed event has fired for that field, and can
 * report `(0, 0)` even when the visible cursor is actually at the end of existing text -- the
 * common case of tapping the mic to continue dictating after an existing draft. A negative
 * [selStart]/[selEnd] (unreported) and an exact `(0, 0)` report against non-empty existing text are
 * both treated as unreliable and fall back to [currentTextLength] (end of the field); any other
 * selection is trusted as a genuine, explicit cursor placement and used as-is.
 */
fun resolveInsertionStart(selStart: Int, selEnd: Int, currentTextLength: Int): Int {
    if (selStart < 0 || selEnd < 0) return currentTextLength
    if (selStart == 0 && selEnd == 0 && currentTextLength > 0) return currentTextLength
    return minOf(selStart, selEnd)
}
