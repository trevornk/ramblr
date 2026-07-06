package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the unified [ProviderChain] as JSON in the same plain (not encrypted) "phonewhisper"
 * SharedPreferences file used by [CleanupWaterfallStore], under a new key so the legacy
 * "cleanup_waterfall_steps" value is left untouched (still read by the live
 * [CleanupWaterfallExecutor] until Phase 2). Mirrors [CleanupWaterfallStore]'s
 * serialize()/deserialize()/load()/save() pattern exactly: the entries themselves aren't secrets
 * -- credentials live in [ProviderCredentialStore].
 */
object ProviderChainStore {
    private const val PREFS_NAME = "phonewhisper"
    private const val KEY_ENTRIES = "provider_chain_entries"

    fun serialize(chain: ProviderChain): String {
        val array = JSONArray()
        chain.entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("kind", entry.kind.name)
                put("model", entry.model)
                put("baseUrlOverride", entry.baseUrlOverride ?: JSONObject.NULL)
            })
        }
        return array.toString()
    }

    /**
     * Returns null if [raw] is blank (never configured) or malformed, so [load] falls back to
     * [ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY]. A valid empty array is not null -- mirrors
     * [CleanupWaterfallStore.deserialize]'s "explicitly emptied is not never-configured"
     * contract: an explicitly-emptied chain loads as zero entries, not the default.
     */
    fun deserialize(raw: String?): ProviderChain? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            val entries = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ProviderChainEntry(
                    kind = ProviderKind.valueOf(obj.getString("kind")),
                    model = obj.getString("model"),
                    baseUrlOverride = if (obj.isNull("baseUrlOverride")) null else obj.getString("baseUrlOverride"),
                )
            }
            ProviderChain(entries)
        } catch (e: Exception) {
            null
        }
    }

    /** The user's configured chain; [ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY] if nothing
     *  (valid) has ever been saved. [normalizeLocalPosition] repairs any chain persisted before
     *  [ProviderChainEditing.addCloud] existed, where a LOCAL entry could have ended up ahead of
     *  cloud entries -- self-healing on every load rather than requiring a one-shot migration
     *  step or a manual re-add from the user (Trevor hit this live on an existing install). */
    fun load(context: Context): ProviderChain =
        normalizeLocalPosition(deserialize(prefs(context).getString(KEY_ENTRIES, null)) ?: ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY)

    /** Moves any [ProviderKind.LOCAL] entry to the end of [chain], preserving the relative order
     *  of every other entry. No-op (returns [chain] unchanged, same instance) if LOCAL is already
     *  last or absent, so a normal already-correct chain never gets a spurious re-save. Internal
     *  (not private) so it's directly unit-testable as the pure function it is, without needing a
     *  fake Android [Context]/SharedPreferences just to exercise list-reordering logic. */
    internal fun normalizeLocalPosition(chain: ProviderChain): ProviderChain {
        val entries = chain.entries
        val localIndex = entries.indexOfFirst { it.kind == ProviderKind.LOCAL }
        if (localIndex < 0 || localIndex == entries.lastIndex) return chain
        val reordered = entries.toMutableList().apply { add(removeAt(localIndex)) }
        return ProviderChain(reordered)
    }

    fun save(context: Context, chain: ProviderChain) {
        prefs(context).edit().putString(KEY_ENTRIES, serialize(chain)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
