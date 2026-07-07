package com.trevornk.ramblr

import android.content.Context
import org.json.JSONObject

/**
 * Remembers the last cleanup persona explicitly selected while a given foreground app package was
 * active (Superwhisper-style per-app "Modes"), so the next dictation in that same app auto-selects
 * the same persona instead of whatever the global "currently selected" persona happens to be.
 *
 * Pure enhancement layered on top of the existing global "cleanup_style" pref (see
 * [CleanupPersonas.currentPersona]): apps with no remembered entry fall back to today's behavior
 * exactly as-is (see [resolvePersona]). Stored as a small packageName -> personaKey JSON map in the
 * same plain "ramblr" prefs file as [CleanupStepStatusStore] -- this isn't a secret, so plain
 * prefs + JSONObject is fine, following the same serialize()/parse() convention.
 */
object PerAppPersonaStore {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_PER_APP_PERSONA = "per_app_cleanup_persona"

    fun serialize(map: Map<String, String>): String =
        JSONObject().apply { map.forEach { (packageName, personaKey) -> put(packageName, personaKey) } }.toString()

    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key -> obj.getString(key) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** The persona key last explicitly selected while [packageName] was the foreground app, if any. */
    fun personaKeyFor(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return parse(prefs(context).getString(KEY_PER_APP_PERSONA, null))[packageName]
    }

    /** Records that [persona] was explicitly selected while [packageName] was the foreground app.
     *  No-op if [packageName] is null/blank -- there's nothing sensible to key the memory on. */
    fun record(context: Context, packageName: String?, persona: CleanupPersona) {
        if (packageName.isNullOrBlank()) return
        val store = prefs(context)
        val updated = parse(store.getString(KEY_PER_APP_PERSONA, null)).toMutableMap()
        updated[packageName] = persona.key
        store.edit().putString(KEY_PER_APP_PERSONA, serialize(updated)).apply()
    }

    /**
     * Resolves which persona should actually be used for a dictation in [packageName]: the
     * per-app remembered persona wins if one exists for this package, otherwise this falls back to
     * [globalPersona] unchanged -- so apps with no per-app memory yet see no behavior change.
     * Resolves through [PersonaRegistry] (#103) so a per-app memory pointing at a custom persona
     * (or a seeded-legacy one) still works, not just built-ins.
     */
    fun resolvePersona(context: Context, packageName: String?, globalPersona: CleanupPersona): CleanupPersona =
        resolvePersona(parse(prefs(context).getString(KEY_PER_APP_PERSONA, null)), packageName, globalPersona) {
            PersonaRegistry.resolve(context, it)
        }

    /** Pure lookup helper split out for unit tests and to keep fallback behavior explicit.
     *  [resolveKey] defaults to built-in-only [CleanupPersonas.fromKey] so existing tests that
     *  don't care about custom personas need no changes; [resolvePersona] (above) passes
     *  [PersonaRegistry.resolve] instead so real dictation also finds custom personas. */
    fun resolvePersona(
        map: Map<String, String>,
        packageName: String?,
        globalPersona: CleanupPersona,
        resolveKey: (String) -> CleanupPersona = CleanupPersonas::fromKey,
    ): CleanupPersona {
        if (packageName.isNullOrBlank()) return globalPersona
        val savedKey = map[packageName] ?: return globalPersona
        return resolveKey(savedKey)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
