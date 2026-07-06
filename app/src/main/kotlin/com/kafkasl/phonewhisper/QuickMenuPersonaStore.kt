package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray

/**
 * Persisted, ordered list of persona keys shown in the floating icon's long-press style menu
 * (#103 Style manager) -- replaces the previous hardcoded `CleanupPersonas.BUILT_IN` iteration in
 * [WhisperAccessibilityService.buildStyleMenuContent]. Capped to [MAX_ENTRIES] per Trevor's
 * request (a long-press menu with more than ~8 rows stops being a quick-access surface); the cap
 * is enforced on write ([setSelection]), not on read, so a value that somehow exceeds it (e.g. a
 * future relaxed limit rolled back) still degrades to "show the first [MAX_ENTRIES]" rather than
 * crashing or silently showing everything.
 *
 * Stored as a JSON array of persona keys (built-in or custom) in the same plain "phonewhisper"
 * prefs file as [ProviderChainStore]/[CustomPersonaStore].
 */
object QuickMenuPersonaStore {
    private const val PREFS_NAME = "phonewhisper"
    private const val KEY_SELECTION = "quick_menu_persona_keys"

    const val MIN_ENTRIES = 5
    const val MAX_ENTRIES = 8

    /** Default selection for a fresh install or an install that predates this store: the five
     *  #103 built-ins, in their defined display order. */
    fun defaultSelection(): List<String> = CleanupPersonas.BUILT_IN.map { it.key }

    fun serialize(keys: List<String>): String = JSONArray(keys).toString()

    fun deserialize(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** The persisted selection, filtered to keys that still resolve to a real persona (a custom
     *  persona referenced here may have since been deleted) and truncated to [MAX_ENTRIES] as a
     *  defensive backstop -- falls back to [defaultSelection] if never configured. */
    fun load(context: Context): List<String> {
        val saved = deserialize(prefs(context).getString(KEY_SELECTION, null)) ?: defaultSelection()
        val validKeys = PersonaRegistry.all(context).map { it.key }.toSet()
        return saved.filter { it in validKeys }.take(MAX_ENTRIES)
    }

    /** Saves [keys], clamped to [MAX_ENTRIES] entries (extra trailing entries are dropped rather
     *  than rejecting the whole write) -- the Style manager's own UI is expected to prevent
     *  selecting more than [MAX_ENTRIES] in the first place; this is the backstop. Does not
     *  enforce [MIN_ENTRIES] here since a persona can be deleted out from under a selection that
     *  was valid when saved -- [MIN_ENTRIES] is a UI-level nudge, not a hard invariant. */
    fun setSelection(context: Context, keys: List<String>) {
        prefs(context).edit().putString(KEY_SELECTION, serialize(keys.take(MAX_ENTRIES))).apply()
    }

    /** Removes [key] from the persisted selection, if present -- called when a custom persona is
     *  deleted so the quick menu never references a persona that no longer exists. */
    fun remove(context: Context, key: String) {
        val current = deserialize(prefs(context).getString(KEY_SELECTION, null)) ?: return
        if (key !in current) return
        prefs(context).edit().putString(KEY_SELECTION, serialize(current.filterNot { it == key })).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
