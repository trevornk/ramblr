package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * User-chosen overlay appearance (#43/#53): icon size, border/fill/glyph colors, and whether a
 * custom icon image (see [OverlayIconStore]) should be used instead of the built-in mic glyph.
 * [borderColor]/[fillColor]/[glyphColor] are null when the user hasn't overridden that particular
 * knob -- WhisperAccessibilityService then keeps drawing exactly what it always has (no border,
 * per-state fill colors, plain white glyph), so a fresh install looks pixel-identical to before
 * this feature existed.
 *
 * Product decision (#53, documented here since the issue explicitly asked for one): a custom icon
 * image replaces the ring's entire built-in look -- border, fill, and glyph tint all stop applying
 * once [hasCustomIcon] is true, rather than being layered on top of an arbitrary image as a tint.
 * An arbitrary user photo has no meaningful "fill" the way the built-in circle does, and tinting a
 * photo is more likely to look broken than intentional; a plain swap is simpler to reason about
 * and impossible to get wrong. See WhisperAccessibilityService.applyButtonAppearance.
 *
 * Also per #43's own note: only the IDLE fill color is user-overridable. RECORDING/TRANSCRIBING
 * keep their fixed colors unconditionally -- those are meaningful state feedback, not decoration,
 * and letting a user's chosen idle color collide with (or be confused for) "recording" would trade
 * a cosmetic win for a real usability regression.
 */
data class OverlayAppearance(
    val ringSizeDp: Int,
    val borderColor: Int?,
    val fillColor: Int?,
    val glyphColor: Int?,
    val hasCustomIcon: Boolean
)

object OverlayAppearancePrefs {
    private const val PREFS_NAME = "phonewhisper"
    const val KEY_RING_SIZE_DP = "overlay_ring_size_dp"
    const val KEY_BORDER_COLOR = "overlay_border_color"
    const val KEY_FILL_COLOR = "overlay_fill_color"
    const val KEY_GLYPH_COLOR = "overlay_glyph_color"
    const val KEY_HAS_CUSTOM_ICON = "overlay_has_custom_icon"

    /** Matches the pre-#53 fixed RING_DP constant, so an unconfigured install is unaffected. */
    const val DEFAULT_RING_DP = 56

    // Bounds chosen so the icon can never be sized into unusability: small enough to still be a
    // reliable touch target, large enough to not dominate the screen (#43).
    const val MIN_RING_DP = 40
    const val MAX_RING_DP = 96

    /** SharedPreferences has no "unset int" -- 0 is used as the "no override" sentinel for the
     *  color keys, since every color this app ever picks/stores is opaque (alpha byte set), so a
     *  real color value is never exactly 0. */
    private const val NO_COLOR = 0

    fun clampRingSizeDp(dp: Int): Int = dp.coerceIn(MIN_RING_DP, MAX_RING_DP)

    fun load(prefs: SharedPreferences): OverlayAppearance = OverlayAppearance(
        ringSizeDp = clampRingSizeDp(prefs.getInt(KEY_RING_SIZE_DP, DEFAULT_RING_DP)),
        borderColor = prefs.getInt(KEY_BORDER_COLOR, NO_COLOR).takeIf { it != NO_COLOR },
        fillColor = prefs.getInt(KEY_FILL_COLOR, NO_COLOR).takeIf { it != NO_COLOR },
        glyphColor = prefs.getInt(KEY_GLYPH_COLOR, NO_COLOR).takeIf { it != NO_COLOR },
        hasCustomIcon = prefs.getBoolean(KEY_HAS_CUSTOM_ICON, false)
    )

    fun load(context: Context): OverlayAppearance = load(prefs(context))

    fun setRingSizeDp(prefs: SharedPreferences, dp: Int) {
        prefs.edit().putInt(KEY_RING_SIZE_DP, clampRingSizeDp(dp)).apply()
    }

    fun setBorderColor(prefs: SharedPreferences, color: Int?) = putOrRemove(prefs, KEY_BORDER_COLOR, color)
    fun setFillColor(prefs: SharedPreferences, color: Int?) = putOrRemove(prefs, KEY_FILL_COLOR, color)
    fun setGlyphColor(prefs: SharedPreferences, color: Int?) = putOrRemove(prefs, KEY_GLYPH_COLOR, color)

    fun setHasCustomIcon(prefs: SharedPreferences, has: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_CUSTOM_ICON, has).apply()
    }

    fun setRingSizeDp(context: Context, dp: Int) = setRingSizeDp(prefs(context), dp)
    fun setBorderColor(context: Context, color: Int?) = setBorderColor(prefs(context), color)
    fun setFillColor(context: Context, color: Int?) = setFillColor(prefs(context), color)
    fun setGlyphColor(context: Context, color: Int?) = setGlyphColor(prefs(context), color)
    fun setHasCustomIcon(context: Context, has: Boolean) = setHasCustomIcon(prefs(context), has)

    private fun putOrRemove(prefs: SharedPreferences, key: String, color: Int?) {
        val editor = prefs.edit()
        if (color == null) editor.remove(key) else editor.putInt(key, color)
        editor.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
