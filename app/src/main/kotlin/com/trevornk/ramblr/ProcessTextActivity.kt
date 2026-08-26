package com.trevornk.ramblr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * "Type to fix" entry point (#157, Option B): the user selects text in any app, taps **Ramblr**
 * in Android's text-selection menu, picks a style, and gets the cleaned text back in place.
 *
 * This is deliberately a plain, self-contained Activity with **no accessibility involvement at
 * all**: [WhisperAccessibilityService] is not modified, not consulted, and does not even have to
 * be enabled for this to work. `onAccessibilityEvent` stays empty, which is the load-bearing
 * sentence of Ramblr's privacy posture (`PRIVACY.md`) — #157 rejects the inline-typed-trigger
 * option precisely because it would delete it.
 *
 * Running the real cleanup pipeline from here needs no service refactor: [PostProcessor
 * .processProviderChain], [ProviderChainStore], [CloudFeatureToggle], [PersonaRegistry] and
 * [LocalCleanupModelHolder] are all `Context`-scoped, so this Activity assembles the same
 * chain/waterfall/persona inputs [WhisperAccessibilityService]'s dictation call site assembles
 * and calls the identical entry point. The pure parts of that assembly live in
 * [ProcessTextRequest.kt] so they are unit-testable without Robolectric.
 *
 * Threading: the cleanup call is issued from [worker], never the main thread — the LOCAL_LLM step
 * is synchronous on-device inference, and [CleanupWaterfallExecutor]'s cloud steps block on
 * OkHttp. Every result is posted back to [handler] and dropped if the Activity is already gone
 * (see [deliver]).
 *
 * Local-model contention with a concurrent dictation is safe by construction rather than by luck:
 * [LocalCleanupModelHolder.withInference] is `@Synchronized` on a process-scoped singleton and
 * checks [LocalCleanupModelSlot.needsReload] *inside* that monitor, so two callers can never
 * double-load the model, and [RealLocalInferenceEngine] runs it through
 * [BoundedBlockingCall.runWithDeadline] on one shared single-thread executor with a wall-clock
 * deadline. A local step that loses the race therefore times out and falls through to the next
 * waterfall step (or fails cleanly) instead of deadlocking.
 */
class ProcessTextActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "process-text-cleanup").apply { isDaemon = true }
    }
    private val inFlightCall = InFlightCall()

    /** Fresh per Activity instance, so a selection-menu cleanup never resumes at a step index
     *  recorded for a different chain by a previous invocation. */
    private val cursor = CleanupWaterfallCursor()

    private var pickerDialog: android.app.AlertDialog? = null
    private var progressDialog: android.app.AlertDialog? = null

    /** Set once the user backs out or the Activity is destroyed: a callback that arrives after
     *  this must not touch the window, or it leaks/crashes on a dead Activity. */
    @Volatile private var abandoned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_PROCESS_TEXT) {
            Log.w(TAG, "Started with unexpected action=${intent?.action}; finishing")
            finish()
            return
        }

        val parse = ProcessTextIntent.parse(
            rawText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
        )
        val request = when (parse) {
            is ProcessTextParse.EmptySelection -> {
                Log.i(TAG, "Empty selection; nothing to clean up")
                return failAndFinish("Nothing selected to clean up")
            }
            is ProcessTextParse.TooLong -> {
                Log.i(TAG, "Selection of ${parse.length} chars exceeds limit ${parse.limit}")
                return failAndFinish("Selection too long (${parse.length} characters, limit ${parse.limit})")
            }
            is ProcessTextParse.Accepted -> parse.request
        }

        val plan = ProcessTextCleanupPlanner.plan(
            chain = ProviderChainStore.load(this),
            cloudCleanupEnabled = CloudFeatureToggle.cleanupEnabled(this),
            allowLocalFallback = DictationModeToggle.allowLocalFallback(this),
            isCredentialConfigured = { kind -> ProviderCredentialStore.get(this, kind).isNotBlank() },
        )
        val ready = when (plan) {
            is ProcessTextCleanupPlan.Unavailable -> {
                Log.i(TAG, "Cleanup unavailable for selection: ${plan.reason}")
                return failAndFinish(messageFor(plan.reason))
            }
            is ProcessTextCleanupPlan.Ready -> plan
        }

        showPersonaPicker(request, ready)
    }

    private fun messageFor(reason: ProcessTextUnavailableReason): String = when (reason) {
        ProcessTextUnavailableReason.CLOUD_CLEANUP_DISABLED ->
            "Cloud cleanup is turned off and no on-device cleanup model is configured"
        ProcessTextUnavailableReason.NO_CLEANUP_CONFIGURED ->
            "No cleanup provider is configured — set one up in Ramblr"
        ProcessTextUnavailableReason.MISSING_OPENAI_KEY ->
            "Cleanup needs an OpenAI API key"
    }

    private fun showPersonaPicker(request: ProcessTextRequest, plan: ProcessTextCleanupPlan.Ready) {
        val personas = PersonaRegistry.all(this)
        if (personas.isEmpty()) {
            // Defensive only: BUILT_IN is non-empty by construction, so this cannot happen today.
            return failAndFinish("No cleanup styles available")
        }
        val current = PersonaRegistry.currentPersona(
            this,
            prefs().getString(KEY_CLEANUP_STYLE, null),
            prefs().getString(KEY_POST_PROCESSING_PROMPT, PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT,
        )
        val checked = personas.indexOfFirst { it.key == current.key }.takeIf { it >= 0 } ?: 0

        pickerDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Clean up with")
            .setSingleChoiceItems(personas.map { it.title }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                startCleanup(request, plan, personas[which])
            }
            .setNegativeButton("Cancel") { _, _ -> cancelAndFinish() }
            .setOnCancelListener { cancelAndFinish() }
            .show()
    }

    private fun startCleanup(
        request: ProcessTextRequest,
        plan: ProcessTextCleanupPlan.Ready,
        persona: CleanupPersona,
    ) {
        val vocabulary = VocabularyTerms.parse(
            prefs().getString(KEY_VOCABULARY_TERMS, VocabularyTerms.DEFAULT_SERIALIZED)
        )
        // An explicit pick in the dialog above is an explicit selection, so the persona's own
        // prompt is used verbatim -- the same contract as the overlay's quick style menu.
        val prompt = PostProcessor.interpolateVocabulary(
            CleanupPersonas.promptForExplicitSelection(persona),
            vocabulary,
        )
        val localModel = LocalCleanupProvider.selectedModel(this)
        val localPrompt = LocalCleanupProvider.selectedSystemPrompt(this)
        val localModelPath = ModelDownloader.localCleanupModelFile(this, localModel)?.absolutePath

        progressDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Cleaning up…")
            .setMessage("Ramblr is cleaning up ${request.text.length} characters.")
            .setNegativeButton("Cancel") { _, _ -> cancelAndFinish() }
            .setOnCancelListener { cancelAndFinish() }
            .show()

        Log.i(
            TAG,
            "Selection cleanup starting: chars=${request.text.length} readOnly=${request.readOnly} " +
                "persona=${persona.key} steps=${plan.waterfall.steps.map { it.group }}",
        )

        worker.execute {
            try {
                PostProcessor.processProviderChain(
                    text = request.text,
                    prompt = prompt,
                    chain = plan.chain,
                    cursor = cursor,
                    cancelHolder = inFlightCall,
                    credentialLookup = { kind -> ProviderCredentialStore.get(this, kind) },
                    localModelPath = { localModelPath },
                    localPrompt = localPrompt,
                    // #182 option 2: local cleanup applies the same terms as a deterministic
                    // post-pass over its output instead of in its prompt (which broke LFM2.5).
                    localVocabulary = vocabulary,
                    // Deliberately no benchmarkContext/correlationId: BenchmarkLogger and
                    // QualityLogger exist to correlate a cleanup stage with the transcription
                    // stage of the same dictation (#100/#105), and a selection-menu cleanup has
                    // no transcription stage. QualityLogger additionally persists the actual text,
                    // and text selected out of some other app is not this app's to record.
                ) { result ->
                    deliver(request, processTextOutcome(result.text, result.error))
                }
            } catch (t: Throwable) {
                // processProviderChain's own steps report failures through the callback; anything
                // escaping here is a programming/native error that would otherwise leave the
                // Activity showing a spinner forever.
                Log.e(TAG, "Selection cleanup threw", t)
                deliver(request, ProcessTextOutcome.Failed(t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun deliver(request: ProcessTextRequest, outcome: ProcessTextOutcome) {
        handler.post {
            if (abandoned || isFinishing || isDestroyed) {
                Log.i(TAG, "Dropping cleanup result: activity already gone")
                return@post
            }
            dismissDialogs()
            when (outcome) {
                is ProcessTextOutcome.Failed -> {
                    Log.w(TAG, "Selection cleanup failed: ${outcome.reason}")
                    failAndFinish("Cleanup failed (${outcome.reason})")
                }
                is ProcessTextOutcome.Cleaned -> {
                    // Always copy first, matching the dictation path's copy-then-write order
                    // (WhisperAccessibilityService.injectText). It is what makes the honest
                    // fallback work for a host that accepts the selection but then ignores our
                    // RESULT_OK: the cleaned text is already on the clipboard rather than lost.
                    // #157 explicitly rules out SwiftSlate's alternative (a TTL handoff to the
                    // accessibility service watching for the host's text-changed event), because
                    // that re-introduces the entire privacy cost this option exists to avoid.
                    ClipboardUtil.copy(this, outcome.text)
                    when (deliveryFor(request.readOnly)) {
                        ProcessTextDelivery.CLIPBOARD_ONLY -> {
                            Log.i(TAG, "Read-only host: returning no replacement, ${outcome.text.length} chars copied")
                            toast("Read-only field — cleaned text copied to clipboard")
                            setResult(RESULT_CANCELED)
                        }
                        ProcessTextDelivery.REPLACE_IN_HOST -> {
                            Log.i(TAG, "Returning cleaned selection: ${outcome.text.length} chars")
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, outcome.text),
                            )
                        }
                    }
                    finish()
                }
            }
        }
    }

    /** User dismissed the picker or the spinner: abort any in-flight provider call (a paid step
     *  must never keep running after a cancel, #63) and return nothing to the host. */
    private fun cancelAndFinish() {
        abandoned = true
        inFlightCall.cancel()
        dismissDialogs()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun failAndFinish(message: String) {
        toast(message)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun dismissDialogs() {
        pickerDialog?.takeIf { it.isShowing }?.dismiss()
        pickerDialog = null
        progressDialog?.takeIf { it.isShowing }?.dismiss()
        progressDialog = null
    }

    override fun onDestroy() {
        abandoned = true
        inFlightCall.cancel()
        dismissDialogs()
        handler.removeCallbacksAndMessages(null)
        // The worker is not awaited: an abandoned local-inference call is already bounded by
        // BoundedBlockingCall's deadline, and shutdown() lets the thread die once it drains.
        worker.shutdown()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "ProcessTextActivity"
        const val PREFS_NAME = "ramblr"
        const val KEY_CLEANUP_STYLE = "cleanup_style"
        const val KEY_POST_PROCESSING_PROMPT = "post_processing_prompt"
        const val KEY_VOCABULARY_TERMS = "custom_vocabulary_terms"
    }
}
