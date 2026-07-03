package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user-configured [CleanupWaterfall] (#32) as JSON in the existing plain (not
 * encrypted) "phonewhisper" SharedPreferences file. The steps themselves (group + model + an
 * optional base URL override) aren't secrets -- the credentials they reference live separately in
 * [CleanupCredentialStore]. [serialize]/[deserialize] are pure and Context-free so the round trip
 * is directly unit-testable; [load]/[save] are the thin Context-bound wrappers callers use.
 */
object CleanupWaterfallStore {
    private const val PREFS_NAME = "phonewhisper"
    private const val KEY_STEPS = "cleanup_waterfall_steps"

    fun serialize(waterfall: CleanupWaterfall): String {
        val array = JSONArray()
        waterfall.steps.forEach { step ->
            array.put(JSONObject().apply {
                put("group", step.group.name)
                put("model", step.model)
                put("baseUrlOverride", step.baseUrlOverride ?: JSONObject.NULL)
            })
        }
        return array.toString()
    }

    /**
     * Returns null -- rather than an empty [CleanupWaterfall] -- if [raw] is blank, malformed, or
     * parses to zero steps, so [load] can treat all three cases identically as "nothing
     * configured" and fall back to [CleanupWaterfall.LEGACY_SINGLE_STEP].
     */
    fun deserialize(raw: String?): CleanupWaterfall? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            val steps = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CleanupStep(
                    group = CleanupStepGroup.valueOf(obj.getString("group")),
                    model = obj.getString("model"),
                    baseUrlOverride = if (obj.isNull("baseUrlOverride")) null else obj.getString("baseUrlOverride"),
                )
            }
            if (steps.isEmpty()) null else CleanupWaterfall(steps)
        } catch (e: Exception) {
            null
        }
    }

    /** The user's configured waterfall, or [CleanupWaterfall.LEGACY_SINGLE_STEP] if nothing
     *  (valid) has been saved -- zero behavior change for anyone who hasn't touched Settings. */
    fun load(context: Context): CleanupWaterfall = deserialize(prefs(context).getString(KEY_STEPS, null)) ?: CleanupWaterfall.LEGACY_SINGLE_STEP

    fun save(context: Context, waterfall: CleanupWaterfall) {
        prefs(context).edit().putString(KEY_STEPS, serialize(waterfall)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
