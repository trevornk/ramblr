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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.view.ContextThemeWrapper
import kotlin.concurrent.thread

/** Voice-only IME shell. It deliberately contains no conventional key rows or typing features. */
class RamblrImeService : InputMethodService() {
    companion object {
        private const val TAG = "RamblrImeService"
        private const val PICKER_HOST_TAG = "ime_style_picker_host"
        private val STYLE_PREF_KEYS = setOf(
            PostProcessingToggle.KEY, "cleanup_style", "per_app_cleanup_persona",
            PerAppPersonaToggle.KEY, "quick_menu_persona_keys", "custom_cleanup_personas",
        )
    }

    private val destination = ImeDestinationGuard()
    private var editorGeneration = 0L
    private var editorIdentity = ImeEditorIdentity(null, 0, 0)
    private var runtime: DictationRuntime? = null
    private var panelController: ImePanelController? = null
    private var modelReadyReload: ImeModelReadyReload? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val historyStore by lazy { DictationHistoryStore.forContext(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    /** #312: keeps the style chip honest when the style / cleanup state is changed elsewhere (the
     *  ring's menu, Settings) while this keyboard is on screen. Held in a field because
     *  SharedPreferences only keeps a weak reference to its listeners. */
    private val stylePrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in STYLE_PREF_KEYS) refreshPanelChrome()
    }
    private var editorPolicy = ImeEditorPolicy(allowsDictation = false, allowsRetention = false)
    /** #284: the bound editor is a secure (password/PIN) one. Distinct from `!allowsDictation`,
     *  which is also true between editors; only this may veto repainting an enabled mic. */
    private var secureEditorBound = false

    private var statusView: TextView? = null
    private var partialView: TextView? = null
    private var micButton: ImageButton? = null
    private var lastRenderedState: ImeUiState? = null
    // #312: cleanup-style picker + quick edit keys.
    private var mainPanel: LinearLayout? = null
    private var pickerView: ImeStylePickerView? = null
    private var styleChip: TextView? = null
    private var backspaceKey: View? = null
    private var spaceKey: View? = null
    private var enterKey: ImageButton? = null
    private val backspaceRepeater = KeyRepeater(
        post = { r, delay -> mainHandler.postDelayed(r, delay) },
        cancel = { r -> mainHandler.removeCallbacks(r) },
    ) { performEditKey(ImeEditKey.BACKSPACE) }
    /** Theme-wrapped context the current input view was inflated with; see [panelContext]. */
    private var inflationContext: Context? = null

    /**
     * A [InputMethodService] is a Service, and a Service's theme is the bare platform default --
     * always light, and without any of the Material3 attributes this panel paints itself with. So
     * every colour lookup here silently fell through to its light fallback and the keyboard stayed
     * white in dark mode while the rest of the app followed the system. Wrapping the app's DayNight
     * theme around the inflation context is what makes colorSurface and friends both exist and
     * track night mode. It is rebuilt per input view so a day/night flip picks up fresh resources.
     */
    private fun panelContext(): Context =
        inflationContext ?: ContextThemeWrapper(this, R.style.Theme_PhoneWhisper).also { inflationContext = it }

    @VisibleForTesting
    internal fun panelControllerForTest(): ImePanelController? = panelController

    @VisibleForTesting
    internal fun editorPolicyForTest(): ImeEditorPolicy = editorPolicy

    @VisibleForTesting
    internal fun lastRenderedStateForTest(): ImeUiState? = lastRenderedState

    override fun onCreate() {
        super.onCreate()
        CustomPersonaStore.ensureLegacySeeded(this)
        ProviderChainMigration.runIfNeeded(this)
        ProviderAccountMigration.runIfNeeded(this)
        registerNetworkCallback()
        getSharedPreferences("ramblr", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(stylePrefsListener)
        thread { ProcessRecordingOrphanCleaner.cleanupOnce(cacheDir) }
        thread { ModelDownloader.pruneOrphanedModelDirs(this) }
    }

    override fun onCreateInputView(): View {
        // Discard any previous wrapper: on a uiMode change Android recreates the input view, and a
        // cached wrapper would still hold the old night-mode resources.
        inflationContext = null
        val context = panelContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(resolveColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
            }
        }
        // The panel is drawn edge to edge (targetSdk 35+), so the system's hide-keyboard and
        // switch-IME buttons (the IME navigation bar, a sibling view in this same window) sit ON TOP
        // of the bottom of the panel. The key row spans the full width, so without clearance
        // Backspace/Enter end up under those buttons. Clearance is the larger of the nav-bar inset
        // and that bar's measured height (its inset is only the thin gesture handle). Recomputed,
        // never accumulated.
        val basePadBottom = dp(10)
        var navInset = 0
        fun applyBottomClearance(v: View) {
            val want = basePadBottom + maxOf(navInset, imeNavigationBarHeight(v))
            if (v.paddingBottom != want) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, want)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            navInset = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            applyBottomClearance(v)
            insets
        }
        // The IME nav bar is attached/laid out after our insets arrive; re-check once it has size.
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.post { applyBottomClearance(v) } }
        // The dictation controls live in [main] so the style picker can swap in over them inside
        // the same window (see ImeStylePickerView) without the keyboard changing height.
        val main = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        mainPanel = main

        statusView = TextView(context).apply {
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
            background = ovalBackground(resolveColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(33, 96, 180)))
            setOnClickListener { panelController?.onMicTap() }
        }
        val micParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        }

        val colors = panelColors()
        styleChip = styleChip(context, colors)
        // Mic row: [style chip | mic | balance]. The chip sits beside the mic so the keyboard keeps
        // its original height instead of gaining a row.
        val micRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                FrameLayout(context).apply {
                    addView(styleChip, FrameLayout.LayoutParams(-2, dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(14) })
                },
                LinearLayout.LayoutParams(0, -1, 1f),
            )
            addView(micButton, micParams)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }

        // Bottom row: [switch keyboard | backspace | space | enter | settings].
        val keyWidth = dp(56)
        backspaceKey = editKey(context, colors, R.drawable.ic_backspace, R.string.ime_key_backspace).also {
            ImeKeyStyle.makeHoldable(it, backspaceRepeater) { performEditKey(ImeEditKey.BACKSPACE) }
        }
        spaceKey = TextView(context).apply {
            text = getString(R.string.ime_key_space)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(colors.onSurfaceVariant)
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            background = ImeKeyStyle.keyBackground(context, colors)
            contentDescription = getString(R.string.ime_key_space)
            setOnClickListener { performEditKey(ImeEditKey.SPACE) }
        }
        enterKey = editKey(context, colors, R.drawable.ic_keyboard_return, R.string.ime_key_enter_generic).also {
            it.setOnClickListener { performEditKey(ImeEditKey.ENTER) }
        }
        val actions = MaxWidthLinearLayout(context, dp(560)).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(actionButton(R.drawable.ic_keyboard_switch, R.string.ime_switch_keyboard) {
                switchIme(
                    tryPrevious = { runCatching { switchToPreviousInputMethod() }.getOrDefault(false) },
                    showPicker = {
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                            ?.showInputMethodPicker()
                    },
                )
            })
            addView(space(dp(8)))
            addView(backspaceKey, LinearLayout.LayoutParams(keyWidth, dp(48)))
            addView(space(dp(6)))
            addView(spaceKey, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(space(dp(6)))
            addView(enterKey, LinearLayout.LayoutParams(keyWidth, dp(48)))
            addView(space(dp(8)))
            addView(actionButton(R.drawable.ic_settings, R.string.ime_open_settings) {
                startActivity(Intent(this@RamblrImeService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }

        main.addView(statusView, LinearLayout.LayoutParams(-1, dp(28)))
        main.addView(partialView, LinearLayout.LayoutParams(-1, dp(44)))
        main.addView(micRow, LinearLayout.LayoutParams(-1, -2))
        main.addView(actions, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(main, LinearLayout.LayoutParams(-1, -2))
        pickerView = ImeStylePickerView(
            context, colors,
            onClose = ::closeStylePicker,
            onToggleCleanup = ::onPickerToggleCleanup,
            onPick = ::onPickerPersona,
        ).apply { visibility = View.GONE }
        root.addView(
            MaxWidthLinearLayout(context, dp(560)).apply {
                orientation = LinearLayout.VERTICAL
                addView(pickerView, LinearLayout.LayoutParams(-1, -1))
                visibility = View.GONE
                tag = PICKER_HOST_TAG
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        if (editorPolicy.allowsDictation) {
            ensureRuntime()
            renderState(ImeUiState.IDLE)
        } else {
            renderState(ImeUiState.SECURE_FIELD)
        }
        refreshPanelChrome()
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
        refreshPanelChrome()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorIdentity = identityOf(info)
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
        refreshPanelChrome()
    }

    override fun onFinishInput() {
        backspaceRepeater.release()
        closeStylePicker()
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
        backspaceRepeater.release()
        closeStylePicker()
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
        if (wasNight != isNight) {
            inflationContext = null
            // Preserve whatever the panel is currently showing; this is a repaint, not a reset, and
            // must not disturb an in-flight dictation or its bound destination.
            val current = lastRenderedState
            setInputView(onCreateInputView())
            current?.let { renderState(it) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runtime?.onTrimMemory(level)
    }

    override fun onDestroy() {
        getSharedPreferences("ramblr", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(stylePrefsListener)
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
            onCommitSettled = { refreshPanelChrome() },
            backgroundSink = BackgroundResultSink(
                copyToClipboard = { ClipboardUtil.copy(this, it) },
                postNotice = { BackgroundDictationNotifications.postResult(this, it) },
            ),
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
        micButton?.background = ovalBackground(when (imeMicAppearance(state)) {
            ImeMicAppearance.RECORDING -> themedColor(R.color.ime_mic_recording)
            ImeMicAppearance.DISABLED -> themedColor(R.color.ime_mic_disabled)
            ImeMicAppearance.READY ->
                resolveColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(33, 96, 180))
        })
        micButton?.isEnabled = imeMicEnabled(state)
        micButton?.alpha = if (imeMicEnabled(state)) 1f else 0.6f
        refreshPanelChrome()
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

    // ---- #312: cleanup-style picker + quick edit keys ----

    private fun panelColors() = ImePanelColors(
        onSurface = resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY),
        onSurfaceVariant = resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY),
        keyFill = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY),
        accent = resolveColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(33, 96, 180)),
    )

    private fun styleChip(context: Context, colors: ImePanelColors): TextView = TextView(context).apply {
        textSize = 14f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        maxWidth = dp(150)
        setTextColor(colors.onSurface)
        setPadding(dp(14), 0, dp(8), 0)
        compoundDrawablePadding = dp(2)
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_drop_down, 0)
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(colors.onSurface)
        isClickable = true
        isFocusable = true
        background = ImeKeyStyle.keyBackground(context, colors)
        setOnClickListener { openStylePicker() }
    }

    private fun editKey(context: Context, colors: ImePanelColors, icon: Int, description: Int): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = getString(description)
            setColorFilter(colors.onSurface)
            background = ImeKeyStyle.keyBackground(context, colors)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }

    /** Main-thread only. Live state for gating: what's painted, corrected by the runtime's truth. */
    private fun gatingState(): ImeUiState {
        val rendered = lastRenderedState ?: ImeUiState.IDLE
        if (rendered == ImeUiState.SECURE_FIELD) return rendered
        return when (runtime?.currentState()) {
            RecordingStateMachine.State.RECORDING -> ImeUiState.RECORDING
            RecordingStateMachine.State.TRANSCRIBING ->
                if (rendered == ImeUiState.CLEANING) rendered else ImeUiState.TRANSCRIBING
            else -> rendered
        }
    }

    private fun commitPending(): Boolean = panelController?.isCommitPending() == true

    private fun enterResolution() = resolveEnterKey(editorIdentity.inputType, editorIdentity.imeOptions)

    private fun editKeyAllowed(key: ImeEditKey): Boolean =
        imeEditKeyEnabled(key, gatingState(), commitPending(), enterResolution())

    /** Runs one edit key against the live InputConnection. Returns false when nothing was done. */
    private fun performEditKey(key: ImeEditKey): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!editKeyAllowed(key)) return false
        val ic = currentInputConnection
        return when (key) {
            ImeEditKey.BACKSPACE -> ImeEditActions.backspace(ic)
            ImeEditKey.SPACE -> ImeEditActions.space(ic)
            ImeEditKey.ENTER -> ImeEditActions.enter(ic, editorIdentity.inputType, editorIdentity.imeOptions)
        }
    }

    private fun enterKeyDescription(): Int = when (val action = enterResolution()) {
        EnterKeyAction.InsertNewline -> R.string.ime_key_enter_newline
        EnterKeyAction.SendEnterKey -> R.string.ime_key_enter_generic
        is EnterKeyAction.PerformEditorAction -> when (action.actionId) {
            EditorInfo.IME_ACTION_GO -> R.string.ime_key_enter_go
            EditorInfo.IME_ACTION_SEARCH -> R.string.ime_key_enter_search
            EditorInfo.IME_ACTION_SEND -> R.string.ime_key_enter_send
            EditorInfo.IME_ACTION_DONE -> R.string.ime_key_enter_done
            EditorInfo.IME_ACTION_NEXT -> R.string.ime_key_enter_next
            EditorInfo.IME_ACTION_PREVIOUS -> R.string.ime_key_enter_previous
            else -> R.string.ime_key_enter_generic
        }
    }

    private fun setKeyEnabled(view: View?, enabled: Boolean) {
        view ?: return
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
        if (!enabled) view.isPressed = false
    }

    /** Re-derives everything that depends on dictation state, the bound editor or the style prefs. */
    private fun refreshPanelChrome() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshPanelChrome() }
            return
        }
        val state = gatingState()
        val pending = commitPending()
        for ((key, view) in listOf(ImeEditKey.BACKSPACE to backspaceKey, ImeEditKey.SPACE to spaceKey, ImeEditKey.ENTER to enterKey)) {
            setKeyEnabled(view, imeEditKeyEnabled(key, state, pending, enterResolution()))
        }
        if (!imeEditKeyEnabled(ImeEditKey.BACKSPACE, state, pending, enterResolution())) backspaceRepeater.release()
        enterKey?.contentDescription = getString(enterKeyDescription())

        val pickerAllowed = imeStylePickerEnabled(state, pending)
        val model = CleanupStyleMenu.load(this, editorIdentity.packageName)
        styleChip?.let { chip ->
            chip.text = if (model.cleanupEnabled) model.current.title else getString(R.string.ime_style_chip_off)
            chip.contentDescription = when {
                !pickerAllowed -> getString(R.string.ime_style_chip_busy_desc, if (model.cleanupEnabled) model.current.title else getString(R.string.ime_style_chip_off))
                model.cleanupEnabled -> getString(R.string.ime_style_chip_desc, model.current.title)
                else -> getString(R.string.ime_style_chip_off_desc)
            }
        }
        setKeyEnabled(styleChip, pickerAllowed)
        if (!pickerAllowed) closeStylePicker()
    }

    private fun openStylePicker() {
        val main = mainPanel ?: return
        val picker = pickerView ?: return
        if (!imeStylePickerEnabled(gatingState(), commitPending())) return
        val host = picker.parent as? View ?: return
        // Pin the picker to the controls' current height so opening it never resizes the keyboard.
        val height = main.height.coerceAtLeast(dp(224))
        picker.bind(CleanupStyleMenu.load(this, editorIdentity.packageName))
        host.layoutParams = (host.layoutParams as LinearLayout.LayoutParams).apply { this.height = height }
        main.visibility = View.GONE
        host.visibility = View.VISIBLE
        picker.visibility = View.VISIBLE
    }

    private fun closeStylePicker() {
        val picker = pickerView ?: return
        (picker.parent as? View)?.visibility = View.GONE
        picker.visibility = View.GONE
        mainPanel?.visibility = View.VISIBLE
    }

    private fun onPickerToggleCleanup() {
        val result = CleanupStyleMenu.toggleCleanup(this)
        closeStylePicker()
        showMessage(getString(when (result) {
            CleanupStyleMenu.ToggleResult.TURNED_ON -> R.string.ime_cleanup_now_on
            CleanupStyleMenu.ToggleResult.TURNED_OFF -> R.string.ime_cleanup_now_off
            CleanupStyleMenu.ToggleResult.NEEDS_SETTINGS -> R.string.ime_cleanup_needs_settings
        }))
        refreshPanelChrome()
    }

    private fun onPickerPersona(persona: CleanupPersona) {
        // Writes the same prefs as the ring menu and Settings; applies to the NEXT dictation (the
        // picker is unavailable while one is in flight -- see imeStylePickerEnabled).
        val selection = CleanupStyleMenu.select(this, editorIdentity.packageName, persona)
        closeStylePicker()
        showMessage(getString(
            if (selection.cleanupEnabled) R.string.ime_style_changed else R.string.ime_style_changed_needs_settings,
            persona.title,
        ))
        refreshPanelChrome()
    }

    private fun actionButton(icon: Int, description: Int, action: () -> Unit): ImageButton =
        ImageButton(panelContext()).apply {
            setImageResource(icon)
            contentDescription = getString(description)
            setColorFilter(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
            background = ovalBackground(Color.TRANSPARENT)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun space(width: Int) =
        View(panelContext()).apply { layoutParams = LinearLayout.LayoutParams(width, 1) }

    private fun ovalBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

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

    /** Height of the IME navigation bar (back / switch-IME buttons) the platform overlays on this
     *  window, or 0 when absent. Looked up by its framework id name so a missing/renamed id on some
     *  OEM degrades to the plain nav-bar inset instead of failing. */
    private fun imeNavigationBarHeight(from: View): Int {
        val id = resources.getIdentifier("input_method_navigation_bar_view", "id", "android")
        if (id == 0) return 0
        val bar = from.rootView?.findViewById<View>(id) ?: return 0
        return if (bar.visibility == View.VISIBLE) bar.height else 0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
