package com.trevornk.ramblr

import android.app.AppOpsManager
import android.content.Context
import android.os.Build

/**
 * Detects Android 13+'s "Restricted settings" block on sideloaded apps requesting Accessibility
 * (#44 follow-up, Trevor hit this directly re-testing from a fresh GitHub Releases sideload).
 * There is no code fix for the restriction itself -- it's a deliberate OS security feature for
 * any app installed outside the Play Store that requests Accessibility/Device Admin -- but the
 * app CAN detect it and show the exact "Allow restricted settings" unblock steps proactively,
 * instead of leaving the user to hit a confusing dead end in system Settings with no explanation.
 *
 * Uses the same `AppOpsManager` check Android itself uses internally
 * (`android:access_restricted_settings`), confirmed via real-world investigation (not guessed) --
 * this exact op string is what stock Android checks to decide whether to show the "Restricted
 * setting" dialog in the first place.
 */
object RestrictedSettingsCheck {
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    /** True when Android is currently blocking this app from Accessibility settings via the
     *  Restricted Settings feature. Only meaningful on API 33+ (Android 13); always false below
     *  that, since the restriction doesn't exist there. Fails safe to false (assume unrestricted)
     *  on any unexpected AppOpsManager error, rather than showing a misleading warning. */
    fun isBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                OP_ACCESS_RESTRICTED_SETTINGS,
                context.applicationInfo.uid,
                context.packageName,
            )
            mode != AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}
