package com.trevornk.ramblr

/**
 * Where dictated text ends up.
 *
 * Today the only implementation is [AccessibilityTextDestination], which drives an
 * `AccessibilityNodeInfo` in whatever app the user is dictating into. The point of the interface is
 * that it names exactly what the dictation pipeline needs of a target field -- read the real text,
 * read the caret, replace the content, or fall back to a paste -- without naming *how* that target
 * is reached. A future IME (#143) reaching the same field through `InputConnection` is then a second
 * implementation rather than a second copy of the write logic.
 *
 * **Deliberately free of accessibility semantics.** No method exposes a node, a window, an
 * `AccessibilityAction`, or an accessibility action id, because an `InputConnection`-backed
 * implementation could not satisfy those. Every member below maps onto plain `InputConnection`
 * calls; the mapping is spelled out per-method so a future IME author can check it, and so a later
 * change that quietly reintroduces a node-shaped concept is obvious in review.
 *
 * Every member is deliberately a function rather than a property: a target field's text, selection
 * and editability are all live reads that change as the app reacts, and the write sequence in
 * [DictationTextWriter] depends on reading them at specific points (notably *after*
 * [prepareForWrite]). A `val` would invite a caller to snapshot them once and silently reintroduce
 * the caret bugs fixed in #42/#140.
 */
interface TextDestination {
    /**
     * True when the destination can accept a whole-field replacement. False means only
     * [pasteFromClipboard] can deliver text here. Read live, after [prepareForWrite], because taking
     * focus can change what a target reports.
     *
     * Accessibility: the node is editable / EditText-like, so `ACTION_SET_TEXT` will work.
     * IME: constant `true` whenever an `InputConnection` is bound -- an IME is only ever attached to
     * a field that accepts text.
     */
    fun acceptsDirectWrite(): Boolean

    /**
     * Re-reads the destination's current state from its underlying source, returning false when it
     * has gone stale and must not be written to. Callers that hold a destination across a delay
     * (the streaming-preview handoff, #45) must refresh before trusting [readText].
     *
     * Accessibility: `AccessibilityNodeInfo.refresh()`.
     * IME: whether the current `InputConnection` is still non-null and still bound to the same
     * input target (`getExtractedText` returning non-null is the usual liveness probe).
     */
    fun refresh(): Boolean

    /**
     * The destination's real current content, with placeholder/hint text resolved away (#47, #140).
     * Never null: a field showing only a placeholder reads as an empty string, which is what the
     * insertion-composition helpers expect.
     *
     * Accessibility: [resolveRealText] over the node's text plus its hint/selection signals.
     * IME: `getExtractedText(ExtractedTextRequest(), 0).text` (or `getTextBeforeCursor` +
     * `getTextAfterCursor`). An IME never sees hint text as content, so it needs no equivalent of
     * the placeholder heuristics -- it simply returns what the connection reports.
     */
    fun readText(): String

    /**
     * Caret/selection anchor, in the coordinate space of [readText]. Negative when unreported.
     *
     * Accessibility: `AccessibilityNodeInfo.getTextSelectionStart()`.
     * IME: `ExtractedText.selectionStart`, or the value last delivered to `onUpdateSelection`.
     */
    fun selectionStart(): Int

    /**
     * Caret/selection end, in the coordinate space of [readText]. Negative when unreported.
     *
     * Accessibility: `AccessibilityNodeInfo.getTextSelectionEnd()`.
     * IME: `ExtractedText.selectionEnd`, or the value last delivered to `onUpdateSelection`.
     */
    fun selectionEnd(): Int

    /**
     * Best-effort preparation of the destination to receive a write. Must be safe to call when
     * nothing needs doing.
     *
     * Accessibility: `ACTION_FOCUS`, since dictation writes into a field the user may not have
     * focused.
     * IME: a no-op (the field is already the IME's input target), or `beginBatchEdit()`.
     */
    fun prepareForWrite()

    /**
     * Replaces the destination's *entire* content with [text]. Returns false when the write failed.
     *
     * This is whole-field replacement, not an append: [DictationTextWriter] has already composed the
     * complete new value, precisely so the caret and separator rules (#42/#140/#144) live in one
     * pure, unit-tested place instead of being re-derived per destination.
     *
     * Accessibility: `ACTION_SET_TEXT` with the full string.
     * IME: `setSelection(0, currentLength)` followed by `commitText(text, 1)` -- or equivalently
     * `deleteSurroundingText(before, after)` then `commitText` -- inside a
     * `beginBatchEdit()`/`endBatchEdit()` pair so the target app sees one atomic edit.
     */
    fun replaceAllText(text: String): Boolean

    /**
     * Asks the destination to pull the current clipboard contents into itself. Used only when
     * [acceptsDirectWrite] is false or a direct write failed. Returns false when no paste route
     * worked.
     *
     * Accessibility: a labelled custom paste action, else `ACTION_PASTE`.
     * IME: `performContextMenuAction(android.R.id.paste)`.
     */
    fun pasteFromClipboard(): Boolean
}

/**
 * Outcome of one write attempt against a [TextDestination]. [priorText] is the destination's full
 * content *before* replacement, captured only on the [InjectMethod.DIRECT] path -- that is the only
 * case where undo (#27) can restore it.
 */
data class TextCommitResult(val method: InjectMethod, val priorText: String? = null)

/**
 * The destination-agnostic half of text delivery: given a [TextDestination] and the text to land,
 * runs the exact write sequence dictation has always used, with no Android or AccessibilityService
 * types anywhere in sight. Extracted verbatim out of `WhisperAccessibilityService` so it can be
 * exercised in plain JVM unit tests and reused by a future IME input surface (#143).
 *
 * All ordering here is load-bearing and behaviour-preserving; see the individual functions.
 */
object DictationTextWriter {

    /**
     * The ordinary one-shot delivery of a finished transcript into [destination].
     *
     * Direct write is tried before paste (#111): an ordinary editable field supports a whole-field
     * replacement with no clipboard round-trip, which also avoids Android 12+'s "X pasted from your
     * clipboard" system toast sitting over the just-injected text. Paste is reserved for
     * destinations that don't accept a direct write (some Compose fields report non-editable yet
     * still expose a working paste action) or where the direct write failed.
     *
     * [TextDestination.prepareForWrite] runs first, and the text/selection reads happen after it,
     * because the destination can report a different caret once focused. Composition of the new
     * value is delegated to [composeOneShotInjection] so this path and the streaming path cannot
     * disagree about where the caret really is (#140) or about the separator before dictated text
     * (#144).
     */
    fun commit(destination: TextDestination, text: String): TextCommitResult {
        destination.prepareForWrite()

        if (destination.acceptsDirectWrite()) {
            val current = destination.readText()
            val updated = composeOneShotInjection(
                current,
                destination.selectionStart(),
                destination.selectionEnd(),
                text,
            )
            if (destination.replaceAllText(updated)) {
                return TextCommitResult(InjectMethod.DIRECT, priorText = current)
            }
        }

        if (destination.pasteFromClipboard()) return TextCommitResult(InjectMethod.FROM_CLIPBOARD)

        return TextCommitResult(InjectMethod.NONE)
    }

    /**
     * Delivery of a finished transcript into the *same* destination a streaming-preview session
     * (#29) was already writing partials into. The tracked [span] is closed out with [text] instead
     * of an independent, caret-based insert, so the streaming leftover is replaced rather than left
     * concatenated alongside the final transcript (#45).
     *
     * There is no prepare step and no paste fallback here on purpose: this destination was already
     * being written to directly this recording, and a paste would append next to the leftover the
     * whole reconciliation exists to remove. A stale destination or a span that cannot be
     * reconciled yields [InjectMethod.NONE] so the caller falls through to its next candidate.
     */
    fun commitClosingStreamingSpan(
        destination: TextDestination,
        span: StreamingSpan,
        text: String,
    ): TextCommitResult {
        if (!destination.refresh()) return TextCommitResult(InjectMethod.NONE)
        val current = destination.readText()
        val updated = reconcileStreamingSpan(current, span, text, isFinalInjectionTarget = true)
            ?: return TextCommitResult(InjectMethod.NONE)
        return if (destination.replaceAllText(updated)) {
            TextCommitResult(InjectMethod.DIRECT, priorText = current)
        } else {
            TextCommitResult(InjectMethod.NONE)
        }
    }

    /**
     * Reverts a streaming-preview session's leftover partial when the final text landed somewhere
     * else (#45, e.g. focus moved after recording stopped) -- otherwise that fragment is left
     * silently orphaned in a field nobody is about to overwrite. Best-effort: a destination that has
     * gone stale by now simply has nothing left to revert.
     */
    fun clearStreamingSpan(destination: TextDestination, span: StreamingSpan) {
        if (!destination.refresh()) return
        val current = destination.readText()
        val cleared = reconcileStreamingSpan(
            current,
            span,
            finalText = "",
            isFinalInjectionTarget = false,
        ) ?: return
        destination.replaceAllText(cleared)
    }
}
