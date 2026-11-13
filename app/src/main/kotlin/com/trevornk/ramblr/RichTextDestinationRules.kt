package com.trevornk.ramblr

/**
 * Detects fields where replacing the whole content (`ACTION_SET_TEXT`) is destructive (#376).
 *
 * Outlook's mail body is a rich-text editor hosted in a WebView. A whole-field write there throws
 * away what a flat string cannot carry (line breaks, formatting, signature, quoted thread) -- whether
 * the loss happens because the read already flattened newlines or because the editor normalises the
 * written string. Such a field has to be written by inserting at the caret instead.
 *
 * The signal is structural, never a package name: an editable, multi-line field under a WebView.
 * Child nodes are NOT usable for this: on real devices (Outlook, Gmail) Chromium exposes the
 * contenteditable mail body as a childless multi-line `EditText` whose read is newline-flattened.
 * A multi-line WebView field that is really a `<textarea>` also takes the caret paste, which is a
 * correct (lossless) insertion there too; the only cost is the clipboard round-trip, whose safety
 * is tracked separately (#378). Single-line WebView inputs, native fields (no WebView ancestor)
 * and empty composers keep the direct write.
 *
 * An EMPTY field has nothing a whole-field write could destroy, so empty WebView composers (Sable
 * and other web chat boxes, the common case) keep the established direct-write behaviour.
 * [currentTextIsEmpty] must come from the RAW node values (`isShowingHintText || text.isNullOrBlank()`),
 * NOT from [resolveRealText]: that one also blanks a focused editable reporting selection -1/-1 (the
 * #140 placeholder heuristic for native EditTexts), which would make a populated contenteditable look
 * empty and let a single SET_TEXT wipe the whole body. Chromium reports an empty composer as `"\n"`,
 * which is blank and so still counts as empty.
 */
fun isLossyWholeFieldDestination(
    isEditable: Boolean,
    inWebView: Boolean,
    isMultiLine: Boolean,
    currentTextIsEmpty: Boolean,
): Boolean = isEditable && inWebView && isMultiLine && !currentTextIsEmpty
