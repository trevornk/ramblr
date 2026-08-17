package com.trevornk.ramblr

/**
 * Edge-to-edge inset math for the Settings screens, kept as a pure function so the interesting
 * part -- which inset wins, and when -- is unit-testable on the JVM without Robolectric or a
 * device. The Android-side plumbing in [BaseSettingsActivity] does nothing but read the real
 * insets, call this, and apply the result as padding.
 *
 * Background: targetSdk 36 (Android 16) removes the ability to opt out of edge-to-edge. The app
 * window is laid out behind the status and navigation bars whether or not it asks to be, and
 * `android:statusBarColor` / `android:navigationBarColor` -- which this app's theme still sets --
 * became no-ops in API 35. Without handling insets, the top of every Settings screen renders
 * underneath the status bar and the last row underneath the gesture pill.
 */
object WindowInsetsMath {

    /** Padding to apply to a scrolling content root, in raw pixels. */
    data class ContentPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Resolve the padding a full-screen scrolling settings list needs.
     *
     * @param barsLeft/Top/Right/Bottom system bar insets (status, navigation, gesture pill).
     * @param cutoutLeft/Right display-cutout insets. Only the horizontal edges matter here: in
     *   portrait the cutout sits inside the status bar so the vertical component is already
     *   covered by [barsTop], but in landscape the notch intrudes from the side where no system
     *   bar exists to account for it.
     * @param imeBottom keyboard inset, or 0 when hidden.
     *
     * The bottom edge takes the *larger* of the navigation bar and the IME rather than their sum:
     * when the keyboard is up it is drawn over the navigation bar, so adding them would push
     * content up by roughly the height of the gesture pill more than necessary. This matters here
     * because Settings has 27 EditText fields.
     */
    fun resolve(
        barsLeft: Int, barsTop: Int, barsRight: Int, barsBottom: Int,
        cutoutLeft: Int = 0, cutoutRight: Int = 0,
        imeBottom: Int = 0,
    ): ContentPadding = ContentPadding(
        left = maxOf(barsLeft, cutoutLeft),
        top = barsTop,
        right = maxOf(barsRight, cutoutRight),
        bottom = maxOf(barsBottom, imeBottom),
    )
}
