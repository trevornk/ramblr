package com.trevornk.ramblr

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlin.concurrent.thread

/** Voice-only IME shell. It deliberately contains no conventional key rows or typing features. */
class RamblrImeService : InputMethodService() {
    companion object {
        private const val TAG = "RamblrImeService"
        internal const val STATUS_TAG = "ime_status"
        internal const val STYLE_PICKER_TAG = "ime_style_picker"
        internal const val TRANSLATE_PICKER_TAG = "ime_translate_picker"
        internal const val PICKER_LIST_TAG = "ime_picker_list"
        internal const val DELETE_TAG = "ime_delete"
        internal const val CONSENT_TAG = "ime_consent"
        internal const val SETUP_NOTICE_TAG = "ime_setup_notice"
        internal const val MIC_PROGRESS_TAG = "ime_mic_progress"
        internal const val WORDMARK_TAG = "ime_wordmark"
        private const val ORIGINAL_TITLE = "Original"
        private const val MIC_DP = 76
        /** Space between the mic button and the processing ring around it. */
        private const val PROGRESS_GAP_DP = 7
        private const val MIC_AREA_DP = 128
        /** Status (with its top margin), partial text and mic row; an open list scrolls within it. */
        private const val BODY_DP = 10 + 28 + 44 + MIC_AREA_DP
    }

    /** Which dropdown list, if any, is covering the panel body. */
    /** CONSENT is the one-time "send text off-device" question, asked in place of a list. */
    private enum class Picker { STYLE, TRANSLATE, CONSENT }

    private val destination = ImeDestinationGuard()
    private var editorGeneration = 0L
    private var editorIdentity = ImeEditorIdentity(null, 0, 0)
    private var runtime: DictationRuntime? = null
    private var panelController: ImePanelController? = null
    private var modelReadyReload: ImeModelReadyReload? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val historyStore by lazy { DictationHistoryStore.forContext(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var editorPolicy = ImeEditorPolicy(allowsDictation = false, allowsRetention = false)
    /** #284: the bound editor is a secure (password/PIN) one. Distinct from `!allowsDictation`,
     *  which is also true between editors; only this may veto repainting an enabled mic. */
    private var secureEditorBound = false

    private var statusView: TextView? = null
    private var partialView: TextView? = null
    private var micButton: ImageButton? = null
    private var micWave: MicWaveView? = null
    private var micProgress: CircularProgressIndicator? = null
    private var stylePicker: TextView? = null
    private var translatePicker: TextView? = null
    private var pickerList: LinearLayout? = null
    private var pickerListScroll: android.widget.ScrollView? = null
    private var panelBody: View? = null
    private var panelRoot: View? = null
    private var openPicker: Picker? = null
    /** What a "Allow" on the consent question goes on to do. */
    private var onConsent: (() -> Unit)? = null
    private var lastRenderedState: ImeUiState? = null
    /** Theme-wrapped context the current input view was inflated with; see [panelContext]. */
    private var inflationContext: Context? = null
    /** The keyboard theme the current input view was built with, to notice a change in Settings. */
    private var inflatedTheme: KeyboardTheme? = null

    /**
     * A [InputMethodService] is a Service, and a Service's theme is the bare platform default --
     * always light, and without any of the Material3 attributes this panel paints itself with. So
     * every colour lookup here silently fell through to its light fallback and the keyboard stayed
     * white in dark mode while the rest of the app followed the system. Wrapping the app's DayNight
     * theme around the inflation context is what makes colorSurface and friends both exist and
     * track night mode. It is rebuilt per input view so a day/night flip picks up fresh resources.
     * A forced Light/Dark [KeyboardTheme] overrides the night bit on this wrapper only.
     */
    private fun panelContext(): Context =
        inflationContext ?: ContextThemeWrapper(this, R.style.Theme_PhoneWhisper).also { wrapper ->
            KeyboardTheme.current(this).nightOverride()?.let { wrapper.applyOverrideConfiguration(it) }
            inflationContext = wrapper
        }

    @VisibleForTesting
    internal fun panelControllerForTest(): ImePanelController? = panelController

    @VisibleForTesting
    internal fun editorPolicyForTest(): ImeEditorPolicy = editorPolicy

    @VisibleForTesting
    internal fun lastRenderedStateForTest(): ImeUiState? = lastRenderedState

    /** The panel's root view, including one rebuilt in place by [rebuildPanel]. */
    @VisibleForTesting
    internal fun panelRootForTest(): View? = panelRoot

    override fun onCreate() {
        super.onCreate()
        CustomPersonaStore.ensureLegacySeeded(this)
        ProviderChainMigration.runIfNeeded(this)
        ProviderAccountMigration.runIfNeeded(this)
        registerNetworkCallback()
        thread { ProcessRecordingOrphanCleaner.cleanupOnce(cacheDir) }
        thread { ModelDownloader.pruneOrphanedModelDirs(this) }
    }

    override fun onCreateInputView(): View {
        // Discard any previous wrapper: on a uiMode change Android recreates the input view, and a
        // cached wrapper would still hold the old night-mode resources.
        inflationContext = null
        openPicker = null
        inflatedTheme = KeyboardTheme.current(this)
        val context = panelContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
            background = GradientDrawable().apply {
                setColor(resolveColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
            }
        }

        // Style and translation share one row; each opens its list over the panel body.
        stylePicker = pickerButton(STYLE_PICKER_TAG) { togglePicker(Picker.STYLE) }
        translatePicker = pickerButton(TRANSLATE_PICKER_TAG) { togglePicker(Picker.TRANSLATE) }
        val pickerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stylePicker, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
            addView(translatePicker, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        }

        statusView = TextView(context).apply {
            tag = STATUS_TAG
            text = getString(R.string.ime_status_idle)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        partialView = TextView(context).apply {
            text = getString(R.string.ime_partial_hint)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            minHeight = dp(40)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        micButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = getString(R.string.ime_mic_start)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = brandOvalBackground()
            setOnClickListener { panelController?.onMicTap() }
        }
        // Drawn over the button while recording, in place of the mic glyph; not clickable, so taps
        // fall through to the button underneath.
        micWave = MicWaveView(context)
        // Spins around the locked mic while the recording is transcribed and cleaned up, so the
        // wait reads as work in progress rather than a dead button.
        micProgress = CircularProgressIndicator(context).apply {
            tag = MIC_PROGRESS_TAG
            isIndeterminate = true
            indicatorSize = dp(MIC_DP + 2 * PROGRESS_GAP_DP)
            trackThickness = dp(3)
            setIndicatorColor(themedColor(R.color.ime_brand_start), themedColor(R.color.ime_brand_end))
            trackColor = Color.TRANSPARENT
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val micArea = android.widget.FrameLayout(context).apply {
            addView(micProgress, android.widget.FrameLayout.LayoutParams(dp(MIC_DP + 2 * PROGRESS_GAP_DP), dp(MIC_DP + 2 * PROGRESS_GAP_DP), Gravity.CENTER))
            addView(micButton, android.widget.FrameLayout.LayoutParams(dp(MIC_DP), dp(MIC_DP), Gravity.CENTER))
            addView(micWave, android.widget.FrameLayout.LayoutParams(dp(MIC_DP), dp(MIC_DP), Gravity.CENTER))
        }

        // Switch-keyboard and settings sit left of the mic, delete on the right where a keyboard's
        // backspace usually is.
        val micRow = android.widget.FrameLayout(context)
        micRow.addView(micArea, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Tap goes straight back to the previous keyboard; long-press always offers the full list.
        val showKeyboardPicker = {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
            Unit
        }
        micRow.addView(actionButton(R.drawable.ic_keyboard_switch, R.string.ime_switch_keyboard, onLongPress = showKeyboardPicker) {
            switchIme(
                tryPrevious = { runCatching { switchToPreviousInputMethod() }.getOrDefault(false) },
                showPicker = showKeyboardPicker,
            )
        }, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        micRow.addView(actionButton(R.drawable.ic_settings, R.string.ime_open_settings) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(52)
        })
        micRow.addView(deleteButton(), android.widget.FrameLayout.LayoutParams(dp(56), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        // A quiet signature under the mic, so the panel is not mistaken for another voice keyboard.
        micRow.addView(TextView(context).apply {
            tag = WORDMARK_TAG
            text = getString(R.string.app_name)
            textSize = 11f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themedColor(R.color.ime_wordmark))
            alpha = 0.8f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView, LinearLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(10) })
            addView(partialView, LinearLayout.LayoutParams(-1, dp(44)))
            addView(micRow, LinearLayout.LayoutParams(-1, dp(MIC_AREA_DP)))
        }
        panelBody = body
        pickerList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = PICKER_LIST_TAG
            setPadding(0, dp(6), 0, 0)
        }
        pickerListScroll = android.widget.ScrollView(context).apply {
            visibility = View.GONE
            addView(pickerList, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        // Fixed height: an open list scrolls inside the body's space instead of growing the panel.
        val bodyFrame = android.widget.FrameLayout(context).apply {
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(pickerListScroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        }

        root.addView(pickerRow, LinearLayout.LayoutParams(-1, dp(40)))
        root.addView(bodyFrame, LinearLayout.LayoutParams(-1, dp(BODY_DP)))
        // Since targetSdk 35 the keyboard window extends behind the navigation bar, which left the
        // bottom of the panel under the gesture handle or nav buttons: pad by that bar's height.
        val bottomPadding = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding + navBar.bottom)
            insets
        }
        panelRoot = root
        refreshPickers()
        if (editorPolicy.allowsDictation) {
            ensureRuntime()
            renderState(ImeUiState.IDLE)
        } else {
            renderState(ImeUiState.SECURE_FIELD)
        }
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Android may restart or replace an editor without first hiding the IME. Never let the
        // old capture continue merely because the window stayed visible; its destination is gone.
        val oldState = runtime?.currentState()
        if (oldState != null && oldState != RecordingStateMachine.State.IDLE) {
            loseLifecycle(ImeLifecycleLoss.INPUT_FINISHED)
        }
        editorGeneration++
        editorIdentity = identityOf(attribute)
        editorPolicy = imeEditorPolicy(editorIdentity.inputType, editorIdentity.imeOptions)
        secureEditorBound = !editorPolicy.allowsDictation
        // Android restarts input for a new editor without necessarily re-showing the input view, so
        // a secure-field teardown has to be undone here or the panel stays dead (#241).
        val transition = imeEditorTransition(editorPolicy.allowsDictation, panelController != null)
        if (!transition.blockSecure) {
            destination.editorChanged(editorGeneration, editorIdentity, currentInputConnection)
            if (transition.rearmRuntime) ensureRuntime()
            panelController?.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
            if (!restarting || transition.rearmRuntime) {
                renderPartial("")
                // #284: a detached dictation is still transcribing; its own idle callback paints IDLE.
                if (runtime?.currentState() != RecordingStateMachine.State.TRANSCRIBING) renderState(ImeUiState.IDLE)
            }
        } else {
            loseLifecycle(ImeLifecycleLoss.INPUT_FINISHED)
            destination.invalidate()
            // #284: a detached (still-transcribing) controller must learn the new editor is secure.
            panelController?.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
            renderPartial("")
            renderState(ImeUiState.SECURE_FIELD)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorIdentity = identityOf(info)
        if (inflatedTheme != null && inflatedTheme != KeyboardTheme.current(this)) rebuildPanel()
        refreshPickers()
        editorPolicy = imeEditorPolicy(editorIdentity.inputType, editorIdentity.imeOptions)
        secureEditorBound = !editorPolicy.allowsDictation
        if (editorPolicy.allowsDictation) {
            destination.editorChanged(editorGeneration, editorIdentity, currentInputConnection)
            ensureRuntime()
            panelController?.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
        } else {
            loseLifecycle(ImeLifecycleLoss.INPUT_FINISHED)
            destination.invalidate()
            panelController?.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
            renderPartial("")
            renderState(ImeUiState.SECURE_FIELD)
        }
    }

    override fun onFinishInput() {
        loseLifecycle(ImeLifecycleLoss.INPUT_FINISHED)
        holdProcessForDetachedTranscription()
        editorGeneration++
        editorIdentity = ImeEditorIdentity(null, 0, 0)
        editorPolicy = ImeEditorPolicy(allowsDictation = false, allowsRetention = false)
        secureEditorBound = false
        destination.invalidate()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        closePicker()
        loseLifecycle(ImeLifecycleLoss.HIDDEN)
        holdProcessForDetachedTranscription()
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (editorPolicy.allowsDictation) ensureRuntime()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Night mode can flip underneath a visible keyboard (the user's schedule crosses sunset while a
     * field is focused). An InputMethodService is not recreated for that, so the cached theme
     * wrapper and the already-painted views would keep the old palette until the panel next
     * reopened. Dropping the wrapper and asking for a fresh input view repaints it in place.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        val wasNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        super.onConfigurationChanged(newConfig)
        val isNight = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        if (wasNight != isNight) rebuildPanel()
    }

    /** Builds a fresh input view in place (night flip, keyboard theme change). Preserves whatever
     *  the panel is currently showing; this is a repaint, not a reset, and must not disturb an
     *  in-flight dictation or its bound destination. */
    private fun rebuildPanel() {
        inflationContext = null
        val current = lastRenderedState
        setInputView(onCreateInputView())
        current?.let { renderState(it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runtime?.onTrimMemory(level)
    }

    override fun onDestroy() {
        loseLifecycle(ImeLifecycleLoss.DESTROYED)
        unregisterNetworkCallback()
        super.onDestroy()
    }

    private fun ensureRuntime() {
        if (!editorPolicy.allowsDictation) return
        if (runtime != null) return
        lateinit var createdRuntime: DictationRuntime
        lateinit var nativeInitialization: ImeNativeRuntimeTaskQueue.Initialization
        val runtimeControl = object : ImeRuntimeControl {
            override fun onTap() = createdRuntime.onTap()
            override fun invalidate() = createdRuntime.beginShutdown()
            override fun teardownAsync() = ProcessImeNativeRuntimeTasks.enqueueTeardown(nativeInitialization) {
                createdRuntime.finishShutdownAndReleaseTranscribers()
            }
        }
        val controller = ImePanelController(
            runtime = runtimeControl,
            renderState = ::renderState,
            destination = destination,
            editorSnapshot = { Triple(editorGeneration, editorIdentity, currentInputConnection) },
            commitText = { connection, text ->
                check(Looper.myLooper() == Looper.getMainLooper()) { "InputConnection commit must run on main" }
                (connection as android.view.inputmethod.InputConnection).commitText(text, 1)
            },
            renderPartial = ::renderPartial,
            userMessage = ::showMessage,
            recordHistory = ::recordImeHistory,
            runHistoryWrite = ImeHistoryWriteExecutor::execute,
            postToMain = { mainHandler.post(it) },
            // #256: IME is a distinct OS registration from the accessibility service and gets its
            // own on-demand exclusion read against the currently-bound editor's package -- no
            // accessibility API involved, no polling.
            isPackageExcluded = { pkg -> PerAppExclusionStore.isExcluded(this, pkg) },
            isRecording = { runtime?.isRecording() ?: false },
            backgroundSink = BackgroundResultSink(
                copyToClipboard = { ClipboardUtil.copy(this, it) },
                postNotice = { BackgroundDictationNotifications.postResult(this, it) },
            ),
            translationLanguage = { KeyboardTranslation.target(this)?.englishName },
            renderLevel = { level -> micWave?.push(level) },
        )
        // #233 Phase 1: the IME is the first (and only) host to reach the merged cloud-live seam.
        // CloudLiveWiring returns null unless the user opted in, chose cloud transcription, and
        // has a Gemini key -- so the default construction is byte-for-byte the previous behavior.
        // Accessibility stays final-only in this phase and is intentionally not wired.
        createdRuntime = DictationRuntime(
            this, controller.listener, cloudLiveFactory = { CloudLiveWiring.factoryOrNull(this) },
            backgroundWork = BackgroundTranscriptionService.work(this),
        )
        panelController = controller
        runtime = createdRuntime
        controller.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
        nativeInitialization = ProcessImeNativeRuntimeTasks.enqueueInitialization(
            local = createdRuntime::initLocalModel,
            streaming = createdRuntime::initStreamingModel,
        )
        val modelReadyReload = ProcessImeNativeRuntimeTasks.newModelReadyReload(
            initialization = nativeInitialization,
            reloadLocal = createdRuntime::initLocalModel,
            reloadStreaming = createdRuntime::initStreamingModel,
        )
        this.modelReadyReload = modelReadyReload
        ProcessActiveImeModelReadyReload.register(modelReadyReload)
    }

    /**
     * #284 lazy hold: the user is leaving the field and a transcription is still in flight, so
     * ask for the foreground-service hold NOW. Starting a foreground service from the background
     * is allowed here because this app is still the device's current input method (ADR-0002); a
     * refused start is swallowed inside the service and the dictation carries on unprotected.
     * Only on these two real leave signals, not on an onStartInput restart of the same field,
     * which is not the user leaving. No-op when nothing is transcribing (including after a
     * teardown, when the runtime reference is already gone).
     */
    private fun holdProcessForDetachedTranscription() {
        runtime?.holdForLeavingHost()
    }

    private fun loseLifecycle(reason: ImeLifecycleLoss) {
        val controller = panelController ?: return
        // #284: a TRANSCRIBING dictation survives the user leaving the field -- the runtime keeps
        // running (the foreground service holds the process), and delivery asks the ticket check
        // where the text may go: the same field if it is still bound, else clipboard + history +
        // notification. Recording is still torn down (the mic must be released), as is DESTROYED.
        val transcribing = runtime?.currentState() == RecordingStateMachine.State.TRANSCRIBING
        // A service torn down mid-transcription loses the result. If the user had already left the
        // field (detached) nothing else will ever tell them; the controller only posts the notice
        // when its destination is gone, so a still-bound field gets nothing new.
        if (reason == ImeLifecycleLoss.DESTROYED && transcribing) {
            runCatching { controller.listener.onDictationFailed(BackgroundFailure.FAILED) }
        }
        if (lifecycleLossActionFor(reason, transcribing) == LifecycleLossAction.DETACH_AND_FINISH) {
            Log.i(TAG, "Keeping in-flight transcription alive across $reason")
            return
        }
        modelReadyReload?.let { modelReadyReload ->
            this.modelReadyReload = null
            ProcessActiveImeModelReadyReload.unregister(modelReadyReload)
        }
        panelController = null
        runtime = null
        controller.onLifecycleLost(reason)
        renderPartial("")
        if (editorPolicy.allowsDictation) renderState(ImeUiState.IDLE)
    }

    private fun identityOf(info: EditorInfo?): ImeEditorIdentity = ImeEditorIdentity(
        packageName = info?.packageName,
        fieldId = info?.fieldId ?: 0,
        inputType = info?.inputType ?: 0,
        imeOptions = info?.imeOptions ?: 0,
        privateImeOptions = info?.privateImeOptions,
    )

    private fun renderState(requested: ImeUiState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            statusView?.post { renderState(requested) }
            return
        }
        // #284: a detached dictation finishing after the user moved into a secure editor must not
        // repaint an enabled mic over the secure-field lock.
        val state = if (secureEditorBound && requested != ImeUiState.SECURE_FIELD) ImeUiState.SECURE_FIELD else requested
        lastRenderedState = state
        statusView?.text = getString(when (state) {
            ImeUiState.IDLE -> R.string.ime_status_idle
            ImeUiState.RECORDING -> R.string.ime_status_recording
            ImeUiState.TRANSCRIBING -> R.string.ime_status_transcribing
            ImeUiState.CLEANING -> R.string.ime_status_cleaning
            ImeUiState.ERROR -> R.string.ime_status_error
            ImeUiState.SECURE_FIELD -> R.string.ime_status_secure_field
        })
        micButton?.contentDescription = getString(when (state) {
            ImeUiState.RECORDING -> R.string.ime_mic_stop
            ImeUiState.SECURE_FIELD -> R.string.ime_status_secure_field
            else -> R.string.ime_mic_start
        })
        // A custom oval drawable ignores isEnabled, so a blocked mic must be painted explicitly or
        // it still reads as tappable (#240).
        micButton?.background = when (imeMicAppearance(state)) {
            ImeMicAppearance.RECORDING -> ovalBackground(themedColor(R.color.ime_mic_recording))
            ImeMicAppearance.DISABLED -> ovalBackground(themedColor(R.color.ime_mic_disabled))
            ImeMicAppearance.READY -> brandOvalBackground()
        }
        micButton?.isEnabled = imeMicEnabled(state)
        micButton?.alpha = if (imeMicEnabled(state)) 1f else 0.6f
        val recording = state == ImeUiState.RECORDING
        micButton?.setImageResource(if (recording) 0 else R.drawable.ic_mic)
        micWave?.setActive(recording)
        micProgress?.visibility = if (imeIsProcessing(state)) View.VISIBLE else View.GONE
    }

    /** Repaints both picker buttons, and the open list if any, from the current prefs. */
    private fun refreshPickers() {
        val style = stylePicker ?: return
        val translate = translatePicker ?: return
        val pkg = editorIdentity.packageName
        val styleKey = KeyboardStyle.selectedKey(this, pkg)
        val styleTitle = KeyboardStyle.options(this, pkg).firstOrNull { it.key == styleKey }?.title
            ?: KeyboardStyle.AS_IS_TITLE
        paintPicker(style, R.drawable.ic_settings_auto_awesome, styleTitle, active = false, open = openPicker == Picker.STYLE)
        style.contentDescription = "Style: $styleTitle"

        // Tinted while translating, so a target left on from earlier can't go unnoticed.
        val target = KeyboardTranslation.target(this)
        paintPicker(translate, R.drawable.ic_ime_translate, target?.nativeName ?: ORIGINAL_TITLE,
            active = target != null, open = openPicker == Picker.TRANSLATE)
        translate.contentDescription = "Translate: ${target?.englishName ?: ORIGINAL_TITLE}"

        renderPickerList()
    }

    private fun togglePicker(picker: Picker) {
        openPicker = if (openPicker == picker) null else picker
        refreshPickers()
    }

    private fun closePicker() {
        if (openPicker == null) return
        openPicker = null
        refreshPickers()
    }

    /** Fills the list for [openPicker] and swaps it in over the panel body, or hides it. */
    private fun renderPickerList() {
        val list = pickerList ?: return
        val open = openPicker
        pickerListScroll?.visibility = if (open == null) View.GONE else View.VISIBLE
        panelBody?.visibility = if (open == null) View.VISIBLE else View.INVISIBLE
        list.removeAllViews()
        when (open) {
            null -> Unit
            Picker.STYLE -> {
                val pkg = editorIdentity.packageName
                val selected = KeyboardStyle.selectedKey(this, pkg)
                val blocker = KeyboardAiReadiness.styleBlocker(this)
                blocker?.let { list.addView(setupNotice(it, CleanupActivity::class.java)) }
                for (option in KeyboardStyle.options(this, pkg)) {
                    list.addView(listRow(option.title, option.subtitle, option.key == selected) {
                        val result = KeyboardStyle.select(this, editorIdentity.packageName, option.key)
                        if (result == KeyboardStyle.Selection.NEEDS_CONSENT) {
                            askConsent {
                                PostProcessingToggle.setEnabled(this, true)
                                blocker?.let(::showMessage)
                            }
                        } else {
                            closePicker()
                            if (option.key != KeyboardStyle.AS_IS) blocker?.let(::showMessage)
                        }
                    })
                }
                list.addView(listRow("More styles", "Choose which styles show up here, or make your own", selected = false, icon = R.drawable.ic_ime_add) {
                    closePicker()
                    startActivity(Intent(this, StyleManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
            Picker.TRANSLATE -> {
                val target = KeyboardTranslation.target(this)
                list.addView(listRow(ORIGINAL_TITLE, "Keep the language you spoke", target == null) {
                    KeyboardTranslation.setTarget(this, null)
                    closePicker()
                })
                val chosen = KeyboardTranslation.chosen(this)
                val blocker = KeyboardAiReadiness.translationBlocker(this)
                blocker?.let { list.addView(setupNotice(it, CloudProviderActivity::class.java)) }
                for (language in chosen) {
                    val row = listRow(language.nativeName, language.englishName.takeIf { it != language.nativeName }, language == target) {
                        when {
                            blocker != null -> { closePicker(); showMessage(blocker) }
                            KeyboardAiReadiness.translationNeedsConsent(this) -> askConsent {
                                KeyboardTranslation.setTarget(this, language.code)
                            }
                            else -> { KeyboardTranslation.setTarget(this, language.code); closePicker() }
                        }
                    }
                    // Still tappable, so the tap can say why, but plainly not available.
                    if (blocker != null) row.alpha = 0.45f
                    list.addView(row)
                }
                list.addView(listRow(if (chosen.isEmpty()) "Add languages" else "Add or remove languages",
                    "Choose which languages show up here", selected = false, icon = R.drawable.ic_ime_add) {
                    closePicker()
                    startActivity(
                        InvocationActivity.intent(this, InvocationActivity.Section.KEYBOARD)
                            .putExtra(BaseSettingsActivity.EXTRA_HIGHLIGHT_TITLE, "Translation languages")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                })
            }
            Picker.CONSENT -> list.addView(consentQuestion())
        }
    }

    /** Asks the one-time #23 question in the panel itself (a keyboard can't show a dialog), then
     *  runs [then] and closes on "Allow". "Not now" changes nothing. */
    private fun askConsent(then: () -> Unit) {
        onConsent = then
        openPicker = Picker.CONSENT
        refreshPickers()
    }

    private fun consentQuestion(): View {
        val context = panelContext()
        val host = KeyboardAiReadiness.consentHost(this)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = CONSENT_TAG
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(TextView(context).apply {
                text = "Send text to $host?"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
            })
            addView(TextView(context).apply {
                text = "Your speech is recognized on this phone. Styles and translation send the recognized text to $host. Ramblr asks only once."
                textSize = 13f
                setPadding(0, dp(6), 0, dp(12))
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(com.google.android.material.button.MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "Not now"
                    setOnClickListener { onConsent = null; closePicker() }
                })
                addView(com.google.android.material.button.MaterialButton(context).apply {
                    text = "Allow"
                    setOnClickListener {
                        KeyboardAiReadiness.grantConsent(this@RamblrImeService)
                        val then = onConsent
                        onConsent = null
                        closePicker()
                        then?.invoke()
                        refreshPickers()
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            })
        }
    }

    /** A list's first row when its choices can't work yet: the reason, and a tap to fix it. */
    private fun setupNotice(reason: String, screen: Class<*>): View =
        listRow(reason, "Tap to set up", selected = false, icon = R.drawable.ic_settings) {
            closePicker()
            startActivity(Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.apply {
            tag = SETUP_NOTICE_TAG
            // The reason must be readable in full, not cut to one line like a choice's title.
            (this as ViewGroup).getChildAt(1).let { texts ->
                ((texts as ViewGroup).getChildAt(0) as TextView).apply { maxLines = 3; ellipsize = null }
            }
        }

    /** Durable history write; invoked only by [ImeHistoryWriteExecutor]. */
    private fun recordImeHistory(entry: DictationHistoryEntry): Boolean {
        if (!getSharedPreferences("ramblr", MODE_PRIVATE).getBoolean("dictation_history_enabled", true)) return false
        return runCatching {
            historyStore.upsert(entry)
            true
        }.onFailure { Log.e(TAG, "Failed to record IME dictation history", it) }.getOrDefault(false)
    }

    private fun renderPartial(text: String) {
        partialView?.text = text.ifBlank { getString(R.string.ime_partial_hint) }
    }

    private fun showMessage(message: String) {
        statusView?.text = message
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runtime?.onDefaultNetworkChanged() }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }.onFailure { Log.w(TAG, "Could not unregister network callback", it) }
    }

    private fun actionButton(icon: Int, description: Int, onLongPress: (() -> Unit)? = null, action: () -> Unit): ImageButton =
        ImageButton(panelContext()).apply {
            setImageResource(icon)
            contentDescription = getString(description)
            setColorFilter(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
            background = ovalBackground(Color.TRANSPARENT)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { action() }
            onLongPress?.let { longPress -> setOnLongClickListener { longPress(); true } }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    /** Backspace: a tap deletes once, holding repeats by character and then by word. */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // performClick() runs on a tap
    private fun deleteButton(): ImageButton = actionButton(R.drawable.ic_ime_backspace, R.string.ime_delete) {
        currentInputConnection?.let { ImeDeleteKey.deleteOnce(it) }
    }.apply {
        tag = DELETE_TAG
        var repeats = 0
        val repeat = object : Runnable {
            override fun run() {
                val ic = currentInputConnection ?: return
                ImeDeleteKey.deleteOnce(ic, word = ImeDeleteKey.wordsAfter(repeats))
                repeats++
                val gap = if (ImeDeleteKey.wordsAfter(repeats)) ImeDeleteKey.REPEAT_WORD_MS else ImeDeleteKey.REPEAT_CHAR_MS
                mainHandler.postDelayed(this, gap)
            }
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    repeats = 0
                    mainHandler.postDelayed(repeat, ImeDeleteKey.REPEAT_START_MS)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(repeat)
                    // A hold already deleted; only a short tap counts as the click.
                    if (repeats > 0) { view.isPressed = false; return@setOnTouchListener true }
                }
                android.view.MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(repeat)
            }
            false
        }
    }

    /** A dropdown button for the picker row; [paintPicker] fills in its look and label. */
    private fun pickerButton(viewTag: String, onTap: () -> Unit): TextView =
        TextView(panelContext()).apply {
            tag = viewTag
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(6)
            setPadding(dp(12), 0, dp(6), 0)
            isClickable = true
            setOnClickListener { onTap() }
        }

    /** Outlined pill, filled with the secondary container when [active], with a thicker accent
     *  outline while its list is [open]. */
    private fun paintPicker(button: TextView, icon: Int, label: String, active: Boolean, open: Boolean) {
        val content = if (active) resolveColor(com.google.android.material.R.attr.colorOnSecondaryContainer, Color.DKGRAY)
            else resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
        button.text = label
        button.setTextColor(content)
        button.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        // Absolute, not relative: a relative drawable stays unresolved until the view is attached
        // and laid out, so it can be missing on the first frame.
        button.setCompoundDrawables(tintedIcon(icon, content, 18), null, tintedIcon(R.drawable.ic_ime_dropdown, content, 24), null)
        button.isSelected = open
        button.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (active) resolveColor(com.google.android.material.R.attr.colorSecondaryContainer, Color.LTGRAY) else Color.TRANSPARENT)
            when {
                open -> setStroke(dp(2), resolveColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(33, 96, 180)))
                !active -> setStroke(dp(1), resolveColor(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY))
            }
        }
    }

    /** One row of an open picker list: a check (or [icon]) column, then title over subtitle. */
    private fun listRow(title: String, subtitle: String?, selected: Boolean, icon: Int? = null, onTap: () -> Unit): View {
        val context = panelContext()
        val primary = resolveColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(33, 96, 180))
        val muted = resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val ripple = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                background = context.getDrawable(ripple.resourceId)
            }
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(tintedIcon(icon ?: R.drawable.ic_ime_check, if (icon != null) muted else primary, 20))
                visibility = if (selected || icon != null) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (selected) primary else resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
                    if (selected) setTypeface(typeface, Typeface.BOLD)
                })
                subtitle?.let {
                    addView(TextView(context).apply {
                        text = it
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(muted)
                    })
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            isSelected = selected
            isClickable = true
            setOnClickListener { onTap() }
        }
    }

    private fun tintedIcon(icon: Int, color: Int, sizeDp: Int) = panelContext().getDrawable(icon)?.mutate()?.apply {
        setTint(color)
        setBounds(0, 0, dp(sizeDp), dp(sizeDp))
    }

    private fun ovalBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** The ready mic in Ramblr's indigo-to-cyan (see ime_brand_start). */
    private fun brandOvalBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(themedColor(R.color.ime_brand_start), themedColor(R.color.ime_brand_end)),
    ).apply { shape = GradientDrawable.OVAL }

    /**
     * Resolves against the panel's wrapped DayNight theme rather than the Service's bare platform
     * theme. Material3 colour attributes resolve to a colour *reference* rather than a literal, so
     * TypedValue.data is only the raw int for the inline TYPE_INT_COLOR_* case; a reference has to
     * be loaded through the themed resources or the panel paints with a resource id as a colour.
     */
    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val context = panelContext()
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            value.data
        } else {
            runCatching { context.getColor(value.resourceId) }.getOrDefault(fallback)
        }
    }

    /** Night-aware lookup for the literal mic-state colours, which are resources, not theme roles. */
    private fun themedColor(colorRes: Int): Int = panelContext().getColor(colorRes)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
