package com.trevornk.ramblr

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import android.view.KeyEvent
import android.os.Build
import kotlin.concurrent.thread
import kotlin.math.abs

/** How a candidate node ended up receiving the text; drives whether/when the clipboard gets cleared. */
enum class InjectMethod {
    /** Nothing worked; the clipboard is the fallback the user must paste from manually. */
    NONE,
    /** The node read the text back out of the clipboard (ACTION_PASTE or a custom paste action). */
    FROM_CLIPBOARD,
    /** The text was handed to the node directly (ACTION_SET_TEXT); the clipboard copy was never read. */
    DIRECT
}

/** What to do with the clipboard after an injection attempt. Pure decision, no Android dependencies. */
sealed class ClipboardClearAction {
    object None : ClipboardClearAction()
    object Immediate : ClipboardClearAction()
    data class Delayed(val delayMs: Long) : ClipboardClearAction()
}

/**
 * A DIRECT injection never reads the clipboard: when [clipboardWritten] is false it was never
 * touched at all, and when an earlier candidate's paste attempt staged the text first it's safe to
 * restore right away. A FROM_CLIPBOARD injection just read it, so restoring waits a grace period in
 * case the target app hasn't finished consuming it. NONE means the clipboard is the actual fallback
 * delivery path — leave it alone.
 */
fun clipboardClearActionFor(method: InjectMethod, clipboardWritten: Boolean, delayMs: Long): ClipboardClearAction = when (method) {
    InjectMethod.DIRECT -> if (clipboardWritten) ClipboardClearAction.Immediate else ClipboardClearAction.None
    InjectMethod.FROM_CLIPBOARD -> ClipboardClearAction.Delayed(delayMs)
    InjectMethod.NONE -> ClipboardClearAction.None
}

/** [injectText]'s default `feedback` value -- shown whenever a caller doesn't ask for a more
 *  specific message. Only meaningful when something actually ended up on the clipboard, so
 *  [injectionFeedbackFor] swaps it out for DIRECT injections (#118). */
const val DEFAULT_INJECT_FEEDBACK = "Copied to clipboard"

/**
 * #115: wall-clock markers for one dictation's user-perceived latency, from the stop tap through
 * to the actual injection result -- see [DictationRuntime.pipelineTiming]'s kdoc for
 * the field's lifecycle. [stopTapAtMs] is the anchor every other field's elapsed-ms is measured
 * against when the [PipelineStage] benchmark line is finally built.
 */
data class PipelineTiming(
    val stopTapAtMs: Long,
    val correlationId: String,
    val drainAtMs: Long? = null,
)

/**
 * Holder for the current dictation's [PipelineTiming] with explicit lifecycle verbs (H2, #192).
 *
 * The previous bare `@Volatile var` field was only ever cleared by [consume] (in
 * `finishInjection`) or overwritten by the next dictation's [start] -- so a dictation that ended
 * on a NON-happy path (no speech detected, long-press cancel, watchdog timeout, transcription
 * error) left its timing populated, and the next unrelated `injectText()` call (e.g. the feedback
 * bubble's raw-text retry) consumed it and wrote a benchmark line whose
 * `injectionAttemptMs`/`totalMs` were measured from the *previous, abandoned* dictation's stop
 * tap. [abandon] is the missing verb: every non-happy-path exit drops the timeline so it can
 * never be misattributed later.
 *
 * Same memory semantics as the field it replaces (a single volatile reference, no CAS): exactly
 * one dictation is ever in flight at a time -- `guard`/`activeToken` enforce that invariant --
 * and the one benign cross-thread copy race (reader-thread [markDrained] vs a simultaneous
 * main-thread write, audit L6) is unchanged.
 */
class PipelineTimingSlot {
    @Volatile private var timing: PipelineTiming? = null

    /** Anchors a fresh dictation timeline, overwriting anything left behind. */
    fun start(timing: PipelineTiming) { this.timing = timing }

    /** Stamps the reader-drain instant onto the active timeline; no-op when none is active. */
    fun markDrained(nowMs: Long) { timing = timing?.copy(drainAtMs = nowMs) }

    /** Returns the active timeline and clears it -- consume-exactly-once, or null when the
     *  dictation this injection belongs to never had / already used / abandoned its timing. */
    fun consume(): PipelineTiming? = timing.also { timing = null }

    /** Drops the timeline on a non-happy-path exit (no-speech, cancel, watchdog, transcription
     *  error) so a later, unrelated injection can never consume stale timing (H2, #192). */
    fun abandon() { timing = null }
}

/**
 * #118: the default "Copied to clipboard" feedback is only true when the text really did land on
 * the clipboard for the user to paste -- a DIRECT (ACTION_SET_TEXT) injection never reads the
 * clipboard at all, so showing that message there is misleading noise. Any caller-supplied,
 * non-default [feedback] (raw-text-retry, cleanup-failed/-skipped, etc.) is meaningful and passes
 * through untouched; only the untouched default gets swapped, and only for DIRECT.
 */
fun injectionFeedbackFor(method: InjectMethod, feedback: String?): String? =
    if (feedback == DEFAULT_INJECT_FEEDBACK && method == InjectMethod.DIRECT) "Inserted" else feedback

/**
 * Snapshot of the most recent successful injection, kept briefly (~10s) to back "undo last
 * insertion" / "retry with raw text" (#27). [node] is only set for a DIRECT (ACTION_SET_TEXT)
 * injection, since that's the only path where restoring the target in place is possible; it's an
 * owned copy that must be recycled when superseded or cleared.
 */
private data class PendingInjection(
    val timestamp: Long,
    val rawText: String,
    val injectedText: String,
    val priorClipboard: String?,
    val priorNodeText: String?,
    val node: AccessibilityNodeInfo?,
    val historyTimestamp: Long,
)

open class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        /** The connected service instance, whichever of the two #156 components it is: this
         *  companion is shared by [WhisperAccessibilityService] and its empty subclass
         *  [SystemControlsAccessibilityService] (JVM statics live on the base class), and
         *  onServiceConnected/onDestroy assign/clear it for both. Every `instance != null`
         *  service-health check in the app therefore accepts EITHER component for free -- and
         *  nothing in this class may compare against its own concrete component name; use
         *  [InvocationServiceMode.activeComponent] for that. */
        var instance: WhisperAccessibilityService? = null

        /** SharedPreferences keys for the persisted overlay drag position (#101). */
        private const val PREF_OVERLAY_X = "overlay_x"
        private const val PREF_OVERLAY_Y = "overlay_y"

        /** Current [RecordingStateMachine.State] of the running service, or null if the service
         *  isn't connected at all (mirrors the existing `instance?.` null-safe read pattern used
         *  by e.g. [ModelDownloadWorker.notifyServiceModelReady] / LivePreviewActivity's
         *  `reloadStreamingModel()` call). Added for the github-flavor self-update install gate
         *  (Part 4, [SelfUpdateInstallGate]/[SelfUpdateInstallWorker]) to check "is dictation
         *  idle right now" without exposing the private [runtime] field itself. */
        fun currentRecordingState(): RecordingStateMachine.State? = instance?.runtime?.currentState()

        /**
         * Cross-component entry point for the Quick Settings tile (#127): [RamblrQsTileService]
         * lives in its own component, not this AccessibilityService, so it can't call
         * [onTap]/[startRecording]/[stopAndTranscribe] directly -- it goes through this static,
         * null-safe function instead, mirroring the existing `instance?.` pattern used by
         * [currentRecordingState]/[ModelDownloadWorker.notifyServiceModelReady] rather than
         * inventing a new Binder/Broadcast IPC channel for what is, in-process, just a same-JVM
         * singleton reference. Returns false (and does nothing) when the service isn't connected
         * at all, i.e. the user hasn't enabled Ramblr's accessibility service -- the tile uses
         * that to show its "service disabled" state instead of silently no-op'ing forever.
         * Posted to the service's own [handler] so the actual state-machine transition always
         * runs on the same thread [onTap] itself runs on, even though the tile calls in from a
         * different process-local component's callback thread.
         */
        fun requestToggleRecording(): Boolean {
            val service = instance ?: return false
            service.handler.post {
                // Never start a recording the user can't see (#136). The tile is reachable while
                // the ring is hidden -- that's the entire point of #127 -- but startRecording()/
                // onTap() never touch IconHiddenState, and applyOverlayVisibility() holds BOTH
                // overlay windows at alpha=0f while it's set. Without this, a tile tap starts a
                // live mic with zero on-screen indication: the ring stays invisible and
                // showFeedback() writes into a bubble that's already been forced to alpha=0f and
                // re-hidden. Silent recording is a privacy problem, not just a UX one, so
                // un-hiding is treated as a precondition of starting rather than an option.
                // See [shouldRestoreIconBeforeToggle] for the rule and why it's start-only.
                if (shouldRestoreIconBeforeToggle(
                        service.runtime.currentState(),
                        IconHiddenState.isHidden(service),
                    )
                ) {
                    IconHiddenState.setHidden(service, false)
                    IconVisibilityNotifications.cancel(service)
                    service.applyOverlayVisibility()
                }
                service.onTap()
            }
            return true
        }

        /** Whether Ramblr's own MainActivity is currently foregrounded (#35), kept as a static flag
         *  rather than only reacting when [instance] is non-null -- so a service that connects while
         *  MainActivity is already open (e.g. right after the user enables Accessibility from
         *  Settings and returns) still starts with the overlay hidden instead of covering the
         *  Settings switches it was just enabled from. */
        @Volatile private var mainActivityForeground = false

        /** Override that keeps the overlay visible despite [mainActivityForeground] being true
         *  (#103): the onboarding wizard's "Try it out" step is a dialog shown ON TOP OF
         *  MainActivity, so #35's Settings-suppression logic hid the exact icon the step asks the
         *  user to tap -- the step was genuinely impossible to complete without first backing out
         *  of onboarding entirely, since MainActivity (a [BaseSettingsActivity]) never left the
         *  foreground while that dialog was up. Scoped to exactly this one step (set true only
         *  while the "Try it out" dialog is showing, false again the moment it's dismissed) rather
         *  than weakening #35's general "don't cover Settings switches" behavior, which remains
         *  correct for every other Settings screen. */
        @Volatile private var overlayForceVisibleOverride = false

        /** Called from MainActivity's onResume/onPause so the floating overlay never covers its own
         *  Settings switches (#35). Safe to call whether or not the service is currently connected. */
        fun setMainActivityForeground(foreground: Boolean) {
            mainActivityForeground = foreground
            instance?.let { service -> service.handler.post { service.applyOverlayVisibility() } }
        }

        /** See [overlayForceVisibleOverride]'s kdoc. Called by [MainActivity]'s onboarding "Try it
         *  out" step around showing/dismissing its dialog -- never left set to true past that one
         *  dialog's lifetime. */
        fun setOverlayForceVisibleOverride(forceVisible: Boolean) {
            overlayForceVisibleOverride = forceVisible
            instance?.let { service -> service.handler.post { service.applyOverlayVisibility() } }
        }

        /** Best-effort package name for whichever app currently owns the active accessibility root. */
        fun foregroundPackageNameOrNull(): String? = instance?.currentForegroundPackageName()

        /**
         * The #254 in-app off switch: turn the accessibility service off from inside Ramblr.
         * Returns false when no service instance is connected (nothing to disable -- caller
         * falls back to the system Settings deep link; see [resolveServiceOffAction]).
         *
         * [AccessibilityService.disableSelf] is the only app-callable way to leave
         * `enabled_accessibility_services`, and it is the ONLY correct one here: in
         * SYSTEM_CONTROLS mode the OS hides the master on/off switch on Ramblr's own
         * Accessibility page (INVISIBLE_TOGGLE classification), so without this the user has no
         * off switch anywhere. disableSelf routes through
         * AccessibilityServiceConnection.disableSelf, which persists the enabled-services list
         * directly and never calls the invisible-toggle shortcut sync, so the OS does not undo
         * it -- unlike an external write, which a bound shortcut can immediately reverse.
         *
         * Any in-flight recording is torn down by the ordinary destroy path: disableSelf()
         * unbinds the service, so [onDestroy] -> [DictationRuntime.shutdown] runs exactly as it
         * does when the user flips the system Settings switch off -- reader forced off
         * RECORDING, AudioRecord released, no stranded mic. Nothing extra to do here.
         */
        fun disableServiceFromApp(): Boolean {
            val service = instance ?: return false
            service.disableSelf()
            return true
        }

        private const val TAG = "PhoneWhisper"
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        // Screen height the long-press menu always leaves uncovered, so the dismiss scrim stays
        // tappable no matter how many rows the menu has.
        private const val STYLE_MENU_OUTSIDE_RESERVE_DP = 120
        // Popup-sized cap on the long-press menu's width; long subtitles wrap rather than
        // stretching the panel across the screen.
        private const val STYLE_MENU_MAX_WIDTH_DP = 360
        /** Default/reference ring diameter that [BTN_DP]/[PAD_DP] are proportioned against; the
         *  actual on-screen size comes from [OverlayAppearancePrefs] (#43/#53) and is scaled by
         *  the same ratio, so an unconfigured install looks pixel-identical to before that setting
         *  existed and a customized size keeps the glyph/padding proportions consistent. */
        private const val RING_DP = OverlayAppearancePrefs.DEFAULT_RING_DP
        private const val FEEDBACK_OFFSET_DP = 64
        /** #117: gap kept between the feedback bubble and the ring on the ring-adjacent edge,
         *  wider than the plain [MARGIN_DP] used everywhere else -- the ring is the single most
         *  likely next tap target while the bubble is touchable (raw-text-retry), so it gets
         *  extra breathing room instead of the ordinary screen-edge margin. */
        private const val RING_AVOID_MARGIN_DP = 20

        /** Grace period before wiping the clipboard after a paste-style injection reads it. */
        private const val CLIPBOARD_CLEAR_DELAY_MS = 30_000L
        /** How long "undo last insertion" / "retry with raw text" stay available after an injection (#27). */
        private const val UNDO_RETRY_WINDOW_MS = 10_000L
        /** Delay before rescanning once if the first candidate scan comes up empty (#5) — long enough
         *  for a transient post-tap focus race to settle, short enough not to feel laggy. */
        private const val INJECTION_RETRY_DELAY_MS = 200L
        private const val WEBVIEW_ANCESTOR_DEPTH = 40
        /** Minimum gap between two streaming-preview partial injections into the focused field
         *  (#29) — chunks arrive far more often than this; injecting on every one would hammer the
         *  target app's input and feel janky. The very first partial of a recording bypasses this
         *  (see [shouldInjectPartial]) so live preview doesn't feel laggy at speech onset. */
        private const val STREAMING_PARTIAL_MIN_INTERVAL_MS = 400L
        private const val PARTIAL_CARET_NUDGE_DELAY_MS = 150L
        /** Clipboard-fallback feedback stays up much longer than a normal success bubble (#5): it's
         *  the only record the user gets that injection didn't happen, so it shouldn't be easy to miss. */
        private const val FALLBACK_FEEDBACK_DURATION_MS = 6000L
        /** The "Not translated: ..." bubble is a sentence naming where to fix it, so it stays up
         *  longer than a routine confirmation. */
        private const val TRANSLATION_NOTICE_DURATION_MS = 5000L
        /** How long a pending preview (#40) waits for an explicit commit tap before it auto-resolves
         *  to the raw transcript — long enough to read a short dictation, short enough that a
         *  distracted user isn't left staring at an un-injected bubble for too long. */
        private const val PREVIEW_TIMEOUT_MS = 8000L
        /** Feedback bubble text is a small pill, not a paragraph — the preview candidate is
         *  truncated to this many characters before display. */
        private const val PREVIEW_PREVIEW_CHARS = 80

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        /** Warmer/brighter than [COLOR_FEEDBACK_BG] so a clipboard-fallback (#5) visually stands out
         *  from a routine success bubble instead of looking identical. */
        private const val COLOR_FEEDBACK_FALLBACK_BG = 0xEEB45309.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
        /** Chips per row of the style menu's "Translate to" section. */
        private const val TRANSLATE_CHIPS_PER_ROW = 3
        /** Fill of the translation language badge on the ring. */
        private const val COLOR_TRANSLATE_BADGE = 0xFF2563EB.toInt()
        /** Busy-ring tint while cleanup runs (#294) -- the same emerald as [COLOR_CLEANUP_ON], so the
         *  ring matches the style menu's "Cleanup: On" row. */
        private const val COLOR_RING_CLEANING = 0xFF34D399.toInt()
        /** Style menu's cleanup row icon (#34, #53): emerald when cleanup will run, neutral grey
         *  when it's off -- the same colors the old always-visible badge used. */
        private const val COLOR_CLEANUP_ON = 0xFF34D399.toInt()
        private const val COLOR_CLEANUP_OFF = 0xFF6B7280.toInt()
        /** Subtle light-alpha border around the long-press style menu (#53 follow-up) -- the
         *  menu's near-black translucent fill has no edge of its own and blends into a dark app
         *  background or dark wallpaper without one. */
        private const val COLOR_MENU_BORDER = 0x33FFFFFF
        /** The mic glyph's original hardcoded color (see ic_mic.xml) -- used when the user hasn't
         *  picked a custom glyph color (#43), so an unconfigured install renders identically. */
        private const val COLOR_GLYPH_DEFAULT = 0xFFFFFFFF.toInt()

        /** Default overlay border color for a fresh install (no explicit [OverlayAppearance]
         *  override yet) -- neon magenta, matching the public launcher icon's accent color and
         *  Trevor's own long-standing manual override, made the shipped default so new installs
         *  see the same look out of the box instead of a borderless ring. */
        private const val COLOR_BORDER_DEFAULT = 0xFFFF00FF.toInt()

        /** Anonymous [ViewOutlineProvider]s can't be `const`, but this one never varies per-view,
         *  so a single shared instance is used to clip a custom icon image (#43) into a circle
         *  matching its own bounds, whatever size the ring is currently configured to. */
        private val OVAL_OUTLINE_PROVIDER = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
    }

    /**
     * The extracted dictation engine (#143 Phase 1): record -> transcribe -> clean -> deliver
     * lives in [DictationRuntime]; this service is its accessibility host (overlay ring UI,
     * a11y-based text injection, preview/undo/clipboard affordances). The listener below is the
     * host half of the pre-extraction pipeline, verbatim: each callback body is exactly the code
     * the corresponding call site ran inline before the extraction.
     */
    internal val runtimeListener = object : RuntimeListener {
        override fun onRecordingStartRequested() {
            // A still-pending preview (#40) from the previous dictation shouldn't linger silently
            // while a new one starts -- resolve it the same safe way a timeout would.
            pendingPreview?.let { resolvePreview { p -> p.timeout() } }

            endStreamingSession()
            flushPendingStreamingHandoff()
            lastBubblePartialText = null
            lastBubblePartialAtMs = 0L
            // The previous dictation's translation bookkeeping (and any unshown skip notice) ends
            // with it; a new dictation asks again when its cleanup starts.
            translationRequested = false
            translationUnavailable = false
            pendingTranslationNotice = null
        }

        override fun onRecordingStartFailed() {
            // End the streaming session so a failed recorder start doesn't leak the OnlineStream
            // opened by beginSession() until the next recording (L11).
            endStreamingSession()
        }

        override fun onRecordingStarted() {
            setBusy(false)
            // Deferred (Trevor-reported bug fix, see animateRingX's kdoc): with SingleTapRestoreToggle
            // on, this exact call site can run while restoreFromPeek()'s animator is still mid-flight
            // repositioning the same ring window from the SAME tap gesture. setAppearance()/
            // startPulse() are a second, independent animation on the same button view; without this
            // guard the two could interleave and the peek-restore slide would visibly stall or never
            // finish, even though recording (and its own animation) started correctly.
            deferUntilPeekAnimationDone {
                setAppearance(COLOR_RECORDING)
                startPulse()
            }
            // A recording started from the QS tile or a11y button while the keyboard-only gate had
            // the ring hidden must bring it back -- the user needs it to see and stop recording.
            reapplyVisibilityModeGate()
        }

        override fun onEnterTranscribingUi() {
            // #284: remember where this dictation was started, now, while the user is still there.
            captureDictationTarget()
            handler.post { stopPulse() }
            setAppearance(COLOR_BUSY)
            setBusy(true)
        }

        override fun onCleaningStarted() {
            // #294: transcription is done and cleanup has begun. Tint the busy ring so the user can
            // tell the two phases apart. The ring (not the button fill) carries the signal because a
            // custom overlay icon (#43) replaces the fill entirely, but the ring always draws on top.
            setBusy(true, cleaning = true)
            setAppearance(COLOR_BUSY)
        }

        override fun onIdleUi() {
            clearDictationTarget()
            setBusy(false)
            setAppearance(COLOR_IDLE)
            // Posted, not run inline: deliverText() has already posted this dictation's feedback
            // bubble, and the gate must see that bubble as showing rather than hide it.
            reapplyVisibilityModeGate()
        }

        override fun onStreamingTeardown() {
            // The host half of the pre-extraction teardownStreamingPreview(): the node reference
            // isn't recycled outright here but moved into [pendingStreamingHandoff] (#45) -- the
            // final batch injection may still be pending behind a preview-before-inject commit
            // (#40) at this point, and needs that reference to reconcile its tracked span once it
            // actually runs. Called from every path back to IDLE and from onDestroy (which
            // flushes it right after, since no injection will ever follow there); safe to call
            // even when no session is active.
            flushPendingStreamingHandoff()
            pendingStreamingHandoff = streamingSession
            streamingSession = null
            // #300: a session that overwrote a selection and ended with nothing to deliver
            // (cancelled, nothing heard, provider error) has no final injection coming to close its
            // span, so put the selected text back now rather than leaving the partial where it was.
            // A pending preview (#40) is the one case where a final injection is still on its way:
            // that keeps the handoff. Sessions that overwrote nothing are untouched, as before.
            if (pendingPreview == null && pendingStreamingHandoff?.replacedText?.isNotEmpty() == true) {
                flushPendingStreamingHandoff()
            }
        }

        override fun onStreamingPartial(text: String) {
            maybeInjectPartial(text)
        }

        // #245: route the Gemini Cloud Live interim through the exact same throttled,
        // node-tracked direct-injection path local streaming preview already uses (#29),
        // instead of leaving DictationRuntime's default no-op. This reuses -- rather than
        // reimplements -- the field-verified safety properties that path already has: the
        // recording-state guard, the shared streamingSession span tracking, and the
        // onStreamingTeardown()/pendingStreamingHandoff reconciliation that resolves a
        // dropped or superseded partial span cleanly instead of leaving stray text behind
        // (the exact risk #245 flagged for naive interim injection). Preview-before-inject
        // is honored because maybeInjectPartial() itself branches on that toggle first.
        override fun onCloudLiveInterim(text: String) {
            // Gemini's interim is already cased and punctuated: display it as received. Only the
            // on-device model's raw ALL-CAPS partials go through smartCapitalize (which would
            // lowercase "I", names and acronyms here).
            maybeInjectPartial(text, requireRecording = true, alreadyCased = true)
        }

        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            // A password field is never a destination, and nothing about the dictation is kept.
            if (resultTargetsPasswordField(dictationTarget)) {
                discardPasswordFieldResult()
                return
            }
            // #284: the field this dictation was started in is gone (another app or field is in
            // front, or the device locked). Never type into whatever is focused now.
            if (!deliveryTargetIsCurrent()) {
                deliverToBackground(text, rawText, paidFallbackGroup, cleanupError)
                return
            }
            // rawText is non-null exactly when cleanup ran and succeeded -- the one branch that
            // routed through preview-before-inject (#40) before the extraction.
            if (rawText != null && PreviewBeforeInjectToggle.isEnabled(this@WhisperAccessibilityService)) {
                beginPreview(rawText = rawText, candidateText = text, paidFallbackGroup = paidFallbackGroup)
            } else {
                injectText(
                    text,
                    rawText = rawText,
                    feedbackDurationMs = feedbackDurationMs,
                    paidFallbackGroup = paidFallbackGroup,
                    cleanupError = cleanupError,
                )
            }
        }

        override fun foregroundPackageName(): String? = currentForegroundPackageName()

        // "Translate to" is shared with the keyboard (one preference), so a dictation started from
        // the floating icon translates into the same language. Read once, when cleanup starts.
        override fun translationTarget(): String? {
            val target = KeyboardTranslation.target(this@WhisperAccessibilityService)?.englishName
            translationRequested = target != null
            return target
        }

        // The ring has no panel: the reason goes in the floating bubble (shown with the insert, see
        // finishInjection), not a toast, and the dictation is inserted untranslated.
        override fun onTranslationUnavailable(consentMissing: Boolean): Boolean {
            translationUnavailable = true
            pendingTranslationNotice = getString(
                if (consentMissing) R.string.ring_translate_unavailable_consent else R.string.ring_translate_unavailable_cloud,
            )
            return true
        }

        // #284: a dictation that ended with no text. Only worth a notification when the user can
        // no longer see the field it was for; otherwise the runtime's own toast is the whole story.
        override fun onDictationFailed(failure: BackgroundFailure) {
            if (!shouldNotifyBackgroundFailure(deliveryTargetIsCurrent())) return
            backgroundSink.failed(failure)
        }
    }

    // --- #284: background delivery -------------------------------------------------------------

    /** Where the in-flight dictation was started: its package, and the focused node (if any) so a
     *  move to a different field in the same app is detectable. Main thread only. */
    internal class DictationTarget(
        val packageName: String,
        val node: AccessibilityNodeInfo?,
        /** The field this dictation was captured in is a password field (see [PasswordFieldGuard]). */
        val isPassword: Boolean = node?.isPasswordField() == true,
    )

    internal var dictationTarget: DictationTarget? = null

    /** Translation bookkeeping for the in-flight dictation (see [runtimeListener]): a target was
     *  requested when cleanup started, the route could not carry it out, and the bubble text that
     *  says why. Written on the cleanup thread, read on the main thread. */
    @Volatile private var translationRequested = false
    @Volatile private var translationUnavailable = false
    @Volatile internal var pendingTranslationNotice: String? = null

    private val backgroundSink by lazy {
        BackgroundResultSink(
            copyToClipboard = { ClipboardUtil.copy(this, it) },
            postNotice = { BackgroundDictationNotifications.postResult(this, it) },
        )
    }

    private fun captureDictationTarget() {
        clearDictationTarget()
        try {
            // Unreadable / unidentifiable => no target => fail open to the pre-#284 behavior.
            val (pkg, node) = readCaptureTarget() ?: return
            dictationTarget = DictationTarget(pkg, node)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't capture dictation target", e)
        }
    }

    private fun clearDictationTarget() {
        dictationTarget?.node?.recycle()
        dictationTarget = null
    }

    // --- Password fields -------------------------------------------------------------------------

    /** Platform read (test seam): whether the input-focused node right now is a password field.
     *  An unreadable tree answers false (the pre-existing fail-open stance of the target reads). */
    internal open fun focusedInputIsPassword(): Boolean {
        val root = try { rootInActiveWindow } catch (e: RuntimeException) { null } ?: return false
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try { focused?.isPasswordField() == true } finally { focused?.recycle() }
        } catch (e: RuntimeException) {
            false
        } finally {
            root.recycle()
        }
    }

    /** True when a finished dictation must be discarded because its field -- the one focused now
     *  or the one it was captured in ([target]) -- is a password field. */
    private fun resultTargetsPasswordField(target: DictationTarget?): Boolean =
        PasswordFieldGuard.routeResult(focusedInputIsPassword(), target?.isPassword == true) ==
            DictationResultRoute.DISCARD_PASSWORD_FIELD

    /**
     * Drops a finished dictation whose destination is a password field: nothing is inserted, put
     * on the clipboard, written to history or fed to the vocabulary collector (the callers invoke
     * this before any of those). A streaming partial left in another field is reverted, and the
     * undo/retry/copy-again affordances are cleared so none can re-offer the text.
     */
    private fun discardPasswordFieldResult(claimedStreamingHandoff: StreamingPreviewSession? = null) {
        pendingInjectionRetry?.let { handler.removeCallbacks(it) }
        pendingInjectionRetry = null
        val streamingHandoff = claimedStreamingHandoff ?: streamingSession ?: pendingStreamingHandoff
        streamingSession = null
        pendingStreamingHandoff = null
        if (streamingHandoff != null) {
            if (!streamingHandoff.node.isPasswordField()) clearStreamingLeftover(streamingHandoff)
            streamingHandoff.node.recycle()
        }
        runtime.pipelineTiming.abandon()
        updatePendingInjection(InjectMethod.NONE, "", "", null, null, null, 0L)
        fallbackClipboardText = null
        Log.i(TAG, "Dictation discarded: destination is a password field")
        showFeedback(PASSWORD_FIELD_REFUSAL_MESSAGE, FALLBACK_FEEDBACK_DURATION_MS, touchable = false, isFallback = true)
    }

    /** Platform read #1 (test seam): who is in front and focused right now, at transcription
     *  start. The returned node (if any) is owned by the caller. */
    internal open fun readCaptureTarget(): Pair<String, AccessibilityNodeInfo?>? {
        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val pkg = captureTargetPackage(root?.packageName?.toString(), focused?.packageName?.toString())
        root?.recycle()
        if (pkg == null) {
            focused?.recycle()
            return null
        }
        return pkg to focused
    }

    /** Platform read #2 (test seam): the same facts again, now, at delivery time. */
    internal open fun readDeliveryProbe(capturedPackage: String, capturedNode: AccessibilityNodeInfo?): DeliveryProbe {
        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val state = when {
            focused == null || capturedNode == null -> CapturedFieldState.UNKNOWN
            focused == capturedNode -> CapturedFieldState.FOCUSED
            capturedNode.refresh() && !capturedNode.isFocused -> CapturedFieldState.MOVED_AWAY
            else -> CapturedFieldState.UNKNOWN
        }
        val locked = (getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true
        val probe = DeliveryProbe(
            capturedPackage = capturedPackage,
            foregroundPackage = root?.packageName?.toString(),
            focusedPackage = focused?.packageName?.toString(),
            deviceLocked = locked,
            fieldState = state,
        )
        root?.recycle()
        focused?.recycle()
        return probe
    }

    /** True unless there is a positive signal the user left the field this dictation started in.
     *  Any failure to read the world answers true (pre-#284 behavior). */
    internal fun deliveryTargetIsCurrent(target: DictationTarget? = dictationTarget): Boolean {
        target ?: return true
        return try {
            isDeliveryTargetCurrent(readDeliveryProbe(target.packageName, target.node))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't evaluate dictation target", e)
            true
        }
    }

    /**
     * The dictation finished but its field is gone. Records history exactly once (the normal
     * injectText funnel, which would also record, is bypassed), puts the text on the clipboard
     * unless the app now in front is excluded (#256), reverts any streaming leftover in the old
     * field, and posts one notification. Nothing is ever written into the now-focused field.
     */
    private fun deliverToBackground(
        text: String,
        rawText: String?,
        paidFallbackGroup: CleanupStepGroup?,
        cleanupError: String?,
        // A dictation that already has a history row (a resolved preview #73, or an injection
        // whose empty-scan retry found the user gone) updates it in place instead of duplicating.
        existingHistoryTimestamp: Long? = null,
        // A streaming session (#29) injectText already claimed before handing off to its retry.
        claimedStreamingHandoff: StreamingPreviewSession? = null,
    ) {
        recordHistory(
            rawText ?: text, cleanedText = if (rawText != null) text else null,
            paidFallbackGroup = paidFallbackGroup, existingTimestamp = existingHistoryTimestamp,
        )
        val historySaved = prefs().getBoolean("dictation_history_enabled", true)

        // Same claim-and-revert the exclusion path does, so a streaming partial typed into the old
        // field before the user left isn't orphaned there.
        val streamingHandoff = claimedStreamingHandoff ?: streamingSession ?: pendingStreamingHandoff
        streamingSession = null
        pendingStreamingHandoff = null
        if (streamingHandoff != null) {
            clearStreamingLeftover(streamingHandoff)
            streamingHandoff.node.recycle()
        }

        // Consumed so a later, unrelated injection can't pick up this dictation's timing (H2, #192).
        runtime.pipelineTiming.abandon()
        updatePendingInjection(InjectMethod.NONE, text, rawText ?: text, null, null, null, 0L)

        val excluded = ExclusionGating.shouldSuppressInsertion(
            PerAppExclusionStore.isExcluded(this, currentForegroundPackageName())
        )
        val delivery = backgroundSink.deliverTargetGone(
            text = text,
            historySaved = historySaved,
            cleanupFailed = cleanupError != null,
            excluded = excluded,
        )
        Log.i(TAG, "Dictation target gone; delivery=$delivery (no insertion attempted)")
        if (delivery == BackgroundDelivery.CLIPBOARD_AND_NOTIFY) {
            fallbackClipboardText = text
            showFeedback("Dictation finished — copied to clipboard · tap to copy again", FALLBACK_FEEDBACK_DURATION_MS, touchable = true, isFallback = true)
        } else {
            fallbackClipboardText = null
        }
    }

    // #245: the accessibility/floating-icon host now reaches the same merged cloud-live seam
    // the IME host has used since #233 Phase 1 (RamblrImeService.kt). CloudLiveWiring.factoryOrNull
    // returns null unless the user opted into Cloud Live, chose cloud transcription, and has a
    // configured Gemini credential -- so this is a no-op, byte-for-byte-identical construction for
    // every shipped/default configuration, exactly as it was for the IME host at merge time.
    //
    // Backed by an explicit nullable field and a get()-based accessor rather than an eager field
    // initializer: an AccessibilityService's field initializers run during the framework's no-arg
    // construction, before attachBaseContext() -- calling `this` as a Context that early
    // (CloudLiveWiring.factoryOrNull reads SharedPreferences off it) would crash before the
    // service ever reaches onServiceConnected(). Deferring construction to first access is safe
    // because every real access (onTap()/callbacks, and onServiceConnected() itself) runs after
    // the service is fully attached and connected -- the same safe timing RamblrImeService gets
    // for free because ensureRuntime() is called from its own post-attach lifecycle methods.
    //
    // The DictationRuntime itself is still created lazily, once, and kept for the process
    // lifetime of this long-lived accessibility service. But `cloudLiveFactory` is passed as a
    // provider lambda (re-invoked on every dictation attempt), not a resolved value captured at
    // this one-time construction -- otherwise toggling Cloud Live on/off, switching cloud/local
    // transcription, or rotating the Gemini key while the service is already running would have
    // no effect until the service process was killed and recreated, since nothing here ever
    // rebuilds `runtimeInstance`. See DictationRuntime's `cloudLiveFactory` kdoc.
    // Construction is @Synchronized because onServiceConnected() reaches `runtime` from two
    // background threads (initLocalModel()/initStreamingModel()) while the main thread can touch
    // it too. An unsynchronized check-then-assign would let two threads each build a
    // DictationRuntime and silently orphan the loser -- never shut down by onDestroy(), which
    // only sees whichever won the assignment, with native models already loading on both.
    private var runtimeInstance: DictationRuntime? = null
    internal val runtime: DictationRuntime
        get() = obtainRuntime()

    @Synchronized
    private fun obtainRuntime(): DictationRuntime =
        runtimeInstance ?: DictationRuntime(
            // #284: no foreground-service hold here, deliberately. The system binds an enabled
            // accessibility service with BIND_FOREGROUND_SERVICE_WHILE_AWAKE, which keeps this
            // process at foreground-service importance while the device is awake; the existing
            // "ramblr:transcription" wakelock covers screen-off CPU. See ADR-0002 for the evidence.
            this, runtimeListener, cloudLiveFactory = { CloudLiveWiring.factoryOrNull(this) },
        ).also { runtimeInstance = it }

    private var overlayView: FrameLayout? = null
    /** The "ES"-style badge on the ring while a translate target is set; null before the overlay exists. */
    internal var translationBadge: TextView? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    /** True while the busy ring shows the cleanup phase (#294). Main thread only. */
    private var cleaningUi = false
    private var feedbackView: TextView? = null
    /** True from [showFeedback] until [hideFeedback] runs; keeps the keyboard-only gate from
     *  hiding the ring under a bubble that may still need a tap. Main thread only. */
    private var feedbackShowing = false
    /** Last "is the ring's anchor present" answer seen by [onAccessibilityEvent] (keyboard up, or
     *  a text field focused, per [RingVisibilityMode]), so the overlay is only re-laid-out when it
     *  actually changes -- each re-layout itself fires another TYPE_WINDOWS_CHANGED. Reset on every
     *  mode change ([onRingVisibilityModeChanged]) because the answer depends on the mode. */
    internal val ringAnchorTracker = RingAnchorTracker()
    /** Adds window/focus events to the subscription only while the user has picked a non-ALWAYS
     *  [RingVisibilityMode]; see [RingEventTypes]. Main thread only. */
    internal val ringEventSubscription = RingEventSubscription(
        getInfo = { serviceInfo },
        setInfo = { serviceInfo = it },
    )
    /** Strong reference: SharedPreferences holds listeners weakly. */
    private var ringModePrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null

    // Auto-hide-to-peek (see RingPeek): true while the ring is currently slid over toward its
    // snapped edge, leaving only a small sliver visible. prePeekX is the full-position window x
    // to animate back to on restore -- null whenever isPeeked is false.
    private var isPeeked = false
    private var prePeekX: Int? = null
    private var peekAnimator: android.animation.ValueAnimator? = null
    /** Set only when a peek-restore ValueAnimator is currently running AND a caller asked to
     *  defer a same-view appearance change until it finishes (see [runAfterPeekRestore]) --
     *  the actual fix for the SingleTapRestoreToggle race documented on [animateRingX]. Cleared
     *  (and invoked) from the animator's own end/cancel listener, never left to accumulate. */
    private var pendingPostPeekAction: Runnable? = null
    private val idlePeekRunnable = Runnable { attemptAutoPeek() }
    // screenW/screenH as of the last time the ring's position was computed (#41) -- the baseline
    // handleScreenSizeChange() diffs against to detect a fold/unfold or rotation. Set alongside
    // layoutParams in showOverlay() and kept in sync every time it repositions the ring.
    private var lastScreenW = 0
    private var lastScreenH = 0

    /** Registered in [registerNetworkCallback]; null when registration failed or after teardown. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Registered in [registerDisplayListener]; null when registration failed or after teardown. */
    private var displayListener: DisplayManager.DisplayListener? = null
    /** Registered in [registerScreenStateReceiver]; null when registration failed or after
     *  teardown. Reapplies overlay visibility on SCREEN_ON/SCREEN_OFF/USER_PRESENT so the ring
     *  hides the instant the device locks and reappears right after unlock (see
     *  [overlayShouldBeVisible]'s keyguard doc). */
    private var screenStateReceiver: android.content.BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        val wasShowing = feedbackShowing
        feedbackShowing = false
        setFeedbackTouchable(false)
        // alpha only, never GONE (real root cause, verified via UID/window-token-attributed
        // BLASTSyncEngine analysis on-device: this window's WindowToken is type=2032
        // TYPE_ACCESSIBILITY_OVERLAY, the only such token in the accessibility-service window
        // set. On a fold-triggered screen-off display switch, WMS collects a synchronous BLAST
        // sync across every window under that token and blocks screen-on for up to a fixed
        // 2000ms waiting for each to draw a frame. A window whose root is View.GONE never draws,
        // so it can never satisfy the sync -- it just eats the full 2s timeout every time,
        // regardless of how the ring (the OTHER window under the same token) behaves. This is
        // exactly why the earlier alpha-vs-GONE fix to the ring alone changed nothing: this
        // feedback bubble was still going GONE every time it hid, and one non-drawing window is
        // enough to stall the whole token's sync. See applyOverlayVisibility() for the same fix
        // applied to the ring.
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.start()
        // The bubble was what kept the keyboard-only gate open; re-evaluate now that it's gone.
        // wasShowing guards against a loop: applyOverlayVisibility() itself posts this runnable.
        if (wasShowing && RingVisibilityMode.read(this@WhisperAccessibilityService) != RingVisibilityMode.ALWAYS) {
            applyOverlayVisibility()
        }
    }

    // Long-press style/cleanup menu (#53) -- a scrim (catches an outside tap to dismiss) plus the
    // menu content itself, both added to the WindowManager only while the menu is open; neither
    // field is ever non-null unless the other is too. See showStyleMenu()/dismissStyleMenu().
    private var styleMenuScrim: View? = null
    private var styleMenuView: View? = null

    // Undo / retry-raw state (#27) — last injection only, cleared on use or expiry.
    private var pendingInjection: PendingInjection? = null
    private val expirePendingInjection = Runnable { clearPendingInjection() }

    // Preview-before-inject (#40) — the cleaned-up candidate awaiting an explicit commit tap,
    // null whenever no preview is in flight. Only touched on the main thread.
    private var pendingPreview: CleanupPreviewState? = null
    // #284: where the pending preview's dictation was started; owns its node. Null whenever no
    // preview is pending (or nothing was captured, which fails open to the pre-#284 behavior).
    private var pendingPreviewTarget: DictationTarget? = null
    private val previewTimeoutRunnable = Runnable { resolvePreview { it.timeout() } }

    // Empty-scan retry (#5) — at most one in flight, cancelled if the service tears down first.
    private var pendingInjectionRetry: Runnable? = null

    // Text last delivered via clipboard-only fallback (#5); backs the feedback bubble's "tap to
    // copy again" affordance. Null whenever the last injection wasn't a fallback.
    private var fallbackClipboardText: String? = null

    // Scheduled post-injection clipboard restore (#5) — at most one in flight, cancelled if a new
    // injection starts or the service tears down first. See clipboardRestoreOutcomeFor.
    private var pendingClipboardRestore: Runnable? = null

    // Live-preview injection state for the current recording (#29), null when no partial has been
    // injected yet this recording or once the session has ended. Only touched on the main thread.
    private data class StreamingPreviewSession(
        val node: AccessibilityNodeInfo,
        val insertionStart: Int,
        var lastPartialLength: Int,
        // #300: the selected text the first partial overwrote, so a dictation that never lands can
        // restore it. Empty when the first partial was a pure insertion.
        val replacedText: String,
        var lastInjectedText: String?,
        var lastInjectedAtMs: Long,
        // What the last write did to the field, so a write the target dropped or reverted can't
        // leave the tracked span describing text that isn't there (see StreamingWriteTrace).
        var trace: StreamingWriteTrace? = null,
    )
    private var streamingSession: StreamingPreviewSession? = null

    // Live-preview *bubble* routing (bug fix: live-preview + preview-before-insert interaction) --
    // when PreviewBeforeInjectToggle is on, streaming partials must never touch the real field
    // pre-commit, so they're mirrored into the feedback bubble instead via this separate, much
    // lighter throttling state (mirrors StreamingPreviewSession's lastInjectedText/At but holds no
    // AccessibilityNodeInfo at all -- nothing here needs recycling). Reset at the start of every
    // recording alongside endStreamingSession()/flushPendingStreamingHandoff(); left completely
    // unused (always null/0L) on the Preview-before-insert-off path, which still writes to the
    // real field via [streamingSession] exactly as before this fix.
    private var lastBubblePartialText: String? = null
    private var lastBubblePartialAtMs: Long = 0L

    // Captured once a recording concludes (#45): the streaming session's tracked span, preserved
    // here (instead of recycled outright) so the eventual final injection can still reconcile it
    // even when that injection is delayed behind a preview-before-inject commit (#40) that runs
    // after teardownStreamingPreview() has already ended the live session. Consumed (and its node
    // recycled) by the very next injectText() call; flushed defensively if a new recording starts
    // or the service tears down before any injection ever consumes it. Only touched on the main thread.
    private var pendingStreamingHandoff: StreamingPreviewSession? = null

    // Local dictation history (#25), so a transcript survives even if injection fails.
    private val historyStore by lazy { DictationHistoryStore.forContext(this) }

    private val dp get() = resources.displayMetrics.density
    // #352: overlay x/y start after a left/top display cutout but not after a left/top nav bar, so
    // only those two come out of the size: the leading cutout, and the trailing nav bar the ring
    // would otherwise draw over. The full display size put a peeked ring past the landscape edge.
    private fun windowInsets(types: Int) = (getSystemService(WINDOW_SERVICE) as WindowManager)
        .currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(types)
    private val screenW get() = resources.displayMetrics.widthPixels -
        windowInsets(WindowInsets.Type.displayCutout()).left - windowInsets(WindowInsets.Type.navigationBars()).right
    private val screenH get() = resources.displayMetrics.heightPixels -
        windowInsets(WindowInsets.Type.displayCutout()).top - windowInsets(WindowInsets.Type.navigationBars()).bottom
    // The usable left edge: past a left nav bar, since x=0 lies under it.
    private val screenLeft get() = (windowInsets(WindowInsets.Type.navigationBars()).left -
        windowInsets(WindowInsets.Type.displayCutout()).left).coerceAtLeast(0)
    // The display's real left edge, where a left peek goes: x=0 starts after a left cutout.
    private val displayLeft get() = -windowInsets(WindowInsets.Type.displayCutout()).left

    override fun onServiceConnected() {
        DictationTextWriter.diagnostic = { Log.w(TAG, it) }
        instance = this
        // #156 guard rail: record that this install has a working service (the signal that
        // separates "killed by the OS shortcut switch" from "never enabled") and re-arm the
        // recovery banner's dismissal for the next fresh detection.
        InvocationGuardRail.recordServiceConnected(this)
        // #254: the service is demonstrably back, so any "Ramblr was turned off" notification in
        // the shade is now stale and must go immediately -- waiting for the next worker tick (up
        // to 15 minutes) would leave a notification claiming Ramblr is off while it is running.
        // recordServiceConnected above has already re-armed the dismissal flag, so a genuine
        // later loss still notifies.
        ServiceRecoveryNotifications.cancel(this)
        // Schedule the recovery check from here too, not just MainActivity.onCreate: a user who
        // sets Ramblr up through onboarding and never reopens the main screen would otherwise
        // never get the periodic check scheduled at all. KEEP makes the overlap a no-op.
        ServiceRecoveryWorker.schedule(this)
        CustomPersonaStore.ensureLegacySeeded(this)
        ProviderChainMigration.runIfNeeded(this)
        ProviderAccountMigration.runIfNeeded(this)
        // Event subscription follows the "Show the icon" choice: nothing beyond the static config
        // for ALWAYS (the default), window/focus changes otherwise. See RingEventTypes.
        startObservingRingMode()
        showOverlay()
        // Self-heal the recovery notification (#135): if the icon is hidden but the notification
        // was swiped away, this is the first moment we can put the way back on screen again.
        // Runs after showOverlay() so the overlay really is connected by the time we claim it is.
        IconVisibilityNotifications.repostIfMissing(this, overlayConnected = overlayView != null)
        registerAccessibilityButton()
        registerNetworkCallback()
        registerDisplayListener()
        registerScreenStateReceiver()
        thread { ProcessRecordingOrphanCleaner.cleanupOnce(cacheDir) }
        thread { ModelDownloader.pruneOrphanedModelDirs(this) }
        // Try to load local model in background
        thread { runtime.initLocalModel() }
        thread { runtime.initStreamingModel() }
    }

    /**
     * OS accessibility-shortcut trigger (#156 dual-component design): with
     * `flagRequestAccessibilityButton` declared in [SystemControlsAccessibilityService]'s config
     * XML (static-only for targetSdk > 29), the user can enable a nav-bar button, floating
     * shortcut, or volume-key hold for Ramblr -- an invocation surface that keeps working with
     * the floating ring hidden and adds no new permission. The registration lives here in the
     * base class and runs for BOTH components; on the default floating-icon component (no flag
     * in its XML) it's a harmless no-op -- the OS never routes a button event to a service
     * without the flag, so the callback simply never fires.
     *
     * Routes through [requestToggleRecording] rather than [onTap] directly so it shares the QS
     * tile's #136 guard: never start a recording while the icon is hidden and invisible -- a
     * silent live mic is a privacy problem, and both non-ring surfaces reach this path with the
     * ring potentially at alpha=0. [onServiceConnected] can re-fire without a new service
     * instance; re-registering the same callback object is safe because the framework stores
     * callbacks in a set keyed per callback instance, and ours lives in a val.
     */
    private val accessibilityButtonCallback =
        object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                requestToggleRecording()
            }
        }

    private fun registerAccessibilityButton() {
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(accessibilityButtonCallback)
        } catch (e: Exception) {
            // Some OEM skins bury or omit the accessibility button entirely; failing to register
            // must never take down the primary ring surface with it.
            Log.e(TAG, "Could not register accessibility button callback", e)
        }
    }

    /**
     * The primary reset trigger for [cleanupCursor], per ADR-0001 (#61): when the default
     * network changes (SSID/VPN/cell transition), the last-known-good waterfall step may be
     * wrong in the expensive direction -- e.g. the cursor stuck on a paid direct-provider step
     * after leaving home, still billed on every dictation after returning to the LAN where the
     * free OmniRoute step is reachable again. The 5-minute idle expiry inside the cursor is only
     * the backstop. `onAvailable` fires once at registration (cursor is already 0 -- harmless)
     * and then exactly on default-network switches, so no change-detection state is needed here.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runtime.onDefaultNetworkChanged()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Could not register network callback; cleanup cursor will rely on idle expiry only", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    /** A 180° flip keeps the display size, so [onConfigurationChanged] never fires for it (#352). */
    private fun registerDisplayListener() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        var lastRotation = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val rotation = dm.getDisplay(displayId)?.rotation ?: return
                val flipped = isFlipWithoutResize(lastRotation, rotation)
                lastRotation = rotation
                if (flipped) {
                    Log.i(TAG, "Display flipped 180° to rotation $rotation; repositioning ring")
                    handleScreenSizeChange()
                }
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        try {
            dm.registerDisplayListener(listener, handler)
            displayListener = listener
        } catch (e: Exception) {
            Log.e(TAG, "Could not register display listener; a 180° flip won't reposition the ring", e)
        }
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayListener = null
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
    }

    /**
     * Reapplies overlay visibility on SCREEN_ON, SCREEN_OFF, and USER_PRESENT so the ring hides
     * the instant the device locks and reappears right after unlock, closing the window where a
     * TYPE_ACCESSIBILITY_OVERLAY would otherwise sit visible and tappable over the lock screen
     * (see [overlayShouldBeVisible]'s doc for the security rationale). SCREEN_OFF is included
     * (not just USER_PRESENT/SCREEN_ON) because [KeyguardManager.isKeyguardLocked] can already be
     * true the instant the screen turns off, and there's no reason to wait for the next event to
     * hide it. All three are sticky-free protected broadcasts, so no permission is required.
     */
    private fun registerScreenStateReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                applyOverlayVisibility()
                // USER_PRESENT only (#135): the unlock is the natural checkpoint to restore a
                // swiped-away recovery notification -- the user is present, looking at the screen,
                // and about to reach for a ring that isn't there. Deliberately not SCREEN_ON/OFF:
                // those fire at the keyguard where the shade is a worse place to surface this, and
                // firing on all three would re-post up to three times per wake for no added value.
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    IconVisibilityNotifications.repostIfMissing(
                        this@WhisperAccessibilityService,
                        overlayConnected = overlayView != null,
                    )
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(receiver, filter)
            screenStateReceiver = receiver
        } catch (e: Exception) {
            Log.e(TAG, "Could not register screen state receiver; overlay may stay visible over the lock screen", e)
        }
    }

    private fun unregisterScreenStateReceiver() {
        val receiver = screenStateReceiver ?: return
        screenStateReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister screen state receiver", e)
        }
    }

    /**
     * Does nothing in the default [RingVisibilityMode.ALWAYS]: Ramblr reads no events. Only when
     * the user picks "only while the keyboard is open" / "while a text field is selected" is the
     * subscription widened (see [RingEventTypes]) and this looks at window and focus changes, purely
     * to learn whether a keyboard window or an editable field is up. It never reads text or screen
     * content -- only window types, [AccessibilityNodeInfo.isEditable] and the class name.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        val mode = RingVisibilityMode.read(this)
        if (!RingEventTypes.isRelevant(mode, type)) return
        val present = isKeyboardShowing() || (mode == RingVisibilityMode.TEXT_FIELD && isTextFieldFocused())
        if (!ringAnchorTracker.update(present)) return
        applyOverlayVisibility()
    }

    /**
     * Brings the event subscription and the ring in line with the saved [RingVisibilityMode]. Runs
     * whenever the saved mode changes (and once at connect). Resets [ringAnchorTracker] first: its
     * last answer was computed under the previous mode, so keeping it could swallow the first real
     * transition after e.g. KEYBOARD -> TEXT_FIELD.
     */
    internal fun onRingVisibilityModeChanged() {
        ringAnchorTracker.reset()
        ringEventSubscription.apply(RingVisibilityMode.read(this))
        applyOverlayVisibility()
    }

    /** Subscribes to the saved mode now and to every later change (the Behavior screen just
     *  writes the preference). */
    internal fun startObservingRingMode() {
        ringEventSubscription.apply(RingVisibilityMode.read(this))
        val prefs = getSharedPreferences("ramblr", MODE_PRIVATE)
        ringModePrefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RingVisibilityMode.KEY) onRingVisibilityModeChanged()
            // The keyboard (or Settings) changed the shared "Translate to" choice: keep the badge true.
            if (key == KeyboardTranslation.KEY_TARGET || key == KeyboardTranslation.KEY_LANGUAGES) refreshTranslationBadge()
        }
        ringModePrefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    override fun onInterrupt() {}

    /**
     * Fires reliably on a Pixel Fold fold/unfold (verified on-device: delivered to this
     * AccessibilityService in both directions, with [screenW]/[screenH] already reflecting the
     * new size) as well as on an ordinary rotation. [handleScreenSizeChange] tells the two apart
     * so rotation -- never repositioned before this fix -- stays a no-op.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleScreenSizeChange()
    }

    override fun onDestroy() {
        instance = null
        ringModePrefsListener?.let {
            getSharedPreferences("ramblr", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it)
        }
        ringModePrefsListener = null
        clearDictationTarget()
        unregisterNetworkCallback()
        unregisterDisplayListener()
        unregisterScreenStateReceiver()
        // Only tear down a runtime that was actually constructed. `runtime`'s getter is lazy
        // (see its kdoc above) -- if the service is destroyed before onServiceConnected ever ran
        // (e.g. the framework unbinds it right after a failed/aborted bind), naively reading
        // `runtime` here would construct a brand-new DictationRuntime for the sole purpose of
        // immediately shutting it down: wasted work, and a DictationRuntime instance nobody
        // needed that would itself need tearing down again. Guard on the backing field instead.
        runtimeInstance?.let { runtime ->
            // The runtime owns the recording/transcription teardown: state machine reset, reader
            // teardown, stray session release, watchdog/guard/in-flight cancel, wakelock release,
            // cleanup-model release, and streaming teardown -- in exactly the pre-extraction order
            // (see [DictationRuntime.shutdown]).
            runtime.shutdown()
            // Release the native transcriber recognizers too (M7): like onTrimMemory, replace(null) can
            // block on an in-flight transcription, so it runs off the main thread. Without this, a
            // service destroy/recreate in the same process (accessibility toggle off/on) leaves the old
            // instance's recognizers (batch model up to 465MB) resident alongside the new ones until
            // process death.
            runtime.releaseTranscribersAsync()
        }
        runtimeInstance = null
        flushPendingStreamingHandoff()
        handler.removeCallbacks(expirePendingInjection)
        pendingInjection?.node?.recycle()
        pendingInjection = null
        pendingInjectionRetry?.let { handler.removeCallbacks(it) }
        pendingInjectionRetry = null
        // pendingClipboardRestore is deliberately left scheduled: it only touches ClipboardManager
        // (no AccessibilityNodeInfo), so it's still safe to run on the main Looper after the
        // service component itself is destroyed, and skipping it would leave the user's clipboard
        // holding the just-dictated text instead of their prior content (#5).
        handler.removeCallbacks(previewTimeoutRunnable)
        // A pending preview is already recorded to history as of #73 (beginPreview upserts it the
        // moment the cleanup candidate exists, not only once injection eventually happens) -- so
        // there's nothing left to persist here. Just drop the in-memory state; the history row
        // survives with the raw+candidate text exactly as it was when the preview began.
        pendingPreview = null
        pendingPreviewTarget?.node?.recycle()
        pendingPreviewTarget = null
        dismissStyleMenu()
        removeOverlay()
        super.onDestroy()
    }

    /**
     * Under memory pressure, drop the cached local-cleanup model (#74) -- it's a pure cache, and
     * the next dictation reloads it. RUNNING_LOW is the threshold; every higher-numbered level
     * (RUNNING_CRITICAL, plus the UI_HIDDEN/BACKGROUND/MODERATE/COMPLETE band, which all signal
     * at least as much pressure or less foreground relevance) qualifies too.
     *
     * The transcriber slots ARE released here too, as of #98 (Claude Fable 5 STT model consult):
     * before this, they were the one local resource with NO memory-pressure handling at all --
     * loaded once at service connect and held forever, unlike [LocalCleanupModelHolder]'s
     * pre-warm/idle-unload/trim-release discipline. On a phone where Ramblr was independently
     * measured as the single largest RSS consumer on the device, a permanently-resident batch
     * recognizer (up to 465MB) plus streaming recognizer stacked on top of the cleanup model was
     * a real, avoidable contributor to the exact mmap-eviction thrash the #92 native-hang
     * investigation diagnosed. [warmUpTranscribersIfTrimmed] reloads them the next time recording
     * starts, the same pre-warm timing [warmUpLocalCleanupModelIfNeeded] already uses -- the
     * reload overlaps with the user still talking, so this should cost no perceived latency on
     * the (much more common) case where memory pressure has already passed by the next dictation.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runtime.onTrimMemory(level)
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { runtime.reloadModel() }

    /** Reload the streaming preview model (called from MainActivity when the toggle or the
     *  streaming model's install state changes). */
    fun reloadStreamingModel() { runtime.reloadStreamingModel() }

    // --- Overlay ---

    private fun showOverlay() {
        // #260: no-op if the overlay is already attached. Without this, a second call would add
        // a duplicate window and overwrite the tracking fields pointing at the first one,
        // orphaning it beyond removeOverlay()'s reach.
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ringSizeDp = OverlayAppearancePrefs.load(this).ringSizeDp
        val buttonSize = (dpScaledToRing(BTN_DP, ringSizeDp) * dp).toInt()
        val ringSize = (ringSizeDp * dp).toInt()
        val pad = (dpScaledToRing(PAD_DP, ringSizeDp) * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        // Busy ring drawn INSIDE the button's own circular fill (Trevor's fix, 2026-07-06) rather
        // than in the ~6dp margin outside it: the prior outer-ring placement was invisible over
        // Google Keep's white note editor because COLOR_RING (a near-white gray) had no contrast
        // against a white host-app background it was drawn over. Sizing/z-ordering the ring to sit
        // on top of the button's OWN fill color (whatever COLOR_BUSY/custom appearance fill is
        // active) makes contrast fully within this app's control regardless of what's behind the
        // overlay -- no host-app background can ever show through it again.
        // visibility is left at its VISIBLE default here and deferred to a post{} below, rather
        // than set to GONE inline before attach (Pixel Fold display-delay bug, reported by Trevor
        // /w r/PixelFold thread https://www.reddit.com/r/PixelFold/comments/1ficw8u/ as reference):
        // Pixel OS has a confirmed bug (root-caused by the Quick Cursor dev, filed against Google)
        // where a view attached to the WindowManager with visibility=GONE already set at attach
        // time causes a ~2s black-screen delay on the NEXT fold/unfold. Deferring the GONE
        // assignment to the following frame via post{} keeps the view VISIBLE for the single
        // attach frame (imperceptible -- it's 0-alpha/no drawable difference either way since
        // isIndeterminate hasn't started spinning) while avoiding the OS-level trigger condition.
        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
        }
        ring.post { ring.visibility = View.GONE }

        val img = ImageView(this).apply {
            setPadding(pad, pad, pad, pad)
        }

        val overlay = FrameLayout(this).apply {
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            // Added after img (on top in z-order) and sized to the button itself, not the larger
            // outer ringSize, so it draws as a ring inset within the button's circular fill instead
            // of in the transparent margin around it.
            addView(ring, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }
        // Language badge ("ES") while a "Translate to" target is set. A child of the same ring
        // window, so it never changes the window size or touch area, and it is not clickable so
        // touches fall through to the ring's own listener. GONE (and absent from layout) when the
        // target is Original; see [refreshTranslationBadge].
        val badge = buildTranslationBadge(ringSizeDp)
        overlay.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ))

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_LAYOUT_NO_LIMITS (Feature A): auto-peek intentionally slides most of the ring
            // off the edge of the display (see RingPeek.peekedX), leaving only a small sliver
            // on-screen. Without this flag WindowManager clamps any window position back to fully
            // on-screen bounds, silently negating the peek animation -- the window would visibly
            // animate but always snap back to its normal resting x, i.e. peek would appear to do
            // nothing at all.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Restore the last dragged position across a service recreation (OS kill/restart,
            // app update, etc. -- see #101) instead of always resetting to the hardcoded
            // right-edge/vertical-center default. The saved value is sanity-clamped to the
            // CURRENT screen bounds via clampRestoredPosition (shared with the drag-release
            // clamp pattern) since the screen size may have changed since it was saved. Only a
            // true first-run/fresh-install (no saved value yet) falls through to the hardcoded
            // default.
            val savedX = prefs().getInt(PREF_OVERLAY_X, Int.MIN_VALUE)
            val savedY = prefs().getInt(PREF_OVERLAY_Y, Int.MIN_VALUE)
            if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
                val (clampedX, clampedY) = clampRestoredPosition(savedX, savedY, screenW, screenH, ringSize, margin, screenLeft)
                x = clampedX
                y = clampedY
            } else {
                x = screenW - ringSize - margin
                y = screenH / 2 - ringSize / 2
            }
        }
        lastScreenW = screenW
        lastScreenH = screenH

        // Where the ring window sat, and where the finger was, at ACTION_DOWN. The ring is only
        // moved once the gesture has been classified as a drag (see RingGestureTracker), so a held
        // thumb's natural roll never nudges it.
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        // Tap / long-press / drag disambiguation (#322) lives in the pure RingGestureTracker so it
        // is unit-tested; this listener only performs the side effects.
        val viewConfiguration = ViewConfiguration.get(this)
        val gesture = RingGestureTracker(
            dragSlopPx = RingGestureTracker.dragSlopPx(dp, viewConfiguration.scaledTouchSlop),
            postLongPressSlopPx = RingGestureTracker.postLongPressSlopPx(dp, viewConfiguration.scaledTouchSlop),
            longPressTimeoutMs = RingGestureTracker.longPressTimeoutMs(ViewConfiguration.getLongPressTimeout()),
        )
        // Long-press behavior is entirely state-driven -- see overlayLongPressActionFor's doc for
        // the full state -> action map (cancel while TRANSCRIBING, undo while IDLE with a pending
        // injection (#27), or the #53 style/cleanup menu in the one remaining reachable state:
        // IDLE with nothing pending). RECORDING never arms the timer at all, exactly as before.
        //
        // Set only when this gesture's long-press actually opened the style menu (#57) -- lets
        // a hold-then-drag single out "the menu I just opened" from cancel-transcription/undo
        // already having fired, neither of which is a persistent overlay that needs dismissing.
        var styleMenuOpenedByLongPress = false
        // Set when ACTION_DOWN consumed this gesture as a peek-restore tap (Feature A) -- the rest
        // of the gesture must then no-op instead of falling through to onTap()/drag-snap using
        // tracker state that was never updated for this gesture.
        var consumedByPeekRestore = false
        val longPressAction = Runnable {
            val action = overlayLongPressActionFor(runtime.currentState(), hasPendingInjection = pendingInjection != null)
            // Drag wins (#57): a hold that already became a drag never fires a long-press. Either
            // way the touch can no longer end as a tap (#322).
            if (!gesture.onLongPressTimer()) return@Runnable
            if (action == OverlayLongPressAction.NONE) return@Runnable
            gesture.markLongPressFired()
            armIdlePeekTimer()
            when (action) {
                OverlayLongPressAction.CANCEL_TRANSCRIPTION -> cancelTranscription()
                OverlayLongPressAction.UNDO_INJECTION -> undoLastInjection()
                OverlayLongPressAction.SHOW_STYLE_MENU -> { showStyleMenu(); styleMenuOpenedByLongPress = true }
                OverlayLongPressAction.NONE -> Unit
            }
        }

        // A drag finished (or was cancelled by the system): settle the ring on the nearest edge.
        fun settleRingAfterDrag(v: View) {
            params.x = if (params.x + ringSize / 2 > screenW / 2)
                screenW - ringSize - margin else screenLeft + margin
            // Clamp y into the on-screen band too (M1): FLAG_LAYOUT_NO_LIMITS disables
            // WindowManager's own clamping (needed for peek), so a drag that ends near
            // the top/bottom edge could otherwise park the ring wholly off-screen with
            // no on-screen affordance left to recover it.
            params.y = params.y.coerceIn(margin, (screenH - ringSize - margin).coerceAtLeast(margin))
            wm.updateViewLayout(v, params)
            feedbackLayoutParams?.let {
                positionFeedback(it, params, feedbackView?.height ?: 0)
                wm.updateViewLayout(feedbackView, it)
            }
            // Persist the newly-settled drag position (#101) so it survives a later
            // service recreation instead of only living in this in-memory params
            // object. Deliberately NOT called from ACTION_MOVE (would fire on every
            // drag frame) -- only here, once the gesture has actually finalized a
            // resting position.
            persistOverlayPosition(params.x, params.y)
        }

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    armIdlePeekTimer()
                    consumedByPeekRestore = false
                    // #128: the whole touch path used to be silent, so a tap that reached the
                    // listener and a tap that never arrived at all (stolen by the system
                    // edge-swipe gesture, or landing on the window's transparent padding) were
                    // indistinguishable in logcat -- both left "zero trace". This one line is the
                    // discriminator: if it's absent for a failed tap, the touch never reached us.
                    // Its counterpart "Ring touch UP/CANCEL" (#322) records how the gesture ended.
                    Log.i(TAG, "Ring touch DOWN: peeked=$isPeeked state=${runtime.currentState()} x=${params.x}")
                    // A touch-down on a currently-peeked ring is a restore tap, not the start of a
                    // normal drag/tap/long-press gesture (Feature A): consume it here so it can
                    // never also fall through to onTap()'s mic-toggle on ACTION_UP.
                    //
                    // #119 exception: with SingleTapRestoreToggle on, the user has opted into a
                    // single tap both restoring AND recording, so this must NOT short-circuit the
                    // gesture the way the two-tap default does. Instead it only triggers the
                    // restore side effect here and then falls through into the exact same
                    // tap/long-press/drag setup used below for a normal (non-peeked) touch-down --
                    // reusing that existing disambiguation rather than inventing a new one means a
                    // long-press or a drag started from a peeked ring still resolves correctly;
                    // only a genuine tap (see RingGestureTracker) ends up calling onTap().
                    // The restore animates the window, so a drag that begins on a just-restored ring
                    // must be measured from where the animation is heading, not the peeked x.
                    val restoreTargetX = if (isPeeked) prePeekX else null
                    if (isPeeked) {
                        restoreFromPeek()
                        if (!SingleTapRestoreToggle.isEnabled(this)) {
                            consumedByPeekRestore = true
                            return@setOnTouchListener true
                        }
                    }
                    startX = restoreTargetX ?: params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    styleMenuOpenedByLongPress = false
                    val pendingAction = overlayLongPressActionFor(runtime.currentState(), hasPendingInjection = pendingInjection != null)
                    val armed = pendingAction != OverlayLongPressAction.NONE
                    // Honour a longer system long-press delay (accessibility) but never shorten
                    // below RingGestureTracker.MIN_LONG_PRESS_MS; re-read every touch so a settings change applies
                    // immediately.
                    gesture.longPressTimeoutMs = RingGestureTracker.longPressTimeoutMs(ViewConfiguration.getLongPressTimeout())
                    gesture.onDown(ev.rawX, ev.rawY, ev.eventTime, longPressArmed = armed)
                    if (armed) handler.postDelayed(longPressAction, gesture.longPressTimeoutMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (consumedByPeekRestore) return@setOnTouchListener true
                    val move = gesture.onMove(ev.rawX, ev.rawY)
                    if (move == RingMoveResult.DRAG_STARTED) {
                        handler.removeCallbacks(longPressAction)
                        // A SingleTapRestoreToggle restore may still be animating the window; the
                        // finger now owns it.
                        peekAnimator?.cancel()
                        // The hold that opened the style menu (#57) has turned into a drag --
                        // dismiss the now-unwanted menu and fall back to plain drag-to-reposition.
                        if (gesture.dragStartedAfterLongPress && styleMenuOpenedByLongPress) {
                            dismissStyleMenu()
                            styleMenuOpenedByLongPress = false
                        }
                    }
                    // Inside the slop the ring stays put: a held thumb's natural roll must not nudge
                    // it. Once dragging, it tracks the finger 1:1 from the DOWN point.
                    if (move != RingMoveResult.HOLDING) {
                        params.x = startX + (ev.rawX - touchX).toInt()
                        params.y = startY + (ev.rawY - touchY).toInt()
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params, feedbackView?.height ?: 0)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressAction)
                    armIdlePeekTimer()
                    if (consumedByPeekRestore) return@setOnTouchListener true
                    val outcome = gesture.onUp(ev.rawX, ev.rawY, ev.eventTime)
                    val stateAtUp = runtime.currentState()
                    // The hold outlasted the long-press time but the main thread hadn't run the
                    // timer yet: run the long-press now rather than mis-reading the lift as a tap.
                    if (outcome == RingGestureOutcome.HOLD_DUE) longPressAction.run()
                    // #322: the missing UP line is why a long-press that recorded was undiagnosable.
                    Log.i(
                        TAG,
                        "Ring touch UP: outcome=$outcome held=${gesture.heldMs(ev.eventTime)}ms " +
                            "moved=${gesture.displacementPx(ev.rawX, ev.rawY).toInt()}px/" +
                            "${(gesture.displacementPx(ev.rawX, ev.rawY) / dp).toInt()}dp " +
                            "max=${gesture.maxDisplacementPx.toInt()}px longPressFired=${gesture.longPressFired} state=$stateAtUp"
                    )
                    when (outcome) {
                        RingGestureOutcome.TAP -> onTap()
                        RingGestureOutcome.DRAG -> settleRingAfterDrag(v)
                        RingGestureOutcome.HOLD_CONSUMED, RingGestureOutcome.HOLD_DUE -> Unit
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressAction)
                    if (!consumedByPeekRestore) {
                        val wasDragging = gesture.onCancel()
                        Log.i(TAG, "Ring touch CANCEL: dragging=$wasDragging max=${gesture.maxDisplacementPx.toInt()}px")
                        // A system cancel mid-drag must still settle the ring on an edge instead of
                        // stranding it wherever the finger stopped.
                        if (wasDragging) settleRingAfterDrag(v)
                    }
                    true
                }
                else -> false
            }
        }

        // Tapping the feedback bubble either retries with the raw (pre-cleanup) transcript (#27)
        // or, on a clipboard fallback, re-copies the text (#5); only touchable while one of those
        // is actually on offer — see setFeedbackTouchable.
        // Real root cause (verified via UID/window-token-attributed BLASTSyncEngine analysis on
        // real device logs, not just timing correlation): this window becomes its own top-level
        // WindowManager window via the second wm.addView call below, sharing the same
        // TYPE_ACCESSIBILITY_OVERLAY WindowToken as `ring`. On a fold-triggered screen-off
        // display switch, WMS synchronously BLAST-syncs every window under that token before
        // allowing screen-on, with a fixed ~2000ms timeout. A window whose root is View.GONE
        // never draws a frame and can never satisfy that sync, so it eats the full timeout on
        // every such fold regardless of what alpha is set to. The root view is therefore kept
        // VISIBLE permanently (see hideFeedback's comment) with alpha=0f expressing the hidden
        // state instead -- alpha was already correctly identified as the safe axis here, GONE
        // was the actual trigger, just not only "at attach time" as originally believed.
        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            isClickable = true
            setOnClickListener { onFeedbackTapped() }
            // #121: TalkBack never announced bubble text changes -- including actionable states
            // like "Preview: ... tap to insert" and "Cleanup failed (...)" -- because this view had
            // no live region set. POLITE (rather than a per-case ASSERTIVE-for-errors split) is
            // used uniformly here: it's a simple, correct fix for every state this bubble shows,
            // and none of those states are urgent/interrupting enough to justify stealing focus
            // from whatever the user is already doing (ASSERTIVE's whole point) at the cost of the
            // added complexity of threading a "is this an error" flag through to every call site.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // No post-attach GONE assignment (real root cause fix -- see hideFeedback's comment
        // above): this window's root view stays VISIBLE permanently; hidden state is expressed
        // purely via alpha, matching the ring's fix. alpha is already 0f above so nothing is
        // visibly drawn, and FLAG_NOT_TOUCHABLE / setFeedbackTouchable gate interaction, exactly
        // as before -- only the window's participation in WMS's fold-triggered BLAST sync
        // changes, since a permanently-attached VISIBLE window can actually draw a frame and
        // satisfy the sync instead of stalling it for the full 2000ms timeout.

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params, feedbackHeight = 0)

        // #260: record each window as it attaches, rather than assigning every tracking field
        // after both addView calls returned. Under the old ordering a throw on the second call
        // left the first window attached with overlayView still null, and removeOverlay()'s
        // null-guarded teardown could then never remove it -- the window leaked for the life of
        // the process. These are TYPE_ACCESSIBILITY_OVERLAY windows whose token belongs to the
        // live service connection, so addView legitimately throws BadTokenException when that
        // connection is torn down between onServiceConnected() and here (an automation app
        // toggling the service off mid-connect does exactly that). Uncaught, it propagated out
        // of a lifecycle callback and took the process down; failing to attach the overlay
        // should cost the user their floating button, not the whole service.
        try {
            wm.addView(overlay, params)
            overlayView = overlay
            button = img
            spinner = ring
            translationBadge = badge
            refreshTranslationBadge()
            layoutParams = params
            wm.addView(feedback, feedbackParams)
            feedbackView = feedback
            feedbackLayoutParams = feedbackParams
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Could not attach overlay windows; floating button unavailable", e)
            removeOverlay()
            return
        } catch (e: IllegalStateException) {
            // "View has already been added to the window manager" -- unreachable via the entry
            // guard above, but unwinding beats leaking a window if it ever is reached.
            Log.e(TAG, "Could not attach overlay windows; floating button unavailable", e)
            removeOverlay()
            return
        }
        applyButtonAppearance(COLOR_IDLE)
        applyOverlayVisibility()
        armIdlePeekTimer()
        applyGestureExclusion(overlay, ringSize)
    }

    /**
     * Reserves the ring's own bounds as a system-gesture-exclusion zone so a tap that lands on it
     * near the screen edge (especially while peeked -- see RingPeek/attemptAutoPeek, which parks
     * most of the ring off-screen on purpose) is delivered to this overlay instead of being
     * eligible for the system edge-swipe-for-home/recents gesture to steal first. Confirmed via a
     * real on-device logcat capture: without this, a tap on the peeked ring (parked at
     * screenW - 14dp, deep inside the edge gesture inset) was consumed by
     * `InputDispatcher: Channel [Gesture Monitor] swipe-up is stealing input gesture`, backgrounding
     * the foreground app into the recents/home transition instead of ever reaching our
     * OnTouchListener -- our overlay never saw the touch at all, so `restoreFromPeek()` never fired
     * and the ring was left exactly where it was, which read as "it just disappears and comes back
     * still hidden." The exclusion rect is in the view's own local coordinate space (0,0 to its own
     * width/height), so it travels with the window automatically across drag-to-reposition, peek,
     * and fold/rotation repositioning without needing to be reapplied on every layout change.
     * Requires API 29+ (minSdk is 30, see build.gradle.kts) so no version guard is needed.
     */
    private fun applyGestureExclusion(overlay: View, ringSize: Int) {
        overlay.setSystemGestureExclusionRects(listOf(Rect(0, 0, ringSize, ringSize)))
    }

    /** Scales [valueDp] (one of [BTN_DP]/[PAD_DP], defined against the [RING_DP] reference size)
     *  to whatever ring size the user has actually configured (#43), so the mic glyph and its
     *  padding stay in the same proportion to the ring regardless of its dp size. */
    private fun dpScaledToRing(valueDp: Int, ringSizeDp: Int): Int = valueDp * ringSizeDp / RING_DP

    /**
     * Reacts to a screen-size change delivered via [onConfigurationChanged] -- a fold/unfold or an
     * ordinary rotation, handled the same way (#354: rotation used to leave a docked ring at its old
     * x, mid-screen in landscape). Other configuration changes leave the size alone and are
     * ignored. On a size change, the ring is
     * re-snapped to whichever edge it was already closest to and its y position is preserved
     * proportionally (rather than fought back to center) using the same [snappedXForScreenChange]/
     * [proportionalYForScreenChange] logic covered by unit tests -- then the feedback bubble is
     * re-derived from the ring's new params exactly as drag-to-reposition already does, via
     * [positionFeedback]. Any open style menu (#53) is dismissed rather than repositioned, since
     * its anchor math would otherwise go stale mid-fold.
     *
     * Peek-aware (fold/peek interaction bug, reported by Trevor): [snappedXForScreenChange] always
     * computes the DOCKED (fully visible) edge position -- fine when the ring isn't peeked, but
     * wrong while it is. Before this fix, a fold/unfold while peeked forced the ring to that docked
     * position on the new screen size while leaving [isPeeked] stuck true and [prePeekX] stuck at
     * the OLD screen's docked x. That desync caused two bugs: (1) the next tap saw isPeeked==true
     * and called [restoreFromPeek] using the stale, wrong-screen prePeekX, producing a pointless
     * slide that looked like the ring "disappeared and came back"; (2) [attemptAutoPeek] bails
     * immediately whenever isPeeked is true, so auto-peek could never fire again until the service
     * restarted. Now, while peeked, [prePeekX] itself (the underlying docked x) is re-snapped for
     * the new screen size, and the ring's actual window x is re-derived as the PEEKED position for
     * that new docked x via [RingPeek.peekedX] -- so a fold/unfold while peeked keeps the ring
     * peeked, correctly positioned for the new screen, with isPeeked/prePeekX staying consistent.
     */
    private fun handleScreenSizeChange() {
        dismissStyleMenu()
        val params = layoutParams ?: return
        val newScreenW = screenW
        val newScreenH = screenH
        if (newScreenW == lastScreenW && newScreenH == lastScreenH) return

        val ringSize = params.width
        val margin = (MARGIN_DP * dp).toInt()

        if (isPeeked) {
            val oldDockedX = prePeekX ?: params.x
            val peekVisiblePx = (PeekVisibleSize.dpOrDefault(this) * dp).toInt()
            val (newPeekedX, newDockedX) = peekedPositionForScreenChange(
                oldDockedX, lastScreenW, newScreenW, ringSize, margin, peekVisiblePx, ringInsetPx(), screenLeft, displayLeft
            )
            if (canPeekAt(newDockedX, ringSize)) {
                prePeekX = newDockedX
                params.x = newPeekedX
                Log.i(TAG, "Screen change while peeked: re-derived x=$newPeekedX (docked $newDockedX) for ${newScreenW}px screen")
            } else {
                // #352: the navigation bar is on this edge now; stay out beside it.
                unpeekInPlace(params, newDockedX)
                Log.i(TAG, "Screen change while peeked: navigation bar on this edge, docked at x=$newDockedX")
            }
        } else {
            params.x = snappedXForScreenChange(params.x, lastScreenW, newScreenW, ringSize, margin, screenLeft)
        }
        params.y = proportionalYForScreenChange(params.y, lastScreenH, newScreenH, ringSize, margin)
        Log.i(TAG, "Screen change: ring at (${params.x},${params.y}) for ${newScreenW}x${newScreenH} screen")
        lastScreenW = newScreenW
        lastScreenH = newScreenH

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Deferred via handler.post (Pixel Fold display-transition stall, same root cause as
        // applyOverlayVisibility's alpha fix above): onConfigurationChanged fires *during* the
        // OS's own WindowManager DeferredDisplayUpdater display-resize transition for the fold.
        // Calling wm.updateViewLayout() synchronously here re-enters that in-flight transition
        // with more overlay-window mutations, competing with it for the same window state right
        // when WMS needs a stable snapshot to dispatch (Transition#sent) the resize. Posting to
        // the main-thread handler runs this on the next looper iteration, after the OS's own
        // transition has already been queued/dispatched, so the ring reposition no longer
        // contends with it. The position math is unchanged -- only the WM mutation is deferred by
        // one frame, which is imperceptible for a repositioning animation.
        handler.post {
            overlayView?.let { wm.updateViewLayout(it, params) }
            feedbackLayoutParams?.let {
                positionFeedback(it, params, feedbackView?.height ?: 0)
                wm.updateViewLayout(feedbackView, it)
            }
        }
        // Persist the fold/unfold-settled position (#101) so a subsequent service recreation
        // restores here rather than the hardcoded default -- mirrors the drag-release persist
        // above.
        persistOverlayPosition(params.x, params.y)
    }

    /**
     * Applies [overlayShouldBeVisible] to the live overlay views (#35, Feature B): hides the ring
     * (draw + touch) and dismisses the feedback bubble and any open style menu (#53) while
     * MainActivity is foregrounded, the user has explicitly hidden the icon via the long-press
     * "Hide icon" row ([IconHiddenState]), OR the device is currently locked at the keyguard (see
     * [isKeyguardLocked] -- closes a real unauthorized-mic-activation gap, since
     * TYPE_ACCESSIBILITY_OVERLAY windows otherwise draw on top of the lock screen by design). This
     * only toggles presentation -- [layoutParams] and [feedbackLayoutParams] stay non-null
     * throughout, so drag-to-reposition is untouched and the overlay reappears exactly where it
     * was left. Recording/transcription state is never touched here. Internal (not private) so
     * [RestoreIconReceiver] can re-apply visibility immediately after flipping [IconHiddenState]
     * back off from outside this class.
     */
    internal fun applyOverlayVisibility() {
        // #256: only ever reads currentForegroundPackageName() at this same on-demand trigger
        // point applyOverlayVisibility() already runs at (app foreground change, screen/keyguard
        // events, hide-icon toggle, force-visible override) -- no new subscription or polling
        // added. See ExclusionGating's doc for the honest limit this implies: the ring can lag a
        // real foreground switch by however long until one of those existing triggers next fires.
        val excludedForeground = ExclusionGating.ringHiddenForExclusion(
            currentForegroundPackageName(),
            PerAppExclusionStore.exclusions(this),
        )
        val visibilityMode = RingVisibilityMode.read(this)
        val gated = visibilityMode != RingVisibilityMode.ALWAYS
        val hiddenByVisibilityMode = ringHiddenByVisibilityMode(
            mode = visibilityMode,
            // Only query windows/focus when a gate is on; the results are ignored for ALWAYS.
            keyboardShowing = gated && isKeyboardShowing(),
            textFieldFocused = visibilityMode == RingVisibilityMode.TEXT_FIELD && isTextFieldFocused(),
            // runtimeInstance, not runtime: reading the lazy getter here would construct one.
            dictationIdle = (runtimeInstance?.currentState() ?: RecordingStateMachine.State.IDLE) ==
                RecordingStateMachine.State.IDLE,
            bubbleShowing = feedbackShowing || pendingPreview != null,
        )
        val visible = overlayShouldBeVisible(
            mainActivityForeground,
            IconHiddenState.isHidden(this),
            isKeyguardLocked(),
            overlayForceVisibleOverride,
            excludedForeground,
            hiddenByVisibilityMode,
        )
        setOverlayTouchable(visible)
        // alpha, not View.GONE (Pixel Fold display-transition stall, root-caused via Opus + real
        // on-device logcat capture: this runs on the same SCREEN_ON/SCREEN_OFF/USER_PRESENT/
        // keyguard events that drive a fold's own WindowManager DeferredDisplayUpdater display-
        // resize transition. Setting the root overlay view to GONE tears down its window surface
        // and drops it from the OS's accessibility window set; when that happens *during* the
        // fold transition, WMS logs "Cannot find window which accessibility connection is added
        // to" and the transition doesn't get dispatched (Transition#sent) for ~2s -- that stall
        // *is* the black screen. It's worse while locked because GONE persists for the whole
        // locked fold, so the window stays orphaned until an authenticated wake rebuilds it.
        // alpha=0f keeps the window's surface alive and registered with WMS at all times, so the
        // display transition never has to re-resolve it. Fully equivalent from a UX/security
        // standpoint: setOverlayTouchable(visible) above already gates FLAG_NOT_TOUCHABLE, so a
        // "hidden" overlay is alpha=0 (invisible) + non-touchable (can't be interacted with),
        // preserving the exact same keyguard/hide-icon guarantees as the old GONE state.
        overlayView?.alpha = if (visible) 1f else 0f
        if (!visible) {
            handler.post(hideFeedback)
            dismissStyleMenu()
        }
    }

    /** Whether an on-screen keyboard window is currently showing, for [RingVisibilityMode].
     *  Needs flagRetrieveInteractiveWindows (accessibility_service_config.xml); an empty window
     *  list means window retrieval isn't working at all, so this fails open to "showing" rather
     *  than hiding the ring for good. */
    private fun isKeyboardShowing(): Boolean {
        val all = windows
        if (all.isNullOrEmpty()) return true
        return all.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    /** Re-evaluates visibility after a dictation state change, but only when a visibility-mode
     *  gate is on -- every other gate ignores dictation state, so there's nothing to redo. */
    private fun reapplyVisibilityModeGate() {
        if (RingVisibilityMode.read(this) != RingVisibilityMode.ALWAYS) handler.post { applyOverlayVisibility() }
    }

    /** Whether the node holding input focus (in any window) is an editable text field -- the
     *  anchor for [RingVisibilityMode.TEXT_FIELD]. It survives the keyboard being dismissed with
     *  Back, which is exactly the case that mode exists for. */
    private fun isTextFieldFocused(): Boolean {
        val focused = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: RuntimeException) { null } ?: return false
        return try {
            focused.isEditable || focused.className?.contains("EditText") == true
        } finally {
            focused.recycle()
        }
    }

    /** Whether the device is currently locked at the keyguard -- see [overlayShouldBeVisible]'s
     *  doc for why this gates overlay visibility. Uses [KeyguardManager.isKeyguardLocked], which
     *  covers both a secure lock (PIN/pattern/biometric) and a plain swipe-to-unlock keyguard;
     *  fails open to "not locked" only if the system service is somehow unavailable, since an
     *  overlay staying hidden a moment too long is a far smaller problem than one appearing where
     *  it shouldn't. */
    private fun isKeyguardLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: false

    /** Adds/removes FLAG_NOT_TOUCHABLE on the ring window so a hidden overlay (#35) lets touches
     *  fall through to whatever's underneath -- e.g. MainActivity's Settings switches -- instead
     *  of the window silently swallowing them despite its view being GONE. */
    private fun setOverlayTouchable(touchable: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        fun WindowManager.LayoutParams.applyTouchable() {
            flags = if (touchable) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        layoutParams?.let { it.applyTouchable(); overlayView?.let { v -> wm.updateViewLayout(v, it) } }
    }

    // --- Auto-hide-to-peek (Feature A, see RingPeek) ---

    /** Any interaction with the ring (drag, tap, long-press) calls this to re-arm the idle timer,
     *  cancelling whatever peek was previously scheduled and restoring the ring if it was already
     *  peeked when the new interaction began. Delay is read fresh from [AutoPeekDelay] each call
     *  (rather than cached) so a change in settings takes effect on the very next
     *  interaction, no service restart needed -- same pattern as [AutoPeekToggle]. */
    private fun armIdlePeekTimer() {
        handler.removeCallbacks(idlePeekRunnable)
        handler.postDelayed(idlePeekRunnable, AutoPeekDelay.millisOrDefault(this))
    }

    /** Fires once the configured [AutoPeekDelay] has elapsed with no ring interaction. Never peeks
     *  while actively recording or while cleanup/transcription is in flight (RingPeek.shouldAutoPeek),
     *  nor while the ring itself isn't currently showing (e.g. MainActivity foregrounded, #35) or
     *  already peeked, nor at all while the user has turned auto-peek off in settings
     *  (see [AutoPeekToggle]) -- the idle timer keeps re-arming itself in that case so the ring is
     *  ready to peek again the moment the setting is turned back on, without needing a service
     *  restart. */
    private fun attemptAutoPeek() {
        if (isPeeked) return
        if (!AutoPeekToggle.isEnabled(this)) { armIdlePeekTimer(); return }
        if (!RingPeek.shouldAutoPeek(runtime.currentState())) { armIdlePeekTimer(); return }
        val overlay = overlayView ?: return
        // Mirrors applyOverlayVisibility's alpha-not-GONE fix (Pixel Fold display-transition
        // stall): hidden state is now expressed as alpha=0f, not View.GONE.
        if (overlay.alpha == 0f) { armIdlePeekTimer(); return }
        val params = layoutParams ?: return

        val ringSize = params.width
        if (!canPeekAt(params.x, ringSize)) { armIdlePeekTimer(); return }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val peekVisiblePx = (PeekVisibleSize.dpOrDefault(this) * dp).toInt()
        val targetX = RingPeek.peekedX(params.x, screenW, ringSize, peekVisiblePx, ringInsetPx(), displayLeft)

        prePeekX = params.x
        isPeeked = true
        refreshTranslationBadge()
        Log.i(TAG, "Peeking ring: x=${params.x} -> $targetX (sliver ${peekVisiblePx}px, inset ${ringInsetPx()}px)")
        animateRingX(params, wm, targetX)
    }

    private fun canPeekAt(x: Int, ringSize: Int): Boolean {
        val nav = windowInsets(WindowInsets.Type.navigationBars())
        return RingPeek.canPeek(x, screenW, ringSize, nav.left, nav.right)
    }

    /** The transparent gap between the ring WINDOW's edge and the mic button actually drawn inside
     *  it (#128) -- the window is [RING_DP]-scaled while the button is the smaller [BTN_DP],
     *  centred, so peeking measured against the window leaves ~6dp of nothing on-screen instead of
     *  ring. See [RingPeek.peekedX]. */
    private fun ringInsetPx(): Int {
        val ringSizeDp = OverlayAppearancePrefs.load(this).ringSizeDp
        return ((ringSizeDp - dpScaledToRing(BTN_DP, ringSizeDp)) * dp / 2).toInt().coerceAtLeast(0)
    }

    /** Restores the ring to its pre-peek position immediately -- called on a touch-down that
     *  lands on a peeked ring (see overlay.setOnTouchListener). Deliberately does NOT call onTap(),
     *  so un-peeking a tap never also toggles the mic. */
    private fun restoreFromPeek() {
        val params = layoutParams ?: return
        // #128: prePeekX being null while isPeeked is true is a state desync, and the old
        // `?: return` swallowed it without a trace -- the ring stayed peeked, the tap did
        // nothing, and nothing was logged, exactly matching the reported symptom. Recover by
        // re-deriving the docked position from the edge the ring is peeked against instead of
        // silently giving up, and say so in the log.
        val targetX = prePeekX ?: run {
            val ringSize = params.width
            val margin = (MARGIN_DP * dp).toInt()
            val recovered = if (params.x + ringSize / 2 > screenW / 2) screenW - ringSize - margin else screenLeft + margin
            Log.w(TAG, "restoreFromPeek: prePeekX was null while peeked (state desync) -- recovering to x=$recovered")
            recovered
        }
        isPeeked = false
        prePeekX = null
        refreshTranslationBadge()
        Log.i(TAG, "Restoring ring from peek: x=${params.x} -> $targetX")
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        animateRingX(params, wm, targetX)
        armIdlePeekTimer()
    }

    /** Leaves peek without animating, for a screen change that moved the navigation bar onto the
     *  ring's edge (#352); the caller applies [params]. */
    private fun unpeekInPlace(params: WindowManager.LayoutParams, dockedX: Int) {
        params.x = dockedX
        isPeeked = false
        prePeekX = null
        refreshTranslationBadge()
        armIdlePeekTimer()
    }

    /** Called from [BehaviorActivity] right after the user flips [AutoPeekToggle] off, so a ring
     *  that's already peeked at that moment snaps back to full visibility immediately instead of
     *  silently staying peeked until the next unrelated touch happens to restore it. No-ops if the
     *  ring isn't currently peeked. Internal (not private) for the same cross-class-call reason as
     *  [applyOverlayVisibility]. */
    internal fun restoreFromPeekIfPeeked() {
        if (isPeeked) restoreFromPeek()
    }

    /** Animates the ring window's x from its current value to [targetX], keeping the feedback
     *  bubble anchored to it in step exactly like drag-to-reposition already does.
     *
     *  Under reduced motion (see [isReducedMotionEnabled]) this still moves the ring to
     *  [targetX] -- peek/un-peek is a functional position change (it uncovers content behind
     *  the ring), not a decorative flourish, so it can't just be skipped the way [startPulse]'s
     *  purely-cosmetic pulse is. It jumps straight to the end position in one frame instead of
     *  tweening through [RingPeek.ANIM_DURATION_MS], which is what "reduce motion" means for a
     *  functional transition: keep the state change, remove the animated interpolation. */
    private fun animateRingX(params: WindowManager.LayoutParams, wm: WindowManager, targetX: Int) {
        // Bug fix (Trevor-reported): with SingleTapRestoreToggle on, a tap against a peeked ring
        // both restores it (this animator) AND immediately starts recording in the SAME gesture
        // (see overlay.setOnTouchListener's ACTION_DOWN -> restoreFromPeek(), then ACTION_UP ->
        // onTap() -> startRecording()). startRecording() calls setAppearance()/startPulse(),
        // which is a second, independent ViewPropertyAnimator on the same `button` view -- with
        // no ordering guarantee against this restore ValueAnimator's per-frame
        // wm.updateViewLayout() calls, the two could interleave and visibly stall/skip the
        // restore, leaving the ring looking like it never slid out even though recording (and
        // its own animation) started correctly. Any queued post-restore action (see
        // [runAfterPeekRestore]) is captured BEFORE cancelling the old animator (if one was
        // already mid-flight) so a rapid second peek/restore in the same gesture never drops a
        // pending callback silently.
        peekAnimator?.cancel()
        val startXValue = params.x
        val overlay = overlayView ?: return

        if (isReducedMotionEnabled()) {
            params.x = targetX
            wm.updateViewLayout(overlay, params)
            feedbackLayoutParams?.let {
                positionFeedback(it, params, feedbackView?.height ?: 0)
                wm.updateViewLayout(feedbackView, it)
            }
            return
        }

        val animator = android.animation.ValueAnimator.ofInt(startXValue, targetX).apply {
            duration = RingPeek.ANIM_DURATION_MS
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                wm.updateViewLayout(overlay, params)
                feedbackLayoutParams?.let {
                    positionFeedback(it, params, feedbackView?.height ?: 0)
                    wm.updateViewLayout(feedbackView, it)
                }
            }
            // Fires the deferred appearance/pulse change (see [deferUntilPeekAnimationDone]) once
            // this animator is actually done moving the window -- on both a normal finish and a
            // cancel (e.g. superseded by a second peek/restore before this one completed), so a
            // queued action can never be silently dropped. Cleared here, not just invoked, so a
            // stale Runnable never re-fires from a later, unrelated animator run.
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pendingPostPeekAction?.let { it.run() }
                    pendingPostPeekAction = null
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    pendingPostPeekAction?.let { it.run() }
                    pendingPostPeekAction = null
                }
            })
        }
        peekAnimator = animator
        animator.start()
    }

    /**
     * Runs [action] immediately if no peek/restore animation is currently in flight, or defers
     * it to fire right after [animateRingX]'s animator finishes (end or cancel) otherwise -- the
     * actual fix for the SingleTapRestoreToggle race documented on [animateRingX]'s kdoc: without
     * this, [startRecording]'s [setAppearance]/[startPulse] calls could run on the very same
     * `button` view mid-flight of the peek-restore ValueAnimator, and the two competing
     * animations could visibly stall/skip the restore. Reduced-motion restores (see
     * [isReducedMotionEnabled]) never leave an animator running, so [action] always runs
     * immediately in that case -- nothing to defer.
     */
    private fun deferUntilPeekAnimationDone(action: () -> Unit) {
        if (peekAnimator?.isRunning == true) {
            pendingPostPeekAction = Runnable(action)
        } else {
            action()
        }
    }

    /**
     * Builds the (initially hidden) translation badge. Readable at the default 56dp ring: bold
     * white caps on a solid blue pill with a thin white edge, so it reads on any ring colour,
     * custom icon or app background; the text size scales with the ring size setting. It sits on
     * the ring's lower-right corner, inside the ring window.
     */
    private fun buildTranslationBadge(ringSizeDp: Int): TextView = TextView(this).apply {
        textSize = (10f * ringSizeDp / RING_DP).coerceIn(9f, 14f)
        setTextColor(0xFFFFFFFF.toInt())
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = false
        isFocusable = false
        val h = (4 * dp).toInt(); val v = (2 * dp).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * dp
            setColor(COLOR_TRANSLATE_BADGE)
            setStroke((1 * dp).toInt().coerceAtLeast(1), 0xFFFFFFFF.toInt())
        }
        visibility = View.GONE
    }

    /** Shows the badge with the chosen language's code while a target is set; hides it for
     *  Original, and while the ring is peeked (only a sliver of the ring is on screen then, and a
     *  badge on it would be clipped clutter). Main thread. */
    internal fun refreshTranslationBadge() {
        val badge = translationBadge ?: return
        val target = KeyboardTranslation.target(this)
        if (target == null || isPeeked) {
            badge.visibility = View.GONE
            return
        }
        badge.text = KeyboardTranslation.badgeText(target)
        badge.contentDescription = getString(R.string.ring_translate_badge_desc, target.englishName)
        badge.visibility = View.VISIBLE
    }

    private fun removeOverlay() {
        // Cancel any in-flight peek/restore animation first (M2): its per-frame update listener
        // calls wm.updateViewLayout(overlay, …), which throws IllegalArgumentException ("View not
        // attached") on the main thread if it fires after the views below are removed.
        // pendingPostPeekAction is cleared BEFORE cancel() (not after) so the animator's own
        // onAnimationCancel listener has nothing left to run -- teardown has no business kicking
        // off a deferred setAppearance()/startPulse() against a button view that's about to be
        // removed below.
        pendingPostPeekAction = null
        peekAnimator?.cancel()
        peekAnimator = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // #260: removeOverlay() is now also the unwind path for a failed attach in showOverlay(),
        // so a throw here would defeat that recovery. removeView throws IllegalArgumentException
        // for a view WMS no longer considers attached, which is exactly the state a torn-down
        // service connection leaves behind. Null the field either way: if the view is already
        // gone there is nothing left to remove, and holding the reference only risks a later
        // removeView against a stale token.
        overlayView?.let {
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Overlay window already detached", e)
            }
            overlayView = null
        }
        feedbackView?.let {
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Feedback window already detached", e)
            }
            feedbackView = null
        }
        button = null
        spinner = null
        translationBadge = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    /** [fillColor] transparent means "no border" -- [GradientDrawable.setStroke] is skipped
     *  entirely rather than drawn at zero alpha, since a customized ring size means the stroke
     *  width would otherwise still consume visible space in the drawable's bounds. */
    private fun circleWithBorder(fillColor: Int, borderColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
        if (borderColor != Color.TRANSPARENT) setStroke((2 * dp).toInt(), borderColor)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    /** Single entry point for the button's look, covering both a state-color change (recording,
     *  transcribing, back to idle) and a live appearance-settings change (#43/#53, see
     *  [applyOverlayAppearance]) -- so the two can never race each other into an inconsistent
     *  half-applied state. See [OverlayAppearance]'s own doc for the custom-icon-vs-color-controls
     *  and idle-only-fill-override product decisions this implements.
     *
     *  Also sets [ImageView.contentDescription] from the runtime's current state (read
     *  directly rather than derived from [stateColor], since every call site already maps 1:1
     *  from the same state) -- this is the app's single most-used interactive control (the
     *  floating overlay button that starts/stops/cancels dictation) and previously had no
     *  accessibility label at all, so TalkBack announced it as an unlabeled image. */
    private fun applyButtonAppearance(stateColor: Int) {
        val btn = button ?: return
        val appearance = OverlayAppearancePrefs.load(this)
        btn.contentDescription = when (runtime.currentState()) {
            RecordingStateMachine.State.IDLE -> getString(R.string.overlay_button_idle_description)
            RecordingStateMachine.State.RECORDING -> getString(R.string.overlay_button_recording_description)
            RecordingStateMachine.State.TRANSCRIBING -> getString(
                if (cleaningUi) R.string.overlay_button_cleaning_description
                else R.string.overlay_button_transcribing_description
            )
        }

        if (appearance.hasCustomIcon) {
            val bitmap = OverlayIconStore.load(this)
            if (bitmap != null) {
                btn.background = null
                btn.scaleType = ImageView.ScaleType.CENTER_CROP
                btn.imageTintList = null
                btn.setImageBitmap(bitmap)
                btn.clipToOutline = true
                btn.outlineProvider = OVAL_OUTLINE_PROVIDER
                return
            }
            // Stored file missing/corrupt: fall through to the built-in glyph (#43's documented
            // fallback) instead of leaving the button with no image at all.
        }

        btn.clipToOutline = false
        btn.outlineProvider = null
        btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
        btn.setImageResource(R.drawable.ic_mic)
        btn.imageTintList = ColorStateList.valueOf(appearance.glyphColor ?: COLOR_GLYPH_DEFAULT)
        val fill = if (stateColor == COLOR_IDLE) (appearance.fillColor ?: COLOR_IDLE) else stateColor
        btn.background = circleWithBorder(fill, appearance.borderColor ?: COLOR_BORDER_DEFAULT)
    }

    private fun setAppearance(color: Int) {
        handler.post { applyButtonAppearance(color) }
    }

    /**
     * Re-applies the overlay's appearance settings (#43/#53) to the already-showing overlay in
     * place -- resizing the ring/button/padding and redrawing colors/icon -- without tearing down
     * and re-adding the WindowManager windows, so the ring's dragged position is never disturbed
     * by a Settings change. Called from MainActivity right after a color/size/custom-icon change,
     * mirroring the existing reloadModel()/reloadStreamingModel() pattern. A no-op if the overlay
     * isn't currently showing (e.g. Accessibility isn't enabled).
     */
    fun applyOverlayAppearance() {
        handler.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val overlay = overlayView ?: return@post
            val params = layoutParams ?: return@post
            val btn = button ?: return@post
            val ring = spinner ?: return@post

            val ringSizeDp = OverlayAppearancePrefs.load(this).ringSizeDp
            val ringSize = (ringSizeDp * dp).toInt()
            val buttonSize = (dpScaledToRing(BTN_DP, ringSizeDp) * dp).toInt()
            val pad = (dpScaledToRing(PAD_DP, ringSizeDp) * dp).toInt()

            params.width = ringSize
            params.height = ringSize
            wm.updateViewLayout(overlay, params)

            (ring.layoutParams as FrameLayout.LayoutParams).apply { width = buttonSize; height = buttonSize }
            ring.requestLayout()
            (btn.layoutParams as FrameLayout.LayoutParams).apply { width = buttonSize; height = buttonSize }
            btn.setPadding(pad, pad, pad, pad)
            btn.requestLayout()

            val stateColor = when (runtime.currentState()) {
                RecordingStateMachine.State.IDLE -> COLOR_IDLE
                RecordingStateMachine.State.RECORDING -> COLOR_RECORDING
                RecordingStateMachine.State.TRANSCRIBING -> COLOR_BUSY
            }
            applyButtonAppearance(stateColor)

            feedbackLayoutParams?.let { positionFeedback(it, params, feedbackView?.height ?: 0); wm.updateViewLayout(feedbackView, it) }

            // #111: the exclusion rect reserved in showOverlay() is sized to the ring at the
            // time it was first shown. A live overlay-size settings change resizes params.width/
            // height above but, without this, never refreshes the exclusion rect -- leaving it
            // pinned to the OLD (now stale) size, so touches near the resized ring's new outer
            // edge fall back into the system edge-swipe gesture's catchment again.
            applyGestureExclusion(overlay, ringSize)
        }
    }

    /** Shows/hides the busy ring. [cleaning] (#294) tints it [COLOR_RING_CLEANING] for the cleanup
     *  phase; every other call resets it to the neutral [COLOR_RING], so a stale cleanup tint can
     *  never leak into the next dictation's transcribing phase. */
    private fun setBusy(visible: Boolean, cleaning: Boolean = false) {
        handler.post {
            cleaningUi = visible && cleaning
            spinner?.indeterminateTintList =
                ColorStateList.valueOf(if (cleaningUi) COLOR_RING_CLEANING else COLOR_RING)
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Anchors the feedback bubble beside the floating icon, flipping above/below based on the
     * icon's vertical screen position (bug fix: the bubble used to always sit a fixed
     * [MARGIN_DP] above the icon regardless of the bubble's real height, so on a tall multi-line
     * bubble -- or when the icon itself was already near the top of the screen -- the bubble
     * could overlap the icon and make it hard to tap once you're done talking). Mirrors
     * [positionStyleMenu]'s already-proven above/below-flip approach: when the icon is in the
     * bottom half of the screen there's more room above it, so the bubble opens upward (as
     * before); when the icon is in the top half, the bubble now opens downward instead, using
     * [feedbackHeight] -- the view's real last-measured height, not a guess -- so it never
     * overlaps the icon on either side. Both directions are still clamped fully on-screen.
     *
     * #117: the ring-relative gap above uses [RING_AVOID_MARGIN_DP] rather than the plain
     * [MARGIN_DP] used for on-screen clamping -- the bubble's touchable footprint is small and
     * scoped to itself (see [setFeedbackTouchable]), but during the 10s raw-text-retry window the
     * very next thing the user is likely to do is tap the ring again to start another dictation,
     * so this is exactly the one spot where a too-thin gap risks stealing that tap. The extra
     * margin is applied only to the ring-adjacent edge; the far edge and on-screen clamping still
     * use [MARGIN_DP] as before.
     */
    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams,
        feedbackHeight: Int,
        targetBounds: Rect? = null
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val ringMargin = (RING_AVOID_MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        val ringSize = bubbleParams.width
        feedbackParams.x = maxOf(screenLeft + margin, bubbleParams.x - offset)
        var openUpward = bubbleParams.y + ringSize / 2 > screenH / 2
        feedbackParams.y = if (openUpward) {
            // Icon in bottom half: open upward, same placement as before this fix.
            (bubbleParams.y - feedbackHeight - ringMargin).coerceAtLeast(margin)
        } else {
            // Icon in top half: open downward instead, so the bubble can't cover the icon.
            (bubbleParams.y + ringSize + ringMargin).coerceAtMost(screenH - feedbackHeight - margin)
        }

        // #120: the natural above/below placement above only ever considers the ring's own
        // position, so it can still land the bubble squarely on top of the field the text was
        // just injected into (e.g. a short field near screen-center, or a tall multi-line one).
        // When the target node's on-screen bounds are known, flip to the opposite side of the
        // ring instead of the naturally-preferred one if that natural spot would overlap it.
        // Simple heuristic, not a general layout solver: try the natural side, and only if that
        // overlaps the target do we take the other side (still clamped on-screen as before).
        if (targetBounds != null) {
            val bubbleTop = feedbackParams.y
            val bubbleBottom = feedbackParams.y + feedbackHeight
            val overlapsTarget = bubbleBottom > targetBounds.top && bubbleTop < targetBounds.bottom
            if (overlapsTarget) {
                openUpward = !openUpward
                feedbackParams.y = if (openUpward) {
                    (bubbleParams.y - feedbackHeight - ringMargin).coerceAtLeast(margin)
                } else {
                    (bubbleParams.y + ringSize + ringMargin).coerceAtMost(screenH - feedbackHeight - margin)
                }
            }
        }
    }

    private fun showFeedback(text: String, durationMs: Long = 2000, touchable: Boolean = false, isFallback: Boolean = false, targetBounds: Rect? = null) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            view.background = pill(if (isFallback) COLOR_FEEDBACK_FALLBACK_BG else COLOR_FEEDBACK_BG)
            setFeedbackTouchable(touchable)
            // view.height reflects the PREVIOUS text's measured size (0 before the bubble has
            // ever been shown) -- close enough for the initial placement below, then corrected
            // once this text's real layout is available, same two-pass approach as
            // showStyleMenu's menu.post{} (WRAP_CONTENT's true size still isn't known until after
            // the next layout pass actually runs).
            positionFeedback(feedbackParams, bubbleParams, view.height, targetBounds)
            wm.updateViewLayout(view, feedbackParams)
            view.post {
                if (feedbackView !== view) return@post // hidden/replaced already
                positionFeedback(feedbackParams, bubbleParams, view.height, targetBounds)
                wm.updateViewLayout(view, feedbackParams)
            }

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
            feedbackShowing = true
            // A bubble that appears while the keyboard-only gate has the ring hidden (e.g. the
            // clipboard fallback, where no field and so no keyboard is up) brings the ring back
            // with it, so a tappable bubble never floats next to an invisible ring.
            if (RingVisibilityMode.read(this) != RingVisibilityMode.ALWAYS) applyOverlayVisibility()
        }
    }

    /**
     * The feedback bubble is normally FLAG_NOT_TOUCHABLE so it never steals taps from the app
     * underneath; it's made touchable only while "retry with raw text" (#27) or "copy again" (#5)
     * is on offer.
     */
    private fun setFeedbackTouchable(touchable: Boolean) {
        val view = feedbackView ?: return
        val params = feedbackLayoutParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
    }

    // --- Long-press style/cleanup menu (#53, #103) ---

    // The persona list, current style and cleanup state come from [CleanupStyleMenu] (#312) -- shared
    // with the keyboard's style picker.

    /**
     * Opens the long-press menu (see overlayLongPressActionFor's state map) letting the user
     * toggle cleanup on/off and pick a cleanup persona (#40) without leaving whatever app they're
     * dictating into. Implemented as two plain WindowManager-added views -- a full-screen
     * touchable scrim (dismisses on an outside tap) plus the menu content on top -- rather than a
     * PopupMenu/PopupWindow: those are anchored to a real Activity/View's window token, which an
     * AccessibilityService's floating overlay doesn't have, so they can't reliably anchor to (or
     * even show above) this service's own TYPE_ACCESSIBILITY_OVERLAY windows.
     */
    /**
     * [content] is a builder rather than a fixed call to [buildStyleMenuContent] so the "Hide
     * icon" confirmation (#135) can reuse this window, scrim, anchoring and dismiss handling
     * verbatim instead of duplicating it -- the confirmation is the same menu showing a different
     * page, which is also how it should read to the user.
     */
    private fun showStyleMenu(content: () -> LinearLayout = ::buildStyleMenuContent) {
        dismissStyleMenu()
        val ringParams = layoutParams ?: return
        val ringSize = ringParams.width
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // The scrim is focusable so the Back gesture/key reaches the menu and closes it, like any
        // other popup. FLAG_ALT_FOCUSABLE_IM keeps it from becoming the IME target, so the app's
        // keyboard isn't torn down just because the menu took focus.
        val scrim = FrameLayout(this).apply {
            setOnClickListener { dismissStyleMenu() }
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) dismissStyleMenu()
                true
            }
        }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val margin = (MARGIN_DP * dp).toInt()
        val panel = content()
        // The panel scrolls inside a height-capped viewport rather than growing past the screen
        // (Z Fold8 crash/trap, see styleMenuMaxHeight). The rounded chrome moves to the viewport
        // so the border frames what's visible instead of scrolling away with the rows.
        val menu = BoundedScrollView(
            this,
            maxWidthPx = styleMenuMaxWidth(screenW, margin, (STYLE_MENU_MAX_WIDTH_DP * dp).toInt()),
            maxHeightPx = styleMenuMaxHeight(screenH, margin, (STYLE_MENU_OUTSIDE_RESERVE_DP * dp).toInt()),
        ).apply {
            background = panel.background
            setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, panel.paddingBottom)
            panel.background = null
            panel.setPadding(0, 0, 0, 0)
            clipToOutline = true
            isVerticalFadingEdgeEnabled = true
            // Keep the scrollbar drawn whenever the rows overflow, so it's obvious there's more.
            isScrollbarFadingEnabled = false
            setFadingEdgeLength((24 * dp).toInt())
            addView(panel)
            // Invisible until positioned (see menu.post below) -- WRAP_CONTENT's real size isn't
            // known until after this first layout pass, so the window is added at a provisional
            // ring-anchored x/y (usually the right screen edge) purely to let it measure itself.
            // Without this, that provisional position was visibly rendered for one frame: a brief
            // flash of the menu box in the wrong spot before it jumped to its real position above
            // the ring.
            visibility = View.INVISIBLE
        }
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Provisional anchor until the real size is known post-layout, below.
            x = ringParams.x
            y = ringParams.y
        }

        // #260: same attach-ordering fix as showOverlay(). A throw on the second addView
        // previously left the scrim attached with styleMenuScrim still null, which
        // dismissStyleMenu()'s null-guarded teardown could never remove -- a full-screen scrim
        // stuck over the user's screen until the process died. Lower exposure than the overlay
        // (long-press triggered, not lifecycle driven) but the same failure.
        try {
            wm.addView(scrim, scrimParams)
            styleMenuScrim = scrim
            scrim.requestFocus()
            // targetSdk 36 means predictive back: the gesture is delivered through the window's
            // OnBackInvokedDispatcher rather than as KEYCODE_BACK, which the key listener above
            // still covers for API 30-32 and hardware Back keys.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                scrim.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT
                ) { dismissStyleMenu() }
            }
            wm.addView(menu, menuParams)
            styleMenuView = menu
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Could not attach style menu", e)
            dismissStyleMenu()
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Could not attach style menu", e)
            dismissStyleMenu()
            return
        }

        // WRAP_CONTENT's real size isn't known until after the first layout pass, so the anchor
        // math runs once more here rather than trying to pre-compute the menu's height.
        menu.post {
            if (styleMenuView !== menu) return@post // dismissed already
            positionStyleMenu(menuParams, ringParams, ringSize, menu.width, menu.height)
            wm.updateViewLayout(menu, menuParams)
            menu.visibility = View.VISIBLE
        }
    }

    private fun dismissStyleMenu() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // #260: also the unwind path for a failed attach above, so it must not throw.
        styleMenuView?.let {
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Style menu already detached", e)
            }
        }
        styleMenuScrim?.let {
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Style menu scrim already detached", e)
            }
        }
        styleMenuView = null
        styleMenuScrim = null
    }

    /** Anchors the menu just above the ring, on whichever side has more horizontal room, then
     *  clamps fully on-screen -- the menu is taller than the ring/feedback bubble, so it opens
     *  upward rather than risking running off the bottom of the screen. */
    private fun positionStyleMenu(
        menuParams: WindowManager.LayoutParams,
        ringParams: WindowManager.LayoutParams,
        ringSize: Int,
        menuWidth: Int,
        menuHeight: Int
    ) {
        val (x, y) = styleMenuPosition(
            ringParams.x, ringParams.y, ringSize, menuWidth, menuHeight, screenW, screenH, (MARGIN_DP * dp).toInt()
        )
        menuParams.x = x
        menuParams.y = y
    }

    /** The style menu's shared chrome (rounded translucent panel), extracted so the "Hide icon"
     *  confirmation page (#135) renders as the same object the user was already looking at rather
     *  than a differently-styled second surface. */
    private fun styleMenuContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumWidth = (220 * dp).toInt()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(COLOR_FEEDBACK_BG)
            // A near-black translucent fill with no edge blends into a dark app background or
            // dark wallpaper -- a subtle light-alpha border keeps the menu visually distinct
            // regardless of what's behind it, without needing a hard opaque outline.
            setStroke((1 * dp).toInt(), COLOR_MENU_BORDER)
        }
        setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
    }

    private fun buildStyleMenuContent(): LinearLayout {
        val container = styleMenuContainer()

        // #312: the model (cleanup state, current style, the list) comes from the helper the
        // keyboard's picker also uses, so the two surfaces can never disagree.
        // Read once, while building: once the menu's focusable scrim attaches it becomes the active
        // window, so a read at tap time would name Ramblr itself instead of the app underneath.
        val hostPackage = currentForegroundPackageName()
        val model = CleanupStyleMenu.load(this, hostPackage)
        val cleanupEnabled = model.cleanupEnabled
        val cleanupIcon = getDrawable(R.drawable.ic_cleanup)?.mutate()?.apply {
            setTint(if (cleanupEnabled) COLOR_CLEANUP_ON else COLOR_CLEANUP_OFF)
        }
        container.addView(
            styleMenuRow(
                cleanupIcon,
                title = if (cleanupEnabled) "Cleanup: On" else "Cleanup: Off",
                subtitle = "Tap to turn cleanup " + if (cleanupEnabled) "off" else "on"
            ) { onStyleMenuCleanupToggleTapped() }
        )

        container.addView(styleMenuDivider())

        for (persona in model.personas) {
            val title = if (persona == model.current) "✓ ${persona.title}" else persona.title
            container.addView(styleMenuRow(icon = null, title = title, subtitle = persona.subtitle) {
                onStyleMenuPersonaTapped(persona, hostPackage)
            })
        }

        container.addView(styleMenuDivider())

        addTranslateSection(container)

        container.addView(styleMenuDivider())

        val streamingEnabled = isLivePreviewOn(
            settingEnabled = prefs().getBoolean("streaming_preview_enabled", false),
            streamingModelInstalled = ModelDownloader.isInstalled(this, selectedStreamingModel()),
            cloudLiveEnabled = CloudLiveToggle.isEnabled(this)
        )
        container.addView(
            styleMenuRow(
                icon = null,
                title = if (streamingEnabled) "Live preview: On" else "Live preview: Off",
                subtitle = "Tap to turn live preview " + if (streamingEnabled) "off" else "on"
            ) { onStyleMenuStreamingToggleTapped() }
        )

        container.addView(styleMenuDivider())

        container.addView(
            styleMenuRow(icon = null, title = "Open Ramblr Settings", subtitle = "Full settings, models, history") {
                onStyleMenuOpenSettingsTapped()
            }
        )

        if (HideIconToggle.isEnabled(this)) {
            container.addView(styleMenuDivider())
            container.addView(
                styleMenuRow(icon = null, title = "Hide icon", subtitle = "Fully hides it — you'll be asked to confirm") {
                    onStyleMenuHideIconTapped()
                }
            )
        }

        return container
    }

    /**
     * "Translate to" in the long-press menu: Original plus the languages chosen in Settings, the
     * same shared choice the keyboard's style picker shows (one preference, so they never
     * disagree). Picking applies to the NEXT dictation. While a dictation is in flight (recording,
     * transcribing or cleaning -- the runtime is not IDLE) the chips are shown dimmed and do
     * nothing, so a translation can't change under a dictation that already started.
     */
    private fun addTranslateSection(container: LinearLayout) {
        val model = CleanupStyleMenu.translateModel(this)
        val busy = runtime.currentState() != RecordingStateMachine.State.IDLE
        if (model.languages.isEmpty()) {
            container.addView(styleMenuRow(
                icon = null,
                title = getString(R.string.ring_translate_menu_title),
                subtitle = getString(R.string.ring_translate_menu_none),
            ) { onStyleMenuOpenCleanupSettingsTapped() })
            return
        }
        container.addView(styleMenuRow(
            icon = null,
            title = getString(R.string.ring_translate_menu_title),
            subtitle = getString(if (busy) R.string.ring_translate_menu_busy else R.string.ring_translate_menu_subtitle),
        ) { /* section header: inert */ })

        // (code or null for Original, label, content description)
        val choices = buildList {
            add(Triple<String?, String, String>(
                null, getString(R.string.ime_translate_original), getString(R.string.ime_translate_original_desc),
            ))
            for (lang in model.languages) add(Triple<String?, String, String>(
                lang.code, lang.nativeName,
                getString(
                    if (lang.code == model.targetCode) R.string.ime_translate_selected_desc else R.string.ime_translate_choice_desc,
                    lang.englishName,
                ),
            ))
        }
        for (rowChoices in choices.chunked(TRANSLATE_CHIPS_PER_ROW)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
            }
            for ((code, label, desc) in rowChoices) {
                val selected = code == model.targetCode
                row.addView(TextView(this).apply {
                    text = label
                    contentDescription = desc
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
                    setPadding((8 * dp).toInt(), (7 * dp).toInt(), (8 * dp).toInt(), (7 * dp).toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8 * dp
                        setColor(if (selected) COLOR_TRANSLATE_BADGE else 0x26FFFFFF)
                    }
                    isEnabled = !busy
                    alpha = if (busy) 0.4f else 1f
                    isClickable = !busy
                    if (!busy) setOnClickListener { onStyleMenuTranslateTapped(code) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * dp).toInt(); marginEnd = (3 * dp).toInt()
                })
            }
            // Pad a short last row so its chips keep the same width as the rows above it.
            repeat(TRANSLATE_CHIPS_PER_ROW - rowChoices.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    marginStart = (3 * dp).toInt(); marginEnd = (3 * dp).toInt()
                })
            }
            container.addView(row)
        }
    }

    /** A "Translate to" chip: store the shared target (applies to the next dictation) and say so. */
    private fun onStyleMenuTranslateTapped(code: String?) {
        // The menu can outlive the moment it was built (a dictation may have started since).
        if (runtime.currentState() != RecordingStateMachine.State.IDLE) return
        KeyboardTranslation.setTarget(this, code)
        dismissStyleMenu()
        refreshTranslationBadge()
        val language = KeyboardTranslation.byCode(code)
        showFeedback(
            if (language == null) getString(R.string.ime_translate_changed_off)
            else getString(R.string.ime_translate_changed, language.englishName),
            durationMs = 1500,
        )
    }

    private fun onStyleMenuOpenCleanupSettingsTapped() {
        dismissStyleMenu()
        startActivity(Intent(this, CleanupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Second page of the style menu shown after "Hide icon" is tapped (#135): confirm, and name
     * the way back *before* the ring disappears rather than after.
     *
     * This is a confirmation step, not a nag. Hiding the icon is the single most destructive thing
     * the long-press menu can do -- it removes the only always-on affordance the app has -- and it
     * was previously one tap with no undo shown anywhere the user was already looking. The recovery
     * text has to be readable at the moment of the decision, because every channel for delivering
     * it afterwards is losable: the notification can be swiped, the toast disappears in seconds,
     * and Settings > Floating icon's restore row is invisible until you're already hidden.
     *
     * Built from the same [styleMenuRow]/[styleMenuDivider] primitives as the main menu so it
     * inherits the overlay's theming, touch handling and dismiss-on-outside-tap for free. An
     * AlertDialog is not an option here: an AccessibilityService has no activity context, and a
     * TYPE_APPLICATION_OVERLAY dialog would be a second window to manage for no benefit.
     */
    private fun buildHideIconConfirmMenu(): LinearLayout {
        val container = styleMenuContainer()

        container.addView(
            styleMenuRow(
                icon = null,
                title = "Hide the floating icon?",
                subtitle = "The ring disappears until you bring it back.",
            ) { /* header row: inert by design, tapping the question does nothing */ }
        )

        container.addView(styleMenuDivider())

        container.addView(
            styleMenuRow(
                icon = null,
                title = "Hide it",
                subtitle = "Get it back from the notification, the Quick Settings tile, or Settings > Floating icon",
            ) { onHideIconConfirmed() }
        )

        container.addView(styleMenuDivider())

        container.addView(
            styleMenuRow(icon = null, title = "Cancel", subtitle = "Leave the icon where it is") {
                dismissStyleMenu()
            }
        )

        return container
    }

    /** Thin horizontal rule separating style menu row groups (#53/#57). */
    private fun styleMenuDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()).apply {
            topMargin = (4 * dp).toInt(); bottomMargin = (4 * dp).toInt()
        }
        setBackgroundColor(0x33FFFFFF)
    }

    private fun styleMenuRow(icon: Drawable?, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { onClick() }
        }
        if (icon != null) {
            row.addView(ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                    marginEnd = (10 * dp).toInt()
                }
            })
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@WhisperAccessibilityService).apply {
                text = title
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
            })
            addView(TextView(this@WhisperAccessibilityService).apply {
                text = subtitle
                textSize = 12f
                setTextColor(0xFFB0B0B0.toInt())
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
        })
        return row
    }

    /**
     * Cleanup on/off from the style menu (#53) -- replaces the old always-visible badge's tap
     * (#34). Turning it OFF is always instant. Turning it ON respects the existing
     * local-transcription + cleanup consent gate (#23) instead of silently bypassing it -- if
     * consent hasn't been given yet, this sends the user to Settings once rather than starting to
     * send local transcripts off-device without the warning they'd get there.
     */
    private fun onStyleMenuCleanupToggleTapped() {
        val result = CleanupStyleMenu.toggleCleanup(this)
        dismissStyleMenu()
        when (result) {
            CleanupStyleMenu.ToggleResult.NEEDS_SETTINGS -> toast("Enable cleanup once in Ramblr settings first")
            CleanupStyleMenu.ToggleResult.TURNED_ON -> showFeedback("Cleanup on", durationMs = 1200)
            CleanupStyleMenu.ToggleResult.TURNED_OFF -> showFeedback("Cleanup off", durationMs = 1200)
        }
    }

    /**
     * Persona/style switch from the style menu (#40, #53, #57) -- the overlay-based counterpart to
     * MainActivity's Settings persona picker (see MainActivity.selectPrompt), writing the exact
     * same two prefs so both surfaces always agree on what's selected. Picking a persona is also
     * a request to hear it, so this always ensures cleanup is enabled too (#57, Trevor's
     * always-on-behavior request) rather than requiring a separate tap on the cleanup row first --
     * unless the consent gate blocks it, in which case the persona is still selected (it takes
     * effect the moment cleanup is turned on) but the feedback bubble says so instead of claiming
     * cleanup is now running.
     */
    private fun onStyleMenuPersonaTapped(persona: CleanupPersona, hostPackage: String?) {
        val selection = CleanupStyleMenu.select(this, hostPackage, persona)
        dismissStyleMenu()
        showFeedback(
            if (selection.cleanupEnabled) "Style: ${persona.title}" else "Style: ${persona.title} (enable cleanup in Settings)",
            durationMs = 1200
        )
    }

    /** Streaming live-preview on/off from the style menu (#57) -- reads/writes the exact same
     *  "streaming_preview_enabled" pref MainActivity's Settings toggle uses (see
     *  LivePreviewActivity.onStreamingPreviewToggle), including the same "model installed or
     *  Cloud Live on" gate ([canEnableLivePreview]), so both surfaces always agree on state and
     *  neither can turn on a live preview with nothing to back it. */
    private fun onStyleMenuStreamingToggleTapped() {
        val modelInstalled = ModelDownloader.isInstalled(this, selectedStreamingModel())
        val cloudLive = CloudLiveToggle.isEnabled(this)
        val enabling = !isLivePreviewOn(prefs().getBoolean("streaming_preview_enabled", false), modelInstalled, cloudLive)
        if (enabling && !canEnableLivePreview(modelInstalled, cloudLive)) {
            dismissStyleMenu()
            toast("Download the streaming model or turn on Cloud Live in Ramblr settings first")
            return
        }
        prefs().edit().putBoolean("streaming_preview_enabled", enabling).apply()
        reloadStreamingModel()
        dismissStyleMenu()
        showFeedback(if (enabling) "Live preview on" else "Live preview off", durationMs = 1200)
    }

    private fun selectedStreamingModel(): Model = ModelDownloader.resolveActiveModel(
        STREAMING_MODEL_CATALOG,
        prefs().getString("streaming_model_name", STREAMING_MODEL.archive) ?: STREAMING_MODEL.archive
    )

    /** Opens MainActivity (#57) directly from the overlay -- FLAG_ACTIVITY_NEW_TASK is required
     *  since this call comes from a Service context, not an Activity. The menu is dismissed first
     *  so it doesn't linger as a stale overlay window on top of MainActivity. */
    private fun onStyleMenuOpenSettingsTapped() {
        dismissStyleMenu()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Hide icon" long-press menu row (Feature B): opens the confirmation page (#135) rather
     *  than hiding immediately. The hide itself is one tap away, but never the *first* tap --
     *  see [buildHideIconConfirmMenu] for why the confirmation is worth the extra step. */
    private fun onStyleMenuHideIconTapped() {
        showStyleMenu(::buildHideIconConfirmMenu)
    }

    /** The actual hide, once confirmed (#135): fully hides the ring/feedback bubble (same
     *  end-state as MainActivity being foregrounded, #35) and posts the "tap to show it again"
     *  notification, since once the ring is gone there's otherwise no on-screen affordance left
     *  to bring it back. */
    private fun onHideIconConfirmed() {
        dismissStyleMenu()
        IconHiddenState.setHidden(this, true)
        applyOverlayVisibility()
        IconVisibilityNotifications.postHidden(this)
        // Name the recovery paths out loud (#135). This can't use showFeedback(): the call above
        // has already forced the feedback bubble to alpha=0f and queued hideFeedback, so anything
        // written there would be invisible -- the hide is exactly the state that suppresses it.
        // A Toast is a separate system-owned window and is unaffected. Belt-and-braces alongside
        // the confirmation page that just named the same paths: the toast is what remains on
        // screen for a moment *after* the ring vanishes, which is when the loss is felt.
        toast("Icon hidden. Tap the notification, or Settings > Floating icon, to show it again")
    }

    private fun startPulse() {
        // Respect the OS "Remove animations" / Developer Options "Animator duration scale = Off"
        // preference (#accessibility pass): unlike the framework's own transition system, a
        // manually-built ViewPropertyAnimator like this one does NOT automatically honor that
        // setting -- it has to be checked explicitly. The pulse is purely decorative (RECORDING
        // state is already conveyed by the button's fill color via applyButtonAppearance), so
        // under reduced motion this just leaves the button at full, static alpha instead of
        // looping an animation the user asked the OS to suppress.
        if (isReducedMotionEnabled()) return
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (runtime.isRecording()) startPulse()
                }.start()
            }.start()
        }
    }

    /** Reads the OS-level reduced-motion preference (Settings > Accessibility > Remove
     *  animations, which also drives Developer Options > Animator duration scale). Android
     *  sets this to exactly `0f` for both the accessibility toggle and the developer setting,
     *  so reading it directly here works uniformly across both entry points and back to this
     *  app's actual minSdk (rather than only the newer AccessibilityManager reduced-motion API). */
    private fun isReducedMotionEnabled(): Boolean = try {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        ) == 0f
    } catch (_: android.provider.Settings.SettingNotFoundException) {
        false
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        // #256: only blocks a NEW recording start (state == IDLE) -- stop/cancel of an
        // already-running dictation must stay reachable regardless of exclusion, so this never
        // touches RECORDING/TRANSCRIBING. On-demand read of currentForegroundPackageName(), same
        // primitive already used elsewhere in this class -- no new subscription.
        if (ExclusionGating.shouldBlockNewRecording(
                runtime.currentState(),
                PerAppExclusionStore.isExcluded(this, currentForegroundPackageName()),
            )
        ) {
            toast("Ramblr is excluded in this app — see Settings > Privacy")
            return
        }
        // Every start path (ring tap, QS tile, accessibility button/shortcut) ends here. Like the
        // keyboard, never record for a password/PIN field; stop/cancel stay reachable.
        if (PasswordFieldGuard.shouldRefuseRecordingStart(runtime.currentState(), focusedInputIsPassword())) {
            toast(PASSWORD_FIELD_REFUSAL_MESSAGE)
            return
        }
        runtime.onTap()
    }

    /** Long-press while TRANSCRIBING (see overlay.setOnTouchListener): abort the in-flight call and return to idle. */
    private fun cancelTranscription() {
        runtime.cancelTranscription()
    }


    // --- Preview-before-inject (#40) ---

    /**
     * Holds [candidateText] instead of injecting it immediately, showing it via the same feedback
     * bubble used for the raw-retry/copy-again affordances (#5/#27) so this doesn't need a new UI
     * surface. A tap commits (see [onFeedbackTapped]); [PREVIEW_TIMEOUT_MS] with no tap falls back
     * to [rawText] (see [CleanupPreviewState]). Any earlier still-pending preview is resolved as a
     * timeout first, so a fast second dictation never silently drops the first one.
     */
    private fun beginPreview(rawText: String, candidateText: String, paidFallbackGroup: CleanupStepGroup? = null) {
        pendingPreview?.let { resolvePreview { p -> p.timeout() } }

        // #73: record history the moment a cleanup candidate exists, not only once injection
        // happens up to PREVIEW_TIMEOUT_MS later -- closes the process-death/service-teardown
        // window where a pending preview was lost with no trace at all. resolvePreview below
        // updates this exact row in place instead of adding a duplicate once the preview resolves.
        val historyTimestamp = recordHistory(rawText, cleanedText = candidateText, paidFallbackGroup = paidFallbackGroup)
        pendingPreview = CleanupPreviewState(rawText, candidateText, paidFallbackGroup, historyTimestamp)
        // #284: the runtime goes idle right after deliverText() returns and onIdleUi forgets the
        // shared dictationTarget, but this preview resolves up to PREVIEW_TIMEOUT_MS later. Move the
        // target (and ownership of its node) into the preview so the user-left check still has
        // something to compare against when the tap/timeout finally arrives.
        pendingPreviewTarget = dictationTarget
        dictationTarget = null
        handler.postDelayed(previewTimeoutRunnable, PREVIEW_TIMEOUT_MS)

        val truncated = candidateText.take(PREVIEW_PREVIEW_CHARS)
        val suffix = if (candidateText.length > truncated.length) "…" else ""
        showFeedback("Preview: $truncated$suffix · tap to insert", PREVIEW_TIMEOUT_MS, touchable = true)
    }

    /** Runs [action] against the pending preview (if any) and injects whatever it resolved to. */
    private fun resolvePreview(action: (CleanupPreviewState) -> PreviewResolution) {
        val preview = pendingPreview ?: return
        pendingPreview = null
        val previewTarget = pendingPreviewTarget
        pendingPreviewTarget = null
        handler.removeCallbacks(previewTimeoutRunnable)
        val resolution = action(preview)
        if (resultTargetsPasswordField(previewTarget)) {
            previewTarget?.node?.recycle()
            discardPasswordFieldResult()
            return
        }
        try {
            // #284: the user may have left the field during the preview window. Same rule as the
            // immediate branch of deliverText: never type into whatever is focused now. The
            // preview's history row (#73) is updated in place, not duplicated.
            if (!deliveryTargetIsCurrent(previewTarget)) {
                if (resolution.committed) {
                    deliverToBackground(
                        resolution.textToInject, preview.rawText, preview.paidFallbackGroup, null,
                        existingHistoryTimestamp = preview.historyTimestamp,
                    )
                } else {
                    deliverToBackground(
                        resolution.textToInject, null, preview.paidFallbackGroup, null,
                        existingHistoryTimestamp = preview.historyTimestamp,
                    )
                }
                return
            }
        } finally {
            previewTarget?.node?.recycle()
        }
        if (resolution.committed) {
            injectText(
                resolution.textToInject,
                rawText = preview.rawText,
                paidFallbackGroup = preview.paidFallbackGroup,
                existingHistoryTimestamp = preview.historyTimestamp,
                deliveryTargetPackage = previewTarget?.packageName,
            )
        } else {
            // Discard/timeout falls back to raw text (see CleanupPreviewState) -- update the
            // already-recorded row to reflect that outcome (no cleanedText survives the discard)
            // rather than leaving the stale candidate text sitting in history (#73).
            injectText(
                resolution.textToInject,
                // #175 (same false-clipboard claim, different path): this is the preview
                // discard/timeout, not a cleanup failure, so it keeps its own wording -- but it
                // was making the identical untrue promise. A DIRECT injection wipes the clipboard
                // immediately, so "raw copied to clipboard" sent the user to an empty clipboard.
                feedback = "Cleanup skipped — inserted raw text",
                existingHistoryTimestamp = preview.historyTimestamp,
                deliveryTargetPackage = previewTarget?.packageName,
            )
        }
    }

    /** Safe to call from any thread: [DictationRuntime.resetToIdle] mutates main-thread-only
     *  state -- so it hops (#72). Kept in the runtime now; the service has no reset of its own. */

    // --- Streaming preview (#29) ---

    /**
     * Injects (or replaces) the live partial preview in the focused field, throttled by
     * [shouldInjectPartial]. The very first partial of a recording scans for an injection
     * candidate the same way the final batch injection does ([findInjectionCandidates]), but only
     * accepts one that supports direct (ACTION_SET_TEXT) injection — a paste-only target can't have
     * a specific span replaced in place, so preview is silently skipped there and only the final
     * batch injection (unaffected by any of this) will land in that field. Subsequent partials
     * reuse the same node rather than rescanning, both for cost and to avoid re-resolving focus
     * mid-dictation. The insertion point uses [resolveInsertionStart] rather than trusting a raw
     * `(0, 0)` selection report (#42), and the text actually written to the field is run through
     * [smartCapitalize] for display only — the raw model hypothesis is still what's compared/stored
     * for throttling purposes.
     */
    private fun maybeInjectPartial(text: String) = maybeInjectPartial(text, requireRecording = true)

    /** [requireRecording] is false only for the debug-build `DEBUG_PARTIAL` test seam, which drives
     *  this path without a live recording; every production caller goes through the overload above. */
    internal fun maybeInjectPartial(text: String, requireRecording: Boolean, alreadyCased: Boolean = false) {
        if (requireRecording && !runtime.isRecording()) return // stale post after stop/cancel raced this
        if (text.isBlank()) return
        val now = System.currentTimeMillis()

        // Bug fix (live-preview + preview-before-insert interaction): when Preview-before-insert
        // is on, the real field must never be touched until the user explicitly commits, so route
        // the live partial into the floating feedback bubble instead of the field. When it's off,
        // fall through to the existing direct-field-write behavior completely unchanged.
        // Live preview OFF is the same promise: the field is untouched until the final text.
        // Cloud Live interims (#245) arrive here whether or not the user turned the setting on.
        if (shouldRouteStreamingPartialToBubble(
                PreviewBeforeInjectToggle.isEnabled(this),
                livePreviewEnabled = prefs().getBoolean("streaming_preview_enabled", false),
            )
        ) {
            updateLivePreviewBubble(text, now, alreadyCased)
            return
        }

        val session = streamingSession
        if (session == null) {
            // Focus can reach a password field mid-recording; the candidate scan below would then
            // fall through to some other field in the window, so refuse before scanning.
            if (!PasswordFieldGuard.allowsPartialWrite(focusedInputIsPassword())) return
            val candidate = findDirectInjectionCandidate() ?: return
            val current = resolveRealText(
                candidate.text?.toString(),
                candidate.isShowingHintText,
                candidate.textSelectionStart,
                candidate.textSelectionEnd,
                candidate.isEditable,
                candidate.isFocused,
            )
            val displayText = if (alreadyCased) text else smartCapitalize(text)
            // #300: the first partial replaces the same range a one-shot injection would, so a
            // ranged selection is overwritten instead of the partial landing in front of it. The
            // selection comes off the destination, which applies the WebView select-all correction
            // (Sable v2 reports select-all as 0/1). Text, tracked span and the overwritten text come
            // back together from composeFirstStreamingPartial so they can't drift apart.
            // #144: the separator is folded into the partial *before* its length is tracked, so it
            // sits inside the session's span and gets replaced along with it on every later partial
            // and by the final text -- never double-added, never left behind.
            val destination = AccessibilityTextDestination(candidate)
            val write = composeFirstStreamingPartial(
                current,
                destination.selectionStart(),
                destination.selectionEnd(),
                displayText,
            )
            if (!setNodeText(candidate, write.updatedText)) {
                candidate.recycle()
                return
            }
            streamingSession = StreamingPreviewSession(
                candidate, write.span.insertionStart, write.span.previousLength, write.span.replacedText, text, now,
                trace = write.span.trace,
            )
            scheduleCaretNudgeAfterLanding(candidate, write.updatedText)
            return
        }

        if (!shouldInjectPartial(text, session.lastInjectedText, session.lastInjectedAtMs, now, STREAMING_PARTIAL_MIN_INTERVAL_MS)) return
        if (!refreshNode(session.node)) { endStreamingSession(); return }
        if (session.node.isPasswordField()) { endStreamingSession(); return }

        val current = resolveRealText(
            session.node.text?.toString(),
            session.node.isShowingHintText,
            session.node.textSelectionStart,
            session.node.textSelectionEnd,
            session.node.isEditable,
            session.node.isFocused,
        )
        val displayText = if (alreadyCased) text else smartCapitalize(text)
        // #144: recomputed rather than remembered, and deliberately safe to recompute -- the
        // character it reads sits before insertionStart, upstream of the span being rewritten, so
        // it's identical on every partial of the session.
        val write = composeTracedStreamingPartial(current, session.insertionStart, session.lastPartialLength, session.trace, displayText)
        if (!setNodeText(session.node, write.updatedText)) { endStreamingSession(); return }
        session.lastPartialLength = write.trackedLength
        session.trace = write.trace
        scheduleCaretNudgeAfterLanding(session.node, write.updatedText)
        session.lastInjectedText = text
        session.lastInjectedAtMs = now
    }

    /**
     * Mirrors the live streaming partial into the feedback bubble instead of the real field
     * (bug fix, live-preview + preview-before-insert interaction) -- reuses [showFeedback] with a
     * long-ish duration (comfortably longer than the throttle interval) so the bubble doesn't
     * flicker to hidden between two rapid partials. This is purely a "watch it type" display: it
     * never touches [pendingPreview] or the [PREVIEW_TIMEOUT_MS] commit/discard clock, which only
     * start once recording stops and cleanup actually produces a candidate (see [beginPreview]).
     * Not touchable, since there's nothing to commit yet at this stage.
     */
    private fun updateLivePreviewBubble(text: String, nowMs: Long, alreadyCased: Boolean = false) {
        if (!shouldInjectPartial(text, lastBubblePartialText, lastBubblePartialAtMs, nowMs, STREAMING_PARTIAL_MIN_INTERVAL_MS)) return
        if (!PasswordFieldGuard.allowsPartialWrite(focusedInputIsPassword())) return
        lastBubblePartialText = text
        lastBubblePartialAtMs = nowMs
        val displayText = if (alreadyCased) text else smartCapitalize(text)
        val truncated = displayText.take(PREVIEW_PREVIEW_CHARS)
        val suffix = if (displayText.length > truncated.length) "…" else ""
        showFeedback("$truncated$suffix", STREAMING_PARTIAL_MIN_INTERVAL_MS * 3, touchable = false)
    }

    /** First candidate from [findInjectionCandidates] that supports direct (ACTION_SET_TEXT)
     *  injection — the only method compatible with replacing a specific span in place, which the
     *  live preview needs and paste-based injection can't do. Recycles every other candidate. */
    private fun findDirectInjectionCandidate(): AccessibilityNodeInfo? {
        val candidates = findInjectionCandidates()
        val direct = candidates.firstOrNull { it.isEditable || it.className?.toString()?.contains("EditText") == true }
        candidates.forEach { if (it !== direct) it.recycle() }
        return direct
    }

    /** Delegates to the extracted [AccessibilityTextDestination] so the streaming-partial path and
     *  the final-injection path share one, and only one, node-write implementation. */
    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean =
        AccessibilityTextDestination(node).replaceAllText(text)

    private fun refreshNode(node: AccessibilityNodeInfo): Boolean =
        AccessibilityTextDestination(node).refresh()

    private var partialCaretNudge: Runnable? = null

    /**
     * The caret nudge that used to ride directly behind every ACTION_SET_TEXT, moved to after the
     * write has been seen to land. Sent immediately it raced a Compose field's recomposition and
     * made the field re-apply its stale value -- silently undoing the write. Only the newest
     * partial's nudge is kept; a session that ended in the meantime skips it.
     */
    private fun scheduleCaretNudgeAfterLanding(node: AccessibilityNodeInfo, expected: String) {
        partialCaretNudge?.let(handler::removeCallbacks)
        val nudge = Runnable {
            partialCaretNudge = null
            if (streamingSession?.node !== node) return@Runnable
            val destination = AccessibilityTextDestination(node)
            if (destination.refresh() && destination.readText() == expected) destination.placeCaretAtEnd(expected.length)
        }
        partialCaretNudge = nudge
        handler.postDelayed(nudge, PARTIAL_CARET_NUDGE_DELAY_MS)
    }

    private fun endStreamingSession() {
        streamingSession?.let { restoreReplacedSelection(it); it.node.recycle() }
        streamingSession = null
    }

    /** #300: a session whose first partial overwrote a selection is being dropped without its final
     *  text ever landing (cancelled, empty transcript, recorder failure) -- put the selected text
     *  back so the user doesn't lose it. A session that overwrote nothing is left exactly as it has
     *  always been (the partial stays), so this only acts when text would otherwise be destroyed. */
    private fun restoreReplacedSelection(session: StreamingPreviewSession) {
        if (session.replacedText.isEmpty()) return
        clearStreamingLeftover(session)
    }

    /** Discards [pendingStreamingHandoff] without reconciling it against a final text (only a
     *  selection the session overwrote is restored, #300) --
     *  used when a new recording starts or the service is destroyed before any final injection
     *  consumed it (e.g. the recording was cancelled or hit the watchdog, so no text ever followed). */
    private fun flushPendingStreamingHandoff() {
        pendingStreamingHandoff?.let { restoreReplacedSelection(it); it.node.recycle() }
        pendingStreamingHandoff = null
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        rawText: String? = null,
        feedback: String? = DEFAULT_INJECT_FEEDBACK,
        feedbackDurationMs: Long = 2000,
        paidFallbackGroup: CleanupStepGroup? = null,
        existingHistoryTimestamp: Long? = null,
        // #175: set when this injection is a cleanup-failure fall-through. Carries the executor's
        // raw error so the notice can be built below, once `method` is known -- the message has to
        // state whether the clipboard actually holds anything, and that depends on the injection
        // method, which isn't decided until injection has been attempted. Passing a finished
        // string from the call site is what made the old message claim a clipboard copy that
        // DIRECT injections wipe immediately (see [CleanupFailureNotice]).
        cleanupError: String? = null,
        // #284: package the dictation was started in, for re-checking inside the empty-scan retry
        // (by then onIdleUi has cleared the shared target). Defaults to the live target; null =
        // unknown = fail open. A user-initiated raw retry has no live target, so it is unguarded.
        deliveryTargetPackage: String? = dictationTarget?.packageName,
    ) {
        // Before the vocabulary collector, history, clipboard or any write: a dictation whose
        // field is a password field is dropped whole (see [discardPasswordFieldResult]).
        if (resultTargetsPasswordField(dictationTarget)) {
            discardPasswordFieldResult()
            return
        }
        // Smart vocabulary suggestions (#216): every accepted dictation funnels through this
        // method -- direct injection, cleanup results, and preview-accepts alike (discarded
        // previews never reach here, so they never feed the counters). One fire-and-forget
        // call; the collector is toggle-gated, runs off-thread, and swallows its own errors,
        // so dictation can never be affected. When cleanup didn't run (rawText == null) the
        // accepted text doubles as the raw side, which yields no correction pairs -- exactly
        // right, since nothing was corrected.
        VocabularySuggestionCollector.collect(this, rawText = rawText ?: text, finalText = text)

        // An earlier paste's delayed restore runs now, not never: the clipboard read below must see
        // what the user had copied, not that dictation's text.
        pendingClipboardRestore?.let { handler.removeCallbacks(it); it.run() }
        pendingClipboardRestore = null
        pendingInjectionRetry?.let { handler.removeCallbacks(it) }
        pendingInjectionRetry = null
        val historyTimestamp = recordHistory(
            rawText ?: text,
            cleanedText = if (rawText != null) text else null,
            paidFallbackGroup = paidFallbackGroup,
            existingTimestamp = existingHistoryTimestamp,
        )

        // Claim whichever streaming-preview session (#29) is relevant to this injection right now,
        // before doing anything else (#45): either the recording's session is still live (this is
        // the normal, non-preview path -- resetToIdle() hasn't run yet) or it's already been moved to
        // pendingStreamingHandoff by an earlier resetToIdle() (the preview-before-inject path, #40,
        // where this injectText() call happens well after the recording ended). Either way, claiming
        // it here -- synchronously, before the empty-scan retry's delay -- guarantees finishInjection
        // always sees the session's real tracked data, never a nulled-out one. Zero effect when
        // streaming preview never ran this recording: both are already null, so streamingHandoff
        // stays null and every path below behaves exactly as it did before #45.
        val streamingHandoff = streamingSession ?: pendingStreamingHandoff
        streamingSession = null
        pendingStreamingHandoff = null

        // #256: final-insertion suppression. Read on demand, right here, at the moment injection
        // is about to happen -- this is deliberately a *fresh* currentForegroundPackageName()
        // read rather than whatever package was foreground when recording started, because the
        // exclusion list's whole point is "text never lands in this app," and the user may have
        // switched apps mid-dictation. No clipboard write and no node write happen on this path --
        // clipboard is itself a hop the text could be manually pasted from into the excluded app,
        // so it's suppressed too, not just the direct node injection. History recording above is
        // unaffected: it's a local record of what was said, not a write into the excluded app.
        if (ExclusionGating.shouldSuppressInsertion(
                PerAppExclusionStore.isExcluded(this, currentForegroundPackageName())
            )
        ) {
            Log.i(TAG, "Suppressing final insertion -- foreground app is on the exclusion list")
            if (streamingHandoff != null) {
                clearStreamingLeftover(streamingHandoff)
                streamingHandoff.node.recycle()
            }
            updatePendingInjection(InjectMethod.NONE, text, rawText ?: text, null, null, null, historyTimestamp)
            fallbackClipboardText = null
            showFeedback(
                "Ramblr is excluded in this app — nothing inserted or copied",
                FALLBACK_FEEDBACK_DURATION_MS,
                touchable = false,
                isFallback = true,
            )
            return
        }

        // Read only -- the dictated text is put on the clipboard later, and only when a paste-style
        // write actually needs it or nothing took the text (see finishInjection). A DIRECT
        // injection, the common case, never writes the clipboard at all.
        val priorClipboard = currentClipboardText()

        val candidates = findInjectionCandidates()

        // A tap that just stole focus from the target field can leave the very next scan seeing no
        // focused/editable node; one short rescan (#5) rescues that transient case without adding a
        // real delay to the common case where a candidate is already there.
        if (shouldRetryEmptyScan(candidates.size)) {
            Log.i(TAG, "No injection candidates on first scan; retrying in ${INJECTION_RETRY_DELAY_MS}ms")
            val retryTarget = deliveryTargetPackage?.let { DictationTarget(it, null) }
            val retry = Runnable {
                pendingInjectionRetry = null
                // Focus may have landed on a password field during the 200 ms: the rescan would
                // otherwise pick some other field in the window.
                if (resultTargetsPasswordField(null)) {
                    discardPasswordFieldResult(streamingHandoff)
                    return@Runnable
                }
                // #284: the 200 ms rescan reads the *active window* again; if the user left in the
                // meantime, hand over to the background path instead of typing into the new app.
                if (!deliveryTargetIsCurrent(retryTarget)) {
                    deliverToBackground(
                        text, rawText, paidFallbackGroup, cleanupError,
                        existingHistoryTimestamp = historyTimestamp, claimedStreamingHandoff = streamingHandoff,
                    )
                    return@Runnable
                }
                finishInjection(findInjectionCandidates(), text, rawText, priorClipboard, feedback, feedbackDurationMs, streamingHandoff, historyTimestamp, cleanupError)
            }
            pendingInjectionRetry = retry
            handler.postDelayed(retry, INJECTION_RETRY_DELAY_MS)
            return
        }

        finishInjection(candidates, text, rawText, priorClipboard, feedback, feedbackDurationMs, streamingHandoff, historyTimestamp, cleanupError)
    }

    private fun finishInjection(
        candidates: List<AccessibilityNodeInfo>,
        text: String,
        rawText: String?,
        priorClipboard: String?,
        feedback: String?,
        feedbackDurationMs: Long,
        streamingHandoff: StreamingPreviewSession?,
        historyTimestamp: Long,
        // #175: threaded through from injectText() rather than resolved there, because the notice
        // depends on the injection method -- which isn't decided until the write attempts below.
        cleanupError: String? = null,
    ) {
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        // #115: the final injection attempt starts here -- scanning is done, this is the actual
        // node-by-node write attempt below. Snapshotting once up front (rather than re-reading
        // System.currentTimeMillis() at the bottom) keeps "attempt start" honest even though the
        // loop below can itself take real time on a slow/unresponsive target node.
        val injectionAttemptAtMs = System.currentTimeMillis()

        var method = InjectMethod.NONE
        var priorNodeText: String? = null
        var injectedNode: AccessibilityNodeInfo? = null
        var handledStreamingHandoff = false
        // Staged at most once, right before the first paste attempt or for the NONE fallback --
        // never up front, so a DIRECT injection leaves the user's clipboard (and any clipboard
        // history / Android 13+ copy preview) completely untouched.
        var clipboardStaged = false
        val stageClipboard = {
            if (!clipboardStaged) {
                ClipboardUtil.copy(this, text)
                clipboardStaged = true
            }
        }
        try {
            for (candidate in candidates) {
                // #45: when this candidate is the exact node the streaming session was tracking,
                // the final text must close out its tracked span instead of an independent
                // selection-based insert -- otherwise the streaming leftover survives concatenated
                // alongside the final text (see reconcileStreamingSpan). Every other candidate goes
                // through the normal, unmodified path.
                val trackedSession = streamingHandoff?.takeIf { candidate == it.node }
                val attempt = if (trackedSession != null) {
                    tryCloseStreamingSpan(candidate, trackedSession, text)
                } else {
                    tryInjectIntoNode(candidate, text, stageClipboard)
                }
                if (attempt.method != InjectMethod.NONE) {
                    method = attempt.method
                    priorNodeText = attempt.priorText
                    if (attempt.method == InjectMethod.DIRECT) injectedNode = AccessibilityNodeInfo.obtain(candidate)
                    handledStreamingHandoff = trackedSession != null
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        // #45: the final text didn't land on the streaming session's tracked node/span (either it
        // wasn't among this scan's candidates at all, or it was tried and failed) -- its leftover
        // partial must be explicitly reverted so it isn't left silently orphaned in a field nobody
        // is about to overwrite.
        if (streamingHandoff != null) {
            if (!handledStreamingHandoff) clearStreamingLeftover(streamingHandoff)
            streamingHandoff.node.recycle()
        }

        Log.i(TAG, if (method != InjectMethod.NONE) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
        // NONE: the clipboard is the deliverable the user pastes from manually (#5).
        if (method == InjectMethod.NONE) stageClipboard()

        // #115: write the end-to-end pipeline benchmark line the moment injection has actually
        // resolved (success or clipboard fallback) -- this IS the "text appears in the focused
        // field" moment the whole timeline exists to measure. Consumed exactly once (the field is
        // nulled right after reading) so a later, unrelated retry-tap injectText() call (e.g.
        // onFeedbackTapped's raw-text retry, or a preview commit that runs well after the
        // original stop tap) never attributes this dictation's stale timing to itself.
        runtime.pipelineTiming.consume()?.let { timing ->
            val nowMs = System.currentTimeMillis()
            BenchmarkLogger.log(
                context = this,
                correlationId = timing.correlationId,
                pipeline = PipelineStage(
                    stopToDrainMs = timing.drainAtMs?.let { it - timing.stopTapAtMs },
                    injectionAttemptMs = injectionAttemptAtMs - timing.stopTapAtMs,
                    injectMethod = method.name,
                    totalMs = nowMs - timing.stopTapAtMs,
                ),
            )
        }

        updatePendingInjection(method, injectedText = text, rawText = rawText ?: text, priorClipboard, priorNodeText, injectedNode, historyTimestamp)

        val retryRawOffered = method != InjectMethod.NONE && rawText != null && rawText != text &&
            RawTextRetryToggle.isEnabled(this)
        val isFallback = method == InjectMethod.NONE
        fallbackClipboardText = if (isFallback) text else null

        // The skip reason for this dictation's translation, if any. Consumed here so it shows once,
        // with this dictation's insert.
        val translationNotice = pendingTranslationNotice
        pendingTranslationNotice = null
        val duration = when {
            retryRawOffered -> UNDO_RETRY_WINDOW_MS
            isFallback -> FALLBACK_FEEDBACK_DURATION_MS
            // Long enough to read the reason (it is a sentence, not a status word).
            translationNotice != null -> maxOf(feedbackDurationMs, TRANSLATION_NOTICE_DURATION_MS)
            else -> feedbackDurationMs
        }
        // retryRawOffered replaces the base feedback text entirely rather than appending a suffix
        // to it (e.g. "Copied to clipboard") -- the cleaned text is already injected at this point,
        // so "Copied to clipboard" is stale/misleading noise; the only actionable thing left to
        // tell the user is that tapping swaps in the raw transcript instead (Trevor's request).
        // #118: only the untouched default clipboard message gets swapped for DIRECT injections;
        // any explicit feedback the caller passed (raw-text-retry, cleanup-failed, etc.) is
        // meaningful and must survive as-is.
        val resolvedFeedback = injectionFeedbackFor(method, feedback)
        // #175: a cleanup-failure fall-through resolves its own message here, where `method` --
        // and therefore what the clipboard actually holds -- is finally known.
        // A translating dictation whose cloud call failed says so ("Translation failed"), not the
        // generic cleanup wording; one that never ran (no key/consent) carries its own notice.
        val translationFailed = translationRequested && !translationUnavailable
        val baseFeedback = cleanupError?.let { CleanupFailureNotice.messageFor(method, it, translating = translationFailed) }
            ?: resolvedFeedback
        val displayFeedback = when {
            retryRawOffered -> "Tap to use raw text"
            isFallback -> (translationNotice ?: baseFeedback)?.let { "$it · tap to copy again" }
            else -> translationNotice ?: baseFeedback
        }
        // #120: bias the bubble away from overlapping the field it just injected into, when that
        // node's on-screen bounds are known (DIRECT injections only -- that's the only path with
        // a live target node at this point).
        val targetBounds = injectedNode?.let { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect
        }
        displayFeedback?.let { showFeedback(it, duration, touchable = retryRawOffered || isFallback, isFallback = isFallback, targetBounds = targetBounds) }

        when (val action = clipboardClearActionFor(method, clipboardStaged, CLIPBOARD_CLEAR_DELAY_MS)) {
            ClipboardClearAction.Immediate -> restoreClipboardAfterInjection(text, priorClipboard)
            is ClipboardClearAction.Delayed -> {
                val restore = Runnable {
                    pendingClipboardRestore = null
                    restoreClipboardAfterInjection(text, priorClipboard)
                }
                pendingClipboardRestore = restore
                handler.postDelayed(restore, action.delayMs)
            }
            ClipboardClearAction.None -> {}
        }
    }

    /** Hands the clipboard back to whatever the user had copied before this dictation (#5) instead
     *  of wiping it to empty — see [clipboardRestoreOutcomeFor] for the compare-and-swap guard
     *  against clobbering something copied since. */
    private fun restoreClipboardAfterInjection(injectedText: String, priorClipboard: String?) {
        when (val outcome = clipboardRestoreOutcomeFor(currentClipboardText(), injectedText, priorClipboard)) {
            ClipboardRestoreOutcome.LeaveAlone -> {}
            ClipboardRestoreOutcome.Clear -> clearPrimaryClip()
            is ClipboardRestoreOutcome.Restore -> ClipboardUtil.copy(this, outcome.priorClipboard)
        }
    }

    private fun clearPrimaryClip() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
    }

    private fun currentClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    /** Replaces the undo/retry snapshot with the outcome of the injection that just ran (#27); a
     *  failed injection (NONE) clears it, since the clipboard copy is the intended deliverable
     *  there, not a side effect to undo. */
    private fun updatePendingInjection(
        method: InjectMethod,
        injectedText: String,
        rawText: String,
        priorClipboard: String?,
        priorNodeText: String?,
        node: AccessibilityNodeInfo?,
        historyTimestamp: Long,
    ) {
        pendingInjection?.node?.recycle()
        handler.removeCallbacks(expirePendingInjection)
        pendingInjection = if (method != InjectMethod.NONE) {
            PendingInjection(System.currentTimeMillis(), rawText, injectedText, priorClipboard, priorNodeText, node, historyTimestamp)
        } else {
            node?.recycle()
            null
        }
        pendingInjection?.let { handler.postDelayed(expirePendingInjection, UNDO_RETRY_WINDOW_MS) }
    }

    private fun clearPendingInjection() {
        pendingInjection?.node?.recycle()
        pendingInjection = null
        setFeedbackTouchable(false)
    }

    /** Long-press-while-IDLE affordance (#27): best-effort, last-injection-only undo. */
    private fun undoLastInjection() {
        val pending = pendingInjection
        if (pending == null) { toast("Nothing to undo"); return }

        val ageMs = System.currentTimeMillis() - pending.timestamp
        val nodeAvailable = pending.node != null && isNodeRestorable(pending.node)
        when (val plan = planUndo(ageMs, UNDO_RETRY_WINDOW_MS, nodeAvailable, pending.priorNodeText, pending.priorClipboard)) {
            is UndoPlan.RestoreInPlace -> {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.priorNodeText)
                }
                val restored = pending.node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
                if (restored) {
                    toast("Undid last insertion")
                } else if (pending.priorClipboard != null) {
                    ClipboardUtil.copy(this, pending.priorClipboard)
                    toast("Couldn't undo in place — previous text copied")
                } else {
                    toast("Couldn't undo — no prior text available")
                }
            }
            is UndoPlan.ClipboardOnly -> {
                ClipboardUtil.copy(this, plan.priorClipboard)
                toast("Couldn't undo in place — previous text copied")
            }
            UndoPlan.Expired -> toast("Undo window expired")
            UndoPlan.Unavailable -> toast("Nothing to undo")
        }
        clearPendingInjection()
    }

    /** A node can go stale the moment the user switches apps; refresh() catches a destroyed view,
     *  and the package check catches a view that's still alive but no longer in the foreground app. */
    private fun isNodeRestorable(node: AccessibilityNodeInfo): Boolean {
        val refreshed = try { node.refresh() } catch (e: Exception) { false }
        if (!refreshed) return false
        val activeRoot = rootInActiveWindow ?: return true
        val samePackage = activeRoot.packageName == node.packageName
        activeRoot.recycle()
        return samePackage
    }

    /**
     * Tap-the-feedback-bubble affordance: commits a pending preview (#40) when one is showing,
     * re-injects the pre-cleanup transcript when cleanup already ran (#27), or re-copies the
     * transcript to the clipboard when the last injection was a clipboard fallback (#5). These
     * never overlap — a pending preview means nothing has been injected yet, so it's checked
     * first; [fallbackClipboardText] is only set when the last injection method was NONE, and raw
     * retry requires a successful injection.
     */
    private fun onFeedbackTapped() {
        if (pendingPreview != null) {
            resolvePreview { p -> p.commit() }
            return
        }
        val pending = pendingInjection
        if (pending != null) {
            val ageMs = System.currentTimeMillis() - pending.timestamp
            if (canRetryRaw(ageMs, UNDO_RETRY_WINDOW_MS, pending.rawText, pending.injectedText)) {
                replaceInjectionWithRaw(pending)
                return
            }
        }
        fallbackClipboardText?.let {
            ClipboardUtil.copy(this, it)
            toast("Copied to clipboard")
        }
    }

    /**
     * "Tap to use raw text" (#343): swaps the cleaned text out of the field for the raw transcript.
     * Re-injecting through [injectText] would insert at the caret *next to* the cleaned text, so a
     * DIRECT injection whose node is still reachable is rewritten in place ([swapInjectedForRaw]).
     * Anything else -- a paste-based injection, a gone node, a field the user has since edited --
     * can't be swapped safely, so the raw text goes to the clipboard instead of being appended.
     */
    private fun replaceInjectionWithRaw(pending: PendingInjection) {
        val node = pending.node
        val prior = pending.priorNodeText
        var replaced = false
        if (node != null && prior != null && isNodeRestorable(node)) {
            val destination = AccessibilityTextDestination(node)
            val current = destination.readText()
            val swap = swapInjectedForRaw(prior, current, pending.injectedText, pending.rawText)
            if (swap != null) {
                replaced = DictationTextWriter.replaceConfirmed(destination, current, swap.text, swap.caret)
            }
        }
        if (replaced) {
            // #73: the dictation already has a history row; update it (same timestamp) so it
            // records the raw text that actually ended up in the field, not a second entry.
            recordHistory(pending.rawText, cleanedText = null, existingTimestamp = pending.historyTimestamp)
            showFeedback("Raw text inserted", 2000)
        } else {
            // The field still holds the cleaned text, so history is left as it was.
            ClipboardUtil.copy(this, pending.rawText)
            showFeedback("Raw text copied", 2000)
        }
        clearPendingInjection()
    }

    /** Persists the transcript to local history off the main thread (#25). [existingTimestamp],
     *  when supplied, updates that already-recorded row in place ([DictationHistoryStore.upsert])
     *  instead of adding a second entry for the same dictation (#73) -- the caller already
     *  recorded this dictation earlier (e.g. as soon as a cleanup candidate existed, before
     *  injection/preview even ran) and is now updating it with the outcome. Returns the
     *  timestamp actually used, so a caller recording for the first time can hand that identity
     *  to a later update call. */
    private fun recordHistory(
        rawText: String,
        cleanedText: String?,
        paidFallbackGroup: CleanupStepGroup? = null,
        existingTimestamp: Long? = null,
    ): Long {
        val timestamp = existingTimestamp ?: System.currentTimeMillis()
        if (!prefs().getBoolean("dictation_history_enabled", true)) return timestamp
        thread {
            try {
                historyStore.upsert(DictationHistoryEntry(timestamp, rawText, cleanedText, paidFallbackGroup))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record dictation history", e)
            }
        }
        return timestamp
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        // #116: the common case is that focus is already sitting on a valid injection target, so
        // check that single node with the same validity check ([isPotentialInjectionTarget]) the
        // full tree walk below uses before paying for a full traversal of (possibly deep) view
        // hierarchies across every active window. Only falls through to the full walk when there's
        // no active root, no input-focused node, or that node doesn't qualify -- the result in the
        // fast-path case is identical to what the full walk would have found, since findFocus(
        // FOCUS_INPUT) on the active root is exactly what collectInjectionCandidates also collects
        // first.
        findFastPathInjectionCandidate()?.let { return listOf(it) }

        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            // H3: this scan only went live 2026-08-26, when flagRetrieveInteractiveWindows was
            // first declared in accessibility_service_config.xml -- before that getWindows()
            // always returned empty and only rootInActiveWindow above ever produced candidates.
            // Parent will regression-test IME/keyboard behavior on-device before merge (baseline:
            // a 2026-08-25 investigation proved a Chrome NTP keyboard-hide bug was NOT Ramblr --
            // it reproduced with ALL accessibility services disabled).
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    /** Fast path for [findInjectionCandidates]: returns the active window's input-focused node
     *  immediately when it already passes [isPotentialInjectionTarget] -- the exact same validity
     *  check [collectPotentialTargets] applies to every node in the full walk -- so this never
     *  diverges from what the full walk would have picked as the top candidate. Returns null (and
     *  recycles whatever it obtained) when there's no active root, no focused node, or the focused
     *  node fails the check, leaving the full walk as the source of truth for those cases. */
    private fun findFastPathInjectionCandidate(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            // A focused WebView page node (container or generic page View) is not the field the
            // user means: the web input inside it is a separate EditText that may not have focus,
            // and pasting into the page node is a silent no-op. Let the full walk find the field
            // instead of settling for it (see WebViewInjectionRules). Native nodes are unaffected.
            if (!isNonFieldWebViewNode(focused) && isPotentialInjectionTarget(focused)) return focused
            focused.recycle()
            return null
        } finally {
            root.recycle()
        }
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        listOfNotNull(
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT),
            root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY),
        ).forEach { focused ->
            if (PasswordFieldGuard.allowsInjectionCandidate(focused.isPasswordField())) out += focused else focused.recycle()
        }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        if (!PasswordFieldGuard.allowsInjectionCandidate(node.isPasswordField())) return false
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    /** [isNonFieldWebViewNode] for a live node: walks at most [WEBVIEW_ANCESTOR_DEPTH] parents,
     *  and only for nodes that are neither editable nor EditText-classed, so the walk is never paid
     *  on the ordinary native-field path. */
    private fun isNonFieldWebViewNode(node: AccessibilityNodeInfo): Boolean =
        isNonFieldWebViewNode(node.className, node.isEditable) {
            var current = node.parent
            var depth = 0
            var found = false
            while (current != null && depth < WEBVIEW_ANCESTOR_DEPTH) {
                if (current.className?.contains("WebView") == true) { found = true; current.recycle(); break }
                val next = current.parent
                current.recycle()
                current = next
                depth++
            }
            current?.takeIf { !found }?.recycle()
            found
        }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        if (isNonFieldWebViewNode(node)) score -= BARE_WEBVIEW_SCORE_PENALTY
        return score
    }


    /** Closes out a streaming-preview session's tracked span with the final text (#45): the
     *  counterpart to [tryInjectIntoNode]'s selection-based insert, used only when [node] is the
     *  exact node [session] was already managing, so the streaming leftover is fully replaced rather
     *  than left concatenated alongside the final transcript. */
    private fun tryCloseStreamingSpan(node: AccessibilityNodeInfo, session: StreamingPreviewSession, text: String): TextCommitResult =
        DictationTextWriter.commitClosingStreamingSpan(
            AccessibilityTextDestination(node),
            StreamingSpan(session.insertionStart, session.lastPartialLength, session.replacedText, session.trace),
            text,
        )

    /** Reverts [session]'s leftover partial in its own node when the final injection ends up landing
     *  somewhere else (#45, e.g. focus moved after recording stopped) -- otherwise that fragment is
     *  left silently orphaned in a field nobody is about to overwrite. Best-effort: a node that's
     *  gone stale by now just has nothing left to revert. */
    private fun clearStreamingLeftover(session: StreamingPreviewSession) {
        DictationTextWriter.clearStreamingSpan(
            AccessibilityTextDestination(session.node),
            StreamingSpan(session.insertionStart, session.lastPartialLength, session.replacedText, session.trace),
        )
    }

    /**
     * Tries direct text injection before clipboard-based paste wherever possible (#111): a
     * classic editable node (isEditable / EditText-like) supports ACTION_SET_TEXT directly, no
     * clipboard round-trip required, so it's tried first now. Paste (a custom paste action, or
     * ACTION_PASTE) is reserved for nodes that don't look like a normal editable field -- some
     * Compose-based text fields report isEditable=false but still expose a working custom paste
     * action, which is the actual reason paste used to run first for every node.
     *
     * This matters beyond a code-path preference: every clipboard-based injection (FROM_CLIPBOARD)
     * makes Ramblr write the cleaned text to the clipboard and the target app read it back, and
     * Android 12+ shows a system "X pasted from your clipboard" toast for that cross-app read --
     * cosmetic noise at best, but it also visibly sits over the just-injected text in some apps
     * (Trevor hit this in Discord). Landing on ACTION_SET_TEXT first for ordinary editable fields
     * (which covers most apps, including Discord's message box) avoids the clipboard entirely and
     * with it that toast, while apps that genuinely need paste still get it as the fallback.
     */
    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String, stageClipboard: () -> Unit): TextCommitResult =
        DictationTextWriter.commit(AccessibilityTextDestination(node, beforePaste = stageClipboard), text)

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        AccessibilityTextDestination.findCustomPasteAction(node)

    private fun currentForegroundPackageName(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    private fun prefs() = getSharedPreferences("ramblr", MODE_PRIVATE)

    /**
     * Writes the overlay's current x/y to [prefs] (#101) so it survives a service recreation
     * (OS kill/restart, app update, etc.) instead of resetting to the hardcoded default. Called
     * only from the drag-release and fold/unfold-settle points where a gesture/transition has
     * actually finalized a resting position -- never from ACTION_MOVE, which would otherwise
     * write on every drag frame.
     */
    private fun persistOverlayPosition(x: Int, y: Int) {
        prefs().edit().putInt(PREF_OVERLAY_X, x).putInt(PREF_OVERLAY_Y, y).apply()
    }

    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
