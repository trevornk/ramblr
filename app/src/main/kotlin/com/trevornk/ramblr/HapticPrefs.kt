package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/** How hard the dictation start/stop tick feels (#385). */
enum class HapticStrength { LIGHT, MEDIUM, STRONG }

/** Snapshot of the user's haptic-feedback settings. [lengthMs] is always within [HapticPrefs]'s bounds when loaded. */
data class HapticSettings(
    val enabled: Boolean,
    val strength: HapticStrength,
    val lengthMs: Int,
)

/**
 * Haptic tick when dictation starts and stops (#385), like Gboard's mic. **Off by default**: it is
 * a new sensory behavior and some users record in quiet/meeting settings, so it must be opted
 * into. Backed by the same "ramblr" prefs file as the other behavior toggles
 * (see [SilenceAutoStopToggle]).
 *
 * Length is only honored on devices with amplitude control (a custom one-shot waveform); devices
 * that only offer the built-in effects use a fixed-length predefined tick/click instead -- see
 * [HapticEffectPlanner]. Stored out-of-range lengths are clamped on read, never trusted.
 */
object HapticPrefs {
    private const val PREFS_NAME = "ramblr"
    const val KEY_ENABLED = "haptics_enabled"
    const val KEY_STRENGTH = "haptics_strength"
    const val KEY_LENGTH_MS = "haptics_length_ms"

    const val DEFAULT_LENGTH_MS = 25
    const val MIN_LENGTH_MS = 10
    const val MAX_LENGTH_MS = 60

    /** Presets offered in the settings picker (short tick -> long buzz). */
    val PRESET_LENGTHS_MS = listOf(10, 25, 40, 60)

    fun clampLengthMs(ms: Int): Int = ms.coerceIn(MIN_LENGTH_MS, MAX_LENGTH_MS)

    fun load(prefs: SharedPreferences): HapticSettings = HapticSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        strength = runCatching { HapticStrength.valueOf(prefs.getString(KEY_STRENGTH, null) ?: "") }
            .getOrDefault(HapticStrength.MEDIUM),
        lengthMs = clampLengthMs(prefs.getInt(KEY_LENGTH_MS, DEFAULT_LENGTH_MS)),
    )

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setStrength(prefs: SharedPreferences, strength: HapticStrength) {
        prefs.edit().putString(KEY_STRENGTH, strength.name).apply()
    }

    fun setLengthMs(prefs: SharedPreferences, ms: Int) {
        prefs.edit().putInt(KEY_LENGTH_MS, clampLengthMs(ms)).apply()
    }

    fun load(context: Context): HapticSettings = load(prefs(context))
    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)
    fun setStrength(context: Context, strength: HapticStrength) = setStrength(prefs(context), strength)
    fun setLengthMs(context: Context, ms: Int) = setLengthMs(prefs(context), ms)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
