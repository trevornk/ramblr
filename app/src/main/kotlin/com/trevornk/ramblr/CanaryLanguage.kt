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
 * Both `srcLang` and `tgtLang` get the same value -- Canary treats matching src/tgt as plain
 * transcription; mismatched values mean *translation*, which is out of scope for a dictation
 * app (#177).
 *
 * Which language is used (#294): an explicit Canary choice always wins, so nobody who already
 * picked a language here sees a change. When the Canary setting was never made, Canary follows
 * the main [DictationLanguage] if that names one of Canary's four languages, and otherwise falls
 * back to [DEFAULT] = "en" (the value the config was hardcoded to before #177). Before this,
 * Canary ignored Dictation language entirely: a user who set Dictation language = German (the
 * obvious, discoverable setting) got Canary decoding with the English token, and Canary answered
 * with an English translation of the German speech. Measured on a Pixel 10a with the same clip:
 * src=en -> "Today is the weather in Berlin very nice, ..."; src=de -> "Heute ist das Wetter in
 * Berlin sehr schön, ...".
 */
object CanaryLanguage {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "canary_src_lang"
    const val DEFAULT = "en"

    /** The exact language set of the shipped canary-180m-flash-en-es-de-fr model -- not a general
     *  language list. A different Canary variant in the catalog would need its own set. */
    val SUPPORTED = listOf("en", "es", "de", "fr")

    /** The explicit Canary choice, or null when never made (or corrupt -- an unknown value, say
     *  a future model's language leaking back onto this one, counts as "not chosen" rather than
     *  being handed to sherpa-onnx as a token it can't map). */
    fun explicitOrNull(prefs: SharedPreferences): String? =
        prefs.getString(KEY, null)?.takeIf { it in SUPPORTED }

    /** True when no explicit Canary language is stored, i.e. Canary follows [DictationLanguage]. */
    fun followsDictationLanguage(prefs: SharedPreferences): Boolean = explicitOrNull(prefs) == null

    /** The language Canary decodes with: the explicit choice, else the Dictation language when
     *  Canary supports it, else [DEFAULT]. Always one of [SUPPORTED]. */
    fun languageOrDefault(prefs: SharedPreferences): String =
        explicitOrNull(prefs)
            ?: DictationLanguage.languageOrNull(prefs)?.takeIf { it in SUPPORTED }
            ?: DEFAULT

    fun setLanguage(prefs: SharedPreferences, language: String) {
        prefs.edit().putString(KEY, if (language in SUPPORTED) language else DEFAULT).apply()
    }

    /** Drops the explicit choice so Canary follows [DictationLanguage] again. */
    fun followDictationLanguage(prefs: SharedPreferences) {
        prefs.edit().remove(KEY).apply()
    }

    fun explicitOrNull(context: Context): String? = explicitOrNull(prefs(context))

    fun languageOrDefault(context: Context): String = languageOrDefault(prefs(context))

    fun followsDictationLanguage(context: Context): Boolean = followsDictationLanguage(prefs(context))

    fun followDictationLanguage(context: Context) = followDictationLanguage(prefs(context))

    fun setLanguage(context: Context, language: String) = setLanguage(prefs(context), language)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
