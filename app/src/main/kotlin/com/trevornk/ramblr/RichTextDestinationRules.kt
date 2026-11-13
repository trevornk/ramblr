package com.trevornk.ramblr

/**
 * Detects fields where replacing the whole content (`ACTION_SET_TEXT`) is destructive (#376).
 *
 * Outlook's mail body is a rich-text editor hosted in a WebView. A whole-field write there throws
 * away what a flat string cannot carry (line breaks, formatting, signature, quoted thread) -- whether
 * the loss happens because the read already flattened newlines or because the editor normalises the
 * written string. Such a field has to be written by inserting at the caret instead.
 *
 * The signal is structural, never a package name. Chromium exposes an `<input>`/`<textarea>` as a
 * childless, atomic field (a flat string *is* its whole model, so SET_TEXT is lossless), whereas a
 * `contenteditable` root has descendant nodes (paragraphs, runs, line breaks). Native `EditText` and
 * Compose fields have no accessibility children either, so the WebView-ancestor requirement keeps
 * them out even if a custom view happens to expose children.
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
    childCount: Int,
    currentTextIsEmpty: Boolean,
): Boolean = isEditable && inWebView && childCount > 0 && !currentTextIsEmpty
