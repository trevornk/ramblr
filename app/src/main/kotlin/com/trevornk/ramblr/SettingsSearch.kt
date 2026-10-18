package com.trevornk.ramblr

import android.content.Context
import android.content.Intent

/**
 * One searchable setting: its row [title] exactly as the target screen renders it (used to scroll
 * to and flash the row, see [BaseSettingsActivity.EXTRA_HIGHLIGHT_TITLE]), where it lives, extra
 * [keywords] people might type instead of the title, and how to open its screen.
 */
data class SettingsSearchEntry(
    val title: String,
    val where: String,
    val keywords: String = "",
    val open: (Context) -> Intent,
)

/**
 * Static index behind the main screen's search box. Static on purpose: building it by inflating
 * every screen would cost a dozen activity layouts per keystroke. SettingsSearchIndexTest renders
 * each target screen and checks every indexed title is really there, so the index can't silently
 * drift from the screens.
 */
object SettingsSearchIndex {

    private inline fun <reified T> screen(): (Context) -> Intent = { Intent(it, T::class.java) }
    private fun behavior(section: BehaviorActivity.Section): (Context) -> Intent =
        { BehaviorActivity.intent(it, section) }
    private fun invocation(section: InvocationActivity.Section): (Context) -> Intent =
        { InvocationActivity.intent(it, section) }
    private fun data(section: DataLogsActivity.Section): (Context) -> Intent =
        { DataLogsActivity.intent(it, section) }

    // "Where" labels: the row titles on the main screen, so a result says which row to tap.
    private const val RECOGNITION = "Recognition"
    private const val CLEANUP = "AI cleanup"
    private const val CLOUD = "Cloud providers"
    private const val ICON = "Floating icon"
    private const val KEYBOARD = "Voice keyboard"
    private const val SHORTCUTS = "System shortcuts"
    private const val RECORDING = "Recording"
    private const val WORDS = "Words and snippets"
    private const val PRIVACY = "Privacy"
    private const val HISTORY = "History"
    private const val BACKUP = "Backup & restore"
    private const val DIAGNOSTICS = "Diagnostics"

    val entries: List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry("Use cloud transcription", RECOGNITION, "speech to text stt local online", screen<TranscriptionActivity>()),
        SettingsSearchEntry("Dictation language", RECOGNITION, "language russian english auto detect", screen<TranscriptionActivity>()),
        SettingsSearchEntry("Local transcription threads", RECOGNITION, "cpu speed performance on-device tuning", screen<TranscriptionActivity>()),
        SettingsSearchEntry("Canary language", RECOGNITION, "canary model language", screen<TranscriptionActivity>()),
        SettingsSearchEntry("Compress audio before cloud upload", RECOGNITION, "aac m4a data mobile", screen<TranscriptionActivity>()),
        SettingsSearchEntry("Live cloud transcription", RECOGNITION, "gemini stream experimental", screen<TranscriptionActivity>()),
        SettingsSearchEntry("Live preview", RECOGNITION, "partial text live streaming", screen<TranscriptionActivity>()),
        SettingsSearchEntry("Streaming live preview", "$RECOGNITION › Live preview", "partial text live streaming", screen<LivePreviewActivity>()),
        SettingsSearchEntry("Improve my dictation with AI", CLEANUP, "cleanup ai grammar punctuation llm", screen<CleanupActivity>()),
        SettingsSearchEntry("Preview before inserting", CLEANUP, "review confirm", screen<CleanupActivity>()),
        SettingsSearchEntry("Remember cleanup style per app", CLEANUP, "persona per app style", screen<CleanupActivity>()),
        SettingsSearchEntry("Offer raw text after cleanup", CLEANUP, "undo original", screen<CleanupActivity>()),
        SettingsSearchEntry("Add provider", CLOUD, "api key openai anthropic gemini groq openrouter", screen<CloudProviderActivity>()),
        SettingsSearchEntry("Fall back to on-device if cloud fails", CLOUD, "fallback offline", screen<CloudProviderActivity>()),
        SettingsSearchEntry("Fall back to cloud if on-device fails", CLOUD, "fallback online", screen<CloudProviderActivity>()),
        SettingsSearchEntry("Floating ring", ICON, "icon button bubble circle", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Show the icon", ICON, "keyboard text field hide focus visibility", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Auto-hide icon when idle", ICON, "peek edge slide hide", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Auto-hide delay", ICON, "peek seconds", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Peeked sliver size", ICON, "peek edge", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Single-tap restore and record", ICON, "peek tap", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Allow hiding the floating icon", ICON, "hide notification", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Size and colors", ICON, "appearance look color size border custom image", behavior(BehaviorActivity.Section.ICON)),
        SettingsSearchEntry("Icon size", "$ICON › Size and colors", "big small", screen<OverlayAppearanceActivity>()),
        SettingsSearchEntry("Custom icon image", "$ICON › Size and colors", "picture photo", screen<OverlayAppearanceActivity>()),
        SettingsSearchEntry("Ramblr keyboard", KEYBOARD, "voice keyboard ime input method", invocation(InvocationActivity.Section.KEYBOARD)),
        SettingsSearchEntry("Keyboard theme", KEYBOARD, "voice keyboard dark light night mode colors appearance", invocation(InvocationActivity.Section.KEYBOARD)),
        SettingsSearchEntry("Translation languages", KEYBOARD, "translate translation keyboard language english", invocation(InvocationActivity.Section.KEYBOARD)),
        SettingsSearchEntry("Quick Settings tile", SHORTCUTS, "qs tile notification shade panel", invocation(InvocationActivity.Section.SHORTCUTS)),
        SettingsSearchEntry("Volume-keys hold", SHORTCUTS, "volume buttons shortcut", invocation(InvocationActivity.Section.SHORTCUTS)),
        SettingsSearchEntry("System accessibility button / gesture", SHORTCUTS, "accessibility shortcut gesture", invocation(InvocationActivity.Section.SHORTCUTS)),
        SettingsSearchEntry("Auto-stop after silence", RECORDING, "pause stop automatically vad", behavior(BehaviorActivity.Section.DICTATION)),
        SettingsSearchEntry("Silence threshold", RECORDING, "pause seconds", behavior(BehaviorActivity.Section.DICTATION)),
        SettingsSearchEntry("Personal vocabulary", WORDS, "words names jargon dictionary terms", behavior(BehaviorActivity.Section.WORDS)),
        SettingsSearchEntry("Smart vocabulary suggestions", WORDS, "suggest words", behavior(BehaviorActivity.Section.WORDS)),
        SettingsSearchEntry("Snippets", WORDS, "templates shortcuts canned text", behavior(BehaviorActivity.Section.WORDS)),
        SettingsSearchEntry("App exclusions", PRIVACY, "bank block apps exclude", behavior(BehaviorActivity.Section.PRIVACY)),
        SettingsSearchEntry("Turn Ramblr off", PRIVACY, "disable stop service off switch", behavior(BehaviorActivity.Section.PRIVACY)),
        SettingsSearchEntry("Let automation apps turn Ramblr off", PRIVACY, "tasker macrodroid broadcast", behavior(BehaviorActivity.Section.PRIVACY)),
        SettingsSearchEntry("Save dictation history", HISTORY, "history transcripts privacy", data(DataLogsActivity.Section.HISTORY)),
        SettingsSearchEntry("Dictation history", HISTORY, "history transcripts old copy", data(DataLogsActivity.Section.HISTORY)),
        SettingsSearchEntry("Backup all data", BACKUP, "export backup", data(DataLogsActivity.Section.BACKUP)),
        SettingsSearchEntry("Restore from backup", BACKUP, "import restore", data(DataLogsActivity.Section.BACKUP)),
        SettingsSearchEntry("Debug / visibility", DIAGNOSTICS, "debug developer details", data(DataLogsActivity.Section.DIAGNOSTICS)),
        SettingsSearchEntry("Save quality log", DIAGNOSTICS, "log diagnostics", data(DataLogsActivity.Section.DIAGNOSTICS)),
        SettingsSearchEntry("Share quality log", DIAGNOSTICS, "log diagnostics send", data(DataLogsActivity.Section.DIAGNOSTICS)),
        SettingsSearchEntry("Share benchmark log", DIAGNOSTICS, "log diagnostics timing", data(DataLogsActivity.Section.DIAGNOSTICS)),
        SettingsSearchEntry("Redo setup walkthrough", "Setup", "onboarding wizard tutorial guide", screen<SetupActivity>()),
        SettingsSearchEntry("Audio permission", "Setup", "microphone mic permission", screen<SetupActivity>()),
        SettingsSearchEntry("Accessibility service", "Setup", "accessibility enable", screen<SetupActivity>()),
        SettingsSearchEntry("Notifications", "Setup", "notification permission alerts download progress", screen<SetupActivity>()),
    )

    /** Every word of [query] must appear in the title, location or keywords; title hits rank
     *  first. Case-insensitive. A blank query returns nothing. */
    fun search(query: String, index: List<SettingsSearchEntry> = entries): List<SettingsSearchEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return index
            .filter { entry ->
                val haystack = "${entry.title} ${entry.where} ${entry.keywords}".lowercase()
                words.all { it in haystack }
            }
            .sortedBy { entry -> if (words.all { it in entry.title.lowercase() }) 0 else 1 }
    }
}
