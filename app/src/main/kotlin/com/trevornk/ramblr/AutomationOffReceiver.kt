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
        when (intent.action) {
            ACTION_TURN_OFF -> handleTurnOff(context)
            ACTION_DIAGNOSTIC -> handleDiagnostic(context)
            else -> return
        }
    }

    private fun handleTurnOff(context: Context) {
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

    /**
     * #254 investigation: read-only diagnostic companion to [handleTurnOff]. Gated behind the
     * SAME [AutomationOffHookToggle] (see [RamblrDiagnosticSnapshot]'s "SECURITY POSTURE" kdoc
     * for why) -- when the hook is off, this is indistinguishable from "nothing here" exactly
     * like the off action's own IGNORED_DISABLED case, via the ordered-broadcast result string:
     * a hook-disabled install returns no result data at all (result code 0, same as
     * [RESULT_HOOK_DISABLED]) rather than a snapshot.
     *
     * Performs NO writes and NO state changes -- every field read is either already
     * unconditionally readable by any app (the `Settings.Secure` accessibility lists, via
     * [InvocationSecureSettings]) or a same-process static read
     * ([WhisperAccessibilityService.instance]). Nothing here dumps SharedPreferences contents,
     * installed-app lists, or any credential/token.
     */
    private fun handleDiagnostic(context: Context) {
        if (!AutomationOffHookToggle.isEnabled(context)) {
            Log.i(TAG, "Automation diagnostic broadcast ignored: hook disabled in settings")
            if (isOrderedBroadcast) setResultCode(RESULT_HOOK_DISABLED)
            return
        }
        val snapshot = RamblrDiagnosticSnapshot(
            serviceInstanceConnected = WhisperAccessibilityService.instance != null,
            activeComponentEnabledInSettings = InvocationSecureSettings.isActiveComponentEnabled(context),
            inactiveComponentEnabledInSettings = InvocationSecureSettings.isInactiveComponentEnabled(context),
            automationOffHookEnabled = true,
            writeSecureSettingsGranted = InvocationSecureSettings.canWrite(context),
        )
        val formatted = formatDiagnosticSnapshot(snapshot)
        Log.i(TAG, "Automation diagnostic: $formatted")
        if (isOrderedBroadcast) {
            setResultCode(RESULT_DIAGNOSTIC_OK)
            setResultData(formatted)
        }
    }

    companion object {
        const val ACTION_TURN_OFF = "com.trevornk.ramblr.action.TURN_OFF"

        /** #254: read-only status snapshot, see [handleDiagnostic]'s kdoc. Reply comes back as
         *  the ordered broadcast's result DATA string (`am broadcast` prints it as `data="..."`),
         *  not a separate extra, so no extras-parsing dependency is needed to read it from a
         *  plain shell/automation-tool broadcast call.
         *
         *  Shipped rather than reverted with the investigation that introduced it: it is the
         *  verify half of the write-then-verify macro pattern that makes an external re-enable
         *  converge. See [RamblrDiagnosticSnapshot] and [automationReEnableVerifyGuidance]. */
        const val ACTION_DIAGNOSTIC = "com.trevornk.ramblr.action.DIAGNOSTIC"

        /** Result code for a successful [ACTION_DIAGNOSTIC] reply; distinct from every
         *  [AutomationOffOutcome]-derived code above so a caller can tell the two actions'
         *  replies apart even if it doesn't track which action it sent. */
        const val RESULT_DIAGNOSTIC_OK = 3

        private const val TAG = "PhoneWhisper"
    }
}
