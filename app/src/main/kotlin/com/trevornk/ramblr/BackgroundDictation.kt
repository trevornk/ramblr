package com.trevornk.ramblr

/**
 * #284: pure, Android-free decisions behind "keep dictating when the user switches apps".
 * See docs/adr/0002-background-transcription.md for the mechanism, the design, and why the
 * decisions live here rather than inline in the two hosts: every branch below is one the daily
 * stay-in-the-field path must never take, and a pure function is the cheapest way to pin that.
 */

/** Where a finished dictation should go, given whether the field it was dictated into survives. */
internal enum class BackgroundDelivery {
    /** The original target is still focused/valid: behave exactly as before #284. */
    INSERT,

    /** The target is gone. Never type into whatever is focused now (wrong-app write); put the
     *  text on the clipboard, keep the history row, and tell the user. */
    CLIPBOARD_AND_NOTIFY,

    /** The target is gone AND an exclusion (#256) applies: the clipboard is a hop into the
     *  excluded app too, so nothing is copied. History (if allowed) + notice only. */
    NOTIFY_ONLY_EXCLUDED,

    /** The target is gone AND the source field asked for no retention (IME no-learning editors):
     *  the text is not copied or saved anywhere; the notice says so. */
    NOTIFY_ONLY_NOT_RETAINED,
}

/**
 * @param targetCurrent the field the dictation was captured against is still the delivery target
 * @param excluded an exclusion-list app is involved (the foreground app, or the source editor)
 * @param retentionAllowed the source field permits keeping dictated text (history/clipboard)
 */
internal fun backgroundDeliveryFor(
    targetCurrent: Boolean,
    excluded: Boolean,
    retentionAllowed: Boolean = true,
): BackgroundDelivery = when {
    // The pre-#284 funnel does its own exclusion check when it inserts; do not duplicate it.
    targetCurrent -> BackgroundDelivery.INSERT
    excluded -> BackgroundDelivery.NOTIFY_ONLY_EXCLUDED
    !retentionAllowed -> BackgroundDelivery.NOTIFY_ONLY_NOT_RETAINED
    else -> BackgroundDelivery.CLIPBOARD_AND_NOTIFY
}

/**
 * A failure notification is only for work the user can no longer see. While the target is current
 * the host already shows its own toast/overlay message, so adding a notification would be new
 * noise on the protected daily-driver path.
 */
internal fun shouldNotifyBackgroundFailure(targetCurrent: Boolean): Boolean = !targetCurrent

/** What the user is told. [opensHistory] picks the tap target (history viewer vs main screen). */
internal data class BackgroundNotice(val title: String, val text: String, val opensHistory: Boolean)

/**
 * Notice for a finished dictation whose target is gone. [historySaved] must reflect what actually
 * happened (history can be switched off, and a no-retention editor never writes it) so the notice
 * never claims a history entry that doesn't exist. No transcript text, ever: notifications can be
 * shown on a locked screen and live in the system's notification log (the function does not even
 * take the text).
 */
internal fun deliveredNoticeFor(
    delivery: BackgroundDelivery,
    historySaved: Boolean,
    cleanupFailed: Boolean,
): BackgroundNotice? {
    val cleanup = if (cleanupFailed) " Cleanup failed, so this is the raw transcript." else ""
    return when (delivery) {
        BackgroundDelivery.INSERT -> null
        BackgroundDelivery.CLIPBOARD_AND_NOTIFY -> BackgroundNotice(
            title = "Dictation finished",
            text = (if (historySaved) "The field you dictated into is gone. Your text is on the clipboard and in Ramblr history."
            else "The field you dictated into is gone. Your text is on the clipboard.") + cleanup,
            opensHistory = historySaved,
        )
        BackgroundDelivery.NOTIFY_ONLY_EXCLUDED -> BackgroundNotice(
            title = "Dictation finished",
            text = (if (historySaved) "Ramblr is excluded in the app involved, so nothing was inserted or copied. Your text is in Ramblr history."
            else "Ramblr is excluded in the app involved, so nothing was inserted, copied or saved.") + cleanup,
            opensHistory = historySaved,
        )
        BackgroundDelivery.NOTIFY_ONLY_NOT_RETAINED -> BackgroundNotice(
            title = "Dictation finished",
            text = "The field you dictated into is gone and asked not to keep dictated text, so nothing was copied or saved.$cleanup",
            opensHistory = false,
        )
    }
}

/** Coarse failure class. Deliberately not the raw error: provider error bodies can be long and
 *  can echo request details, and a notification is a poor place for diagnostics. */
enum class BackgroundFailure { NO_SPEECH, TIMED_OUT, FAILED }

internal fun failureNoticeFor(failure: BackgroundFailure): BackgroundNotice = when (failure) {
    BackgroundFailure.NO_SPEECH -> BackgroundNotice(
        title = "Dictation heard no speech",
        text = "Ramblr finished in the background but didn't detect any speech.",
        opensHistory = false,
    )
    BackgroundFailure.TIMED_OUT -> BackgroundNotice(
        title = "Dictation timed out",
        text = "Transcription didn't finish in time and was stopped. Open Ramblr and try again.",
        opensHistory = false,
    )
    BackgroundFailure.FAILED -> BackgroundNotice(
        title = "Dictation failed",
        text = "Ramblr couldn't transcribe your recording in the background. The audio isn't kept; open Ramblr and dictate again.",
        opensHistory = false,
    )
}

/**
 * The "target is gone" side effects, shared by both hosts so they cannot drift: clipboard (only
 * for [BackgroundDelivery.CLIPBOARD_AND_NOTIFY]), then one notification. History is the host's job
 * because each host already owns its own history writer; its outcome comes in as [historySaved].
 * Every side effect is individually guarded: a clipboard or notification failure must never break
 * the pipeline's terminal teardown that runs right after.
 */
internal class BackgroundResultSink(
    private val copyToClipboard: (String) -> Unit,
    private val postNotice: (BackgroundNotice) -> Unit,
) {
    fun deliverTargetGone(
        text: String,
        historySaved: Boolean,
        cleanupFailed: Boolean,
        excluded: Boolean,
        retentionAllowed: Boolean = true,
    ): BackgroundDelivery {
        val delivery = backgroundDeliveryFor(targetCurrent = false, excluded = excluded, retentionAllowed = retentionAllowed)
        if (delivery == BackgroundDelivery.CLIPBOARD_AND_NOTIFY) {
            runCatching { copyToClipboard(text) }
        }
        deliveredNoticeFor(delivery, historySaved, cleanupFailed)?.let { runCatching { postNotice(it) } }
        return delivery
    }

    fun failed(failure: BackgroundFailure) {
        runCatching { postNotice(failureNoticeFor(failure)) }
    }
}

/** Packages whose windows transiently take the active window without meaning "the user left"
 *  (notification shade, quick settings). They fail open. */
private val TRANSIENT_SYSTEM_PACKAGES = setOf("com.android.systemui")

/**
 * The package to remember as "where this dictation was started", captured when transcription
 * begins. Null (= fail open, exactly the pre-#284 behavior) when nothing identifiable is in front
 * or a transient system surface is, e.g. dictation stopped from the Quick Settings tile while the
 * shade is still collapsing must not pin SystemUI as the target.
 */
internal fun captureTargetPackage(foregroundPackage: String?, focusedPackage: String?): String? {
    val pkg = focusedPackage ?: foregroundPackage
    if (pkg == null || pkg in TRANSIENT_SYSTEM_PACKAGES) return null
    return pkg
}

/** What the host could tell about the field captured at transcription start. */
internal enum class CapturedFieldState {
    /** Couldn't tell (no captured node, it no longer refreshes, or nothing has input focus now). */
    UNKNOWN,

    /** The input-focused node is the captured node. */
    FOCUSED,

    /** The captured node still exists and is unfocused while a *different* node has input focus. */
    MOVED_AWAY,
}

internal data class DeliveryProbe(
    val capturedPackage: String?,
    val foregroundPackage: String?,
    val focusedPackage: String?,
    val deviceLocked: Boolean,
    val fieldState: CapturedFieldState,
)

/**
 * Whether the field a dictation was captured against is still the place to deliver it.
 *
 * Fail-open on missing information: with no captured package, or nothing readable, the answer is
 * "current", which is byte-for-byte the pre-#284 behavior. The new behavior only engages on a
 * *positive* signal that the user moved: the device locked, a different app holds input focus or
 * is in front, or the captured field is demonstrably alive-but-unfocused with another field
 * focused. A tap that merely steals focus (nothing focused anywhere) stays "current" -- that is
 * the transient case the empty-scan retry (#5) exists for.
 */
internal fun isDeliveryTargetCurrent(p: DeliveryProbe): Boolean {
    val captured = p.capturedPackage ?: return true
    if (p.deviceLocked) return false
    val focused = p.focusedPackage
    if (focused != null) {
        if (focused != captured) return false
        return p.fieldState != CapturedFieldState.MOVED_AWAY
    }
    val fg = p.foregroundPackage ?: return true
    if (fg in TRANSIENT_SYSTEM_PACKAGES) return true
    return fg == captured
}

/** IME host: what to do with an in-flight dictation when the input lifecycle is lost. */
internal enum class LifecycleLossAction {
    /** Cancel and release, exactly as before #284. */
    TEAR_DOWN,

    /** Let the transcription finish; deliver through the ticket check (insert if the editor is
     *  still the bound one, otherwise clipboard + history + notification). */
    DETACH_AND_FINISH,
}

/**
 * Only TRANSCRIBING work has anything worth saving. A recording in progress is intentionally
 * abandoned (the mic must be released and the user has not finished speaking), and a destroyed
 * service takes its process-local work with it.
 */
internal fun lifecycleLossActionFor(reason: ImeLifecycleLoss, transcribing: Boolean): LifecycleLossAction =
    if (transcribing && reason != ImeLifecycleLoss.DESTROYED) LifecycleLossAction.DETACH_AND_FINISH
    else LifecycleLossAction.TEAR_DOWN

/** Keeps a process-wide background-work hold balanced: [onAcquire] on EVERY acquire (not just the
 *  0 -> 1 edge: if the service was force-stopped by its self-cap or the platform timeout while a
 *  hold was still live, the next acquire must be able to ask for a fresh start, and the callback
 *  is idempotent), [onLastRelease] on 1 -> 0, and a release with nothing held is ignored (never
 *  goes negative). Callbacks run under the lock, so they must be quick and non-blocking. */
internal class BackgroundWorkHolds(
    private val onAcquire: () -> Unit,
    private val onLastRelease: () -> Unit,
) {
    private var count = 0

    @Synchronized fun acquire() {
        count++
        onAcquire()
    }

    @Synchronized fun release() {
        if (count == 0) return
        if (--count == 0) onLastRelease()
    }

    @Synchronized fun held(): Int = count

    /** Test seam: the shipped instance is process-wide, so tests that drive the real seam must
     *  not inherit another test's un-ended holds. */
    @Synchronized fun resetForTest() { count = 0 }
}

/**
 * Seam through which [DictationRuntime] asks its process to stay alive while a dictation is being
 * transcribed AFTER the user has left the field. The default does nothing (unit tests, the
 * accessibility host, anything that opts out). The shipped implementation is
 * [BackgroundTranscriptionService.Companion.work]. The hold is lazy (ADR-0002): the runtime calls
 * [begin] only when its host reports the user is leaving ([DictationRuntime.holdForLeavingHost]),
 * never on the stay-in-field path.
 */
internal interface BackgroundWork {
    /** Called on the main thread, at most once per dictation. Must never throw. */
    fun begin()

    /** Called when the pipeline reaches any terminal state. Idempotent per [begin]. Must never throw. */
    fun end()

    object None : BackgroundWork {
        override fun begin() {}
        override fun end() {}
    }
}
