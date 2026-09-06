package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user-authored Snippets (#248) as JSON in the same plain "ramblr" SharedPreferences
 * file as [CustomPersonaStore]/[ProviderChainStore] -- these aren't secrets, so plain prefs +
 * JSONObject is fine, following the same serialize()/parse() convention used throughout this
 * codebase.
 *
 * Keys are randomly generated (never derived from the trigger text), mirroring
 * [CustomPersonaStore.newKey]'s reasoning: editing a trigger's wording must not change its
 * identity for any future feature that might reference a snippet by key.
 *
 * Snippets are **opt-in** (see [SnippetsToggle]): the store itself is inert data either way, but
 * [SnippetExpander] is only ever consulted from the shared production path
 * ([SnippetRuntimeSupport.expand]) when the toggle is on, so an empty or freshly-installed prefs
 * file changes zero existing dictation behavior.
 */
object SnippetsStore {
    private const val PREFS_NAME = "ramblr"
    const val KEY_SNIPPETS = "snippets_v1"

    fun serialize(entries: List<SnippetEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("key", entry.key)
                put("trigger", entry.trigger)
                put("expansion", entry.expansion)
            })
        }
        return array.toString()
    }

    /** Returns an empty list if [raw] is blank or malformed, so a corrupted value never crashes
     *  Settings or the dictation pipeline -- worst case snippets are gone, not the app. */
    fun deserialize(raw: String?): List<SnippetEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SnippetEntry(
                    key = obj.getString("key"),
                    trigger = obj.getString("trigger"),
                    expansion = obj.getString("expansion"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun load(prefs: SharedPreferences): List<SnippetEntry> = deserialize(prefs.getString(KEY_SNIPPETS, null))

    fun load(context: Context): List<SnippetEntry> = load(prefs(context))

    fun save(prefs: SharedPreferences, entries: List<SnippetEntry>) {
        prefs.edit().putString(KEY_SNIPPETS, serialize(entries)).apply()
    }

    fun save(context: Context, entries: List<SnippetEntry>) = save(prefs(context), entries)

    fun newKey(): String = "snippet_${java.util.UUID.randomUUID().toString().take(8)}"

    /** Every OTHER snippet's normalized trigger words, for [SnippetEntryValidation.validate]'s
     *  duplicate check. [excludingKey] lets an edit-in-place dialog validate against its own
     *  unmodified state rather than always colliding with itself. */
    fun existingTriggerWordSets(context: Context, excludingKey: String? = null): Set<List<String>> =
        load(context).filter { it.key != excludingKey }.map { SnippetExpander.normalizeWords(it.trigger) }.toSet()

    fun add(context: Context, trigger: String, expansion: String): SnippetEntry {
        val entry = SnippetEntry(key = newKey(), trigger = trigger.trim(), expansion = expansion.trim())
        save(context, load(context) + entry)
        return entry
    }

    fun update(context: Context, key: String, trigger: String, expansion: String) {
        val updated = load(context).map {
            if (it.key == key) it.copy(trigger = trigger.trim(), expansion = expansion.trim()) else it
        }
        save(context, updated)
    }

    fun delete(context: Context, key: String) {
        save(context, load(context).filterNot { it.key == key })
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Master opt-in toggle for Snippets (#248). Off by default: the feature does nothing to a
 * dictation until the user both turns this on AND configures at least one snippet -- consistent
 * with the issue's "opt-in configured exact phrase triggers" design and with every other
 * behavior-changing toggle in this codebase ([SilenceAutoStopToggle], [CompressedUploadToggle])
 * defaulting off.
 */
object SnippetsToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "snippets_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Single call site both production hosts ([DictationRuntime] and [ProcessTextActivity]) use to
 * apply Snippets (#248) to a finished dictation/cleanup result, so the opt-in gate and the
 * store read can never drift between the two hosts. Deliberately trivial -- the actual matching
 * logic lives entirely in [SnippetExpander], which stays a pure, dependency-free function for
 * JVM testing; this object exists only so "is the feature on, and what's configured" is resolved
 * identically everywhere it is consulted.
 */
object SnippetRuntimeSupport {
    fun expand(context: Context, text: String): String {
        if (!SnippetsToggle.isEnabled(context)) return text
        val entries = SnippetsStore.load(context)
        if (entries.isEmpty()) return text
        return SnippetExpander.expand(text, entries)
    }
}
