package com.trevornk.ramblr

import java.util.Locale

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
 * Whether a live streaming partial should render inside the floating feedback bubble instead of
 * writing directly to the real accessibility field (bug fix, live-preview + preview-before-insert
 * interaction). When Preview-before-insert is on, the real field must stay completely untouched
 * until the user explicitly commits (or the preview safety-timeout auto-commits the raw
 * fallback) -- otherwise a discarded/timed-out preview can leave stale streamed text sitting in
 * the target app with nothing left to reconcile it against. When Preview-before-insert is off,
 * this returns false and [WhisperAccessibilityService.maybeInjectPartial] keeps writing straight
 * into the field exactly as it always has.
 */
fun shouldRouteStreamingPartialToBubble(previewBeforeInjectEnabled: Boolean): Boolean =
    previewBeforeInjectEnabled

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
    // Locale.ROOT, not the default locale: a Turkish device would otherwise lowercase 'I' to the
    // dotless 'ı' (and uppercase 'i' to 'İ'), mangling the English live-preview string (L12).
    val chars = text.lowercase(Locale.ROOT).toCharArray()
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
 * Resolves a node's real text content, treating placeholder/hint text as empty (#47). When a field
 * is empty and displaying its hint (e.g. "RCS message"), `AccessibilityNodeInfo.getText()` returns
 * the hint string itself, not an empty string -- so every call site that reads "what's currently in
 * this field" to insert relative to must check `isShowingHintText()` first, or the hint gets glued
 * onto the front of the dictated text with no separating space. Single shared choke point for that
 * check so it can't be missed at a new call site.
 *
 * [selectionStart]/[selectionEnd] are a second, independent line of defence added for #140
 * (WhatsApp's "Message" composer). `isShowingHintText()` is only reliable for a placeholder rendered
 * through the framework's own `android:hint` machinery. WhatsApp's composer instead seeds the
 * placeholder as the field's own text: measured on-device it reports `text='Message'` with
 * `isShowingHintText=false` AND `hintText=null`, so neither the #47 check nor any hint comparison
 * can see it. What *does* distinguish it is the text selection: a placeholder has no cursor
 * position inside it, so the node reports `-1/-1`, whereas a field holding real content always
 * reports a real insertion point.
 *
 * Measured on a Pixel 10a across WhatsApp, Google Keep and Chrome (the full matrix is in #140).
 * Placeholder states reported `-1/-1`; every field holding genuine text reported an index >= 0,
 * including the cases most likely to be mistaken for a placeholder:
 *  - a WhatsApp draft restored after navigating away and back  -> `0/0`
 *  - Keep note title/body text in an unfocused node            -> `0/0`
 *  - a web input prefilled via `value=` and programmatically focused, never touched by the
 *    user, so no selection-changed event had ever fired for it -> `0/0`
 *
 * That last case is the specific hazard [resolveInsertionStart] documents below -- selection being
 * unreported until a selection-changed event occurs -- which is why it was tested explicitly rather
 * than assumed. It reports `0`, not `-1`, so it is not mistaken for a placeholder.
 *
 * The rule is deliberately conservative: it requires the node to be editable and focused, and the
 * selection to be entirely absent, before overriding what the field claims to contain. An
 * unfocused node is never blanked by this path, so a stray non-target node holding real text cannot
 * be destroyed by it. If a future app reports no selection for genuine content, the failure mode is
 * that the user's existing text is replaced rather than appended to -- hence the narrow guards.
 *
 * Note for maintainers: every parameter is required on purpose. Defaults here would let a new call
 * site silently opt out of the #140 signals and quietly reinstate the bug; a compile error is the
 * cheaper failure. An earlier attempt at #140 compared `text == hintText` instead. That was
 * based on a UIAutomator dump of Keep's search field showing `text` and `hint` as an identical
 * pair -- but UIAutomator does not expose `isShowingHintText`, and reading the real
 * `AccessibilityNodeInfo` showed Keep already reports `isShowingHintText=true` there. The #47 check
 * had always covered Keep; the hint comparison was redundant, did nothing for WhatsApp (which
 * exposes no hint at all), and risked blanking a genuine draft that happened to equal the
 * placeholder. It was removed in favour of the selection signal above.
 */
fun resolveRealText(
    rawText: String?,
    isShowingHintText: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
    isEditable: Boolean,
    isFocused: Boolean,
): String = when {
    isShowingHintText -> ""
    !rawText.isNullOrBlank() &&
        isEditable &&
        isFocused &&
        selectionStart < 0 &&
        selectionEnd < 0 -> ""
    else -> rawText.orEmpty()
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

/**
 * The span of existing text that a one-shot injection should overwrite: [start] inclusive,
 * [endExclusive] exclusive, ready to hand straight to `String.replaceRange`.
 */
data class ReplacementSpan(val start: Int, val endExclusive: Int)

/**
 * The span that a one-shot (non-streaming) injection should overwrite, for the direct
 * `ACTION_SET_TEXT` path.
 *
 * Start is delegated to [resolveInsertionStart] so this path can't disagree with the streaming path
 * about where the cursor really is -- they previously did, and a WhatsApp draft restored by leaving
 * and re-entering a chat (which reports `(0, 0)` with the caret visibly at the end) had dictation
 * prepended rather than appended (#140).
 *
 * The end preserves a genuine ranged selection, so dictating over highlighted text still replaces
 * it, which [resolveInsertionStart] alone would lose by collapsing to a single insertion point.
 * When the start was overridden as unreliable, the span collapses to a pure insertion -- deleting a
 * range computed from a selection we just declared untrustworthy would risk destroying real text.
 * Both ends are clamped into `0..currentTextLength`, because a node can report a stale index past
 * the end of its own current text and `replaceRange` would otherwise throw uncaught, taking down
 * the whole accessibility service.
 */
fun resolveReplacementSpan(selStart: Int, selEnd: Int, currentTextLength: Int): ReplacementSpan {
    val start = resolveInsertionStart(selStart, selEnd, currentTextLength)
    val trusted = selStart >= 0 && selEnd >= 0 && start == minOf(selStart, selEnd)
    val end = if (trusted) maxOf(selStart, selEnd) else start
    val safeStart = start.coerceIn(0, currentTextLength)
    val safeEnd = end.coerceIn(safeStart, currentTextLength)
    return ReplacementSpan(safeStart, safeEnd)
}
