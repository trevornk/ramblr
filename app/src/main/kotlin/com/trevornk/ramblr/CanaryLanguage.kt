package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * User-configurable source language for the NeMo Canary local model (#177), wired into the
 * `srcLang`/`tgtLang` fields of the canary branch in [LocalTranscriber.detectModelConfig].
 * Only the Canary model consumes this -- every other local model (Whisper, Parakeet, Moonshine,
 * NeMo CTC) has no language-token concept in its sherpa-onnx config and ignores it entirely.
 *
 * Canary is a prompted multilingual model: decoding starts from a source-language token, and the
 * wrong token doesn't just degrade accuracy -- it collapses output entirely. Measured 2026-08-26
 * on the Fold: canary-180m-flash's own bundled `de.wav` decodes to degenerate " E E E E…" under
 * the previously hardcoded `srcLang = "en"`, while `en.wav` is perfect. So non-English speech was
 * simply broken until the token became configurable.
 *
 * Defaults to [DEFAULT] = "en", the value the config was hardcoded to before this setting
 * existed, so shipping this is purely additive: nobody who never opens the setting sees any
 * change in behavior. Both `srcLang` and `tgtLang` get the same value -- Canary treats matching
 * src/tgt as plain transcription; mismatched values mean *translation*, which is out of scope
 * for a dictation app (#177).
 */
object CanaryLanguage {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "canary_src_lang"
    const val DEFAULT = "en"

    /** The exact language set of the shipped canary-180m-flash-en-es-de-fr model -- not a general
     *  language list. A different Canary variant in the catalog would need its own set. */
    val SUPPORTED = listOf("en", "es", "de", "fr")

    fun languageOrDefault(prefs: SharedPreferences): String {
        val stored = prefs.getString(KEY, DEFAULT) ?: DEFAULT
        // Coerce unknown values (corrupt pref, or a future model's language leaking back onto
        // this one) to the safe default rather than handing sherpa-onnx a token it can't map.
        return if (stored in SUPPORTED) stored else DEFAULT
    }

    fun setLanguage(prefs: SharedPreferences, language: String) {
        prefs.edit().putString(KEY, if (language in SUPPORTED) language else DEFAULT).apply()
    }

    fun languageOrDefault(context: Context): String = languageOrDefault(prefs(context))

    fun setLanguage(context: Context, language: String) = setLanguage(prefs(context), language)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
