package com.trevornk.ramblr

import android.content.Context
import android.content.Intent
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** Voice-only IME shell. It deliberately contains no conventional key rows or typing features. */
class RamblrImeService : InputMethodService() {
    companion object { private const val TAG = "RamblrImeService" }

    private val destination = ImeDestinationGuard()
    private var editorGeneration = 0L
    private var editorIdentity = ImeEditorIdentity(null, 0, 0)
    private var runtime: DictationRuntime? = null
    private var panelController: ImePanelController? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val historyStore by lazy { DictationHistoryStore.forContext(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var editorPolicy = ImeEditorPolicy(allowsDictation = false, allowsRetention = false)

    private var statusView: TextView? = null
    private var partialView: TextView? = null
    private var micButton: ImageButton? = null

    override fun onCreate() {
        super.onCreate()
        CustomPersonaStore.ensureLegacySeeded(this)
        ProviderChainMigration.runIfNeeded(this)
        registerNetworkCallback()
        thread { ProcessRecordingOrphanCleaner.cleanupOnce(cacheDir) }
        thread { ModelDownloader.pruneOrphanedModelDirs(this) }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(resolveColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
            }
        }

        statusView = TextView(this).apply {
            text = getString(R.string.ime_status_idle)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        partialView = TextView(this).apply {
            text = getString(R.string.ime_partial_hint)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            minHeight = dp(40)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        micButton = ImageButton(this).apply {
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

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(actionButton(R.drawable.ic_keyboard_switch, R.string.ime_switch_keyboard) {
            switchIme(
                tryPrevious = { runCatching { switchToPreviousInputMethod() }.getOrDefault(false) },
                showPicker = {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.showInputMethodPicker()
                },
            )
        })
        actions.addView(space(dp(24)))
        actions.addView(actionButton(R.drawable.ic_settings, R.string.ime_open_settings) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })

        root.addView(statusView, LinearLayout.LayoutParams(-1, dp(28)))
        root.addView(partialView, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(micButton, micParams)
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(52)))
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
        if (editorPolicy.allowsDictation) {
            destination.editorChanged(editorGeneration, editorIdentity, currentInputConnection)
            panelController?.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
            if (!restarting) renderState(ImeUiState.IDLE)
        } else {
            loseLifecycle(ImeLifecycleLoss.INPUT_FINISHED)
            destination.invalidate()
            renderPartial("")
            renderState(ImeUiState.SECURE_FIELD)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorIdentity = identityOf(info)
        editorPolicy = imeEditorPolicy(editorIdentity.inputType, editorIdentity.imeOptions)
        if (editorPolicy.allowsDictation) {
            destination.editorChanged(editorGeneration, editorIdentity, currentInputConnection)
            ensureRuntime()
            panelController?.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
        } else {
            loseLifecycle(ImeLifecycleLoss.INPUT_FINISHED)
            destination.invalidate()
            renderPartial("")
            renderState(ImeUiState.SECURE_FIELD)
        }
    }

    override fun onFinishInput() {
        loseLifecycle(ImeLifecycleLoss.INPUT_FINISHED)
        editorGeneration++
        editorIdentity = ImeEditorIdentity(null, 0, 0)
        editorPolicy = ImeEditorPolicy(allowsDictation = false, allowsRetention = false)
        destination.invalidate()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        loseLifecycle(ImeLifecycleLoss.HIDDEN)
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (editorPolicy.allowsDictation) ensureRuntime()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

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
        val runtimeControl = object : ImeRuntimeControl {
            override fun onTap() = createdRuntime.onTap()
            override fun invalidate() = createdRuntime.beginShutdown()
            override fun teardownAsync() = ProcessImeNativeRuntimeTasks.enqueueTeardown {
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
        )
        createdRuntime = DictationRuntime(this, controller.listener)
        panelController = controller
        runtime = createdRuntime
        controller.onEditorChanged(editorGeneration, editorIdentity, currentInputConnection)
        ProcessImeNativeRuntimeTasks.enqueueInitialization(
            local = createdRuntime::initLocalModel,
            streaming = createdRuntime::initStreamingModel,
        )
    }

    private fun loseLifecycle(reason: ImeLifecycleLoss) {
        val controller = panelController ?: return
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

    private fun renderState(state: ImeUiState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            statusView?.post { renderState(state) }
            return
        }
        statusView?.text = getString(when (state) {
            ImeUiState.IDLE -> R.string.ime_status_idle
            ImeUiState.RECORDING -> R.string.ime_status_recording
            ImeUiState.TRANSCRIBING -> R.string.ime_status_transcribing
            ImeUiState.CLEANING -> R.string.ime_status_cleaning
            ImeUiState.ERROR -> R.string.ime_status_error
            ImeUiState.SECURE_FIELD -> R.string.ime_status_secure_field
        })
        micButton?.contentDescription = getString(
            if (state == ImeUiState.RECORDING) R.string.ime_mic_stop else R.string.ime_mic_start
        )
        micButton?.background = ovalBackground(
            if (state == ImeUiState.RECORDING) Color.rgb(190, 45, 45)
            else resolveColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(33, 96, 180))
        )
        micButton?.isEnabled = state != ImeUiState.TRANSCRIBING &&
            state != ImeUiState.CLEANING && state != ImeUiState.SECURE_FIELD
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

    private fun actionButton(icon: Int, description: Int, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(description)
            setColorFilter(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
            background = ovalBackground(Color.TRANSPARENT)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun space(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(width, 1) }

    private fun ovalBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
