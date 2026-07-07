package com.trevornk.ramblr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user-authored cleanup personas (#103 Style manager) as JSON in the same plain
 * "ramblr" SharedPreferences file as [ProviderChainStore]/[PerAppPersonaStore] -- these
 * aren't secrets, so plain prefs + JSONObject is fine, following the same serialize()/parse()
 * convention used throughout this codebase.
 *
 * Keys are randomly generated (not derived from the title) so renaming a custom persona never
 * changes its key and breaks [PerAppPersonaStore] memories or the saved "cleanup_style" pointer.
 * Built-in personas are never stored here -- see [CleanupPersonas.BUILT_IN]; this store only ever
 * holds custom entries plus the one-time seeded [CleanupPersonas.LEGACY_RETIRED] copies (#103).
 */
object CustomPersonaStore {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_CUSTOM_PERSONAS = "custom_cleanup_personas"
    private const val KEY_LEGACY_SEEDED = "custom_personas_legacy_seeded"

    fun serialize(personas: List<CleanupPersona>): String {
        val array = JSONArray()
        personas.forEach { persona ->
            array.put(JSONObject().apply {
                put("key", persona.key)
                put("title", persona.title)
                put("subtitle", persona.subtitle)
                put("prompt", persona.prompt)
            })
        }
        return array.toString()
    }

    /** Returns an empty list if [raw] is blank or malformed, so a corrupted value never crashes
     *  the Style manager -- worst case the user's custom personas are gone, not the app. */
    fun deserialize(raw: String?): List<CleanupPersona> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CleanupPersona(
                    key = obj.getString("key"),
                    title = obj.getString("title"),
                    subtitle = obj.getString("subtitle"),
                    prompt = obj.getString("prompt"),
                    isBuiltIn = false,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun load(context: Context): List<CleanupPersona> = deserialize(prefs(context).getString(KEY_CUSTOM_PERSONAS, null))

    fun save(context: Context, personas: List<CleanupPersona>) {
        prefs(context).edit().putString(KEY_CUSTOM_PERSONAS, serialize(personas)).apply()
    }

    /** Generates a fresh random key for a new custom persona -- never derived from the title so
     *  renames don't reassign the key (see class doc). */
    fun newKey(): String = "custom_${java.util.UUID.randomUUID().toString().take(8)}"

    fun add(context: Context, title: String, subtitle: String, prompt: String): CleanupPersona {
        val persona = CleanupPersona(key = newKey(), title = title, subtitle = subtitle, prompt = prompt, isBuiltIn = false)
        save(context, load(context) + persona)
        return persona
    }

    fun update(context: Context, key: String, title: String, subtitle: String, prompt: String) {
        val updated = load(context).map {
            if (it.key == key) it.copy(title = title, subtitle = subtitle, prompt = prompt) else it
        }
        save(context, updated)
    }

    fun delete(context: Context, key: String) {
        save(context, load(context).filterNot { it.key == key })
    }

    fun fromKey(context: Context, key: String): CleanupPersona? = load(context).firstOrNull { it.key == key }

    /**
     * One-time migration (#103): seeds [CleanupPersonas.LEGACY_RETIRED] (Gangster/Smart/Teacher)
     * into the custom store so users who were using one of these as a global/per-app persona keep
     * seeing it as an editable/deletable entry in the new Style manager instead of it silently
     * disappearing when they were demoted out of [CleanupPersonas.BUILT_IN]. Runs at most once per
     * install, gated by [KEY_LEGACY_SEEDED] -- safe to call unconditionally on every app start.
     */
    fun ensureLegacySeeded(context: Context) {
        val store = prefs(context)
        if (store.getBoolean(KEY_LEGACY_SEEDED, false)) return
        val existing = load(context)
        val toSeed = CleanupPersonas.LEGACY_RETIRED.filter { legacy -> existing.none { it.key == legacy.key } }
        if (toSeed.isNotEmpty()) save(context, existing + toSeed)
        store.edit().putBoolean(KEY_LEGACY_SEEDED, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
