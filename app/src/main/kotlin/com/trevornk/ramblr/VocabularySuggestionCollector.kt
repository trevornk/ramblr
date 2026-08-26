package com.trevornk.ramblr

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Service-side wiring for smart vocabulary suggestions (#216): the one call
 * [WhisperAccessibilityService] makes per accepted dictation. Everything else — extraction
 * ([VocabularySuggestionExtractor]), thresholds/persistence ([VocabularySuggestionStore]),
 * the gate ([VocabularySuggestionsToggle]) — lives in plain testable objects; this class only
 * moves the work off the main thread and guarantees the dictation path can never be broken by
 * a collection bug (outer try/catch, log-and-drop).
 *
 * Privacy: [collect] receives the transcript strings already in scope at the injection
 * call site and never persists them — the only writes are per-token candidate counters
 * (see [VocabularySuggestionStore]'s kdoc).
 */
object VocabularySuggestionCollector {

    private const val TAG = "VocabSuggest"

    /** Single background thread: collection is rare (once per dictation) and cheap, and a
     *  single lane means store read-modify-writes never race each other. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vocab-suggestions").apply { isDaemon = true }
    }

    /**
     * Records one accepted dictation. No-ops instantly when the master toggle is off. Safe to
     * call from the main thread — all real work happens on [executor].
     *
     * [rawText] is the raw ASR transcript; [finalText] the accepted final text (post-cleanup,
     * or the same string when cleanup didn't run/change anything).
     */
    @JvmStatic
    fun collect(context: Context, rawText: String, finalText: String) {
        try {
            if (!VocabularySuggestionsToggle.isEnabled(context)) return
            val appContext = context.applicationContext
            executor.execute {
                try {
                    // Re-check on the worker: the user may have toggled off (which also
                    // cleared counters) between post and execution.
                    if (!VocabularySuggestionsToggle.isEnabled(appContext)) return@execute
                    val vocabulary = VocabularyEditor.terms(appContext)
                    val events = VocabularySuggestionExtractor.extract(
                        rawText = rawText,
                        finalText = finalText,
                        vocabularyTerms = vocabulary,
                    ) { word -> SuggestionFilterDictionary.contains(appContext, word) }
                    if (events.isEmpty()) return@execute
                    VocabularySuggestionStore.recordEvents(
                        prefs = appContext.getSharedPreferences("ramblr", Context.MODE_PRIVATE),
                        events = events,
                        vocabularyTerms = vocabulary,
                        nowMs = System.currentTimeMillis(),
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "suggestion collection failed (ignored)", t)
                }
            }
        } catch (t: Throwable) {
            // Never let suggestion bookkeeping interfere with dictation itself.
            Log.w(TAG, "suggestion collection dispatch failed (ignored)", t)
        }
    }
}
