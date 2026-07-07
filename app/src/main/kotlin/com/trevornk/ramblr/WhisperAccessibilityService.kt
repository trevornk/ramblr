package com.trevornk.ramblr

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    val node: AccessibilityNodeInfo?,
    val historyTimestamp: Long,
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

        /** Best-effort package name for whichever app currently owns the active accessibility root. */
        fun foregroundPackageNameOrNull(): String? = instance?.currentForegroundPackageName()

        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        /** Default/reference ring diameter that [BTN_DP]/[PAD_DP] are proportioned against; the
         *  actual on-screen size comes from [OverlayAppearancePrefs] (#43/#53) and is scaled by
         *  the same ratio, so an unconfigured install looks pixel-identical to before that setting
         *  existed and a customized size keeps the glyph/padding proportions consistent. */
        private const val RING_DP = OverlayAppearancePrefs.DEFAULT_RING_DP
        private const val FEEDBACK_OFFSET_DP = 64

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

        /** Anonymous [ViewOutlineProvider]s can't be `const`, but this one never varies per-view,
         *  so a single shared instance is used to clip a custom icon image (#43) into a circle
         *  matching its own bounds, whatever size the ring is currently configured to. */
        private val OVAL_OUTLINE_PROVIDER = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
    }

    private val stateMachine = RecordingStateMachine()
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null

    // Auto-hide-to-peek (see RingPeek): true while the ring is currently slid over toward its
    // snapped edge, leaving only a small sliver visible. prePeekX is the full-position window x
    // to animate back to on restore -- null whenever isPeeked is false.
    private var isPeeked = false
    private var prePeekX: Int? = null
    private var peekAnimator: android.animation.ValueAnimator? = null
    private val idlePeekRunnable = Runnable { attemptAutoPeek() }
    // screenW/screenH as of the last time the ring's position was computed (#41) -- the baseline
    // handleScreenSizeChange() diffs against to detect a fold/unfold. Set alongside layoutParams
    // in showOverlay() and kept in sync every time it repositions the ring.
    private var lastScreenW = 0
    private var lastScreenH = 0
    @Volatile private var recordingEngine: RecordingEngine? = null
    private val guard = TranscriptionGuard()
    private val inFlightCall = InFlightCall()
    private val cleanupCursor = CleanupWaterfallCursor()

    /** Registered in [registerNetworkCallback]; null when registration failed or after teardown. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Registered in [registerScreenStateReceiver]; null when registration failed or after
     *  teardown. Reapplies overlay visibility on SCREEN_ON/SCREEN_OFF/USER_PRESENT so the ring
     *  hides the instant the device locks and reappears right after unlock (see
     *  [overlayShouldBeVisible]'s keyguard doc). */
    private var screenStateReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var activeToken: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        setFeedbackTouchable(false)
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
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
    private val previewTimeoutRunnable = Runnable { resolvePreview { it.timeout() } }

    // Empty-scan retry (#5) — at most one in flight, cancelled if the service tears down first.
    private var pendingInjectionRetry: Runnable? = null

    // Text last delivered via clipboard-only fallback (#5); backs the feedback bubble's "tap to
    // copy again" affordance. Null whenever the last injection wasn't a fallback.
    private var fallbackClipboardText: String? = null

    // Scheduled post-injection clipboard restore (#5) — at most one in flight, cancelled if a new
    // injection starts or the service tears down first. See clipboardRestoreOutcomeFor.
    private var pendingClipboardRestore: Runnable? = null

    // Local transcription engine (loaded lazily)
    private val transcriberSlot = TranscriberSlot<LocalTranscriber> { it.release() }

    // Streaming live-preview engine (#29) — only loaded when the opt-in setting is on and the
    // streaming model is installed (see initStreamingModel/shouldUseStreamingPreview). Kept loaded
    // across recordings like transcriberSlot; only its per-recording OnlineStream is torn down
    // between dictations (see StreamingTranscriber.beginSession/endSession).
    private val streamingTranscriberSlot = TranscriberSlot<StreamingTranscriber> { it.release() }

    // Set by onTrimMemory (#98) when the transcriber slots were released under memory pressure;
    // cleared once warmUpTranscribersIfTrimmed reloads them. Avoids reloading on every single
    // recording start -- only after a real trim actually emptied the slots.
    @Volatile private var transcribersTrimmed = false

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
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        ensureProviderChainMigrated()
        CustomPersonaStore.ensureLegacySeeded(this)
        showOverlay()
        registerNetworkCallback()
        registerScreenStateReceiver()
        thread { cleanupOrphanedRecordings() }
        thread { ModelDownloader.pruneOrphanedModelDirs(this) }
        // Try to load local model in background
        thread { initLocalModel() }
        thread { initStreamingModel() }
    }

    /**
     * Phase 2 provider-chain unification (#95): the live dictation paths below now read
     * ProviderChain/ProviderCredentialStore only, so the one-time legacy migration must run before
     * the first recording can resolve a provider. Kept in the AccessibilityService lifecycle (not
     * an Activity) because this is the component that owns always-on dictation and may be started
     * independently of settings UI.
     */
    private fun ensureProviderChainMigrated() {
        try {
            ProviderChainMigration.migrate(this)
        } catch (e: Exception) {
            Log.e(TAG, "Provider chain migration failed; falling back to ProviderChainStore defaults", e)
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
                cleanupCursor.reset()
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
        unregisterNetworkCallback()
        unregisterScreenStateReceiver()
        // If a recording is in progress, force the reader thread off RECORDING/TRANSCRIBING so
        // it tears down the AudioRecord and discards buffered PCM instead of leaking the mic or
        // silently continuing to record after the service appears off.
        recordingEngine?.let { engine ->
            stateMachine.reset()
            engine.awaitTeardown()
            recordingEngine = null
        }
        guard.cancel()
        inFlightCall.cancel()
        // The cancel above makes any in-flight local completion abort at its next piece check
        // (#83), so the holder's daemon thread can close the cached ~1 GB cleanup model promptly
        // without this main-thread teardown waiting on it (#74).
        LocalCleanupModelHolder.releaseAsync()
        teardownStreamingPreview()
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            LocalCleanupModelHolder.releaseAsync()
            transcribersTrimmed = true
            transcriberSlot.replace(null)
            streamingTranscriberSlot.replace(null)
        }
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
        val archive = prefs().getString("streaming_model_name", STREAMING_MODEL.archive) ?: STREAMING_MODEL.archive
        val model = ModelDownloader.resolveActiveModel(STREAMING_MODEL_CATALOG, archive)
        val enabled = shouldUseStreamingPreview(
            settingEnabled = prefs().getBoolean("streaming_preview_enabled", false),
            streamingModelInstalled = StreamingTranscriber.isAvailable(this, model)
        )
        val newTranscriber = if (enabled) StreamingTranscriber.create(this, model) else null
        streamingTranscriberSlot.replace(newTranscriber)
        Log.i(TAG, if (newTranscriber != null) "Streaming preview ready" else "Streaming preview unavailable")
    }

    /** Reload the streaming preview model (called from MainActivity when the toggle or the
     *  streaming model's install state changes). */
    fun reloadStreamingModel() { thread { initStreamingModel() } }

    // --- Overlay ---

    private fun showOverlay() {
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
        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

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
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }
        lastScreenW = screenW
        lastScreenH = screenH

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var lastRawX = 0f; var lastRawY = 0f
        // Long-press behavior is entirely state-driven -- see overlayLongPressActionFor's doc for
        // the full state -> action map (cancel while TRANSCRIBING, undo while IDLE with a pending
        // injection (#27), or the #53 style/cleanup menu in the one remaining reachable state:
        // IDLE with nothing pending). RECORDING never arms the timer at all, exactly as before.
        var longPressFired = false
        // Set only when this gesture's long-press actually opened the style menu (#57) -- lets
        // ACTION_MOVE single out "the menu I just opened" from cancel-transcription/undo already
        // having fired, neither of which is a persistent overlay that needs dismissing on drag.
        var styleMenuOpenedByLongPress = false
        // Set when ACTION_DOWN consumed this gesture as a peek-restore tap (Feature A) -- ACTION_UP
        // must then no-op instead of falling through to onTap()/drag-snap using touchX/touchY that
        // were never updated for this gesture (still holding whatever the previous gesture left).
        var consumedByPeekRestore = false
        val longPressAction = Runnable {
            val action = overlayLongPressActionFor(stateMachine.current(), hasPendingInjection = pendingInjection != null)
            val moved = abs(lastRawX - touchX) + abs(lastRawY - touchY)
            if (!shouldFireLongPress(action, movedPastThreshold = moved >= TAP_THRESHOLD_DP * dp)) return@Runnable
            longPressFired = true
            armIdlePeekTimer()
            when (action) {
                OverlayLongPressAction.CANCEL_TRANSCRIPTION -> cancelTranscription()
                OverlayLongPressAction.UNDO_INJECTION -> undoLastInjection()
                OverlayLongPressAction.SHOW_STYLE_MENU -> { showStyleMenu(); styleMenuOpenedByLongPress = true }
                OverlayLongPressAction.NONE -> longPressFired = false
            }
        }

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    armIdlePeekTimer()
                    consumedByPeekRestore = false
                    // A touch-down on a currently-peeked ring is a restore tap, not the start of a
                    // normal drag/tap/long-press gesture (Feature A): consume it here so it can
                    // never also fall through to onTap()'s mic-toggle on ACTION_UP.
                    if (isPeeked) {
                        restoreFromPeek()
                        consumedByPeekRestore = true
                        return@setOnTouchListener true
                    }
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    longPressFired = false
                    styleMenuOpenedByLongPress = false
                    val pendingAction = overlayLongPressActionFor(stateMachine.current(), hasPendingInjection = pendingInjection != null)
                    if (pendingAction != OverlayLongPressAction.NONE) {
                        handler.postDelayed(longPressAction, LONG_PRESS_CANCEL_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (consumedByPeekRestore) return@setOnTouchListener true
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params, feedbackView?.height ?: 0)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    // The hold that opened the style menu (#57) has turned into a drag -- dismiss
                    // the now-unwanted menu and fall back to plain drag-to-reposition; ACTION_UP's
                    // existing moved-based branch takes it from here exactly as an ordinary drag.
                    if (styleMenuOpenedByLongPress && abs(ev.rawX - touchX) + abs(ev.rawY - touchY) >= TAP_THRESHOLD_DP * dp) {
                        dismissStyleMenu()
                        styleMenuOpenedByLongPress = false
                        longPressFired = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressAction)
                    armIdlePeekTimer()
                    if (consumedByPeekRestore) return@setOnTouchListener true
                    if (longPressFired) return@setOnTouchListener true
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params, feedbackView?.height ?: 0)
                            wm.updateViewLayout(feedbackView, it)
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
        positionFeedback(feedbackParams, params, feedbackHeight = 0)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
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
     * Reacts to a screen-size change delivered via [onConfigurationChanged] -- on a Pixel Fold
     * this fires for both a fold/unfold and an ordinary rotation, so [isFoldSizeChange] is used to
     * ignore a plain width/height swap (rotation has never repositioned the overlay, and this fix
     * (#41) is scoped to fold/unfold only). On a genuine fold-driven size change, the ring is
     * re-snapped to whichever edge it was already closest to and its y position is preserved
     * proportionally (rather than fought back to center) using the same [snappedXForScreenChange]/
     * [proportionalYForScreenChange] logic covered by unit tests -- then the feedback bubble is
     * re-derived from the ring's new params exactly as drag-to-reposition already does, via
     * [positionFeedback]. Any open style menu (#53) is dismissed rather than repositioned, since
     * its anchor math would otherwise go stale mid-fold.
     */
    private fun handleScreenSizeChange() {
        dismissStyleMenu()
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
            positionFeedback(it, params, feedbackView?.height ?: 0)
            wm.updateViewLayout(feedbackView, it)
        }
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
        val visible = overlayShouldBeVisible(mainActivityForeground, IconHiddenState.isHidden(this), isKeyguardLocked())
        setOverlayTouchable(visible)
        overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            handler.post(hideFeedback)
            dismissStyleMenu()
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
     *  peeked when the new interaction began. */
    private fun armIdlePeekTimer() {
        handler.removeCallbacks(idlePeekRunnable)
        handler.postDelayed(idlePeekRunnable, RingPeek.IDLE_TIMEOUT_MS)
    }

    /** Fires once [RingPeek.IDLE_TIMEOUT_MS] has elapsed with no ring interaction. Never peeks
     *  while actively recording or while cleanup/transcription is in flight (RingPeek.shouldAutoPeek),
     *  nor while the ring itself isn't currently showing (e.g. MainActivity foregrounded, #35) or
     *  already peeked, nor at all while the user has turned auto-peek off in Advanced settings
     *  (see [AutoPeekToggle]) -- the idle timer keeps re-arming itself in that case so the ring is
     *  ready to peek again the moment the setting is turned back on, without needing a service
     *  restart. */
    private fun attemptAutoPeek() {
        if (isPeeked) return
        if (!AutoPeekToggle.isEnabled(this)) { armIdlePeekTimer(); return }
        if (!RingPeek.shouldAutoPeek(stateMachine.current())) { armIdlePeekTimer(); return }
        val overlay = overlayView ?: return
        if (overlay.visibility != View.VISIBLE) { armIdlePeekTimer(); return }
        val params = layoutParams ?: return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ringSize = params.width
        val peekVisiblePx = (RingPeek.PEEK_VISIBLE_DP * dp).toInt()
        val targetX = RingPeek.peekedX(params.x, screenW, ringSize, peekVisiblePx)

        prePeekX = params.x
        isPeeked = true
        animateRingX(params, wm, targetX)
    }

    /** Restores the ring to its pre-peek position immediately -- called on a touch-down that
     *  lands on a peeked ring (see overlay.setOnTouchListener). Deliberately does NOT call onTap(),
     *  so un-peeking a tap never also toggles the mic. */
    private fun restoreFromPeek() {
        val params = layoutParams ?: return
        val targetX = prePeekX ?: return
        isPeeked = false
        prePeekX = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        animateRingX(params, wm, targetX)
        armIdlePeekTimer()
    }

    /** Called from [AdvancedActivity] right after the user flips [AutoPeekToggle] off, so a ring
     *  that's already peeked at that moment snaps back to full visibility immediately instead of
     *  silently staying peeked until the next unrelated touch happens to restore it. No-ops if the
     *  ring isn't currently peeked. Internal (not private) for the same cross-class-call reason as
     *  [applyOverlayVisibility]. */
    internal fun restoreFromPeekIfPeeked() {
        if (isPeeked) restoreFromPeek()
    }

    /** Animates the ring window's x from its current value to [targetX], keeping the feedback
     *  bubble anchored to it in step exactly like drag-to-reposition already does. */
    private fun animateRingX(params: WindowManager.LayoutParams, wm: WindowManager, targetX: Int) {
        peekAnimator?.cancel()
        val startXValue = params.x
        val overlay = overlayView ?: return
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
        }
        peekAnimator = animator
        animator.start()
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
        button = null
        spinner = null
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
     *  and idle-only-fill-override product decisions this implements. */
    private fun applyButtonAppearance(stateColor: Int) {
        val btn = button ?: return
        val appearance = OverlayAppearancePrefs.load(this)

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
        btn.background = circleWithBorder(fill, appearance.borderColor ?: Color.TRANSPARENT)
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

            val stateColor = when (stateMachine.current()) {
                RecordingStateMachine.State.IDLE -> COLOR_IDLE
                RecordingStateMachine.State.RECORDING -> COLOR_RECORDING
                RecordingStateMachine.State.TRANSCRIBING -> COLOR_BUSY
            }
            applyButtonAppearance(stateColor)

            feedbackLayoutParams?.let { positionFeedback(it, params, feedbackView?.height ?: 0); wm.updateViewLayout(feedbackView, it) }
        }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
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
     */
    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams,
        feedbackHeight: Int
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        val ringSize = bubbleParams.width
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = if (bubbleParams.y + ringSize / 2 > screenH / 2) {
            // Icon in bottom half: open upward, same placement as before this fix.
            (bubbleParams.y - feedbackHeight - margin).coerceAtLeast(margin)
        } else {
            // Icon in top half: open downward instead, so the bubble can't cover the icon.
            (bubbleParams.y + ringSize + margin).coerceAtMost(screenH - feedbackHeight - margin)
        }
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
            // view.height reflects the PREVIOUS text's measured size (0 before the bubble has
            // ever been shown) -- close enough for the initial placement below, then corrected
            // once this text's real layout is available, same two-pass approach as
            // showStyleMenu's menu.post{} (WRAP_CONTENT's true size still isn't known until after
            // the next layout pass actually runs).
            positionFeedback(feedbackParams, bubbleParams, view.height)
            wm.updateViewLayout(view, feedbackParams)
            view.post {
                if (feedbackView !== view) return@post // hidden/replaced already
                positionFeedback(feedbackParams, bubbleParams, view.height)
                wm.updateViewLayout(view, feedbackParams)
            }

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

    // --- Long-press style/cleanup menu (#53, #103) ---

    /** Personas shown in the long-press style menu (#103): the user's persisted quick-menu
     *  selection (built-in and/or custom, 5-8 entries, see [QuickMenuPersonaStore]), resolved to
     *  full [CleanupPersona] objects in the user's chosen order. Falls back to
     *  [QuickMenuPersonaStore.defaultSelection] (the five #103 built-ins) for a fresh install. */
    private fun quickMenuPersonas(): List<CleanupPersona> =
        QuickMenuPersonaStore.load(this).map { PersonaRegistry.resolve(this, it) }

    /**
     * Opens the long-press menu (see overlayLongPressActionFor's state map) letting the user
     * toggle cleanup on/off and pick a cleanup persona (#40) without leaving whatever app they're
     * dictating into. Implemented as two plain WindowManager-added views -- a full-screen
     * touchable scrim (dismisses on an outside tap) plus the menu content on top -- rather than a
     * PopupMenu/PopupWindow: those are anchored to a real Activity/View's window token, which an
     * AccessibilityService's floating overlay doesn't have, so they can't reliably anchor to (or
     * even show above) this service's own TYPE_ACCESSIBILITY_OVERLAY windows.
     */
    private fun showStyleMenu() {
        dismissStyleMenu()
        val ringParams = layoutParams ?: return
        val ringSize = ringParams.width
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val scrim = FrameLayout(this).apply { setOnClickListener { dismissStyleMenu() } }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val menu = buildStyleMenuContent()
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

        wm.addView(scrim, scrimParams)
        wm.addView(menu, menuParams)
        styleMenuScrim = scrim
        styleMenuView = menu

        // WRAP_CONTENT's real size isn't known until after the first layout pass, so the anchor
        // math runs once more here rather than trying to pre-compute the menu's height.
        menu.post {
            if (styleMenuView !== menu) return@post // dismissed already
            positionStyleMenu(menuParams, ringParams, ringSize, menu.width, menu.height)
            wm.updateViewLayout(menu, menuParams)
        }
    }

    private fun dismissStyleMenu() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        styleMenuView?.let { wm.removeView(it) }
        styleMenuScrim?.let { wm.removeView(it) }
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
        val margin = (MARGIN_DP * dp).toInt()
        menuParams.x = if (ringParams.x + ringSize / 2 > screenW / 2) {
            (ringParams.x + ringSize - menuWidth).coerceAtLeast(margin)
        } else {
            ringParams.x.coerceAtMost(screenW - menuWidth - margin)
        }
        menuParams.y = (ringParams.y - menuHeight - margin).coerceIn(margin, screenH - menuHeight - margin)
    }

    private fun buildStyleMenuContent(): LinearLayout {
        val container = LinearLayout(this).apply {
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

        val cleanupEnabled = PostProcessingToggle.isEnabled(this)
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

        val globalPersona = PersonaRegistry.currentPersona(
            this,
            prefs().getString("cleanup_style", null),
            prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        )
        val current = if (PerAppPersonaToggle.isEnabled(this)) {
            PerAppPersonaStore.resolvePersona(this, currentForegroundPackageName(), globalPersona)
        } else {
            globalPersona
        }
        for (persona in quickMenuPersonas()) {
            val title = if (persona == current) "✓ ${persona.title}" else persona.title
            container.addView(styleMenuRow(icon = null, title = title, subtitle = persona.subtitle) {
                onStyleMenuPersonaTapped(persona)
            })
        }

        container.addView(styleMenuDivider())

        val streamingEnabled = prefs().getBoolean("streaming_preview_enabled", false)
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
                styleMenuRow(icon = null, title = "Hide icon", subtitle = "Fully hides it -- restore from a notification") {
                    onStyleMenuHideIconTapped()
                }
            )
        }

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
        val enabling = !PostProcessingToggle.isEnabled(this)
        if (enabling && !ensureCleanupEnabled()) {
            dismissStyleMenu()
            toast("Enable cleanup once in Ramblr settings first")
            return
        }
        if (!enabling) PostProcessingToggle.setEnabled(this, false)
        dismissStyleMenu()
        showFeedback(if (enabling) "Cleanup on" else "Cleanup off", durationMs = 1200)
    }

    /** Turns cleanup on if it isn't already, respecting the same local-cleanup consent gate (#23)
     *  [onStyleMenuCleanupToggleTapped] enforces. Returns whether cleanup ended up enabled --
     *  true if it was already on or turning it on just succeeded, false if blocked on consent. */
    private fun ensureCleanupEnabled(): Boolean {
        if (PostProcessingToggle.isEnabled(this)) return true
        val useLocal = prefs().getBoolean("use_local", true)
        val hasConsented = prefs().getBoolean("local_cleanup_consent_seen", false)
        val cleanupIsLocalOnly = !CloudFeatureToggle.cleanupEnabled(this)
        if (LocalCleanupConsent.shouldPrompt(useLocal, usePostProcessing = true, hasConsented = hasConsented, cleanupIsLocalOnly = cleanupIsLocalOnly)) return false
        PostProcessingToggle.setEnabled(this, true)
        return true
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
    private fun onStyleMenuPersonaTapped(persona: CleanupPersona) {
        if (PerAppPersonaToggle.isEnabled(this)) {
            PerAppPersonaStore.record(this, currentForegroundPackageName(), persona)
        }
        prefs().edit()
            .putString("cleanup_style", persona.key)
            .putString("post_processing_prompt", CleanupPersonas.promptForExplicitSelection(persona))
            .apply()
        val cleanupEnabled = ensureCleanupEnabled()
        dismissStyleMenu()
        showFeedback(
            if (cleanupEnabled) "Style: ${persona.title}" else "Style: ${persona.title} (enable cleanup in Settings)",
            durationMs = 1200
        )
    }

    /** Streaming live-preview on/off from the style menu (#57) -- reads/writes the exact same
     *  "streaming_preview_enabled" pref MainActivity's Settings toggle uses (see
     *  MainActivity.onStreamingPreviewToggle), including the same "model must be installed"
     *  gate, so both surfaces always agree on state and neither can turn on a live preview with
     *  nothing installed to back it. */
    private fun onStyleMenuStreamingToggleTapped() {
        val enabling = !prefs().getBoolean("streaming_preview_enabled", false)
        if (enabling && !ModelDownloader.isInstalled(this, selectedStreamingModel())) {
            dismissStyleMenu()
            toast("Download the streaming model in Ramblr settings first")
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

    /** "Hide icon" long-press menu row (Feature B): fully hides the ring/feedback bubble (same
     *  end-state as MainActivity being foregrounded, #35) and posts the "tap to show it again"
     *  notification, since once the ring is gone there's otherwise no on-screen affordance left
     *  to bring it back. */
    private fun onStyleMenuHideIconTapped() {
        dismissStyleMenu()
        IconHiddenState.setHidden(this, true)
        applyOverlayVisibility()
        IconVisibilityNotifications.postHidden(this)
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

        warmUpLocalCleanupModelIfNeeded()
        warmUpTranscribersIfTrimmed()
        warmUpCloudConnectionsIfNeeded()

        // A still-pending preview (#40) from the previous dictation shouldn't linger silently
        // while a new one starts -- resolve it the same safe way a timeout would.
        pendingPreview?.let { resolvePreview { p -> p.timeout() } }

        endStreamingSession()
        flushPendingStreamingHandoff()
        lastBubblePartialText = null
        lastBubblePartialAtMs = 0L
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

    /**
     * Pre-warms the local cleanup model (#95) the instant recording starts, so its cold GGUF
     * load (mmap + first-touch page faults on a several-hundred-MB file) overlaps with the user
     * still talking and the transcription that follows, instead of starting only once cleanup
     * itself runs and eating into [CLEANUP_WATERFALL_HARD_CAP_MS]'s budget -- see
     * [LocalCleanupModelHolder.warmUpAsync]'s kdoc for the failure mode this fixes.
     *
     * Deliberately checked (not unconditional): only bothers when cleanup is actually enabled
     * and the configured waterfall would use LOCAL_LLM for at least one step, since otherwise
     * this would resident-load a multi-hundred-MB model into memory for a dictation that will
     * never touch it (e.g. cleanup off, or an all-cloud waterfall).
     */
    private fun warmUpLocalCleanupModelIfNeeded() {
        if (!PostProcessingToggle.shouldRunCleanup(PostProcessingToggle.isEnabled(this))) return
        val providerChain = ProviderChainStore.load(this)
        if (!providerChain.usesLocalLlm()) return
        val modelPath = ModelDownloader.localCleanupModelFile(this, LocalCleanupProvider.selectedModel(this))?.absolutePath
            ?: return
        LocalCleanupModelHolder.warmUpAsync(modelPath)
    }

    /**
     * Reloads the batch (and, if enabled, streaming) transcriber if [onTrimMemory] released them
     * under memory pressure (#98) -- mirrors [warmUpLocalCleanupModelIfNeeded]'s pre-warm timing:
     * this runs the instant recording starts, so a reload (typically well under a second for
     * these much-smaller-than-the-cleanup-LLM models) overlaps with the user still talking rather
     * than adding perceived latency at transcription time. No-ops on the far more common case
     * where no trim has happened since the last dictation.
     */
    private fun warmUpTranscribersIfTrimmed() {
        if (!transcribersTrimmed) return
        transcribersTrimmed = false
        thread { initLocalModel() }
        thread { initStreamingModel() }
    }

    /**
     * Pre-warms DNS/TCP/TLS for whatever cloud host(s) this dictation would actually call (#100
     * perceived-latency follow-up), the same "pay the cost while the user is still talking"
     * timing [warmUpLocalCleanupModelIfNeeded]/[warmUpTranscribersIfTrimmed] already use. Reads
     * the same [ProviderChainStore]/[CloudFeatureToggle] state the real transcription and cleanup
     * call sites resolve against, so this only opens connections a real call could actually use.
     */
    private fun warmUpCloudConnectionsIfNeeded() {
        val chain = ProviderChainStore.load(this)
        val transcriptionCandidates = ProviderChainRuntime.transcriptionCandidates(chain)
        val cleanupChain = ProviderChainRuntime.effectiveChainForCleanup(chain, CloudFeatureToggle.cleanupEnabled(this))
        val hosts = NetworkWarmup.hostsToWarm(transcriptionCandidates, cleanupChain)
        NetworkWarmup.warmUpAsync(hosts)
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
                // No valid token from this thread's view — but that view can be stale (#66): the
                // stop tap mints the token on the main thread *after* the RECORDING->TRANSCRIBING
                // CAS this reader thread reacted to, so deciding to discard here could silently
                // drop a real dictation. Resolve on the main thread instead, where token/state
                // are authoritative — mirroring startMaxDurationTranscription's pattern.
                handler.post { resolveLateRecordingOnMain(result) }
                return
            }
        }
        continueTranscription(result, token)
    }

    /** Main-thread resolution for a recording that finished without a token (#66/#90) — see
     *  [resolveLateRecording] for the decision table. */
    private fun resolveLateRecordingOnMain(result: RecordingEngine.Result) {
        val token = activeToken
        when (resolveLateRecording(token, guard.isCurrent(token), stateMachine.current())) {
            LateRecordingResolution.CONTINUE_TRANSCRIPTION -> thread { continueTranscription(result, token) }
            LateRecordingResolution.DISCARD -> result.pcmFile?.delete()
            LateRecordingResolution.DISCARD_AND_RESET -> {
                // Mic error mid-recording (#90): the reader thread self-claimed TRANSCRIBING but
                // no transcription will run and no watchdog was armed — without this reset the
                // overlay stays stuck in TRANSCRIBING with taps as no-ops forever.
                result.pcmFile?.delete()
                reset(result.errorMessage?.let { "Recording error: $it" } ?: "Recording stopped unexpectedly")
            }
        }
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
        val allowCloudFallback = DictationModeToggle.allowCloudFallback(this)

        when {
            useLocal && transcriberSlot.get() != null -> transcribeLocal(file, token, allowCloudFallback)
            // #98 UX follow-up: previously this fell straight through to transcribeApi() whenever
            // the local model wasn't loaded yet, regardless of *why* -- including the real-world
            // case of a fresh install where onboarding's model download is still in progress.
            // Since a local-mode user typically never entered a cloud API key, that produced a
            // confusing "Set API key in Ramblr app" error with no connection to the actual cause,
            // making the whole dictation feature look silently broken during exactly the moment
            // (onboarding's "Try it out" step) a new user is forming their first impression.
            // Give an honest, specific message instead of misrouting to a path that was never
            // configured -- unless #100's "fall back to cloud if on-device fails" toggle is on,
            // in which case a not-yet-downloaded local model is exactly the case that toggle
            // exists for.
            useLocal && allowCloudFallback -> transcribeApi(file, token)
            useLocal -> {
                file.delete()
                reset("Local model still downloading -- try again once it finishes")
            }
            else -> transcribeApi(file, token)
        }
    }

    /**
     * [file] is read straight off disk into a bounded-size FloatArray (#16's duration cap bounds
     * a recording to ~19MB PCM / ~38MB of floats) rather than staging through an intermediate
     * ByteArray copy of the whole file first. sherpa-onnx's `OfflineStream.acceptWaveform` only
     * accepts one full FloatArray per stream — there's no incremental/chunked accept in the
     * vendored bindings — so this remains the single largest allocation on this path.
     */
    private fun transcribeLocal(file: File, token: Int, allowCloudFallback: Boolean) {
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
                // #100: local transcription's audio file is already gone (read-then-delete
                // above), so a cloud fallback here needs the raw PCM again -- but re-reading a
                // deleted file isn't possible. Since local transcription failure this late
                // (post-decode) is rare and re-recording is cheap, cloud fallback for
                // transcription is only wired at the "model not loaded yet" branch in
                // continueTranscription, not this in-flight-failure path; report the error
                // honestly instead of silently retrying without audio.
                handler.post {
                    if (!guard.isCurrent(token)) return@post // cancelled or watchdog already reset the UI
                    toast("Local error: ${e.message}")
                    resetToIdle()
                }
            }
        }
    }

    private fun transcribeApi(file: File, token: Int) {
        val chain = ProviderChainStore.load(this)
        val allowLocalFallback = DictationModeToggle.allowLocalFallback(this)
        val candidates = ProviderChainRuntime.transcriptionCandidates(chain, allowLocalFallback)
        if (candidates.isEmpty()) {
            file.delete()
            reset("No transcription provider configured")
            return
        }

        fun attempt(index: Int) {
            if (index >= candidates.size) {
                file.delete()
                reset("Set API key in Ramblr app")
                return
            }
            val entry = candidates[index]
            when (entry.kind) {
                ProviderKind.OPENAI -> {
                    val apiKey = ProviderCredentialStore.get(this, ProviderKind.OPENAI)
                    if (apiKey.isBlank()) {
                        Log.w(TAG, "Skipping OpenAI transcription provider: no ProviderCredentialStore.OPENAI credential configured")
                        attempt(index + 1)
                        return
                    }
                    Log.i(TAG, "Cloud transcription via ProviderChain provider=${entry.kind} (OpenAI audio/transcriptions)")
                    val transcribeStartMs = System.currentTimeMillis()
                    TranscriberClient.transcribe(file, apiKey, inFlightCall) { result ->
                        Log.i(TAG, "OpenAI transcription HTTP round-trip took ${System.currentTimeMillis() - transcribeStartMs}ms")
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
                ProviderKind.LOCAL -> {
                    if (transcriberSlot.get() != null) {
                        Log.i(TAG, "Transcription via ProviderChain provider=${entry.kind}")
                        transcribeLocal(file, token, allowCloudFallback = false)
                    } else {
                        file.delete()
                        reset("Local model still downloading -- try again once it finishes")
                    }
                }
                ProviderKind.GEMINI -> {
                    val apiKey = ProviderCredentialStore.get(this, ProviderKind.GEMINI)
                    if (apiKey.isBlank()) {
                        Log.w(TAG, "Skipping Gemini transcription provider: no ProviderCredentialStore.GEMINI credential configured")
                        attempt(index + 1)
                        return
                    }
                    Log.i(TAG, "Cloud transcription via ProviderChain provider=${entry.kind} (Gemini generateContent audio)")
                    GeminiTranscriberClient.transcribe(file, apiKey, entry.model.ifBlank { GeminiTranscriberClient.DEFAULT_MODEL }, inFlightCall) { result ->
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
                ProviderKind.ANTHROPIC, ProviderKind.OMNIROUTE -> attempt(index + 1) // filtered out by capability; defensive only
            }
        }

        attempt(0)
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

        if (usePostProcessing) {
            // Phase 3 (#95): "Use cloud for Cleanup" toggle on the new unified Cloud screen --
            // applied here as a pure filter ahead of cleanup resolution so this call site is the
            // only place that needs to know about it; everything downstream (cleanupWaterfallFor,
            // shouldUseCleanupExecutor, processProviderChain) keeps operating on a plain
            // ProviderChain exactly as Phase 2 verified.
            val providerChain = ProviderChainRuntime.effectiveChainForCleanup(
                ProviderChainStore.load(this), CloudFeatureToggle.cleanupEnabled(this), DictationModeToggle.allowLocalFallback(this)
            )
            val cleanupWaterfall = ProviderChainRuntime.cleanupWaterfallFor(providerChain)

            // A zero-step provider chain means the user explicitly removed every executable cleanup
            // step: cleanup is disabled, so inject raw instead of falling back to any legacy store.
            if (cleanupWaterfall.steps.isEmpty()) {
                handler.post {
                    if (!guard.isCurrent(token)) return@post
                    injectText(text)
                    resetToIdle()
                }
                return
            }

            // The simple migrated single-OpenAI chain keeps the old LEGACY_SINGLE_STEP behavior:
            // fail before making a network call if the one required credential is missing. Real
            // multi-step chains resolve credentials inside CleanupWaterfallExecutor and can fall
            // through past an unconfigured cloud step to another provider (including LOCAL).
            if (!ProviderChainRuntime.shouldUseCleanupExecutor(providerChain) &&
                ProviderCredentialStore.get(this, ProviderKind.OPENAI).isBlank()) {
                handler.post {
                    if (!guard.isCurrent(token)) return@post
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    resetToIdle()
                }
                return
            }

            val savedPrompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            val perAppPersonaKey = if (PerAppPersonaToggle.isEnabled(this)) {
                PerAppPersonaStore.personaKeyFor(this, currentForegroundPackageName())
            } else {
                null
            }
            val rawPrompt = perAppPersonaKey
                ?.let { CleanupPersonas.promptForExplicitSelection(PersonaRegistry.resolve(this, it)) }
                ?: savedPrompt
            val vocabulary = VocabularyTerms.parse(prefs().getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))
            val prompt = PostProcessor.interpolateVocabulary(rawPrompt, vocabulary)

            if (!guard.isCurrent(token)) return
            Log.i(TAG, "Cleanup via ProviderChain entries=${providerChain.entries.map { it.kind }} executableSteps=${cleanupWaterfall.steps.map { it.group }}")
            PostProcessor.processProviderChain(
                text = text,
                prompt = prompt,
                chain = providerChain,
                cursor = cleanupCursor,
                cancelHolder = inFlightCall,
                credentialLookup = { kind -> ProviderCredentialStore.get(this, kind) },
                localModelPath = { ModelDownloader.localCleanupModelFile(this, LocalCleanupProvider.selectedModel(this))?.absolutePath },
                localPrompt = LocalCleanupProvider.selectedSystemPrompt(this),
            ) { result ->
                handler.post {
                    if (!guard.isCurrent(token)) return@post // cancelled or watchdog already reset the UI
                    if (result.text != null && result.text.isNotBlank()) {
                        val servingGroup = recordProviderChainCleanupSuccess(cleanupWaterfall)
                        val paidFallbackGroup = servingGroup?.takeIf { it.isPaidFallback() }
                        if (PreviewBeforeInjectToggle.isEnabled(this)) {
                            beginPreview(rawText = text, candidateText = result.text, paidFallbackGroup = paidFallbackGroup)
                        } else {
                            injectText(result.text, rawText = text, paidFallbackGroup = paidFallbackGroup)
                        }
                    } else {
                        // Log + surface the real failure reason (bad/missing key, HTTP status,
                        // network error, etc.) instead of a generic "cleanup failed" that gives
                        // the user and any future debugging nothing to go on (#98, Trevor hit
                        // this directly: OpenAI key rejected/failed with zero visible reason).
                        // result.error already carries this from PostProcessor.Result/
                        // CleanupStepOutcome -- it was just being discarded here.
                        val reason = result.error?.takeIf { it.isNotBlank() } ?: "unknown error"
                        Log.w(TAG, "Cleanup failed, injecting raw text: $reason")
                        injectText(text, feedback = "Cleanup failed ($reason) — raw copied to clipboard", feedbackDurationMs = 4000)
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

    /** Marks whichever provider-chain cleanup step just served this cleanup call as healthy (#32), so the
     *  Settings status dot reflects real usage, not just a Test-button press, and returns that
     *  step's group so callers can attribute the result for dictation history's "paid fallback"
     *  badge (#33). Which step that was isn't threaded through [PostProcessor.processProviderChain]'s
     *  callback, so it's inferred from [cleanupCursor]'s last-known-good index instead -- safe to
     *  read immediately after a success since [CleanupWaterfallCursor.recordSuccess] was just
     *  called with "now", well inside the idle-expiry window [CleanupWaterfallCursor.startIndex]
     *  checks. A total-waterfall failure is deliberately not attributed to any one step here,
     *  since several steps may have failed for different reasons -- the Settings "Test" button is
     *  the deterministic way to pin down which. */
    private fun recordProviderChainCleanupSuccess(executableWaterfall: CleanupWaterfall): CleanupStepGroup? {
        val succeededIndex = cleanupCursor.startIndex(System.currentTimeMillis())
        val step = executableWaterfall.steps.getOrNull(succeededIndex) ?: return null
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

        // #73: record history the moment a cleanup candidate exists, not only once injection
        // happens up to PREVIEW_TIMEOUT_MS later -- closes the process-death/service-teardown
        // window where a pending preview was lost with no trace at all. resolvePreview below
        // updates this exact row in place instead of adding a duplicate once the preview resolves.
        val historyTimestamp = recordHistory(rawText, cleanedText = candidateText, paidFallbackGroup = paidFallbackGroup)
        pendingPreview = CleanupPreviewState(rawText, candidateText, paidFallbackGroup, historyTimestamp)
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
            injectText(
                resolution.textToInject,
                rawText = preview.rawText,
                paidFallbackGroup = preview.paidFallbackGroup,
                existingHistoryTimestamp = preview.historyTimestamp,
            )
        } else {
            // Discard/timeout falls back to raw text (see CleanupPreviewState) -- update the
            // already-recorded row to reflect that outcome (no cleanedText survives the discard)
            // rather than leaving the stale candidate text sitting in history (#73).
            injectText(
                resolution.textToInject,
                feedback = "Cleanup skipped — raw copied to clipboard",
                existingHistoryTimestamp = preview.historyTimestamp,
            )
        }
    }

    /** Safe to call from any thread: [resetToIdle] mutates main-thread-only state
     *  (streamingSession/pendingStreamingHandoff, node recycling, the guard), and this is the
     *  one entry point reachable from the RecordingEngine reader thread (fast tap-tap with zero
     *  bytes captured, or missing API key) -- so it hops (#72). */
    private fun reset(msg: String) {
        toast(msg)
        handler.post { resetToIdle() }
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

        // Bug fix (live-preview + preview-before-insert interaction): when Preview-before-insert
        // is on, the real field must never be touched until the user explicitly commits, so route
        // the live partial into the floating feedback bubble instead of the field. When it's off,
        // fall through to the existing direct-field-write behavior completely unchanged.
        if (shouldRouteStreamingPartialToBubble(PreviewBeforeInjectToggle.isEnabled(this))) {
            updateLivePreviewBubble(text, now)
            return
        }

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

    /**
     * Mirrors the live streaming partial into the feedback bubble instead of the real field
     * (bug fix, live-preview + preview-before-insert interaction) -- reuses [showFeedback] with a
     * long-ish duration (comfortably longer than the throttle interval) so the bubble doesn't
     * flicker to hidden between two rapid partials. This is purely a "watch it type" display: it
     * never touches [pendingPreview] or the [PREVIEW_TIMEOUT_MS] commit/discard clock, which only
     * start once recording stops and cleanup actually produces a candidate (see [beginPreview]).
     * Not touchable, since there's nothing to commit yet at this stage.
     */
    private fun updateLivePreviewBubble(text: String, nowMs: Long) {
        if (!shouldInjectPartial(text, lastBubblePartialText, lastBubblePartialAtMs, nowMs, STREAMING_PARTIAL_MIN_INTERVAL_MS)) return
        lastBubblePartialText = text
        lastBubblePartialAtMs = nowMs
        val displayText = smartCapitalize(text)
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

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) nudgeSelectionToEnd(node, text.length)
        return ok
    }

    /**
     * Defensive, app-agnostic nudge after a successful ACTION_SET_TEXT (#quirk-compat): the
     * platform contract already says ACTION_SET_TEXT places the cursor at the end and the widget
     * "should" fire TYPE_VIEW_TEXT_CHANGED, so on a spec-compliant EditText this is a no-op.
     * Observed on-device with Google Keep though: a large bulk ACTION_SET_TEXT (as opposed to
     * incremental per-keystroke input, which is what Keep's own rendering path is normally
     * exercised by) can land text that's present in the node's reported .text and still
     * selectable/deletable, but never gets repainted -- independently corroborated by user reports
     * of Keep's own editor going invisible mid-typing, unrelated to any accessibility service. This
     * is *not* Keep-specific code: it's the same explicit ACTION_SET_SELECTION call for every node
     * on every app, on the theory that some custom text renderers only recompute/repaint their
     * layout in response to an explicit selection-change action rather than trusting ACTION_SET_TEXT
     * alone. Best-effort -- failure here doesn't invalidate the SET_TEXT that already succeeded.
     */
    private fun nudgeSelectionToEnd(node: AccessibilityNodeInfo, textLength: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, textLength)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (e: Exception) {
            // Best-effort only; some nodes reject selection args entirely (e.g. non-text views
            // that happened to report isEditable=true). Never let this affect injection success.
        }
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

    private fun injectText(
        text: String,
        rawText: String? = null,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000,
        paidFallbackGroup: CleanupStepGroup? = null,
        existingHistoryTimestamp: Long? = null,
    ) {
        pendingClipboardRestore?.let { handler.removeCallbacks(it) }
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
                finishInjection(findInjectionCandidates(), text, rawText, priorClipboard, feedback, feedbackDurationMs, streamingHandoff, historyTimestamp)
            }
            pendingInjectionRetry = retry
            handler.postDelayed(retry, INJECTION_RETRY_DELAY_MS)
            return
        }

        finishInjection(candidates, text, rawText, priorClipboard, feedback, feedbackDurationMs, streamingHandoff, historyTimestamp)
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

        updatePendingInjection(method, injectedText = text, rawText = rawText ?: text, priorClipboard, priorNodeText, injectedNode, historyTimestamp)

        val retryRawOffered = method != InjectMethod.NONE && rawText != null && rawText != text
        val isFallback = method == InjectMethod.NONE
        fallbackClipboardText = if (isFallback) text else null

        val duration = when {
            retryRawOffered -> UNDO_RETRY_WINDOW_MS
            isFallback -> FALLBACK_FEEDBACK_DURATION_MS
            else -> feedbackDurationMs
        }
        // retryRawOffered replaces the base feedback text entirely rather than appending a suffix
        // to it (e.g. "Copied to clipboard") -- the cleaned text is already injected at this point,
        // so "Copied to clipboard" is stale/misleading noise; the only actionable thing left to
        // tell the user is that tapping swaps in the raw transcript instead (Trevor's request).
        val displayFeedback = when {
            retryRawOffered -> "Tap to use raw text"
            isFallback -> feedback?.let { "$it · tap to copy again" }
            else -> feedback
        }
        displayFeedback?.let { showFeedback(it, duration, touchable = retryRawOffered || isFallback, isFallback = isFallback) }

        when (val action = clipboardClearActionFor(method, CLIPBOARD_CLEAR_DELAY_MS)) {
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
                // #73: reuse the same history row (via existingHistoryTimestamp) instead of
                // recording a second entry for a dictation that already has one -- "retry with
                // raw text" is an update to what actually ended up injected, not a new dictation.
                injectText(
                    pending.rawText,
                    feedback = "Raw text copied",
                    feedbackDurationMs = 2000,
                    existingHistoryTimestamp = pending.historyTimestamp,
                )
                return
            }
        }
        fallbackClipboardText?.let {
            ClipboardUtil.copy(this, it)
            toast("Copied to clipboard")
        }
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
            val setTextOk = setNodeText(node, updated)
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

    private fun currentForegroundPackageName(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    private fun prefs() = getSharedPreferences("ramblr", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
