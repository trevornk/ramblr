package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * #257: the automation-facing equivalent of #255's in-app "Turn Ramblr off" row. Off by default.
 *
 * WHY THIS EXISTS
 *
 * The #254 reporter automates Ramblr around banking apps with MacroDroid, using an
 * `Accessibility Service -> Disable` action that writes `enabled_accessibility_services`
 * directly. That write is a component-addressed race the automation tool cannot win reliably:
 *
 *  - it must name the exact component, and Ramblr ships two (#156), only one PM-enabled at a
 *    time -- naming the wrong one is a silent no-op in one direction and makes AMS strip Ramblr
 *    entirely in the other (#258);
 *  - it takes effect whenever the OS gets round to it, so a macro triggered by an app launch is
 *    racing that app's own startup.
 *
 * [android.accessibilityservice.AccessibilityService.disableSelf] has neither problem: it is
 * addressed to the live service rather than a component name, it persists through
 * AccessibilityServiceConnection.disableSelf immediately, and the invisible-toggle shortcut sync
 * never runs against it. But only Ramblr can call it -- hence this hook.
 *
 * WHY IT IS OFF BY DEFAULT
 *
 * An exported receiver means any app on the device can silence dictation with no user
 * interaction. That is a small blast radius but a real one, so it is the user's explicit choice.
 * Enabling it is a deliberate act by someone who already runs an automation tool; the default
 * install surface is unchanged.
 *
 * There is deliberately NO enable counterpart. An app cannot add itself back to
 * `enabled_accessibility_services` without WRITE_SECURE_SETTINGS, so a symmetric "on" action
 * would work only on the advanced tier and would hand arbitrary callers the power to switch an
 * accessibility service ON -- a much worse thing to expose than the power to switch it off.
 * Re-enabling stays a user action (Settings, or Ramblr's own one-tap route on the advanced tier).
 */
object AutomationOffHookToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "automation_off_hook_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** What [AutomationOffReceiver] should do with an incoming broadcast. */
enum class AutomationOffOutcome {
    /** The hook setting is off: ignore the broadcast entirely. */
    IGNORED_DISABLED,

    /** Hook on, but no service instance is connected -- nothing to disable. */
    IGNORED_NOT_RUNNING,

    /** Hook on and a live service exists: call disableSelf(). */
    DISABLE,
}

/**
 * Pure decision for an incoming automation off-broadcast, so the policy is unit-testable without
 * a device. The receiver does the two impure things (read the toggle, ask for the live instance)
 * and hands the answers here.
 *
 * Note the deliberate ordering: the toggle is checked FIRST, so a disabled hook is indifferent to
 * whether the service happens to be running. That keeps the off state a flat "this app does not
 * respond to that broadcast at all" rather than something an outside caller can probe for
 * service state.
 */
fun resolveAutomationOff(hookEnabled: Boolean, serviceConnected: Boolean): AutomationOffOutcome = when {
    !hookEnabled -> AutomationOffOutcome.IGNORED_DISABLED
    !serviceConnected -> AutomationOffOutcome.IGNORED_NOT_RUNNING
    else -> AutomationOffOutcome.DISABLE
}
