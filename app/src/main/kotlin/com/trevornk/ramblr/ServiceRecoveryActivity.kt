package com.trevornk.ramblr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * #254: no-UI trampoline reached by tapping [ServiceRecoveryNotifications]'s notification.
 *
 * Deliberately an Activity rather than a BroadcastReceiver, for the same reason as
 * [RestoreIconActivity]: a notification whose PendingIntent starts an Activity gets the normal
 * immediate shade-collapse animation, and -- more importantly here -- the base-tier path needs to
 * start the system Settings Activity, which a background broadcast cannot reliably do.
 *
 * The recovery logic is intentionally identical to MainActivity's own banner tap
 * (`onServiceKilledBannerTapped`): advanced tier re-enables in place with a raw Secure write, base
 * tier deep-links to the service's own Accessibility page where the enable switch is one tap away.
 * The two entry points differ only in where the user tapped, not in what recovery means.
 */
class ServiceRecoveryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceRecoveryNotifications.cancel(this)

        // Advanced tier (WRITE_SECURE_SETTINGS granted): true one-tap recovery. Raw Secure writes
        // bypass the invisible-toggle sync, which is exactly what is wanted -- restore
        // enabled_accessibility_services without the OS re-syncing shortcuts (#156 memo §3).
        if (InvocationSecureSettings.reEnableService(this)) {
            Toast.makeText(this, "Ramblr re-enabled", Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(0, 0)
            return
        }

        // Base tier: the OS offers no app-side re-enable, so the best available path is the
        // service's own Settings page. Targets whichever component the current #156 mode says
        // should be active, so the user lands on the right one of the two entries.
        val details = Intent(InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(
                Intent.EXTRA_COMPONENT_NAME,
                InvocationServiceMode.activeComponent(this).flattenToString(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(details)
        } catch (_: android.content.ActivityNotFoundException) {
            // OEM skins that don't export the per-service details page (the action string is
            // @hide in the SDK even though AOSP has exported it since API 30 = minSdk).
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
