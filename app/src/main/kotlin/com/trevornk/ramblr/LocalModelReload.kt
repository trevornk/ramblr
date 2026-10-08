package com.trevornk.ramblr

import android.content.Context

/**
 * Makes every running dictation surface rebuild its local recognizer from the current settings.
 *
 * The Canary source-language token is baked into the native recognizer when it is created (#294),
 * so a language change only takes effect after a reload; without one the already-loaded
 * recognizer keeps decoding with the old value until the service happens to restart (that is
 * what made "I set German and still get English" persist after the setting was changed). Covers
 * the accessibility service and, when one is alive, the IME runtime -- the two long-lived
 * holders. Audio-file jobs build a fresh recognizer per job and so always see the current value.
 */
internal object LocalModelReload {
    /** True when the model that would load right now is Canary: the selected `model_name`, or,
     *  when blank, the first installed model (the same auto-detect [DictationRuntime] does). */
    fun activeModelIsCanary(selected: String, installed: List<String>): Boolean {
        val active = selected.ifBlank { installed.firstOrNull() } ?: return false
        return active.contains("canary")
    }

    /** Reloads the running recognizers, but only when Canary is the active model -- every other
     *  local model ignores the language setting, and reloading a large one costs seconds. */
    fun reloadIfCanaryActive(context: Context) {
        val selected = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            .getString("model_name", "") ?: ""
        if (!activeModelIsCanary(selected, LocalTranscriber.availableModels(context))) return
        WhisperAccessibilityService.instance?.reloadModel()
        ProcessActiveImeModelReadyReload.notifyModelReady(ModelDownloadWorker.ModelReloadKind.TRANSCRIPTION)
    }
}
