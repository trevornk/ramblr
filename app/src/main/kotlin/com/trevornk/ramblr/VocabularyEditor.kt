package com.trevornk.ramblr

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText

/**
 * The Personal Vocabulary editor dialog and its row summary, shared by both Settings entry
 * points (#217): the top-level "Personal vocabulary" row on [MainActivity] and the original
 * Vocabulary section on [BehaviorActivity].
 *
 * Extracted verbatim from BehaviorActivity's private `promptVocabulary`/`vocabularySummary`/
 * `vocabularyLocalOnlyNote` (#26/#185) when #140's discoverability complaint got the feature
 * promoted to the main screen -- one implementation, two callers, so the two rows can never
 * drift apart in behavior or copy. Same prefs key, same dialog, same inert-setting note.
 */
object VocabularyEditor {

    fun terms(context: Context): List<String> = VocabularyTerms.parse(
        prefs(context).getString(PREF_KEY, VocabularyTerms.DEFAULT_SERIALIZED)
    )

    /** Appends [term] to the vocabulary exactly as if typed into the editor dialog (#216: the
     *  suggestion UI's Add action) — same prefs key, same parse/serialize normalization, so a
     *  duplicate (case-insensitive) is a no-op just as it would be in the dialog. */
    fun addTerm(context: Context, term: String) {
        val updated = VocabularyTerms.parse(
            VocabularyTerms.serialize(terms(context) + term)
        )
        prefs(context).edit()
            .putString(PREF_KEY, VocabularyTerms.serialize(updated))
            .apply()
    }

    /** The Behavior row's subtitle: the term list itself, prefixed with the #185 inert-setting
     *  warning when nothing in the current configuration applies the terms. */
    fun rowSummary(context: Context): String =
        vocabularyRowSummaryText(terms(context), inert = localOnlyNote(context) != null)

    /** Non-null when the user's current configuration ignores the vocabulary entirely (#185):
     *  cloud transcription off AND no active cleanup path of any kind. Mirrors the real runtime
     *  gates: transcription's `use_local` pref, cleanup's master toggle, [CloudFeatureToggle]'s
     *  cloud gate, the chain's cleanup-capable entries, and -- for the local path, which applies
     *  terms via [VocabularyPostCorrector]'s output post-pass since #182 -- an actually
     *  installed local cleanup model ([ModelDownloader.localCleanupModelFile] non-null, the same
     *  check the executor's LOCAL_LLM branch fails on). */
    fun localOnlyNote(context: Context): String? {
        val cloudTranscription = !prefs(context).getBoolean("use_local", true)
        val cleanupOn = PostProcessingToggle.isEnabled(context)
        val cleanupEntries = ProviderChainStore.load(context).capableEntriesFor(needsTranscription = false)
        val cloudCleanup = cleanupOn &&
            CloudFeatureToggle.cleanupEnabled(context) &&
            cleanupEntries.any { it.kind != ProviderKind.LOCAL }
        val localCleanup = cleanupOn &&
            cleanupEntries.any { it.kind == ProviderKind.LOCAL } &&
            ModelDownloader.localCleanupModelFile(context, LocalCleanupProvider.selectedModel(context)) != null
        return VocabularyTerms.localOnlyNote(
            cloudTranscriptionActive = cloudTranscription,
            cloudCleanupActive = cloudCleanup,
            localCleanupActive = localCleanup,
        )
    }

    /** Shows the editor dialog. [onSaved] runs after a save so the caller can refresh its own
     *  row subtitle -- each entry point words that subtitle differently (term count on the main
     *  screen, term list on Behavior), which is why the dialog doesn't refresh anything itself. */
    fun prompt(activity: Activity, onSaved: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "One term per line, e.g. FastHTML"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(VocabularyTerms.serialize(terms(activity)))
        }
        // #185/#182: the terms reach cloud transcription, cloud cleanup (prompt interpolation),
        // and -- since #182's option-2 post-pass -- local cleanup, where they are applied as a
        // deterministic correction over the model's output rather than in its prompt. Only
        // local transcription ignores them (#131), so the inert-setting note now fires only
        // when cleanup is off entirely on a local-transcription setup.
        val message = buildString {
            append(
                "Project names or jargon that speech-to-text often mishears. One per line.\n\n" +
                    "Applies to cloud transcription and to cleanup (cloud and local)."
            )
            localOnlyNote(activity)?.let { append("\n\n").append(it) }
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Personal vocabulary")
            .setMessage(message)
            .setView(input.apply { setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 8)) })
            .setPositiveButton("Save") { _, _ ->
                val terms = VocabularyTerms.parse(input.text.toString())
                prefs(activity).edit()
                    .putString(PREF_KEY, VocabularyTerms.serialize(terms))
                    .apply()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Same "custom_vocabulary_terms" key every reader uses ([WhisperAccessibilityService],
     *  [ProcessTextActivity], [BackupManager]'s backup set) -- literal here because
     *  ProcessTextActivity's mirror constant lives in its private companion. */
    private const val PREF_KEY = "custom_vocabulary_terms"

    private fun prefs(context: Context) = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private fun dp(context: Context, n: Int) = (n * context.resources.displayMetrics.density).toInt()
}
