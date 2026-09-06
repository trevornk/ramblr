package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * #257: exported receiver that lets an automation app (MacroDroid, Tasker) turn Ramblr's
 * accessibility service off, gated behind [AutomationOffHookToggle] (default off).
 *
 * Usage once enabled in Ramblr's Behavior settings:
 *
 *     am broadcast -a com.trevornk.ramblr.action.TURN_OFF -n com.trevornk.ramblr/.AutomationOffReceiver
 *
 * The explicit `-n` component is what makes this reachable from a shell/automation context on
 * modern Android, where an implicit broadcast to a manifest receiver is not delivered.
 *
 * The disable itself routes through [WhisperAccessibilityService.disableServiceFromApp] --
 * the same disableSelf() path as #255's in-app off switch, and the reason this hook exists at
 * all: an external write to `enabled_accessibility_services` is component-addressed and racy,
 * while disableSelf targets the live service directly. See [AutomationOffHookToggle] for why
 * there is no enable counterpart.
 *
 * Also records the user-intent flag via [InvocationGuardRail] so #258's stale-component repair
 * does not treat an automation-requested off as damage to be undone. [InvocationGuardRail.recordServiceConnected]
 * clears it when the service is next enabled, so a genuine later loss is still detected.
 */
class AutomationOffReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TURN_OFF) return

        val outcome = resolveAutomationOff(
            hookEnabled = AutomationOffHookToggle.isEnabled(context),
            serviceConnected = WhisperAccessibilityService.instance != null,
        )

        var resultCode = resultCodeFor(outcome)

        when (outcome) {
            AutomationOffOutcome.IGNORED_DISABLED ->
                Log.i(TAG, "Automation off-hook broadcast ignored: hook disabled in settings")

            AutomationOffOutcome.IGNORED_NOT_RUNNING ->
                Log.i(TAG, "Automation off-hook broadcast ignored: service not connected")

            AutomationOffOutcome.DISABLE -> {
                // Mark the off as intentional before disabling, so #258's detector reads this
                // flag on the next MainActivity refresh regardless of when onDestroy fires.
                InvocationGuardRail.dismissBanner(context)
                InvocationGuardRail.recordUserTurnedOff(context)
                val disabled = WhisperAccessibilityService.disableServiceFromApp()
                // disableServiceFromApp() returns false if the live instance disappeared
                // between the serviceConnected check above and this call. Only report the
                // accepted-disable code when the invocation actually returned true, so a caller
                // never sees RESULT_DISABLE_REQUESTED for a request that found nothing to
                // disable. `disabled == true` still means only "disableSelf() was invoked", not
                // "the service is now confirmed off" -- see resultCodeFor's KDoc.
                resultCode = resultCodeForDisableAttempt(disabled)
                Log.i(TAG, "Automation off-hook: disableSelf dispatched (success=$disabled)")
            }
        }

        // Only ordered broadcasts return result feedback to the caller.
        if (isOrderedBroadcast) {
            setResultCode(resultCode)
        }
    }

    companion object {
        const val ACTION_TURN_OFF = "com.trevornk.ramblr.action.TURN_OFF"
        private const val TAG = "PhoneWhisper"
    }
}
