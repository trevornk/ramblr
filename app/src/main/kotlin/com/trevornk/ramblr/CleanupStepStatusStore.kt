package com.trevornk.ramblr

import android.content.Context
import org.json.JSONObject

/** Last-known outcome for one waterfall step, driving the status dot in Settings (#32). */
enum class CleanupStepHealth { UNTESTED, SUCCESS, FAILURE }

/**
 * Tracks [CleanupStepHealth] per waterfall step across app restarts, in the same plain
 * "ramblr" prefs file used for other non-secret settings. Keyed by
 * [keyFor] -- group + model + base URL override -- rather than list position or object identity,
 * since steps are freely reordered/added/removed in Settings and health should follow "this
 * OmniRoute Claude step" across a reorder, not "whatever now sits at index 2".
 *
 * Updated from two places: [WhisperAccessibilityService] after a real cleanup call succeeds
 * (inferred from [CleanupWaterfallCursor]'s last-known-good index -- see
 * `WhisperAccessibilityService.recordWaterfallSuccess`), and MainActivity's per-step "Test"
 * button, which is the only place a *failure* gets attributed to one specific step, since a
 * failed real cleanup call doesn't say which of the attempted steps actually failed.
 */
object CleanupStepStatusStore {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_STATUS = "cleanup_step_status"

    fun keyFor(step: CleanupStep): String = "${step.group.name}|${step.model}|${step.baseUrlOverride ?: ""}"

    fun serialize(statuses: Map<String, CleanupStepHealth>): String =
        JSONObject().apply { statuses.forEach { (key, health) -> put(key, health.name) } }.toString()

    fun parse(raw: String?): Map<String, CleanupStepHealth> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                runCatching { CleanupStepHealth.valueOf(obj.getString(key)) }.getOrDefault(CleanupStepHealth.UNTESTED)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun healthFor(context: Context, step: CleanupStep): CleanupStepHealth =
        parse(prefs(context).getString(KEY_STATUS, null))[keyFor(step)] ?: CleanupStepHealth.UNTESTED

    fun record(context: Context, step: CleanupStep, health: CleanupStepHealth) {
        val store = prefs(context)
        val updated = parse(store.getString(KEY_STATUS, null)).toMutableMap()
        updated[keyFor(step)] = health
        store.edit().putString(KEY_STATUS, serialize(updated)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
