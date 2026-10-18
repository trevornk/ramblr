package com.trevornk.ramblr

import android.content.Context

/** A language the keyboard can translate dictation into. [englishName] goes into the AI prompt;
 *  [nativeName] is what the keyboard chip shows. */
internal data class TranslationLanguage(val code: String, val englishName: String, val nativeName: String)

/**
 * Keyboard-only translation: the user picks a handful of languages in Settings, and the keyboard
 * shows one chip per language next to "Original". The chosen target applies to dictations started
 * from the keyboard only; the floating icon never translates. Translation is done by the cloud
 * cleanup step, so it needs a cloud AI provider (on-device cleanup models are too small for it).
 */
internal object KeyboardTranslation {
    private const val PREFS_NAME = "ramblr"
    const val KEY_LANGUAGES = "translation_languages"
    const val KEY_TARGET = "keyboard_translate_to"

    val ALL: List<TranslationLanguage> = listOf(
        TranslationLanguage("en", "English", "English"),
        TranslationLanguage("es", "Spanish", "Español"),
        TranslationLanguage("de", "German", "Deutsch"),
        TranslationLanguage("fr", "French", "Français"),
        TranslationLanguage("it", "Italian", "Italiano"),
        TranslationLanguage("pt", "Portuguese", "Português"),
        TranslationLanguage("nl", "Dutch", "Nederlands"),
        TranslationLanguage("pl", "Polish", "Polski"),
        TranslationLanguage("uk", "Ukrainian", "Українська"),
        TranslationLanguage("ru", "Russian", "Русский"),
        TranslationLanguage("cs", "Czech", "Čeština"),
        TranslationLanguage("ro", "Romanian", "Română"),
        TranslationLanguage("hu", "Hungarian", "Magyar"),
        TranslationLanguage("el", "Greek", "Ελληνικά"),
        TranslationLanguage("sv", "Swedish", "Svenska"),
        TranslationLanguage("da", "Danish", "Dansk"),
        TranslationLanguage("no", "Norwegian", "Norsk"),
        TranslationLanguage("fi", "Finnish", "Suomi"),
        TranslationLanguage("tr", "Turkish", "Türkçe"),
        TranslationLanguage("ka", "Georgian", "ქართული"),
        TranslationLanguage("kk", "Kazakh", "Қазақша"),
        TranslationLanguage("ar", "Arabic", "العربية"),
        TranslationLanguage("he", "Hebrew", "עברית"),
        TranslationLanguage("hi", "Hindi", "हिन्दी"),
        TranslationLanguage("zh", "Simplified Chinese", "中文"),
        TranslationLanguage("ja", "Japanese", "日本語"),
        TranslationLanguage("ko", "Korean", "한국어"),
        TranslationLanguage("vi", "Vietnamese", "Tiếng Việt"),
        TranslationLanguage("th", "Thai", "ไทย"),
        TranslationLanguage("id", "Indonesian", "Bahasa Indonesia"),
    )

    fun byCode(code: String?): TranslationLanguage? = ALL.firstOrNull { it.code == code }

    /** Parses the stored comma list, keeping only known codes, in [ALL]'s order. */
    fun parseChosen(raw: String?): List<TranslationLanguage> {
        val codes = raw.orEmpty().split(',').map { it.trim() }.toSet()
        return ALL.filter { it.code in codes }
    }

    fun chosen(context: Context): List<TranslationLanguage> = parseChosen(prefs(context).getString(KEY_LANGUAGES, null))

    /** Saves the languages offered on the keyboard; a target that is no longer offered is cleared. */
    fun setChosen(context: Context, codes: Collection<String>) {
        val kept = ALL.filter { it.code in codes }
        val editor = prefs(context).edit().putString(KEY_LANGUAGES, kept.joinToString(",") { it.code })
        if (kept.none { it.code == prefs(context).getString(KEY_TARGET, null) }) editor.remove(KEY_TARGET)
        editor.apply()
    }

    /** The language keyboard dictations are translated into, or null for "Original". */
    fun target(context: Context): TranslationLanguage? {
        val code = prefs(context).getString(KEY_TARGET, null) ?: return null
        return chosen(context).firstOrNull { it.code == code }
    }

    fun setTarget(context: Context, code: String?) {
        prefs(context).edit().apply { if (code == null) remove(KEY_TARGET) else putString(KEY_TARGET, code) }.apply()
    }

    /** Settings row summary. */
    fun summary(context: Context): String {
        val chosen = chosen(context)
        return if (chosen.isEmpty()) "None yet. Pick languages to get translate buttons on the keyboard"
            else chosen.joinToString(", ") { it.englishName }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
