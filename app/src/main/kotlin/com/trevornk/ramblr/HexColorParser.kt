package com.trevornk.ramblr

/**
 * Parses a user-typed hex color string into an opaque ARGB int (#59's custom overlay color
 * picker hex field). Pulled out as a standalone, Context-free, Android-framework-free object
 * (rather than android.graphics.Color.parseColor, which isn't available/mockable in this
 * project's plain JVM unit tests -- no Robolectric) so the parsing itself is directly
 * unit-testable, matching how CleanupWaterfallStore.deserialize/PostProcessor.interpolateVocabulary
 * keep their pure logic separate from any Android-bound caller.
 */
object HexColorParser {
    /**
     * Accepts a 6-digit RRGGBB hex string, with or without a leading '#', case-insensitive.
     * Returns null for anything else (wrong length, non-hex characters, blank) rather than
     * throwing, since callers typically run this on every keystroke of a text field. The
     * returned int always has the alpha byte forced to 0xFF -- every color this app stores is
     * opaque (see OverlayAppearancePrefs's NO_COLOR sentinel note).
     */
    fun parse(raw: String): Int? {
        val trimmed = raw.trim().removePrefix("#")
        if (trimmed.length != 6 || trimmed.any { it !in "0123456789abcdefABCDEF" }) return null
        val rgb = trimmed.toIntOrNull(16) ?: return null
        return rgb or 0xFF000000.toInt()
    }
}
