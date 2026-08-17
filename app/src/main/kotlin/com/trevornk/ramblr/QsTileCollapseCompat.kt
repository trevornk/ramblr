package com.trevornk.ramblr

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Which `TileService.startActivityAndCollapse` overload is safe to call on [sdkInt].
 *
 * Both overloads are traps, in opposite directions, and the safe window for each is disjoint:
 *
 *  - `startActivityAndCollapse(Intent)` exists from API 24 but was deprecated in API 34, where it
 *    throws [UnsupportedOperationException] *unconditionally*. Safe only below 34.
 *  - `startActivityAndCollapse(PendingIntent)` was *added* in API 34. Calling it below that throws
 *    [NoSuchMethodError]. Safe only from 34 up.
 *
 * With `minSdk 30` the app spans both regimes, so the branch is mandatory. #136 fixed the first
 * trap by switching wholesale to the PendingIntent overload, which silently introduced the second:
 * the quick-settings "tap to enable" affordance then crashed on Android 11-13 instead of Android
 * 14+. Lint only surfaced it once the project moved to a toolchain that could see the API-34
 * floor (`NewApi`, reported against the unguarded call).
 *
 * Extracted as a pure function of the SDK int -- rather than reading [Build.VERSION.SDK_INT]
 * inline -- purely so the boundary is unit-testable on the JVM without Robolectric, matching
 * [shouldRestoreIconBeforeToggle]'s approach in this same feature.
 *
 * [ChecksSdkIntAtLeast] is what keeps that extraction free: lint's `NewApi` check only recognises
 * an *inline* `SDK_INT` comparison as a guard, so without this annotation it cannot see through
 * the helper and re-reports the API-34 call as unguarded. The annotation tells lint that a `true`
 * return means "SDK_INT >= 34", restoring the analysis at the call site.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun shouldUsePendingIntentCollapse(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
