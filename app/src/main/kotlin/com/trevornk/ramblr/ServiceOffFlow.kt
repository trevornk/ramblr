package com.trevornk.ramblr

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * The "Turn Ramblr off" row's flow (#254), shared by every screen that shows the row (Privacy,
 * since the settings regroup) and the deep link the invocation mode switch also uses.
 */
internal object ServiceOffFlow {

    fun subtitle(activity: Activity): String = serviceOffRowSubtitleText(
        serviceEnabled = InvocationSecureSettings.isServiceEnabled(activity),
        mode = InvocationServiceMode.currentMode(activity),
    )

    /**
     * "Turn Ramblr off": the app-side master off switch, and in system-controls mode the only
     * one that exists (the OS hides the master toggle on an INVISIBLE_TOGGLE service's own
     * Accessibility page). Confirm first -- this stops dictation everywhere -- then take the
     * action [resolveServiceOffAction] picks, sweeping OS shortcut bindings beforehand when we
     * can, since a bound volume-keys shortcut would otherwise turn the service straight back on
     * (AMS.performAccessibilityShortcutTargetService).
     */
    fun onRowTapped(activity: Activity, refresh: () -> Unit) {
        val action = resolveServiceOffAction(
            serviceEnabled = InvocationSecureSettings.isServiceEnabled(activity),
            serviceConnected = WhisperAccessibilityService.instance != null,
        )
        if (action == ServiceOffAction.ALREADY_OFF) {
            toast(activity, "Ramblr's accessibility service is already off")
            refresh()
            return
        }
        val risk = resolveServiceOffShortcutRisk(
            mode = InvocationServiceMode.currentMode(activity),
            buttonTargetBound = InvocationSecureSettings.isButtonTargetBound(activity),
            volumeKeysBound = InvocationSecureSettings.isVolumeKeysBound(activity),
            canWrite = InvocationSecureSettings.canWrite(activity),
        )
        android.app.AlertDialog.Builder(activity)
            .setTitle("Turn Ramblr off?")
            .setMessage(serviceOffConfirmMessage(risk))
            .setPositiveButton("Turn off") { _, _ -> performServiceOff(activity, action, risk) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performServiceOff(activity: Activity, action: ServiceOffAction, risk: ServiceOffShortcutRisk) {
        // Sweep the OS shortcut bindings BEFORE disabling, in that order and only on the tier
        // that can: raw Secure writes bypass the invisible-toggle sync (#156 memo §3), so this
        // cannot itself trip the "last shortcut off" kill, and once nothing is bound there is
        // nothing left that can resurrect the service after disableSelf().
        if (risk == ServiceOffShortcutRisk.SWEEPABLE) {
            InvocationSecureSettings.setBinding(
                activity, InvocationSecureSettings.KEY_BUTTON_TARGETS, bound = false,
            )
            InvocationSecureSettings.setBinding(
                activity, InvocationSecureSettings.KEY_SHORTCUT_TARGET_SERVICE, bound = false,
            )
        }
        // The guard-rail banner must not fire for a disable the user just asked for: dismissing
        // it here marks this detection handled, and the service clears the dismissal itself the
        // next time it connects, so a genuine future kill still gets the banner.
        InvocationGuardRail.dismissBanner(activity)
        // Same reasoning for the #258 repair path: this off is deliberate, so the stale-component
        // detector must not "recover" from it. Cleared when the service next connects.
        InvocationGuardRail.recordUserTurnedOff(activity)
        if (action == ServiceOffAction.SELF_DISABLE && WhisperAccessibilityService.disableServiceFromApp()) {
            toast(activity, "Ramblr turned off")
            // The OS unbinds asynchronously, so an immediate re-read can still show the old
            // enabled state; refresh on the next resume instead of asserting a state we
            // haven't observed.
            return
        }
        // Listed as enabled with no live instance to disable (crashed / not yet connected):
        // only system Settings can clear the entry. Same deep link the mode switch uses.
        toast(activity, "Opening Ramblr's accessibility settings")
        openServiceDetailsSettings(activity)
    }

    /** Deep-link to the ACTIVE component's own service page (action string public-in-behavior
     *  since API 30 = minSdk; see [InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS]
     *  for why it's a spelled-out string). Falls back to the top-level Accessibility list if an
     *  OEM skin doesn't resolve the details action -- which is also fine for the mode-switch
     *  flow, since only one Ramblr entry is visible there. */
    fun openServiceDetailsSettings(activity: Activity) {
        val component = InvocationServiceMode.activeComponent(activity)
        val details = Intent(InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        try {
            activity.startActivity(details)
        } catch (_: android.content.ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun toast(activity: Activity, message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
