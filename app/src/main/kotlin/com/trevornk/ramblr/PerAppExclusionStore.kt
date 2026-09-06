package com.trevornk.ramblr

import android.content.Context
import org.json.JSONArray

/**
 * #256: the explicit, user-picked set of app packages where Ramblr suppresses its own behavior
 * (no new recording, no ring, no final-insertion) — the "per-app exclusion list" the issue asks
 * for. Mirrors [PerAppPersonaStore]'s shape deliberately: same "ramblr" SharedPreferences file,
 * same serialize()/parse() convention, same null/blank-safe idioms. Unlike the persona store this
 * is a plain set (membership only, no associated value), stored as a JSON array of package names
 * rather than an object map.
 *
 * **Exact package names only, by design choice for #256 — no prefix/wildcard matching.** A
 * wildcard or prefix scheme is more convenient to populate but far easier to get wrong in a way
 * that silently widens (or narrows) what's actually covered; an explicit list is the predictable,
 * auditable choice the issue's own "open questions" section flagged as the safer default.
 *
 * **What this store does NOT do:** it holds no logic about *when* exclusion is checked, nor does
 * it touch the accessibility event stream — it's a pure prefs-backed set, read on demand by
 * whichever call site (ring visibility, recording start, injection, IME) needs to know "is this
 * package excluded right now."
 */
object PerAppExclusionStore {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_EXCLUDED_PACKAGES = "per_app_excluded_packages"

    fun serialize(packages: Set<String>): String =
        JSONArray().apply { packages.sorted().forEach { put(it) } }.toString()

    fun parse(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i, null) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** The full set of excluded package names, empty (never null) when none are configured. */
    fun exclusions(context: Context): Set<String> = parse(prefs(context).getString(KEY_EXCLUDED_PACKAGES, null))

    /** Whether [packageName] is on the exclusion list. Null/blank package names are never
     *  considered excluded — there's nothing to match, and treating "unknown" as "excluded" would
     *  invert the fail-safe direction this feature needs (see individual call sites' honesty
     *  notes about what "legitimately known" foreground identity actually means here). */
    fun isExcluded(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName in exclusions(context)
    }

    fun setExclusions(context: Context, packages: Set<String>) {
        prefs(context).edit().putString(KEY_EXCLUDED_PACKAGES, serialize(packages)).apply()
    }

    fun setExcluded(context: Context, packageName: String, excluded: Boolean) {
        val current = exclusions(context).toMutableSet()
        if (excluded) current.add(packageName) else current.remove(packageName)
        setExclusions(context, current)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
