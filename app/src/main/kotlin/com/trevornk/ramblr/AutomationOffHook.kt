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

/**
 * `am broadcast` sends an ordered broadcast and prints back `result=<code>`, but
 * [AutomationOffReceiver] never called [android.content.BroadcastReceiver.setResultCode], so
 * every outcome silently kept the platform default of 0. This mapping only takes effect when the
 * delivery is actually an ordered broadcast ([android.content.BroadcastReceiver.isOrderedBroadcast]);
 * for a normal broadcast there is no result slot to set, and the receiver's other behavior is
 * unaffected either way.
 *
 * [AutomationOffOutcome.IGNORED_DISABLED] keeps result code 0 deliberately: that is also
 * Android's own default for a mistyped action or no registered receiver, so a disabled hook is
 * not distinguishable from "nothing here" -- see [resolveAutomationOff]'s ordering note. This
 * protects service-*state* disclosure (running vs. not) while the hook is opted out; it says
 * nothing about whether Ramblr is installed, which any caller can already determine other ways
 * (e.g. querying installed packages).
 *
 * [RESULT_DISABLE_REQUESTED] means only that [WhisperAccessibilityService.disableServiceFromApp]
 * returned `true`: a live instance existed and `disableSelf()` was invoked. It is not a
 * confirmation that the service has finished tearing down.
 */
fun resultCodeFor(outcome: AutomationOffOutcome): Int = when (outcome) {
    AutomationOffOutcome.IGNORED_DISABLED -> RESULT_HOOK_DISABLED
    AutomationOffOutcome.IGNORED_NOT_RUNNING -> RESULT_NOT_RUNNING
    AutomationOffOutcome.DISABLE -> RESULT_DISABLE_REQUESTED
}

/**
 * Result code for the [AutomationOffOutcome.DISABLE] path once
 * [WhisperAccessibilityService.disableServiceFromApp]'s actual return value is known.
 * [disabled] can be false if the live instance disappeared between the receiver's
 * serviceConnected check and the call itself; in that case the caller gets
 * [RESULT_NOT_RUNNING] rather than a falsely-accepted [RESULT_DISABLE_REQUESTED].
 */
fun resultCodeForDisableAttempt(disabled: Boolean): Int =
    if (disabled) RESULT_DISABLE_REQUESTED else RESULT_NOT_RUNNING

/** Matches Android's own default ordered-broadcast result code (no receiver set one). */
const val RESULT_HOOK_DISABLED = 0

/** Hook is enabled, but there is no live service instance to disable. */
const val RESULT_NOT_RUNNING = 2

/** disableSelf() was invoked on a live instance; see [resultCodeFor]'s KDoc for what this does
 *  NOT guarantee. */
const val RESULT_DISABLE_REQUESTED = 1

/**
 * `UserHandle.getIdentifier()` is `@SystemApi`-only and absent from the public SDK stub an
 * ordinary app compiles against, but [android.os.Process.myUid] is public, and every Android
 * build since multi-user support landed (API 17) partitions uids as
 * `userId * PER_USER_RANGE + appId`, with PER_USER_RANGE fixed at 100000 -- the same constant
 * `UserHandle.PER_USER_RANGE` uses internally. This lets an app derive its own hosting user id
 * without any permission.
 */
const val PER_USER_RANGE = 100000

/** The numeric Android user id hosting the process with the given [uid]. */
fun userIdForUid(uid: Int): Int = uid / PER_USER_RANGE

/**
 * Explicitly targets Ramblr's hosting user. Implicit/current-user selection can require
 * cross-user privileges unavailable to an ordinary app caller, even on the primary user.
 * Derive [userId] from Ramblr's uid rather than assuming user 0.
 */
fun automationOffHookCommand(packageName: String, userId: Int): String =
    "am broadcast -a ${AutomationOffReceiver.ACTION_TURN_OFF} " +
        "-n $packageName/.AutomationOffReceiver --user $userId"

/**
 * #254: the readable status snapshot behind [AutomationOffReceiver.ACTION_DIAGNOSTIC].
 *
 * WHY THIS IS A SHIPPED FEATURE AND NOT A ONE-OFF DIAGNOSTIC
 *
 * This began as an investigation-only action in the `diagnostic-254-1` prerelease, to find out
 * why an external re-enable sometimes didn't take. It earned permanent status by answering that
 * question: the reporter's two snapshots showed `instance_connected=true,
 * active_component_enabled=true` while working and `active_component_enabled=false,
 * inactive_component_enabled=false` after a failed restore -- Ramblr absent from
 * `enabled_accessibility_services` entirely. That is a write that did not persist, not a service
 * that failed to bind, and not #258's stale-component failure (which would show the INACTIVE
 * component listed). It also cleared #258's fix: no stale entry remained.
 *
 * A write to `enabled_accessibility_services` is not reliably durable from outside the app.
 * [InvocationServiceMode.verifySettled] exists because we device-observed AMS asynchronously
 * re-persisting its in-memory state AFTER our own writes landed and clobbering them -- Ramblr
 * fights that with a poll-and-repair loop internally. An automation tool writing the same key
 * has no such loop and no way to see the outcome, because `settings get` and `dumpsys` are both
 * blocked for an ordinary same-user shell (`INTERACT_ACROSS_USERS` / `DUMP`).
 *
 * So this action is the verify half of a write-then-verify pair that an automation user cannot
 * otherwise build: enable, wait, read this snapshot, and enable again if it didn't stick. That
 * makes an unreliable external write into a convergent one. Removing this action would take that
 * capability away, which is why it ships rather than being reverted with the investigation.
 *
 * It reports no SharedPreferences keys, no installed-app lists, and no credentials -- nothing not
 * already readable by any app via the same `Settings.Secure` calls (see [InvocationSecureSettings]'s
 * class kdoc: those reads require no permission).
 *
 * SECURITY POSTURE: deliberately gated behind the SAME [AutomationOffHookToggle] as the
 * destructive TURN_OFF action, default off, rather than exposed unconditionally. Reusing the
 * existing opt-in gate (rather than inventing a separate always-on toggle) means enabling
 * automation control at all is the one decision the user already has to make; there's no new
 * consent surface to reason about, and the blast radius is unchanged from what #257 shipped.
 *
 * Field choices, and why each is safe/useful:
 *  - [serviceInstanceConnected]: [WhisperAccessibilityService.instance] != null -- the same
 *    signal MainActivity's own "acc" status row already uses; not a secret, and the whole point
 *    of the diagnostic.
 *  - [activeComponentEnabledInSettings] / [inactiveComponentEnabledInSettings]: distinguishes a
 *    genuine "not enabled" from #258's stale-wrong-component failure mode -- exactly the
 *    distinction the parent asked to preserve (coalesced/never-took vs. genuine toggle).
 *  - [automationOffHookEnabled]: always true when this snapshot could be produced at all (the
 *    action is gated on it), included anyway so a MacroDroid/Tasker parser has one field it can
 *    assert on to confirm the broadcast reached a real, opted-in install rather than a stale
 *    cached result.
 *  - [writeSecureSettingsGranted]: whether the advanced tier (in-app self-heal via
 *    `reEnableService()`) is even available on this install, so the reporter knows which recovery
 *    path applies without pulling `dumpsys package`.
 */
data class RamblrDiagnosticSnapshot(
    val serviceInstanceConnected: Boolean,
    val activeComponentEnabledInSettings: Boolean,
    val inactiveComponentEnabledInSettings: Boolean,
    val automationOffHookEnabled: Boolean,
    val writeSecureSettingsGranted: Boolean,
)

/** Stable, MacroDroid/Tasker-parseable `key=value;key=value` encoding, deliberately not JSON --
 *  no dependency needed to read it back out of a broadcast result string in either tool. */
fun formatDiagnosticSnapshot(s: RamblrDiagnosticSnapshot): String =
    "instance_connected=${s.serviceInstanceConnected};" +
        "active_component_enabled=${s.activeComponentEnabledInSettings};" +
        "inactive_component_enabled=${s.inactiveComponentEnabledInSettings};" +
        "automation_off_hook_enabled=${s.automationOffHookEnabled};" +
        "write_secure_settings_granted=${s.writeSecureSettingsGranted}"

/**
 * The status-query counterpart to [automationOffHookCommand], for an automation tool's shell
 * action. Targets Ramblr's hosting user explicitly for the same reason the off command does --
 * implicit/current-user selection requires cross-user privileges an ordinary app caller does not
 * have, and assuming user 0 is wrong on a secondary profile.
 *
 * `am broadcast` prints the reply as `result=<code>` plus `data="<snapshot>"`; see
 * [AutomationOffReceiver.ACTION_DIAGNOSTIC]. Reading `data` is what makes the verify-and-retry
 * pattern in [automationReEnableVerifyGuidance] possible without any permission.
 */
fun automationDiagnosticCommand(packageName: String, userId: Int): String =
    "am broadcast -a ${AutomationOffReceiver.ACTION_DIAGNOSTIC} " +
        "-n $packageName/.AutomationOffReceiver --user $userId"

/**
 * The user-facing recipe for making an external re-enable actually stick (#254).
 *
 * WHY THIS EXISTS RATHER THAN A CODE FIX
 *
 * Ramblr cannot fix this one from the inside. The failing step is an automation tool's own write
 * to `enabled_accessibility_services`, performed while Ramblr's service is NOT running -- there
 * is no Ramblr process alive at that moment to detect the failure, retry it, or even observe it.
 * A re-enable broadcast receiver is not an option either: adding itself back to that list needs
 * WRITE_SECURE_SETTINGS, and exposing an app-callable "turn an accessibility service ON" action
 * to arbitrary callers is a far worse capability than the "off" one (see [AutomationOffHookToggle]).
 *
 * What Ramblr CAN do is make the write verifiable, which is the whole point of promoting
 * [AutomationOffReceiver.ACTION_DIAGNOSTIC] to a shipped action. The macro becomes convergent
 * rather than hopeful:
 *
 *  1. Enable action (the tool's own Accessibility Service -> Enable).
 *  2. Wait ~2 seconds -- long enough for a late AMS re-persist to land and clobber the write if
 *     it is going to (observed ~1s in [InvocationServiceMode.verifySettled]'s device testing).
 *  3. Diagnostic broadcast; read `active_component_enabled` out of the result data.
 *  4. If false, enable again and repeat. Two or three attempts is plenty in practice.
 *
 * This is the same settle-verify-repair shape [InvocationServiceMode.verifySettled] runs
 * internally for Ramblr's own mode switch, for exactly the same reason, just expressed in the
 * automation tool instead of in Kotlin.
 */
fun automationReEnableVerifyGuidance(): String =
    "If an external re-enable sometimes doesn't take, add a verify step after it: wait about " +
        "2 seconds, send the diagnostic broadcast, and read active_component_enabled from the " +
        "result data. If it is false, run the enable action again. Android can discard a write " +
        "to the accessibility list shortly after it lands, and re-checking is the only reliable " +
        "way to tell -- Ramblr isn't running at that moment, so it cannot retry for you."
