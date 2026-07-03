package com.kafkasl.phonewhisper

/**
 * What "undo last insertion" should do, decided from plain state (see #27). Node validity is
 * checked by the caller (AccessibilityNodeInfo.refresh() plus a same-package check) and passed in
 * as [nodeAvailable] so this stays free of Android types and fully unit-testable.
 */
sealed class UndoPlan {
    /** The undo window has passed; nothing to do. */
    object Expired : UndoPlan()
    /** Nothing was captured, or neither the node nor a changed clipboard is available to restore. */
    object Unavailable : UndoPlan()
    /** The target node is still reachable — restore its prior text directly. */
    data class RestoreInPlace(val priorNodeText: String) : UndoPlan()
    /** The node can't be restored in place; best-effort fallback copies the prior clipboard back. */
    data class ClipboardOnly(val priorClipboard: String) : UndoPlan()
}

/**
 * Decides the undo action for the last injection. In-place restore is preferred whenever the node
 * is still available; otherwise this falls back to the clipboard, which is always a partial-but-real
 * win even when the target app doesn't expose old text (e.g. paste-only injections).
 */
fun planUndo(
    ageMs: Long,
    windowMs: Long,
    nodeAvailable: Boolean,
    priorNodeText: String?,
    priorClipboard: String?
): UndoPlan = when {
    ageMs > windowMs -> UndoPlan.Expired
    nodeAvailable && priorNodeText != null -> UndoPlan.RestoreInPlace(priorNodeText)
    priorClipboard != null -> UndoPlan.ClipboardOnly(priorClipboard)
    else -> UndoPlan.Unavailable
}

/**
 * Whether "retry with raw text" should be offered: only within the recovery window, and only when
 * cleanup actually changed the text (otherwise raw and injected are identical and there's nothing
 * to retry with).
 */
fun canRetryRaw(ageMs: Long, windowMs: Long, rawText: String, injectedText: String): Boolean =
    ageMs <= windowMs && rawText != injectedText

/**
 * Whether to wait and rescan before falling back to the clipboard (see #5). Some apps briefly
 * report no focused/editable node right in the moment the overlay tap hands focus back after
 * stealing it for the gesture; a short delay lets that settle. The caller only ever consults this
 * once per injection — a still-empty rescan means the app doesn't expose an injectable node at all,
 * not a transient focus race, so there's no point looping.
 */
fun shouldRetryEmptyScan(candidateCount: Int): Boolean = candidateCount == 0
