package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * The language the user dictates in (#290), sent as a hint to every cloud transcription path and
 * used to keep cleanup from translating.
 *
 * Why this exists: without a hint, cloud ASR auto-detects the spoken language from the audio.
 * Detection is reliable on clean speech but can lock onto English, especially on short,
 * accented, or noisy clips. When it does, the model doesn't fail -- it produces an English
 * transcript or an English-phonetic garble of the German audio, and the user sees their speech
 * "translated". Measured 2026-10-05 on German TTS clips: whisper.cpp forced to English returned
 * fluent English translations, and Gemini 3.1 Flash-Lite with Ramblr's prompt rendered one
 * German clip as English-phonetic text on 9 of 9 runs whether or not the prompt said "never
 * translate". Telling Gemini the language fixed that clip on 3 of 3 runs. OpenAI-compatible
 * `/audio/transcriptions` servers (OpenAI, Groq, Open WebUI, local Whisper servers) take an
 * ISO-639-1 `language` field for exactly this.
 *
 * Defaults to [AUTO] (no hint), which is exactly what every build before this setting sent, so
 * shipping this changes nothing for users who never open it.
 *
 * Separate from [CanaryLanguage]: Canary is a local model that needs a source-language token
 * from its own four-language set. This setting is for cloud providers and can be any
 * ISO-639-1 code in [SUPPORTED].
 */
object DictationLanguage {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "dictation_language"

    /** Stored value meaning "no hint; let the provider detect the language". */
    const val AUTO = ""

    /**
     * ISO-639-1 codes offered in the picker. This is the set that the OpenAI transcription
     * models, Whisper (and so Groq and most self-hosted servers), and Gemini all accept, limited
     * to languages with usable Whisper accuracy so the picker isn't padded with options that
     * would transcribe badly anyway. Ordered by name in [label], not here.
     */
    val SUPPORTED = listOf(
        "af", "ar", "hy", "az", "be", "bs", "bg", "ca", "zh", "hr", "cs", "da", "nl", "en", "et",
        "fi", "fr", "gl", "de", "el", "he", "hi", "hu", "is", "id", "it", "ja", "kn", "kk", "ko",
        "lv", "lt", "mk", "ms", "mr", "mi", "ne", "no", "fa", "pl", "pt", "ro", "ru", "sr", "sk",
        "sl", "es", "sw", "sv", "tl", "ta", "th", "tr", "uk", "ur", "vi", "cy",
    )

    /** The stored language, or null for [AUTO]. Unknown stored values coerce to null rather than
     *  sending a provider a code it may reject with a 400 and fail the whole dictation. */
    fun languageOrNull(prefs: SharedPreferences): String? {
        val stored = prefs.getString(KEY, AUTO) ?: AUTO
        return stored.takeIf { it in SUPPORTED }
    }

    fun setLanguage(prefs: SharedPreferences, language: String?) {
        prefs.edit().putString(KEY, language?.takeIf { it in SUPPORTED } ?: AUTO).apply()
    }

    fun languageOrNull(context: Context): String? = languageOrNull(prefs(context))

    fun setLanguage(context: Context, language: String?) = setLanguage(prefs(context), language)

    /** English name for [code], e.g. "German". Used in prompts sent to models, so it stays in
     *  English regardless of the device locale. */
    fun englishName(code: String): String =
        Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    /** Picker/summary label, e.g. "German (Deutsch)", or just "English" when the native name
     *  matches the English one. */
    fun label(code: String?): String {
        if (code == null) return "Auto-detect"
        val english = englishName(code)
        val native = Locale(code).getDisplayLanguage(Locale(code))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale(code)) else it.toString() }
        return if (native.isBlank() || native.equals(english, ignoreCase = true)) english else "$english ($native)"
    }

    /**
     * Gemini Live's spelling of each [SUPPORTED] code, per its "Supported languages" table
     * (ai.google.dev/gemini-api/docs/live-api/capabilities, last updated 2026-09-18, checked
     * 2026-10-05). Every [SUPPORTED] language is listed there. Most use the same two-letter
     * code; Live wants a script/region form for Chinese and Portuguese and `fil` for Filipino.
     * The null fallback in [geminiLiveCode] remains for any future [SUPPORTED] addition Live
     * doesn't list, so an unlisted code sends no hint rather than risking session setup.
     */
    private val GEMINI_LIVE_OVERRIDES = mapOf("zh" to "zh-Hans", "pt" to "pt-BR", "tl" to "fil")
    private val GEMINI_LIVE_CODES: Map<String, String> =
        SUPPORTED.associateWith { GEMINI_LIVE_OVERRIDES[it] ?: it }

    /** The Gemini Live language code for [code], or null when Live doesn't document it. */
    fun geminiLiveCode(code: String?): String? = code?.let { GEMINI_LIVE_CODES[it] }

    /** [SUPPORTED] sorted by English name, for the picker. */
    fun pickerOrder(): List<String> = SUPPORTED.sortedBy { englishName(it) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
