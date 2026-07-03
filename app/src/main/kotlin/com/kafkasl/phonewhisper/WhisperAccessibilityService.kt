package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
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
 * A DIRECT injection never reads the clipboard, so it's safe to wipe right away. A FROM_CLIPBOARD
 * injection just read it, so clearing waits a grace period in case the target app hasn't finished
 * consuming it. NONE means the clipboard is the actual fallback delivery path — leave it alone.
 */
fun clipboardClearActionFor(method: InjectMethod, delayMs: Long): ClipboardClearAction = when (method) {
    InjectMethod.DIRECT -> ClipboardClearAction.Immediate
    InjectMethod.FROM_CLIPBOARD -> ClipboardClearAction.Delayed(delayMs)
    InjectMethod.NONE -> ClipboardClearAction.None
}

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
    val node: AccessibilityNodeInfo?
)

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null

        /** Whether Ramblr's own MainActivity is currently foregrounded (#35), kept as a static flag
         *  rather than only reacting when [instance] is non-null -- so a service that connects while
         *  MainActivity is already open (e.g. right after the user enables Accessibility from
         *  Settings and returns) still starts with the overlay hidden instead of covering the
         *  Settings switches it was just enabled from. */
        @Volatile private var mainActivityForeground = false

        /** Called from MainActivity's onResume/onPause so the floating overlay never covers its own
         *  Settings switches (#35). Safe to call whether or not the service is currently connected. */
        fun setMainActivityForeground(foreground: Boolean) {
            mainActivityForeground = foreground
            instance?.let { service -> service.handler.post { service.applyOverlayVisibility() } }
        }

        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64
        /** Small badge overlapping the ring's top-right corner (#34): shows/toggles the cleanup gate.
         *  Sized and bordered for at-a-glance visibility even in its OFF/grey state (Trevor found the
         *  original 20dp unbordered dot too easy to miss against the ring/background in practice). */
        private const val CLEANUP_BADGE_DP = 28

        /** Hold the button this long while TRANSCRIBING to cancel (see overlay.setOnTouchListener). */
        private const val LONG_PRESS_CANCEL_MS = 500L
        /** Backstop if no transcription/cleanup callback ever fires; covers transcription + cleanup callTimeouts. */
        private const val WATCHDOG_TIMEOUT_MS = 400_000L
        /** Grace period before wiping the clipboard after a paste-style injection reads it. */
        private const val CLIPBOARD_CLEAR_DELAY_MS = 30_000L
        /** How long "undo last insertion" / "retry with raw text" stay available after an injection (#27). */
        private const val UNDO_RETRY_WINDOW_MS = 10_000L
        /** Delay before rescanning once if the first candidate scan comes up empty (#5) — long enough
         *  for a transient post-tap focus race to settle, short enough not to feel laggy. */
        private const val INJECTION_RETRY_DELAY_MS = 200L
        /** Minimum gap between two streaming-preview partial injections into the focused field
         *  (#29) — chunks arrive far more often than this; injecting on every one would hammer the
         *  target app's input and feel janky. The very first partial of a recording bypasses this
         *  (see [shouldInjectPartial]) so live preview doesn't feel laggy at speech onset. */
        private const val STREAMING_PARTIAL_MIN_INTERVAL_MS = 400L
        /** Clipboard-fallback feedback stays up much longer than a normal success bubble (#5): it's
         *  the only record the user gets that injection didn't happen, so it shouldn't be easy to miss. */
        private const val FALLBACK_FEEDBACK_DURATION_MS = 6000L
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
        /** Cleanup-toggle badge (#34): emerald when cleanup will run, neutral grey when it's off. */
        private const val COLOR_CLEANUP_ON = 0xFF34D399.toInt()
        private const val COLOR_CLEANUP_OFF = 0xFF6B7280.toInt()
    }

    private val stateMachine = RecordingStateMachine()
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var cleanupToggleView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var cleanupToggleLayoutParams: WindowManager.LayoutParams? = null
    // screenW/screenH as of the last time the ring's position was computed (#41) -- the baseline
    // handleScreenSizeChange() diffs against to detect a fold/unfold. Set alongside layoutParams
    // in showOverlay() and kept in sync every time it repositions the ring.
    private var lastScreenW = 0
    private var lastScreenH = 0
    @Volatile private var recordingEngine: RecordingEngine? = null
    private val guard = TranscriptionGuard()
    private val inFlightCall = InFlightCall()
    private val cleanupCursor = CleanupWaterfallCursor()
    @Volatile private var activeToken: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        setFeedbackTouchable(false)
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Keeps the cleanup-toggle badge (#34) in sync the instant the toggle changes from anywhere
    // else -- e.g. MainActivity's Settings switch -- not just taps on the badge itself. Also
    // reacts to the "ever configured" flag (#38) flipping, since that's what brings the badge
    // into existence the first time cleanup is turned on.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PostProcessingToggle.KEY || key == CleanupBadgeVisibility.KEY_EVER_CONFIGURED) {
            handler.post { applyCleanupBadgeVisibility() }
        }
    }

    // Whether the cleanup badge window is currently added to the WindowManager (#38). The badge
    // View/LayoutParams are always constructed in showOverlay(), but only actually attached once
    // shouldShowBadge() is true -- see applyCleanupBadgeVisibility() -- so a plain-dictation user
    // who's never touched cleanup never has an (even grey/off) badge added at all.
    private var cleanupToggleAdded = false

    // Undo / retry-raw state (#27) — last injection only, cleared on use or expiry.
    private var pendingInjection: PendingInjection? = null
    private val expirePendingInjection = Runnable { clearPendingInjection() }

    // Preview-before-inject (#40) — the cleaned-up candidate awaiting an explicit commit tap,
    // null whenever no preview is in flight. Only touched on the main thread.
    private var pendingPreview: CleanupPreviewState? = null
    private val previewTimeoutRunnable = Runnable { resolvePreview { it.timeout() } }

    // Empty-scan retry (#5) — at most one in flight, cancelled if the service tears down first.
    private var pendingInjectionRetry: Runnable? = null

    // Text last delivered via clipboard-only fallback (#5); backs the feedback bubble's "tap to
    // copy again" affordance. Null whenever the last injection wasn't a fallback.
    private var fallbackClipboardText: String? = null

    // Local transcription engine (loaded lazily)
    private val transcriberSlot = TranscriberSlot<LocalTranscriber> { it.release() }

    // Streaming live-preview engine (#29) — only loaded when the opt-in setting is on and the
    // streaming model is installed (see initStreamingModel/shouldUseStreamingPreview). Kept loaded
    // across recordings like transcriberSlot; only its per-recording OnlineStream is torn down
    // between dictations (see StreamingTranscriber.beginSession/endSession).
    private val streamingTranscriberSlot = TranscriberSlot<StreamingTranscriber> { it.release() }

    // Live-preview injection state for the current recording (#29), null when no partial has been
    // injected yet this recording or once the session has ended. Only touched on the main thread.
    private data class StreamingPreviewSession(
        val node: AccessibilityNodeInfo,
        val insertionStart: Int,
        var lastPartialLength: Int,
        var lastInjectedText: String?,
        var lastInjectedAtMs: Long
    )
    private var streamingSession: StreamingPreviewSession? = null

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
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        thread { cleanupOrphanedRecordings() }
        // Try to load local model in background
        thread { initLocalModel() }
        thread { initStreamingModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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
        // If a recording is in progress, force the reader thread off RECORDING/TRANSCRIBING so
        // it tears down the AudioRecord and discards buffered PCM instead of leaking the mic or
        // silently continuing to record after the service appears off.
        recordingEngine?.let { engine ->
            stateMachine.reset()
            engine.awaitTeardown()
            recordingEngine = null
        }
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        guard.cancel()
        inFlightCall.cancel()
        teardownStreamingPreview()
        flushPendingStreamingHandoff()
        handler.removeCallbacks(expirePendingInjection)
        pendingInjection?.node?.recycle()
        pendingInjection = null
        pendingInjectionRetry?.let { handler.removeCallbacks(it) }
        pendingInjectionRetry = null
        handler.removeCallbacks(previewTimeoutRunnable)
        pendingPreview = null
        removeOverlay()
        super.onDestroy()
    }

    /** Deletes any temp PCM files left behind by a process death that skipped normal teardown. */
    private fun cleanupOrphanedRecordings() {
        cacheDir.listFiles { f -> f.name.startsWith("rec_") && f.name.endsWith(".pcm") }
            ?.forEach { it.delete() }
    }

    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        val newTranscriber = if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(this)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                LocalTranscriber.create(this, models.first())
            } else null
        } else {
            LocalTranscriber.create(this, modelName)
        }
        // Swap in the new transcriber, then release the old one — waiting for any transcription
        // still in flight on it — so switching models never holds more than one native recognizer.
        transcriberSlot.replace(newTranscriber)
        if (newTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    /**
     * (Re)loads the streaming live-preview model (#29), gated on both the opt-in setting and the
     * streaming model being installed (see [shouldUseStreamingPreview]). Called from MainActivity
     * when either changes, mirroring [reloadModel]'s pattern for the offline model.
     */
    private fun initStreamingModel() {
        val enabled = shouldUseStreamingPreview(
            settingEnabled = prefs().getBoolean("streaming_preview_enabled", false),
            streamingModelInstalled = StreamingTranscriber.isAvailable(this)
        )
        val newTranscriber = if (enabled) StreamingTranscriber.create(this) else null
        streamingTranscriberSlot.replace(newTranscriber)
        Log.i(TAG, if (newTranscriber != null) "Streaming preview ready" else "Streaming preview unavailable")
    }

    /** Reload the streaming preview model (called from MainActivity when the toggle or the
     *  streaming model's install state changes). */
    fun reloadStreamingModel() { thread { initStreamingModel() } }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }
        lastScreenW = screenW
        lastScreenH = screenH

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        // While TRANSCRIBING, holding the button for LONG_PRESS_CANCEL_MS cancels instead of
        // waiting for ACTION_UP; this can't collide with tap/drag handling since a plain tap or
        // drag never leaves the button down that long. The same hold also doubles as "undo last
        // insertion" (#27) when IDLE with a pending injection — the two never overlap since one
        // requires TRANSCRIBING and the other requires IDLE (RECORDING taps/drags are unaffected).
        var longPressFired = false
        val longPressAction = Runnable {
            longPressFired = true
            if (stateMachine.current() == RecordingStateMachine.State.TRANSCRIBING) {
                cancelTranscription()
            } else if (pendingInjection != null) {
                undoLastInjection()
            }
        }

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    longPressFired = false
                    if (stateMachine.current() == RecordingStateMachine.State.TRANSCRIBING || pendingInjection != null) {
                        handler.postDelayed(longPressAction, LONG_PRESS_CANCEL_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    cleanupToggleLayoutParams?.let {
                        positionCleanupToggle(it, params, ringSize)
                        if (cleanupToggleAdded) wm.updateViewLayout(cleanupToggleView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressAction)
                    if (longPressFired) return@setOnTouchListener true
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                        cleanupToggleLayoutParams?.let {
                            positionCleanupToggle(it, params, ringSize)
                            if (cleanupToggleAdded) wm.updateViewLayout(cleanupToggleView, it)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressAction)
                    true
                }
                else -> false
            }
        }

        // Tapping the feedback bubble either retries with the raw (pre-cleanup) transcript (#27)
        // or, on a clipboard fallback, re-copies the text (#5); only touchable while one of those
        // is actually on offer — see setFeedbackTouchable.
        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            setOnClickListener { onFeedbackTapped() }
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        // 1-tap cleanup on/off badge (#34): a small always-touchable dot overlapping the ring's
        // top-right corner, so flipping the global cleanup gate never requires leaving whatever
        // app is being dictated into, or even letting go of the drag-to-reposition affordance.
        val cleanupToggle = ImageView(this).apply {
            setImageResource(R.drawable.ic_cleanup)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val badgePad = (3 * dp).toInt()
            setPadding(badgePad, badgePad, badgePad, badgePad)
            isClickable = true
            setOnClickListener { onCleanupToggleTapped() }
        }

        val cleanupToggleSize = (CLEANUP_BADGE_DP * dp).toInt()
        val cleanupToggleParams = WindowManager.LayoutParams(
            cleanupToggleSize, cleanupToggleSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionCleanupToggle(cleanupToggleParams, params, ringSize)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        cleanupToggleView = cleanupToggle
        layoutParams = params
        feedbackLayoutParams = feedbackParams
        cleanupToggleLayoutParams = cleanupToggleParams
        // Attaches the badge window only if shouldShowBadge() says it should exist yet (#38); a
        // plain-dictation user who's never touched cleanup gets no badge at all, not even hidden.
        applyCleanupBadgeVisibility()
        applyOverlayVisibility()
    }

    /**
     * Reacts to a screen-size change delivered via [onConfigurationChanged] -- on a Pixel Fold
     * this fires for both a fold/unfold and an ordinary rotation, so [isFoldSizeChange] is used to
     * ignore a plain width/height swap (rotation has never repositioned the overlay, and this fix
     * (#41) is scoped to fold/unfold only). On a genuine fold-driven size change, the ring is
     * re-snapped to whichever edge it was already closest to and its y position is preserved
     * proportionally (rather than fought back to center) using the same [snappedXForScreenChange]/
     * [proportionalYForScreenChange] logic covered by unit tests -- then the feedback bubble and
     * #34 cleanup badge are re-derived from the ring's new params exactly as drag-to-reposition
     * already does, via [positionFeedback]/[positionCleanupToggle].
     */
    private fun handleScreenSizeChange() {
        val params = layoutParams ?: return
        val newScreenW = screenW
        val newScreenH = screenH
        if (!isFoldSizeChange(lastScreenW, lastScreenH, newScreenW, newScreenH)) {
            lastScreenW = newScreenW
            lastScreenH = newScreenH
            return
        }

        val ringSize = params.width
        val margin = (MARGIN_DP * dp).toInt()
        params.x = snappedXForScreenChange(params.x, lastScreenW, newScreenW, ringSize, margin)
        params.y = proportionalYForScreenChange(params.y, lastScreenH, newScreenH, ringSize, margin)
        lastScreenW = newScreenW
        lastScreenH = newScreenH

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let { wm.updateViewLayout(it, params) }
        feedbackLayoutParams?.let {
            positionFeedback(it, params)
            wm.updateViewLayout(feedbackView, it)
        }
        cleanupToggleLayoutParams?.let {
            positionCleanupToggle(it, params, ringSize)
            if (cleanupToggleAdded) wm.updateViewLayout(cleanupToggleView, it)
        }
    }

    /**
     * Adds or removes the cleanup badge's WindowManager window based on
     * [CleanupBadgeVisibility.shouldShowBadge] (#38), so the badge simply doesn't exist for a
     * user who has never turned cleanup on -- not merely hidden/grey. Safe to call at any time;
     * a no-op if the badge's already in the desired state. [updateCleanupToggleAppearance] is
     * only meaningful once the badge is actually attached, so it's called after ensuring that.
     */
    private fun applyCleanupBadgeVisibility() {
        val view = cleanupToggleView ?: return
        val params = cleanupToggleLayoutParams ?: return
        val shouldShow = CleanupBadgeVisibility.shouldShowBadge(
            everConfigured = CleanupBadgeVisibility.hasEverConfigured(this),
            currentlyEnabled = PostProcessingToggle.isEnabled(this)
        )
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (shouldShow && !cleanupToggleAdded) {
            wm.addView(view, params)
            cleanupToggleAdded = true
        } else if (!shouldShow && cleanupToggleAdded) {
            wm.removeView(view)
            cleanupToggleAdded = false
        }
        if (cleanupToggleAdded) updateCleanupToggleAppearance()
    }

    /**
     * Applies [overlayShouldBeVisible] to the live overlay views (#35): hides the ring and cleanup
     * badge (draw + touch) and dismisses the feedback bubble while MainActivity is foregrounded.
     * This only toggles presentation -- [layoutParams], [feedbackLayoutParams] and
     * [cleanupToggleLayoutParams] stay non-null throughout, so drag-to-reposition and the cleanup
     * badge's position tracking (#34) are untouched, and the overlay reappears exactly where it
     * was left. Recording/transcription state is never touched here.
     */
    private fun applyOverlayVisibility() {
        val visible = overlayShouldBeVisible(mainActivityForeground)
        setOverlayTouchable(visible)
        overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
        cleanupToggleView?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) handler.post(hideFeedback)
    }

    /** Adds/removes FLAG_NOT_TOUCHABLE on the ring and cleanup badge windows so a hidden overlay
     *  (#35) lets touches fall through to whatever's underneath -- e.g. MainActivity's Settings
     *  switches -- instead of the window silently swallowing them despite its view being GONE. */
    private fun setOverlayTouchable(touchable: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        fun WindowManager.LayoutParams.applyTouchable() {
            flags = if (touchable) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        layoutParams?.let { it.applyTouchable(); overlayView?.let { v -> wm.updateViewLayout(v, it) } }
        cleanupToggleLayoutParams?.let {
            it.applyTouchable()
            if (cleanupToggleAdded) cleanupToggleView?.let { v -> wm.updateViewLayout(v, it) }
        }
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        if (cleanupToggleAdded) cleanupToggleView?.let { wm.removeView(it) }
        cleanupToggleAdded = false
        cleanupToggleView = null
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
        cleanupToggleLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    /** Same as [circle] but with a white border, used for small badges (the #34 cleanup toggle)
     *  that need to read clearly against both the dark ring and whatever's behind it on-screen. */
    private fun circleWithBorder(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2 * dp).toInt(), 0xFFFFFFFF.toInt())
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000, touchable: Boolean = false, isFallback: Boolean = false) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            view.background = pill(if (isFallback) COLOR_FEEDBACK_FALLBACK_BG else COLOR_FEEDBACK_BG)
            setFeedbackTouchable(touchable)
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
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

    /** Keeps the cleanup badge (#34) glued to the ring's top-right corner as the bubble is dragged. */
    private fun positionCleanupToggle(
        toggleParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams,
        ringSize: Int
    ) {
        val badgeSize = (CLEANUP_BADGE_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        toggleParams.x = (bubbleParams.x + ringSize - badgeSize * 3 / 4)
            .coerceIn(margin, screenW - badgeSize - margin)
        toggleParams.y = (bubbleParams.y - badgeSize / 3).coerceIn(margin, screenH - badgeSize - margin)
    }

    /** Reflects the current [PostProcessingToggle] state on the badge: green when cleanup will run
     *  on the next dictation, grey when it's off. A white border keeps it visible against both the
     *  dark ring and light backgrounds in either state -- alpha is no longer used to signal OFF
     *  (color contrast alone does that job) since a dimmed badge was too easy to overlook. */
    private fun updateCleanupToggleAppearance() {
        val view = cleanupToggleView ?: return
        val enabled = PostProcessingToggle.isEnabled(this)
        view.background = circleWithBorder(if (enabled) COLOR_CLEANUP_ON else COLOR_CLEANUP_OFF)
        view.alpha = 1f
    }

    /**
     * 1-tap flip of the global cleanup gate (#34). Turning it OFF is always instant. Turning it ON
     * respects the existing local-transcription + cleanup consent gate (#23) instead of silently
     * bypassing it -- if consent hasn't been given yet, this sends the user to Settings once rather
     * than starting to send local transcripts off-device without the warning they'd get there.
     */
    private fun onCleanupToggleTapped() {
        val enabling = !PostProcessingToggle.isEnabled(this)
        if (enabling) {
            val useLocal = prefs().getBoolean("use_local", true)
            val hasConsented = prefs().getBoolean("local_cleanup_consent_seen", false)
            if (LocalCleanupConsent.shouldPrompt(useLocal, usePostProcessing = true, hasConsented = hasConsented)) {
                toast("Enable cleanup once in Ramblr settings first")
                return
            }
        }
        if (enabling) CleanupBadgeVisibility.markConfigured(this)
        PostProcessingToggle.setEnabled(this, enabling)
        updateCleanupToggleAppearance()
        showFeedback(if (enabling) "Cleanup on" else "Cleanup off", durationMs = 1200)
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (stateMachine.isRecording()) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (stateMachine.current()) {
            RecordingStateMachine.State.IDLE -> startRecording()
            RecordingStateMachine.State.RECORDING -> stopAndTranscribe()
            RecordingStateMachine.State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Ramblr app"); return
        }

        // A still-pending preview (#40) from the previous dictation shouldn't linger silently
        // while a new one starts -- resolve it the same safe way a timeout would.
        pendingPreview?.let { resolvePreview { p -> p.timeout() } }

        endStreamingSession()
        flushPendingStreamingHandoff()
        val streamingActive = streamingTranscriberSlot.get() != null
        if (streamingActive) streamingTranscriberSlot.use { it.beginSession() }

        val engine = RecordingEngine(cacheDir, stateMachine)
        val started = engine.start(
            onMaxDuration = { /* handled in startMaxDurationTranscription on the main thread */ },
            onFinished = { result -> onRecordingFinished(result) },
            onChunk = if (streamingActive) ::handleStreamingChunk else { _, _ -> }
        )
        if (!started) { toast("Couldn't start recording — mic busy?"); return }

        recordingEngine = engine
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()
    }

    /** Flips shared state; the reader thread notices, drains, tears down and hands off via [onRecordingFinished]. */
    private fun stopAndTranscribe() {
        if (!stateMachine.tryStartTranscribing()) return
        activeToken = guard.start()
        armWatchdog(activeToken)
        enterTranscribingUi()
    }

    private fun enterTranscribingUi() {
        handler.post { stopPulse() }
        setAppearance(COLOR_BUSY)
        setBusy(true)
    }

    /** Long-press while TRANSCRIBING (see overlay.setOnTouchListener): abort the in-flight call and return to idle. */
    private fun cancelTranscription() {
        if (stateMachine.current() != RecordingStateMachine.State.TRANSCRIBING) return
        inFlightCall.cancel()
        resetToIdle()
        toast("Transcription cancelled")
    }

    /** Backstop for a callback that never arrives (stalled socket, hung local model, etc). See #20. */
    private fun armWatchdog(token: Int) {
        handler.postDelayed({
            if (guard.isCurrent(token)) {
                inFlightCall.cancel()
                resetToIdle()
                toast("Transcription timed out")
            }
        }, WATCHDOG_TIMEOUT_MS)
    }

    /** Common teardown for every path back to IDLE: normal completion, cancel, or watchdog. */
    private fun resetToIdle() {
        guard.cancel()
        activeToken = 0
        stateMachine.reset()
        setBusy(false)
        setAppearance(COLOR_IDLE)
        teardownStreamingPreview()
    }

    /** Called on the reader thread once the AudioRecord has been drained and released. */
    private fun onRecordingFinished(result: RecordingEngine.Result) {
        recordingEngine = null
        if (result.discarded) return // service destroyed / recording forced off — nothing to transcribe

        val token = when {
            activeToken != 0 && guard.isCurrent(activeToken) -> activeToken
            result.stopReason == RecordingEngine.StopReason.MAX_DURATION -> {
                startMaxDurationTranscription(result)
                return
            }
            else -> {
                result.pcmFile?.delete()
                return
            }
        }
        continueTranscription(result, token)
    }

    /**
     * The reader thread can hit the duration cap without a UI tap, so no token exists yet. Mint
     * that token on the main thread with a fresh state check; if the user/service already reset to
     * IDLE, discard the temp file instead of resurrecting a cancelled transcription.
     */
    private fun startMaxDurationTranscription(result: RecordingEngine.Result) {
        handler.post {
            if (stateMachine.current() != RecordingStateMachine.State.TRANSCRIBING || activeToken != 0) {
                result.pcmFile?.delete()
                return@post
            }
            val token = guard.start()
            activeToken = token
            armWatchdog(token)
            enterTranscribingUi()
            toast("Recording limit reached (10 min) — transcribing…")
            thread { continueTranscription(result, token) }
        }
    }

    private fun continueTranscription(result: RecordingEngine.Result, token: Int) {
        val file = result.pcmFile
        if (file == null) { reset("No audio captured"); return }
        if (result.errorMessage != null) Log.e(TAG, "Recording ended with error: ${result.errorMessage}")

        val useLocal = prefs().getBoolean("use_local", true)

        if (useLocal && transcriberSlot.get() != null) {
            transcribeLocal(file, token)
        } else {
            transcribeApi(file, token)
        }
    }

    /**
     * [file] is read straight off disk into a bounded-size FloatArray (#16's duration cap bounds
     * a recording to ~19MB PCM / ~38MB of floats) rather than staging through an intermediate
     * ByteArray copy of the whole file first. sherpa-onnx's `OfflineStream.acceptWaveform` only
     * accepts one full FloatArray per stream — there's no incremental/chunked accept in the
     * vendored bindings — so this remains the single largest allocation on this path.
     */
    private fun transcribeLocal(file: File, token: Int) {
        thread {
            try {
                val samples = try { PcmFileBuffer.readAsFloatArray(file) } finally { file.delete() }

                val t0 = System.currentTimeMillis()
                val text = transcriberSlot.use { it.transcribe(samples, SAMPLE_RATE) }
                    ?: throw IllegalStateException("Local model was unloaded during transcription")
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

                handleTranscriptionResult(text, token)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    if (!guard.isCurrent(token)) return@post // cancelled or watchdog already reset the UI
                    toast("Local error: ${e.message}")
                    resetToIdle()
                }
            }
        }
    }

    private fun transcribeApi(file: File, token: Int) {
        val apiKey = ApiKeyStore.getApiKey(this)
        if (apiKey.isBlank()) { file.delete(); reset("Set API key in Ramblr app"); return }

        TranscriberClient.transcribe(file, apiKey, inFlightCall) { result ->
            file.delete()
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text, token)
            } else {
                handler.post {
                    if (!guard.isCurrent(token)) return@post // cancelled or watchdog already reset the UI
                    toast("Error: ${result.error ?: "empty transcript"}")
                    resetToIdle()
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?, token: Int) {
        if (text.isNullOrBlank()) {
            handler.post {
                if (!guard.isCurrent(token)) return@post
                toast("No speech detected")
                resetToIdle()
            }
            return
        }

        val usePostProcessing = PostProcessingToggle.shouldRunCleanup(PostProcessingToggle.isEnabled(this))
        val apiKey = ApiKeyStore.getApiKey(this)

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    if (!guard.isCurrent(token)) return@post
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    resetToIdle()
                }
                return
            }

            val rawPrompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            val vocabulary = VocabularyTerms.parse(prefs().getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))
            val prompt = PostProcessor.interpolateVocabulary(rawPrompt, vocabulary)
            val baseUrl = prefs().getString("cleanup_base_url", PostProcessor.DEFAULT_BASE_URL) ?: PostProcessor.DEFAULT_BASE_URL
            val model = prefs().getString("cleanup_model", PostProcessor.DEFAULT_MODEL) ?: PostProcessor.DEFAULT_MODEL

            if (!guard.isCurrent(token)) return
            val waterfall = CleanupWaterfallStore.load(this)
            PostProcessor.processWaterfall(
                this, text, prompt, waterfall, cleanupCursor, inFlightCall, apiKey, baseUrl, model,
            ) { result ->
                handler.post {
                    if (!guard.isCurrent(token)) return@post // cancelled or watchdog already reset the UI
                    if (result.text != null && result.text.isNotBlank()) {
                        val servingGroup = recordWaterfallSuccess(waterfall)
                        val paidFallbackGroup = servingGroup?.takeIf { it.isPaidFallback() }
                        if (PreviewBeforeInjectToggle.isEnabled(this)) {
                            beginPreview(rawText = text, candidateText = result.text, paidFallbackGroup = paidFallbackGroup)
                        } else {
                            injectText(result.text, rawText = text, paidFallbackGroup = paidFallbackGroup)
                        }
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                    }
                    resetToIdle()
                }
            }
        } else {
            handler.post {
                if (!guard.isCurrent(token)) return@post
                injectText(text)
                resetToIdle()
            }
        }
    }

    /** Marks whichever step of [waterfall] just served this cleanup call as healthy (#32), so the
     *  Settings status dot reflects real usage, not just a Test-button press, and returns that
     *  step's group so callers can attribute the result for dictation history's "paid fallback"
     *  badge (#33). Which step that was isn't threaded through [PostProcessor.processWaterfall]'s
     *  callback, so it's inferred from [cleanupCursor]'s last-known-good index instead -- safe to
     *  read immediately after a success since [CleanupWaterfallCursor.recordSuccess] was just
     *  called with "now", well inside the idle-expiry window [CleanupWaterfallCursor.startIndex]
     *  checks. A total-waterfall failure is deliberately not attributed to any one step here,
     *  since several steps may have failed for different reasons -- the Settings "Test" button is
     *  the deterministic way to pin down which. */
    private fun recordWaterfallSuccess(waterfall: CleanupWaterfall): CleanupStepGroup? {
        val succeededIndex = cleanupCursor.startIndex(System.currentTimeMillis())
        val step = waterfall.steps.getOrNull(succeededIndex) ?: return null
        CleanupStepStatusStore.record(this, step, CleanupStepHealth.SUCCESS)
        return step.group
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

        pendingPreview = CleanupPreviewState(rawText, candidateText, paidFallbackGroup)
        handler.postDelayed(previewTimeoutRunnable, PREVIEW_TIMEOUT_MS)

        val truncated = candidateText.take(PREVIEW_PREVIEW_CHARS)
        val suffix = if (candidateText.length > truncated.length) "…" else ""
        showFeedback("Preview: $truncated$suffix · tap to insert", PREVIEW_TIMEOUT_MS, touchable = true)
    }

    /** Runs [action] against the pending preview (if any) and injects whatever it resolved to. */
    private fun resolvePreview(action: (CleanupPreviewState) -> PreviewResolution) {
        val preview = pendingPreview ?: return
        pendingPreview = null
        handler.removeCallbacks(previewTimeoutRunnable)
        val resolution = action(preview)
        if (resolution.committed) {
            injectText(resolution.textToInject, rawText = preview.rawText, paidFallbackGroup = preview.paidFallbackGroup)
        } else {
            injectText(resolution.textToInject, feedback = "Cleanup skipped — raw copied to clipboard")
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        resetToIdle()
    }

    // --- Streaming preview (#29) ---

    /**
     * Runs on [RecordingEngine]'s reader thread (see [RecordingEngine.start]'s `onChunk`): converts
     * the raw PCM chunk and feeds it to the streaming recognizer, then hops to the main thread only
     * if there's a new hypothesis to potentially show. Decoding happens on every chunk regardless of
     * whether the result ends up injected — [maybeInjectPartial] is what throttles actual UI/field
     * updates, not this.
     */
    private fun handleStreamingChunk(buf: ByteArray, len: Int) {
        val samples = PcmFileBuffer.bytesToFloatArray(buf, len)
        val text = streamingTranscriberSlot.use { it.acceptChunk(samples, SAMPLE_RATE) } ?: return
        handler.post { maybeInjectPartial(text) }
    }

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
    private fun maybeInjectPartial(text: String) {
        if (!stateMachine.isRecording()) return // stale post after stop/cancel raced this
        if (text.isBlank()) return
        val now = System.currentTimeMillis()

        val session = streamingSession
        if (session == null) {
            val candidate = findDirectInjectionCandidate() ?: return
            val current = resolveRealText(candidate.text?.toString(), candidate.isShowingHintText)
            val insertionStart = resolveInsertionStart(candidate.textSelectionStart, candidate.textSelectionEnd, current.length)
            val displayText = smartCapitalize(text)
            if (!setNodeText(candidate, replacePartialInField(current, insertionStart, 0, displayText))) {
                candidate.recycle()
                return
            }
            streamingSession = StreamingPreviewSession(candidate, insertionStart, displayText.length, text, now)
            return
        }

        if (!shouldInjectPartial(text, session.lastInjectedText, session.lastInjectedAtMs, now, STREAMING_PARTIAL_MIN_INTERVAL_MS)) return
        if (!refreshNode(session.node)) { endStreamingSession(); return }

        val current = resolveRealText(session.node.text?.toString(), session.node.isShowingHintText)
        val displayText = smartCapitalize(text)
        val updated = replacePartialInField(current, session.insertionStart, session.lastPartialLength, displayText)
        if (!setNodeText(session.node, updated)) { endStreamingSession(); return }
        session.lastPartialLength = displayText.length
        session.lastInjectedText = text
        session.lastInjectedAtMs = now
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

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun refreshNode(node: AccessibilityNodeInfo): Boolean =
        try { node.refresh() } catch (e: Exception) { false }

    private fun endStreamingSession() {
        streamingSession?.node?.recycle()
        streamingSession = null
    }

    /** Tears down everything about the current recording's live-preview session: the streaming
     *  recognizer's per-recording stream, and the injected-node reference. The node reference isn't
     *  recycled outright here but moved into [pendingStreamingHandoff] (#45) -- the final batch
     *  injection may still be pending behind a preview-before-inject commit (#40) at this point, and
     *  needs that reference to reconcile its tracked span once it actually runs. Called from every
     *  path back to IDLE ([resetToIdle]) and from [onDestroy] (which flushes it right after, since
     *  no injection will ever follow there); safe to call even when no session is active. */
    private fun teardownStreamingPreview() {
        flushPendingStreamingHandoff()
        pendingStreamingHandoff = streamingSession
        streamingSession = null
        streamingTranscriberSlot.use { it.endSession() }
    }

    /** Discards [pendingStreamingHandoff] without attempting to reconcile it against any field --
     *  used when a new recording starts or the service is destroyed before any final injection
     *  consumed it (e.g. the recording was cancelled or hit the watchdog, so no text ever followed). */
    private fun flushPendingStreamingHandoff() {
        pendingStreamingHandoff?.node?.recycle()
        pendingStreamingHandoff = null
    }

    // --- Text injection ---

    private val clearClipboard = Runnable { clearPrimaryClip() }

    private fun injectText(
        text: String,
        rawText: String? = null,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000,
        paidFallbackGroup: CleanupStepGroup? = null
    ) {
        handler.removeCallbacks(clearClipboard)
        pendingInjectionRetry?.let { handler.removeCallbacks(it) }
        pendingInjectionRetry = null
        recordHistory(rawText ?: text, cleanedText = if (rawText != null) text else null, paidFallbackGroup = paidFallbackGroup)

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

        val priorClipboard = currentClipboardText()
        ClipboardUtil.copy(this, text)

        val candidates = findInjectionCandidates()

        // A tap that just stole focus from the target field can leave the very next scan seeing no
        // focused/editable node; one short rescan (#5) rescues that transient case without adding a
        // real delay to the common case where a candidate is already there.
        if (shouldRetryEmptyScan(candidates.size)) {
            Log.i(TAG, "No injection candidates on first scan; retrying in ${INJECTION_RETRY_DELAY_MS}ms")
            val retry = Runnable {
                pendingInjectionRetry = null
                finishInjection(findInjectionCandidates(), text, rawText, priorClipboard, feedback, feedbackDurationMs, streamingHandoff)
            }
            pendingInjectionRetry = retry
            handler.postDelayed(retry, INJECTION_RETRY_DELAY_MS)
            return
        }

        finishInjection(candidates, text, rawText, priorClipboard, feedback, feedbackDurationMs, streamingHandoff)
    }

    private fun finishInjection(
        candidates: List<AccessibilityNodeInfo>,
        text: String,
        rawText: String?,
        priorClipboard: String?,
        feedback: String?,
        feedbackDurationMs: Long,
        streamingHandoff: StreamingPreviewSession?
    ) {
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var method = InjectMethod.NONE
        var priorNodeText: String? = null
        var injectedNode: AccessibilityNodeInfo? = null
        var handledStreamingHandoff = false
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
                    tryInjectIntoNode(candidate, text)
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

        updatePendingInjection(method, injectedText = text, rawText = rawText ?: text, priorClipboard, priorNodeText, injectedNode)

        val retryRawOffered = method != InjectMethod.NONE && rawText != null && rawText != text
        val isFallback = method == InjectMethod.NONE
        fallbackClipboardText = if (isFallback) text else null

        val suffix = when {
            retryRawOffered -> " · tap to use raw text"
            isFallback -> " · tap to copy again"
            else -> ""
        }
        val duration = when {
            retryRawOffered -> UNDO_RETRY_WINDOW_MS
            isFallback -> FALLBACK_FEEDBACK_DURATION_MS
            else -> feedbackDurationMs
        }
        feedback?.let { showFeedback(it + suffix, duration, touchable = retryRawOffered || isFallback, isFallback = isFallback) }

        when (val action = clipboardClearActionFor(method, CLIPBOARD_CLEAR_DELAY_MS)) {
            ClipboardClearAction.Immediate -> clearPrimaryClip()
            is ClipboardClearAction.Delayed -> handler.postDelayed(clearClipboard, action.delayMs)
            ClipboardClearAction.None -> {}
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
        node: AccessibilityNodeInfo?
    ) {
        pendingInjection?.node?.recycle()
        handler.removeCallbacks(expirePendingInjection)
        pendingInjection = if (method != InjectMethod.NONE) {
            PendingInjection(System.currentTimeMillis(), rawText, injectedText, priorClipboard, priorNodeText, node)
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
                injectText(pending.rawText, feedback = "Raw text copied", feedbackDurationMs = 2000)
                return
            }
        }
        fallbackClipboardText?.let {
            ClipboardUtil.copy(this, it)
            toast("Copied to clipboard")
        }
    }

    /** Persists the transcript to local history off the main thread, right as it's handed off
     *  for injection/clipboard-copy, so a failed injection still leaves it recoverable (#25). */
    private fun recordHistory(rawText: String, cleanedText: String?, paidFallbackGroup: CleanupStepGroup? = null) {
        if (!prefs().getBoolean("dictation_history_enabled", true)) return
        val timestamp = System.currentTimeMillis()
        thread {
            try {
                historyStore.add(DictationHistoryEntry(timestamp, rawText, cleanedText, paidFallbackGroup))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record dictation history", e)
            }
        }
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
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

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
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
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    /** Outcome of one injection attempt. [priorText] is the node's full text before replacement —
     *  only captured on the DIRECT path, since that's the only case where undo can restore it (#27). */
    private data class InjectAttempt(val method: InjectMethod, val priorText: String? = null)

    /** Closes out a streaming-preview session's tracked span with the final text (#45): the
     *  counterpart to [tryInjectIntoNode]'s selection-based insert, used only when [node] is the
     *  exact node [session] was already managing, so the streaming leftover is fully replaced rather
     *  than left concatenated alongside the final transcript. */
    private fun tryCloseStreamingSpan(node: AccessibilityNodeInfo, session: StreamingPreviewSession, text: String): InjectAttempt {
        if (!refreshNode(node)) return InjectAttempt(InjectMethod.NONE)
        val current = resolveRealText(node.text?.toString(), node.isShowingHintText)
        val updated = reconcileStreamingSpan(
            current, StreamingSpan(session.insertionStart, session.lastPartialLength), text, isFinalInjectionTarget = true
        ) ?: return InjectAttempt(InjectMethod.NONE)
        return if (setNodeText(node, updated)) InjectAttempt(InjectMethod.DIRECT, priorText = current) else InjectAttempt(InjectMethod.NONE)
    }

    /** Reverts [session]'s leftover partial in its own node when the final injection ends up landing
     *  somewhere else (#45, e.g. focus moved after recording stopped) -- otherwise that fragment is
     *  left silently orphaned in a field nobody is about to overwrite. Best-effort: a node that's
     *  gone stale by now just has nothing left to revert. */
    private fun clearStreamingLeftover(session: StreamingPreviewSession) {
        if (!refreshNode(session.node)) return
        val current = resolveRealText(session.node.text?.toString(), session.node.isShowingHintText)
        val cleared = reconcileStreamingSpan(
            current, StreamingSpan(session.insertionStart, session.lastPartialLength), finalText = "", isFinalInjectionTarget = false
        ) ?: return
        setNodeText(session.node, cleared)
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): InjectAttempt {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return InjectAttempt(InjectMethod.FROM_CLIPBOARD)
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return InjectAttempt(InjectMethod.FROM_CLIPBOARD)

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = resolveRealText(node.text?.toString(), node.isShowingHintText)
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return InjectAttempt(InjectMethod.DIRECT, priorText = current)
        }

        return InjectAttempt(InjectMethod.NONE)
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    /**
     * Debug-only structural trace of an injection candidate. Never logs [AccessibilityNodeInfo.getText]
     * or [AccessibilityNodeInfo.getContentDescription] — those carry the on-screen contents of
     * whatever app the user is dictating into, so they must not reach logcat, even in debug builds.
     */
    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.d(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
